package spinal.core

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.sys.process.{Process, ProcessLogger}
import morphhdl.{MorphVerilog}
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite

class NativeDerivedLocalParameterTests extends AnyFunSuite {
  private val commandIndex = new java.util.concurrent.atomic.AtomicInteger()
  import morphhdl.examples.RecordLink
  private def emit(directory: Path, split: Boolean, passes: Boolean = true)(body: => Component): String = {
    val config = SpinalConfig(targetDirectory = directory.toString,
      oneFilePerComponent = split, headerWithDate = false)
    MorphVerilog(config)(body)
    info(s"Generated local-parameter evidence: $directory")
    import scala.collection.JavaConverters._
    val paths = Files.list(directory)
    try paths.iterator().asScala.filter(_.toString.endsWith(".v")).toVector.sortBy(_.toString)
      .map(p => new String(Files.readAllBytes(p), UTF_8)).mkString("\n") finally paths.close()
  }
  private def run(directory: Path, command: Seq[String]): String = {
    val log = new StringBuilder
    val code = Process(command, directory.toFile).!(ProcessLogger(
      line => log.append(line).append('\n'), line => log.append(line).append('\n')))
    Files.write(directory.resolve(s"command-${commandIndex.incrementAndGet()}.log"),
      (command.mkString(" ") + "\n" + log).getBytes(UTF_8))
    assert(code == 0, command.mkString(" ") + "\n" + log)
    log.toString
  }
  for (split <- Seq(false, true); passes <- Seq(false, true)) test(s"RecordLink uses a native localparam with same-file overrides, split=$split, passes=$passes") {
    val dir = Files.createTempDirectory("derived-localparam-")
    def body = new RecordLink(HdlInt.param("DATA_BITS", 32, 1, 2048).asElabInt,
      HdlInt.param("GENERATION_BITS", 8, 2, 64).asElabInt)
    val rtl = emit(dir, split, passes)(body)
    assert(rtl.contains("localparam integer TOTAL_BITS = (DATA_BITS + GENERATION_BITS);"), rtl)
    assert(rtl.contains("parameter integer DATA_BITS = 32"), rtl)
    assert(rtl.contains("parameter integer GENERATION_BITS = 8"), rtl)
    assert(rtl.contains("[(DATA_BITS + GENERATION_BITS)-1:0] dataIn"), rtl)
    assert(!rtl.contains("parameter integer TOTAL_BITS"))
    assert(rtl == emit(Files.createTempDirectory("derived-localparam-repeat-"), split, passes)(body))
    val dut = dir.resolve("RecordLink.v")
    for ((a,b) <- Seq((32,8),(64,8),(1,2),(2048,64),(8,32))) {
      val width = a+b
      val binding = if (a == 32 && b == 8) "" else s"#(.DATA_BITS($a), .GENERATION_BITS($b))"
      val tb = s"""module tb;
        |reg [$width-1:0] source;
        |wire [$width-1:0] result;
        |RecordLink $binding dut(source,result);
        |initial begin
        |if ($$bits(dut.dataIn) != $width) $$fatal(1,"wrong width");
        |source = {$width{1'bx}}; #1; if (result !== source) $$fatal(1,"X");
        |source = {$width{1'bz}}; #1; if (result !== source) $$fatal(1,"Z");
        |source = {$width{1'b1}}; #1; if (result !== source) $$fatal(1,"data");
        |$$display("DERIVED_LOCALPARAM_PASS"); $$finish;
        |end
        |endmodule
        |""".stripMargin
      Files.write(dir.resolve("tb.v"), tb.getBytes(UTF_8))
      run(dir, Seq("iverilog", "-g2012", "-s", "tb", "-o", "sim.vvp", dut.toString, "tb.v"))
      assert(run(dir, Seq("vvp", "sim.vvp")).contains("DERIVED_LOCALPARAM_PASS"))
      val referenceDir = Files.createDirectory(dir.resolve(s"reference-$a-$b"))
      SpinalVerilog(SpinalConfig(targetDirectory = referenceDir.toString, headerWithDate = false)) {
        val reference = new RecordLink(ElabInt.literal(a), ElabInt.literal(b))
        reference.setDefinitionName("reference")
        reference
      }
      val reference = referenceDir.resolve("reference.v")
      run(dir, Seq("yosys", "-Q", "-p", s"read_verilog $dut; chparam -set DATA_BITS $a -set GENERATION_BITS $b RecordLink; rename RecordLink candidate; read_verilog $reference; equiv_make candidate reference equiv; hierarchy -top equiv; equiv_simple; equiv_status -assert"))
    }
    run(dir, Seq("iverilog", "-g2001", "-s", "RecordLink", "-o", "strict.vvp", dut.toString))
    run(dir, Seq("verilator", "--lint-only", "--language", "1364-2001", "--top-module", "RecordLink", dut.toString))
    run(dir, Seq("yosys", "-Q", "-p", s"read_verilog $dut; synth -top RecordLink; check -assert"))
  }

