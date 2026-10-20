package morphhdl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files,Path}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import morphhdl.frontend.HdlInt

class ScopedProcessExtensionTests extends AnyFunSuite {
  private val eight=ElabInt.literal(8)
  private def run(dir:Path,args:Seq[String]):Unit={val r=Increment66ToolEvidence.run(dir,args);assert(r._1==0,args.mkString(" ")+"\n"+r._2)}
  class Fold extends Component {
    setDefinitionName("ExtendedLoop")
    val n=HdlInt.param("COUNT",3,1,8).asElabInt
    val data=in Bits(n*eight bits)
    val enable=in Bool()
    val salt=in UInt(8 bits)
    val signedLimit=in SInt(5 bits)
    val sum,product,difference=out Bits(8 bits)
    val minimum=out SInt(8 bits)
    val copied=out Bits(n*eight bits)
    val outputs=ElabProcess.outputsLoop(n,Seq(
      ElabScopedProcess.Output(eight,5),ElabScopedProcess.Output(eight,1),
      ElabScopedProcess.Output(eight,127,true),ElabScopedProcess.Output(n*eight),ElabScopedProcess.Output(eight,255))) { p =>
      val outer=p.index
      val byte=p.slice(data,outer,eight)
      p.when(p.bool(enable)) {
        p.foreach(ElabInt.literal(2)) { inner =>
          p.output(0).assign(p.output(0).current + byte + inner.asUInt(eight))
        }
        p.output(1).assign(p.output(1).current * p.uint(salt))
        p.when(byte.asSigned < p.output(2).current) { p.output(2).assign(byte) }
        p.when(byte.asSigned >= p.sint(signedLimit)) { p.output(3).assignSlice(outer,eight,byte) }
        p.output(4).assign(p.output(4).current - byte)
      }
    }
    sum:=outputs(0);product:=outputs(1);minimum:=outputs(2).asSInt;copied:=outputs(3);difference:=outputs(4)
  }
  private def simulate(dir:Path,n:Int,native:Boolean):Unit={
    val parameter=if(native)s"#(.COUNT($n))" else ""
    Files.write(dir.resolve("tb.v"),s"""module tb;
reg [${n*8-1}:0] data;reg enable;reg[7:0] salt;reg signed[4:0] signedLimit;
wire[7:0] sum,product,difference;wire signed[7:0] minimum;wire[${n*8-1}:0] copied;
reg[7:0] s,p,d;reg signed[7:0] m,v;reg[${n*8-1}:0] c;integer t,i,j;
ExtendedLoop $parameter dut(data,enable,salt,signedLimit,sum,product,difference,minimum,copied);
task check;begin
s=5;p=1;m=127;c=0;d=255;
for(i=0;i<$n;i=i+1)if(enable)begin
 v=data[i*8+:8];for(j=0;j<2;j=j+1)s=s+v+j;
 p=p*salt;if(v<m)m=v;if(v>=signedLimit)c[i*8+:8]=v;d=d-v;
end
#1;if(sum!==s || product!==p || minimum!==m || copied!==c || difference!==d) $$fatal(1,"fold mismatch");
end endtask
initial begin
for(t=0;t<512;t=t+1)begin data={4{$$random}};salt=$$random;enable=t[0];signedLimit=$$random;check;end
 data=~0;salt=255;enable=1;signedLimit=-1;check;
 data=0;check;data={${n}{8'h80}};check;
 enable=1'bx;check;enable=1'bz;check;enable=1;signedLimit=5'bx;check;signedLimit=5'bz;check;
 $$finish;end
endmodule
""".getBytes(UTF_8));run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim","ExtendedLoop.v","tb.v"));run(dir,Seq("vvp","sim"))
  }
  for(native<-Seq(false,true);split<-Seq(false,true))test(s"multiple outputs defaults nested folds signed arithmetic native=$native split=$split"){
    val dir=Files.createTempDirectory("scoped-extension-")
    val config=SpinalConfig(targetDirectory=dir.toString,headerWithDate=false,oneFilePerComponent=split)
    if(native)MorphVerilog(config)(new Fold) else SpinalVerilog(config)(new Fold)
    val text=new String(Files.readAllBytes(dir.resolve("ExtendedLoop.v")),UTF_8)
    if(native){assert("for \\(".r.findAllIn(text).length==2,text);assert("always @".r.findAllIn(text).length==1,text)}
    run(dir,Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","ExtendedLoop","ExtendedLoop.v"))
    for(n<-if(native)Seq(1,3,8)else Seq(3)){
      simulate(dir,n,native)
      run(dir,Seq("yosys","-Q","-p",s"read_verilog -DSYNTHESIS ExtendedLoop.v; ${if(native)s"chparam -set COUNT $n ExtendedLoop;"else ""} proc; select -assert-none t:$$dlatch; synth -top ExtendedLoop; check -assert"))
    }
  }
  for(bad<-Seq(false,true))test(s"correlated bounds compare exact roots bad=$bad"){
    val dir=Files.createTempDirectory("scoped-correlated-")
    def generate():Unit={MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)){
      new Component {
        setDefinitionName("CorrelatedLoop")
        val w=HdlInt.param("WIDTH",4,2,16).asElabInt
        val n=if(bad)HdlInt.param("OTHER",3,1,15).asElabInt else w-1
        val data=in Bits(w bits);val result=out UInt(8 bits)
        result:=ElabProcess.uintLoop(n,eight){p=>p.when(p.bit(data,p.index+1)){p.assign(p.index.asUInt(eight))}}
      }
    };()}
    if(bad){val error=intercept[Exception](generate());assert(error.toString.contains("SPINAL-SCOPED-PROCESS"),error.toString)}
    else {generate();run(dir,Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","CorrelatedLoop","CorrelatedLoop.v"))}
  }
  class MatrixFold extends Component {
    setDefinitionName("MatrixFold")
    val rows=HdlInt.param("ROWS",2,1,3).asElabInt
    val cols=HdlInt.param("COLS",3,1,4).asElabInt
    val data=in Bits(rows*cols bits)
    val total=out UInt(16 bits);val copied=out Bits(rows*cols bits)
    val fields=ElabProcess.outputsLoop(rows,Seq(ElabScopedProcess.Output(ElabInt.literal(16),9),ElabScopedProcess.Output(rows*cols))) { p =>
      val row=p.index
      p.foreach(cols) { col =>
        val at=row*cols+col
        p.when(p.bit(data,at)) {
          p.output(0).assign(p.output(0).current + at.asUInt(eight).extend(ElabInt.literal(16)))
          p.output(1).assignSlice(at,ElabInt.literal(1),p.literal(1,ElabInt.literal(1)))
        }
      }
    }
    total:=fields(0).asUInt;copied:=fields(1)
  }
  for(native<-Seq(false,true))test(s"nested symbolic affine indices and extension native=$native"){
    val dir=Files.createTempDirectory("scoped-matrix-fold-")
    val config=SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)
    if(native)MorphVerilog(config)(new MatrixFold)else SpinalVerilog(config)(new MatrixFold)
    run(dir,Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","MatrixFold","MatrixFold.v"))
    for((rows,cols)<-if(native)Seq((1,1),(2,3),(3,4))else Seq((2,3))){
      val n=rows*cols;val params=if(native)s"#(.ROWS($rows),.COLS($cols))"else ""
      Files.write(dir.resolve("tb.v"),s"""module tb;
reg[$n-1:0] data;wire[15:0] total;wire[$n-1:0] copied;integer k,b;reg[15:0] expected;reg[$n-1:0] cp;
MatrixFold $params dut(data,total,copied);
task check;begin expected=9;cp=0;for(b=0;b<$n;b=b+1)if(data[b])begin expected=expected+b;cp[b]=1;end
#1;if(total!==expected || copied!==cp)$$fatal(1,"matrix fold mismatch");end endtask
initial begin for(k=0;k<(1<<$n);k=k+1)begin data=k;check;end data='bx;check;data='bz;check;$$finish;end
endmodule""".getBytes(UTF_8))
      run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim","MatrixFold.v","tb.v"));run(dir,Seq("vvp","sim"))
      run(dir,Seq("yosys","-Q","-p",s"read_verilog -DSYNTHESIS MatrixFold.v; ${if(native)s"chparam -set ROWS $rows -set COLS $cols MatrixFold;"else ""} proc; select -assert-none t:$$dlatch; synth -top MatrixFold; check -assert"))
    }
  }
  test("zero outer and inner iteration counts preserve explicit signed defaults"){
    val dir=Files.createTempDirectory("scoped-zero-fold-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)){
      new Component {
        setDefinitionName("ZeroFold")
        val n=HdlInt.param("COUNT",1,0,3).asElabInt
        val m=HdlInt.param("INNER",1,0,2).asElabInt
        val data=in SInt(8 bits);val result=out SInt(16 bits)
        val r=ElabProcess.outputsLoop(n,Seq(ElabScopedProcess.Output(ElabInt.literal(16),-7,true))){p=>
          p.foreach(m){_=>p.output(0).assign(p.current+p.sint(data).extend(ElabInt.literal(16)))}
        };result:=r.head.asSInt
      }
    }
    run(dir,Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","ZeroFold","ZeroFold.v"))
    for(n<-0 to 3;m<-0 to 2){
      Files.write(dir.resolve("tb.v"),s"""module tb;reg signed[7:0] data;wire signed[15:0] result;integer k;reg signed[15:0] expected;
ZeroFold #(.COUNT($n),.INNER($m)) dut(data,result);
initial begin for(k=-128;k<128;k=k+1)begin data=k;expected=-7+$n*$m*k;#1;if(result!==expected)$$fatal(1,"zero/signed fold");end $$finish;end endmodule""".getBytes(UTF_8))
      run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim","ZeroFold.v","tb.v"));run(dir,Seq("vvp","sim"))
    }
  }
  for(mode<-Seq("escaped-index","escaped-predicate","signedness","arithmetic-width","narrow-extension","default-overflow","signed-default-overflow","negative-inner","affine-oob"))
  test(s"extended scoped process rejects unsafe $mode"){
    val dir=Files.createTempDirectory("scoped-unsafe-extension-")
    val error=intercept[Exception]{MorphVerilog(SpinalConfig(targetDirectory=dir.toString)){
      new Component {
        val n=HdlInt.param("COUNT",2,1,4).asElabInt
        val data=in Bits(8 bits);val signedData=in SInt(8 bits);val result=out Bits(8 bits)
        val default=if(mode=="default-overflow")BigInt(256) else if(mode=="signed-default-overflow")BigInt(128) else BigInt(0)
        val fields=ElabProcess.outputsLoop(n,Seq(ElabScopedProcess.Output(eight,default,mode=="signed-default-overflow"))){p=>
          mode match {
            case "escaped-index" => var v:ElabScopedProcess.Value=null;p.foreach(ElabInt.literal(2)){i=>v=i.asUInt(eight)};p.assign(v)
            case "escaped-predicate" => var v:ElabScopedProcess.Predicate=null;p.foreach(ElabInt.literal(2)){i=>v=p.bit(data,i)};p.when(v){p.assign(p.bits(data))}
            case "signedness" => p.when(p.bits(data)<p.sint(signedData)){p.assign(p.bits(data))}
            case "arithmetic-width" => p.assign(p.bits(data)+p.literal(1,ElabInt.literal(4)))
            case "narrow-extension" => p.assign(p.bits(data).extend(ElabInt.literal(4)))
            case "negative-inner" => p.foreach(ElabInt.literal(-1)){_=>p.assign(p.bits(data))}
            case "affine-oob" => val outer=p.index;p.foreach(ElabInt.literal(4)){j=>p.when(p.bit(data,outer*4+j)){p.assign(p.bits(data))}}
            case _=>p.assign(p.bits(data))
          }
        };result:=fields.head
      }
    }}
    assert(error.toString.contains("SPINAL-SCOPED-PROCESS"),error.toString)
  }
  for(native<-Seq(false,true))test(s"hardware defaults seed ordered arithmetic native=$native"){
    val dir=Files.createTempDirectory("scoped-seed-")
    class Seed extends Component {
      setDefinitionName("Seed")
      val n=HdlInt.param("COUNT",2,0,4).asElabInt
      val seed,data=in UInt(8 bits);val enable=in Bool();val result=out Bits(8 bits)
      result:=ElabProcess.outputsLoop(n,Seq(ElabScopedProcess.Output.from(seed))){p=>
        p.when(p.bool(enable)){p.assign(p.current+p.uint(data))}
      }.head
    }
    val config=SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)
    if(native)MorphVerilog(config)(new Seed) else SpinalVerilog(config)(new Seed)
    run(dir,Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","Seed","Seed.v"))
    for(n<-if(native)Seq(0,1,4)else Seq(2)){
      val params=if(native)s"#(.COUNT($n))"else ""
      Files.write(dir.resolve("tb.v"),s"""module tb;reg[7:0] seed,data;reg enable;wire[7:0] result;integer t;reg[7:0] expected;
Seed $params dut(seed,data,enable,result);
task check;begin expected=seed;if(enable)expected=seed+$n*data;#1;if(result!==expected)$$fatal(1,"seed");end endtask
initial begin for(t=0;t<1024;t=t+1)begin seed=$$random;data=$$random;enable=t[0];check;end enable=1'bx;check;enable=1'bz;check;$$finish;end endmodule""".getBytes(UTF_8))
      run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim","Seed.v","tb.v"));run(dir,Seq("vvp","sim"))
    }
  }
  test("extended process matches an independent combinational oracle"){
    val dir=Files.createTempDirectory("scoped-fold-equivalence-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false))(new Fold)
    Files.write(dir.resolve("Oracle.v"),"""module Oracle(
input [23:0] data,input enable,input[7:0] salt,input signed[4:0] signedLimit,
output reg[7:0] sum,product,difference,output reg signed[7:0] minimum,output reg[23:0] copied);
integer i,j;reg signed[7:0] v;
always @* begin
sum=5;product=1;difference=255;minimum=127;copied=0;v=0;j=0;
for(i=0;i<3;i=i+1)if(enable)begin
v=data[i*8+:8];for(j=0;j<2;j=j+1)sum=sum+v+j;
product=product*salt;difference=difference-v;
if(v<minimum)minimum=v;if(v>=signedLimit)copied[i*8+:8]=v;
end end endmodule
""".getBytes(UTF_8))
    run(dir,Seq("timeout","60","yosys","-Q","-p","read_verilog -DSYNTHESIS ExtendedLoop.v Oracle.v; proc; opt; equiv_make Oracle ExtendedLoop equiv; hierarchy -top equiv; equiv_simple; equiv_status -assert"))
  }
  for(reverse<-Seq(false,true);split<-Seq(false,true))test(s"nested scalar loop bounds and affine scale share definitions reverse=$reverse split=$split"){
    import morphhdl.frontend.formalParam
    class Child(actualCount:ElabInt,actualStep:ElabInt) extends Component {
      setDefinitionName("NestedChild",noMerge=false)
      val n=formalParam(actualCount,"INNER",0,4)
      val step=formalParam(actualStep,"STEP",1,3)
      val data=in UInt(8 bits);val result=out Bits(16 bits)
      result:=ElabProcess.outputsLoop(ElabInt.literal(2),Seq(ElabScopedProcess.Output(ElabInt.literal(16),7))){p=>
        p.foreach(n){i=>p.assign(p.current+p.uint(data).extend(ElabInt.literal(16))+(i*step).asUInt(ElabInt.literal(16)))}
      }.head
    }
    class Parent extends Component {
      setDefinitionName("NestedParent")
      val a=HdlInt.param("A",2,0,4).asElabInt;val b=HdlInt.param("B",3,0,4).asElabInt
      val step=HdlInt.param("STEP",2,1,3).asElabInt
      val data=in UInt(8 bits);val x,y=out Bits(16 bits)
      (if(reverse)Seq(1,0)else Seq(0,1)).foreach { number=>
        val child=new Child(if(number==0)a else b,if(number==0)ElabInt.literal(1)else step)
        child.setName("child"+number);child.data:=data
        if(number==0)x:=child.result else y:=child.result
      }
    }
    def generate(dir:Path):MorphPublicationReport=MorphVerilog.generateWithPublicationReport(
      SpinalConfig(targetDirectory=dir.toString,headerWithDate=false,oneFilePerComponent=split))(new Parent)
    val dir=Files.createTempDirectory("scoped-nested-sharing-");val report=generate(dir)
    assert(report.modules.count(_.instances.size==2)==1,report.toJson)
    assert(report.modules.find(_.instances.size==2).get.loops.exists(_.startsWith("nested scoped")),report.toJson)
    val again=Files.createTempDirectory("scoped-nested-repeat-");assert(report.toJson==generate(again).toJson)
    val files=report.generated.generatedSourcesPaths.map(p=>java.nio.file.Paths.get(p).getFileName.toString)
    run(dir,Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","NestedParent")++(if(split)Seq.empty else Seq("-Wno-DECLFILENAME"))++files)
    for((a,b,step)<-Seq((2,3,2),(0,4,3),(4,1,1))){
      Files.write(dir.resolve("tb.v"),s"""module tb;reg[7:0] data;wire[15:0] x,y;integer k,i;reg[15:0] ex,ey;
NestedParent #(.A($a),.B($b),.STEP($step)) dut(data,x,y);
initial begin for(k=0;k<256;k=k+1)begin data=k;ex=7;ey=7;
for(i=0;i<$a;i=i+1)ex=ex+2*(k+i);for(i=0;i<$b;i=i+1)ey=ey+2*(k+i*$step);
#1;if(x!==ex || y!==ey)$$fatal(1,"nested binding");end $$finish;end endmodule""".getBytes(UTF_8))
      run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim")++files++Seq("tb.v"));run(dir,Seq("vvp","sim"))
    }
  }
  for(signed<-Seq(false,true))test(s"one-bit nonzero default preserves retained loop signed=$signed"){
    val dir=Files.createTempDirectory("scoped-one-bit-default-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)){
      new Component {
        setDefinitionName("OneBitDefault")
        val n=HdlInt.param("COUNT",1,0,3).asElabInt
        val enable=in Bool();val result=out Bits(1 bits)
        result:=ElabProcess.outputsLoop(n,Seq(ElabScopedProcess.Output(ElabInt.literal(1),if(signed)-1 else 1,signed))){p=>
          p.when(p.bool(enable)){p.assign(p.literal(0,ElabInt.literal(1)))}
        }.head
      }
    }
    run(dir,Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","OneBitDefault","OneBitDefault.v"))
    for(n<-0 to 3){
      Files.write(dir.resolve("tb.v"),s"""module tb;reg enable;wire result;
OneBitDefault #(.COUNT($n)) dut(enable,result);
initial begin enable=0;#1;if(result!==1)$$fatal;enable=1;#1;if(result!==($n==0))$$fatal;enable=1'bx;#1;if(result!==1)$$fatal;enable=1'bz;#1;if(result!==1)$$fatal;$$finish;end endmodule""".getBytes(UTF_8))
      run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim","OneBitDefault.v","tb.v"));run(dir,Seq("vvp","sim"))
    }
  }
}
