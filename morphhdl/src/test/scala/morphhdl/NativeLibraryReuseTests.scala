package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._
import scala.sys.process.ProcessLogger
import scala.util.matching.Regex

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib.StreamFifo

import morphhdl.frontend.{Flow => MorphFlow, HdlInt, Stream => MorphStream}

class NativeLibraryReuseTests extends AnyFunSuite {
  private final class NativePipes(width: HdlInt) extends Component {
    setDefinitionName("NativePipes")

    val fixedLiteralInput = in(morphhdl.frontend.UInt(width bits))
    val fixedLiteralMatch = out(Bool())

    val streamInValid = in(Bool())
    val streamInReady = out(Bool())
    val streamInPayload = in(morphhdl.frontend.Bits(width bits))
    val streamOutValid = out(Bool())
    val streamOutReady = in(Bool())
    val streamOutPayload = out(morphhdl.frontend.Bits(width bits))

    val flowInValid = in(Bool())
    val flowInPayload = in(morphhdl.frontend.Bits(width bits))
    val flowOutValid = out(Bool())
    val flowOutPayload = out(morphhdl.frontend.Bits(width bits))

    fixedLiteralMatch := fixedLiteralInput === U(255, 8 bits)

    val stream = MorphStream(morphhdl.frontend.Bits(width bits))
    stream.valid := streamInValid
    stream.payload := streamInPayload
    streamInReady := stream.ready
    val streamPipe = stream.m2sPipe().s2mPipe().halfPipe()
    streamOutValid := streamPipe.valid
    streamPipe.ready := streamOutReady
    streamOutPayload := streamPipe.payload

    val flow = MorphFlow(morphhdl.frontend.Bits(width bits))
    flow.valid := flowInValid
    flow.payload := flowInPayload
    val flowPipe = flow.m2sPipe
    flowOutValid := flowPipe.valid
    flowOutPayload := flowPipe.payload
  }

  private final class NativeStaticFifo(width: HdlInt) extends Component {
    setDefinitionName("NativeStaticFifo")

    val pushValid = in(Bool())
    val pushReady = out(Bool())
    val pushPayload = in(morphhdl.frontend.Bits(width bits))
    val popValid = out(Bool())
    val popReady = in(Bool())
    val popPayload = out(morphhdl.frontend.Bits(width bits))
    val flush = in(Bool())

    val fifo = StreamFifo(
      morphhdl.frontend.HardType(morphhdl.frontend.Bits(width bits)),
      depth = 4,
      latency = 2
    )
    fifo.io.push.valid := pushValid
    fifo.io.push.payload := pushPayload
    pushReady := fifo.io.push.ready
    popValid := fifo.io.pop.valid
    fifo.io.pop.ready := popReady
    popPayload := fifo.io.pop.payload
    fifo.io.flush := flush
  }

  test("native Stream and Flow retain symbolic geometry externally") {
    inTemporaryDirectory { directory =>
      val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
      val parameterized = emitMorph(directory, "native_pipes.v", new NativePipes(width))
      val concrete = emitConcrete(directory, "native_pipes_concrete.v", new NativePipes(width))

      assert(module(concretize(parameterized, "NativePipes", 8), "NativePipes") ==
        module(concrete, "NativePipes"))
      assert(parameterized.contains("parameter integer WIDTH = 8"))
      Vector("fixedLiteralInput", "streamInPayload", "streamOutPayload", "flowInPayload", "flowOutPayload")
        .foreach(name => assert(hasWidth(parameterized, name, "[WIDTH-1:0]")))
      assert(parameterized.contains("always @(posedge clk)"))
      assert(parameterized.contains("(fixedLiteralInput == 8'hff)"))
      assert(parameterized.contains("streamInReady") && parameterized.contains("streamOutReady"))
      assert(!parameterized.contains("ParamRTL"))
      compileOverride(directory, directory.resolve("native_pipes.v"), "NativePipes")
    }
  }

