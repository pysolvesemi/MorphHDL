package morphhdl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import spinal.core._
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite

class ScopedProcessTests extends AnyFunSuite {
  class Operations(symbolic: Boolean, n0: Int = 3, w0: Int = 5) extends Component {
    setDefinitionName("ScopedOperations")
    val n = if (symbolic) HdlInt.param("COUNT", n0, 1, 32).asElabInt else ElabInt.literal(n0)
    val w = if (symbolic) HdlInt.param("ELEMENT_BITS", w0, 1, 8).asElabInt else ElabInt.literal(w0)
    val keep = in Bits(n bits)
    val take = in UInt(8 bits)
    val data = in Bits((n * w) bits)
    val salt = in Bits(w bits)
    val enable, invert = in Bool()
    val count = out UInt(8 bits)
    val word = out Bits((n * w) bits)
    count := ElabProcess.uintLoop(n, ElabInt.literal(8)) { p =>
      p.when(p.bool(enable) && p.bit(keep, p.index)) {
        p.assign((p.index + 1).asUInt(ElabInt.literal(8)))
      }
    }
    word := ElabProcess.bitsLoop(n, n * w) { p =>
      p.when(p.index.asUInt(ElabInt.literal(8)) < p.uint(take)) {
        p.when(p.bool(enable)) {
          p.ifElse(p.bool(invert)) {
            p.assignSlice(p.index, w, p.slice(data, p.index, w) ^ p.bits(salt))
          } {
            p.assignSlice(p.index, w, p.slice(data, p.index, w))
          }
        }
      }
    }
  }
  private def run(dir: Path, args: Seq[String]): Unit = {
    val r = Increment66ToolEvidence.run(dir, args); assert(r._1 == 0, args.mkString(" ") + "\n" + r._2)
  }
  private def simulate(dir: Path, rtl: String, n: Int, w: Int, symbolic: Boolean): Unit = {
    val overrideText = if (symbolic) s"#(.COUNT($n),.ELEMENT_BITS($w))" else ""
    Files.write(dir.resolve("tb.v"), s"""module tb;
reg [$n-1:0] keep; reg [7:0] take; reg [${n*w}-1:0] data; reg [$w-1:0] salt;
reg enable,invert; wire [7:0] count; wire [${n*w}-1:0] word;
reg [7:0] expected;reg [${n*w}-1:0] expectedWord; integer t,k,b;
ScopedOperations $overrideText dut(keep,take,data,salt,enable,invert,count,word);
task check;begin
 expected=0;expectedWord=0;
 for(b=0;b<$n;b=b+1)begin
   if(enable && keep[b])expected=b+1;
   if(b<take)if(enable)begin
     if(invert)expectedWord[b*$w+:$w]=data[b*$w+:$w]^salt;
     else expectedWord[b*$w+:$w]=data[b*$w+:$w];
   end
 end
 #1;if(count!==expected || word!==expectedWord) $$fatal(1,"scoped mismatch take=%d",take);
end endtask
initial begin
 for(t=0;t<256;t=t+1)for(k=0;k<8;k=k+1)begin
  take=t;keep=$$random;data={8{$$random}};salt=$$random;enable=k[0];invert=k[1];check;
 end
 enable=1;invert=0;take=255;keep=0;check;keep=~0;check;
 for(k=0;k<$n;k=k+1)begin keep=1<<k;check;end
 keep={$n{1'bx}};check;keep={$n{1'bz}};check;
 keep=~0;take=8'bx;check;take=8'bz;check;take=255;
 enable=1'bx;check;enable=1'bz;check;enable=1;invert=1'bx;check;invert=1'bz;check;
 $$display("SCOPED_PASS");$$finish;
end
endmodule
""".getBytes(UTF_8))
    run(dir, Seq("iverilog", "-g2001", "-s", "tb", "-o", "sim", rtl, "tb.v"))
    run(dir, Seq("timeout", "20", "vvp", "sim"))
  }
  for (symbolic <- Seq(false,true); unpacked <- Seq(false,true); split <- Seq(false,true))
  test(s"scoped process boundary matrix symbolic=$symbolic unpacked=$unpacked split=$split") {
    def generate(dir: Path): MorphPublicationReport = {
      val config = MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false,oneFilePerComponent=split),
        preserveConstantVecs=true,preserveConstantLoops=true,
        vecLayout=if(unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector)
      MorphVerilog.generateWithPublicationReport(config)(new Operations(symbolic))
    }
    val dir = Files.createTempDirectory("scoped-matrix-")
    val report = generate(dir)
    val text = new String(Files.readAllBytes(dir.resolve("ScopedOperations.v")),UTF_8)
    val repeated = Files.createTempDirectory("scoped-repeat-")
    assert(report.toJson == generate(repeated).toJson)
    assert(text == new String(Files.readAllBytes(repeated.resolve("ScopedOperations.v")),UTF_8))
    assert("for \\(".r.findAllIn(text).length == 2,text)
    assert(report.modules.head.loops.count(_.startsWith("scoped procedural")) == 2)
    run(dir,Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","ScopedOperations","ScopedOperations.v"))
    for((n,w) <- (if(symbolic) Seq((1,1),(3,5),(32,8)) else Seq((3,5)))) {
      simulate(dir,"ScopedOperations.v",n,w,symbolic)
      val parameters = if(symbolic) s"chparam -set COUNT $n -set ELEMENT_BITS $w ScopedOperations; " else ""
      run(dir,Seq("yosys","-Q","-p",s"read_verilog -DSYNTHESIS ScopedOperations.v; $parameters proc; select -assert-none t:$$dlatch; synth -top ScopedOperations; check -assert"))
    }
  }
  test("ordinary generation executes all scoped iterations") {
    val dir=Files.createTempDirectory("scoped-ordinary-")
    SpinalVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false))(new Operations(false))
    simulate(dir,"ScopedOperations.v",3,5,false)
  }
  for((n,bits)<-Seq((1,1),(32,6),(32,32)))
  test(s"scoped index sizing and unsigned comparisons count=$n width=$bits") {
    val dir=Files.createTempDirectory("scoped-unsigned-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
      new Component {
        setDefinitionName("UnsignedLoop")
        val take=in UInt(32 bits)
        val result=out UInt(bits bits)
        result:=ElabProcess.uintLoop(ElabInt.literal(n),ElabInt.literal(bits)){p=>
          p.when(p.index.asUInt(ElabInt.literal(32)) < p.uint(take)) {
            p.assign((p.index+1).asUInt(ElabInt.literal(bits)))
          }
        }
      }
    }
    Files.write(dir.resolve("tb.v"),s"""module tb;
reg[31:0]take;wire[$bits-1:0]result;UnsignedLoop dut(take,result);
initial begin take=32'h80000000;#1;if(result!==$n) $$fatal;
take=32'hffffffff;#1;if(result!==$n) $$fatal;take=0;#1;if(result!==0) $$fatal;
take=32'bx;#1;if(result!==0) $$fatal;take=32'bz;#1;if(result!==0) $$fatal;$$finish;end endmodule
""".getBytes(UTF_8))
    run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim","UnsignedLoop.v","tb.v"))
    run(dir,Seq("vvp","sim"))
    run(dir,Seq("verilator","--lint-only","-Wall","--language","1364-2001","--top-module","UnsignedLoop","UnsignedLoop.v"))
    run(dir,Seq("yosys","-Q","-p","read_verilog UnsignedLoop.v; proc; select -assert-none t:$dlatch; synth -top UnsignedLoop; check -assert"))
  }
  test("scoped loops are equivalent to ordinary unrolled control") {
    val dir=Files.createTempDirectory("scoped-equivalence-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false))(new Operations(false))
    SpinalVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
      val oracle=new Operations(false);oracle.setDefinitionName("ScopedOracle");oracle
    }
    run(dir,Seq("yosys","-Q","-p","read_verilog -DSYNTHESIS ScopedOperations.v ScopedOracle.v; proc; opt; equiv_make ScopedOracle ScopedOperations equiv; hierarchy -top equiv; equiv_simple; equiv_status -assert"))
  }
  test("scoped input identity survives aliases and forward assignment") {
    val dir=Files.createTempDirectory("scoped-forward-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
      new Component {
        setDefinitionName("ForwardLoop")
        val data=in Bits(3 bits)
        val late=Bits(ElabInt.literal(3) bits)
        val alias=Bits(ElabInt.literal(3) bits);alias:=late
        val result=out UInt(2 bits)
        result:=ElabProcess.uintLoop(ElabInt.literal(3),ElabInt.literal(2)){p=>
          p.when(p.bit(alias,p.index)){p.assign((p.index+1).asUInt(ElabInt.literal(2)))}
        }
        late:=data
      }
    }
    Files.write(dir.resolve("tb.v"),"""module tb;
reg[2:0]data;wire[1:0]result;integer k,b,expected;ForwardLoop dut(data,result);
initial begin for(k=0;k<8;k=k+1)begin data=k;expected=0;
for(b=0;b<3;b=b+1)if(data[b])expected=b+1;
#1;if(result!==expected)$fatal;end $finish;end endmodule
""".getBytes(UTF_8))
    run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim","ForwardLoop.v","tb.v"))
    run(dir,Seq("vvp","sim"))
    run(dir,Seq("verilator","--lint-only","-Wall","--top-module","ForwardLoop","ForwardLoop.v"))
  }
  test("zero and boundary count overrides retain the zero default") {
    val dir=Files.createTempDirectory("scoped-zero-count-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
      new Component {
        setDefinitionName("ZeroLoop")
        val n=HdlInt.param("COUNT",3,0,32).asElabInt
        val data=in Bits(32 bits);val result=out UInt(6 bits)
        result:=ElabProcess.uintLoop(n,ElabInt.literal(6)){p=>
          p.when(p.bit(data,p.index)){p.assign((p.index+1).asUInt(ElabInt.literal(6)))}
        }
      }
    }
    for(n<-Seq(0,1,3,32)) {
      Files.write(dir.resolve("tb.v"),s"""module tb;
reg[31:0]data;wire[5:0]result;ZeroLoop #(.COUNT($n)) dut(data,result);
initial begin data=~0;#1;if(result!==$n) $$fatal;data=0;#1;if(result!==0) $$fatal;$$finish;end endmodule
""".getBytes(UTF_8))
      run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim","ZeroLoop.v","tb.v"))
      run(dir,Seq("vvp","sim"))
      run(dir,Seq("yosys","-Q","-p",s"read_verilog ZeroLoop.v; chparam -set COUNT $n ZeroLoop; proc; select -assert-none t:$$dlatch; synth -top ZeroLoop; check -assert"))
    }
    run(dir,Seq("verilator","--lint-only","-Wall","--top-module","ZeroLoop","ZeroLoop.v"))
  }
  test("scoped comparisons boolean predicates and bitwise values preserve ordered four-state behavior") {
    val dir=Files.createTempDirectory("scoped-operators-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
      new Component {
        setDefinitionName("OperatorLoop")
        val take=in UInt(8 bits);val skip=in UInt(32 bits);val enable=in Bool()
        val data,mask,salt=in Bits(8 bits);val result=out Bits(8 bits)
        result:=ElabProcess.bitsLoop(ElabInt.literal(4),ElabInt.literal(8)){p=>
          val i=p.index.asUInt(ElabInt.literal(8))
          p.ifElse(((i <= p.uint(take)) && (i =/= p.uint(skip))) || ((i === p.uint(take)) && p.bool(enable))) {
            p.assign((p.bits(data) & p.bits(mask)) | p.bits(salt))
          } {
            p.when(!p.bool(enable)){p.assign(p.bits(data) ^ p.bits(mask))}
          }
        }
      }
    }
    Files.write(dir.resolve("tb.v"),"""module tb;
reg[7:0]take,data,mask,salt;reg[31:0]skip;reg enable;wire[7:0]result;reg[7:0]expected;integer t,s,e,b;
OperatorLoop dut(.take(take),.skip(skip),.enable(enable),.data(data),.mask(mask),.salt(salt),.result(result));
task check;begin expected=0;
for(b=0;b<4;b=b+1)begin
if(((b[7:0]<=take)&&(b[7:0]!=skip))||((b[7:0]==take)&&enable))expected=(data&mask)|salt;
else if(!enable)expected=data^mask;
end #1;if(result!==expected)$fatal(1,"operator semantics");end endtask
initial begin
for(t=0;t<6;t=t+1)for(s=0;s<6;s=s+1)for(e=0;e<2;e=e+1)begin
 take=t;skip=s;enable=e;data=$random;mask=$random;salt=$random;check;end
skip=32'hffffffff;check;take=8'bx;check;take=8'bz;check;take=3;skip=32'bx;check;
skip=32'bz;check;enable=1'bx;check;enable=1'bz;check;$finish;end endmodule
""".getBytes(UTF_8))
    run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim","OperatorLoop.v","tb.v"))
    run(dir,Seq("vvp","sim"))
    run(dir,Seq("verilator","--lint-only","-Wall","--language","1364-2001","--top-module","OperatorLoop","OperatorLoop.v"))
    run(dir,Seq("yosys","-Q","-p","read_verilog OperatorLoop.v; proc; select -assert-none t:$dlatch; synth -top OperatorLoop; check -assert"))
  }
  test("independent source width parameters stay live with a constant subrange count") {
    val dir=Files.createTempDirectory("scoped-source-width-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
      new Component {
        setDefinitionName("SourceWidthLoop")
        val w=HdlInt.param("WIDTH",8,4,16).asElabInt
        val data=in Bits(w bits);val result=out UInt(2 bits)
        result:=ElabProcess.uintLoop(ElabInt.literal(3),ElabInt.literal(2)){p=>
          p.when(p.bit(data,p.index)){p.assign((p.index+1).asUInt(ElabInt.literal(2)))}
        }
      }
    }
    for(w<-Seq(4,8,16)) {
      Files.write(dir.resolve("tb.v"),s"""module tb;
reg[$w-1:0]data;wire[1:0]result;integer k,b,expected;SourceWidthLoop #(.WIDTH($w)) dut(data,result);
initial begin for(k=0;k<256;k=k+1)begin data=$$random;expected=0;
for(b=0;b<3;b=b+1)if(data[b])expected=b+1;
#1;if(result!==expected) $$fatal;end $$finish;end endmodule
""".getBytes(UTF_8))
      run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim","SourceWidthLoop.v","tb.v"))
      run(dir,Seq("vvp","sim"))
    }
    run(dir,Seq("verilator","--lint-only","-Wall","--top-module","SourceWidthLoop","SourceWidthLoop.v"))
  }
  for(mode <- Seq("escape","foreign-handle","width","bounds","side-effect","feedback","extra-write","negative-count","zero-width","wide-index","unused-input","empty-body"))
  test(s"scoped process rejects unsafe $mode") {
    val dir=Files.createTempDirectory("scoped-negative-")
    val generated=MorphVerilog.tryGenerate(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
      new Component {
        val n=HdlInt.param("N",3,1,4).asElabInt
        val data=in Bits(n bits)
        val source=Bits(n bits)
        if(mode!="feedback")source:=data
        val flag=in Bool()
        val result=out UInt(8 bits)
        var escaped: ElabScopedProcess.Index=null
        val first=ElabProcess.uintLoop(n,ElabInt.literal(8)) { p =>
          escaped=p.index
          p.when(p.bit(source,p.index)){p.assign((p.index+1).asUInt(ElabInt.literal(8)))}
        }
        mode match {
          case "escape" => escaped.asUInt(ElabInt.literal(8)); result:=first
          case "feedback" => source:=first.asBits.resized; result:=first
          case "extra-write" => first:=0;result:=first
          case _ => result:=ElabProcess.uintLoop(if(mode=="negative-count") ElabInt.literal(-1) else n,
            if(mode=="zero-width") ElabInt.literal(0) else ElabInt.literal(8)) { p =>
            mode match {
              case "foreign-handle" => p.bit(source,escaped)
              case "unused-input" => p.bool(flag);p.assign(p.index.asUInt(ElabInt.literal(8)))
              case "empty-body" => p.when(p.bool(flag)){}
              case "wide-index" => p.index.asUInt(ElabInt.literal(33))
              case "negative-count" | "zero-width" => ()
              case "width" => p.assign(p.index.asUInt(ElabInt.literal(1)))
              case "bounds" => p.bit(source,p.index+1)
              case "side-effect" => val hidden=Bool();hidden:=flag
            }
            if(mode!="unused-input" && mode!="empty-body")
              p.when(p.bool(flag)){p.assign(p.index.asUInt(ElabInt.literal(8)))}
          }
        }
      }
    }
    assert(generated.isLeft,generated.toString)
    val detail=generated.left.get.detail
    assert(detail.contains("SPINAL-SCOPED-PROCESS") || (mode=="extra-write" && detail.contains("ASSIGNMENT OVERLAP")) || (mode=="feedback" && detail.contains("COMBINATORIAL LOOP") || detail.contains("COMBINATIONAL LOOP")),detail)
  }
}
