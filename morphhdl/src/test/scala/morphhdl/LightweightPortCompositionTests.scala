package morphhdl

import java.nio.file.{Files,Path}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class CompositionCounter(COUNT_BITS:Param[Int]) extends Component {
  require(COUNT_BITS>=1 && COUNT_BITS<=64)
  val source=in UInt(COUNT_BITS bits)
  val result=out UInt(COUNT_BITS bits)
  result:=source
}
class CompositionBits(DATA_BITS:Param[Int]) extends Component {
  require(DATA_BITS>=1 && DATA_BITS<=64)
  val source=in Bits(DATA_BITS bits)
  val result=out Bits(DATA_BITS bits)
  result:=source
}
class CompositionConcrete extends Component {
  val source=in Bits(1 bits);val data=in Bits(8 bits)
  val fault=out Bool();val result=out Bits(8 bits)
  fault:=source.asBool;result:=data
}
class LightweightPortCompositionTests extends AnyFunSuite {
  private def generate(dir:Path)(body: => Component):Unit =
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
      preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray))(body)
  private def check(dir:Path,name:String,bench:String):Unit={
    Files.write(dir.resolve("tb.v"),bench.getBytes("UTF-8"))
    for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim",name+".v","tb.v"),Seq("vvp","sim"),
      Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal",name+".v"))) {
      val(code,log)=Increment66ToolEvidence.run(dir,command);assert(code==0,log)
    }
  }
  test("sibling output arithmetic and cast chains use exact parent actual geometry") {
    val dir=Files.createTempDirectory("sibling-geometry-")
    generate(dir){new Component {
      setDefinitionName("SiblingGeometry")
      val WIDTH:Param[Int]=Param(7);require(WIDTH>=1 && WIDTH<=63)
      val a=in UInt((WIDTH+1) bits);val b=in UInt((WIDTH+1) bits)
      val difference=out UInt((WIDTH+1) bits);val forwarded=out Bits((WIDTH+1) bits)
      val first=new CompositionCounter(WIDTH+1);val second=new CompositionCounter(WIDTH+1)
      first.source:=a;second.source:=b
      difference:=first.result-second.result
      val bridge=new CompositionBits(WIDTH+1);val end=new CompositionCounter(WIDTH+1)
      bridge.source:=first.result.asBits;end.source:=bridge.result.asUInt
      val cloned=cloneOf(end.result)
      cloned:=end.result
      forwarded:=cloned.asBits
    }}
    val cases=Seq(1,4,7,63).map(w=>s"Check #(.W($w)) c$w(a,b);").mkString("\n")
    check(dir,"SiblingGeometry",s"""module Check #(parameter W=7)(input [63:0] a,b);
wire [W:0] difference,forwarded;reg [W:0] expected;
SiblingGeometry #(.WIDTH(W)) dut(.a(a[W:0]),.b(b[W:0]),.difference(difference),.forwarded(forwarded));
always @(a or b)begin expected=a[W:0]-b[W:0];#1;if(difference!==expected||forwarded!==a[W:0])$$fatal;end
endmodule
module tb;reg [63:0] a,b;integer n;
$cases
initial begin a=0;b=1;#2;for(n=0;n<100;n=n+1)begin a={$$random,$$random};b={$$random,$$random};#2;end a='bx;#2;b='bz;#2;$$finish;end endmodule
""")
  }
  test("concrete child expressions retain native connection nets and four state behavior") {
    val dir=Files.createTempDirectory("concrete-ports-")
    generate(dir){new Component {
      setDefinitionName("ConcretePorts")
      val WIDTH:Param[Int]=Param(8);require(WIDTH>=1 && WIDTH<=64)
      val symbolic=in Bits(WIDTH bits);val copy=out Bits(WIDTH bits);copy:=symbolic
      val a=in Bool();val b=in Bool();val data=in Bits(8 bits);val selector=in UInt(2 bits)
      val fault=out Bool();val nibble=out Bits(4 bits);val complement=out Bits(8 bits);val assembled=out Bits(12 bits)
      val child=new CompositionConcrete
      child.source:=(a && !b && data(0)).asBits;child.data:=data
      val sliceChild=new CompositionBits(4)
      val selected=selector.muxList(child.result,Seq(0 -> data,1 -> ~data)).dontSimplifyIt()
      sliceChild.source:=selected(3 downto 0)
      val sibling=new CompositionConcrete
      sibling.source:=child.fault.asBits;sibling.data:=child.result(7 downto 4) ## child.result(3 downto 0)
      assembled(7 downto 0):=child.result;assembled(11 downto 8):=data(3 downto 0)
      fault:=sibling.fault || b
      nibble:=sliceChild.result;complement:= ~sibling.result
    }}
    check(dir,"ConcretePorts","""module tb;
reg a,b;reg [7:0] data;reg [1:0] selector;reg [3:0] expectedNibble;wire fault;wire [3:0] nibble;wire [7:0] complement,copy;wire [11:0] assembled;integer n;
ConcretePorts dut(.a(a),.b(b),.data(data),.selector(selector),.fault(fault),.nibble(nibble),.complement(complement),.symbolic(data),.copy(copy),.assembled(assembled));
task verify;begin case(selector)2'd1:expectedNibble=~data[3:0];default:expectedNibble=data[3:0];endcase #1;if(fault!==((a&&!b&&data[0])||b)||nibble!==expectedNibble||complement!==~data||copy!==data||assembled!=={data[3:0],data})$fatal;end endtask
initial begin for(n=0;n<100;n=n+1)begin a=$random;b=$random;data=$random;selector=$random;verify;end a=1'bx;b=0;data='bz;selector='bx;verify;a=1'bz;b=1'bx;data='bx;selector='bz;verify;$finish;end endmodule
""")
  }
  for(affine<-Seq(false,true))test(s"ordinary scoped Bits selection includes guarded affine reads affine=$affine") {
    val dir=Files.createTempDirectory("scoped-bits-")
    generate(dir){new Component {
      setDefinitionName("ScopedBits")
      val COUNT:Param[Int]=Param(5);require(COUNT>=1 && COUNT<=9)
      val source=in Bits(COUNT bits);val enable=in Bool();val result=out Bits(COUNT bits)
      val lanes=Vec(Bool(),COUNT)
      for(i<-0 until COUNT) {
        val lane=lanes(i)
        if(affine) {
          if(2*i+1<COUNT) lane:=source(2*i+1)
          else lane:=False
        } else {
          lane:=False
          when(source(i) && enable){lane:=True}
        }
      }
      result:=lanes.asBits
    }}
    val instances=(1 to 9).map(n=>s"Check #(.N($n)) c$n(source,enable);").mkString("\n")
    val update=if(affine)"if(2*i+1<N)expected[i]=source[2*i+1];"else "if(source[i]&&enable)expected[i]=1;"
    check(dir,"ScopedBits",s"""module Check #(parameter N=5)(input [8:0] source,input enable);
wire [N-1:0] result;reg [N-1:0] expected;integer i;
ScopedBits #(.COUNT(N)) dut(.source(source[N-1:0]),.enable(enable),.result(result));
always @(source or enable)begin expected=0;for(i=0;i<N;i=i+1)$update#1;if(result!==expected)$$fatal;end
endmodule
module tb;reg [8:0] source;reg enable;integer n;
$instances
initial begin enable=1;for(n=0;n<512;n=n+1)begin source=n;#2;end source='bx;#2;source='bz;#2;enable=0;#2;enable=1'bx;#2;$$finish;end endmodule
""")
  }
  test("ordinary Bits indexing rejects escaped and out of bounds affine indices") {
    for(mode<-Seq("escaped","bounds")) {
      val error=intercept[Exception]{generate(Files.createTempDirectory("bits-negative-")){new Component {
        val COUNT:Param[Int]=Param(5);require(COUNT>=1 && COUNT<=9)
        val source=in Bits(COUNT bits);val result=out Bool()
        var escaped:ElabScopedIndex=null
        ElabFiniteRange.foreach(COUNT,"probe") { i =>
          if(mode=="escaped")escaped=i
          else {val invalid=source(2*i+1);invalid.dontSimplifyIt()}
        }
        if(mode=="escaped")result:=source(escaped) else result:=False
      }}}
      assert(error.toString.contains("SPINAL-"),error.toString)
    }
  }
  test("unrelated actuals cannot validate each other by equal defaults") {
    val error=intercept[Exception]{generate(Files.createTempDirectory("sibling-unrelated-")){new Component {
      val LEFT:Param[Int]=Param(8);require(LEFT>=1 && LEFT<=64)
      val RIGHT:Param[Int]=Param(8);require(RIGHT>=1 && RIGHT<=64)
      val a=in UInt(LEFT bits);val b=in UInt(RIGHT bits);val result=out UInt(LEFT bits)
      val first=new CompositionCounter(LEFT);val second=new CompositionCounter(RIGHT)
      first.source:=b;second.source:=a;result:=first.result-second.result
    }}}
    assert(error.toString.contains("INDEPENDENT-ROOTS")||error.toString.contains("FORMAL-ACTUAL-CONNECTION-CONFLICT"),error.toString)
  }

  for(mode<-Seq("direction","drivers"))test(s"direct child connections retain native $mode rejection") {
    val error=intercept[Exception]{generate(Files.createTempDirectory("port-negative-")){new Component {
      val WIDTH:Param[Int]=Param(8);require(WIDTH>=1 && WIDTH<=64)
      val source=in Bits(WIDTH bits);val result=out Bits(WIDTH bits);result:=source
      val a=in Bits(8 bits);val b=in Bits(8 bits);val output=out Bits(8 bits)
      val child=new CompositionConcrete
      child.source:=B(0,1 bits);child.data:=a;output:=child.result
      if(mode=="direction")child.result:=b else child.data:=b
    }}}
    assert(error.toString.toUpperCase.contains("HIERARCHY") || error.toString.toUpperCase.contains("OVERLAP"),error.toString)
  }

  test("concrete child input bit and slice overrides retain full defaults and priority") {
    val dir=Files.createTempDirectory("partial-child-input-")
    generate(dir){new Component {
      setDefinitionName("PartialChildInput")
      val WIDTH:Param[Int]=Param(8);require(WIDTH>=1 && WIDTH<=64)
      val source=in Bits(WIDTH bits);val copy=out Bits(WIDTH bits);copy:=source
      val a=in Bits(8 bits);val b=in Bits(4 bits);val enable=in Bool()
      val result=out Bits(8 bits);val flag=out Bool()
      val child=new CompositionConcrete
      child.data:=a;child.data(3 downto 0):=b
      child.data(7 downto 4):=a(7 downto 4) | (enable.asBits.resize(4) |<< 1)
      child.source(0):=enable
      result:=child.result;flag:=child.fault
    }}
    check(dir,"PartialChildInput","""module tb;reg [7:0] a;reg [3:0] b;reg enable;wire [7:0] result,copy;wire flag;integer i;
PartialChildInput dut(.source(8'd0),.copy(copy),.a(a),.b(b),.enable(enable),.result(result),.flag(flag));
task verify;begin #1;if(result!=={(a[7:4]|{2'b0,enable,1'b0}),b}||flag!==enable)$fatal;end endtask
initial begin for(i=0;i<100;i=i+1)begin a=$random;b=$random;enable=$random;verify;end a='bx;b='bz;enable=1'bx;verify;a='bz;b='bx;enable=1'bz;verify;$finish;end endmodule
""")
  }
  test("partial child input without a complete default remains rejected") {
    val error=intercept[Exception]{generate(Files.createTempDirectory("partial-child-negative-")){new Component {
      val WIDTH:Param[Int]=Param(8);require(WIDTH>=1 && WIDTH<=64)
      val source=in Bits(WIDTH bits);val copy=out Bits(WIDTH bits);copy:=source
      val a=in Bits(4 bits);val result=out Bits(8 bits)
      val child=new CompositionConcrete
      child.source:=B(0,1 bits);child.data(3 downto 0):=a;result:=child.result
    }}}
    assert(error.toString.contains("LATCH") || error.toString.contains("NO DRIVER") ||
      error.toString.contains("PORT-CONNECTION-UNSUPPORTED"),error.toString)
  }

  test("partial child input competing full writes remain rejected") {
    val error=intercept[Exception]{generate(Files.createTempDirectory("partial-child-overlap-")){new Component {
      val WIDTH:Param[Int]=Param(8);require(WIDTH>=1 && WIDTH<=64)
      val source=in Bits(WIDTH bits);val copy=out Bits(WIDTH bits);copy:=source
      val a=in Bits(4 bits);val result=out Bits(8 bits)
      val child=new CompositionConcrete
      child.source:=B(0,1 bits);child.data:=B(0,8 bits)
      child.data(3 downto 0):=a;child.data:=a ## ~a;result:=child.result
    }}}
    assert(error.toString.toUpperCase.contains("OVERLAP"),error.toString)
  }

  test("literal one bit child outputs retain packed carriers for direct bit reads") {
    val dir=Files.createTempDirectory("one-bit-child-")
    generate(dir){new Component {
      setDefinitionName("OneBitChild")
      val WIDTH:Param[Int]=Param(8);require(WIDTH>=1 && WIDTH<=64)
      val source=in Bits(WIDTH bits);val copy=out Bits(WIDTH bits);copy:=source
      val stimulus=in Bool();val result=out Bool()
      val child=new CompositionBits(1)
      child.source:=stimulus.asBits
      result:=child.result(0)
    }}
    check(dir,"OneBitChild","""module tb;reg input_bit;wire result;wire [7:0] copy;
OneBitChild dut(.source(8'd0),.copy(copy),.stimulus(input_bit),.result(result));
initial begin input_bit=0;#1;if(result!==input_bit)$fatal;input_bit=1;#1;if(result!==input_bit)$fatal;input_bit=1'bx;#1;if(result!==input_bit)$fatal;input_bit=1'bz;#1;if(result!==input_bit)$fatal;$finish;end endmodule
""")
  }

  test("direct child slices reject source domains smaller than the fixed slice") {
    val error=intercept[Exception]{generate(Files.createTempDirectory("slice-domain-negative-")){new Component {
      val WIDTH:Param[Int]=Param(8);require(WIDTH>=1 && WIDTH<=64)
      val source=in Bits(WIDTH bits);val result=out Bits(4 bits)
      val child=new CompositionBits(4)
      child.source:=source(3 downto 0);result:=child.result
    }}}
    assert(error.toString.contains("SLICE-DOMAIN") || error.toString.contains("PORT-CONNECTION-UNSUPPORTED"),error.toString)
  }

  test("parent clones of mixed literal child widths cannot import child formal declarations") {
    val dir=Files.createTempDirectory("literal-clone-")
    generate(dir){new Component {
      setDefinitionName("LiteralClone")
      val WIDTH:Param[Int]=Param(8);require(WIDTH>=1 && WIDTH<=64)
      val source=in Bits(WIDTH bits);val result=out Bits(WIDTH bits);result:=source
      val a=in Bits(10 bits);val b=in Bits(20 bits)
      val x=out Bits(10 bits);val y=out Bits(20 bits)
      val left=new CompositionBits(10);val right=new CompositionBits(20)
      left.source:=a;right.source:=b
      val leftClone=cloneOf(left.result);val rightClone=cloneOf(right.result)
      leftClone:=left.result;rightClone:=right.result
      x:=leftClone;y:=rightClone
    }}
    check(dir,"LiteralClone","""module tb;reg [19:0] data;wire [7:0] result;wire [9:0] x;wire [19:0] y;integer i;
LiteralClone dut(.source(data[7:0]),.result(result),.a(data[9:0]),.b(data),.x(x),.y(y));
initial begin for(i=0;i<100;i=i+1)begin data=$random;#1;if(result!==data[7:0]||x!==data[9:0]||y!==data)$fatal;end data='bx;#1;if(x!==data[9:0]||y!==data)$fatal;$finish;end endmodule
""")
  }

  test("scoped affine Bits syntax preserves concrete unrolled generation when loop retention is disabled") {
    val dir=Files.createTempDirectory("concrete-bit-fallback-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)){new Component {
      setDefinitionName("ConcreteBitFallback")
      val WIDTH:Param[Int]=Param(8);require(WIDTH>=1 && WIDTH<=16)
      val passthrough=in Bits(WIDTH bits);val copy=out Bits(WIDTH bits);copy:=passthrough
      val source=in Bits(3 bits);val result=out Bits(2 bits)
      val lanes=Vec(Bool(),2)
      ElabFiniteRange.foreach(ElabInt.literal(2),"bits") { i=>i(lanes):=source(i+1) }
      result:=lanes.asBits
    }}
    check(dir,"ConcreteBitFallback","""module tb;reg [2:0] source;wire [1:0] result;wire [7:0] copy;integer i;
ConcreteBitFallback dut(.source(source),.result(result),.passthrough(8'd0),.copy(copy));
initial begin for(i=0;i<8;i=i+1)begin source=i;#1;if(result!==source[2:1])$fatal;end $finish;end endmodule
""")
  }

}
