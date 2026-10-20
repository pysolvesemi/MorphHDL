package morphhdl

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.internals.PhaseDeviceDefault

class SynchronizerAttributeTests extends AnyFunSuite {
  private def annotate(signal: BaseType, mode: String): Unit = {
    mode match {
      case "upper" => signal.addAttribute("ASYNC_REG", "TRUE")
      case "lower" => signal.addAttribute("async_reg", "true")
      case "upperLower" => signal.addAttribute("ASYNC_REG", "true")
      case "lowerUpper" => signal.addAttribute("async_reg", "TRUE")
      case "both" =>
        signal.addAttribute("async_reg", "true")
        signal.addAttribute("ASYNC_REG", "TRUE")
        signal.addAttribute("ASYNC_REG", "TRUE")
      case "false" => signal.addAttribute("async_reg", "false")
      case "upperFalse" => signal.addAttribute("ASYNC_REG", "FALSE")
      case "conflict" =>
        signal.addAttribute("ASYNC_REG", "TRUE")
        signal.addAttribute("async_reg", "false")
      case "integer" => signal.addAttribute("async_reg", 1)
      case "flag" => signal.addAttribute("async_reg")
      case "unknown" => signal.addAttribute("async_reg", "enabled")
      case "comment" => signal.addAttribute(new AttributeString("async_reg", "false", COMMENT_ATTRIBUTE))
      case "none" =>
    }
    signal.addAttribute("KEEP", "upper")
    signal.addAttribute("keep", "lower")
  }

  private class Scalar(mode: String, repeated: Boolean, tagged: Boolean = true) extends Component {
    setDefinitionName("Scalar")
    val x = in Bool()
    val y = out Bool()
    val stage = RegNext(x) init False
    annotate(stage, mode)
    if(tagged) stage.addTag(crossClockDomain)
    if(repeated) {
      val authored = stage.getTags().collect { case a: Attribute => a }.toVector
      PhaseDeviceDefault.onCrossClockBuffer(GlobalData.get.config, stage)
      PhaseDeviceDefault.onCrossClockBuffer(GlobalData.get.config, stage)
      assert(!tagged || stage.hasTag(crossClockDomain))
      assert(stage.getTags().collect { case a: Attribute if a.getName == "KEEP" => a }
        .exists(a => authored.exists(_ eq a)))
    }
    y := stage
  }

  private class ArrayChain(mode: String) extends Component {
    setDefinitionName("ArrayChain")
    val WIDTH: Param[Int] = Param(5)
    require(WIDTH >= 1 && WIDTH <= 8)
    val DEPTH: Param[Int] = Param(3)
    require(DEPTH >= 2 && DEPTH <= 4)
    val x = in Bits(WIDTH bits)
    val y = out Bits(WIDTH bits)
    val stages = Vec.fill(DEPTH) {
      val stage = Reg(Bits(WIDTH bits)) init 0
      annotate(stage, mode)
      stage.addTag(crossClockBuffer)
    }
    stages(0).addTag(crossClockDomain)
    stages(0) := x
    for(i <- 0 until (DEPTH - 1)) stages(i + 1) := stages(i)
    y := stages(DEPTH - 1)
  }

  private def emit(device: Device, mode: String, array: Boolean = false,
      repeated: Boolean = false, tagged: Boolean = true): (Path, String) = {
    val dir = Files.createTempDirectory("synchronizer-attributes-")
    val config = SpinalConfig(targetDirectory = dir.toString, headerWithDate = false, device = device)
    if(array) MorphVerilog(MorphAggregateOptions(config, true, true, MorphAggregateOptions.UnpackedArray))(new ArrayChain(mode))
    else SpinalVerilog(config)(new Scalar(mode, repeated, tagged))
    val name = if(array) "ArrayChain" else "Scalar"
    dir -> new String(Files.readAllBytes(dir.resolve(name + ".v")), UTF_8)
  }
  private def count(rtl: String, text: String): Int = rtl.sliding(text.length).count(_ == text)
  private def check(rtl: String, upper: Int, lower: Int): Unit = {
    assert(count(rtl, "ASYNC_REG = \"TRUE\"") == upper, rtl)
    assert(count(rtl, "async_reg = \"true\"") == lower, rtl)
    assert(rtl.contains("KEEP = \"upper\"") && rtl.contains("keep = \"lower\""), rtl)
  }
  private def run(dir: Path, command: String*): Unit = {
    val result = Increment66ToolEvidence.run(dir, command)
    assert(result._1 == 0, command.mkString(" ") + "\n" + result._2)
  }

  for(device <- Seq(Device(), Device.XILINX); mode <- Seq("upper", "lower", "none", "both"))
  test(s"Vivado synchronizer attributes preserve authored spelling and insertion is idempotent ${device.vendor} $mode") {
    val (dir, rtl) = emit(device, mode, repeated = true)
    check(rtl, if(mode == "upper" || mode == "both") 1 else 0,
      if(mode == "upper" || mode == "both") 0 else 1)
    assert(rtl == emit(device, mode, repeated = true)._2)
    run(dir, "iverilog", "-g2001", "-s", "Scalar", "-o", "scalar.vvp", "Scalar.v")
  }

