package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

case class Lwr23TypedConfig(word:Param[UInt], @rtlParamName("LegacyMask") mask:Param[Bits],
    offset:Param[SInt], enabled:Param[Boolean], optionalCount:Option[Param[Int]])
class Lwr23TypedChild(c:Lwr23TypedConfig) extends Component {
  setDefinitionName("TypedConfiguration")
  require(c.word>=0 && c.word<=31)
  require(c.mask>=0 && c.mask<=255)
  require(c.offset>= -8 && c.offset<=7)
  require(c.optionalCount>=0 && c.optionalCount<=7)
  val source=in Bits(8 bits)
  val result=out Bits(8 bits)
  val word=out UInt(19 bits)
  val offset=out SInt(9 bits)
  val optionalCount=out UInt(3 bits)
  if(c.enabled) result:=source ^ B(c.mask,8 bits) else result:=source
  word:=U(c.word,19 bits);offset:=S(c.offset,9 bits);optionalCount:=U(c.optionalCount,3 bits)
}

class TypedConfigurationParameterTests extends AnyFunSuite {
  for(present<-Seq(false,true)) test(s"configuration preserves packed signed Boolean optional and explicit names present=$present") {
    val directory=Files.createTempDirectory("typed-config-")
    MorphVerilog(SpinalConfig(targetDirectory=directory.toString,headerWithDate=false)) {
      new Lwr23TypedChild(Lwr23TypedConfig(U(3,19 bits),B(5,8 bits),S(-3,8 bits),true,
        if(present)Some(2)else None))
    }
    val rtl=new String(Files.readAllBytes(directory.resolve("TypedConfiguration.v")),"UTF-8")
    assert(rtl.contains("parameter [18:0] C_WORD") && rtl.contains("parameter [7:0] LegacyMask") &&
      rtl.contains("parameter signed [7:0] C_OFFSET") && rtl.contains("C_ENABLED"),rtl)
    assert(rtl.contains("parameter integer C_OPTIONAL_COUNT")==present,rtl)
    val optional=if(present)",.C_OPTIONAL_COUNT(7)" else ""
    Files.write(directory.resolve("tb.v"),s"""module tb;
reg[7:0]source;wire[7:0]result;wire[18:0]word;wire signed[8:0]offset;wire[2:0]optionalCount;
TypedConfiguration #(.C_WORD(31),.LegacyMask(201),.C_OFFSET(-8),.C_ENABLED(1)$optional)
dut(source,result,word,offset,optionalCount);
integer i;initial begin for(i=0;i<256;i=i+1)begin source=i;#1;
if(result!==(source^8'd201) || word!==19'd31 || offset!==-9'sd8 || optionalCount!==3'd${if(present)7 else 0})
$$fatal(1,"typed configuration override");end $$finish;end endmodule
""".getBytes("UTF-8"))
    for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","TypedConfiguration.v","tb.v"),Seq("vvp","sim"))) {
      val result=Increment66ToolEvidence.run(directory,command)
      assert(result._1==0,command.mkString(" ")+"\n"+result._2)
    }
  }
}
