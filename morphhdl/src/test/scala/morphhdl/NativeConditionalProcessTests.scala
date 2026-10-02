package morphhdl

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets.UTF_8
import scala.sys.process.{Process, ProcessLogger}
import spinal.core._
import morphhdl.frontend._
import org.scalatest.funsuite.AnyFunSuite

class NativeConditionalProcessTests extends AnyFunSuite {
  class ConditionalLaneLoop(parameterized: Boolean, unsafe: Int = 0) extends Component {
    @dontName val lanes = if (parameterized) HdlInt.param("LANES", 4, 1, 4) else HdlInt.literal(4)
    @dontName val laneWidth = if (parameterized) HdlInt.param("PIXEL_WIDTH", 30, 1, 30) else HdlInt.literal(30)
    val previousData = in(morphhdl.frontend.Bits((lanes.asElabInt * laneWidth.asElabInt) bits))
    val previousMask = in(morphhdl.frontend.Bits(lanes bits))
    val clear = in Bool()
    val selected = in(spinal.core.UInt(3 bits))
    val pixel = in(morphhdl.frontend.Bits(laneWidth bits))
    val assembled = out(morphhdl.frontend.Bits((lanes.asElabInt * laneWidth.asElabInt) bits))
    val mask = out(morphhdl.frontend.Bits(lanes bits))
    assembled.addAttribute("keep")
    if (unsafe == 4) assembled.setAsReg()
    assembled := previousData
    mask := previousMask
    when(clear) { assembled := 0; mask := 0 }
    (0 until lanes).named("selected_lane", "selected").foreach { lane =>
      lane.whenSelected(selected) {
        if (unsafe == 1) assembled(lane * laneWidth, laneWidth) := assembled(29 downto 0)
        else if (unsafe == 3) when(clear) { assembled(lane * laneWidth, laneWidth) := pixel }
        else assembled(lane * laneWidth, laneWidth) := pixel
        mask(lane * HdlInt.literal(1), HdlInt.literal(1)) := B(1, 1 bits)
        if (unsafe == 2) mask(lane * HdlInt.literal(1), HdlInt.literal(1)) := B(0, 1 bits)
        if (unsafe == 5) (0 until lanes).named("nested", "inner").foreach { inner =>
          mask(inner * HdlInt.literal(1), HdlInt.literal(1)) := B(0, 1 bits)
        }
      }
    }
  }
  for (mode <- 1 to 5)
  test(s"unsupported conditional bodies fail closed, mode=$mode") {
    val error = intercept[Exception] {
      MorphVerilog(SpinalConfig(
        targetDirectory = Files.createTempDirectory("native-conditional-negative-").toString))(
        new ConditionalLaneLoop(false, mode))
    }
    val messages = Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_ != null)
      .map(x => String.valueOf(x.getMessage)).mkString("\n")
    assert(messages.contains(if (mode == 5) "MORPH-FRONTEND-NESTED-GENERATE-UNSUPPORTED" else "CONDITIONAL-BODY-UNSUPPORTED"), messages)
  }
  private def run(dir: Path, args: Seq[String]): (Int, String) =
    morphhdl.Increment66ToolEvidence.run(dir,args)
  test("ordinary Scala constant loops retain the existing unrolled native baseline") {
    val dir = Files.createTempDirectory("ordinary-conditional-loop-")
    SpinalVerilog(SpinalConfig(targetDirectory = dir.toString, headerWithDate = false))(
      new examples.ParameterExtensionsBaselineWriter.ConditionalLaneLoop)
    val rtl = new String(Files.readAllBytes(dir.resolve("ConditionalLaneLoop.v")), UTF_8)
    assert(!rtl.contains("for ("), rtl)
    assert("if\\s*\\(".r.findAllIn(rtl).size >= 8, rtl)
    val compile = run(dir, Seq("iverilog", "-g2001", "-s", "ConditionalLaneLoop", "-o", "ordinary.vvp", "ConditionalLaneLoop.v"))
    assert(compile._1 == 0, compile._2)
  }
  for (parameterized <- Seq(false,true))
  test(s"conditional packed loops retain priority and four-state behavior, parameterized=$parameterized") {
    val dir = Files.createTempDirectory("native-conditional-loop-")
    MorphVerilog(SpinalConfig(targetDirectory = dir.toString,
      headerWithDate = false))(new ConditionalLaneLoop(parameterized))
    val rtl = new String(Files.readAllBytes(dir.resolve("ConditionalLaneLoop.v")), UTF_8)
    assert(rtl.contains("for (") && rtl.contains("+:"), rtl)
    val indices = "integer ([A-Za-z_][A-Za-z0-9_]*);".r.findAllMatchIn(rtl).map(_.group(1)).toVector
    assert(indices.size == 2 && indices.distinct.size == 2 && !indices.contains("selected"), rtl)
    assert("always @".r.findAllIn(rtl).size == 2 && rtl.contains("keep") && rtl.contains("assembled"), rtl)
    val repeated = Files.createTempDirectory("native-conditional-loop-repeat-")
    MorphVerilog(SpinalConfig(targetDirectory = repeated.toString,
      headerWithDate = false))(new ConditionalLaneLoop(parameterized))
    assert(rtl == new String(Files.readAllBytes(repeated.resolve("ConditionalLaneLoop.v")), UTF_8))
    val lint = run(dir, Seq("verilator", "--lint-only", "-Wno-fatal", "--top-module", "ConditionalLaneLoop", "ConditionalLaneLoop.v"))
    assert(lint._1 == 0, lint._2)
    val profiles = if (parameterized) Seq((1,1),(3,7),(4,30)) else Seq((4,30))
    for ((lanes,width) <- profiles) {
      val specialization = if (parameterized) s"chparam -set LANES $lanes -set PIXEL_WIDTH $width ConditionalLaneLoop; " else ""
      val synth = run(dir, Seq("yosys", "-p", s"read_verilog -DSYNTHESIS ConditionalLaneLoop.v; $specialization proc; select -assert-none t:$$dlatch; synth -top ConditionalLaneLoop; check -assert"))
      assert(synth._1 == 0, synth._2)
      Files.write(dir.resolve("oracle.v"), s"""module oracle(input [${lanes*width-1}:0] previousData,
        |input [$lanes-1:0] previousMask,input clear,input [2:0] selected,input [$width-1:0] pixel,
        |output reg [${lanes*width-1}:0] assembled,output reg [$lanes-1:0] mask);
        |always @* begin assembled=previousData; mask=previousMask;
        |if(clear) begin assembled=0; mask=0; end
        |${(0 until lanes).map(i => s"if(selected == 3'd$i) begin assembled[${i*width} +: $width]=pixel; mask[$i]=1'b1; end").mkString("\n")}
        |end endmodule
        |""".stripMargin.getBytes(UTF_8))
      val proof = run(dir, Seq("yosys", "-p", s"read_verilog -DSYNTHESIS ConditionalLaneLoop.v oracle.v; $specialization proc; memory; opt; equiv_make oracle ConditionalLaneLoop equiv; hierarchy -top equiv; equiv_simple; equiv_status -assert"))
      assert(proof._1 == 0, proof._2)
      val overrides = if (parameterized) s"#(.LANES($lanes),.PIXEL_WIDTH($width))" else ""
      val differential = lanes == 4 && width == 30
      if (differential) {
        val baseline = java.nio.file.Paths.get("docs/morphhdl/evidence/increment66/baseline/ordinary-loop-native/ConditionalLaneLoop.v")
        Files.write(dir.resolve("baseline.v"), new String(Files.readAllBytes(baseline), UTF_8)
          .replace("module ConditionalLaneLoop", "module Baseline").getBytes(UTF_8))
      }
      Files.write(dir.resolve("tb.v"), s"""module tb;
        |localparam N=$lanes, W=$width;
        |reg [N*W-1:0] previousData, expected; reg [N-1:0] previousMask, expectedMask;
        |reg clear; reg [2:0] selected; reg [W-1:0] pixel;
        |wire [N*W-1:0] assembled; wire [N-1:0] mask;
        |${if (differential) "wire [119:0] baselineData; wire [3:0] baselineMask; Baseline reference(previousData,previousMask,clear,selected,pixel,baselineData,baselineMask);" else ""}
        |integer sample, choice, c, i;
        |ConditionalLaneLoop $overrides dut(previousData,previousMask,clear,selected,pixel,assembled,mask);
        |initial begin
        |for(sample=0;sample<64;sample=sample+1) begin
        |previousData={$$random,$$random,$$random,$$random}; previousMask=$$random; pixel=$$random;
        |if(sample==60) begin previousData={N*W{1'bx}}; previousMask={N{1'bz}}; end
        |if(sample==61) pixel={W{1'bz}};
        |if(sample==62) pixel={W{1'bx}};
        |for(choice=0;choice<10;choice=choice+1) for(c=0;c<4;c=c+1) begin
        |selected=choice; if(choice==8) selected=3'bxxx; if(choice==9) selected=3'bzzz;
        |clear=c; if(c==2) clear=1'bx; if(c==3) clear=1'bz;
        |expected=previousData; expectedMask=previousMask;
        |if(clear) begin expected=0; expectedMask=0; end
        |for(i=0;i<N;i=i+1) if(selected==i) begin expected[i*W+:W]=pixel; expectedMask[i]=1; end
        |#1; if(assembled !== expected || mask !== expectedMask) $$fatal(1,"LOOP_ORACLE");
        |${if (differential) "if(assembled !== baselineData || mask !== baselineMask) $fatal(1,\"LOOP_BASELINE\");" else ""}
        |end end
        |$$finish; end
        |endmodule
        |""".stripMargin.getBytes(UTF_8))
      val compiled = run(dir, Seq("iverilog","-g2001","-s","tb","-o","loop.vvp","ConditionalLaneLoop.v","tb.v") ++ (if (differential) Seq("baseline.v") else Seq.empty))
      assert(compiled._1 == 0, compiled._2)
      val simulated = run(dir, Seq("vvp","loop.vvp"))
      assert(simulated._1 == 0, simulated._2)
    }
  }
}
