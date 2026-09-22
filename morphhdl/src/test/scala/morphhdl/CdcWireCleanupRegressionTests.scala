package morphhdl

import java.nio.file.{Files, Path, Paths}
import java.nio.charset.StandardCharsets
import scala.collection.JavaConverters._
import morphhdl.examples.{CdcWireCleanupArtifactWriter, CdcWireCleanupFixtures}
import org.scalatest.funsuite.AnyFunSuite
import morphhdl.frontend.HdlInt
import spinal.core._
import spinal.core.internals.{Phase, PhaseContext}
import scala.collection.mutable.ArrayBuffer
import scala.sys.process.{Process, ProcessLogger}

/** HDL semantics and the independent oracles run in check-cdc-wire-cleanup.py. */
class CdcWireCleanupRegressionTests extends AnyFunSuite {
  test("symbolic zero retains a width-sensitive complement under a Boolean receiver") {
    withDirectory { root => morphhdl.examples.CdcPublicationCleanupFixtures.zeroWidthControl(root) }
  }

  test("retained resize identities preserve allocated references across hierarchy in both modes") {
    withDirectory { root =>
      for (enabled <- Vector(false, true)) {
        val first = morphhdl.examples.CdcPublicationCleanupFixtures.generate(root.resolve(enabled.toString),
          naming = true, enabled = enabled, observe = enabled, hierarchy = true)
        val repeat = morphhdl.examples.CdcPublicationCleanupFixtures.generate(root.resolve(enabled + "-repeat"),
          naming = true, enabled = enabled, observe = false, hierarchy = true)
        assert(first == repeat)
      }
    }
  }

  test("publication-owned resize boundaries allow recursive occupancy aliases zero and mux cleanup") {
    withDirectory { root =>
      val first = morphhdl.examples.CdcPublicationCleanupFixtures.generate(root.resolve("on"),
        naming = false, enabled = true, observe = true)
      val repeat = morphhdl.examples.CdcPublicationCleanupFixtures.generate(root.resolve("repeat"),
        naming = false, enabled = true, observe = false)
      assert(first == repeat)
      assert(first.contains("FIFO_LOG_DEPTH + 2"))
      assert(!first.contains("morphhdl_resize"))
    }
  }

  test("resize capture preserves unnamed provenance explicit lookalikes and naming collisions in both modes") {
    withDirectory { root =>
      for (enabled <- Vector(false, true)) {
        val first = morphhdl.examples.CdcPublicationCleanupFixtures.generate(root.resolve(enabled.toString),
          naming = true, enabled = enabled, observe = enabled)
        val repeat = morphhdl.examples.CdcPublicationCleanupFixtures.generate(root.resolve(enabled + "-repeat"),
          naming = true, enabled = enabled, observe = false)
        assert(first == repeat)
        for (name <- Vector("kept_difference", "_zz_user_kept", "morphhdl_resize_source", "_zz_wide"))
          assert(first.contains("assign " + name + " ="), name)
        assert(!first.contains("morphhdl_resize_source_"), "compiler prefix was injected")
        assert(!first.contains("assign morphhdl_resize ="), "compiler target name was injected")
      }
    }
  }

