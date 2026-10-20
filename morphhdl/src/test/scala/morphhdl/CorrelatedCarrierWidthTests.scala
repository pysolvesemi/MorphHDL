package morphhdl

import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class CorrelatedCarrierWidthTests extends AnyFunSuite {
  class Threshold(empty: Boolean = false, narrow: Boolean = false, constrained: Boolean = true,
      disjunction: Boolean = false, finite: Boolean = false) extends Component {
    setDefinitionName("Threshold")
    val DEPTH: Param[Int] = Param(3)
    @dontName val MARGIN: ElabInt = morphhdl.frontend.formalParam(ElabInt.literal(2), "MARGIN",
      if(finite && !empty) 1 else 0, if(finite && empty) 65535 else 65536)
    require(DEPTH >= 2 && DEPTH <= 16)
    val capacity = DEPTH.pow2 + 1
    if (constrained) {
      if (disjunction) require(MARGIN < capacity || MARGIN >= capacity)
      else if (empty) require(MARGIN < capacity)
      else require(MARGIN >= 1 && MARGIN < capacity)
    }
    val fill = in UInt((DEPTH + (if(narrow) 0 else 2)) bits)
    val selected = out Bool()
    val threshold = if(empty) MARGIN else capacity - MARGIN
    selected := fill >= ElabValue.uintLike(threshold, fill, "threshold")
  }
  class Deadline(overflow: Boolean = false) extends Component {
    setDefinitionName("Deadline")
    val TIMEOUT: Param[Int] = Param(1024)
    require(TIMEOUT >= 32 && TIMEOUT <= 16777216)
    @localparam val DEADLINE = U(if(overflow) TIMEOUT * 2 else TIMEOUT - 1, (TIMEOUT + 1).log2Up bits)
    val age = in UInt((TIMEOUT + 1).log2Up bits)
    val expired = out Bool()
    expired := age >= DEADLINE
  }
  class Modular extends Component {
    val WIDTH: Param[Int] = Param(4)
    require(WIDTH >= 4 && WIDTH <= 7)
    @localparam val WRAPPED = U(WIDTH, 3 bits) + U(7, 3 bits)
    @localparam val NEGATIVE = S(-3, 5 bits)
    val mask = in UInt(3 bits)
    val result = out UInt(3 bits)
    val signedResult = out SInt(5 bits)
    result := WRAPPED ^ mask
    signedResult := NEGATIVE
  }
  def generate(body: => Component, name: String): (Path,String) = {
    val dir = Files.createTempDirectory("correlated-carrier-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false))(body)
    dir -> new String(Files.readAllBytes(dir.resolve(name+".v")),"UTF-8")
  }
  def run(dir: Path, command: String*): Unit = {
    val r=Increment66ToolEvidence.run(dir,command)
    assert(r._1 == 0,command.mkString(" ")+"\n"+r._2)
  }
  def tools(dir: Path, name: String): Unit = {
    run(dir,"verilator","--lint-only","--language","1364-2001","-Wno-fatal","-DSYNTHESIS",name+".v")
    run(dir,"yosys","-Q","-T","-p",s"read_verilog -DSYNTHESIS $name.v; hierarchy -check -top $name; proc; opt; check -assert; select -assert-none t:$$dlatch")
  }
  for(empty <- Seq(false,true)) test(s"correlated ${if(empty) "empty" else "full"} thresholds retain native count width") {
    val (dir,rtl)=generate(new Threshold(empty),"Threshold")
    assert(rtl==generate(new Threshold(empty),"Threshold")._2)
    assert(!rtl.contains("[17:0]"),rtl)
    for(depth <- Seq(2,3,8,16); margin <- Seq(if(empty) 0 else 1, 2, 1 << depth)) {
      val bits=depth+2
      val threshold=if(empty) margin else (1 << depth)+1-margin
      Files.write(dir.resolve("tb.v"),s"""module tb;
reg [$bits-1:0] fill; wire selected;
Threshold #(.DEPTH($depth),.MARGIN($margin)) dut(fill,selected);
initial begin fill=0; #1; if(selected !== ${if(threshold==0) 1 else 0}) $$fatal;
fill=$threshold; #1; if(selected !== 1) $$fatal;
fill=${math.max(0,threshold-1)}; #1; if(selected !== ${if(threshold==0) 1 else 0}) $$fatal;
fill={${bits}{1'bx}}; #1; if(selected !== 1'bx) $$fatal;
$$finish; end endmodule
""".getBytes("UTF-8"))
      run(dir,"iverilog","-g2001","-s","tb","-o","sim","Threshold.v","tb.v")
      run(dir,"vvp","sim")
    }
    tools(dir,"Threshold")
  }
  for(empty <- Seq(false,true)) test(s"large exact threshold tables remain bounded, empty=$empty") {
    val (dir,rtl)=generate(new Threshold(empty=empty,finite=true),"Threshold")
    assert(!rtl.contains("[17:0]"),rtl)
    val margin=if(empty) 65535 else 65536
    val expected=if(empty) 65535 else 1
    Files.write(dir.resolve("tb.v"),s"""module tb; wire selected;
Threshold #(.DEPTH(16),.MARGIN($margin)) dut(18'd$expected,selected);
initial begin #1; if(selected !== 1) $$fatal; $$finish; end endmodule
""".getBytes("UTF-8"))
    run(dir,"iverilog","-g2001","-s","tb","-o","sim","Threshold.v","tb.v");run(dir,"vvp","sim")
  }
  test("correlation does not authorize overflowing or unproven threshold carriers") {
    for((empty,narrow,constrained,disjunction) <- Seq((false,true,true,false),(true,true,true,false),
        (false,false,false,false),(false,false,true,true),(true,false,false,false))) {
      val e=intercept[Exception]{generate(new Threshold(empty,narrow,constrained,disjunction),"Threshold")}
      assert(e.toString.contains("VALUE-WIDTH-INSUFFICIENT") || e.toString.contains("VALUE-DOMAIN-UNSUPPORTED"),e.toString)
    }
  }
  test("whole typed deadline initializer uses assignment sizing without value helpers") {
    val (dir,rtl)=generate(new Deadline,"Deadline")
    assert(!rtl.contains("DEADLINE_VALUE"),rtl)
    assert(rtl.linesIterator.exists(l=>l.contains("localparam") && l.contains("DEADLINE =") && l.contains("TIMEOUT - 1")),rtl)
    assert(rtl.contains("DEADLINE <= age") || rtl.contains("age >= DEADLINE"),rtl)
    assert(rtl==generate(new Deadline,"Deadline")._2)
    for(timeout <- Seq(32,33,63,64,65,1023,1024,1025,16777215,16777216)) {
      val bits=BigInt(timeout).bitLength
      Files.write(dir.resolve("tb.v"),s"""module tb; reg [$bits-1:0] age; wire expired;
Deadline #(.TIMEOUT($timeout)) dut(age,expired);
initial begin age=${timeout-2}; #1; if(expired !== 0) $$fatal;
age=${timeout-1}; #1; if(expired !== 1) $$fatal;
age=$timeout; #1; if(expired !== 1) $$fatal; $$finish; end endmodule
""".getBytes("UTF-8"))
      run(dir,"iverilog","-g2001","-s","tb","-o","sim","Deadline.v","tb.v");run(dir,"vvp","sim")
    }
    tools(dir,"Deadline")
  }
  test("direct initializer still rejects genuine overflow") {
    val e=intercept[Exception]{generate(new Deadline(true),"Deadline")}
    assert(e.toString.contains("VALUE-WIDTH-INSUFFICIENT"),e.toString)
  }
  test("nested modular operations and signed constants preserve their representations") {
    val (dir,rtl)=generate(new Modular,"Modular")
    assert(rtl.contains("WRAPPED_EXPR"),rtl)
    for(width <- 4 to 7) {
      Files.write(dir.resolve("tb.v"),s"""module tb; wire[2:0] result; wire signed[4:0] negative;
Modular #(.WIDTH($width)) dut(3'd0,result,negative);
initial begin #1; if(result !== ${(width+7)&7} || negative !== -5'sd3) $$fatal; $$finish; end endmodule
""".getBytes("UTF-8"))
      run(dir,"iverilog","-g2001","-s","tb","-o","sim","Modular.v","tb.v");run(dir,"vvp","sim")
    }
    tools(dir,"Modular")
  }
}
