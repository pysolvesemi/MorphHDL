package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

class LightweightHierarchyCapacityTests extends AnyFunSuite {
  class CapacityChild(actualCount: ElabInt, actualWidth: ElabInt) extends Component {
    noIoPrefix()
    val count: ElabInt=RtlParam.int("COUNT",actualCount,1,8)
    val width: ElabInt=RtlParam.int("WIDTH",actualWidth,1,16)
    val source=in(Vec(UInt(width bits),count))
    val result=out(Vec(UInt(width bits),count))
    for(i<-0 until count) result(i) := ~source(i)
  }
  for(symbolic<-Seq(false,true); unpacked<-Seq(false,true))
    test(s"smaller parent capacity retains exact child actual symbolic=$symbolic unpacked=$unpacked") {
      def generate() = {
        val dir=Files.createTempDirectory("lightweight-hierarchy-capacity-")
        MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
          preserveConstantVecs=true,preserveConstantLoops=true,
          vecLayout=if(unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector)) {
          new Component {
            setDefinitionName("CapacityTop")
            val count: ElabInt=if(symbolic) RtlParam.int("COUNT",3,1,4) else ElabInt.literal(3)
            val width: ElabInt=RtlParam.int("WIDTH",8,1,16)
            val source=in(Vec(UInt(width bits),count))
            val result=out(Vec(UInt(width bits),count))
            val child=new CapacityChild(count,width)
            child.source := source
            result := child.result
          }
        }
        dir
      }
      val dir=generate();val again=generate()
      val files=dir.toFile.listFiles.filter(_.getName.endsWith(".v")).map(_.getName).sorted.toSeq
      files.foreach(file=>assert(java.util.Arrays.equals(Files.readAllBytes(dir.resolve(file)),Files.readAllBytes(again.resolve(file)))))
      val profiles=(for(n<-(if(symbolic)Seq(1,2,3,4)else Seq(3));w<-Seq(1,8,16)) yield s"Check #(.N($n),.W($w)) c_${n}_$w(data);").mkString("\n")
      val binding=if(symbolic) ".COUNT(N)," else ""
      Files.write(dir.resolve("tb.v"),s"""module Check #(parameter N=3,W=8)(input [63:0] data);
wire [N*W-1:0] result; CapacityTop #($binding.WIDTH(W)) dut(.source(data[N*W-1:0]),.result(result));
always @(data) begin #1; if(result !== ~data[N*W-1:0]) $$fatal; end endmodule
module tb; reg [63:0] data; integer i;
$profiles
initial begin data=0; #3; data=~64'd0; #3; data='bx; #3; data='bz; #3;
for(i=0;i<64;i=i+1) begin data={$$random,$$random}; #3; end $$finish; end endmodule
""".getBytes("UTF-8"))
      for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim")++files++Seq("tb.v"),Seq("vvp","sim"),
          Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","--top-module","CapacityTop")++files,
          Seq("yosys","-Q","-T","-p",s"read_verilog ${files.mkString(" ")}; hierarchy -check -top CapacityTop; synth -top CapacityTop; check -assert; select -assert-none t:$$dlatch"))) {
        val(status,log)=Increment66ToolEvidence.run(dir,command);assert(status==0,s"$dir\n$log")
      }
    }
  for(reverse<-Seq(false,true)) test(s"mixed literal child counts share their definition reverse=$reverse") {
    val dir=Files.createTempDirectory("lightweight-mixed-capacity-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
      preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray)) {
      new Component {
        setDefinitionName("MixedCapacity")
        val width: ElabInt=RtlParam.int("WIDTH",8,1,16)
        val source3=in(Vec(UInt(width bits),ElabInt.literal(3)))
        val source5=in(Vec(UInt(width bits),ElabInt.literal(5)))
        val result3=out(Vec(UInt(width bits),ElabInt.literal(3)))
        val result5=out(Vec(UInt(width bits),ElabInt.literal(5)))
        val instances=if(reverse) Seq(new CapacityChild(ElabInt.literal(5),width),new CapacityChild(ElabInt.literal(3),width))
          else Seq(new CapacityChild(ElabInt.literal(3),width),new CapacityChild(ElabInt.literal(5),width))
        val small=instances(if(reverse) 1 else 0)
        val large=instances(if(reverse) 0 else 1)
        small.source := source3
        large.source := source5
        result3 := small.result
        result5 := large.result
      }
    }
    val files=dir.toFile.listFiles.filter(_.getName.endsWith(".v")).map(_.getName).toSeq
    val rtl=files.map(f=>new String(Files.readAllBytes(dir.resolve(f)),"UTF-8")).mkString("\n")
    assert("(?m)^module CapacityChild\\b".r.findAllIn(rtl).size==1,rtl)
    assert(!rtl.contains("module CapacityChild_"),rtl)
    val profiles=Seq(1,8,16).map(w=>s"Check #(.W($w)) c$w(data);").mkString("\n")
    Files.write(dir.resolve("tb.v"),s"""module Check #(parameter W=8)(input [127:0] data);
wire [3*W-1:0] a; wire [5*W-1:0] b;
MixedCapacity #(.WIDTH(W)) dut(.source3(data[3*W-1:0]),.source5(data[5*W-1:0]),.result3(a),.result5(b));
always @(data) begin #1; if(a !== ~data[3*W-1:0] || b !== ~data[5*W-1:0]) $$fatal; end endmodule
module tb; reg [127:0] data; integer i;
$profiles
initial begin data=0; #3; data=~128'd0; #3; data='bx; #3; data='bz; #3;
for(i=0;i<64;i=i+1) begin data={$$random,$$random,$$random,$$random}; #3; end $$finish; end endmodule
""".getBytes("UTF-8"))
    for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim")++files++Seq("tb.v"),Seq("vvp","sim"))) {
      val(status,log)=Increment66ToolEvidence.run(dir,command);assert(status==0,s"$dir\n$log")
    }
  }

}
