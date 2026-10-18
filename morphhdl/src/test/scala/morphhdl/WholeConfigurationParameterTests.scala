package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

case class WholeVideo(numBytes:Int=8, enabled:Boolean=true)
case class WholeConfig(video:WholeVideo=WholeVideo(), timeout:Int=1024) {
  val doubled:Int=video.numBytes*2
}
class WholeConfigModule(c:Param[WholeConfig]) extends Component {
  setDefinitionName("WholeConfigModule")
  require(c.video.numBytes>=1 && c.video.numBytes<=16)
  require(c.timeout>=1 && c.timeout<=65535)
  val x=in Bits(c.video.numBytes bits)
  val y=out Bits(c.doubled bits)
  val timeout=out UInt(16 bits);timeout:=U(c.timeout,16 bits)
  if(c.video.enabled) y:=x.resize(c.doubled) else y:=0
}

class WholeParent(c:Param[WholeConfig]) extends Component {
  setDefinitionName("WholeParent")
  require(c.video.numBytes>=1 && c.video.numBytes<=16)
  require(c.timeout>=1 && c.timeout<=65535)
  val x=in Bits(c.video.numBytes bits);val y=out Bits(c.doubled bits)
  val child=new WholeConfigModule(c)
  val timeout=out UInt(16 bits);timeout:=child.timeout
  child.x:=x;y:=child.y
}
case class WholePair(left:WholeVideo,right:WholeVideo)
class WholePairModule(c:Param[WholePair]) extends Component {
  setDefinitionName("WholePairModule")
  require(c.left.numBytes>=1 && c.left.numBytes<=16)
  require(c.right.numBytes>=1 && c.right.numBytes<=16)
  val left=out UInt(8 bits);val right=out UInt(8 bits)
  if(c.left.enabled) left:=U(c.left.numBytes,8 bits) else left:=0
  if(c.right.enabled) right:=U(c.right.numBytes,8 bits) else right:=0
}
case class WholeRecursiveConfig(count:Int=3)
@_root_.spinal.core.rtlRecursive
final class WholeRecursive(c:Param[WholeRecursiveConfig]) extends Component {
  setDefinitionName("WholeRecursive")
  require(c.count>=1 && c.count<=4)
  noIoPrefix()
  val io=new Bundle {val terms=in(Vec(UInt(8 bits),c.count));val result=out UInt(16 bits)}
  if(c.count==1) io.result:=io.terms(0).resize(16)
  else {
    val child=new WholeRecursive(c.copy(count=c.count-1))
    val remaining=Vec(UInt(8 bits),c.count-1)
    for(i<-0 until(c.count-1))remaining(i):=io.terms(i+1)
    child.io.terms:=remaining
    io.result:=child.io.result+io.terms(0).resize(16)
  }
}

object WholeLayout {def twice(n:Int):Int=n*2}
case class WholeConstrained(count:Int=3, lanes:Int=2) {
  require(count>=1 && count<=8)
  require(lanes>=1 && lanes<=4)
  require(count>=lanes)
  val width=WholeLayout.twice(count)
}
class WholeConstrainedModule(c:Param[WholeConstrained]) extends Component {
  setDefinitionName("WholeConstrainedModule")
  val x=in Bits(c.width bits);val y=out Bits(c.width bits)
  for(i<-0 until c.width)y(i):=x(i)
  val lanes=out UInt(4 bits);lanes:=U(c.lanes,4 bits)
}
case class WholeMixed(@rtlParamName("WIDTH") width:Int=3,count:Param[Int]=2)
class WholeMixedModule(c:Param[WholeMixed]) extends Component {
  setDefinitionName("WholeMixedModule")
  require(c.width>=1 && c.width<=8);require(c.count>=1 && c.count<=4)
  val x=in Bits(c.width bits);val y=out Bits(c.width bits);y:=x
  val count=out UInt(4 bits);count:=U(c.count,4 bits)
}

