package spinal.core

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets.UTF_8
import scala.sys.process.{Process, ProcessLogger}
import morphhdl.{MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.HdlInt
import morphhdl.examples.OptionalPipeline
import org.scalatest.funsuite.AnyFunSuite

class NativeScopedLegalityTests extends AnyFunSuite {
  private class ConcretePipeline(width: Int, enabled: Boolean) extends Component {
    val dataIn = in Bits(width bits)
    val dataOut = out Bits(width bits)
    if (enabled) dataOut := RegNext(dataIn) else dataOut := dataIn
  }

  // Both designs have unconstrained power-up state. Sequential equivalence must
  // establish agreement after a clock edge; no reset or initial value is added.
  private def qualifyHardware(directory: Path, mode: Int, width: Int): Unit = {
    val strict = run(directory, Seq("iverilog", "-g2001", "-DSYNTHESIS", "-s", "OptionalPipeline",
      s"-POptionalPipeline.WIDTH=$width", s"-POptionalPipeline.USE_PIPELINE=$mode",
      "-o", "strict.vvp", "OptionalPipeline.v"))
    assert(strict._1 == 0, strict._2)
    val lint = run(directory, Seq("verilator", "--lint-only", "-Wno-fatal", "--top-module", "OptionalPipeline",
      s"-GWIDTH=$width", s"-GUSE_PIPELINE=$mode", "OptionalPipeline.v"))
    assert(lint._1 == 0, lint._2)
    val referenceDir = directory.resolve(s"reference-$mode-$width")
    SpinalVerilog(SpinalConfig(targetDirectory = referenceDir.toString, headerWithDate = false)) {
      new ConcretePipeline(width, mode == 1)
    }
    val clocks = if (mode == 1) ", .clk(clk), .reset(reset)" else ""
    Files.write(directory.resolve("gold.v"), s"""module gold(input clk, input reset,
      |input [$width-1:0] dataIn, output [$width-1:0] dataOut);
      |ConcretePipeline reference(.dataIn(dataIn), .dataOut(dataOut)$clocks);
      |endmodule
      |""".stripMargin.getBytes(UTF_8))
    val script = s"""read_verilog -DSYNTHESIS OptionalPipeline.v
      |chparam -set WIDTH $width -set USE_PIPELINE $mode OptionalPipeline
      |hierarchy -top OptionalPipeline
      |proc; flatten; opt; check -assert
      |rename OptionalPipeline gate
      |design -stash candidate
      |read_verilog ${referenceDir.resolve("ConcretePipeline.v")} gold.v
      |hierarchy -top gold
      |proc; flatten; opt; check -assert
      |design -copy-from candidate -as gate gate
      |equiv_make gold gate equiv
      |hierarchy -top equiv
      |equiv_simple
      |equiv_induct -seq 2
      |equiv_status -assert
      |""".stripMargin
    Files.write(directory.resolve("equivalence.ys"), script.getBytes(UTF_8))
    val proof = run(directory, Seq("yosys", "-s", "equivalence.ys"))
    Files.write(directory.resolve(s"formal-$mode-$width.log"), proof._2.getBytes(UTF_8))
    assert(proof._1 == 0 && proof._2.contains("Equivalence successfully proven"), proof._2)
    val synthesis = run(directory, Seq("yosys", "-p", s"read_verilog -DSYNTHESIS OptionalPipeline.v; chparam -set WIDTH $width -set USE_PIPELINE $mode OptionalPipeline; synth -top OptionalPipeline; check -assert"))
    assert(synthesis._1 == 0, synthesis._2)
  }

  private def run(dir: Path, command: Seq[String]): (Int, String) = {
    val log = new StringBuilder
    val code = Process(command, dir.toFile).!(ProcessLogger(
      line => log.append(line).append('\n'), line => log.append(line).append('\n')))
    (code, log.toString)
  }

  for (split <- Seq(false, true); defaultMode <- Seq(0, 1))
    test(s"OptionalPipeline retains scoped legality and latency, split=$split, default=$defaultMode") {
      val directory = Files.createTempDirectory("scoped-legality-pipeline-")
      MorphVerilog(MorphWireAssignmentPasses(SpinalConfig(
        targetDirectory = directory.toString, oneFilePerComponent = split,
        headerWithDate = false), enabled = false)) {
        new OptionalPipeline(HdlInt.param("WIDTH", 16, 1, 64).asElabInt,
          HdlInt.param("USE_PIPELINE", defaultMode, 0, 1).asElabInt)
      }
      val source = directory.resolve("OptionalPipeline.v")
      val rtl = new String(Files.readAllBytes(source), UTF_8)
      info(s"Scoped legality artifact: $directory")
      assert(rtl.contains("G_PARAMETER_LEGALITY_ACTIVE"), rtl)
      assert(rtl.contains("initial $fatal(1, \"%s\", \"Pipeline mode requires WIDTH >= 8\");"), rtl)
      for (mode <- Seq(0, 1); width <- Seq(1, 4, 7, 8, 16, 64)) {
        Files.write(directory.resolve("tb.v"), s"""module tb;
          |reg clk = 0;
          |reg [$width-1:0] source;
          |wire [$width-1:0] result;
          |OptionalPipeline #(.WIDTH($width), .USE_PIPELINE($mode)) dut
          |(.clk(clk), .dataIn(source), .dataOut(result));
          |initial begin
          |source = {$width{1'b1}}; #1;
          |if ($mode == 0 && result !== source) $$fatal(1,"bypass latency");
          |clk = 1; #1; if (result !== source) $$fatal(1,"first edge");
          |clk = 0; source = 0; #1;
          |if ($mode == 1 && result !== {$width{1'b1}}) $$fatal(1,"pipeline latency");
          |if ($mode == 0 && result !== source) $$fatal(1,"bypass latency");
          |clk = 1; #1; if (result !== source) $$fatal(1,"second edge");
          |clk = 0; source = {$width{1'bx}}; #1; clk = 1; #1;
          |if (result !== source) $$fatal(1,"X propagation");
          |clk = 0; source = {$width{1'bz}}; #1; clk = 1; #1;
          |if (result !== source) $$fatal(1,"Z propagation");
          |$$display("SCOPED_PIPELINE_PASS"); $$finish;
          |end
          |endmodule
          |""".stripMargin.getBytes(UTF_8))
        val compiled = run(directory, Seq("iverilog", "-g2012", "-s", "tb", "-o", "test.vvp",
          source.toString, "tb.v"))
        assert(compiled._1 == 0, compiled._2)
        val simulated = run(directory, Seq("vvp", "test.vvp"))
        Files.write(directory.resolve(s"mode-$mode-width-$width.log"), simulated._2.getBytes(UTF_8))
        if (mode == 1 && width < 8) {
          assert(simulated._1 != 0 && simulated._2.contains("Pipeline mode requires WIDTH >= 8"), simulated._2)
          assert(simulated._2.split("Pipeline mode requires WIDTH >= 8", -1).length == 2, simulated._2)
        } else {
          assert(simulated._1 == 0 && simulated._2.contains("SCOPED_PIPELINE_PASS"), simulated._2)
          qualifyHardware(directory, mode, width)
        }
      }
    }
  private def emit(name: String)(body: => Component): (Path, String) = {
    val directory = Files.createTempDirectory("scoped-legality-" + name + "-")
    MorphVerilog(MorphWireAssignmentPasses(SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false), enabled = false)) {
      val result = body
      result.setDefinitionName(name)
      result
    }
    directory -> new String(Files.readAllBytes(directory.resolve(name + ".v")), UTF_8)
  }
  private def marker(source: Bool, name: String): Unit = {
    val retained = Bool().setName(name).dontSimplifyIt()
    retained := source
  }
  private def diagnosticCase(dir: Path, name: String, parameters: Seq[(String, Int)],
      expected: Option[String]): Unit = {
    val compiled = run(dir, Seq("iverilog", "-g2012", "-s", name, "-o", "diagnostic.vvp") ++
      parameters.map { case (parameter, value) => s"-P$name.$parameter=$value" } :+ (name + ".v"))
    assert(compiled._1 == 0, compiled._2)
    val simulated = run(dir, Seq("vvp", "diagnostic.vvp"))
    expected match {
      case Some(message) =>
        assert(simulated._1 != 0 && simulated._2.contains(message), simulated._2)
        assert(simulated._2.split(java.util.regex.Pattern.quote(message), -1).length == 2, simulated._2)
      case None => assert(simulated._1 == 0, simulated._2)
    }
  }

  test("nested else-if owners retain full priority and defer a false requirement only under activation") {
    val (dir, rtl) = emit("NestedLegality")(new Component {
      val mode: ElabInt = HdlInt.param("MODE", 0, 0, 3).asElabInt
      val width: ElabInt = HdlInt.param("WIDTH", 8, 1, 16).asElabInt
      val source = in Bool()
      val result = out Bool()
      result := source
      if (mode > 0) {
        if (mode == 1) {
          require(width >= 8, "FIRST_RULE")
          marker(source, "firstMarker")
        } else if (mode == 2) {
          require(width >= 4, "SECOND_RULE")
          marker(source, "secondMarker")
        } else {
          require(width >= 32, "INACTIVE_FALSE_RULE")
          marker(source, "thirdMarker")
        }
      } else marker(source, "bypassMarker")
    })
    assert(rtl.split("initial \\$fatal", -1).length == 4, rtl)
    for (mode <- 0 to 3; width <- Seq(1, 4, 8, 16)) {
      val failure = if (mode == 1 && width < 8) Some("FIRST_RULE")
        else if (mode == 2 && width < 4) Some("SECOND_RULE")
        else if (mode == 3) Some("INACTIVE_FALSE_RULE") else None
      diagnosticCase(dir, "NestedLegality", Seq("MODE" -> mode, "WIDTH" -> width), failure)
    }
    val lostPriority = rtl.replace("if (!(((MODE) == (1))))", "if (1)")
    assert(lostPriority != rtl)
    Files.write(dir.resolve("NestedLegality.v"), lostPriority.getBytes(UTF_8))
    diagnosticCase(dir, "NestedLegality", Seq("MODE" -> 1, "WIDTH" -> 8), Some("INACTIVE_FALSE_RULE"))
    Files.write(dir.resolve("NestedLegality.v"), rtl.getBytes(UTF_8))
  }

  test("ordinary typed Boolean match preserves scoped requirements") {
    val (dir, _) = emit("MatchedLegality")(new Component {
      val mode: ElabInt = HdlInt.param("MODE", 0, 0, 1).asElabInt
      val width: ElabInt = HdlInt.param("WIDTH", 8, 1, 16).asElabInt
      val source = in Bool()
      val result = out Bool()
      result := source
      (mode == 1) match {
        case true =>
          require(width >= 8, "MATCH_RULE")
          marker(source, "matchedMarker")
        case false => marker(source, "unmatchedMarker")
      }
    })
    diagnosticCase(dir, "MatchedLegality", Seq("MODE" -> 0, "WIDTH" -> 4), None)
    diagnosticCase(dir, "MatchedLegality", Seq("MODE" -> 1, "WIDTH" -> 4), Some("MATCH_RULE"))
    diagnosticCase(dir, "MatchedLegality", Seq("MODE" -> 1, "WIDTH" -> 8), None)
  }

  test("finite generate ownership keeps zero iterations inactive and retains the loop binding") {
    val (dir, rtl) = emit("LoopLegality")(new Component {
      val count: ElabInt = HdlInt.param("COUNT", 2, 0, 3).asElabInt
      val width: ElabInt = HdlInt.param("WIDTH", 8, 1, 16).asElabInt
      val source = in Bool()
      val result = out Bool()
      result := source
      ElabFiniteRange.foreach(count, "legality checks") { _ =>
        require(width >= 8, "LOOP_RULE")
        marker(source, "loopMarker")
      }
    })
    assert(rtl.contains("for (") && rtl.contains("G_PARAMETER_LEGALITY_ACTIVE"), rtl)
    diagnosticCase(dir, "LoopLegality", Seq("COUNT" -> 0, "WIDTH" -> 4), None)
    diagnosticCase(dir, "LoopLegality", Seq("COUNT" -> 1, "WIDTH" -> 4), Some("LOOP_RULE"))
    for (count <- 0 to 3)
      diagnosticCase(dir, "LoopLegality", Seq("COUNT" -> count, "WIDTH" -> 8), None)
    val staleLoop = rtl.replace(" < (COUNT);", " < (1);")
    assert(staleLoop != rtl)
    Files.write(dir.resolve("LoopLegality.v"), staleLoop.getBytes(UTF_8))
    diagnosticCase(dir, "LoopLegality", Seq("COUNT" -> 0, "WIDTH" -> 4), Some("LOOP_RULE"))
    Files.write(dir.resolve("LoopLegality.v"), rtl.getBytes(UTF_8))
  }

  test("a rejected second branch rolls back the first obligation and exact hardware before retry") {
    val (dir, rtl) = emit("RetriedLegality")(new Component {
      val mode: ElabInt = HdlInt.param("MODE", 0, 0, 1).asElabInt
      val width: ElabInt = HdlInt.param("WIDTH", 8, 1, 16).asElabInt
      val source = in Bool()
      val result = out Bool()
      result := source
      val rejected = new IllegalStateException("deliberate rejected probe")
      def attempt(reject: Boolean): Unit = {
        if (mode == 1) {
          require(width >= 8, "RETRY_RULE")
          marker(source, "retryTrue")
        } else {
          if (reject) throw rejected
          marker(source, "retryFalse")
        }
      }
      try attempt(true) catch { case error: IllegalStateException if error eq rejected => () }
      assert(NativeSymbolicLegality.checkpoint(this) == 0)
      assert(ParameterizedStructure.regionsOf(this).isEmpty)
      attempt(false)
      assert(NativeSymbolicLegality.checkpoint(this) == 1)
    })
    assert(rtl.split("initial \\$fatal", -1).length == 2, rtl)
    diagnosticCase(dir, "RetriedLegality", Seq("MODE" -> 0, "WIDTH" -> 4), None)
    diagnosticCase(dir, "RetriedLegality", Seq("MODE" -> 1, "WIDTH" -> 4), Some("RETRY_RULE"))
  }

  for (laneMinimum <- Seq(1, 2))
  test(s"bounded relational capture retains independent child actuals and activated legality, minimum=$laneMinimum") {
    class RelationalChild(dataBits: ElabInt, lanes: ElabInt) extends Component {
      val dataIn = in Bits(dataBits bits)
      val laneIn = in Bits(lanes bits)
      val observed = out Bool()
      observed := dataIn.msb ^ laneIn.msb
    }
    val (dir, rtl) = emit("RelationalLegality")(new Component {
      val dataBits: ElabInt = HdlInt.param("DATA_BITS", 3, 1, 4).asElabInt
      val lanes: ElabInt = HdlInt.param("LANES", 2, laneMinimum, 4).asElabInt
      val dataIn = in Bits(4 bits)
      val laneIn = in Bits(4 bits)
      val observed = out Bool()
      if (dataBits >= lanes) {
        require(dataBits >= lanes, "IMPLIED_ACTIVATION_RULE")
        require(dataBits >= 2, "RELATIONAL_RULE")
        val child = new RelationalChild(dataBits, lanes)
        val childData = Bits(dataBits bits).setName("childData").dontSimplifyIt()
        val childLanes = Bits(lanes bits).setName("childLanes").dontSimplifyIt()
        childData := dataIn.resize(dataBits)
        childLanes := laneIn.resize(lanes)
        child.dataIn := childData
        child.laneIn := childLanes
        observed := child.observed
      } else observed := dataIn(0) ^ laneIn(0)
    })
    assert(rtl.contains(".DATA_BITS(DATA_BITS)") && rtl.contains(".LANES(LANES)"), rtl)
    assert(!rtl.contains("IMPLIED_ACTIVATION_RULE"), rtl)
    for (dataBits <- 1 to 4; lanes <- laneMinimum to 4) {
      val expectedIndexA = if (dataBits >= lanes) dataBits - 1 else 0
      val expectedIndexB = if (dataBits >= lanes) lanes - 1 else 0
      Files.write(dir.resolve("tb.v"), s"""module tb;
        |reg [3:0] a, b;
        |wire observed;
        |integer x, y;
        |RelationalLegality #(.DATA_BITS($dataBits), .LANES($lanes)) dut(a,b,observed);
        |initial begin
        |for (x=0; x<16; x=x+1) for (y=0; y<16; y=y+1) begin
        |a=x; b=y; #1;
        |if (observed !== (a[$expectedIndexA] ^ b[$expectedIndexB])) $$fatal(1,"relational child binding");
        |end
        |$$display("RELATIONAL_CHILD_PASS"); $$finish;
        |end
        |endmodule
        |""".stripMargin.getBytes(UTF_8))
      val compiled = run(dir, Seq("iverilog", "-g2012", "-s", "tb", "-o", "relation.vvp", "RelationalLegality.v", "tb.v"))
      assert(compiled._1 == 0, compiled._2)
      val simulated = run(dir, Seq("vvp", "relation.vvp"))
      if (dataBits == 1 && lanes == 1)
        assert(simulated._1 != 0 && simulated._2.contains("RELATIONAL_RULE"), simulated._2)
      else assert(simulated._1 == 0 && simulated._2.contains("RELATIONAL_CHILD_PASS"), simulated._2)
    }
  }

  for (split <- Seq(false, true))
    test(s"canonical children keep distinct actuals and instance activation, split=$split") {
      class CheckedChild(width: ElabInt) extends Component {
        val data = in Bits(width bits)
        val observed = out Bool()
        require(width >= 8, "CHILD_WIDTH_RULE")
        observed := data.msb
      }
      val dir = Files.createTempDirectory("scoped-child-")
      MorphVerilog(MorphWireAssignmentPasses(SpinalConfig(targetDirectory = dir.toString,
        oneFilePerComponent = split, headerWithDate = false), enabled = false))(new Component {
        setDefinitionName("SiblingLegality")
        val mode: ElabInt = HdlInt.param("MODE", 0, 0, 2).asElabInt
        val a: ElabInt = HdlInt.param("A", 8, 1, 16).asElabInt
        val b: ElabInt = HdlInt.param("B", 8, 1, 16).asElabInt
        val first = in Bits(16 bits)
        val second = in Bits(16 bits)
        val observed = out Bool()
        if (mode == 1) {
          val child = ElabFormalComponent.parameter(a, "WIDTH", 1, 16)(w => new CheckedChild(w))
          child.data := first.resize(a)
          observed := child.observed
        } else if (mode == 2) {
          val child = ElabFormalComponent.parameter(b, "WIDTH", 1, 16)(w => new CheckedChild(w))
          child.data := second.resize(b)
          observed := child.observed
        } else observed := False
      })
      val stream = Files.list(dir)
      val sources = try {
        import scala.collection.JavaConverters._
        stream.iterator().asScala.filter(_.toString.endsWith(".v")).map(_.toString).toVector
      } finally stream.close()
      for (mode <- 0 to 2; a <- Seq(4, 8, 16); b <- Seq(4, 8, 16)) {
        val invalid = (mode == 1 && a < 8) || (mode == 2 && b < 8)
        val expected = if (mode == 0) "1'b0" else if (mode == 1) s"first[${a - 1}]" else s"second[${b - 1}]"
        Files.write(dir.resolve("tb.v"), s"""module tb;
          |reg [15:0] first, second; wire observed; integer i;
          |SiblingLegality #(.MODE($mode), .A($a), .B($b)) dut(first,second,observed);
          |initial begin
          |for(i=0;i<16;i=i+1) begin
          |first = 16'b1 << i; second = ~(16'b1 << i); #1;
          |if(observed !== $expected) $$fatal(1,"CHILD_BINDING_MISMATCH");
          |end
          |$$display("CHILD_BINDING_PASS"); $$finish;
          |end
          |endmodule
          |""".stripMargin.getBytes(UTF_8))
        val compiled = run(dir, Seq("iverilog", "-g2012", "-s", "tb", "-o", "child.vvp") ++ sources :+ "tb.v")
        assert(compiled._1 == 0, compiled._2)
        val simulated = run(dir, Seq("vvp", "child.vvp"))
        if (invalid) {
          assert(simulated._1 != 0 && simulated._2.contains("CHILD_WIDTH_RULE"), simulated._2)
          assert(simulated._2.split("CHILD_WIDTH_RULE", -1).length == 2, simulated._2)
        } else assert(simulated._1 == 0 && simulated._2.contains("CHILD_BINDING_PASS"), simulated._2)
      }
      val top = dir.resolve("SiblingLegality.v")
      val original = new String(Files.readAllBytes(top), UTF_8)
      val staleActual = original.replace(".WIDTH(A)", ".WIDTH(B)")
      assert(staleActual != original, original)
      Files.write(dir.resolve("tb.v"), """module tb;
        |wire observed;
        |SiblingLegality #(.MODE(1), .A(8), .B(16)) dut(16'h0080,16'h0000,observed);
        |initial begin #1; if(observed !== 1'b1) $fatal(1,"STALE_CHILD_ACTUAL"); $finish; end
        |endmodule
        |""".stripMargin.getBytes(UTF_8))
      for ((rtl, mutated) <- Seq(original -> false, staleActual -> true)) {
        Files.write(top, rtl.getBytes(UTF_8))
        val compiled = run(dir, Seq("iverilog", "-g2012", "-s", "tb", "-o", "stale.vvp") ++ sources :+ "tb.v")
        assert(compiled._1 == 0, compiled._2)
        val outcome = run(dir, Seq("vvp", "stale.vvp"))
        if (mutated) assert(outcome._1 != 0 && outcome._2.contains("STALE_CHILD_ACTUAL"), outcome._2)
        else assert(outcome._1 == 0, outcome._2)
      }
      Files.write(top, original.getBytes(UTF_8))
    }

  test("safe invalid defaults are retained and universally true requirements disappear") {
    val (invalidDir, invalidRtl) = emit("InvalidDefault") {
      new OptionalPipeline(HdlInt.param("WIDTH", 4, 1, 64).asElabInt,
        HdlInt.param("USE_PIPELINE", 1, 0, 1).asElabInt)
    }
    assert(invalidRtl.contains("WIDTH = 4") && invalidRtl.contains("USE_PIPELINE = 1"), invalidRtl)
    diagnosticCase(invalidDir, "InvalidDefault", Seq.empty, Some("Pipeline mode requires WIDTH >= 8"))
    diagnosticCase(invalidDir, "InvalidDefault", Seq("USE_PIPELINE" -> 0), None)
    val (_, trueRtl) = emit("UniversallyValid") {
      new OptionalPipeline(HdlInt.param("WIDTH", 8, 8, 64).asElabInt,
        HdlInt.param("USE_PIPELINE", 0, 0, 1).asElabInt)
    }
    assert(!trueRtl.contains("$fatal") && !trueRtl.contains("G_PARAMETER_LEGALITY"), trueRtl)
    val concreteDir = Files.createTempDirectory("scoped-concrete-")
    SpinalVerilog(SpinalConfig(targetDirectory = concreteDir.toString, headerWithDate = false)) {
      new OptionalPipeline(ElabInt.literal(8), ElabInt.literal(1))
    }
    val concreteRtl = new String(Files.readAllBytes(concreteDir.resolve("OptionalPipeline.v")), UTF_8)
    assert(!concreteRtl.contains("$fatal"), concreteRtl)
  }

  test("live activation and latency mutants are detected by semantic oracles") {
    val (dir, original) = emit("OptionalPipeline") {
      new OptionalPipeline(HdlInt.param("WIDTH", 16, 1, 64).asElabInt,
        HdlInt.param("USE_PIPELINE", 0, 0, 1).asElabInt)
    }
    val guard = "if (((USE_PIPELINE) == (1))) begin : G_PARAMETER_LEGALITY_ACTIVE"
    assert(original.contains(guard), original)
    def simulate(rtl: String, mode: Int, width: Int): (Int, String) = {
      Files.write(dir.resolve("mutant.v"), rtl.getBytes(UTF_8))
      Files.write(dir.resolve("mutation-tb.v"), s"""module tb;
        |reg clk=0; reg [$width-1:0] data=0; wire [$width-1:0] observed;
        |OptionalPipeline #(.WIDTH($width), .USE_PIPELINE($mode)) dut(data, observed, clk, 1'b0);
        |initial begin
        |#1; clk=1; #1; clk=0; data=1; #1;
        |if ($mode == 1 && observed !== 0) $$fatal(1,"LATENCY_MUTATION");
        |if ($mode == 0 && observed !== 1) $$fatal(1,"BYPASS_MUTATION");
        |$$display("MUTATION_BASELINE_PASS"); $$finish;
        |end
        |endmodule
        |""".stripMargin.getBytes(UTF_8))
      val compiled = run(dir, Seq("iverilog", "-g2012", "-s", "tb", "-o", "mutant.vvp", "mutant.v", "mutation-tb.v"))
      assert(compiled._1 == 0, compiled._2) // parser errors never count as detection
      run(dir, Seq("vvp", "mutant.vvp"))
    }
    assert(simulate(original, 0, 4)._1 == 0)
    val activeFailure = simulate(original, 1, 4)
    assert(activeFailure._1 != 0 && activeFailure._2.contains("Pipeline mode requires WIDTH >= 8"))
    for (replacement <- Seq("if (1) begin : G_PARAMETER_LEGALITY_ACTIVE",
      "if (((USE_PIPELINE) != (1))) begin : G_PARAMETER_LEGALITY_ACTIVE")) {
      val mutant = original.replace(guard, replacement)
      val outcome = simulate(mutant, 0, 4)
      assert(outcome._1 != 0 && outcome._2.contains("Pipeline mode requires WIDTH >= 8"), outcome._2)
    }
    val dropped = original.replace(guard, "if (0) begin : G_PARAMETER_LEGALITY_ACTIVE")
    val missingDiagnostic = simulate(dropped, 1, 4)
    assert(missingDiagnostic._1 == 0 && missingDiagnostic._2.contains("MUTATION_BASELINE_PASS"), missingDiagnostic._2)
    assert(simulate(original, 1, 8)._1 == 0)
    val latency = original.replace("dataOut = dataIn_regNext;", "dataOut = dataIn;")
    assert(latency != original)
    val changedLatency = simulate(latency, 1, 8)
    assert(changedLatency._1 != 0 && changedLatency._2.contains("LATENCY_MUTATION"), changedLatency._2)
    val fatal = "initial $fatal(1, \"%s\", \"Pipeline mode requires WIDTH >= 8\");"
    assert(original.contains(fatal))
    val duplicate = original.replace(fatal, fatal + "\n      " + fatal)
    assert(duplicate.split(java.util.regex.Pattern.quote(fatal), -1).length - 1 == 2)
    assert(original.split(java.util.regex.Pattern.quote(fatal), -1).length - 1 == 1)
    val duplicatedOutcome = simulate(duplicate, 1, 4)
    assert(duplicatedOutcome._1 != 0 && duplicatedOutcome._2.contains("Pipeline mode requires WIDTH >= 8"))
  }

  test("joint structural evidence rejects copied predicates and does not mint integer authority") {
    val a = HdlInt.param("A", 2, 1, 4).asElabInt
    val b = HdlInt.param("B", 2, 1, 4).asElabInt
    val predicate = a >= b
    val proof = ElaborationProductDomain.structuralPredicate(predicate.expression)
    proof.requireCondition(predicate.expression)
    val error = intercept[ParameterizedVerilogException] {
      proof.requireCondition(predicate.expression.copy())
    }
    assert(error.code == "SPINAL-ELAB-DOMAIN-PRODUCT-AUTHORITY-MISSING")
    assert(proof.indices(0).size == 10 && proof.indices(1).size == 6)
    val foreign = HdlInt.param("A", 2, 1, 4).asElabInt >= b
    intercept[ParameterizedVerilogException](proof.requireCondition(foreign.expression))
    // A retained requirement cannot prove positivity of A-B throughout its
    // declaration domain; activation does not become an integer assumption.
    val unsafe = intercept[Exception] {
      emit("UnsafeGeometry")(new Component {
        ElabControl.requireCondition(a >= b, "not an assumption", "test", 1)
        val bad = in Bits((a - b) bits)
      })
    }
    val causes = Iterator.iterate[Throwable](unsafe)(_.getCause).takeWhile(_ != null)
      .map(error => String.valueOf(error.getMessage)).mkString("\n")
    assert(causes.contains("SPINAL-ELAB-INT-WIDTH-DOMAIN-INVALID"), causes)
  }

  test("standalone scoped fixture generation is deterministic") {
    val first = Files.createTempDirectory("scoped-repeat-a-")
    val second = Files.createTempDirectory("scoped-repeat-b-")
    morphhdl.examples.ScopedLegalityArtifactWriter.main(Array(first.toString))
    morphhdl.examples.ScopedLegalityArtifactWriter.main(Array(second.toString))
    assert(java.util.Arrays.equals(Files.readAllBytes(first.resolve("OptionalPipeline.v")),
      Files.readAllBytes(second.resolve("OptionalPipeline.v"))))
  }

}
