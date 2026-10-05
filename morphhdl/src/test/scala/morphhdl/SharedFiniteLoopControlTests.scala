package morphhdl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files,Path}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class SharedFiniteLoopControlTests extends AnyFunSuite {
  private def run(dir:Path,args:String*):Unit={
    val(code,log)=Increment66ToolEvidence.run(dir,args)
    assert(code==0,s"${args.mkString(" ")}\n$log")
  }
  for(childBinding <- Seq(false,true); nested <- Seq(false,true)) {
    test(s"module control reads relocated state and drives loop memory and scalar child=$childBinding nested=$nested") {
      val dir=Files.createTempDirectory("shared-loop-control-")
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false,oneFilePerComponent=true),
        preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray))(new Component {
        setDefinitionName("Top")
        val source=in Bits(3 bits);val fault,enable=in Bool();val address=in UInt(2 bits);val data=in Bits(8 bits)
        val result=out Bits(3 bits);val errors=out UInt(8 bits);val memory_result=out Bits(8 bits)
        val flags=Vec.fill(3)(Reg(Bool()) init False)
        val count=Reg(UInt(8 bits)) init 0
        val accept=if(childBinding) {
          val child=new Component {
            setDefinitionName("Control")
            val ready=in Bool();val permit=out Bool();permit:=ready
          }
          child.setName("control")
          val ready=Bool().setName("control_ready").dontSimplifyIt()
          val permit=Bool().setName("control_permit").dontSimplifyIt()
          ready:=enable;child.ready:=ready;permit:=child.permit
          // Reading the parent-authored child input is legal native wiring.
          child.ready && permit
        } else enable
        val bad=(accept && flags.asBits.orR && fault).setName("bad").dontSimplifyIt()
        when(bad) {count:=count+1}
        val memory=Mem(Bits(8 bits),4)
        memory.write(address,data,enable && !bad)
        memory_result:=memory.readSync(address,enable)
        val bits=source.asBools
        ElabFiniteRange.foreach(ElabInt.literal(3),"bank_state") { i =>
          val bank=i(flags)
          when(enable) {
            when(i(bits)) {bank:=True}
            if(nested) { when(fault) { when(bad) {bank:=False} } }
            else {when(bad) {bank:=False}}
          }
        }
        result:=flags.asBits;errors:=count
      })
      val rtl=new String(Files.readAllBytes(dir.resolve("Top.v")),UTF_8)
      assert(rtl.indexOf("assign bad =")>=0 && rtl.indexOf("assign bad =")<rtl.indexOf("generate"),rtl)
      Files.write(dir.resolve("tb.v"),s"""module tb;
reg clk=0,reset=0,enable=0,fault=0;reg[2:0]source=0,flags=0;reg[7:0] data=0,count=0;reg[1:0]address=0;
wire[2:0]result;wire[7:0]errors,memory_result;reg[7:0] memory[0:3],expected_read;reg bad;integer k;
Top dut(.clk(clk),.reset(reset),.enable(enable),.fault(fault),.source(source),.data(data),.address(address),.result(result),.errors(errors),.memory_result(memory_result));
initial begin reset=1;#2;if(result!==0 || errors!==0) $$fatal(1,"reset");reset=0;
for(k=0;k<128;k=k+1)begin
source=(k<16)?0:$$random;enable=(k<16)?1:(k%4!=0);fault=(k<16)?0:(k%3==0);address=k%4;data=k*7;#1;
bad=enable && (|flags) && fault;
if(enable) begin expected_read=memory[address];if(!bad)memory[address]=data;flags=bad?0:(flags|source);end
if(bad)count=count+1;
clk=1;#1;if(result!==flags || errors!==count) $$fatal(1,"control priority/hold");
if(k>=16 && memory_result!==expected_read) $$fatal(1,"memory shared enable/read hold");clk=0;#1;
end $$finish;end endmodule
""".getBytes(UTF_8))
      val files=Seq("Top.v")++(if(childBinding)Seq("Control.v")else Seq.empty)
      run(dir,(Seq("iverilog","-g2001","-s","tb","-o","sim")++files++Seq("tb.v")):_*);run(dir,"vvp","sim")
      run(dir,(Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","Top")++files):_*)
      run(dir,"yosys","-Q","-p",s"read_verilog ${files.mkString(" ")}; synth -top Top; check -assert")
    }
  }
}
