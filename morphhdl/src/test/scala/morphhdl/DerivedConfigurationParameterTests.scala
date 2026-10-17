package morphhdl

import java.nio.file.{Files,Path}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

object Lwr27Layout {
  def width(data:ElabInt,lanes:ElabInt,generation:ElabInt):ElabInt = {
    val header=data+7
    header+generation+lanes+16+1
  }
}
case class Lwr27Video(depth:Param[Int],data:Param[Int],lanes:Param[Int],generation:Param[Int]) {
  require(depth>=1 && depth<=4)
  require(data>=1 && data<=32)
  require(lanes>=1 && lanes<=4)
  require(generation>=1 && generation<=8)
  val capacity=depth.pow2+1
  val countBits=depth+2
  val recordBits=Lwr27Layout.width(data,lanes,generation)
  val twice=recordBits*2
}
case class Lwr27Config(video:Lwr27Video) {
  val total=video.twice+video.countBits
}
class Lwr27Child(c:Lwr27Config) extends Component {
  setDefinitionName("DerivedConfiguration")
  val source=in Bits(c.video.recordBits bits)
  val result=out Bits(c.video.recordBits bits)
  val capacity=out UInt(6 bits)
  val total=out UInt(8 bits)
  result:= ~source
  capacity:=U(c.video.capacity,6 bits)
  total:=U(c.total,8 bits)
}

case class Lwr27PairConfig(left:Lwr27Video,right:Lwr27Video)
class Lwr27Pair(c:Lwr27PairConfig) extends Component {
  setDefinitionName("DerivedPair")
  require(c.left.depth>=1 && c.left.depth<=4);require(c.right.depth>=1 && c.right.depth<=4)
  require(c.left.data>=1 && c.left.data<=32);require(c.right.data>=1 && c.right.data<=32)
  require(c.left.lanes>=1 && c.left.lanes<=4);require(c.right.lanes>=1 && c.right.lanes<=4)
  require(c.left.generation>=1 && c.left.generation<=8);require(c.right.generation>=1 && c.right.generation<=8)
  val left=out UInt(8 bits);val right=out UInt(8 bits)
  left:=U(c.left.recordBits,8 bits);right:=U(c.right.recordBits,8 bits)
}
class Lwr27Parent(c:Lwr27Config) extends Component {
  setDefinitionName("DerivedParent")
  require(c.video.depth>=1 && c.video.depth<=4);require(c.video.data>=1 && c.video.data<=32)
  require(c.video.lanes>=1 && c.video.lanes<=4);require(c.video.generation>=1 && c.video.generation<=8)
  require(c.video.data>=c.video.lanes)
  val source=in Bits(c.video.recordBits bits);val result=out Bits(c.video.recordBits bits)
  val capacity=out UInt(6 bits);val total=out UInt(8 bits)
  val child=new Lwr27Child(c)
  child.source:=source;result:=child.result;capacity:=child.capacity;total:=child.total
}
case class Lwr27RecursiveConfig(count:Param[Int],width:Param[Int],mode:Int) {
  val termBits=width+1
  val packedBits=count*termBits
  val ordinary=7
}
@_root_.spinal.core.rtlRecursive
final class Lwr27Recursive(c:Lwr27RecursiveConfig) extends Component {
  setDefinitionName("DerivedRecursive")
  require(c.count>=1 && c.count<=4);require(c.width>=1 && c.width<=8)
  noIoPrefix()
  val io=new Bundle {val source=in(Vec(UInt(c.termBits bits),c.count));val result=out UInt(16 bits)}
  if(c.count==1)io.result:=io.source(0).resize(16)
  else {
    val child=new Lwr27Recursive(Lwr27RecursiveConfig(c.count-1,c.width,c.mode))
    val remaining=Vec(UInt(c.termBits bits),c.count-1)
    for(i<-0 until (c.count-1))remaining(i):=io.source(i+1)
    child.io.source:=remaining
    io.result:=child.io.result+io.source(0).resize(16)
  }
}

case class Lwr27InvalidConfig(count:Param[Int]) {val bits=count-3}
class Lwr27Invalid(c:Lwr27InvalidConfig) extends Component {
  require(c.count>=1 && c.count<=4)
  val source=in Bits(c.bits bits);val result=out Bits(c.bits bits);result:=source
}

case class Lwr27Constrained(count:Param[Int],lanes:Param[Int]) {
  require(count>=1 && count<=8)
  require(lanes>=1 && lanes<=4)
  val capacity=count+1
  require(capacity>lanes,"capacity must exceed lanes")
}
class Lwr27ConstrainedChild(c:Lwr27Constrained) extends Component {
  setDefinitionName("DerivedConstraints")
  val result=out UInt(4 bits);result:=U(c.capacity,4 bits)
}