  for (split <- Seq(false, true); preserve <- Seq(false, true))
    test(s"CA-005 ANSI widths expand nested aliases while body locals survive split=$split preserve=$preserve") {
      class PortAlias(busBits: ElabInt) extends Component {
        setDefinitionName("Ca005PortAlias")
        val busBytes: ElabInt = busBits / 8
        val keepBits: ElabInt = busBytes + 1
        val s_keep = in Bits(keepBits bits)
        val m_keep = out Bits(keepBits bits)
        val signedIn = in SInt(busBytes bits)
        val signedOut = out SInt(busBytes bits)
        val pad = inout(Analog(Bits(busBytes bits)))
        val bodyWire = Bits(keepBits bits).dontSimplifyIt()
        bodyWire := s_keep
        m_keep := bodyWire
        signedOut := signedIn
      }
      val dir = Files.createTempDirectory("ca005-port-alias-")
      val config = morphhdl.MorphAggregateOptions(SpinalConfig(targetDirectory = dir.toString,
        oneFilePerComponent = split, headerWithDate = false), preserve, preserve,
        if(preserve) morphhdl.MorphAggregateOptions.UnpackedArray else morphhdl.MorphAggregateOptions.PackedVector)
      MorphVerilog(config)(new PortAlias(HdlInt.param("BUS_LOG2_BYTES", 3, 2, 5).asElabInt.pow2 * 8))
      val dut = dir.resolve("Ca005PortAlias.v")
      val rtl = new String(Files.readAllBytes(dut), UTF_8)
      val header = rtl.take(rtl.indexOf(");"))
      assert(!header.contains("BUS_BYTES") && !header.contains("KEEP_BITS"), rtl)
      assert(header.contains("BUS_LOG2_BYTES"), rtl)
      assert(rtl.contains("localparam integer BUS_BYTES") && rtl.contains("KEEP_BITS = (BUS_BYTES + 1)"), rtl)
      assert(rtl.contains("[KEEP_BITS-1:0] bodyWire"), rtl)
      for(log <- 2 to 5) {
        val bits = 1 << log
        val tb = s"""module tb;
reg [$bits:0] source; wire [$bits:0] result;
reg signed [${bits-1}:0] si; wire signed [${bits-1}:0] so;
wire [${bits-1}:0] pad;
Ca005PortAlias #(.BUS_LOG2_BYTES($log)) dut(.s_keep(source),.m_keep(result),.signedIn(si),.signedOut(so),.pad(pad));
Ca005PortAlias default_dut();
initial begin
if ($$bits(dut.s_keep) != ${bits+1} || $$bits(dut.pad) != $bits || $$bits(default_dut.s_keep) != 9) $$fatal;
source={${bits+1}{1'bx}}; si={${bits}{1'bx}}; #1; if(result!==source || so!==si) $$fatal;
source={${bits+1}{1'bz}}; si={${bits}{1'bz}}; #1; if(result!==source || so!==si) $$fatal;
source='b1; si=-1; #1; if(result!==source || so!==si) $$fatal;
$$finish; end
endmodule"""
        Files.write(dir.resolve("tb.v"), tb.getBytes(UTF_8))
        run(dir, Seq("iverilog", "-g2012", "-s", "tb", "-o", "ca005.vvp", dut.toString, "tb.v"))
        run(dir, Seq("vvp", "ca005.vvp"))
      }
      run(dir, Seq("iverilog", "-g2001", "-s", "Ca005PortAlias", "-o", "strict.vvp", dut.toString))
      run(dir, Seq("verilator", "--lint-only", "--language", "1364-2001", "--top-module", "Ca005PortAlias", dut.toString))
      run(dir, Seq("yosys", "-Q", "-p", s"read_verilog $dut; synth -top Ca005PortAlias; check -assert"))
    }