  private def withDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("cdc-wire-cleanup-")
    try body(directory)
    finally {
      val paths = Files.walk(directory)
      try paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse
        .foreach(Files.deleteIfExists(_))
      finally paths.close()
    }
  }

  for (name <- CdcWireCleanupFixtures.names) {
    test(s"$name converges in one production invocation with deterministic emission") {
      withDirectory { directory =>
        val first = CdcWireCleanupArtifactWriter.generate(directory.resolve("first"), name,
          enabled = true, observe = true)
        val repeat = CdcWireCleanupArtifactWriter.generate(directory.resolve("repeat"), name,
          enabled = true)
        assert(first == repeat, s"$name observation or repeat changed emitted RTL")
        assert(Files.exists(directory.resolve("first").resolve(name + ".fixed-point.json")))
      }
    }
  }

  test("generated-looking explicit names and keep/debug/CDC barriers survive recursive cleanup") {
    withDirectory { directory =>
      val source = CdcWireCleanupArtifactWriter.generate(directory, "CdcWireControls", enabled = true)
      for (name <- Vector("protectedKeep", "protectedGuard", "protectedDebug", "protectedCdc",
          "protectedGeometry", "when_user_l42"))
        assert(("(?m)^\\s*assign\\s+" + name + "\\s*=").r.findFirstIn(source).nonEmpty,
          s"protected identity $name disappeared:\n$source")
      assert(source.contains("async_reg"), source)
      assert(source.contains("state <="), source)
    }
  }

  test("shared unnamed expressions above the duplication budget retain their actual identity") {
    withDirectory { directory =>
      var carrier: Bits = null
      MorphVerilog(MorphWireAssignmentPasses(SpinalConfig(targetDirectory = directory.toString,
        oneFilePerComponent = true, headerWithDate = false))) {
        new Component {
          setDefinitionName("CdcWireBudgetControl")
          val width: ElabInt = HdlInt.param("WIDTH", 8, 6, 16).asElabInt
          val a, b = in Bits(width bits)
          val receivers = out Vec(Bits(width bits), 33)
          @dontName val shared = Bits(width bits)
          shared := a ^ b
          carrier = shared
          for (index <- 0 until 33) receivers(index) := shared ^ B(index, 6 bits).resize(width)
        }
      }
      val source = new String(Files.readAllBytes(directory.resolve("CdcWireBudgetControl.v")),
        StandardCharsets.UTF_8)
      val name = java.util.regex.Pattern.quote(carrier.getName())
      assert(("(?m)^\\s*assign\\s+" + name + "\\s*=").r.findFirstIn(source).nonEmpty,
        "shared carrier above the aggregate duplication budget disappeared:\n" + source)
    }
  }

  test("shared compiler expression nodes inline while independently protected expression nodes remain") {
    withDirectory { directory =>
      var protectedNode: UInt = null
      MorphVerilog(MorphWireAssignmentPasses(SpinalConfig(targetDirectory = directory.toString,
        oneFilePerComponent = true, headerWithDate = false))) {
        new Component {
          setDefinitionName("CdcSharedExpressionNodeControl")
          val width: ElabInt = HdlInt.param("WIDTH", 8, 4, 16).asElabInt
          val a, b = in UInt(width bits)
          val advance = in Bool()
          val first, second, protectedValue = out UInt(width bits)
          val sampled = out(Reg(UInt(width bits)) init(0))
          @dontName val shared = a + b
          first := shared
          second := shared ^ b
          when(advance) { sampled := shared }
          @dontName val protectedExpression = a ^ b
          protectedExpression.dontSimplifyIt()
          protectedNode = protectedExpression
          protectedValue := protectedExpression
        }
      }
      val source = new String(Files.readAllBytes(directory.resolve("CdcSharedExpressionNodeControl.v")),
        StandardCharsets.UTF_8)
      assert(source.contains("assign first = (a + b);"), source)
      assert(source.contains("assign second = ((a + b) ^ b);"), source)
      assert(source.contains("sampled <= (a + b);"), source)
      val name = java.util.regex.Pattern.quote(protectedNode.getName())
      assert(("(?m)^\\s*assign\\s+" + name + "\\s*=").r.findFirstIn(source).nonEmpty, source)
    }
  }

  test("simplified compiler alias nodes reach every receiver while explicit and protected aliases remain") {
    for (generatedName <- Vector(false, true)) withDirectory { directory =>
      var sharedNode, protectedNode, explicitlyNamedNode: UInt = null
      var inspected = false
      val config = MorphWireAssignmentPasses(SpinalConfig(targetDirectory = directory.toString,
        oneFilePerComponent = true, headerWithDate = false))
      config.phasesInserters += { phases: ArrayBuffer[Phase] =>
        val index = phases.indexWhere(_.getClass.getName ==
          "morphhdl.examples.ProductionWireAssignmentPhase")
        require(index >= 0)
        phases.insert(index + 1, new Phase {
          override def hasNetlistImpact: Boolean = false
          override def impl(pc: PhaseContext): Unit = {
            val declarations = ArrayBuffer.empty[BaseType]
            pc.walkDeclarations {
              case value: BaseType => declarations += value
              case _ =>
            }
            assert(!declarations.exists(_ eq sharedNode),
              "the first production invocation retained the simplified shared alias identity")
            assert(declarations.exists(_ eq protectedNode), "dontSimplify alias identity disappeared")
            assert(declarations.exists(_ eq explicitlyNamedNode), "explicit alias identity disappeared")
            inspected = true
          }
        })
      }
      MorphVerilog(config) {
        new Component {
          setDefinitionName("CdcSharedZeroShiftAliasControl")
          val width: ElabInt = HdlInt.param("WIDTH", 8, 4, 16).asElabInt
          val payload, other = in UInt(width bits)
          val advance = in Bool()
          val first, second, protectedValue, explicitValue = out UInt(width bits)
          val sampled = out(Reg(UInt(width bits)) init(0))
          // This starts as a real compiler expression type node. Ordinary
          // simplification exposes a direct alias before the wire pipeline.
          @dontName val shared = payload >> 0
          if (generatedName) shared.setCompositeName(payload, "folded", Nameable.REMOVABLE)
          sharedNode = shared
          first := shared
          second := shared ^ other
          // The generated-named alias profile remains continuous-only;
          // the unnamed profile also authorizes a nonblocking receiver.
          when(advance) { sampled := (if (generatedName) payload else shared) }
          @dontName val protectedAlias = payload >> 0
          if (generatedName) protectedAlias.setCompositeName(payload, "protected", Nameable.REMOVABLE)
          protectedAlias.dontSimplifyIt()
          protectedNode = protectedAlias
          protectedValue := protectedAlias
          @dontName val explicitAlias = payload >> 0
          explicitAlias.setName("when_user_alias")
          explicitlyNamedNode = explicitAlias
          explicitValue := explicitAlias
        }
      }
      assert(inspected, "native identity observer did not run")
      val source = new String(Files.readAllBytes(directory.resolve("CdcSharedZeroShiftAliasControl.v")),
        StandardCharsets.UTF_8)
      assert(source.contains("assign first = payload;"), source)
      assert(source.contains("assign second = (payload ^ other);"), source)
      assert(source.contains("sampled <= payload;"), source)
      for (node <- Vector(protectedNode, explicitlyNamedNode)) {
        val name = java.util.regex.Pattern.quote(node.getName())
        assert(("(?m)^\\s*assign\\s+" + name + "\\s*=").r.findFirstIn(source).nonEmpty, source)
      }
    }
  }

  test("late unsigned wrappers retain symbolic widths with signed declarations enabled or disabled") {
    withDirectory { directory =>
      final case class Emitted(source: Path, top: String)
      def emit(moduleName: String, signed: Boolean): Emitted = {
        val output = directory.resolve(moduleName)
        val base = MorphWireAssignmentPasses(SpinalConfig(targetDirectory = output.toString,
          oneFilePerComponent = true, headerWithDate = false), enabled = true)
        val config = if (signed) base else MorphSignedDeclarations.disable(base)
        val report = MorphVerilog(config) {
          new Component {
            setDefinitionName(moduleName)
            val width: ElabInt = HdlInt.param("WORD_BITS", 33, 1, 65).asElabInt
            val gray, a, b = in Bits(width bits)
            val binary = out UInt(width bits)
            val mixed = out Bits(width bits)
            binary := spinal.lib.fromGray(gray)
            // An unrelated expression graph also exceeds the inline budget.
            // Any remaining late carrier must keep its logical packed width.
            mixed := (0 until 100).foldLeft(a) { (value, _) => (value |>> 1) ^ b }
          }
        }
        assert(report.generatedSourcesPaths.size == 1, report.generatedSourcesPaths)
        Emitted(Paths.get(report.generatedSourcesPaths.head), report.toplevelName)
      }
      // The disabled native printer freezes the deep graph's late wrappers at
      // its elaboration width. Use an independent width-explicit RTL oracle,
      // so that pre-existing limitation cannot define the expected result.
      val reference = Emitted(directory.resolve("LateUnsignedReference.v"), "LateUnsignedReference")
      Files.write(reference.source, """module LateUnsignedReference #(
  parameter integer WORD_BITS=33
) (
  input wire [WORD_BITS-1:0] gray, a, b,
  output reg [WORD_BITS-1:0] binary, mixed
);
  integer i;
  always @* begin
    binary[WORD_BITS-1] = gray[WORD_BITS-1] ^ 1'b0;
    for (i=WORD_BITS-2; i>=0; i=i-1) binary[i] = binary[i+1] ^ gray[i];
    mixed = a;
    for (i=0; i<100; i=i+1) mixed = (mixed >> 1) ^ b;
  end
endmodule
""".getBytes(StandardCharsets.UTF_8))
      val optimized = Vector(
        emit("LateUnsignedSigned", signed = true),
        emit("LateUnsignedLegacy", signed = false))
      optimized.foreach { emitted =>
        val source = new String(Files.readAllBytes(emitted.source), StandardCharsets.UTF_8)
        assert(!source.contains("[32:0]"), source)
        Vector("binary", "mixed").foreach { receiver =>
          assert(("(?m)^\\s*wire\\s+\\[[^\\]]*WORD_BITS[^\\]]*\\]\\s+_zz_\\w*" + receiver + "\\w*;").r
            .findFirstIn(source).nonEmpty, "fixture did not retain a late symbolic wrapper:\n" + source)
        }
      }
      def available(tool: String, option: String): Boolean =
        scala.util.Try(Process(Seq(tool, option)).!(ProcessLogger(_ => ())) == 0).getOrElse(false)
      def run(command: Seq[String]): Unit = {
        val diagnostic = new StringBuilder
        def append(line: String): Unit = diagnostic.synchronized { diagnostic.append(line).append('\n'); () }
        assert(Process(command).!(ProcessLogger(append _, append _)) == 0,
          command.mkString(" ") + "\n" + diagnostic)
      }
      if (available("iverilog", "-V")) {
        val bench = directory.resolve("late_unsigned_tb.v")
        Files.write(bench, s"""module late_unsigned_tb;
  parameter integer WORD_BITS=65;
  reg [WORD_BITS-1:0] gray, a, b;
  wire [WORD_BITS-1:0] reference_binary, reference_mixed;
  wire [WORD_BITS-1:0] signed_binary, signed_mixed, legacy_binary, legacy_mixed;
  reg [WORD_BITS-1:0] expected_binary, expected_mixed;
  integer i, sample;
  ${reference.top} #(.WORD_BITS(WORD_BITS)) reference_dut(
    .gray(gray), .a(a), .b(b), .binary(reference_binary), .mixed(reference_mixed));
  ${optimized(0).top} #(.WORD_BITS(WORD_BITS)) signed_dut(
    .gray(gray), .a(a), .b(b), .binary(signed_binary), .mixed(signed_mixed));
  ${optimized(1).top} #(.WORD_BITS(WORD_BITS)) legacy_dut(
    .gray(gray), .a(a), .b(b), .binary(legacy_binary), .mixed(legacy_mixed));
  task check;
    begin
      expected_binary[WORD_BITS-1] = gray[WORD_BITS-1] ^ 1'b0;
      for (i=WORD_BITS-2; i>=0; i=i-1)
        expected_binary[i] = expected_binary[i+1] ^ gray[i];
      expected_mixed = a;
      for (i=0; i<100; i=i+1) expected_mixed = (expected_mixed >> 1) ^ b;
      #1;
      if (reference_binary !== expected_binary || reference_mixed !== expected_mixed ||
          signed_binary !== expected_binary || signed_mixed !== expected_mixed ||
          legacy_binary !== expected_binary || legacy_mixed !== expected_mixed)
        $$fatal(1, "late unsigned symbolic width mismatch WORD_BITS=%0d gray=%h a=%h b=%h expected_binary=%h expected_mixed=%h reference_binary=%h reference_mixed=%h signed_binary=%h signed_mixed=%h legacy_binary=%h legacy_mixed=%h",
          WORD_BITS, gray, a, b, expected_binary, expected_mixed, reference_binary,
          reference_mixed, signed_binary, signed_mixed, legacy_binary, legacy_mixed);
    end
  endtask
  initial begin
    gray=0; a=0; b=0; check;
    gray=1; gray=gray << (WORD_BITS-1); a=gray; b=gray; check;
    gray={WORD_BITS{1'b1}}; a=gray; b=gray; check;
    gray={WORD_BITS{1'bx}}; a=gray; b=gray; check;
    gray={WORD_BITS{1'bz}}; a=gray; b=gray; check;
    for (sample=0; sample<128; sample=sample+1) begin
      for (i=0; i<WORD_BITS; i=i+1) begin gray[i]=$$random; a[i]=$$random; b[i]=$$random; end
      check;
    end
    $$display("LATE_UNSIGNED_WIDTH_PASS WORD_BITS=%0d", WORD_BITS);
    $$finish;
  end
endmodule
""".getBytes(StandardCharsets.UTF_8))
        Vector(1, 33, 65).foreach { width =>
          val executable = directory.resolve(s"late_unsigned_$width.vvp")
          run(Seq("iverilog", "-g2001", "-s", "late_unsigned_tb",
            s"-Plate_unsigned_tb.WORD_BITS=$width", "-o", executable.toString,
            bench.toString, reference.source.toString) ++ optimized.map(_.source.toString))
          run(Seq("vvp", executable.toString))
        }
      } else info("Icarus unavailable: late-wrapper width checks passed; four-state overrides run in focused CI")
      if (available("yosys", "-V")) {
        for (emitted <- optimized; width <- Vector(1, 33, 65)) {
          val name = emitted.top
          val script = directory.resolve(s"$name-$width.ys")
          def prepare(source: Path, top: String, role: String): String = s"""read_verilog $source
chparam -set WORD_BITS $width $top
hierarchy -check -top $top
proc
opt
rename -hide
rename $top $role
design -stash $role
"""
          Files.write(script, (prepare(reference.source, reference.top, "gold") +
            prepare(emitted.source, name, "gate") + """design -reset
design -copy-from gold -as gold gold
design -copy-from gate -as gate gate
equiv_make gold gate equiv
hierarchy -top equiv
equiv_simple
equiv_status -assert
""").getBytes(StandardCharsets.UTF_8))
          run(Seq("yosys", "-Q", "-s", script.toString))
        }
      } else info("Yosys unavailable: late-wrapper formal equivalence runs in focused CI")
    }
  }
}
