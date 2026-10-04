package morphhdl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import morphhdl.frontend.{HdlInt, HdlIntRangeStart, StructuralVecOps, formalParam}

class SymbolicVecInitializerTests extends AnyFunSuite {
  private class Chain(mode: String, actualWidth: ElabInt, actualDepth: ElabInt) extends Component {
    setDefinitionName("Chain")
    @dontName private val width = formalParam(actualWidth, "WIDTH", 1, 64)
    @dontName private val depth = formalParam(actualDepth, "DEPTH", 2, 8)
    @dontName private val loopDepth = HdlInt.fromElabIntParameter(depth)
    val data = in Bits(width bits)
    val enable = in Bool()
    val result = out Bits(width bits)
    val stages = Vec.fill(depth)((Reg(Bits(width bits)) init 0).addAttribute("ASYNC_REG", "TRUE"))
    if(mode == "full") {
      ElabFiniteRange.foreach(depth, "load") { i => when(enable) { i(stages) := data } }
    } else if(mode == "next") {
      val next = Vec(Bits(width bits), depth)
      next(0) := data
      (0 until (loopDepth - 1)).named("next_tail", "lane").foreach { i => next(i + 1) := stages(i) }
      ElabFiniteRange.foreach(depth, "load") { i => when(enable) { i(stages) := i(next) } }
    } else when(enable) {
      stages(0) := data
      (0 until (loopDepth - 1)).named("shift_tail", "lane").foreach { i => stages(i + 1) := stages(i) }
    }
    val last = ElabValue.uintLike(depth - 1, UInt(5 bits), "last")
    result := stages(last)
  }
  private class Top(mode: String) extends Component {
    @dontName private val width = HdlInt.param("WIDTH", 8, 1, 64).asElabInt
    @dontName private val depth = HdlInt.param("DEPTH", 3, 2, 8).asElabInt
    val data = in Bits(width bits)
    val enable = in Bool()
    val result = out Bits(width bits)
    val child = new Chain(mode, width, depth)
    child.data := data
    child.enable := enable
    result := child.result
  }
  private def run(dir: Path, args: Seq[String]): Unit = {
    val (code, log) = Increment66ToolEvidence.run(dir, args)
    assert(code == 0, s"$args in $dir\n$log")
  }
  for(mode <- Seq("direct", "next", "full"); unpacked <- Seq(false, true)) {
    test(s"symbolic initialized Vec reset lineage mode=$mode unpacked=$unpacked") {
      val dir = Files.createTempDirectory("symbolic-vec-init-")
      def generate(destination: Path): Unit = MorphVerilog(MorphAggregateOptions(
        SpinalConfig(targetDirectory = destination.toString, headerWithDate = false, oneFilePerComponent = true,
          defaultConfigForClockDomains = ClockDomainConfig(resetKind = ASYNC, resetActiveLevel = LOW)),
        preserveConstantVecs = true, preserveConstantLoops = true,
        vecLayout = if(unpacked) MorphAggregateOptions.UnpackedArray else MorphAggregateOptions.PackedVector))(new Top(mode))
      generate(dir)
      val repeat = Files.createTempDirectory("symbolic-vec-init-repeat-")
      generate(repeat)
      for(file <- Seq("Top.v", "Chain.v"))
        assert(java.util.Arrays.equals(Files.readAllBytes(dir.resolve(file)), Files.readAllBytes(repeat.resolve(file))))
      val rtl = new String(Files.readAllBytes(dir.resolve("Chain.v")), UTF_8)
      assert(rtl.contains("ASYNC_REG") && rtl.contains("for (") && rtl.contains("negedge resetn"), rtl)
      assert(rtl.contains("{WIDTH{1'b0}}"), rtl)
      val profiles = for(w <- Seq(1, 5, 64); d <- Seq(2, 3, 8)) yield (w, d)
      val instances = profiles.map { case(w, d) => s"wire done${w}_$d; profile #(.W($w),.D($d)) p${w}_$d(done${w}_$d);" }.mkString("\n")
      val done = profiles.map { case(w, d) => s"done${w}_$d" }.mkString(" && ")
      val update = if(mode == "full") "q={D{data}};" else "q={q[(D-1)*W-1:0],data};"
      Files.write(dir.resolve("tb.v"), s"""module profile #(parameter W=8,D=3)(output reg done=0);
reg clk=0,resetn=1,en=0;reg[W-1:0] data=0;wire[W-1:0] result;
reg[D*W-1:0] q=0;integer k;
Top #(.WIDTH(W),.DEPTH(D)) dut(.clk(clk),.resetn(resetn),.enable(en),.data(data),.result(result));
initial begin #1;resetn=0;#1;if(result!==0) $$fatal(1,"initial reset");resetn=1;
for(k=0;k<96;k=k+1) begin clk=0;en=(k%3)!=0;data={$$random,$$random};#2;
if(k==42) begin en=0;resetn=0;q=0;#1;if(result!==0) $$fatal(1,"async stalled reset");resetn=1;end
clk=1;if(en) begin $update end #1;if(result!==q[D*W-1-:W]) $$fatal(1,"width/depth reset shift hold %0d/%0d",W,D);
end done=1;end endmodule
module tb;
$instances
initial begin wait($done);$$finish;end endmodule
""".getBytes(UTF_8))
      run(dir, Seq("iverilog", "-g2001", "-s", "tb", "-o", "sim", "Top.v", "Chain.v", "tb.v"))
      run(dir, Seq("timeout", "15", "vvp", "sim"))
      for((w, d) <- profiles) {
        run(dir, Seq("verilator", "--lint-only", "-Wall", "--language", "1364-2001", "-DSYNTHESIS",
          "--top-module", "Top", s"-GWIDTH=$w", s"-GDEPTH=$d", "Top.v", "Chain.v"))
        run(dir, Seq("yosys", "-Q", "-p",
          s"read_verilog Top.v Chain.v; chparam -set WIDTH $w -set DEPTH $d Top; synth -top Top; check -assert"))
      }
    }
  }
}
