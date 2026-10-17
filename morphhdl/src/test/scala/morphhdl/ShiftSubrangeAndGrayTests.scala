package morphhdl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import morphhdl.frontend.{HdlInt, HdlIntRangeStart, StructuralVecOps}

class ShiftSubrangeAndGrayTests extends AnyFunSuite {
  private class Chain(mode: String, symbolic: Boolean, missing: Boolean = false, overlap: Boolean = false) extends Component {
    setDefinitionName("Top")
    @dontName private val depth = if(mode == "csr") HdlInt.literal(17)
      else if(symbolic) HdlInt.param("DEPTH",3,2,8) else HdlInt.literal(4)
    val data = in Bits(8 bits)
    val enable = in Bool()
    val result = out Bits(8 bits)
    val stages = Vec.fill(depth.asElabInt)((Reg(Bits(8 bits)) init 0).addAttribute("ASYNC_REG","TRUE"))
    if(mode == "next") {
      // Deliberately local and unnamed: exact carriers must survive pruning.
      val next = Vec(Bits(8 bits),depth.asElabInt)
      if(!missing) next(0) := data
      if(overlap) next(1) := data
      (0 until (depth-1)).named("next_tail","lane").foreach { i => next(i+1) := stages(i) }
      ElabFiniteRange.foreach(depth.asElabInt,"load") { i => when(enable) { i(stages) := i(next) } }
    } else when(enable) {
      if(!missing) stages(0) := (if(mode=="reset") data | B(255,8 bits) else if(mode=="csr") data ^ B(1,8 bits) else data)
      if(overlap) stages(1) := data
      (0 until (depth-1)).named("shift_tail","lane").foreach { i =>
        stages(i+1) := (if(mode=="csr") data else stages(i))
      }
    }
    val last = ElabValue.uintLike(depth.asElabInt-1,UInt(5 bits),"last")
    result := stages(last)
  }
  private def options(dir: Path, unpacked: Boolean) = MorphAggregateOptions(
    SpinalConfig(targetDirectory=dir.toString,headerWithDate=false,
      defaultConfigForClockDomains=ClockDomainConfig(resetKind=ASYNC,resetActiveLevel=LOW)),
    preserveConstantVecs=true,preserveConstantLoops=true,
    vecLayout=if(unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector)
  private def run(dir: Path, command: Seq[String]): Unit = {
    val (code,log)=Increment66ToolEvidence.run(dir,command)
    assert(code==0,s"$command failed in $dir\n$log")
  }
  private def tools(dir: Path, lint: Boolean = true, prefixArray: Boolean = false): Unit = {
    run(dir,Seq("iverilog","-g2001","-s","tb","-o","sim","Top.v","tb.v"))
    run(dir,Seq("timeout","15","vvp","sim"))
    if(prefixArray) Files.write(dir.resolve("prefix.vlt"),
      "`verilator_config\nsplit_var -module \"Top\" -var \"decode_stage\"\n".getBytes(UTF_8))
    // Split the proven acyclic stage array into per-element scheduling nodes.
    // This is an optimization directive, not a lint warning waiver.
    if(lint) run(dir,Seq("verilator","--lint-only","-Wall","--language","1364-2001","-DSYNTHESIS","--top-module","Top") ++
      (if(prefixArray) Seq("prefix.vlt") else Seq.empty) ++ Seq("Top.v"))
    run(dir,Seq("yosys","-Q","-p","read_verilog Top.v; synth -top Top; check -assert; scc -expect 0"))
  }
  for(mode <- Seq("direct","reset","csr","next"); symbolic <- Seq(false,true) if mode!="csr" || !symbolic;
      unpacked <- Seq(false,true)) {
    test(s"subrange register chain mode=$mode symbolic=$symbolic unpacked=$unpacked") {
      val dir=Files.createTempDirectory("shift-subrange-")
      MorphVerilog(options(dir,unpacked))(new Chain(mode,symbolic))
      val rtl=new String(Files.readAllBytes(dir.resolve("Top.v")),UTF_8)
      assert(rtl.contains("ASYNC_REG"),rtl)
      assert(rtl.contains("for ("),rtl)
      val depths=if(mode=="csr") Seq(17) else if(symbolic) Seq(2,3,4,8) else Seq(4)
      val declarations=depths.map { n =>
        val parameters=if(symbolic) s"#(.DEPTH($n))" else ""
        s"reg [${n*8-1}:0] q$n=0;wire[7:0] y$n;Top $parameters u$n(.clk(clk),.resetn(resetn),.enable(en),.data(data),.result(y$n));"
      }.mkString("\n")
      val updates=depths.map { n => if(mode=="csr") s"q$n={$n{data}};" else
        s"q$n={q$n[${n*8-9}:0],${if(mode=="reset") "8'hff" else "data"}};" }.mkString("\n")
      val checks=depths.map(n=>s"""if(y$n !== q$n[${n*8-1}-:8]) $$fatal(1,"depth $n");""").mkString("\n")
      val reset=depths.map(n=>s"q$n=0;").mkString
      Files.write(dir.resolve("tb.v"),s"""module tb;
reg clk=0,resetn=1,en=0;reg[7:0] data=0;integer k;
$declarations
initial begin #1;resetn=0;#1;$checks resetn=1;
for(k=0;k<80;k=k+1) begin clk=0;en=(k%3)!=0;data=k*13+7;#2;
if(k==41) begin resetn=0;$reset #1;$checks resetn=1;end
clk=1;if(en) begin $updates end #1;$checks end $$finish;end
endmodule
""".getBytes(UTF_8))
      // Reset-only mode intentionally ignores data; the independent report's
      // identical unused-input warning is not hidden as a clean lint result.
      tools(dir,lint=mode!="reset")
    }
  }
  for(mode <- Seq("direct","next"); missing <- Seq(false,true)) {
    test(s"subrange rejects missing or competing writer mode=$mode missing=$missing") {
      val dir=Files.createTempDirectory("shift-subrange-negative-")
      val error=intercept[Exception] {
        MorphVerilog(options(dir,true))(new Chain(mode,true,missing=missing,overlap= !missing))
      }
      val text=Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_!=null).map(_.getMessage).mkString("\n")
      assert(text.contains("STORAGE-MISMATCH") || text.contains("WRITER-MISMATCH") || text.contains("NO DRIVER"),text)
    }
  }
  for(unpacked <- Seq(false,true); default <- Seq(1,8,64)) {
    test(s"Gray prefix stage array preserves all width overrides unpacked=$unpacked default=$default") {
      val dir=Files.createTempDirectory("gray-prefix-")
      def generate(destination:Path): Unit = MorphVerilog(options(destination,unpacked))(new Component {
        setDefinitionName("Top")
        @dontName private val width=HdlInt.param("DATA_BITS",default,1,64).asElabInt
        val gray=in Bits(width bits)
        val binary=out UInt(width bits)
        binary:=spinal.lib.fromGray(gray)
      })
      generate(dir)
      val repeated=Files.createTempDirectory("gray-prefix-repeat-")
      generate(repeated)
      assert(java.util.Arrays.equals(Files.readAllBytes(dir.resolve("Top.v")),Files.readAllBytes(repeated.resolve("Top.v"))))
      val rtl=new String(Files.readAllBytes(dir.resolve("Top.v")),UTF_8)
      assert(rtl.contains("decode_stage") && rtl.contains("for (") && rtl.contains("1 << i"),rtl)
      assert(!rtl.contains("morphhdl_ceil_log2"),rtl)
      val widths=Seq(1,2,3,5,8,13,31,32,33,63,64)
      val declarations=widths.map(n=>s"wire[${n-1}:0] b$n;wire[${n-1}:0] x$n=value[${n-1}:0];Top #(.DATA_BITS($n)) u$n(.gray(x$n ^ (x$n >> 1)),.binary(b$n));").mkString("\n")
      val checks=widths.map(n=>s"""if(b$n !== x$n) $$fatal(1,"Gray width $n");""").mkString("\n")
      Files.write(dir.resolve("tb.v"),s"""module tb;reg[63:0] value;integer k;
$declarations
initial begin for(k=0;k<1024;k=k+1) begin
value={$$random,$$random};#1;$checks
value=64'b1 << (k%64);#1;$checks
end value=0;#1;$checks value=~64'b0;#1;$checks $$finish;end endmodule
""".getBytes(UTF_8))
      tools(dir,prefixArray=default!=1)
    }
  }
  test("single-bit Gray domain has no XOR stage or generate loop") {
    val dir=Files.createTempDirectory("gray-single-bit-")
    MorphVerilog(options(dir,true))(new Component {
      setDefinitionName("Top")
      @dontName private val width=HdlInt.param("DATA_BITS",1,1,1).asElabInt
      val gray=in Bits(width bits)
      val binary=out UInt(width bits)
      binary:=spinal.lib.fromGray(gray)
    })
    val rtl=new String(Files.readAllBytes(dir.resolve("Top.v")),UTF_8)
    assert(!rtl.contains("decode_stage") && !rtl.contains(" ^ ") && !rtl.contains("for ("),rtl)
    Files.write(dir.resolve("tb.v"),"module tb;reg gray;wire binary;Top dut(.gray(gray),.binary(binary));initial begin gray=0;#1;if(binary!==gray) $fatal;gray=1;#1;if(binary!==gray) $fatal;$finish;end endmodule".getBytes(UTF_8))
    tools(dir)
  }

}
