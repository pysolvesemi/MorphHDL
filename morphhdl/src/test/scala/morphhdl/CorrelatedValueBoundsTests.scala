package morphhdl

import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import morphhdl.frontend.formalParam

class CorrelatedThreshold(actualDepth: ElabInt, actualMargin: ElabInt, constrained: Boolean = true,
    disjunctive: Boolean = false, carrierBits: Int = 18) extends Component {
  setDefinitionName("CorrelatedThreshold")
  @dontName val depth: ElabInt = formalParam(actualDepth, "DEPTH", 2, 16)
  @dontName val margin: ElabInt = formalParam(actualMargin, "MARGIN", 0, 65537)
  val capacity = depth.pow2 + 1
  if (constrained) {
    if (disjunctive) require(margin < capacity || margin >= capacity)
    else require(margin >= 1 && margin < capacity)
  }
  val result = out UInt(carrierBits bits)
  result := ElabValue.uintLike(capacity - margin, U(0, carrierBits bits), "threshold")
}

class CorrelatedThresholdParent extends Component {
  setDefinitionName("CorrelatedThresholdParent")
  val depth: Param[Int] = Param(3)
  val margin: Param[Int] = Param(2)
  require(depth >= 2 && depth <= 16)
  require(margin >= 1 && margin <= 65536)
  require(margin < depth.pow2 + 1)
  val result = out UInt(18 bits)
  val source = in UInt(18 bits)
  val child = new CorrelatedThreshold(depth, margin)
  result := child.result ^ source
}

class CorrelatedTimeout(oversized: Boolean = false) extends Component {
  setDefinitionName("CorrelatedTimeout")
  val timeout: Param[Int] = Param(1024)
  require(timeout >= 32 && timeout <= (1 << 24))
  val source = in UInt((timeout + 1).log2Up bits)
  val result = out UInt((timeout + 1).log2Up bits)
  val value = if (oversized) timeout * 2 else timeout - 1
  result := source ^ ElabValue.uintLike(value, result, "deadline")
}

