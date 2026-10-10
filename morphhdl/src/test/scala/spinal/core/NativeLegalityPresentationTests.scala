package spinal.core

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.sys.process.{Process, ProcessLogger}
import morphhdl.{MorphVerilog}
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite

/** Exercises the native diagnostic and its runtime termination, not a rewritten DUT. */
class NativeLegalityPresentationTests extends AnyFunSuite {
  private val message = "LIVE_LANES must be 1 or 4"
  private class Probe(text: String) extends Component {
    setDefinitionName("NativeLegalityPresentationProbe")
    private val lanes: ElabInt = HdlInt.param("LIVE_LANES", 1, 1, 4).asElabInt
    val inputBits = in Bits(lanes bits)
    val outputBits = out Bits(lanes bits)
    outputBits := inputBits
    if (text == null) require(lanes == 1 || lanes == 4)
    else require(lanes == 1 || lanes == 4, text)
  }
  private def emit(directory: Path, passes: Boolean, text: String = message): String = {
    val config = SpinalConfig(targetDirectory = directory.toString,
      oneFilePerComponent = true, headerWithDate = false)
    MorphVerilog(config)(new Probe(text))
    new String(Files.readAllBytes(directory.resolve("NativeLegalityPresentationProbe.v")), UTF_8)
  }
  private def run(directory: Path, name: String, command: Seq[String]): (Int, String) = {
    val output = new StringBuilder
    val code = Process(command, directory.toFile).!(ProcessLogger(
      line => output.append(line).append('\n'), line => output.append(line).append('\n')))
    Files.write(directory.resolve(name + ".log"), output.toString.getBytes(UTF_8))
    (code, output.toString)
  }

  for (passes <- Seq(false, true)) test(s"native legality presentation and simulation with passes=$passes") {
    val directory = Files.createTempDirectory("cdc-legality-")
    val rtl = emit(directory, passes)
    assert(rtl == emit(Files.createTempDirectory("cdc-legality-repeat-"), passes))
    assert(rtl.contains("begin : G_PARAMETER_LEGALITY_0"))
    assert(!rtl.contains("g_morphhdl_parameter_legality"))
    assert(rtl.contains("initial $fatal(1, \"%s\", \"" + message + "\");"))
    assert(!rtl.contains("MorphHDL parameter legality failed:"))
    val source = directory.resolve("NativeLegalityPresentationProbe.v").toString
    for (lanes <- 1 to 4; synthesis <- Seq(false, true)) {
      val tag = s"lanes-$lanes-synthesis-$synthesis"
      val wrapper = directory.resolve("tb.v")
      Files.write(wrapper, s"""module tb;
        |reg [$lanes-1:0] din;
        |wire [$lanes-1:0] dout;
        |NativeLegalityPresentationProbe #(.LIVE_LANES($lanes)) dut(din, dout);
        |initial begin
        |din = {$lanes{1'bx}}; #1;
        |if (dout !== din) $$fatal(1,"X propagation");
        |din = {$lanes{1'bz}}; #1;
        |if (dout !== din) $$fatal(1,"Z propagation");
        |din = {$lanes{1'b1}}; #1;
        |if (dout !== din) $$fatal(1,"data propagation");
        |$$display("LEGALITY_DATA_PASS"); $$finish;
        |end
        |endmodule
        |""".stripMargin.getBytes(UTF_8))
      val flags = if (synthesis) Seq("-DSYNTHESIS") else Seq.empty
      val compiled = run(directory, tag + "-compile", Seq("iverilog", "-g2012") ++ flags ++
        Seq("-s", "tb", "-o", "test.vvp", source, wrapper.toString))
      assert(compiled._1 == 0, compiled._2)
      val simulated = run(directory, tag + "-run", Seq("vvp", "test.vvp"))
      if (!synthesis && (lanes == 2 || lanes == 3)) {
        assert(simulated._1 != 0 && simulated._2.contains(message), simulated._2)
        assert(simulated._2.split(message, -1).length == 2, simulated._2)
        assert(!simulated._2.contains("LEGALITY_DATA_PASS"))
      } else assert(simulated._1 == 0 && simulated._2.contains("LEGALITY_DATA_PASS"), simulated._2)
    }
    assert(run(directory, "strict", Seq("iverilog", "-g2001", "-DSYNTHESIS",
      "-s", "NativeLegalityPresentationProbe", "-o", "strict.vvp", source))._1 == 0)
    assert(run(directory, "lint", Seq("verilator", "--lint-only", "--language", "1364-2001",
      "-DSYNTHESIS", "--top-module", "NativeLegalityPresentationProbe", source))._1 == 0)
    assert(run(directory, "synthesis", Seq("yosys", "-Q", "-p",
      s"read_verilog -DSYNTHESIS $source; synth -top NativeLegalityPresentationProbe; check -assert"))._1 == 0)
  }

