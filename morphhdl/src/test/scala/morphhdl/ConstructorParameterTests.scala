package morphhdl

import java.nio.file.{Files,Path}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

final class ConstructorCopy(WIDTH: Param[Int] = 8) extends Component {
  setDefinitionName("ConstructorCopy")
  require(WIDTH >= 1 && WIDTH <= 64)
  val io=new Bundle {val source=in Bits(WIDTH bits);val result=out Bits(WIDTH bits)}
  io.result := ~io.source
}

@_root_.spinal.core.rtlRecursive
final class ConstructorRecursive(COUNT:Param[Int], MASK:Option[Param[Int]]=None) extends Component {
  setDefinitionName("ConstructorRecursive")
  require(COUNT>=1 && COUNT<=5)
  require(MASK>=0 && MASK<=1)
  val io=new Bundle {val terms=in(Vec(Bits(8 bits),COUNT));val result=out Bits(8 bits)}
  if(COUNT==1) io.result:=io.terms(0)
  else {
    val nextCount=(COUNT+1)/2
    val values=Vec(Bits(8 bits),nextCount)
    for(i<-0 until nextCount) {
      if(2*i+1<COUNT)values(i):=io.terms(2*i)^io.terms(2*i+1)
      else values(i):=io.terms(i*2)
    }
    val next=new ConstructorRecursive(COUNT=nextCount,MASK=Param.forwardOptional(MASK))
    next.io.terms:=values
    io.result:=next.io.result
  }
}

final class ConstructorValues(FLAG:Param[Boolean], WORD:Param[UInt], FLAGS:Param[Bits], OFFSET:Param[SInt]) extends Component {
  setDefinitionName("ConstructorValues")
  require(WORD>=0 && WORD<=31)
  require(FLAGS>=0 && FLAGS<=255)
  require(OFFSET>= -8 && OFFSET<=7)
  val source=in Bits(8 bits);val result=out Bits(8 bits)
  val word=out UInt(19 bits);val flags=out Bits(8 bits);val offset=out SInt(9 bits)
  if(FLAG)result:=source else result:= ~source
  word:=U(WORD,19 bits);flags:=B(FLAGS,8 bits);offset:=S(OFFSET,9 bits)
}

final class ConstructorSynchronizer(DATA_BITS:Param[Int], SYNC_STAGES:Param[Int]) extends Component {
  setDefinitionName("ConstructorSynchronizer")
  require(DATA_BITS>=1 && DATA_BITS<=64)
  require(SYNC_STAGES>=2 && SYNC_STAGES<=4)
  val source=in Bits(DATA_BITS bits);val result=out Bits(DATA_BITS bits);val enable=in Bool()
  val stages=Vec.fill(SYNC_STAGES)((Reg(Bits(DATA_BITS bits)) init(0)).addAttribute("ASYNC_REG","TRUE"))
  when(enable) {
    stages(0):=source
    for(i<-0 until (SYNC_STAGES-1))stages(i+1):=stages(i)
  }
  result:=stages(SYNC_STAGES-1)
}

final class ConstructorNamed(@rtlParamName("DATA_BITS") width:Param[Int]) extends Component {
  setDefinitionName("ConstructorNamed")
  require(width>=1 && width<=64)
  val source=in Bits(width bits);val result=out Bits(width bits)
  result:=source
}

final class ConstructorScalar(VALUE:Param[Int]) extends Component {
  require(VALUE>=0 && VALUE<=255)
  val source=in Bits(8 bits);val result=out Bits(8 bits)
  result:=source ^ B(VALUE,8 bits)
}
final class ConstructorMiddle(OTHER:Param[Int]) extends Component {
  require(OTHER>=0 && OTHER<=255)
  val source=in Bits(8 bits);val result=out Bits(8 bits)
  val child=new ConstructorScalar(OTHER)
  child.source:=source;result:=child.result
}

@_root_.spinal.core.rtlRecursive
final class ConstructorNondecreasing(COUNT:Param[Int]) extends Component {
  require(COUNT>=1 && COUNT<=5)
  val io=new Bundle {val source=in Bits(8 bits);val result=out Bits(8 bits)}
  if(COUNT==1)io.result:=io.source
  else {val child=new ConstructorNondecreasing(COUNT);child.io.source:=io.source;io.result:=child.io.result}
}

