package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}

import scala.collection.JavaConverters._
import scala.sys.process.ProcessLogger
import scala.util.matching.Regex

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib.{Flow, Stream, StreamFifo}

import morphhdl.frontend.HdlInt

class NativeLibraryReuseTests extends AnyFunSuite {
  private final class NativePipes(width: HdlInt) extends Component {
    setDefinitionName("NativePipes")

    val fixedLiteralInput = in(UInt(width bits))
    val fixedLiteralMatch = out(Bool())

    val streamInValid = in(Bool())
    val streamInReady = out(Bool())
    val streamInPayload = in(Bits(width bits))
    val streamOutValid = out(Bool())
    val streamOutReady = in(Bool())
    val streamOutPayload = out(Bits(width bits))

    val flowInValid = in(Bool())
    val flowInPayload = in(Bits(width bits))
    val flowOutValid = out(Bool())
    val flowOutPayload = out(Bits(width bits))

    fixedLiteralMatch := fixedLiteralInput === U(255, 8 bits)

    val stream = Stream(Bits(width bits))
    stream.valid := streamInValid
    stream.payload := streamInPayload
    streamInReady := stream.ready
    val streamPipe = stream.m2sPipe().s2mPipe().halfPipe()
    streamOutValid := streamPipe.valid
    streamPipe.ready := streamOutReady
    streamOutPayload := streamPipe.payload

    val flow = Flow(Bits(width bits))
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
    val pushPayload = in(Bits(width bits))
    val popValid = out(Bool())
    val popReady = in(Bool())
    val popPayload = out(Bits(width bits))
    val flush = in(Bool())

