package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class LightweightStatementChainTests extends AnyFunSuite {
  for (default <- Seq(1, 3)) test(s"statement else-if preserves absent terminal branch default=$default") {
    def generate() = {
      val dir = Files.createTempDirectory("lightweight-statement-chain-")
      MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
        new Component {
          setDefinitionName("StatementChain")
          val width: ElabInt = RtlParam.int("WIDTH",8,1,16)
          val mode: ElabInt = RtlParam.int("MODE",default,1,3)
          val source = in Bits(width bits)
          val result = out Bits(width bits)
          if (mode == 1) {
            result := source
            source // A statement-valued if discards this ordinary Scala result.
          } else if (mode == 2) {
            result := ~source
            source
          }
          if (mode == 3) result := 0
        }
      }
      dir -> new String(Files.readAllBytes(dir.resolve("StatementChain.v")),"UTF-8")
    }
    val (dir,rtl)=generate()
    assert(rtl==generate()._2)
    val instances=(for(w<-Seq(1,8,16);m<-1 to 3) yield
      s"Check #(.W($w),.M($m)) c_${w}_$m(data);").mkString("\n")
    Files.write(dir.resolve("tb.v"),s"""module Check #(parameter W=1,M=1)(input [15:0] data);
wire [W-1:0] result;
StatementChain #(.WIDTH(W),.MODE(M)) dut(.source(data[W-1:0]),.result(result));
always @(data) begin #1;
if(result !== (M==1 ? data[W-1:0] : M==2 ? ~data[W-1:0] : {W{1'b0}})) $$fatal;
end endmodule
module tb; reg [15:0] data; integer i;
$instances
initial begin data=0; #3; data=16'hffff; #3; data='bx; #3; data='bz; #3;
for(i=0;i<64;i=i+1) begin data=$$random; #3; end $$finish; end endmodule
""".getBytes("UTF-8"))
    for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","StatementChain.v","tb.v"),
      Seq("vvp","sim"),Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","StatementChain.v"))) {
      val(status,log)=Increment66ToolEvidence.run(dir,command)
      assert(status==0,s"$dir\n$log")
    }
  }
  test("a statement chain still rejects Scala-only branches") {
    val dir=Files.createTempDirectory("lightweight-statement-effects-")
    val error=intercept[Exception] {
      MorphVerilog(SpinalConfig(targetDirectory=dir.toString)) {
        new Component {
          val width: ElabInt=RtlParam.int("WIDTH",8,1,16)
          val mode: ElabInt=RtlParam.int("MODE",1,1,3)
          val source=in Bits(width bits)
          val result=out Bits(width bits)
          result := source
          val host=scala.collection.mutable.ArrayBuffer.empty[Int]
          if(mode == 1) {
            val retained=Bits(width bits).dontSimplifyIt()
            retained := source
          } else if(mode == 2) {
            host += 2
          }
        }
      }
    }
    assert(error.getMessage.contains("SCALA-SIDE-EFFECT-UNSUPPORTED"),error.getMessage)
  }

}
