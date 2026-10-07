package morphhdl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files,Path}
import scala.collection.JavaConverters._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import morphhdl.frontend.{HdlInt,formalParam}

class PublicationDecisionTests extends AnyFunSuite {
  class Cell(actual:ElabInt, inverse:Boolean, scalarOnly:Boolean) extends Component {
    setDefinitionName("Cell", noMerge=false)
    val n=formalParam(actual,"COUNT",1,8)
    val data=in Bits((if(scalarOnly)ElabInt.literal(8) else n) bits)
    val result=out UInt(8 bits)
    result:=ElabProcess.uintLoop(n,ElabInt.literal(8)){p=>
      val test=p.bit(data,p.index)
      p.when(if(inverse) !test else test){p.assign((p.index+1).asUInt(ElabInt.literal(8)))}
    }
  }
  class Top(reverse:Boolean,scalarOnly:Boolean=false) extends Component {
    setDefinitionName("DecisionTop")
    val a=HdlInt.param("A",3,1,8).asElabInt
    val b=HdlInt.param("B",5,1,8).asElabInt
    val dataA=in Bits((if(scalarOnly)ElabInt.literal(8) else a) bits);val dataB=in Bits((if(scalarOnly)ElabInt.literal(8) else b) bits)
    val resultA,resultB,inverseA=out UInt(8 bits)
    val order=if(reverse) Seq(2,1,0) else Seq(0,1,2)
    order.foreach { i =>
      val child=new Cell(if(i==1)b else a,i==2,scalarOnly)
      child.setName("cell"+i)
      child.data:=(if(i==1)dataB else dataA)
      if(i==0)resultA:=child.result else if(i==1)resultB:=child.result else inverseA:=child.result
    }
  }
  private def files(dir:Path):Vector[(String,String)] = {
    val stream=Files.list(dir)
    try stream.iterator.asScala.filter(_.toString.endsWith(".v")).toVector.sortBy(_.getFileName.toString)
      .map(p=>p.getFileName.toString->new String(Files.readAllBytes(p),UTF_8)) finally stream.close()
  }
  private def run(dir:Path,args:Seq[String]):Unit={val r=Increment66ToolEvidence.run(dir,args);assert(r._1==0,r._2)}
  for(split<-Seq(false,true);reverse<-Seq(false,true);scalarOnly<-Seq(false,true))
  test(s"publication report explains native sharing and separates distinct scoped programs split=$split reverse=$reverse scalarOnly=$scalarOnly") {
    def generate(dir:Path, report:Boolean):Option[MorphPublicationReport]={
      val c=SpinalConfig(targetDirectory=dir.toString,headerWithDate=false,oneFilePerComponent=split)
      if(report)Some(MorphVerilog.generateWithPublicationReport(c)(new Top(reverse,scalarOnly)))
      else {MorphVerilog(c)(new Top(reverse,scalarOnly));None}
    }
    val dir=Files.createTempDirectory("decisions-")
    val report=generate(dir,true).get
    assert(report.modules.size==3,report.toJson)
    val nativeNames = "(?m)^module ([A-Za-z_][A-Za-z0-9_]*)".r.findAllMatchIn(files(dir).map(_._2).mkString("\n")).map(_.group(1)).toVector
    assert(report.modules.map(_.name).sorted==nativeNames.sorted,report.toJson)
    assert(report.modules.count(_.instances.size==2)==1,report.toJson)
    assert(report.modules.find(_.instances.size==2).get.parameters.exists(_._1=="COUNT"),report.toJson)
    assert(report.modules.find(_.name=="DecisionTop").get.parameters.forall(_._2.contains("child actual binding")),report.toJson)
    val plain=Files.createTempDirectory("decisions-plain-");generate(plain,false)
    assert(files(dir)==files(plain))
    val repeat=Files.createTempDirectory("decisions-repeat-")
    assert(report.toJson==generate(repeat,true).get.toJson)
    assert(files(dir)==files(repeat))
    Files.write(dir.resolve("report.json"),report.toJson.getBytes(UTF_8))
    run(dir,Seq("python3","-c","import json; d=json.load(open('report.json')); assert d['schemaVersion']==1; assert len(d['modules'])==3"))
    val publishedText=files(dir).map(_._2).mkString("\n")
    assert(publishedText.contains(".COUNT(A)") && publishedText.contains(".COUNT(B)"),publishedText)
    val aWidth=if(scalarOnly)8 else 3
    val bWidth=if(scalarOnly)8 else 5
    for((aCount,bCount)<-(if(scalarOnly)Seq((3,5),(2,7),(8,1)) else Seq((3,5)))) {
    val overrides=if(scalarOnly)s"#(.A($aCount),.B($bCount))" else ""
    Files.write(dir.resolve("tb.v"),s"""module tb;
reg[$aWidth-1:0]a;reg[$bWidth-1:0]b;wire[7:0]x,y,z;integer i,j,k,ex,ey,ez;
DecisionTop $overrides dut(.dataA(a),.dataB(b),.resultA(x),.resultB(y),.inverseA(z));
initial begin
for(i=0;i<${1<<aWidth};i=i+1)for(j=0;j<${1<<bWidth};j=j+1)begin a=i;b=j;ex=0;ey=0;ez=0;
for(k=0;k<$aCount;k=k+1)begin if(a[k])ex=k+1;if(!a[k])ez=k+1;end
for(k=0;k<$bCount;k=k+1)if(b[k])ey=k+1;
#1;if(x!==ex||y!==ey||z!==ez)$$fatal(1,"distinct scoped program merged");end
$$finish;end endmodule
""".getBytes(UTF_8))
    val rtl=files(dir).map(_._1).filterNot(_=="tb.v")
    run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim")++rtl++Seq("tb.v"))
    run(dir,Seq("vvp","sim"))
    }
    val rtl=files(dir).map(_._1).filterNot(_=="tb.v")
    run(dir,Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","DecisionTop")++(if(split)Seq.empty else Seq("-Wno-DECLFILENAME"))++rtl)
  }
  test("publication report capture is released after generation failure") {
    intercept[Throwable] {
      MorphVerilog.generateWithPublicationReport(SpinalConfig()) { throw new IllegalArgumentException("fixture") ; new Top(false) }
    }
    val dir=Files.createTempDirectory("decisions-after-failure-")
    assert(MorphVerilog.generateWithPublicationReport(SpinalConfig(targetDirectory=dir.toString))(new Top(false)).modules.nonEmpty)
  }
}