  test("native static-depth StreamFifo retains only symbolic payload geometry") {
    inTemporaryDirectory { directory =>
      val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
      val parameterized = emitMorph(directory, "native_static_fifo.v", new NativeStaticFifo(width))
      val concrete = emitConcrete(directory, "native_static_fifo_concrete.v", new NativeStaticFifo(width))

      assert(parameterized.contains("parameter integer WIDTH = 8"))
      assert(!parameterized.contains("parameter integer DEPTH"))
      assert("(?m)^module StreamFifo #\\(".r.findAllMatchIn(parameterized).size == 1)
      assert(parameterized.contains(".WIDTH(WIDTH)"))
      assert("(?m)^\\s*reg \\[WIDTH-1:0\\] [A-Za-z_][A-Za-z0-9_$]* \\[0:3\\];$".r
        .findFirstIn(parameterized).nonEmpty)
      assert(hasWidth(parameterized, "pushPayload", "[WIDTH-1:0]"))
      assert(hasWidth(parameterized, "popPayload", "[WIDTH-1:0]"))
      assert(!concrete.contains("parameter integer WIDTH"))
      assert(concrete.contains("[7:0]") && concrete.contains("[0:3]"))
      compileOverride(directory, directory.resolve("native_static_fifo.v"), "NativeStaticFifo")
    }
  }

  private def config(directory: Path): SpinalConfig =
    SpinalConfig(
      targetDirectory = directory.toString,
      defaultConfigForClockDomains = ClockDomainConfig(
        clockEdge = RISING,
        resetKind = SYNC,
        resetActiveLevel = HIGH
      )
    )

  private def emitMorph(directory: Path, file: String, component: => Component): String = {
    val spinalConfig = config(directory)
    spinalConfig.netlistFileName = file
    MorphVerilog(spinalConfig)(component)
    read(directory.resolve(file))
  }

  private def emitConcrete(directory: Path, file: String, component: => Component): String = {
    val spinalConfig = config(directory)
    spinalConfig.netlistFileName = file
    SpinalVerilog(spinalConfig)(component)
    read(directory.resolve(file))
  }

  private def module(verilog: String, name: String): String = {
    val pattern = ("(?s)module " + java.util.regex.Pattern.quote(name) + "(?: #\\(.*?\\n\\))? \\(.*?endmodule").r
    pattern.findFirstIn(verilog).getOrElse(verilog)
  }

  private def concretize(verilog: String, name: String, width: Int): String = {
    val header: Regex = ("(?s)module " + java.util.regex.Pattern.quote(name) + " #\\(.*?\\n\\) \\(").r
    header.replaceFirstIn(verilog, s"module $name (")
      .replace("[WIDTH-1:0]", s"[${width - 1}:0]")
  }

  private def hasWidth(verilog: String, name: String, range: String): Boolean =
    (java.util.regex.Pattern.quote(range) + "\\s+" + java.util.regex.Pattern.quote(name) + "(?=\\s*(?:[,;]|\\)))").r
      .findFirstIn(verilog).nonEmpty

  private def compileOverride(directory: Path, verilog: Path, top: String): Unit = {
    val wrapper = directory.resolve(top + "Override.v")
    val source = s"""module ${top}Override;
      |  reg clk; reg reset;
      |  $top #(.WIDTH(5)) dut();
      |endmodule
      |""".stripMargin
    Files.write(wrapper, source.getBytes(StandardCharsets.UTF_8))
    val log = new StringBuilder
    val status = scala.sys.process.Process(
      Seq("iverilog", "-g2001", "-s", top + "Override", "-o", directory.resolve("override.out").toString,
        verilog.toString, wrapper.toString),
      directory.toFile
    ).!(ProcessLogger(line => log.append(line).append('\n')))
    assert(status == 0, log.toString)
  }

  private def read(path: Path): String = new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def inTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-native-library-")
    try body(directory)
    finally {
      val stream = Files.walk(directory)
      try stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
      finally stream.close()
    }
  }
}
