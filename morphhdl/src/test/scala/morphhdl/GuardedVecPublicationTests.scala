package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class GuardedVecPublicationTests extends AnyFunSuite {
  private def directory(prefix: String): java.nio.file.Path = sys.env.get("MORPHHDL_VEC_EVIDENCE") match {
    case Some(path) => Files.createTempDirectory(Files.createDirectories(java.nio.file.Paths.get(path)), prefix)
    case None => Files.createTempDirectory(prefix)
  }
  private def generate(mode: String, unpacked: Boolean): (java.nio.file.Path, String) = {
    val dir = directory("guarded-vec-" + mode)
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString, headerWithDate=false),
      preserveConstantVecs=true, preserveConstantLoops=true, vecLayout=if(unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector)) {
      new Component {
        setDefinitionName("GuardedVec")
        @dontName val choice = morphhdl.frontend.HdlInt.param("CHOICE", 1, 0, 2).asElabInt
        val terms = in Bits(64 bits)
        val result = out Bits(64 bits)
        val enable = in Bool()
        if(mode.startsWith("signed")) {
          val lanes = Vec.tabulate(8)(_ => SInt(8 bits).dontSimplifyIt())
          for(i <- 0 until 8) {
            if(mode == "signed-partial") {
              lanes(i).assignFromBits(terms(i*8, 4 bits), 3, 0)
              lanes(i).assignFromBits(terms(i*8+4, 4 bits), 7, 4)
            } else if(mode == "signed-bits") lanes(i).assignFromBits(terms(i*8, 8 bits))
            else lanes(i) := terms(i*8, 8 bits).asSInt
          }
          for(i <- 0 until 8) result(i*8, 8 bits) := (lanes(i) < S(0, 8 bits)).asBits.resize(8)
        } else {
          val bank = if(mode == "promoted") Some(Vec.tabulate(8)(_ => Bits(8 bits).dontSimplifyIt()).setName("lanes")) else None
          val lanes = bank.map(_.toVector).getOrElse(Vector.fill(8)(Bits(8 bits).dontSimplifyIt()))
          for(i <- 0 until 8) {
            val source = terms(i*8, 8 bits).dontSimplifyIt()
            if (mode == "promoted" && i == 7) lanes(i) := 0
            else if (mode == "promoted" && i >= 4) {
              lanes(i) := 0
              when(enable) { lanes(i) := source }
            }
            else for(level <- 0 until 3) {
              ElabControl.generateSymbolic(if(mode=="overlap" && level==1) choice >= 0 else choice.elabEq(level), "GuardedVec", level+1) {
                if(mode == "promoted" && level == 0) lanes(i) := 0
                else if(mode == "promoted" && level == 1) lanes(i) := RegNext(source) init(0)
                else lanes(i) := source
              }
            }
            if(mode != "promoted") result(i*8, 8 bits) := lanes(i)
          }
          bank.foreach(vector => result := vector.asBits)
        }
      }
    }
    (dir, new String(Files.readAllBytes(dir.resolve("GuardedVec.v")), "UTF-8"))
  }
  for(peer <- Seq(false, true); default <- Seq(1,3))
  test(s"scalar-formal recursive definitions share across defaults peer=$peer default=$default") {
    val dir = directory("guarded-sharing-")
    class Tree(actual: ElabInt, maskActual: ElabInt) extends Component {
      setDefinitionName("ScalarTree")
      @dontName val count = ElabFormalParameter(actual, "COUNT", 1, 8)
      @dontName val mask = ElabFormalParameter(maskActual, "MASK", 0, 1)
      val x = in UInt(8 bits)
      val y = out UInt(8 bits)
      class Next extends BlackBox {
        setBlackBoxName("ScalarTree")
        addGeneric("COUNT", (count+1)/2)
        addGeneric("MASK", mask)
        val x = in UInt(8 bits)
        val y = out UInt(8 bits)
      }
      ElabControl.selectSymbolic(count <= 1, "ScalarTree", 1) { y := x } {
        val child = new Next
        child.x := x
        ElabControl.selectSymbolic(mask.elabEq(0), "ScalarTree", 2) { y := child.y + 1 } { y := child.y + 2 }
      }
    }
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString)) {
      new Component {
        setDefinitionName("SharingTop")
        val x = in UInt(8 bits)
        val a = out UInt(8 bits)
        val b = out UInt(8 bits)
        val first = new Tree(ElabInt.literal(default), ElabInt.literal(0))
        first.x := x
        a := first.y
        if(peer) {
          val second = new BlackBox {
            setBlackBoxName("ScalarTree")
            addGeneric("COUNT", ElabInt.literal(7))
            addGeneric("MASK", ElabInt.literal(1))
            val x = in UInt(8 bits)
            val y = out UInt(8 bits)
          }
          ElabModuleReference.bind(second, first)
          second.x := x; b := second.y
        } else {
          val second = new Tree(ElabInt.literal(7), ElabInt.literal(1))
          second.x := x; b := second.y
        }
      }
    }
    val rtl = dir.toFile.listFiles().filter(_.getName.endsWith(".v")).map(f =>
      new String(Files.readAllBytes(f.toPath), "UTF-8")).mkString("\n")
    assert("(?m)^module ScalarTree".r.findAllIn(rtl).size == 1, rtl)
    tools(dir, "SharingTop", s"""module tb;
reg [7:0] x; wire [7:0] a,b; reg [7:0] ea,eb; integer n;
SharingTop dut(x,a,b);
initial begin
for(n=0;n<256;n=n+1) begin x=n; ea=x+8'd${if(default==1) 0 else 2}; eb=x+8'd6; #2;
if(a !== ea || b !== eb) $$fatal; end
x=8'bx; #2; if(a !== 8'bx || b !== 8'bx) $$fatal;
x=8'bz; #2; if(a !== 8'b${if(default==1) "z" else "x"} || b !== 8'bx) $$fatal;
$$display("PASS"); $$finish; end endmodule
""", Seq.empty)
  }
  test("partial signed writes remain rejected separately from whole-scalar writes") {
    val error = intercept[MorphVerilogException] { generate("signed-partial", true) }
    assert(error.getMessage.contains("VEC-STATIC-WRITE-EVIDENCE-MISMATCH"), error.getMessage)
  }
  test("overlapping structural writers remain rejected") {
    val error = intercept[MorphVerilogException] { generate("overlap", true) }
    assert(error.getMessage.contains("ASSIGNMENT OVERLAP"), error.getMessage)
  }

  private def tools(dir: java.nio.file.Path, top: String, bench: String, parameters: Seq[(String,Int)]): Unit = {
    Files.write(dir.resolve("tb.v"), bench.getBytes("UTF-8"))
    val commands = Seq(
      Seq("iverilog", "-g2001", "-s", "tb", "-o", "sim", s"$top.v", "tb.v"),
      Seq("vvp", "sim"),
      Seq("verilator", "--lint-only", "--language", "1364-2001", "-DSYNTHESIS", "-Wno-fatal", "--top-module", top) ++
        parameters.map { case (name,value) => s"-G$name=$value" } ++ Seq(s"$top.v"),
      Seq("yosys", "-Q", "-T", "-p", s"read_verilog $top.v; " +
        parameters.map { case (name,value) => s"chparam -set $name $value $top; " }.mkString +
        s"hierarchy -check -top $top; proc; select -assert-none t:$$dlatch; synth; check -assert"))
    commands.zipWithIndex.foreach { case (command, index) =>
      val (status, output) = Increment66ToolEvidence.run(dir, command)
      Files.write(dir.resolve(s"tools-${parameters.mkString}-$index.log"),
        (command.mkString(" ")+"\n"+output).getBytes("UTF-8"))
      assert(status == 0, command.mkString(" ")+"\n"+output)
      if(command.head == "vvp") assert(output.contains("PASS"), output)
    }
  }

  for(mode <- Seq("bridge", "promoted", "signed-bits", "signed-direct"); unpacked <- Seq(false,true))
  test(s"guarded Vec publication $mode unpacked=$unpacked") {
    val (dir, rtl) = generate(mode, unpacked)
    assert(rtl == generate(mode, unpacked)._2)
    assert(!rtl.contains("native_edge_"), rtl)
    for(choice <- (if(mode.startsWith("signed")) Seq(0) else Seq(0,1,2))) {
      val parameter = if(mode.startsWith("signed")) "" else s"#(.CHOICE($choice))"
      val clock = if(mode=="promoted") ",.clk(clk),.reset(reset)" else ""
      val expected = if(mode.startsWith("signed"))
        "for(j=0;j<8;j=j+1) expected[j*8 +: 8] = {7'b0, ($signed(terms[j*8 +: 8]) < 8'sd0)};"
        else if(mode=="bridge") "expected=terms;"
        else s"expected[63:56]=0; expected[55:32]=enable ? terms[55:32] : 24'd0; expected[31:0]=" +
          (if(choice==0) "32'd0;" else if(choice==1) "reset ? 32'd0 : terms[31:0];" else "terms[31:0];")
      tools(dir, "GuardedVec", s"""module tb;
reg [63:0] terms; reg enable,clk,reset; wire [63:0] result; reg [63:0] expected; integer n,j;
GuardedVec $parameter dut(.terms(terms),.result(result),.enable(enable)$clock);
initial begin clk=0; reset=1; enable=0; terms=0; #2;
for(n=0;n<150;n=n+1) begin
clk=0; reset=(n%13==0); enable=n%2;
case(n)
0:terms=0; 1:terms=64'hffffffffffffffff; 2:terms=64'h7f80817eff00aa55;
3:terms=64'bx; 4:terms=64'bz;
default:terms={$$random,$$random}; endcase
#2; clk=1; #2; expected=0; $expected
if(result !== expected) begin $$display("FAIL %h %h",result,expected); $$fatal; end
end
$$display("PASS"); $$finish; end endmodule
""", if(parameter.isEmpty) Seq.empty else Seq("CHOICE" -> choice))
    }
  }
}