  test("native unsigned arithmetic wrappers retain parameterized widths at larger overrides") {
    val dir = Files.createTempDirectory("native-wrapper-width-")
    val rtl = emit(dir, true)(new Component {
      setDefinitionName("KeepMask")
      val busBytes: ElabInt = HdlInt.param("BUS_BYTES", 8, 4, 32).asElabInt
      val keep = in Bits(busBytes bits)
      val legal = out Bool()
      val keepWide = keep.asUInt.resize(busBytes + 1)
      legal := keepWide =/= 0 && (keepWide & (keepWide + 1)) === 0
    })
    for (width <- Seq(4, 8, 16, 32)) {
      val tb = s"""module tb;
reg [${width-1}:0] keep; wire legal; integer i;
KeepMask #(.BUS_BYTES($width)) dut(keep,legal);
initial begin keep=0; #1; if(legal!==0) $$fatal;
for(i=1;i<=$width;i=i+1) begin keep=({$width{1'b1}} >> ($width-i)); #1; if(legal!==1) $$fatal; end
for(i=1;i<$width;i=i+1) begin keep=(1 << i); #1; if(legal!==0) $$fatal; end
$$finish; end
endmodule"""
      Files.write(dir.resolve("tb.v"), tb.getBytes(UTF_8))
      run(dir, Seq("iverilog", "-g2001", "-s", "tb", "-o", "sim", "KeepMask.v", "tb.v"))
      run(dir, Seq("vvp", "sim"))
    }
    run(dir, Seq("verilator", "--lint-only", "--language", "1364-2001", "--top-module", "KeepMask", "KeepMask.v"))
    run(dir, Seq("yosys", "-Q", "-p", "read_verilog KeepMask.v; chparam -set BUS_BYTES 32 KeepMask; synth -top KeepMask; check -assert"))
  }

  test("named calculations preserve dependency order and asymmetric roots") {
    val rtl = emit(Files.createTempDirectory("derived-localparam-chain-"), true)(new Component {
      val dataBits: ElabInt = HdlInt.param("DATA_BITS", 8, 1, 32).asElabInt
      val generationBits: ElabInt = HdlInt.param("GENERATION_BITS", 8, 2, 32).asElabInt
      val depth: ElabInt = HdlInt.param("DEPTH", 2, 1, 4).asElabInt
      val headerBits: ElabInt = generationBits * 2
      val recordBits: ElabInt = dataBits + headerBits
      val storageBits: ElabInt = recordBits * depth
      val din = in Bits(storageBits bits)
      val dout = out Bits(storageBits bits)
      dout := din
    })
    assert(rtl.contains("HEADER_BITS = (GENERATION_BITS * 2)"), rtl)
    assert(rtl.contains("RECORD_BITS = (DATA_BITS + HEADER_BITS)"), rtl)
    assert(rtl.contains("STORAGE_BITS = (RECORD_BITS * DEPTH)"), rtl)
    assert(rtl.indexOf("HEADER_BITS =") < rtl.indexOf("RECORD_BITS ="))
    assert(rtl.indexOf("RECORD_BITS =") < rtl.indexOf("STORAGE_BITS ="))
  }

  test("alternate declaration defaults do not change the retained calculation") {
    for (default <- Seq(32, 64)) {
      val dir = Files.createTempDirectory("derived-localparam-default-")
      val rtl = emit(dir, true)(new RecordLink(
        HdlInt.param("DATA_BITS", default, 1, 2048).asElabInt,
        HdlInt.param("GENERATION_BITS", 8, 2, 64).asElabInt))
      assert(rtl.contains(s"parameter integer DATA_BITS = $default"), rtl)
      assert(rtl.contains("localparam integer TOTAL_BITS = (DATA_BITS + GENERATION_BITS);"), rtl)
      Files.write(dir.resolve("tb.v"), s"""module tb;
        |RecordLink dut();
        |initial begin
        |if ($$bits(dut.dataIn) != ${default+8}) $$fatal(1,"alternate default");
        |$$finish;
        |end
        |endmodule
        |""".stripMargin.getBytes(UTF_8))
      run(dir, Seq("iverilog", "-g2012", "-s", "tb", "-o", "default.vvp", "RecordLink.v", "tb.v"))
      run(dir, Seq("vvp", "default.vvp"))
    }
  }

