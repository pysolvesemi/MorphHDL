package spinal.core

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets.UTF_8
import scala.sys.process.{Process, ProcessLogger}
import morphhdl.{MorphVerilog}
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite

class NativeResizeSimplificationTests extends AnyFunSuite {
  private class ResizeProbe(width: ElabInt) extends Component {
    val u = in UInt(width bits)
    val s = in SInt(width bits)
    val n = in UInt((width + 1) bits)
    val shift = in UInt(3 bits)
    val wide = out UInt((width + 1) bits)
    val signedWide = out SInt((width + 1) bits)
    val equal = out UInt(width bits)
    val narrow = out UInt(width bits)
    val joined = out Bits((width * 2 + 1) bits)
    val left = out UInt((width + 1) bits)
    val right = out SInt((width + 1) bits)
    val same = out Bool()
    val countShift = out UInt(64 bits)
    wide := u.resize(width + 1)
    signedWide := s.resize(width + 1)
    equal := u.resize(width)
    narrow := n.resize(width)
    joined := u.resize(width + 1).asBits ## u.asBits
    left := u.resize(width + 1) |<< shift
    right := s.resize(width + 1) >> shift
    same := u.resize(width + 1) === n
    countShift := U(1, 64 bits) |<< u.resize(width + 1)
  }
  private class ConcreteResizeProbe(width: Int) extends Component {
    val u = in UInt(width bits)
    val s = in SInt(width bits)
    val n = in UInt((width + 1) bits)
    val shift = in UInt(3 bits)
    val wide = out UInt((width + 1) bits)
    val signedWide = out SInt((width + 1) bits)
    val equal = out UInt(width bits)
    val narrow = out UInt(width bits)
    val joined = out Bits((width * 2 + 1) bits)
    val left = out UInt((width + 1) bits)
    val right = out SInt((width + 1) bits)
    val same = out Bool()
    val countShift = out UInt(64 bits)
    wide := u.resize(width + 1)
    signedWide := s.resize(width + 1)
    equal := u.resize(width)
    narrow := n.resize(width)
    joined := u.resize(width + 1).asBits ## u.asBits
    left := u.resize(width + 1) |<< shift
    right := s.resize(width + 1) >> shift
    same := u.resize(width + 1) === n
    countShift := U(1, 64 bits) |<< u.resize(width + 1)
  }
  private def run(dir: Path, args: Seq[String]): (Int, String) =
    morphhdl.Increment66ToolEvidence.run(dir,args)
  for (split <- Seq(false, true); derived <- Seq(false, true))
  test(s"native resize proves width relations while retaining nested type boundaries, split=$split derived=$derived") {
    val dir = Files.createTempDirectory("native-resize-simplification-")
    MorphVerilog(SpinalConfig(targetDirectory = dir.toString,
      oneFilePerComponent = split, headerWithDate = false)) {
      val width = if (derived) HdlInt.param("BUS_LOG2_BYTES", 3, 2, 5).asElabInt.pow2 * 8 / 8
        else HdlInt.param("WIDTH", 8, 1, 32).asElabInt
      new ResizeProbe(width)
    }
    val rtl = new String(Files.readAllBytes(dir.resolve("ResizeProbe.v")), UTF_8)
    assert(!rtl.contains(" ? "), rtl)
    assert(rtl.contains("{1{1'b0}}") && rtl.contains(", u}"), rtl)
    assert(rtl.contains("$signed") || rtl.contains(" signed "), rtl)
    info(s"Resize artifact: $dir")
    val baselineKind = if (derived) "resize-derived" else "resize-direct"
    val baselinePath = morphhdl.RepositoryTestResources.resolve(
      s"docs/morphhdl/evidence/increment66/baseline/$baselineKind/SymbolicResizeProbe.v")
    val baseline = new String(Files.readAllBytes(baselinePath), UTF_8)
      .replace("module SymbolicResizeProbe", "module BaselineResize")
    Files.write(dir.resolve("baseline.v"), baseline.getBytes(UTF_8))
    val lint = run(dir, Seq("verilator", "--lint-only", "-Wno-fatal", "--top-module", "ResizeProbe", "ResizeProbe.v"))
    assert(lint._1 == 0, lint._2)
    val profiles = if (derived) (2 to 5).map(x => ("BUS_LOG2_BYTES", x, 1 << x))
      else Seq(1, 2, 4, 7, 8, 32).map(x => ("WIDTH", x, x))
    for ((parameter, value, width) <- profiles) {
      Files.write(dir.resolve("tb.v"), s"""module tb;
        |localparam W=$width;
        |reg [W-1:0] u; reg signed [W-1:0] s; reg [W:0] n; reg [2:0] shift;
        |wire [W:0] wide, baselineWide; wire signed [W:0] signedWide;
        |wire [W-1:0] equal,narrow; wire [2*W:0] joined;
        |wire [W:0] left; wire signed [W:0] right; wire same; wire [63:0] countShift;
        |reg [W:0] expectedWide,expectedLeft; reg signed [W:0] expectedSigned,expectedRight;
        |reg [63:0] expectedCount; integer i;
        |ResizeProbe #(.$parameter($value)) dut(u,s,n,shift,wide,signedWide,equal,narrow,joined,left,right,same,countShift);
        |BaselineResize #(.$parameter($value)) baseline(.keep(u), .wide(baselineWide));
        |task check; begin
        |expectedWide={1'b0,u}; expectedSigned={s[W-1],s};
        |expectedLeft=expectedWide << shift; expectedRight=expectedSigned >>> shift;
        |expectedCount=64'b1 << expectedWide;
        |#1;
        |if(wide !== baselineWide || wide !== expectedWide || signedWide !== expectedSigned || equal !== u || narrow !== n[W-1:0]) $$fatal(1,"RESIZE_VALUE");
        |if(joined !== {expectedWide,u} || left !== expectedLeft || right !== expectedRight) $$fatal(1,"NESTED_RESIZE");
        |if(same !== (expectedWide == n) || countShift !== expectedCount) $$fatal(1,"SIZING_BOUNDARY");
        |end endtask
        |initial begin
        |for(i=0;i<400;i=i+1) begin u=$$random; s=$$random; n=$$random; shift=i%8; check; end
        |u={W{1'bx}}; s={W{1'bx}}; n={(W+1){1'bx}}; shift=0; check;
        |u={W{1'bz}}; s={W{1'bz}}; n={(W+1){1'bz}}; shift=1; check;
        |u=1; s=-1; n=1; shift=3'bxxx; check;
        |$$display("NATIVE_RESIZE_PASS"); $$finish;
        |end
        |endmodule
        |""".stripMargin.getBytes(UTF_8))
      val compiled = run(dir, Seq("iverilog", "-g2001", "-DSYNTHESIS", "-s", "tb", "-o", "resize.vvp", "ResizeProbe.v", "baseline.v", "tb.v"))
      assert(compiled._1 == 0, compiled._2)
      val simulated = run(dir, Seq("vvp", "resize.vvp"))
      assert(simulated._1 == 0 && simulated._2.contains("NATIVE_RESIZE_PASS"), simulated._2)
      val synth = run(dir, Seq("yosys", "-p", s"read_verilog -DSYNTHESIS ResizeProbe.v; chparam -set $parameter $value ResizeProbe; synth -top ResizeProbe; check -assert"))
      assert(synth._1 == 0, synth._2)
      val reference = dir.resolve(s"reference-$parameter-$value")
      SpinalVerilog(SpinalConfig(targetDirectory = reference.toString, headerWithDate = false)) {
        new ConcreteResizeProbe(width)
      }
      val script = s"""read_verilog -DSYNTHESIS ResizeProbe.v
        |chparam -set $parameter $value ResizeProbe
        |hierarchy -top ResizeProbe
        |proc; flatten; opt
        |rename ResizeProbe gate
        |design -stash candidate
        |read_verilog ${reference.resolve("ConcreteResizeProbe.v")}
        |hierarchy -top ConcreteResizeProbe
        |proc; flatten; opt
        |rename ConcreteResizeProbe gold
        |design -copy-from candidate -as gate gate
        |equiv_make gold gate equiv
        |hierarchy -top equiv
        |equiv_simple
        |equiv_status -assert
        |""".stripMargin
      Files.write(dir.resolve("proof.ys"), script.getBytes(UTF_8))
      val proof = run(dir, Seq("yosys", "-s", "proof.ys"))
      Files.write(dir.resolve(s"proof-$value.log"), proof._2.getBytes(UTF_8))
      assert(proof._1 == 0 && proof._2.contains("Equivalence successfully proven"), proof._2)
    }
  }
}
