package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

case class Lwr23Video(numBytes: Param[Int], mode: Int)
case class Lwr23Config(video: Lwr23Video)
class Lwr23Child(c: Lwr23Config) extends Component {
  setDefinitionName("ConfiguredChild")
  require(c.video.numBytes >= 1 && c.video.numBytes <= 8)
  val source=in Bits(c.video.numBytes * 8 bits)
  val result=out Bits(c.video.numBytes * 8 bits)
  result := ~source
}

class NestedConfigurationParameterTests extends AnyFunSuite {
  test("nested fields retain independent child bindings and uppercase paths") {
    def generate() = {
      val directory=Files.createTempDirectory("nested-config-")
      MorphVerilog(SpinalConfig(targetDirectory=directory.toString,headerWithDate=false)) {
        new Component {
          setDefinitionName("ConfiguredTop")
          val numBytes:Param[Int]=Param(4)
          require(numBytes>=1 && numBytes<=8)
          val source=in Bits(numBytes*8 bits)
          val result=out Bits(numBytes*8 bits)
          val child=new Lwr23Child(Lwr23Config(Lwr23Video(numBytes,0)))
          child.source:=source
          result:=child.result
        }
      }
      directory
    }
    val directory=generate()
    val rtl=new String(Files.readAllBytes(directory.resolve("ConfiguredTop.v")),"UTF-8")
    assert(rtl.contains("NUM_BYTES") && rtl.contains("C_VIDEO_NUM_BYTES"),rtl)
    assert(rtl==new String(Files.readAllBytes(generate().resolve("ConfiguredTop.v")),"UTF-8"))
    Files.write(directory.resolve("tb.v"),"""module tb;
reg[55:0]source;wire[55:0]result;
ConfiguredTop #(.NUM_BYTES(7)) dut(source,result);
integer i;initial begin for(i=0;i<128;i=i+1)begin source={$random,$random};#1;
if(result!==~source)$fatal(1,"configuration binding");end $finish;end endmodule
""".getBytes("UTF-8"))
    for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","ConfiguredTop.v","tb.v"),Seq("vvp","sim"))) {
      val result=Increment66ToolEvidence.run(directory,command)
      assert(result._1==0,command.mkString(" ")+"\n"+result._2)
    }
  }
  test("shared configuration objects retain independent declaration-path formals") {
    val directory=Files.createTempDirectory("shared-config-")
    val video=Lwr23Video(3,7)
    MorphVerilog(SpinalConfig(targetDirectory=directory.toString,headerWithDate=false)) {
      new Lwr23Pair(Lwr23SharedPair(video,video))
    }
    val rtl=new String(Files.readAllBytes(directory.resolve("SharedConfiguration.v")),"UTF-8")
    assert(rtl.contains("C_LEFT_NUM_BYTES") && rtl.contains("C_RIGHT_NUM_BYTES") && !rtl.contains("parameter integer C_LEFT_MODE"),rtl)
    Files.write(directory.resolve("tb.v"),"""module tb;
wire[3:0]l,r;SharedConfiguration #(.C_LEFT_NUM_BYTES(1),.C_RIGHT_NUM_BYTES(7)) dut(l,r);
initial begin #1;if(l!==1 || r!==7)$fatal(1,"aliased configuration paths");$finish;end endmodule
""".getBytes("UTF-8"))
    for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","SharedConfiguration.v","tb.v"),Seq("vvp","sim"))) {
      val result=Increment66ToolEvidence.run(directory,command)
      assert(result._1==0,command.mkString(" ")+"\n"+result._2)
    }
  }

}

case class Lwr23SharedPair(left:Lwr23Video,right:Lwr23Video)
class Lwr23Pair(c:Lwr23SharedPair) extends Component {
  setDefinitionName("SharedConfiguration")
  require(c.left.numBytes>=1 && c.left.numBytes<=8)
  require(c.right.numBytes>=1 && c.right.numBytes<=8)
  val left=out UInt(4 bits);val right=out UInt(4 bits)
  left:=U(c.left.numBytes,4 bits);right:=U(c.right.numBytes,4 bits)
}
