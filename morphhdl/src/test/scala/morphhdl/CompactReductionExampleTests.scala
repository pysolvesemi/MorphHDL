package morphhdl

import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

/** Executable roadmap example: bounded, geometry-preserving generic callbacks. */
@_root_.spinal.core.rtlRecursive
final class CompactReduction[T <: Data](payload: HardType[T], countActual: Param[Int],
    initialActual: Param[Int], maskActual: Option[Param[Int]],
    @rtlHardwareCallback operation: (T,T) => T,
    @rtlHardwareCallback bridge: Option[(T,Int) => T]) extends Component {
  setDefinitionName("CompactReduction")
  noIoPrefix()
  val COUNT: Param[Int] = Param(countActual)
  val INITIAL_COUNT: Param[Int] = Param(initialActual)
  val PIPELINE_MASK: Param[Int] = Param.optional(maskActual, 0)
  require(COUNT >= 1 && COUNT <= 5)
  require(INITIAL_COUNT >= 1 && INITIAL_COUNT <= 5)
  require(PIPELINE_MASK >= 0 && PIPELINE_MASK <= 7)
  require(INITIAL_COUNT >= COUNT)
  val io = new Bundle {
    val terms = in(Vec(payload(), COUNT))
    val result = out(payload())
  }
  if (COUNT == 1) io.result := io.terms(0)
  else {
    val nextCount = (COUNT + 1) / 2
    val combined = Vec(payload(), nextCount)
    for (i <- 0 until nextCount) {
      val pair = payload()
      if (2*i+1 < COUNT) pair := operation(io.terms(2*i), io.terms(2*i+1))
      else pair := io.terms(2*i)
      for (stage <- 0 until 3) {
        if (INITIAL_COUNT.log2Up - COUNT.log2Up == stage) {
          val value = payload()
          value := (if (bridge.isDefined) bridge.get(pair, stage) else pair)
          if (((PIPELINE_MASK >> stage) & 1) != 0)
            combined(i) := RegNext(value) init(value.getZero)
          else combined(i) := value
        }
      }
    }
    val next = new CompactReduction(payload, nextCount, INITIAL_COUNT,
      if (maskActual.isDefined) Some(PIPELINE_MASK) else None, operation, bridge)
    next.io.terms := combined
    io.result := next.io.result
  }
}

class CompactReductionExampleTests extends AnyFunSuite {
  for(mode <- Seq("plain", "pipeline", "bridge"); default <- Seq(1,5))
  test(s"generic Option recursive example mode=$mode default=$default") {
    def generate() = {
      val dir=Files.createTempDirectory("compact-reduction-")
      MorphVerilog(MorphAggregateOptions(SpinalConfig(targetDirectory=dir.toString,headerWithDate=false),
        preserveConstantVecs=true,preserveConstantLoops=true,vecLayout=MorphAggregateOptions.UnpackedArray)) {
        new CompactReduction(HardType(UInt(8 bits)), Param(default), Param(default),
          if(mode=="pipeline") Some(Param(0)) else None,
          (a:UInt,b:UInt)=>a-b,
          if(mode=="bridge") Some((v:UInt,l:Int)=>v+U(l+1,8 bits)) else None)
      }
      dir
    }
    val dir=generate();val repeat=generate()
    val rtl=new String(Files.readAllBytes(dir.resolve("CompactReduction.v")),"UTF-8")
    assert(rtl==new String(Files.readAllBytes(repeat.resolve("CompactReduction.v")),"UTF-8"))
    assert(rtl.contains("parameter integer PIPELINE_MASK")== (mode=="pipeline"))
    def reference(n:Int):String = {
      var values:Seq[String]=(0 until n).map(i=>s"terms[${i*8} +: 8]")
      var stage=0
      while(values.size>1) {
        values=values.grouped(2).map { pair =>
          val value=if(pair.size==2)s"(${pair(0)} - ${pair(1)})" else pair.head
          if(mode=="bridge")s"($value + 8'd${stage+1})" else value
        }.toVector
        stage+=1
      }
      values.head
    }
    val checks=Seq(1,3,5).map { n =>
      val parameters=s".COUNT($n),.INITIAL_COUNT($n)"+(if(mode=="pipeline")",.PIPELINE_MASK(1)" else "")
      val clocks=if(mode=="pipeline")",.clk(clk),.reset(reset)" else ""
      s"""wire [7:0] r$n;
CompactReduction #($parameters) d$n(.terms(terms[${n*8-1}:0]),.result(r$n)$clocks);
always @(posedge clk or posedge reset) if(reset) expected$n<=0;else expected$n<=${reference(n)};
always @(negedge clk) begin #1;if(r$n !== reference$n) $$fatal;end"""
    }.mkString("\n")
    val refs=Seq(1,3,5).map(n=>s"wire [7:0] reference$n = ${if(mode=="pipeline" && n>1)s"expected$n" else reference(n)};").mkString("\n")
    Files.write(dir.resolve("tb.v"),s"""module tb;
reg clk=0,reset=0;reg [39:0] terms=0;integer cycle;
reg [7:0] expected1,expected3,expected5;
always #5 clk=~clk;
$refs
$checks
initial begin #1;reset=1;#6;reset=0;
for(cycle=0;cycle<40;cycle=cycle+1)begin @(negedge clk);#2;terms={$$random,$$random};end
@(negedge clk);#2;terms='bx;repeat(2)@(negedge clk);#2;$$finish;end
endmodule
""".getBytes("UTF-8"))
    for(command<-Seq(Seq("iverilog","-g2001","-s","tb","-o","sim","CompactReduction.v","tb.v"),Seq("vvp","sim"),
      Seq("verilator","--lint-only","--language","1364-2001","-DSYNTHESIS","-Wno-fatal","CompactReduction.v"))) {
      val(code,log)=Increment66ToolEvidence.run(dir,command);assert(code==0,log)
    }
  }
}
