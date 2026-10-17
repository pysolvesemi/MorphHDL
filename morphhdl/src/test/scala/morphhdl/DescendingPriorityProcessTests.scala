package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class DescendingPriorityProcessTests extends AnyFunSuite {
  for(descending <- Seq(false,true); unpacked <- Seq(false,true))
    test(s"ordered priority retains coherent array and packed outputs descending=$descending unpacked=$unpacked") {
      val dir=Files.createTempDirectory("ordered-priority-")
      def emit(target: java.nio.file.Path): Unit = {
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=target.toString,headerWithDate=false),
        preserveConstantVecs=true,preserveConstantLoops=true,
        vecLayout=if(unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector)) {
        new Component {
          setDefinitionName("PriorityTop")
          val count: ElabInt=RtlParam.int("COUNT",3,1,8)
          val width: ElabInt=RtlParam.int("WIDTH",8,1,16)
          val errors=in(Vec(Bits(width bits),count))
          val words=in Bits(count*width bits)
          val mask=in Bits(width bits)
          val enable=in Bool()
          val selected,selectedContext=out Bits(width bits)
          val packedErrors=errors.asBits
          if(unpacked) {
            val results=ElabProcess.prioritySelect(count,packedErrors,mask,Seq(words -> width),descending)
            selected:=0;selectedContext:=0
            when(enable) { selected:=results(0); selectedContext:=results(0) & mask }
          } else {
          val results=ElabProcess.outputsLoop(count,Seq(ElabScopedProcess.Output(width),ElabScopedProcess.Output(width)),descending) { p =>
            p.when(p.bool(enable)) {
              p.when((p.slice(packedErrors,p.index,width) & p.bits(mask)) =/= p.literal(0,width)) {
                p.output(0).assign(p.slice(words,p.index,width))
                p.output(1).assign(p.slice(words,p.index,width) & p.bits(mask))
              }
            }
          }
          selected:=results(0);selectedContext:=results(1)
          }
        }
      }
      }
      emit(dir)
      val again=Files.createTempDirectory("ordered-priority-repeat-")
      emit(again)
      val files=dir.toFile.listFiles.filter(_.getName.endsWith(".v")).map(_.getName).toSeq
      files.foreach(file => assert(java.util.Arrays.equals(Files.readAllBytes(dir.resolve(file)),Files.readAllBytes(again.resolve(file)))))
      val rtl=new String(Files.readAllBytes(dir.resolve("PriorityTop.v")),"UTF-8")
      assert(rtl.contains("for ("),rtl)
      if(descending) assert(rtl.contains(">= 0;"),rtl)
      val traversal=if(descending) "i=N-1;i>=0;i=i-1" else "i=0;i<N;i=i+1"
      val profiles=(for(n<-Seq(1,2,3,5,8);w<-Seq(1,5,16))yield s"Check #(.N($n),.W($w)) c_${n}_$w(errors,words,mask,enable);").mkString("\n")
      Files.write(dir.resolve("tb.v"),s"""module Check #(parameter N=3,W=8)(input [127:0] errors,words,input [15:0] mask,input enable);
wire [W-1:0] selected,selectedContext; reg [W-1:0] refSelected,refContext; integer i;
PriorityTop #(.COUNT(N),.WIDTH(W)) dut(.errors(errors[N*W-1:0]),.words(words[N*W-1:0]),.mask(mask[W-1:0]),.enable(enable),.selected(selected),.selectedContext(selectedContext));
always @(errors or words or mask or enable) begin #1;
refSelected=0;refContext=0;
for($traversal) if(enable) if (|(errors[i*W+:W] & mask[W-1:0])) begin
refSelected=words[i*W+:W];refContext=words[i*W+:W]&mask[W-1:0];end
if(selected !== refSelected || selectedContext !== refContext) $$fatal(1,"priority mismatch N=%d W=%d",N,W);
end endmodule
module tb; reg [127:0] errors,words; reg [15:0] mask; reg enable; integer k;
$profiles
initial begin errors=0;words=~128'd0;mask=~16'd0;enable=1;#3;
for(k=0;k<128;k=k+1) begin errors=128'b1<<k;words={$$random,$$random,$$random,$$random};#3;end
errors=~128'd0;#3;errors='bx;#3;errors='bz;#3;errors=~128'd0;mask='bx;#3;mask='bz;#3;mask=~16'd0;words='bx;#3;words='bz;#3;enable=1'bx;#3;enable=1'bz;#3;enable=1;
for(k=0;k<128;k=k+1) begin errors={$$random,$$random,$$random,$$random};words={$$random,$$random,$$random,$$random};mask=$$random;#3;end $$finish;end endmodule
""".getBytes("UTF-8"))
      for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim")++files++Seq("tb.v"),Seq("vvp","sim"),
        Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","--top-module","PriorityTop")++files,
        Seq("yosys","-Q","-T","-p",s"read_verilog -DSYNTHESIS ${files.mkString(" ")}; hierarchy -check -top PriorityTop; synth -top PriorityTop; check -assert; select -assert-none t:$$dlatch"))) {
        val(status,log)=Increment66ToolEvidence.run(dir,command);assert(status==0,s"$dir\n$log")
      }
    }
  for(bad <- Seq("zero", "short", "foreign")) test(s"priority API rejects invalid source geometry and ownership $bad") {
    val dir=Files.createTempDirectory("invalid-priority-")
    val error=intercept[Exception] {
      MorphVerilog(SpinalConfig(targetDirectory=dir.toString)) {
        new Component {
          val count: ElabInt=RtlParam.int("COUNT",3,1,8)
          val mask=in Bits(8 bits)
          val words=in Bits(count*8 bits)
          val other=new Component { val hidden=Bits(8 bits); hidden:=0 }
          val predicates=if(bad=="foreign") other.hidden else if(bad=="short") mask else words
          val results=ElabProcess.prioritySelect(if(bad=="zero") ElabInt.literal(0) else count,
            predicates,mask,Seq(words -> ElabInt.literal(8)),descending=true)
          val result=out Bits(8 bits); result:=results.head
        }
      }
    }
    assert(error.toString.contains("SPINAL-ELAB-PROCESS"),error.toString)
  }

}
