package spinal.core.internals

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.sys.process.{Process, ProcessLogger}
import org.scalatest.funsuite.AnyFunSuite
import morphhdl.{MorphSignedCasts, MorphVerilog}
import morphhdl.frontend.HdlInt

class TypedBalancedReductionPublicationTests extends AnyFunSuite {
  private def generate(): String = {
    val path = TypedBalancedReductionPublicationArtifactWriter.candidate(
      Files.createTempDirectory("balanced-publication-"))
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)
  }

  test("the public native helper emits independent WIDTH and COUNT parameters") {
    val text = generate()
    assert("(?s).*parameter[^;]*WIDTH[^;]*=.*".r.pattern.matcher(text).matches(), text)
    assert("(?s).*parameter[^;]*COUNT[^;]*=.*".r.pattern.matcher(text).matches(), text)
    assert(text.linesIterator.count(_.trim.startsWith("module BalancedPublication")) == 1, text)
    assert(text.contains("unsignedIn") && text.contains("signedIn") &&
      text.contains("bitsIn") && text.contains("boolIn"), text)
    assert(text.contains("WIDTH") && text.contains("COUNT"), text)
  }

  test("singleton-default publication retains all balanced stages for larger overrides") {
    val text = generate()
    for (tree <- 1 to 5; level <- 0 until 5) {
      assert(text.contains(s"morphhdl_balanced_${tree}_active_$level"), text)
    }
    assert(text.contains("generate") && text.contains("genvar") && text.contains("begin : tail"), text)
    assert(text.contains("+:"), text)
  }

  test("repeated independent publication is byte deterministic") {
    assert(generate() == generate())
  }

  test("signed boundary mode preserves balanced publication across singleton and odd counts") {
    val directory = Files.createTempDirectory("balanced-publication-signed-")
    val config = TypedBalancedReductionPublicationArtifactWriter.config(directory, "BalancedPublication.v")
    MorphVerilog(MorphSignedCasts.enable(config)) {
      new BalancedPublicationHardware(HdlInt.param("WIDTH", 5, 1, 32),
        HdlInt.param("COUNT", 1, 1, 17))
    }
    val rtl = new String(Files.readAllBytes(directory.resolve("BalancedPublication.v")), StandardCharsets.UTF_8)
    assert(rtl.contains("morphhdl_balanced_2_active_4"), rtl)
    assert("(?m)^.*output\\s+wire\\s+signed\\s+\\[WIDTH-1:0\\]\\s+sAdd\\b.*$".r
      .findFirstIn(rtl).nonEmpty, rtl)
    if (available("iverilog") && available("vvp")) {
      Vector((5, 1), (1, 3), (8, 5)).foreach { case (width, count) =>
        simulateSignedPublication(directory, width, count)
      }
    }
  }

  private def available(command: String): Boolean =
    Process(Seq("sh", "-c", s"command -v $command")).!(ProcessLogger(_ => (), _ => ())) == 0

  private def simulateSignedPublication(directory: Path, width: Int, count: Int): Unit = {
    val module = s"BalancedPublicationReference_w${width}_n$count"
    val referenceDirectory = directory.resolve(s"reference_w${width}_n$count")
    TypedBalancedReductionPublicationArtifactWriter.config(referenceDirectory, module + ".v")
      .generateVerilog(new BalancedPublicationReference(width, count))
    val testbench = directory.resolve(s"signed_tb_${width}_$count.v")
    val executable = directory.resolve(s"signed_tb_${width}_$count.out")
    val text = s"""module tb;
      |localparam W = $width;
      |localparam N = $count;
      |reg clk, reset, enable;
      |reg [(W*N)-1:0] unsignedIn, signedIn, bitsIn;
      |reg [N-1:0] boolIn;
      |wire [W-1:0] uAdd, sAdd, bXor, rAdd;
      |wire [W-1:0] refUAdd, refSAdd, refBXor, refRAdd;
      |wire qAnd, refQAnd;
      |reg [W-1:0] expectedSignedSum;
      |integer cycle, lane;
      |BalancedPublication #(.WIDTH(W), .COUNT(N)) dut (
      |  .clk(clk), .reset(reset), .enable(enable), .unsignedIn(unsignedIn),
      |  .signedIn(signedIn), .bitsIn(bitsIn), .boolIn(boolIn),
      |  .uAdd(uAdd), .sAdd(sAdd), .bXor(bXor), .qAnd(qAnd), .rAdd(rAdd));
      |$module reference (
      |  .clk(clk), .reset(reset), .enable(enable), .unsignedIn(unsignedIn),
      |  .signedIn(signedIn), .bitsIn(bitsIn), .boolIn(boolIn),
      |  .uAdd(refUAdd), .sAdd(refSAdd), .bXor(refBXor), .qAnd(refQAnd), .rAdd(refRAdd));
      |initial begin
      |  clk = 0;
      |  for (cycle = 0; cycle < 32; cycle = cycle + 1) begin
      |    reset = cycle < 2 || cycle == 17;
      |    enable = cycle % 5 != 2;
      |    expectedSignedSum = 0;
      |    for (lane = 0; lane < N; lane = lane + 1) begin
      |      unsignedIn[lane*W +: W] = 7*cycle + 3*lane;
      |      signedIn[lane*W +: W] = lane % 2 == 0 ? -(cycle+lane+1) : cycle+2*lane;
      |      bitsIn[lane*W +: W] = 11*cycle + 5*lane;
      |      boolIn[lane] = (cycle+lane) % 4 != 0;
      |      expectedSignedSum = expectedSignedSum + signedIn[lane*W +: W];
      |    end
      |    #1;
      |    clk = 1;
      |    #1;
      |    if ({uAdd, sAdd, bXor, qAnd, rAdd} !== {refUAdd, refSAdd, refBXor, refQAnd, refRAdd}
      |        || sAdd !== expectedSignedSum) begin
      |      $$display("FAIL signed balanced width=%0d count=%0d cycle=%0d", W, N, cycle);
      |      $$finish;
      |    end
      |    clk = 0;
      |  end
      |  $$display("PASS signed balanced publication");
      |  $$finish;
      |end
      |endmodule
      |""".stripMargin
    Files.write(testbench, text.getBytes(StandardCharsets.UTF_8))
    assert(Process(Seq("iverilog", "-g2001", "-s", "tb", "-o", executable.toString,
      directory.resolve("BalancedPublication.v").toString,
      referenceDirectory.resolve(module + ".v").toString, testbench.toString)).! == 0)
    val output = Process(Seq("vvp", executable.toString)).!!
    assert(output.contains("PASS signed balanced publication") && !output.contains("FAIL"), output)
  }
}