  for(mode <- Seq("upper", "lower", "none", "both"))
  test(s"Efinix retains its lowercase target annotation $mode") {
    val (_, rtl) = emit(Device.EFINIX, mode, repeated = true)
    // An uppercase user attribute is not assumed to be recognized by Efinity.
    check(rtl, if(mode == "both") 2 else if(mode == "upper") 1 else 0, 1)
  }

  test("Vivado recognizes both supported true-value spellings without rewriting the authored value") {
    for(device <- Seq(Device(), Device.XILINX); mode <- Seq("upperLower", "lowerUpper")) {
      val (_, rtl) = emit(device, mode, repeated = true)
      assert(count(rtl, if(mode == "upperLower") "ASYNC_REG = \"true\"" else "async_reg = \"TRUE\"") == 1, rtl)
      check(rtl, 0, 0)
    }
    assert(intercept[Exception] { emit(Device.EFINIX, "lowerUpper") }
      .getMessage.contains("SPINAL-SYNCHRONIZER-ATTRIBUTE-CONFLICT"))
  }

  test("unrelated targets and untagged registers keep their authored attributes") {
    for(device <- Seq(Device.ALTERA, Device.LATTICE, Device.ACTEL, Device.ASIC, Device.NONE, Device("other"))) {
      val (_, rtl) = emit(device, "upperFalse", repeated = true)
      assert(rtl.contains("ASYNC_REG = \"FALSE\"") && !rtl.contains("async_reg = \"true\""), rtl)
      check(emit(device, "none")._2, 0, 0)
    }
    assert(emit(Device.XILINX, "false", tagged = false)._2.contains("async_reg = \"false\""))
  }

  test("comment attributes do not suppress a real synthesis annotation") {
    val (_, rtl) = emit(Device.XILINX, "comment", repeated = true)
    check(rtl, 0, 1)
    assert(rtl.contains("async_reg = \"false\""), rtl)
  }

  test("conflicting and unsupported synchronizer values fail with target and declaration diagnostics") {
    for(device <- Seq(Device(), Device.XILINX, Device.EFINIX);
        mode <- Seq("false", "conflict", "integer", "flag", "unknown")) {
      val error = intercept[Exception] { emit(device, mode) }
      assert(error.getMessage.contains("SPINAL-SYNCHRONIZER-ATTRIBUTE-CONFLICT"), error.getMessage)
      assert(error.getMessage.contains("stage") && error.getMessage.contains(device.vendor), error.getMessage)
    }
    assert(intercept[Exception] { emit(Device.XILINX, "upperFalse") }.getMessage.contains("SPINAL-SYNCHRONIZER-ATTRIBUTE-CONFLICT"))
    for(device <- Seq(Device(), Device.XILINX, Device.EFINIX)) {
      assert(intercept[Exception] { emit(device, "conflict", array = true) }
        .getMessage.contains("SPINAL-SYNCHRONIZER-ATTRIBUTE-CONFLICT"))
    }
  }

  for(device <- Seq(Device(), Device.XILINX, Device.EFINIX); mode <- Seq("upper", "lower", "none"))
  test(s"unpacked symbolic synchronizers retain attributes reset and shift behavior ${device.vendor} $mode") {
    val (dir, rtl) = emit(device, mode, array = true)
    check(rtl, if(mode == "upper") 1 else 0,
      if(mode != "upper" || device == Device.EFINIX) 1 else 0)
    assert(rtl.contains("reg [(WIDTH)-1:0] stages [0:(DEPTH)-1]"), rtl)
    assert(rtl == emit(device, mode, array = true)._2)
    run(dir, "verilator", "--lint-only", "--language", "1364-2001", "-Wall", "-DSYNTHESIS", "ArrayChain.v")
    run(dir, "yosys", "-Q", "-p", "read_verilog -DSYNTHESIS ArrayChain.v; synth -top ArrayChain; check -assert; select -assert-none t:$dlatch")
    for(width <- Seq(1, 8); depth <- Seq(2, 4)) {
      Files.write(dir.resolve("tb.v"), s"""module tb;
        |reg clk=0, reset=1; reg [${width-1}:0] x=0; wire [${width-1}:0] y;
        |reg [${width-1}:0] expected[0:${depth-1}]; integer i,j;
        |ArrayChain #(.WIDTH($width),.DEPTH($depth)) dut(.clk(clk),.reset(reset),.x(x),.y(y));
        |initial begin
        |for(j=0;j<$depth;j=j+1) expected[j]=0;
        |#1; if(y !== 0) $$fatal(1,"reset"); reset=0;
        |for(i=0;i<40;i=i+1) begin
        |x=$$random; if(i==5) x='bx; if(i==7) x='bz;
        |for(j=$depth-1;j>0;j=j-1) expected[j]=expected[j-1];
        |expected[0]=x; #1;clk=1;#1;if(y !== expected[$depth-1]) $$fatal(1,"shift");clk=0;
        |end
        |reset=1;#1;if(y !== 0) $$fatal(1,"async reset");
        |$$display("SYNC_ATTRIBUTES_PASS");$$finish;
        |end endmodule
        |""".stripMargin.getBytes(UTF_8))
      run(dir, "iverilog", "-g2001", "-s", "tb", "-o", "sim", "ArrayChain.v", "tb.v")
      run(dir, "vvp", "sim")
    }
  }
}