class WholeConfigurationParameterTests extends AnyFunSuite {
  test("ordinary configurations remain host values while whole-object opt-in emits scalar formals") {
    val host=WholeConfig();assert(host.video.numBytes==8 && host.doubled==16)
    val dir=Files.createTempDirectory("whole-config-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false))(new WholeConfigModule(host))
    val rtl=new String(Files.readAllBytes(dir.resolve("WholeConfigModule.v")),"UTF-8")
    assert(rtl.contains("C_VIDEO_NUM_BYTES"),rtl)
    assert(rtl.contains("C_VIDEO_ENABLED"),rtl)
    assert(rtl.contains("C_TIMEOUT"),rtl)
    assert(!rtl.contains("parameter integer C_DOUBLED"),rtl)
  }
  private def generate(name:String)(body: => Component):java.nio.file.Path={
    val dir=Files.createTempDirectory("whole-"+name+"-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false))(body)
    dir
  }
  private def simulate(dir:java.nio.file.Path,body:String):Unit={
    Files.write(dir.resolve("tb.v"),body.getBytes("UTF-8"))
    val stream=Files.list(dir)
    val sources=try {import scala.collection.JavaConverters._;stream.iterator.asScala.filter(_.toString.endsWith(".v")).map(_.getFileName.toString).toVector.sorted}finally stream.close()
    for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim")++sources,Seq("vvp","sim"))) {
      val result=Increment66ToolEvidence.run(dir,command)
      assert(result._1==0,command.mkString(" ")+"\n"+result._2)
    }
  }
  test("nested whole-object child forwarding retains parameter overrides and derived widths") {
    val dir=generate("parent")(new WholeParent(WholeConfig()))
    val rtl=new String(Files.readAllBytes(dir.resolve("WholeParent.v")),"UTF-8")
    assert(rtl.contains(".C_VIDEO_NUM_BYTES"),rtl)
    for(width<-Seq(1,3,16);enabled<-Seq(0,1))simulate(dir,s"""module tb;
reg[${width-1}:0]x;wire[${width*2-1}:0]y;wire[15:0]timeout;integer k;
WholeParent #(.C_VIDEO_NUM_BYTES($width),.C_VIDEO_ENABLED($enabled),.C_TIMEOUT(65535)) dut(.x(x),.y(y),.timeout(timeout));
initial begin for(k=0;k<40;k=k+1)begin x=$$random;#1;
if(timeout!==65535)$$fatal(1,"forwarded scalar");
if(y!==($enabled ? {{${width}{1'b0}},x} : ${width*2}'d0))$$fatal(1,"whole configuration override");
end $$finish;end endmodule""")
  }
  test("same actual object in two fields preserves independent paths") {
    val shared=WholeVideo(3,true)
    val dir=generate("pair")(new WholePairModule(WholePair(shared,shared)))
    simulate(dir,"""module tb;wire[7:0]left,right;
WholePairModule #(.C_LEFT_NUM_BYTES(1),.C_RIGHT_NUM_BYTES(16),.C_LEFT_ENABLED(0),.C_RIGHT_ENABLED(1)) dut(left,right);
initial begin #1;if(left!==0 || right!==16)$fatal(1,"independent paths");$finish;end endmodule""")
  }
  test("whole-object recursive copy retains decreasing symbolic count and generic definition") {
    for(default<-Seq(1,3)) {
      val dir=generate("recursive")(new WholeRecursive(WholeRecursiveConfig(default)))
      val rtl=new String(Files.readAllBytes(dir.resolve("WholeRecursive.v")),"UTF-8")
      assert("module WholeRecursive".r.findAllIn(rtl).length==1,rtl)
      for(count<-1 to 4)simulate(dir,s"""module tb;
reg[${count*8-1}:0]terms;wire[15:0]result;reg[15:0]expected;integer i,j;
WholeRecursive #(.C_COUNT($count)) dut(terms,result);
initial begin for(i=0;i<32;i=i+1)begin terms=$$random;expected=0;
for(j=0;j<$count;j=j+1)expected=expected+terms[j*8+:8];#1;
if(result!==expected)$$fatal(1,"recursive whole configuration");end $$finish;end endmodule""")
    }
  }
  test("whole-object illegal defaults retain domain validation") {
    val error=intercept[Exception]{generate("invalid")(new WholeConfigModule(WholeConfig(WholeVideo(17,true))))}
    assert(Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_!=null).exists(e=>Option(e.getMessage).exists(m=>m.contains("DOMAIN") || m.contains("requirement failed"))),error.toString)
  }
  test("derived pure helpers constraints and ordinary loops use promoted leaves") {
    val dir=generate("constrained")(new WholeConstrainedModule(WholeConstrained()))
    for((count,lanes)<-Seq((1,1),(5,3),(8,4)))simulate(dir,s"""module tb;
reg[${count*2-1}:0]x;wire[${count*2-1}:0]y;wire[3:0]lanes;
WholeConstrainedModule #(.C_COUNT($count),.C_LANES($lanes)) dut(x,y,lanes);
initial begin x='b10101;#1;if(x!==y || lanes!==$lanes)$$fatal(1,"derived helper");$$finish;end endmodule""")
    Files.write(dir.resolve("tb.v"),"""module tb;reg[1:0]x;wire[1:0]y;wire[3:0]lanes;
WholeConstrainedModule #(.C_COUNT(1),.C_LANES(4)) dut(x,y,lanes);initial begin #2;$finish;end endmodule""".getBytes("UTF-8"))
    assert(Increment66ToolEvidence.run(dir,Seq("iverilog","-g2001","-s","tb","-o","invalid","WholeConstrainedModule.v","tb.v"))._1==0)
    assert(Increment66ToolEvidence.run(dir,Seq("vvp","invalid"))._1!=0,"illegal cross-field override must fail")
  }
  test("mixed explicit leaves and named promoted leaves publish once with deterministic output") {
    val dirs=Seq.fill(2)(generate("mixed")(new WholeMixedModule(WholeMixed())))
    val rtl=dirs.map(d=>new String(Files.readAllBytes(d.resolve("WholeMixedModule.v")),"UTF-8"))
    assert(rtl(0)==rtl(1))
    assert("parameter integer WIDTH".r.findAllIn(rtl.head).length==1,rtl.head)
    assert("parameter integer C_COUNT".r.findAllIn(rtl.head).length==1,rtl.head)
    simulate(dirs.head,"""module tb;reg[6:0]x;wire[6:0]y;wire[3:0]count;
WholeMixedModule #(.WIDTH(7),.C_COUNT(4)) dut(x,y,count);
initial begin x=7'h5a;#1;if(y!==x || count!==4)$fatal(1,"mixed leaves");$finish;end endmodule""")
    for(cmd<-Seq(Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","WholeMixedModule.v"),
      Seq("yosys","-Q","-p","read_verilog -DSYNTHESIS WholeMixedModule.v; hierarchy -check -top WholeMixedModule; proc; opt; check -assert; stat"))) {
      val result=Increment66ToolEvidence.run(dirs.head,cmd);assert(result._1==0,result._2)
    }
  }
}