object Lwr27Arithmetic {def twice(n:Int):Int=2*n}
case class Lwr27ArrayConfig(count:Param[Int],width:Param[Int]) {
  require(count>=1 && count<=4);require(width>=1 && width<=8)
  val capacity=count+1
  val wordBits=width+1
  val doubled=Lwr27Arithmetic.twice(count)
  val ordinary=(2147483647+1)/2
  val signed=ordinary+count
}
class Lwr27Array(c:Lwr27ArrayConfig) extends Component {
  setDefinitionName("DerivedArray")
  val source=in(Vec(Bits(c.wordBits bits),c.capacity))
  val result=out(Vec(Bits(c.wordBits bits),c.capacity))
  val doubled=out UInt(4 bits)
  val signed=out SInt(32 bits)
  signed:=S(c.signed,32 bits)
  for(i<-0 until c.capacity)result(i):= ~source(i)
  doubled:=U(c.doubled,4 bits)
}

class DerivedConfigurationParameterTests extends AnyFunSuite {
  private def run(dir:Path,cmd:Seq[String]):Unit={
    val r=Increment66ToolEvidence.run(dir,cmd)
    assert(r._1==0,cmd.mkString(" ")+"\n"+r._2)
  }
  private def generate(default:Int):Path={
    val dir=Files.createTempDirectory("derived-config-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
      new Lwr27Child(Lwr27Config(Lwr27Video(default,8,1,2)))
    };dir
  }
  test("nested derived fields retain symbolic geometry, overrides and deterministic output") {
    val dir=generate(2)
    val rtl=new String(Files.readAllBytes(dir.resolve("DerivedConfiguration.v")),"UTF-8")
    assert(rtl==new String(Files.readAllBytes(generate(2).resolve("DerivedConfiguration.v")),"UTF-8"))
    assert(rtl.contains("C_VIDEO_DEPTH") && rtl.contains("C_VIDEO_DATA") && rtl.contains("C_VIDEO_GENERATION"),rtl)
    assert(!rtl.linesIterator.exists(l=>l.contains("parameter ") && l.contains("CAPACITY")),rtl)
    assert(rtl.contains("DERIVED_C_VIDEO_CAPACITY") && rtl.contains("DERIVED_C_TOTAL"),rtl)
    assert(!rtl.contains("CONFIGURATION_DERIVED"),rtl)
    for((depth,data,lanes,generation)<-Seq((1,1,1,1),(2,8,4,2),(3,17,3,5),(4,32,4,8))) {
      val width=data+7+generation+lanes+16+1
      Files.write(dir.resolve("tb.v"),s"""module tb;
reg[$width-1:0]source;wire[$width-1:0]result;wire[5:0]capacity;wire[7:0]total;
DerivedConfiguration #(.C_VIDEO_DEPTH($depth),.C_VIDEO_DATA($data),.C_VIDEO_LANES($lanes),.C_VIDEO_GENERATION($generation)) dut(source,result,capacity,total);
integer i;initial begin for(i=0;i<100;i=i+1)begin source={$$random,$$random,$$random};#1;
if(result!==~source || capacity!==${(1<<depth)+1} || total!==${width*2+depth+2})$$fatal(1,"derived override");end
source='bx;#1;if(result!==source)$$fatal(1,"X propagation");$$finish;end endmodule
""".getBytes("UTF-8"))
      run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim","DerivedConfiguration.v","tb.v"));run(dir,Seq("vvp","sim"))
    }
    run(dir,Seq("verilator","--lint-only","--language","1364-2001","-Wno-fatal","-DSYNTHESIS","--top-module","DerivedConfiguration","DerivedConfiguration.v"))
    run(dir,Seq("yosys","-Q","-T","-p","read_verilog DerivedConfiguration.v; hierarchy -check -top DerivedConfiguration; proc; opt; check -assert; select -assert-none t:$dlatch"))
  }
  test("distinct defaults use the same symbolic derivation") {
    val a=generate(1);val b=generate(4)
    for(dir<-Seq(a,b)) {
      val rtl=new String(Files.readAllBytes(dir.resolve("DerivedConfiguration.v")),"UTF-8")
      assert(rtl.contains("C_VIDEO_DEPTH") && rtl.contains("C_VIDEO_DATA"),rtl)
    }
  }
  test("shared configuration instances rebind independently through nested paths") {
    val dir=Files.createTempDirectory("derived-pair-")
    val video=Lwr27Video(2,8,1,2)
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
      new Lwr27Pair(Lwr27PairConfig(video,video))
    }
    Files.write(dir.resolve("tb.v"),"""module tb;
wire[7:0]l,r;
DerivedPair #(.C_LEFT_DATA(1),.C_RIGHT_DATA(32),.C_LEFT_GENERATION(3),.C_RIGHT_GENERATION(8)) dut(l,r);
initial begin #1;if(l!==29 || r!==65)$fatal(1,"shared config derived roots");$finish;end endmodule
""".getBytes("UTF-8"))
    run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim","DerivedPair.v","tb.v"));run(dir,Seq("vvp","sim"))
  }
  test("parent child bindings preserve symbolic derivations and cross-field guards") {
    val dir=Files.createTempDirectory("derived-parent-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
      new Lwr27Parent(Lwr27Config(Lwr27Video(2,8,1,2)))
    }
    val rtl=new String(Files.readAllBytes(dir.resolve("DerivedParent.v")),"UTF-8")
    assert(rtl.contains(".C_VIDEO_DATA"),rtl)
    Files.write(dir.resolve("tb.v"),"""module tb;
reg[67:0]source;wire[67:0]result;wire[5:0]capacity;wire[7:0]total;
DerivedParent #(.C_VIDEO_DATA(32),.C_VIDEO_DEPTH(4),.C_VIDEO_LANES(4),.C_VIDEO_GENERATION(8)) dut(source,result,capacity,total);
initial begin source=68'h123456789;#1;if(result!==~source || capacity!==17 || total!==142)$fatal(1,"parent derived binding");$finish;end endmodule
""".getBytes("UTF-8"))
    run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim","DerivedParent.v","tb.v"));run(dir,Seq("vvp","sim"))
    Files.write(dir.resolve("bad.v"),"""module tb;DerivedParent #(.C_VIDEO_DATA(1),.C_VIDEO_LANES(4)) dut();initial begin #1;$finish;end endmodule""".getBytes("UTF-8"))
    run(dir,Seq("iverilog","-g2001","-s","tb","-o","bad","DerivedParent.v","bad.v"))
    assert(Increment66ToolEvidence.run(dir,Seq("vvp","bad"))._1!=0)
  }
  test("recursive bindings recompute derived term geometry on non-default overrides") {
    val dir=Files.createTempDirectory("derived-recursive-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
      new Lwr27Recursive(Lwr27RecursiveConfig(3,3,0))
    }
    val rtl=new String(Files.readAllBytes(dir.resolve("DerivedRecursive.v")),"UTF-8")
    assert("module DerivedRecursive".r.findAllIn(rtl).size==1,rtl)
    for((count,width)<-Seq((1,1),(2,4),(3,7),(4,8))) {
      val bits=width+1
      Files.write(dir.resolve("tb.v"),s"""module tb;
reg[${count*bits}-1:0]source;wire[15:0]result;reg[15:0]expected;integer i,j;
DerivedRecursive #(.C_COUNT($count),.C_WIDTH($width)) dut(source,result);
initial begin for(i=0;i<100;i=i+1)begin source={$$random,$$random};expected=0;
for(j=0;j<$count;j=j+1)expected=expected+source[j*$bits+:$bits];#1;
if(result!==expected)$$fatal(1,"recursive derived geometry");end $$finish;end endmodule
""".getBytes("UTF-8"))
      run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim","DerivedRecursive.v","tb.v"));run(dir,Seq("vvp","sim"))
    }
  }

  test("derived widths must be legal across the domain, not just at the default") {
    val dir=Files.createTempDirectory("derived-invalid-")
    intercept[Exception] {
      MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {new Lwr27Invalid(Lwr27InvalidConfig(4))}
    }
  }

  test("configuration requirements retain domains and cross-field override guards") {
    val dir=Files.createTempDirectory("derived-constraints-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
      new Lwr27ConstrainedChild(Lwr27Constrained(4,2))
    }
    for((count,lanes,valid)<-Seq((8,4,true),(1,1,true),(1,4,false),(9,1,false))) {
      Files.write(dir.resolve("tb.v"),s"""module tb;wire[3:0]result;
DerivedConstraints #(.C_COUNT($count),.C_LANES($lanes)) dut(result);
initial begin #1;if(result!==${count+1})$$fatal(1,"constraint result");$$finish;end endmodule
""".getBytes("UTF-8"))
      run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim","DerivedConstraints.v","tb.v"))
      val r=Increment66ToolEvidence.run(dir,Seq("vvp","sim"));assert((r._1==0)==valid,r._2)
      if(count==1 && lanes==4)assert(r._2.contains("capacity must exceed lanes"),r._2)
    }
  }

  test("derived array geometry and loop bounds retain symbolic overrides") {
    val dir=Files.createTempDirectory("derived-array-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
      preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray)) {new Lwr27Array(Lwr27ArrayConfig(2,3))}
    val rtl=new String(Files.readAllBytes(dir.resolve("DerivedArray.v")),"UTF-8")
    assert(rtl.contains("for (") && rtl.contains("C_COUNT") && rtl.contains("C_WIDTH"),rtl)
    for((count,width)<-Seq((1,1),(3,5),(4,8))) {
      val bits=(count+1)*(width+1)
      Files.write(dir.resolve("tb.v"),s"""module tb;reg[$bits-1:0]source;wire[$bits-1:0]result;wire[3:0]doubled;wire signed[31:0]signed_value;
DerivedArray #(.C_COUNT($count),.C_WIDTH($width)) dut(source,result,doubled,signed_value);
integer i;initial begin for(i=0;i<64;i=i+1)begin source={$$random,$$random};#1;
if(result!==~source || doubled!==${count*2} || signed_value!==${-1073741824+count})$$fatal(1,"derived array bound");end $$finish;end endmodule
""".getBytes("UTF-8"))
      run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim","DerivedArray.v","tb.v"));run(dir,Seq("vvp","sim"))
    }
  }

}