    val fifo = StreamFifo(
      HardType(Bits(width bits)),
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

  private final class NativeFromGray(width: HdlInt) extends Component {
    setDefinitionName("NativeFromGray")

    val gray = in(Bits(width bits))
    val binary = out(UInt(width bits))
    val decoded = spinal.lib.fromGray(gray).setName("decoded")
    binary := decoded
  }

  test("native Stream and Flow retain symbolic geometry externally") {
    inTemporaryDirectory { directory =>
      val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
      val parameterized = emitMorph(directory, "native_pipes.v", new NativePipes(width))
      val concrete = emitConcrete(directory, "native_pipes_concrete.v", new NativePipes(width))

      assert(
        module(concretize(parameterized, "NativePipes", 8), "NativePipes") ==
          module(concrete, "NativePipes")
      )
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
      assert(
        "(?m)^\\s*reg \\[WIDTH-1:0\\] [A-Za-z_][A-Za-z0-9_$]* \\[0:3\\];$".r
          .findFirstIn(parameterized)
          .nonEmpty
      )
      assert(hasWidth(parameterized, "pushPayload", "[WIDTH-1:0]"))
      assert(hasWidth(parameterized, "popPayload", "[WIDTH-1:0]"))
      assert(!concrete.contains("parameter integer WIDTH"))
      assert(concrete.contains("[7:0]") && concrete.contains("[0:3]"))
      compileOverride(directory, directory.resolve("native_static_fifo.v"), "NativeStaticFifo")
    }
  }

  test("native typed fromGray retains every prefix stage through WIDTH 65") {
    inTemporaryDirectory { directory =>
      val width = HdlInt.param("WIDTH", default = 33, min = 1, max = 65)
      val spinalConfig = config(directory)
      spinalConfig.netlistFileName = "native_from_gray.v"
      var top: NativeFromGray = null
      val report = MorphVerilog(spinalConfig) {
        top = new NativeFromGray(width)
        top
      }
      val parameterized = read(directory.resolve("native_from_gray.v"))

      val widthParameter = report.parameters.find(_.name == "WIDTH")
      assert(widthParameter.nonEmpty)
      assert(widthParameter.get.default == BigInt(33))
      assert(widthParameter.get.constraints == Vector(
        paramrtl.IntConstraint.MinInclusive(BigInt(1)),
        paramrtl.IntConstraint.MaxInclusive(BigInt(65))
      ))
      assert(top.decoded.getBitsWidth == 33)
      val decodedWidth = widthOfExpr(top.decoded)
      assert(decodedWidth.parameters.map(_.name) == Vector("WIDTH"))
      assert(decodedWidth.minimum == BigInt(1))
      assert(decodedWidth.maximum == BigInt(65))

      assert(parameterized.contains("parameter integer WIDTH = 33"))
      assert(hasWidth(parameterized, "gray", "[WIDTH-1:0]"))
      assert(hasWidth(parameterized, "binary", "[WIDTH-1:0]"))
      assert(!parameterized.contains("[32:0]"), parameterized)
      val compact = parameterized.replaceAll("\\s+", "")
      Vector(1, 2, 4, 8, 16, 32, 64).foreach { shift =>
        assert(
          compact.contains(s">>>$shift)"),
          s"typed fromGray omitted shift-$shift:\n$parameterized"
        )
      }
      val decodedDeclarations =
        """(?m)^\s*(?:wire|reg)\s+\[([^\]]+)\]\s+([A-Za-z_][A-Za-z0-9_$]*decoded[A-Za-z0-9_$]*)\s*;\s*$""".r
          .findAllMatchIn(parameterized)
          .map(value => value.group(1).replaceAll("\\s+", "") -> value.group(2))
          .toVector
      assert(decodedDeclarations.nonEmpty, parameterized)
      assert(
        decodedDeclarations.forall(_._1 == "WIDTH-1:0"),
        decodedDeclarations.mkString(", ")
      )

      val concrete = emitConcrete(
        directory,
        "native_from_gray_width_65.v",
        new NativeFromGray(HdlInt.literal(65))
      )
      assert(!concrete.contains("parameter integer WIDTH"), concrete)
      assert(hasWidth(concrete, "gray", "[64:0]"), concrete)
      assert(hasWidth(concrete, "binary", "[64:0]"), concrete)

      val mask = (BigInt(1) << 65) - 1
      def stagedDecode(grayValue: BigInt): BigInt = {
        var decoded = grayValue & mask
        var shift = 1
        while (shift < 65) {
          decoded = (decoded ^ (decoded >> shift)) & mask
          shift = shift << 1
        }
        decoded
      }
      Vector(
        BigInt(0),
        BigInt(1),
        BigInt(1) << 64,
        (BigInt(1) << 64) | (BigInt(1) << 41) | BigInt("123456789", 16),
        mask
      ).foreach { binaryValue =>
        val grayValue = binaryValue ^ (binaryValue >> 1)
        assert(stagedDecode(grayValue) == binaryValue)
      }

      if (commandAvailable("iverilog"))
        compileOverride(
          directory,
          directory.resolve("native_from_gray.v"),
          "NativeFromGray",
          width = 65
        )
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
    header
      .replaceFirstIn(verilog, s"module $name (")
      .replace("[WIDTH-1:0]", s"[${width - 1}:0]")
  }

  private def hasWidth(verilog: String, name: String, range: String): Boolean =
    (java.util.regex.Pattern.quote(range) + "\\s+" + java.util.regex.Pattern.quote(name) + "(?=\\s*(?:[,;]|\\)))").r
      .findFirstIn(verilog)
      .nonEmpty

  private def compileOverride(
      directory: Path,
      verilog: Path,
      top: String,
      width: Int = 5
  ): Unit = {
    val wrapper = directory.resolve(top + "Override.v")
    val source = s"""module ${top}Override;
      |  reg clk; reg reset;
      |  $top #(.WIDTH($width)) dut();
      |endmodule
      |""".stripMargin
    Files.write(wrapper, source.getBytes(StandardCharsets.UTF_8))
    val log = new StringBuilder
    val status = scala.sys.process
      .Process(
        Seq(
          "iverilog",
          "-g2001",
          "-s",
          top + "Override",
          "-o",
          directory.resolve("override.out").toString,
          verilog.toString,
          wrapper.toString
        ),
        directory.toFile
      )
      .!(ProcessLogger(line => log.append(line).append('\n')))
    assert(status == 0, log.toString)
  }

  private def commandAvailable(name: String): Boolean =
    sys.env
      .get("PATH")
      .toVector
      .flatMap(_.split(java.io.File.pathSeparator).toVector)
      .exists(directory => Files.isExecutable(Paths.get(directory).resolve(name)))

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
