package morphhdl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import morphhdl.frontend.{HdlInt, formalParam}

class VecWordPublicationTests extends AnyFunSuite {
  private def generate(kind: String, unpacked: Boolean = true): (Path, String) = {
    val dir = sys.env.get("MORPHHDL_VEC_EVIDENCE") match {
      case Some(root) => Files.createDirectories(Paths.get(root)); Files.createTempDirectory(Paths.get(root), s"$kind-$unpacked-")
      case None => Files.createTempDirectory("vec-word-" + kind)
    }
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString, headerWithDate=false),
      preserveConstantVecs=true, preserveConstantLoops=true,
      vecLayout=if(unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector))(new Component {
      setDefinitionName("Top")
      @dontName val ports = HdlInt.param("PORTS", 8, 1, 16)
      val deltaWidth = ports.asElabInt.log2Up + 32
      val probe = in Bits(ports.asElabInt bits)
      val parity = out Bool()
      parity := probe.xorR
      val x = in UInt(36 bits)
      val y = out UInt(36 bits)
      if(kind == "matrix") {
        val terms = Vec.tabulate(3, 5)((row, col) => (x + U(row * 5 + col, 36 bits)).dontSimplifyIt())
        terms.setName("terms")
        y := terms(0)(0) + terms(1)(1) + terms(2)(4)
      } else if(kind.startsWith("alias")) {
        val nodes = (0 until 3).map { i =>
          val node = x + U(i, 36 bits)
          if(kind == "alias") node.dontSimplifyIt() else node
        }
        val consumed = nodes(0) + nodes(1) + nodes(2)
        val grouped = Vec.tabulate(3)(nodes(_))
        grouped.setName("grouped")
        y := consumed + grouped(2) + nodes(0)
      } else {
        @dontName val width = ports.ceilLog2 + HdlInt.literal(32)
        val arrayWidth = if(kind == "child-local") deltaWidth else ports.asElabInt.log2Up + 32
        class Child(actual: HdlInt) extends Component {
          setDefinitionName("Child")
          @dontName val bits = formalParam(actual, "WIDTH", 32, 36)
          val delta = in UInt(bits.asElabInt bits)
          val result = out UInt(36 bits)
          result := (~delta).resize(36)
        }
        val deltas = Vec.tabulate(3)(_ => x.resize(arrayWidth).dontSimplifyIt())
        deltas.setName("deltas")
        val children = (0 until 3).map { i =>
          val child = new Child(width)
          child.delta := deltas(i)
          val result = UInt(36 bits).dontSimplifyIt()
          result := child.result
          result
        }
        y := children(0) ^ children(1) ^ children(2)
      }
    })
    (dir, new String(Files.readAllBytes(dir.resolve("Top.v")), UTF_8))
  }

  private def tools(dir: Path, kind: String, ports: Int): Unit = {
    val expected = kind match {
      case "matrix" => "x + x + x + 36'd20"
      case value if value.startsWith("alias") => "x + x + x + x + x + 36'd5"
      case _ => "unused"
    }
    // Child results zero-extend a truncated, inverted input. The flat reference
    // uses an independently computed host width only in this testbench.
    val width = 32 + (32 - Integer.numberOfLeadingZeros(ports - 1))
    val reference = if(kind.startsWith("child")) s"((~x) & ((36'hfffffffff) >> (36 - $width)))" else expected
    Files.write(dir.resolve("tb.v"), s"""module tb;
reg [35:0] x; reg [${ports-1}:0] probe; wire [35:0] y; wire parity; reg [35:0] expected; integer i;
Top #(.PORTS($ports)) dut(probe,parity,x,y);
task check;
begin #1; expected = $reference; if(y !== expected || parity !== ^probe) begin
$$display("FAIL %h %h", y, expected); $$fatal; end end
endtask
initial begin
probe=0; x=0; check; x=36'hfffffffff; check;
for(i=0;i<36;i=i+1) begin x=36'b1<<i; check; end
for(i=0;i<40;i=i+1) begin x={$$random,$$random}; probe=$$random; check; end
x=36'bx; check; x=36'bz; check;
$$display("PASS"); $$finish;
end
endmodule
""".getBytes(UTF_8))
    val commands = Seq(
      Seq("iverilog", "-g2001", "-s", "tb", "-o", "sim", "Top.v", "tb.v"),
      Seq("vvp", "sim"),
      Seq("verilator", "--lint-only", "--language", "1364-2001", "-Wno-fatal", "--top-module", "Top", s"-GPORTS=$ports", "Top.v"),
      Seq("yosys", "-Q", "-T", "-p", s"read_verilog Top.v; chparam -set PORTS $ports Top; hierarchy -check -top Top; proc; select -assert-none t:$$dlatch; synth; check -assert"))
    commands.zipWithIndex.foreach { case (command, index) =>
      val (status, output) = Increment66ToolEvidence.run(dir, command)
      Files.write(dir.resolve(s"tools-$ports-$index.log"), (command.mkString(" ")+"\n"+output).getBytes(UTF_8))
      assert(status == 0, command.mkString(" ") + "\n" + output)
      if(command.head == "vvp") assert(output.contains("PASS"), output)
    }
  }

  for(unpacked <- Seq(false,true)) test(s"constant multidimensional whole words retain direct element accesses unpacked=$unpacked") {
    val (dir, rtl) = generate("matrix", unpacked)
    if(unpacked) {
      assert(rtl.contains("terms[1][1]") && rtl.contains("terms[2][4]"), rtl)
      assert(!rtl.contains("terms_write") && !rtl.contains("terms_read"), rtl)
    }
    assert(rtl == generate("matrix", unpacked)._2)
    tools(dir, "matrix", 8)
  }
  for(unpacked <- Seq(false,true)) {
    for(kind <- Seq("alias", "alias-pruned")) test(s"already consumed nodes retain authenticated construction aliases kind=$kind unpacked=$unpacked") {
      val (dir, rtl) = generate(kind, unpacked)
      assert(!rtl.contains("grouped_0"), rtl)
      assert(rtl == generate(kind, unpacked)._2)
      tools(dir, kind, 8)
    }
    for(kind <- Seq("child", "child-local")) test(s"symbolic Vec element child bindings compile kind=$kind unpacked=$unpacked") {
      val (dir, rtl) = generate(kind, unpacked)
      if(kind == "child-local") {
        assert(rtl.contains("localparam integer DELTA_WIDTH"), rtl)
        assert(rtl.contains(".WIDTH(DELTA_WIDTH)"), rtl)
        assert(!rtl.contains("parameter integer DELTA_WIDTH"), rtl)
      }
      if(unpacked) assert(!rtl.contains("deltas_read") && !rtl.contains("deltas_packed_view"), rtl)
      assert(!"\\]\\s*\\[".r.findFirstIn(rtl.linesIterator.filter(_.contains(".delta ")).mkString).nonEmpty, rtl)
      assert(rtl == generate(kind, unpacked)._2)
      (1 to 16).foreach(ports => tools(dir, kind, ports))
    }
  }

  test("construction aliases reject repeated and foreign node identities") {
    for(foreign <- Seq(false,true)) {
      val error = intercept[Throwable] {
        val dir = Files.createTempDirectory("vec-alias-invalid-")
        MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString), preserveConstantVecs=true))(new Component {
          val x = in UInt(8 bits)
          val y = out UInt(8 bits)
          val node = if(foreign) {
            val child = new Component { val result = out UInt(8 bits); result := 1 }
            child.result
          } else x
          val grouped = Vec.tabulate(2)(_ => node)
          y := grouped(0)
        })
      }
      assert(error.toString.contains("CONSTRUCTION-ALIAS-INVALID"), error.toString)
    }
  }
  test("unrecorded raw carrier access still fails without a construction alias") {
    val error = intercept[Throwable] {
      val dir = Files.createTempDirectory("vec-raw-carrier-")
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString), preserveConstantVecs=true))(new Component {
        @dontName val width = HdlInt.param("WIDTH", 8, 1, 16).asElabInt
        val x = in UInt(width bits)
        val y = out UInt(width bits)
        val values = Vec.fill(2)(UInt(width bits).dontSimplifyIt())
        values.vec.foreach(_ := x)
        y := values.vec.head
      })
    }
    assert(error.toString.contains("VEC-RESIDUAL-CARRIER-REFERENCE"), error.toString)
  }

}
