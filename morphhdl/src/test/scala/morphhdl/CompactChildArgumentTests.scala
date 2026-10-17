package morphhdl

import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class CompactArgumentLeaf(actual: ElabInt) extends Component {
  val limit: Param[Int] = Param(actual, "LIMIT")
  require(limit >= 1 && limit <= 16777216)
  val source = in UInt(25 bits)
  val result = out Bool()
  result := source < U(limit, 25 bits)
}
class CompactArgumentParent(logarithmic: Boolean = false, direct: Boolean = false) extends Component {
  val timeout: Param[Int] = Param(1024)
  require(timeout >= 32 && timeout <= 16777216)
  val source = in UInt(25 bits)
  val result = out Bool()
  val child = new CompactArgumentLeaf(if(direct) timeout else if(logarithmic) (timeout + 1).log2Up else timeout - 1)
  child.source := source
  result := child.result
}
class CompactChildArgumentTests extends AnyFunSuite {
  private def generate(body: => Component): Path = {
    val dir = Files.createTempDirectory("compact-child-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString, headerWithDate=false))(body)
    dir
  }
  private def run(dir: Path, args: String*): Unit = {
    val result = Increment66ToolEvidence.run(dir,args)
    assert(result._1 == 0,args.mkString(" ")+"\n"+result._2)
  }
  test("large derived child actual retains its parent parameter binding across overrides") {
    for((logarithmic,direct) <- Seq((false,false),(true,false),(false,true))) {
      val dir = generate(new CompactArgumentParent(logarithmic,direct))
      val rtl = new String(Files.readAllBytes(dir.resolve("CompactArgumentParent.v")), "UTF-8")
      assert(rtl.contains(if(direct) ".LIMIT" else if(logarithmic) "TIMEOUT + 1" else "TIMEOUT - 1"), rtl)
      val again = generate(new CompactArgumentParent(logarithmic,direct))
      assert(rtl == new String(Files.readAllBytes(again.resolve("CompactArgumentParent.v")),"UTF-8"))
      for(timeout <- Seq(32,33,1023,1024,65536,16777215,16777216)) {
        val limit = if(direct) timeout else if(logarithmic) BigInt(timeout).bitLength else timeout - 1
        Files.write(dir.resolve("tb.v"),s"""module tb;reg[24:0] source;wire result;integer i;
CompactArgumentParent #(.TIMEOUT($timeout)) dut(.source(source),.result(result));
initial begin
source=0;#1;if(result!==1)$$fatal(1,"zero");
source=$limit-1;#1;if(result!==1)$$fatal(1,"below");
source=$limit;#1;if(result!==0)$$fatal(1,"at");
source=25'h1ffffff;#1;if(result!==0)$$fatal(1,"maximum");
for(i=0;i<32;i=i+1)begin source=$$random;#1;if(result!==(source<$limit))$$fatal(1,"random");end
$$finish;end endmodule
""".getBytes("UTF-8"))
        run(dir,"iverilog","-g2001","-s","tb","-o","sim","CompactArgumentParent.v","tb.v")
        run(dir,"vvp","sim")
      }
      run(dir,"verilator","--lint-only","--language","1364-2001","-Wno-fatal","-DSYNTHESIS","CompactArgumentParent.v")
      run(dir,"yosys","-Q","-T","-p","read_verilog CompactArgumentParent.v; hierarchy -check -top CompactArgumentParent; proc; opt; check -assert; select -assert-none t:$dlatch")
    }
  }
  test("child domain guards reject invalid overrides rather than freezing the construction default") {
    val dir=generate(new CompactArgumentParent)
    for(limit <- Seq(0,16777217)) {
      Files.write(dir.resolve("tb.v"),s"""module tb;wire result;
CompactArgumentLeaf #(.LIMIT($limit)) dut(.source(25'd0),.result(result));
initial begin #2;$$finish;end endmodule
""".getBytes("UTF-8"))
      run(dir,"iverilog","-g2001","-s","tb","-o","sim","CompactArgumentParent.v","tb.v")
      val result=Increment66ToolEvidence.run(dir,Seq("vvp","sim"))
      assert(result._1==1 && result._2.contains("FATAL:"),result._2)
    }
  }
  test("captured compact child arguments remain unsupported without structural authority") {
    val error=intercept[Exception]{generate(new Component {
      setDefinitionName("CapturedCompactArgument")
      val timeout: Param[Int]=Param(1024)
      require(timeout>=32 && timeout<=16777216)
      val mode: Param[Int]=Param(1)
      require(mode>=1 && mode<=2)
      val source=in UInt(25 bits);val result=out Bool()
      if(mode==1) {
        val child=new CompactArgumentLeaf(timeout-1)
        child.source:=source
        result:=child.result
      } else {result:=False}
    })}
    assert(error.toString.contains("COMPACT-SCOPE-UNSUPPORTED"),error.toString)
  }
  test("foreign compound child arguments give exact binding diagnostics") {
    val error=intercept[Exception]{generate(new Component {
      setDefinitionName("ForeignCompactArgument")
      val timeout: Param[Int]=Param(1024)
      require(timeout>=32 && timeout<=16777216)
      val sibling=new Component {
        val timeout: Param[Int]=Param(1024)
        require(timeout>=32 && timeout<=16777216)
        val result=out UInt(25 bits);result:=U(timeout,25 bits)
      }
      val child=new CompactArgumentLeaf(sibling.timeout-1)
      child.source:=sibling.result
      val result=out Bool();result:=child.result
    })}
    val text=error.toString
    for(fragment<-Seq("COMPACT-OWNER-MISMATCH","ForeignCompactArgument","child","LIMIT","TIMEOUT - 1","TIMEOUT=[32,16777216]","CompactChildArgumentTests.scala"))
      assert(text.contains(fragment),text)
  }
}