class CorrelatedValueBoundsTests extends AnyFunSuite {
  private def generate(body: => Component): Path = {
    val dir = Files.createTempDirectory("correlated-value-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString, headerWithDate=false))(body)
    dir
  }
  private def run(dir: Path, args: String*): Unit = {
    val r = Increment66ToolEvidence.run(dir, args)
    assert(r._1 == 0, args.mkString(" ") + "\n" + r._2)
  }
  test("child-owned relational contracts authorize symbolic unsigned subtraction and reject invalid overrides") {
    val dir = generate(new CorrelatedThreshold(ElabInt.literal(3), ElabInt.literal(2)))
    val rtl = new String(Files.readAllBytes(dir.resolve("CorrelatedThreshold.v")), "UTF-8")
    val repeat = generate(new CorrelatedThreshold(ElabInt.literal(3), ElabInt.literal(2)))
    assert(rtl == new String(Files.readAllBytes(repeat.resolve("CorrelatedThreshold.v")), "UTF-8"))
    assert(rtl.contains("MARGIN") && rtl.contains("DEPTH") && rtl.contains(" - MARGIN"), rtl)
    for (depth <- Seq(2,16); margin <- Seq(1, (1 << depth), 0, (1 << depth)+1, (1 << depth)+2)) {
      val valid = margin >= 1 && margin < (1 << depth)+1
      Files.write(dir.resolve("tb.v"), s"""module tb;
wire[17:0] result;
CorrelatedThreshold #(.DEPTH($depth),.MARGIN($margin)) dut(.result(result));
initial begin #1; if(result !== ${((1 << depth)+1-margin) & 262143}) $$fatal(1,"value mismatch"); $$finish; end
endmodule
""".getBytes("UTF-8"))
      run(dir,"iverilog","-g2001","-s","tb","-o","sim","CorrelatedThreshold.v","tb.v")
      val r=Increment66ToolEvidence.run(dir,Seq("vvp","sim"))
      assert((r._1==0)==valid,r._2)
      if(!valid) assert(r._2.contains("FATAL") && !r._2.contains("value mismatch"),r._2)
    }
    run(dir,"verilator","--lint-only","--language","1364-2001","-Wno-fatal","-DSYNTHESIS","CorrelatedThreshold.v")
    run(dir,"yosys","-Q","-T","-p","read_verilog CorrelatedThreshold.v; hierarchy -check -top CorrelatedThreshold; proc; opt; check -assert; select -assert-none t:$dlatch")
  }
  test("parent bindings preserve child contract and non-default values") {
    val dir=generate(new CorrelatedThresholdParent)
    for((depth,margin)<-Seq((2,1),(2,4),(16,1),(16,65536))) {
      Files.write(dir.resolve("tb.v"),s"""module tb; wire[17:0] result;
CorrelatedThresholdParent #(.DEPTH($depth),.MARGIN($margin)) dut(.source(18'd0),.result(result));
initial begin #1; if(result !== ${(1 << depth)+1-margin}) $$fatal(1,"binding mismatch"); $$finish; end endmodule
""".getBytes("UTF-8"))
      val files=dir.toFile.listFiles.filter(_.getName.endsWith(".v")).map(_.getName).sorted
      run(dir,(Seq("iverilog","-g2001","-s","tb","-o","sim") ++ files):_*)
      run(dir,"vvp","sim")
    }
  }
  test("missing or disjunctive positivity and undersized carriers remain rejected") {
    for((constrained,disjunctive,bits)<-Seq((false,false,18),(true,true,18),(true,false,16))) {
      val e=intercept[Exception] { generate(new CorrelatedThreshold(ElabInt.literal(3),ElabInt.literal(2),constrained,disjunctive,bits)) }
      val code=if(bits==16)"VALUE-WIDTH-INSUFFICIENT" else "VALUE-DOMAIN-UNSUPPORTED"
      assert(e.toString.contains(code),e.toString)
    }
  }
  test("a parent-only constraint cannot authorize the public child") {
    val e=intercept[Exception] { generate(new Component {
      val depth:Param[Int]=Param(3); require(depth>=2 && depth<=16)
      val margin:Param[Int]=Param(2); require(margin>=1 && margin<=65536)
      require(margin < depth.pow2+1)
      val result=out UInt(18 bits)
      val child=new CorrelatedThreshold(depth,margin,constrained=false)
      result:=child.result
    }) }
    assert(e.toString.contains("VALUE-DOMAIN-UNSUPPORTED"),e.toString)
  }
  test("compact timeout value fits its correlated logarithmic carrier across overrides") {
    val dir=generate(new CorrelatedTimeout)
    for(timeout<-Seq(32,33,1024,65536,16777215,16777216)) {
      val bits=BigInt(timeout).bitLength
      Files.write(dir.resolve("tb.v"),s"""module tb; wire[$bits-1:0] result;
CorrelatedTimeout #(.TIMEOUT($timeout)) dut(.source($bits'd0),.result(result));
initial begin #1; if(result !== ${timeout-1}) $$fatal(1,"deadline mismatch"); $$finish; end endmodule
""".getBytes("UTF-8"))
      run(dir,"iverilog","-g2001","-s","tb","-o","sim","CorrelatedTimeout.v","tb.v")
      run(dir,"vvp","sim")
    }
    run(dir,"verilator","--lint-only","--language","1364-2001","-Wno-fatal","-DSYNTHESIS","CorrelatedTimeout.v")
    run(dir,"yosys","-Q","-T","-p","read_verilog CorrelatedTimeout.v; hierarchy -check -top CorrelatedTimeout; proc; opt; check -assert; select -assert-none t:$dlatch")
    val e=intercept[Exception]{generate(new CorrelatedTimeout(oversized=true))}
    assert(e.toString.contains("VALUE-WIDTH-INSUFFICIENT"),e.toString)
  }

}
