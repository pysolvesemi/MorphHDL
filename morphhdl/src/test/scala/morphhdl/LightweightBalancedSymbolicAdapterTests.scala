package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib.BalancedTree

class LightweightBalancedSymbolicAdapterTests extends AnyFunSuite {
  for(kind <- Seq("uint","sint","bits"); pipelined <- Seq(false,true); defaultCount <- Seq(1,5))
    test(s"public adapter symbolic geometry kind=$kind pipeline=$pipelined default=$defaultCount") {
      def build[T <: BitVector](factory: ElabInt => T, operation: (T,T) => T) = {
        val dir=Files.createTempDirectory("lightweight-symbolic-adapter-")
        MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
          preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray)) {
          new Component {
            setDefinitionName("SymbolicAdapter")
            val count: ElabInt=RtlParam.int("COUNT",defaultCount,1,17)
            val width: ElabInt=RtlParam.int("WIDTH",8,1,64)
            val mask: ElabInt=if(pipelined) RtlParam.int("MASK",0,0,31) else null
            val clk=in Bool(); val resetn=in Bool(); val enable=in Bool(); val softReset=in Bool()
            val area=new ClockingArea(ClockDomain(clk,reset=resetn,softReset=softReset,clockEnable=enable,
              config=ClockDomainConfig(resetKind=ASYNC,resetActiveLevel=LOW))) {
              val data=in(Vec(factory(width),count))
              val result=out(factory(width))
              result := BalancedTree.reduceBalancedTree(data,"SymbolicTree",mask)(operation)
            }
          }
        }
        dir
      }
      def generate() = kind match {
        case "uint" => build((w:ElabInt)=>UInt(w bits),(a:UInt,b:UInt)=>a-b)
        case "sint" => build((w:ElabInt)=>SInt(w bits),(a:SInt,b:SInt)=>a-b)
        case "bits" => build((w:ElabInt)=>Bits(w bits),(a:Bits,b:Bits)=>a^b)
      }
      val dir=generate(); val again=generate()
      val files=dir.toFile.listFiles.filter(_.getName.endsWith(".v")).map(_.getName).sorted.toSeq
      for(file<-files) assert(java.util.Arrays.equals(Files.readAllBytes(dir.resolve(file)),Files.readAllBytes(again.resolve(file))))
      val rtl=files.map(f=>new String(Files.readAllBytes(dir.resolve(f)),"UTF-8")).mkString("\n")
      assert("(?m)^module SymbolicTree\\b".r.findAllIn(rtl).size==1,rtl)
      assert(rtl.contains(".DATA_WIDTH"),rtl)
      val reference=new StringBuilder
      for(n<-Seq(1,2,3,5,7,8,15,16,17); w<-Seq(1,8,32,36,64); m<- (if(pipelined) Seq(0,1,7,31) else Seq(0))) {
        val tag=s"n${n}w${w}m$m"; val binding=if(pipelined) s",.MASK($m)" else ""
        reference.append(s"wire [${w-1}:0] result_$tag; SymbolicAdapter #(.COUNT($n),.WIDTH($w)$binding) dut_$tag(.clk(clk),.resetn(resetn),.enable(enable),.softReset(softReset),.area_data(data[${n*w-1}:0]),.area_result(result_$tag));\n")
        var words=(0 until n).map(i=>s"data[${i*w}+:$w]").toVector
        var level=0
        while(words.size>1) {
          words=words.grouped(2).zipWithIndex.map { case(pair,lane)=>
            val name=s"${tag}_l${level}_$lane"; val op=if(kind=="bits") "^" else "-"
            val value=if(pair.size==1) pair.head else s"(${pair(0)} $op ${pair(1)})"
            reference.append(s"wire [${w-1}:0] ${name}_value=$value;\n")
            if((m & (1<<level))!=0) {
              reference.append(s"reg [${w-1}:0] $name; always @(posedge clk or negedge resetn) if(!resetn) $name<=0; else if(enable) begin if(softReset) $name<=0; else $name<=${name}_value; end\n")
              name
            } else name+"_value"
          }.toVector
          level+=1
        }
        reference.append(s"""always @(negedge clk) if(result_$tag !== ${words.head}) begin $$display("mismatch $tag"); $$fatal; end
""")
      }
      Files.write(dir.resolve("tb.v"),s"""module tb;
reg clk=0; always #5 clk=~clk;
reg resetn=0,enable=1,softReset=0; reg [1087:0] data=0; integer i,j;
$reference
initial begin #2; resetn=1; #1; resetn=0; #8; resetn=1;
for(i=0;i<120;i=i+1) begin @(negedge clk); #1;
for(j=0;j<34;j=j+1) data[j*32+:32]=$$random;
enable=(i%7!=2); softReset=(i%17==0);
if(i==30) data=0; if(i==31) data=~1088'd0; if(i==32) data='bx; if(i==33) data='bz;
if(i==34) data=1088'd1; if(i==35) data=1088'd1<<1087;
if(i==60) begin resetn=0; #2; resetn=1; end end
@(negedge clk); #1; $$finish; end endmodule
""".getBytes("UTF-8"))
      val synthMask=if(pipelined) ",.MASK(31)" else ""
      Files.write(dir.resolve("synth_top.v"),s"""module SynthTop(input clk,resetn,enable,softReset,
input [611:0] data,output [35:0] result);
SymbolicAdapter #(.COUNT(17),.WIDTH(36)$synthMask) dut(.clk(clk),.resetn(resetn),.enable(enable),.softReset(softReset),.area_data(data),.area_result(result));
endmodule
""".getBytes("UTF-8"))
      for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim")++files++Seq("tb.v"),
          Seq("vvp","sim"),Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","--top-module","SymbolicAdapter")++files,
          Seq("yosys","-Q","-T","-p",s"read_verilog ${files.mkString(" ")} synth_top.v; hierarchy -check -top SynthTop; synth -top SynthTop; check -assert; select -assert-none t:$$dlatch"))) {
        val(status,log)=Increment66ToolEvidence.run(dir,command); assert(status==0,s"$dir\n$log")
      }
    }
}
