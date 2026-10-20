package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class StructuralSwitchRegisterTests extends AnyFunSuite {
  private def generate(packed: Boolean, mode: String) = {
    val dir = Files.createTempDirectory("structural-switch-register-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
      preserveConstantVecs=true,preserveConstantLoops=true,
      vecLayout=if(packed) MorphAggregateOptions.PackedVector else MorphAggregateOptions.UnpackedArray))(new Component {
      setDefinitionName("Copy")
      val address=in UInt(3 bits)
      val enable, rejectOp, update=in Bool()
      val data=in Bits(8 bits)
      val result=out Bits(40 bits)
      val source=Vec.fill(3)(Reg(Bits(8 bits)) init(0))
      for(i <- 0 until 3) when(update) { source(i):=data ^ B(i,8 bits) }
      val snapshot=Vec.tabulate(5) { lane =>
        if(mode == "clock" && lane == 4) {
          val other = new ClockingArea(ClockDomain.current.copy()) {
            val value = Reg(Bits(8 bits)) init(0)
          }
          other.value
        } else if(mode == "missingReset" && lane == 4) Reg(Bits(8 bits))
        else Reg(Bits(8 bits)) init(if(mode == "reset" && lane == 1) 1 else 0)
      }
      def copy(): Unit = {
        val count: ElabInt = ElabInt.literal(3)
        for(i <- 0 until count) snapshot(i):=source(i)
        if(mode != "missing") snapshot(3):=data
        snapshot(4):= ~data
        if(mode == "overlap") snapshot(1):=data
      }
      when(enable) {
        switch(address) {
          is(0) { snapshot(4):=0 }
          is(2,3) { when(rejectOp) { snapshot(4):=0 } otherwise { if(mode == "nested") { switch(update) { is(True) { copy() } } }
            else if(mode != "default") copy() } }
          default { if(mode == "default") { when(!rejectOp) { copy() } } }
          // A case authored after default must still exclude that default body.
          is(6) { snapshot(4):=0 }
        }
      }
      result:=snapshot.asBits
    })
    dir
  }
  for(packed <- Seq(false,true); mode <- Seq("case","default","nested")) test(s"switch guarded register prefix packed=$packed mode=$mode") {
    val dir=generate(packed,mode)
    val rtl=new String(Files.readAllBytes(dir.resolve("Copy.v")),"UTF-8")
    assert(rtl.contains("generate") && rtl.contains("for ("),rtl)
    val again=generate(packed,mode)
    assert(rtl==new String(Files.readAllBytes(again.resolve("Copy.v")),"UTF-8"))
    Files.write(dir.resolve("tb.v"),s"""module tb;
reg clk=0,reset=0,enable=0,reject=0,update=0; reg[2:0]address=0;reg[7:0]data=0;
wire[39:0]result;reg[39:0]expected=0;reg[23:0]source=0;integer k,j;
Copy dut(.clk(clk),.reset(reset),.enable(enable),.rejectOp(reject),.update(update),.address(address),.data(data),.result(result));
initial begin
reset=1;#2;if(result!==0) $$fatal(1,"async reset");reset=0;
for(k=0;k<512;k=k+1)begin
address=(k==456)?3'bxxx:(k==488)?3'bzzz:k%8;enable=(k/8)%2;reject=(k/16)%2;update=(k/32)%2;data=k*17;#1;
if(enable) begin
if(address==0 || address==6) expected[39:32]=0;
else if(address==2 || address==3) begin
if(reject)expected[39:32]=0;${if(mode!="default") (if(mode=="nested") "else if(update)" else "else") + " begin expected[23:0]=source;expected[31:24]=data;expected[39:32]=~data;end" else ""}
end ${if(mode=="default") "else if(!reject)begin expected[23:0]=source;expected[31:24]=data;expected[39:32]=~data;end" else ""}
end
if(update)for(j=0;j<3;j=j+1)source[j*8+:8]=data^j;
clk=1;#1;if(result!==expected) $$fatal(1,"guard/order mismatch step %d actual %h expected %h",k,result,expected);clk=0;#1;
if(k==255)begin reset=1;#1;expected=0;source=0;if(result!==0) $$fatal(1,"midrun reset");reset=0;end
end $$finish;end endmodule
""".getBytes("UTF-8"))
    for(cmd <- Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","Copy.v","tb.v"),Seq("vvp","sim"),
      Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","Copy.v"),
      Seq("yosys","-Q","-p","read_verilog Copy.v; synth -top Copy; check -assert; select -assert-none t:$dlatch"))) {
      val(code,log)=Increment66ToolEvidence.run(dir,cmd);assert(code==0,s"$dir\n$log")
    }
  }
  for(mode <- Seq("missing","overlap","clock","reset","missingReset")) test(s"reject $mode complement writers") {
    val error=intercept[Exception](generate(false,mode))
    assert(error.toString.contains(if(mode=="overlap") "ASSIGNMENT OVERLAP" else "SPINAL-TYPED-LOOP-REGISTER-STORAGE-MISMATCH"),error.toString)
  }
}