  test("explicit signal collisions are preserved and unused calculations add no parameters") {
    val rtl = emit(Files.createTempDirectory("derived-localparam-names-"), true)(new Component {
      val bits: ElabInt = HdlInt.param("WIDTH", 3, 1, 16).asElabInt
      val totalBits: ElabInt = bits + 1
      val totalAlias: ElabInt = totalBits
      val unusedRoot: ElabInt = HdlInt.param("UNUSED_ROOT", 3, 1, 16).asElabInt
      val unused: ElabInt = unusedRoot + 1
      val inputBits = in Bits(totalBits bits)
      val outputBits = out Bits(totalAlias bits)
      val collision = out Bool()
      collision.setName("TOTAL_BITS")
      collision := inputBits.orR
      outputBits := inputBits
    })
    assert(!rtl.contains("parameter integer UNUSED_ROOT"), rtl)
    assert(!rtl.contains("localparam integer UNUSED"), rtl)
    assert(!rtl.contains("localparam integer TOTAL_BITS ="), rtl)
    assert("localparam integer TOTAL_BITS_[0-9]+ =".r.findFirstIn(rtl).nonEmpty, rtl)
    assert(!rtl.contains("localparam integer TOTAL_ALIAS"), rtl)
  }

  test("ordinary concrete SpinalVerilog remains parameter-free") {
    val dir = Files.createTempDirectory("derived-localparam-concrete-")
    SpinalVerilog(SpinalConfig(targetDirectory = dir.toString, headerWithDate = false)) {
      new RecordLink(ElabInt.literal(32), ElabInt.literal(8))
    }
    val rtl = new String(Files.readAllBytes(dir.resolve("RecordLink.v")), UTF_8)
    assert(!rtl.contains("localparam integer TOTAL_BITS"), rtl)
    assert(rtl.contains("[39:0]"), rtl)
  }

  test("child actuals reference parent locals and canonical children keep their own formals") {
    class Child(width: ElabInt) extends Component {
      setDefinitionName("DerivedFormalChild")
      val din = in Bits(width bits)
      val dout = out Bits(width bits)
      dout := din
    }
    val dir = Files.createTempDirectory("derived-localparam-child-")
    val rtl = emit(dir, false)(new Component {
      setDefinitionName("DerivedFormalTop")
      val a: ElabInt = HdlInt.param("A", 8, 1, 16).asElabInt
      val b: ElabInt = HdlInt.param("B", 8, 1, 16).asElabInt
      val totalBits: ElabInt = a + b
      val din = in Bits(totalBits bits)
      val dout = out Bits(totalBits bits)
      val other = out Bits(totalBits bits)
      val first = ElabFormalComponent.parameter(totalBits, "WIDTH", 1, 32)(w => new Child(w))
      val second = ElabFormalComponent.parameter(totalBits, "WIDTH", 1, 32)(w => new Child(w))
      first.din := din
      second.din := din
      dout := first.dout
      other := second.dout
    })
    assert(rtl.contains(".WIDTH(TOTAL_BITS)"), rtl)
    assert(rtl.split(java.util.regex.Pattern.quote(".WIDTH(TOTAL_BITS)"), -1).length == 3, rtl)
    val childStart = rtl.indexOf("module DerivedFormalChild")
    val child = rtl.substring(childStart, rtl.indexOf("endmodule", childStart))
    assert(child.contains("parameter integer WIDTH"), rtl)
    assert(!child.contains("localparam integer TOTAL_BITS"), rtl)
    run(dir, Seq("iverilog", "-g2001", "-s", "DerivedFormalTop", "-o", "child.vvp", "DerivedFormalTop.v"))
  }

