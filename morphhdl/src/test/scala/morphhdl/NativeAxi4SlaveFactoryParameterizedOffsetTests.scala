package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.regex.Pattern

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config, Axi4SlaveFactory}

import morphhdl.frontend.{formalComponent, HdlInt}

/**
  * Ordinary SpinalHDL component. The register map is authored only through the
  * real spinal.lib.bus.amba4.axi.Axi4SlaveFactory.
  */
final class NativeAxi4SlaveFactoryRegisterBlock(offset: Int) extends Component {
  setDefinitionName("NativeAxi4SlaveFactoryRegisterBlock")

  private val config = Axi4Config(
    addressWidth = 12,
    dataWidth = 32,
    idWidth = 2
  )

  val io = new Bundle {
    val axi = slave(Axi4(config))
    val observedBase = out UInt (32 bits)
    val observedNext = out UInt (32 bits)
    val observedFixed = out UInt (32 bits)
  }

  val baseRegister = Reg(UInt(32 bits)) init (0)
  val nextRegister = Reg(UInt(32 bits)) init (0)
  val fixedRegister = Reg(UInt(32 bits)) init (0)

  io.observedBase := baseRegister
  io.observedNext := nextRegister
  io.observedFixed := fixedRegister

  val factory = Axi4SlaveFactory(io.axi)
  factory.readAndWrite(baseRegister, offset)
  factory.readAndWrite(nextRegister, offset + 4)
  factory.readAndWrite(fixedRegister, BigInt(0x080))
}

final class NativeAxi4SlaveFactoryParameterizedTop(actualOffset: HdlInt)
    extends Component {
  setDefinitionName("NativeAxi4SlaveFactoryParameterizedTop")

  private val config = Axi4Config(
    addressWidth = 12,
    dataWidth = 32,
    idWidth = 2
  )

  val io = new Bundle {
    val axi = slave(Axi4(config))
    val observedBase = out UInt (32 bits)
    val observedNext = out UInt (32 bits)
    val observedFixed = out UInt (32 bits)
  }

  val registers = formalComponent.parameter(
    actualOffset,
    "OFFSET",
    minimum = BigInt(0x010),
    maximum = BigInt(0x070)
  )(offset => new NativeAxi4SlaveFactoryRegisterBlock(offset))
  registers.setName("registers")

  io.axi <> registers.io.axi
  io.observedBase := registers.io.observedBase
  io.observedNext := registers.io.observedNext
  io.observedFixed := registers.io.observedFixed
}

class NativeAxi4SlaveFactoryParameterizedOffsetTests extends AnyFunSuite {
  private def component(default: Int = 0x040)
      : NativeAxi4SlaveFactoryParameterizedTop = {
    val offset = HdlInt.param(
      "TOP_OFFSET",
      default = BigInt(default),
      min = BigInt(0x010),
      max = BigInt(0x070)
    )
    new NativeAxi4SlaveFactoryParameterizedTop(offset)
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def exportArtifact(filename: String, content: String): Unit =
    sys.env.get("MORPHDL_NATIVE_AXI4_ARTIFACT_DIR").foreach { rawDirectory =>
      val directory = java.nio.file.Paths.get(rawDirectory)
      Files.createDirectories(directory)
      Files.write(
        directory.resolve(filename),
        content.getBytes(StandardCharsets.UTF_8)
      )
    }

  private def containsAddressLiteral(verilog: String, value: Int): Boolean = {
    val decimal = Pattern
      .compile(
        "(?<![A-Za-z0-9_])" + Pattern.quote(value.toString) +
          "(?![A-Za-z0-9_])"
      )
      .matcher(verilog)
      .find()
    val nativeHex = f"12'h$value%03x"
    decimal || verilog.toLowerCase.contains(nativeHex)
  }

  private def config(directory: Path, filename: String): SpinalConfig = {
    Files.createDirectories(directory)
    val result = SpinalConfig(targetDirectory = directory.toString)
    result.netlistFileName = filename
    result
  }

  test("real native Axi4SlaveFactory retains direct and derived parameterized offsets") {
    withTemporaryDirectory { directory =>
      val firstDirectory = directory.resolve("first")
      val secondDirectory = directory.resolve("second")
      val concreteDirectory = directory.resolve("concrete")
      val filename = "native_axi4_slave_factory_parameterized_offset.v"

      val firstReport = MorphVerilog(config(firstDirectory, filename))(component())
      MorphVerilog(config(secondDirectory, filename))(component())
      SpinalVerilog(config(concreteDirectory, filename))(component())

      val first = read(firstDirectory.resolve(filename))
      val second = read(secondDirectory.resolve(filename))
      val concrete = read(concreteDirectory.resolve(filename))

      exportArtifact("native_axi4_parameterized.v", first)
      exportArtifact("native_axi4_concrete_top.v", concrete)

      assert(first == second, "native factory parameterized emission is not deterministic")
      assert(firstReport.parameters.exists(_.name == "TOP_OFFSET"))
      assert(first.contains("module NativeAxi4SlaveFactoryRegisterBlock #("))
      assert(first.contains("parameter integer OFFSET = 64"))
      assert(first.contains(".OFFSET(TOP_OFFSET)"))
      assert(first.contains("OFFSET"))
      assert(
        first.contains("OFFSET + 4") ||
          first.contains("OFFSET+4") ||
          first.contains("(OFFSET + 4)")
      )

      // Signals below are generated by the native AXI4 slave factory path.
      assert(first.contains("io_axi_aw_ready"))
      assert(first.contains("io_axi_w_ready"))
      assert(first.contains("io_axi_b_valid"))
      assert(first.contains("io_axi_ar_ready"))
      assert(first.contains("io_axi_r_valid"))
      assert(first.contains("baseRegister"))
      assert(first.contains("nextRegister"))
      assert(first.contains("fixedRegister"))

      // The unrelated fixed mapping must remain an ordinary concrete address.
      assert(containsAddressLiteral(first, 0x080))
      assert(!first.contains("ParamRTL"))
      assert(!first.contains("class Axi4SlaveFactory"))

      assert(!concrete.contains("parameter integer OFFSET"))
      assert(!concrete.contains("parameter integer TOP_OFFSET"))
      assert(containsAddressLiteral(concrete, 0x040))
      assert(containsAddressLiteral(concrete, 0x044))
      assert(containsAddressLiteral(concrete, 0x080))
    }
  }

  test("ordinary concrete native Axi4SlaveFactory remains concrete") {
    withTemporaryDirectory { directory =>
      val filename = "native_axi4_slave_factory_concrete.v"
      val report = SpinalVerilog(config(directory, filename))(
        new NativeAxi4SlaveFactoryRegisterBlock(0x024)
      )
      val verilog = read(directory.resolve(filename))
      exportArtifact("native_axi4_concrete_block.v", verilog)
      assert(report.toplevelName == "NativeAxi4SlaveFactoryRegisterBlock")
      assert(!verilog.contains("parameter integer OFFSET"))
      assert(containsAddressLiteral(verilog, 0x024))
      assert(containsAddressLiteral(verilog, 0x028))
      assert(containsAddressLiteral(verilog, 0x080))
      assert(verilog.contains("io_axi_aw_ready"))
      assert(verilog.contains("io_axi_r_valid"))
    }
  }

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-native-axi4-offset-test-")
    try body(directory)
    finally {
      val stream = Files.walk(directory)
      try {
        stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach {
          path => Files.deleteIfExists(path)
        }
      } finally stream.close()
    }
  }
}
