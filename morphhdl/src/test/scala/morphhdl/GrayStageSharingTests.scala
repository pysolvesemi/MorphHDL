package morphhdl
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files,Path}
import scala.collection.JavaConverters._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import morphhdl.frontend.{HdlInt,formalParam}

class GrayStageSharingTests extends AnyFunSuite {
  private class Decoder(actual:ElabInt) extends Component {
    @dontName private val width:ElabInt=formalParam(actual,"WIDTH",1,64)
    val gray=in Bits(width bits);val binary=out UInt(width bits)
    binary:=spinal.lib.fromGray(gray)
  }
  private class Top(reverse:Boolean) extends Component {
    setDefinitionName("Top")
    @dontName private val a=HdlInt.param("A",1,1,64).asElabInt
    @dontName private val b=HdlInt.param("B",64,1,64).asElabInt
    val x=in Bits(a bits);val y=in Bits(b bits);val u=out UInt(a bits);val v=out UInt(b bits)
    val pair=if(reverse){val right=new Decoder(b);val left=new Decoder(a);(left,right)}
      else{val left=new Decoder(a);val right=new Decoder(b);(left,right)}
    pair._1.gray:=x;u:=pair._1.binary;pair._2.gray:=y;v:=pair._2.binary
  }
  private def sources(dir:Path):Vector[Path]={val stream=Files.list(dir);try stream.iterator.asScala.filter(_.toString.endsWith(".v")).toVector.sortBy(_.toString) finally stream.close()}
  private def run(dir:Path,cmd:Seq[String]):Unit={val(code,log)=Increment66ToolEvidence.run(dir,cmd);assert(code==0,s"$cmd in $dir\n$log")}
  for(split<-Seq(false,true);reverse<-Seq(false,true);unpacked<-Seq(false,true)) {
    test(s"Gray stage definitions share independent width actuals split=$split reverse=$reverse unpacked=$unpacked") {
      val dir=Files.createTempDirectory("gray-stage-sharing-")
      def generate(destination:Path):Vector[(String,String)]={
        MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=destination.toString,oneFilePerComponent=split,headerWithDate=false),
          preserveConstantVecs=true,preserveConstantLoops=true,
          vecLayout=if(unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector))(new Top(reverse))
        sources(destination).map(p=>p.getFileName.toString -> new String(Files.readAllBytes(p),UTF_8))
      }
      val files=generate(dir)
      assert(files==generate(Files.createTempDirectory("gray-stage-sharing-repeat-")))
      val text=files.map(_._2).mkString("\n")
      assert("(?m)^module Decoder(?:_\\d+)?\\b".r.findAllIn(text).size==1,text)
      assert(text.contains(".WIDTH(A)") && text.contains(".WIDTH(B)"),text)
      Files.write(dir.resolve("tb.v"),"""module tb;
reg[63:0] a,b;wire u;wire[63:0] v;wire[32:0] s;wire[4:0] t;integer k;
wire x=a[0];wire[32:0] ax=a[32:0];wire[4:0] by=b[4:0];
Top defaults(.x(x),.y(b ^ (b >> 1)),.u(u),.v(v));
Top #(.A(33),.B(5)) overrides(.x(ax ^ (ax >> 1)),.y(by ^ (by >> 1)),.u(s),.v(t));
initial begin for(k=0;k<256;k=k+1) begin a={$random,$random};b={$random,$random};#1;
if(u!==x || v!==b || s!==ax || t!==by) $fatal(1,"shared Gray definition");end
$finish;end endmodule
""".getBytes(UTF_8))
      val names=files.map(_._1)
      run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim")++names++Seq("tb.v"))
      run(dir,Seq("timeout","15","vvp","sim"))
      run(dir,Seq("yosys","-Q","-p",s"read_verilog ${names.mkString(" ")}; synth -top Top; check -assert; scc -expect 0"))
      if(split) {
        Files.write(dir.resolve("prefix.vlt"),"`verilator_config\nsplit_var -module \"Decoder\" -var \"decode_stage\"\n".getBytes(UTF_8))
        run(dir,Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","Top","-GA=5","prefix.vlt")++names)
      }
    }
  }
}