class ConstructorParameterTests extends AnyFunSuite {
  private def generate(dir:Path)(body: => Component):Unit = {
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
      preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray))(body)
  }
  private def check(dir:Path,name:String,bench:String):Unit={
    Files.write(dir.resolve("tb.v"),bench.getBytes("UTF-8"))
    for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim",name+".v","tb.v"),Seq("vvp","sim"),
      Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal",name+".v"))) {
      val(code,log)=Increment66ToolEvidence.run(dir,command);assert(code==0,log)
    }
  }
  for(reverse<-Seq(false,true))test(s"constructor formals retain named symbolic literal derived and default actuals reverse=$reverse") {
    def emit()={val dir=Files.createTempDirectory("constructor-formals-");generate(dir){new Component {
      setDefinitionName("ConstructorTop")
      val BUS_WIDTH:Param[Int]=Param(8);require(BUS_WIDTH>=1 && BUS_WIDTH<=63)
      val source=in Bits(BUS_WIDTH bits);val result=out Bits(BUS_WIDTH bits)
      val extra=in Bits((BUS_WIDTH+1) bits);val extraResult=out Bits((BUS_WIDTH+1) bits)
      val fixed=in Bits(8 bits);val fixedResult=out Bits(8 bits);val defaultResult=out Bits(8 bits)
      var evaluated=0
      def actual():Int={evaluated+=1;8}
      val instances=if(reverse)Seq(new ConstructorCopy(WIDTH=actual()),new ConstructorCopy(BUS_WIDTH))
        else Seq(new ConstructorCopy(BUS_WIDTH),new ConstructorCopy(WIDTH=actual()))
      assert(evaluated==1)
      val symbolic=instances(if(reverse)1 else 0);val literal=instances(if(reverse)0 else 1)
      val derived=new ConstructorCopy(BUS_WIDTH+1);val defaulted=new ConstructorCopy()
      symbolic.io.source:=source;result:=symbolic.io.result
      literal.io.source:=fixed;fixedResult:=literal.io.result
      derived.io.source:=extra;extraResult:=derived.io.result
      defaulted.io.source:=fixed;defaultResult:=defaulted.io.result
    }};dir}
    val dir=emit();val repeated=emit()
    val rtl=new String(Files.readAllBytes(dir.resolve("ConstructorTop.v")),"UTF-8")
    assert(rtl==new String(Files.readAllBytes(repeated.resolve("ConstructorTop.v")),"UTF-8"))
    assert("(?m)^module ConstructorCopy(?: \\#|\\s*\\()".r.findAllIn(rtl).size==1,rtl)
    assert(rtl.contains(".WIDTH") && rtl.contains("BUS_WIDTH"),rtl)
    val cases=Seq(1,5,8,63).map(w=>s"Check #(.W($w)) c$w(data);").mkString("\n")
    check(dir,"ConstructorTop",s"""module Check #(parameter W=8)(input [63:0] data);
wire [W-1:0] result;wire [W:0] extraResult;wire [7:0] fixedResult,defaultResult;
ConstructorTop #(.BUS_WIDTH(W)) dut(.source(data[W-1:0]),.result(result),.extra(data[W:0]),.extraResult(extraResult),.fixed(data[7:0]),.fixedResult(fixedResult),.defaultResult(defaultResult));
always @(data) begin #1;if(result!==~data[W-1:0] || extraResult!==~data[W:0] || fixedResult!==~data[7:0] || defaultResult!==~data[7:0])$$fatal;end
endmodule
module tb;reg [63:0] data;integer n;
$cases
initial begin data=0;#2;for(n=0;n<40;n=n+1)begin data={$$random,$$random};#2;end data='bx;#2;data='bz;#2;$$finish;end
endmodule
""")
  }
  for(present<-Seq(false,true); default<-Seq(1,5))test(s"constructor recursive formals retain optional presence present=$present default=$default") {
    val dir=Files.createTempDirectory("constructor-recursive-")
    generate(dir){new ConstructorRecursive(default,if(present)Some(Param(0))else None)}
    val rtl=new String(Files.readAllBytes(dir.resolve("ConstructorRecursive.v")),"UTF-8")
    assert(rtl.contains("parameter integer MASK")==present,rtl)
    val cases=Seq(1,3,5).map {n=>
      val mask=if(present)",.MASK(1)"else ""
      val expected=(0 until n).map(i=>s"data[${i*8} +: 8]").mkString(" ^ ")
      s"wire [7:0] result$n;ConstructorRecursive #(.COUNT($n)$mask) d$n(.terms(data[${8*n-1}:0]),.result(result$n));always @(data)begin #1;if(result$n !== ($expected)) $$fatal;end"
    }.mkString("\n")
    check(dir,"ConstructorRecursive",s"""module tb;reg [39:0] data;integer n;
$cases
initial begin data=0;#2;for(n=0;n<40;n=n+1)begin data={$$random,$$random};#2;end data='bx;#2;data='bz;#2;$$finish;end endmodule
""")
  }
  test("constructor Boolean and packed formals preserve representation and overrides") {
    val dir=Files.createTempDirectory("constructor-packed-")
    generate(dir){new ConstructorValues(true,U(3,19 bits),B(5,8 bits),S(-3,8 bits))}
    val rtl=new String(Files.readAllBytes(dir.resolve("ConstructorValues.v")),"UTF-8")
    assert(rtl.contains("parameter [18:0] WORD"),rtl)
    assert(rtl.contains("parameter signed [7:0] OFFSET"),rtl)
    val cases=(for(flag<-Seq(0,1);word<-Seq(0,7,31);offset<-Seq(-8,-1,0,7))yield
      s"Check #(.F($flag),.W($word),.O($offset)) c_${flag}_${word}_${offset+8}(source);").mkString("\n")
    check(dir,"ConstructorValues",s"""module Check #(parameter F=0,W=0,O=0)(input [7:0] source);
wire [7:0] result,flags;wire [18:0] word;wire signed [8:0] offset;
ConstructorValues #(.FLAG(F),.WORD(W),.FLAGS(201),.OFFSET(O)) dut(.source(source),.result(result),.word(word),.flags(flags),.offset(offset));
always @(source)begin #1;if(result!==(F?source:~source)||word!==W||flags!==8'd201||offset!==O)$$fatal;end
endmodule
module tb;reg [7:0] source;integer n;
$cases
initial begin source=0;#2;for(n=0;n<30;n=n+1)begin source=$$random;#2;end source='bx;#2;source='bz;#2;$$finish;end endmodule
""")
  }
  test("constructor register Vec preserves reset stalls metadata and independent clocks") {
    val dir=Files.createTempDirectory("constructor-sync-")
    generate(dir){new ConstructorSynchronizer(8,3)}
    val rtl=new String(Files.readAllBytes(dir.resolve("ConstructorSynchronizer.v")),"UTF-8")
    assert(rtl.contains("ASYNC_REG"),rtl)
    val cases=(for(w<-Seq(1,5,64);d<-Seq(2,3,4))yield s"Check #(.W($w),.D($d),.PERIOD(${d+1})) c_${w}_$d();").mkString("\n")
    check(dir,"ConstructorSynchronizer",s"""module Check #(parameter W=8,D=3,PERIOD=4)();
reg clk=0,reset=0,enable=1,runClock=1;reg [W-1:0] source=0;wire [W-1:0] result;reg [W-1:0] expected[0:D-1];integer i,n;
always #PERIOD if(runClock)clk=~clk;
ConstructorSynchronizer #(.DATA_BITS(W),.SYNC_STAGES(D)) dut(.clk(clk),.reset(reset),.enable(enable),.source(source),.result(result));
always @(posedge clk or posedge reset)if(reset)for(i=0;i<D;i=i+1)expected[i]<=0;else if(enable)begin expected[0]<=source;for(i=1;i<D;i=i+1)expected[i]<=expected[i-1];end
always @(negedge clk)if(result!==expected[D-1])$$fatal;
initial begin #1;reset=1;#13;reset=0;
for(n=0;n<40;n=n+1)begin @(negedge clk);#1;source={$$random,$$random};enable=n%3!=0;end
@(negedge clk);#1;runClock=0;reset=1;#13;reset=0;#7;runClock=1;enable=1;
repeat(6)@(negedge clk);#1;source='bx;repeat(6)@(negedge clk);#1;source='bz;end
endmodule
module tb;
$cases
initial begin #1500;$$finish;end endmodule
""")
  }
  test("constructor actual domains and packed representations reject incompatible values") {
    for(width<-Seq(0,-1,65)) {
      val error=intercept[Exception]{generate(Files.createTempDirectory("constructor-bad-")){new ConstructorCopy(width)}}
      assert(error.toString.contains("DOMAIN") || error.toString.contains("domain"),error.toString)
    }
    val error=intercept[Exception]{generate(Files.createTempDirectory("constructor-broad-")){new Component {
      val P:Param[Int]=Param(8);require(P>=1 && P<=128)
      val child=new ConstructorCopy(P)
      child.io.source:=0
    }}}
    assert(error.toString.contains("DOMAIN") || error.toString.contains("domain"),error.toString)
    val representation=intercept[Exception]{generate(Files.createTempDirectory("constructor-representation-")){
      new ConstructorValues(true,B(3,19 bits),B(5,8 bits),S(-3,8 bits))
    }}
    assert(representation.toString.contains("REPRESENTATION-MISMATCH"),representation.toString)
  }

  test("explicit constructor HDL names preserve the Scala named-argument API") {
    val dir=Files.createTempDirectory("constructor-name-")
    generate(dir){new ConstructorNamed(width=8)}
    val rtl=new String(Files.readAllBytes(dir.resolve("ConstructorNamed.v")),"UTF-8")
    assert(rtl.contains("parameter integer DATA_BITS"),rtl)
    assert(!rtl.contains("parameter integer width"),rtl)
    check(dir,"ConstructorNamed","""module tb;reg [4:0] source;wire [4:0] result;
ConstructorNamed #(.DATA_BITS(5)) dut(.source(source),.result(result));
initial begin source=5'd21;#1;if(result!==source)$fatal;source='bx;#1;if(result!==source)$fatal;$finish;end endmodule
""")
  }

  test("constructor scalar forwarding retains every intermediate binding without symbolic ports") {
    val dir=Files.createTempDirectory("constructor-forward-")
    generate(dir){new Component {
      setDefinitionName("ConstructorForward")
      val PUBLIC:Param[Int]=Param(7);require(PUBLIC>=0 && PUBLIC<=255)
      val source=in Bits(8 bits);val result=out Bits(8 bits)
      val child=new ConstructorMiddle(PUBLIC)
      child.source:=source;result:=child.result
    }}
    val cases=Seq(0,7,201,255).map(v=>s"Check #(.V($v)) c$v(source);").mkString("\n")
    check(dir,"ConstructorForward",s"""module Check #(parameter V=7)(input [7:0] source);
wire [7:0] result;ConstructorForward #(.PUBLIC(V)) dut(.source(source),.result(result));
always @(source)begin #1;if(result!==(source^V[7:0]))$$fatal;end endmodule
module tb;reg [7:0] source;integer n;
$cases
initial begin for(n=0;n<256;n=n+1)begin source=n;#2;end source='bx;#2;source='bz;#2;$$finish;end endmodule
""")
  }
  test("duplicate constructor formal names remain rejected") {
    class Duplicate(@rtlParamName("WIDTH") A:Param[Int],@rtlParamName("WIDTH") B:Param[Int]) extends Component {
      require(A>=1 && A<=8);require(B>=1 && B<=8)
      val source=in Bits(A bits);val result=out Bits(B bits);result:=source
    }
    val error=intercept[Exception]{generate(Files.createTempDirectory("constructor-duplicate-")){new Duplicate(8,8)}}
    assert(error.toString.contains("FORMAL-NAME-DUPLICATE"),error.toString)
  }
  test("root packed constructor capture rejects unrelated hardware and changed literal lineage") {
    val extra=intercept[Exception]{generate(Files.createTempDirectory("constructor-extra-")){
      new ConstructorValues(true,ConstructorParam.Packed.literal({val extra=U(0,8 bits);U(3,19 bits)}),B(5,8 bits),S(-3,8 bits))
    }}
    assert(extra.toString.contains("ACTUAL-SCOPE-UNSUPPORTED"),extra.toString)
    var saved:UInt=null
    class Mutating(WORD:Param[UInt]) extends Component {
      require(WORD>=0 && WORD<=31)
      saved.removeAssignments()
      val result=out UInt(19 bits);result:=U(WORD,19 bits)
    }
    val changed=intercept[Exception]{generate(Files.createTempDirectory("constructor-changed-")){
      new Mutating(ConstructorParam.Packed.literal({saved=U(3,19 bits);saved}))
    }}
    assert(changed.toString.contains("LITERAL-LINEAGE-CHANGED"),changed.toString)
  }

  test("constructor recursive actuals cannot avoid strict decrease") {
    val error=intercept[Exception]{generate(Files.createTempDirectory("constructor-nondecreasing-")){
      new ConstructorNondecreasing(5)
    }}
    assert(error.toString.contains("RECURS") && (error.toString.toLowerCase.contains("decreas") || error.toString.contains("TERMINATION")),error.toString)
  }

  for(width<-Seq(1,64))test(s"literal constructor width $width retains the complete override domain") {
    val dir=Files.createTempDirectory("constructor-extreme-")
    generate(dir){new ConstructorCopy(width)}
    check(dir,"ConstructorCopy","""module tb;reg [4:0] source;wire [4:0] result;
ConstructorCopy #(.WIDTH(5)) dut(.io_source(source),.io_result(result));
initial begin source=5'd21;#1;if(result!==~source)$fatal;source='bz;#1;if(result!==~source)$fatal;$finish;end endmodule
""")
  }

}
