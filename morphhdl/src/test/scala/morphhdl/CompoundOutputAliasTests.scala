package morphhdl

import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class CompoundAliasChild(actualA: ElabInt, actualB: ElabInt, kind: String) extends Component {
  val a: Param[Int] = Param(actualA, "A")
  require(a >= 1 && a <= 16)
  val b: Param[Int] = Param(actualB, "B")
  require(b >= 1 && b <= 16)
  def word: BitVector = kind match {
    case "UInt" => UInt((a + b) bits)
    case "SInt" => SInt((a + b) bits)
    case _ => Bits((a + b) bits)
  }
  val source = in(word)
  val result = out(word)
  result.assignFromBits(~source.asBits)
}
class CompoundAliasTop(symbolic: Boolean, kind: String, bad: String = "") extends Component {
  val p: Param[Int] = Param(8)
  require(p >= 1 && p <= 16)
  val q: Param[Int] = Param(4)
  require(q >= 1 && q <= 16)
  val a: ElabInt = if(symbolic) p else ElabInt.literal(8)
  val b: ElabInt = if(symbolic) q else ElabInt.literal(4)
  def word: BitVector = kind match {
    case "UInt" => UInt((a + b) bits)
    case "SInt" => SInt((a + b) bits)
    case _ => Bits((a + b) bits)
  }
  val source = in(word)
  val result = out(word)
  val otherResult = out(word)
  val first = new CompoundAliasChild(a,b,kind)
  val second = new CompoundAliasChild(b,a,kind)
  first.source.assignFrom(source)
  second.source.assignFrom(source)
  val alias = cloneOf(first.result).dontSimplifyIt()
  alias.assignFrom(first.result)
  val otherAlias = cloneOf(second.result).dontSimplifyIt()
  otherAlias.assignFrom(second.result)
  result.assignFrom(alias)
  otherResult.assignFrom(otherAlias)
  if(bad == "foreign") {
    val unrelated = RtlParam.int("UNRELATED",8,1,16)
    val invalid = Bits((unrelated + b) bits).dontSimplifyIt()
    invalid := first.result.asBits
    val extra = out Bits((unrelated + b) bits)
    extra := invalid
  }
  if(bad == "mismatch") {
    val invalid = Bits((a + b + 1) bits).dontSimplifyIt()
    invalid := first.result.asBits
    val extra = out Bits((a + b + 1) bits)
    extra := invalid
  }
}

class CompoundOutputAliasTests extends AnyFunSuite {
  private def generate(symbolic: Boolean, kind: String, bad: String = ""): Path = {
    val dir=Files.createTempDirectory("compound-alias-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString, headerWithDate=false))(new CompoundAliasTop(symbolic,kind,bad))
    dir
  }
  private def run(dir:Path,args:String*):Unit = {
    val r=Increment66ToolEvidence.run(dir,args)
    assert(r._1==0,args.mkString(" ")+"\n"+r._2)
  }
  test("compound child output clones preserve literal and symbolic actuals for native packed types") {
    for(symbolic <- Seq(false,true);kind <- Seq("Bits","UInt","SInt")) {
      val dir=generate(symbolic,kind)
      val rtl=new String(Files.readAllBytes(dir.resolve("CompoundAliasTop.v")),"UTF-8")
      assert("(?m)^module CompoundAliasChild\\b".r.findAllIn(rtl).length==1)
      assert(rtl==new String(Files.readAllBytes(generate(symbolic,kind).resolve("CompoundAliasTop.v")),"UTF-8"))
      if(kind=="SInt") assert(rtl.contains("signed"))
      for((p,q)<- (if(symbolic) Seq((1,1),(1,16),(8,4),(16,1),(16,16)) else Seq((8,4)))) {
        val width=p+q
        val parameters=if(symbolic)s"#(.P($p),.Q($q))" else ""
        Files.write(dir.resolve("tb.v"),s"""module tb;reg[$width-1:0] source;wire[$width-1:0] result,otherResult;integer i;
CompoundAliasTop $parameters dut(.source(source),.result(result),.otherResult(otherResult));
initial begin
source=0;#1;if(result!==~source || otherResult!==~source)$$fatal(1,"zero");
source='bx;#1;if(result!==~source || otherResult!==~source)$$fatal(1,"unknown");
source='bz;#1;if(result!==~source || otherResult!==~source)$$fatal(1,"high impedance");
for(i=0;i<50;i=i+1)begin source=$$random;#1;if(result!==~source || otherResult!==~source)$$fatal(1,"data");end
$$finish;end endmodule
""".getBytes("UTF-8"))
        run(dir,"iverilog","-g2001","-s","tb","-o","sim","CompoundAliasTop.v","tb.v")
        run(dir,"vvp","sim")
      }
      run(dir,"verilator","--lint-only","--language","1364-2001","-Wno-fatal","-DSYNTHESIS","CompoundAliasTop.v")
      run(dir,"yosys","-Q","-T","-p","read_verilog CompoundAliasTop.v; hierarchy -check -top CompoundAliasTop; proc; opt; check -assert; select -assert-none t:$dlatch")
    }
  }
  test("unrelated same-witness roots and mismatched widths remain rejected") {
    for(bad<-Seq("foreign","mismatch")) {
      val error=intercept[Exception]{generate(true,"Bits",bad)}
      assert(error.toString.contains("CONFLICT") || error.toString.contains("WIDTH MISMATCH"),error.toString)
    }
  }
}