  test("native messages retain empty text and literal escaping") {
    for ((message, escaped) <- Seq[(String, String)]((null, "requirement failed"), "" -> "", "quoted \"value\" \\ %s %d %m\nnext\tline" ->
      "quoted \\\"value\\\" \\\\ %s %d %m\\nnext\\tline")) {
      val directory = Files.createTempDirectory("cdc-legality-message-")
      val rtl = emit(directory, false, message)
      assert(rtl.contains("initial $fatal(1, \"%s\", \"" + escaped + "\");"))
      val compiled = run(directory, "message-compile", Seq("iverilog", "-g2012",
        "-s", "NativeLegalityPresentationProbe", "-PNativeLegalityPresentationProbe.LIVE_LANES=2",
        "-o", "message.vvp", "NativeLegalityPresentationProbe.v"))
      assert(compiled._1 == 0, compiled._2)
      val simulated = run(directory, "message-run", Seq("vvp", "message.vvp"))
      assert(simulated._1 != 0 && simulated._2.contains(Option(message).getOrElse("requirement failed")), simulated._2)
      assert(!simulated._2.contains("MorphHDL parameter legality failed:"))
    }
  }

  test("multiple obligations retain order and avoid user names in sibling scopes") {
    val directory = Files.createTempDirectory("cdc-legality-owners-")
    class Child(lanes: ElabInt) extends Component {
      val inputBits = in Bits(lanes bits)
      val outputBits = out Bits(lanes bits)
      outputBits := inputBits
      // This is a user identity: the generated block must allocate around it.
      val occupied = out Bool()
      occupied.setName("G_PARAMETER_LEGALITY_0")
      occupied := inputBits.orR
      ElabControl.requireCondition(lanes.elabNe(2), "first constraint", "probe", 1)
      ElabControl.requireCondition(lanes.elabNe(3), "second constraint", "probe", 2)
    }
    MorphVerilog(SpinalConfig(targetDirectory = directory.toString,
      oneFilePerComponent = true, headerWithDate = false))(new Component {
      val lanes = HdlInt.param("LIVE_LANES", 1, 1, 4).asElabInt
      val inputBits = in Bits(lanes bits)
      val first, second = out Bits(lanes bits)
      val status = out Bits(2 bits)
      val a = new Child(lanes)
      val b = new Child(lanes)
      a.inputBits := inputBits
      b.inputBits := inputBits
      first := a.outputBits
      second := b.outputBits
      status(0) := a.occupied
      status(1) := b.occupied
    })
    import scala.collection.JavaConverters._
    val paths = Files.list(directory)
    val modules = try paths.iterator().asScala.filter(_.toString.endsWith(".v"))
      .map(p => new String(Files.readAllBytes(p), UTF_8)).toVector finally paths.close()
    val children = modules.filter(_.contains("first constraint"))
    assert(children.nonEmpty)
    children.foreach { rtl =>
      val labels = "begin : (G_PARAMETER_LEGALITY_[A-Za-z0-9_]+)".r
        .findAllMatchIn(rtl).map(_.group(1)).toVector
      assert(labels.size == 2 && labels.distinct.size == 2)
      assert(!labels.contains("G_PARAMETER_LEGALITY_0"))
      assert(rtl.indexOf("first constraint") < rtl.indexOf("second constraint"))
      assert(rtl.contains("G_PARAMETER_LEGALITY_0"))
    }
  }
}
