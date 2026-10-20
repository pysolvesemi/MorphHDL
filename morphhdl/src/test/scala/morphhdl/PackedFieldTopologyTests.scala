package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class PackedFieldTopologyTests extends AnyFunSuite {
  private def generate(multiple:Boolean, terminalPatch:Boolean) = {
    val dir = Files.createTempDirectory("packed-field-topology-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
      preserveConstantLoops=true,preserveConstantVecs=true)) {
      new Component {
        setDefinitionName("PackedFields")
        val WIDTH:Param[Int]=Param(8);require(WIDTH==8)
        val source=in Bits(WIDTH bits)
        val result=out Bits(128 bits)
        def connect():Unit = {
          val context=Bits(128 bits).setName("packedContext").dontSimplifyIt()
          context:=0
          for(i<-0 until 4) {
            context(i*32,8 bits):=source
            context(i*32+8,8 bits):=source
          }
          if(multiple) {
            for(i<-0 until 4)context(i*32+4,4 bits):=source(3 downto 0)
          }
          result:=context
          if(terminalPatch) context(32,8 bits):=B(42,8 bits)
          else { context(32,8 bits):=B(42,8 bits); () }
        }
        connect()
      }
    }
    dir
  }
  for(multiple<-Seq(false,true);terminal<-Seq(false,true))
    test(s"partial fields preserve zero default and ordered overrides multiple=$multiple terminal=$terminal") {
      val dir=generate(multiple,terminal)
      val expected=if(multiple) "{source[3:0],source[3:0]}" else "source"
      Files.write(dir.resolve("tb.v"),s"""module tb;
reg[7:0]source;wire[127:0]result;integer n;reg[7:0]lane;
PackedFields dut(source,result);
initial begin for(n=0;n<258;n=n+1)begin source=n;if(n==256)source=8'hxz;if(n==257)source=8'hzx;lane=$expected;#1;
if(result!=={16'b0,source,lane,16'b0,source,lane,16'b0,source,8'd42,16'b0,source,lane})$$fatal(1,"priority");end $$finish;end
endmodule
""".getBytes("UTF-8"))
      for(cmd<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","PackedFields.v","tb.v"),Seq("vvp","sim"),
        Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","PackedFields.v"),
        Seq("yosys","-Q","-p","read_verilog -DSYNTHESIS PackedFields.v; hierarchy -check -top PackedFields; proc; opt; check -assert; select -assert-none t:$dlatch"))) {
        val r=Increment66ToolEvidence.run(dir,cmd);assert(r._1==0,r._2)
      }
    }
  for(multiple <- Seq(false,true)) test(s"explicit typed partial or competing loops retain default ownership rejection multiple=$multiple") {
    val error=intercept[Exception] {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=Files.createTempDirectory("packed-field-negative-").toString),preserveConstantLoops=true)) {
        new Component {
          val count:ElabInt=ElabInt.literal(4)
          val source=in Bits(8 bits)
          val result=out Bits((if(multiple)32 else 128) bits)
          result:=0
          if(multiple) {
            for(i<-0 until count)result(i*8,8 bits):=source
            for(i<-0 until count)result(i*8,8 bits):= ~source
          } else {
            for(i<-0 until count)result(i*32,8 bits):=source
          }
        }
      }
    }
    assert(Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_!=null)
      .exists(e=>Option(e.getMessage).exists(_.contains("FINITE-PACKED-DEFAULT-UNPROVEN"))),error.toString)
  }
}