  test("typed helper dependencies remain legal Verilog-2001 constant functions") {
    val dir = Files.createTempDirectory("derived-localparam-helper-")
    val rtl = emit(dir, true)(new Component {
      setDefinitionName("DerivedHelper")
      val count: ElabInt = HdlInt.param("COUNT", 4, 1, 32).asElabInt
      val addressBits: ElabInt = count.addressWidth
      val capacity: ElabInt = addressBits.pow2
      val din = in Bits(capacity bits)
      val dout = out Bits(capacity bits)
      dout := din
    })
    assert(rtl.contains("localparam integer ADDRESS_BITS ="), rtl)
    assert(rtl.contains("localparam integer CAPACITY = (1 << (ADDRESS_BITS));"), rtl)
    run(dir, Seq("iverilog", "-g2001", "-s", "DerivedHelper", "-o", "helper.vvp", "DerivedHelper.v"))
    run(dir, Seq("verilator", "--lint-only", "--language", "1364-2001", "--top-module", "DerivedHelper", "DerivedHelper.v"))
    run(dir, Seq("yosys", "-Q", "-p", "read_verilog DerivedHelper.v; chparam -set COUNT 17 DerivedHelper; synth -top DerivedHelper; check -assert"))
  }

  test("same-file asymmetric overrides detect frozen defaults, operator changes and root swaps") {
    val dir = Files.createTempDirectory("derived-localparam-mutations-")
    val rtl = emit(dir, true)(new Component {
      setDefinitionName("DerivedMutationProbe")
      val a: ElabInt = HdlInt.param("A", 8, 1, 16).asElabInt
      val b: ElabInt = HdlInt.param("B", 8, 1, 16).asElabInt
      val totalBits: ElabInt = a + b * 2
      val din = in Bits(totalBits bits)
      val dout = out Bits(totalBits bits)
      val bodyWire = Bits(totalBits bits).dontSimplifyIt()
      bodyWire := din
      dout := bodyWire
    })
    val original = "localparam integer TOTAL_BITS = (A + (B * 2));"
    assert(rtl.contains(original), rtl)
    val tb = """module tb;
      |wire [15:0] result;
      |DerivedMutationProbe #(.A(2), .B(7)) dut(16'hac35,result);
      |initial begin
      |#1; if ($bits(dut.din) != 16 || $bits(dut.bodyWire) != 16 || result !== 16'hac35) $fatal(1,"DERIVED_MUTATION_DETECTED");
      |$display("DERIVED_ORACLE_PASS"); $finish;
      |end
      |endmodule
      |""".stripMargin
    Files.write(dir.resolve("tb.v"), tb.getBytes(UTF_8))
    run(dir, Seq("iverilog", "-g2012", "-s", "tb", "-o", "oracle.vvp", "DerivedMutationProbe.v", "tb.v"))
    assert(run(dir, Seq("vvp", "oracle.vvp")).contains("DERIVED_ORACLE_PASS"))
    for ((label, value) <- Seq("frozen" -> "24", "operator" -> "(A * (B * 2))",
        "root-swap" -> "(B + (A * 2))", "wrong-binding" -> "A")) {
      // Deliberate negative DUTs live beside the immutable generated candidate.
      Files.write(dir.resolve("mutant.v"), rtl.replace(original,
        s"localparam integer TOTAL_BITS = $value;").getBytes(UTF_8))
      run(dir, Seq("iverilog", "-g2012", "-s", "tb", "-o", "mutant.vvp", "mutant.v", "tb.v"))
      val log = new StringBuilder
      val code = Process(Seq("vvp", "mutant.vvp"), dir.toFile).!(ProcessLogger(line => log.append(line).append('\n')))
      Files.write(dir.resolve(s"mutation-$label.log"), log.toString.getBytes(UTF_8))
      assert(code != 0 && log.toString.contains("DERIVED_MUTATION_DETECTED"), label + ": " + log)
    }
    assert(new String(Files.readAllBytes(dir.resolve("DerivedMutationProbe.v")), UTF_8) == rtl)
  }

