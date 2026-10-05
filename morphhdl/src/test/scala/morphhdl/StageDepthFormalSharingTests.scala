package morphhdl
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files,Path}
import scala.collection.JavaConverters._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import morphhdl.frontend.{HdlInt,HdlIntRangeStart,StructuralVecOps,formalParam}

class StageDepthFormalSharingTests extends AnyFunSuite {
  private class Cell(actualWidth: ElabInt, actualDepth: ElabInt, dynamic:Boolean, difference: String = "") extends Component {
    setDefinitionName("StageDepthCell", noMerge=false)
    @dontName private val width:ElabInt=formalParam(actualWidth,"WIDTH",1,64)
    @dontName private val depth:ElabInt=formalParam(actualDepth,"DEPTH",2,if(difference=="domain") 5 else 4)
    val clk,resetn,enable=in Bool()
    val data=in Bits(width bits)
    val result=out Bits(width bits)
    val logic=new ClockingArea(ClockDomain(clk,resetn,config=ClockDomainConfig(resetKind=ASYNC,
      resetActiveLevel=if(difference=="polarity") HIGH else LOW))) {
      val stages=Vec.fill(depth)((Reg(Bits(width bits)) init(if(difference=="reset") 1 else 0))
        .addAttribute("ASYNC_REG",if(difference=="attribute") "FALSE" else "TRUE"))
      when(enable) {
        stages(0):= (if(difference=="logic") ~data else data)
        @dontName val count=HdlInt.fromElabIntParameter(depth)
        (0 until (count-1)).named("shift", "i").foreach { i => stages(i+1):=stages(i) }
      }
      if(dynamic) result:=stages(ElabValue.uintLike(depth-1,U(0,3 bits),"last"))
      else result:=ElabVec.select(stages,depth-1)
    }
  }
  private class Wrapper(actualWidth:ElabInt,actualDepth:ElabInt,levels:Int,dynamic:Boolean) extends Component {
    @dontName private val width:ElabInt=formalParam(actualWidth,"WIDTH",1,64)
    @dontName private val depth:ElabInt=formalParam(actualDepth,"DEPTH",2,4)
    val clk,resetn,enable=in Bool()
    val data=in Bits(width bits)
    val result=out Bits(width bits)
    if(levels==1) {
      val child=new Cell(width,depth,dynamic)
      child.clk:=clk;child.resetn:=resetn;child.enable:=enable;child.data:=data;result:=child.result
    } else {
      val child=new Wrapper(width,depth,levels-1,dynamic)
      child.clk:=clk;child.resetn:=resetn;child.enable:=enable;child.data:=data;result:=child.result
    }
  }
  private class Top(literal:Boolean,reverse:Boolean,levels:Int,dynamic:Boolean,difference:String="") extends Component {
    setDefinitionName("Top")
    @dontName private val w0=if(literal) ElabInt.literal(1) else HdlInt.param("W0",1,1,64).asElabInt
    @dontName private val w1=HdlInt.param("W1",5,1,64).asElabInt
    @dontName private val w2=HdlInt.param("W2",64,1,64).asElabInt
    @dontName private val d0=if(literal) ElabInt.literal(2) else HdlInt.param("D0",2,2,4).asElabInt
    @dontName private val d1=if(literal) ElabInt.literal(3) else HdlInt.param("D1",3,2,4).asElabInt
    @dontName private val d2=if(literal) ElabInt.literal(4) else HdlInt.param("D2",4,2,4).asElabInt
    val clk0,clk1,clk2,reset0,reset1,reset2,en=in Bool()
    val x0=in Bits(w0 bits);val x1=in Bits(w1 bits);val x2=in Bits(w2 bits)
    val y0=out Bits(w0 bits);val y1=out Bits(w1 bits);val y2=out Bits(w2 bits)
    val widths=Seq(w0,w1,w2);val depths=Seq(d0,d1,d2)
    val clocks=Seq(clk0,clk1,clk2);val resets=Seq(reset0,reset1,reset2)
    val inputs=Seq(x0,x1,x2);val outputs=Seq(y0,y1,y2)
    for(i <- (if(reverse) Seq(2,1,0) else Seq(0,1,2))) {
      if(levels==0) {
        val child=new Cell(widths(i),depths(i),dynamic,if(i==1) difference else "").setName(s"cell_$i")
        child.clk:=clocks(i);child.resetn:=resets(i);child.enable:=en;child.data:=inputs(i);outputs(i):=child.result
      } else {
        val child=new Wrapper(widths(i),depths(i),levels,dynamic).setName(s"wrapper_$i")
        child.clk:=clocks(i);child.resetn:=resets(i);child.enable:=en;child.data:=inputs(i);outputs(i):=child.result
      }
    }
  }
  private def sources(dir:Path):Vector[Path]={val stream=Files.list(dir);try stream.iterator.asScala.filter(_.toString.endsWith(".v")).toVector.sortBy(_.toString) finally stream.close()}
  private def run(dir:Path,command:Seq[String]):Unit={val(code,log)=Increment66ToolEvidence.run(dir,command);assert(code==0,s"$command in $dir\n$log")}
  for(literal<-Seq(false,true);reverse<-Seq(false,true);levels<-Seq(0,1);dynamic<-Seq(false,true);split<-Seq(false,true);unpacked<-Seq(false,true)) {
    test(s"register Vec depth sharing literal=$literal reverse=$reverse levels=$levels dynamic=$dynamic split=$split unpacked=$unpacked") {
      val dir=Files.createTempDirectory("stage-depth-sharing-")
      def generate(out:Path):Vector[(String,String)]={
        MorphVerilog(SpinalConfig(targetDirectory=out.toString,oneFilePerComponent=split,headerWithDate=false,flags=scala.collection.mutable.HashSet[Any](VerilogAggregateOptions(preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=if(unpacked) VerilogAggregateOptions.UnpackedArray else VerilogAggregateOptions.PackedVector))))(new Top(literal,reverse,levels,dynamic))
        sources(out).map(p=>p.getFileName.toString -> new String(Files.readAllBytes(p),UTF_8))
      }
      val files=generate(dir)
      assert(files==generate(Files.createTempDirectory("stage-depth-sharing-repeat-")))
      val text=files.map(_._2).mkString("\n")
      assert("(?m)^module StageDepthCell(?:_\\d+)?\\b".r.findAllIn(text).size==1,text)
      for(n<-0 to 2) assert(text.contains(s".DEPTH(${if(literal) (n+2).toString else "D"+n})"),text)
      assert(text.contains("ASYNC_REG"),text)
      val widthArgs=if(literal) "" else ".W0(A),"
      val depthArgs=if(literal) "" else ",.D0(P),.D1(Q),.D2(R)"
      val tb=s"""module profile #(parameter A=1,B=5,C=64,P=2,Q=3,R=4)(output reg done=0);
reg c0=0,c1=0,c2=0,r0=1,r1=1,r2=1,en=0,run0=1;
reg[A-1:0] x0=0;reg[B-1:0] x1=0;reg[C-1:0] x2=0;
wire[A-1:0] y0;wire[B-1:0] y1;wire[C-1:0] y2;
reg[A*4-1:0] q0=0;reg[B*4-1:0] q1=0;reg[C*4-1:0] q2=0;integer k;
Top #($widthArgs.W1(B),.W2(C)$depthArgs) dut(.clk0(c0),.clk1(c1),.clk2(c2),.reset0(r0),.reset1(r1),.reset2(r2),.en(en),.x0(x0),.x1(x1),.x2(x2),.y0(y0),.y1(y1),.y2(y2));
always #3 if(run0) c0=~c0;always #5 c1=~c1;always #7 c2=~c2;
always @(posedge c0 or negedge r0) begin if(!r0) q0=0;else if(en) q0={q0[A*3-1:0],x0};#1;if(y0 !== q0[(P-1)*A+:A]) $$fatal(1,"clock 0 depth");end
always @(posedge c1 or negedge r1) begin if(!r1) q1=0;else if(en) q1={q1[B*3-1:0],x1};#1;if(y1 !== q1[(Q-1)*B+:B]) $$fatal(1,"clock 1 depth");end
always @(posedge c2 or negedge r2) begin if(!r2) q2=0;else if(en) q2={q2[C*3-1:0],x2};#1;if(y2 !== q2[(R-1)*C+:C]) $$fatal(1,"clock 2 depth");end
initial begin #1;r0=0;r1=0;r2=0;#2;r0=1;r1=1;r2=1;
for(k=0;k<100;k=k+1) begin #2.5;en=(k%4)!=0;x0=$$random;x1=$$random;x2={$$random,$$random};#17.5;
if(k==40) begin run0=0;#2;r0=0;#2;if(y0!==0) $$fatal(1,"stopped clock reset");r0=1;#8;run0=1;end
end done=1;end endmodule
module tb;wire a,b;
profile normal(a);profile #(.A(${if(literal) 1 else 3}),.B(17),.C(33),.P(${if(literal) 2 else 4}),.Q(${if(literal) 3 else 2}),.R(${if(literal) 4 else 3})) overrides(b);
initial begin wait(a && b);$$finish;end endmodule
"""
      Files.write(dir.resolve("tb.v"),tb.getBytes(UTF_8))
      val names=files.map(_._1)
      run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim")++names++Seq("tb.v"))
      run(dir,Seq("timeout","15","vvp","sim"))
      run(dir,Seq("yosys","-Q","-p",s"read_verilog ${names.mkString(" ")}; synth -top Top; check -assert"))
      // Published logical depth excludes unused finite carrier lanes.
      if(split) run(dir,Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","Top")++names)
    }
  }
  for(difference<-Seq("reset","polarity","attribute","logic","domain");dynamic<-Seq(false,true)) {
    test(s"register Vec sharing preserves semantic differences $difference dynamic=$dynamic") {
      val dir=Files.createTempDirectory("stage-depth-negative-")
      MorphVerilog(SpinalConfig(targetDirectory=dir.toString,oneFilePerComponent=true,headerWithDate=false,flags=scala.collection.mutable.HashSet[Any](VerilogAggregateOptions(preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=VerilogAggregateOptions.UnpackedArray))))(new Top(false,false,0,dynamic,difference))
      val text=sources(dir).map(p=>new String(Files.readAllBytes(p),UTF_8)).mkString("\n")
      assert("(?m)^module StageDepthCell(?:_\\d+)?\\b".r.findAllIn(text).size==2,text)
    }
  }
}
