package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

@_root_.spinal.core.rtlRecursive
final class CompactOptionalForward(countActual:Param[Int], capacityActual:Option[Param[Int]], mutate:Boolean=false) extends Component {
  setDefinitionName("CompactOptionalForward")
  val COUNT:Param[Int]=Param(countActual)
  val CAPACITY:Param[Int]=Param.optional(capacityActual,0)
  require(COUNT>=1 && COUNT<=3)
  require(CAPACITY>=0 && CAPACITY<=2147483647)
  noIoPrefix()
  val io=new Bundle {val x=in UInt(31 bits);val y=out UInt(31 bits)}
  if(COUNT==1) io.y:=io.x ^ U(CAPACITY,31 bits)
  else {
    val child=new CompactOptionalForward(COUNT-1,
      if(capacityActual.isDefined)Some(if(mutate)CAPACITY/2 else CAPACITY)else None,mutate)
    child.io.x:=io.x;io.y:=child.io.y
  }
}

class CsrMigrationLoopTests extends AnyFunSuite {
  private def run(dir:java.nio.file.Path, name:String, tb:String): Unit = {
    Files.write(dir.resolve("tb.v"),tb.getBytes("UTF-8"))
    for(cmd<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim",name+".v","tb.v"),Seq("vvp","sim"),
      Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal",name+".v"))) {
      val(code,log)=Increment66ToolEvidence.run(dir,cmd);assert(code==0,log)
    }
  }
  for(symbolic<-Seq(false,true)) test(s"ordinary Vec length retains exact geometry symbolic=$symbolic") {
    val dir=Files.createTempDirectory("vec-length-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
      preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray)) {
      new Component {
        setDefinitionName("LengthLoop")
        val depth=if(symbolic)RtlParam.int("DEPTH",3,1,8) else ElabInt.literal(3)
        val x=in(Vec(Bits(8 bits),depth));val y=out(Vec(Bits(8 bits),depth))
        for(i<-0 until x.length) y(i):=x(i)
      }
    }
    val rtl=new String(Files.readAllBytes(dir.resolve("LengthLoop.v")),"UTF-8")
    assert(rtl.contains("for ("),rtl)
    val instances=(if(symbolic)1 to 8 else 3 to 3).map { n=>
      s"wire [${n*8-1}:0] y$n; LengthLoop ${if(symbolic)s"#(.DEPTH($n))" else ""} d$n(.x(x[${n*8-1}:0]),.y(y$n)); always @(x) begin #1;if(y$n!==x[${n*8-1}:0]) $$fatal;end"
    }.mkString("\n")
    run(dir,"LengthLoop",s"module tb;reg[63:0]x;integer j;$instances initial begin x=0;#3;x='bx;#3;for(j=0;j<50;j=j+1)begin x={$$random,$$random};#3;end $$finish;end endmodule")
  }
  for((lower,upper)<-Seq((1,17),(16,17),(17,17),(18,17))) test(s"ordinary nonzero register range $lower until $upper") {
    val depth=lower.max(upper)
    val dir=Files.createTempDirectory("nonzero-range-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
      preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray)) {
      new Component {
        setDefinitionName("TailLoop")
        val x=in(Vec(Bits(8 bits),depth));val en=in Bool();val y=out(Vec(Bits(8 bits),depth))
        val regs=Vec.fill(depth)(Reg(Bits(8 bits)) init(0))
        regs.zipWithIndex.foreach { case (r,j) => if(j<lower) when(en){r:=x(j)} }
        for(i<-lower until upper) when(en){regs(i):=x(i)}
        y:=regs
      }
    }
    val rtl=new String(Files.readAllBytes(dir.resolve("TailLoop.v")),"UTF-8")
    // Empty data ranges emit no generate loop; a procedural reset loop may
    // independently initialize the retained array after LWR-38 compaction.
    assert(rtl.split("\n").exists(line=>line.trim.startsWith("for (") && line.contains("begin :")) == (upper>lower),rtl)
    run(dir,"TailLoop",s"""module tb; reg clk=0,reset=1,en=0;reg[${depth*8-1}:0] x=0;wire[${depth*8-1}:0]y;reg[${depth*8-1}:0]expected=0;integer j;
TailLoop dut(.clk(clk),.reset(reset),.en(en),.x(x),.y(y));
initial begin #1;clk=1;#1;clk=0;reset=0;for(j=0;j<60;j=j+1)begin x={$$random,$$random,$$random,$$random,$$random};en=(j%3!=0);#1;clk=1;if(en)expected=x;#1;if(y!==expected)$$fatal;clk=0;end reset=1;#1;if(y!==0)$$fatal;$$finish;end endmodule""")
  }
  test("nonzero inclusive combinational slice preserves endpoints") {
    val dir=Files.createTempDirectory("inclusive-slice-")
    MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
      preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray)) {
      new Component {
        setDefinitionName("InclusiveSlice")
        val x=in(Vec(Bits(8 bits),4));val y=out(Vec(Bits(8 bits),4))
        y(0):=x(0);y(3):=x(3)
        for(i<-1 to 2)y(i):= ~x(i)
      }
    }
    val rtl=new String(Files.readAllBytes(dir.resolve("InclusiveSlice.v")),"UTF-8")
    assert(rtl.contains("for ("),rtl)
    run(dir,"InclusiveSlice","""module tb;reg[31:0]x;wire[31:0]y;integer i;InclusiveSlice dut(.x(x),.y(y));
initial begin for(i=0;i<100;i=i+1)begin x=$random;#1;if(y!==(x^32'h00ffff00))$fatal;end $finish;end endmodule""")
  }
  for(actual<-Seq(None,Some(0),Some(1),Some(Int.MaxValue))) test(s"optional compact domain actual=$actual") {
    val dir=Files.createTempDirectory("compact-optional-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
      new Component {
        setDefinitionName("OptionalCapacity")
        val actualValue=actual.map(ElabInt.literal)
        val responseBufferBytes:Param[Int]=Param.optional(actualValue,0)
        require(responseBufferBytes>=0 && responseBufferBytes<=2147483647)
        val width=RtlParam.int("WIDTH",31,31,31)
        val x=in UInt(width bits)
        val y=out UInt(width bits)
        y:=x ^ U(responseBufferBytes,31 bits)
        RtlParam.recursiveOptionalOverride(responseBufferBytes,actualValue.map(_=>responseBufferBytes))
      }
    }
    val rtl=new String(Files.readAllBytes(dir.resolve("OptionalCapacity.v")),"UTF-8")
    assert(rtl.contains("parameter integer RESPONSE_BUFFER_BYTES")==actual.nonEmpty,rtl)
    run(dir,"OptionalCapacity",s"module tb;wire[30:0]y;OptionalCapacity dut(.x(31'd0),.y(y));initial begin #1;if(y!==31'd${actual.getOrElse(0)}) $$fatal;$$finish;end endmodule")
  }
  for(present<-Seq(false,true)) test(s"compact optional recursive forwarding presence=$present") {
    val dir=Files.createTempDirectory("compact-forward-")
    MorphVerilog(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false)) {
      new CompactOptionalForward(Param(3),if(present)Some(Param(7))else None)
    }
    val rtl=new String(Files.readAllBytes(dir.resolve("CompactOptionalForward.v")),"UTF-8")
    assert(rtl.contains("parameter integer CAPACITY")==present,rtl)
    val tests=(1 to 3).flatMap(n=>(if(present)Seq(0,7,Int.MaxValue)else Seq(0)).map { v=>
      s"wire[30:0]y${n}_$v;CompactOptionalForward #(.COUNT($n)${if(present)s",.CAPACITY($v)"else ""}) d${n}_$v(.x(31'd13),.y(y${n}_$v));initial begin #1;if(y${n}_$v!==31'd${13^v}) $$fatal;end"
    }).mkString("\n")
    run(dir,"CompactOptionalForward",s"module tb;$tests initial begin #3;$$finish;end endmodule")
  }
  for(actual<-Seq(-1L,2147483648L)) test(s"compact optional rejects outside Int domain $actual") {
    val error=intercept[Exception] {
      MorphVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("optional-invalid-").toString)) {
        new Component {val p=RtlParam.optional("P",Some(ElabInt.fromBigInt(BigInt(actual))),0,Int.MaxValue,0)}
      }
    }
    assert(error.toString.contains("DOMAIN") || error.toString.contains("OUT-OF-RANGE"),error.toString)
  }

  test("compact recursive scalar must remain unchanged") {
    val error=intercept[Exception] {
      MorphVerilog(SpinalConfig(targetDirectory=Files.createTempDirectory("compact-recursive-invalid-").toString)) {
        new CompactOptionalForward(Param(3),Some(Param(7)),true)
      }
    }
    assert(error.toString.contains("RECURSION-BINDING-UNPROVEN"),error.toString)
  }

}