  test("names cannot transfer to public expression copies or another component") {
    var owner: Component = null
    var value: ElabInt = null
    emit(Files.createTempDirectory("derived-localparam-identity-"), true)(new Component {
      owner = this
      val width: ElabInt = HdlInt.param("WIDTH", 3, 1, 16).asElabInt
      val totalBits: ElabInt = width + 1
      value = totalBits
      val din = in Bits(totalBits bits)
      val dout = out Bits(totalBits bits)
      dout := din
    })
    assert(NativeLocalParameters.reference(owner, value.expression).contains("TOTAL_BITS"))
    assert(NativeLocalParameters.reference(owner, value.expression.copy()).isEmpty)
    var other: Component = null
    emit(Files.createTempDirectory("derived-localparam-other-"), true)(new Component {
      other = this
      val width: ElabInt = HdlInt.param("OTHER_WIDTH", 3, 1, 16).asElabInt
      val din = in Bits(width bits)
      val dout = out Bits(width bits)
      dout := din
    })
    assert(NativeLocalParameters.reference(other, value.expression).isEmpty)
  }

  test("a cyclic calculation graph is rejected before publication") {
    val failure = intercept[Exception] {
      emit(Files.createTempDirectory("derived-localparam-cycle-"), true)(new Component {
        val width: ElabInt = HdlInt.param("WIDTH", 3, 1, 16).asElabInt
        val totalBits: ElabInt = width + 1
        totalBits.expression.localCalculation = Some(NativeLocalParameters.Calculation("+",
          Vector(totalBits.expression, ElabInt.literal(1).expression)))
        val din = in Bits(totalBits bits)
        val dout = out Bits(totalBits bits)
        dout := din
      })
    }
    val messages = Iterator.iterate[Throwable](failure)(_.getCause).takeWhile(_ != null)
      .map(_.getMessage).mkString("\n")
    assert(messages.contains("cycle in typed local parameter calculations"), messages)
  }

  test("restricted branch calculations stay within their generate owner") {
    class ScopedSink(width: ElabInt) extends Component {
      val din = in Bits(width bits)
      val observed = out Bool()
      observed := din.orR
    }
    val dir = Files.createTempDirectory("derived-localparam-scope-")
    val rtl = emit(dir, true)(new Component {
      setDefinitionName("DerivedScoped")
      val width: ElabInt = HdlInt.param("WIDTH", 2, 1, 4).asElabInt
      val din = in Bits(width bits)
      val dout = out Bits(width bits)
      dout := din
      if (width > 1) {
        val branchBits: ElabInt = width - 1
        val child = ElabFormalComponent.parameter(branchBits, "LOCAL_WIDTH", 1, 3)(w => new ScopedSink(w))
        val branchData = Bits(branchBits bits)
        branchData.setName("branchData")
        branchData.dontSimplifyIt()
        branchData := din.resize(branchBits)
        child.din := branchData
      } else {
        val child = ElabFormalComponent.parameter(width, "LOCAL_WIDTH", 1, 3)(w => new ScopedSink(w))
        val bypassData = Bits(width bits)
        bypassData.setName("bypassData")
        bypassData.dontSimplifyIt()
        bypassData := din.resize(width)
        child.din := bypassData
      }
    })
    assert(!rtl.contains("localparam integer BRANCH_BITS"), rtl)
    assert(rtl.contains("generate") && rtl.contains("WIDTH - 1"), rtl)
    import scala.collection.JavaConverters._
    val paths = Files.list(dir)
    val sources = try paths.iterator().asScala.filter(_.toString.endsWith(".v"))
      .map(_.toString).toVector finally paths.close()
    for (width <- Seq(1, 2, 4))
      run(dir, Seq("iverilog", "-g2001", "-DSYNTHESIS", "-s", "DerivedScoped",
        s"-PDerivedScoped.WIDTH=$width", "-o", "scope.vvp") ++ sources)
  }

  test("negative intermediate local parameters preserve signed integer arithmetic") {
    val dir = Files.createTempDirectory("derived-localparam-signed-")
    val rtl = emit(dir, true)(new Component {
      setDefinitionName("DerivedSignedOffset")
      val a: ElabInt = HdlInt.param("A", 4, 1, 8).asElabInt
      val b: ElabInt = HdlInt.param("B", 4, 1, 8).asElabInt
      val signedOffset: ElabInt = a - b
      val totalBits: ElabInt = signedOffset + 16
      val din = in Bits(totalBits bits)
      val dout = out Bits(totalBits bits)
      dout := din
    })
    assert(rtl.contains("localparam integer SIGNED_OFFSET = (A - B);"), rtl)
    assert(rtl.contains("localparam integer TOTAL_BITS = (SIGNED_OFFSET + 16);"), rtl)
    Files.write(dir.resolve("tb.v"), """module tb;
      |wire [8:0] result;
      |DerivedSignedOffset #(.A(1),.B(8)) dut(9'h1a5,result);
      |initial begin #1;
      |if ($bits(dut.din) != 9 || dut.SIGNED_OFFSET != -7 || dut.TOTAL_BITS != 9 || result !== 9'h1a5) $fatal(1,"signed local arithmetic");
      |$display("SIGNED_LOCAL_PASS"); $finish;
      |end
      |endmodule
      |""".stripMargin.getBytes(UTF_8))
    run(dir, Seq("iverilog", "-g2012", "-s", "tb", "-o", "signed.vvp", "DerivedSignedOffset.v", "tb.v"))
    assert(run(dir, Seq("vvp", "signed.vvp")).contains("SIGNED_LOCAL_PASS"))
    for ((label, original, replacement) <- Seq(
        ("lost-sign", "SIGNED_OFFSET = (A - B)", "SIGNED_OFFSET = ((A >= B) ? (A - B) : 0)"),
        ("lost-carry", "TOTAL_BITS = (SIGNED_OFFSET + 16)", "TOTAL_BITS = ((SIGNED_OFFSET + 16) & 7)"))) {
      assert(rtl.contains(original))
      Files.write(dir.resolve("mutant.v"), rtl.replace(original, replacement).getBytes(UTF_8))
      run(dir, Seq("iverilog", "-g2012", "-s", "tb", "-o", "mutant.vvp", "mutant.v", "tb.v"))
      val log = new StringBuilder
      val code = Process(Seq("vvp", "mutant.vvp"), dir.toFile)
        .!(ProcessLogger(line => log.append(line).append('\n')))
      Files.write(dir.resolve(s"mutation-$label.log"), log.toString.getBytes(UTF_8))
      assert(code != 0 && log.toString.contains("signed local arithmetic"), label + ": " + log)
    }
    assert(new String(Files.readAllBytes(dir.resolve("DerivedSignedOffset.v")), UTF_8) == rtl)
  }

  test("a derived value used only by an external generic retains its parent local") {
    val dir = Files.createTempDirectory("derived-localparam-external-")
    val rtl = emit(dir, true)(new Component {
      setDefinitionName("DerivedExternalTop")
      val base: ElabInt = HdlInt.param("BASE", 1, 1, 8).asElabInt
      val adjustedLatency: ElabInt = base + 1
      val din = in Bits(8 bits)
      val dout = out Bits(8 bits)
      val external = new BlackBox {
        setBlackBoxName("DerivedExternalLeaf")
        addGeneric("LATENCY", adjustedLatency)
        val din = in Bits(8 bits)
        val dout = out Bits(8 bits)
      }
      external.din := din
      dout := external.dout
    })
    assert(rtl.contains("localparam integer ADJUSTED_LATENCY = (BASE + 1);"), rtl)
    assert(rtl.replaceAll("\\s+", "").contains(".LATENCY(ADJUSTED_LATENCY)"), rtl)
    assert(!rtl.contains("module DerivedExternalLeaf"), rtl)
    Files.write(dir.resolve("leaf.v"), """module DerivedExternalLeaf #(parameter integer LATENCY=1)
      |(input wire [7:0] din, output wire [7:0] dout);
      |assign dout=din;
      |endmodule
      |module tb;
      |wire [7:0] result;
      |DerivedExternalTop #(.BASE(8)) dut(8'ha5,result);
      |initial begin #1;
      |if (dut.external.LATENCY != 9 || result !== 8'ha5) $fatal(1,"external binding");
      |$display("EXTERNAL_LOCAL_PASS"); $finish;
      |end
      |endmodule
      |""".stripMargin.getBytes(UTF_8))
    run(dir, Seq("iverilog", "-g2012", "-s", "tb", "-o", "external.vvp", "DerivedExternalTop.v", "leaf.v"))
    assert(run(dir, Seq("vvp", "external.vvp")).contains("EXTERNAL_LOCAL_PASS"))
  }
}
