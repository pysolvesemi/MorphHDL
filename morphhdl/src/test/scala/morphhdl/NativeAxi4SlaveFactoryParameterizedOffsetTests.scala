package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.regex.Pattern

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config, Axi4SlaveFactory}
import spinal.lib.bus.misc.ElabIntSingleMapping

import morphhdl.frontend.HdlInt

/** Ordinary application-shaped SpinalHDL source using the real
  * [[Axi4SlaveFactory]] and its explicit typed address overloads.
  */
final class NativeAxi4SlaveFactoryParameterizedTop(offset: ElabInt)
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
    val observedReadEvent = out Bool ()
    val observedWriteEvent = out Bool ()
  }

  val baseRegister = Reg(UInt(32 bits)) init (0)
  val nextRegister = Reg(UInt(32 bits)) init (0)
  val fixedRegister = Reg(UInt(32 bits)) init (0)
  val readEvent = Reg(Bool()) init (False)
  val writeEvent = Reg(Bool()) init (False)

  io.observedBase := baseRegister
  io.observedNext := nextRegister
  io.observedFixed := fixedRegister
  io.observedReadEvent := readEvent
  io.observedWriteEvent := writeEvent

  val factory = Axi4SlaveFactory(io.axi)
  factory.write(baseRegister, offset)
  factory.read(baseRegister, offset)
  factory.readAndWrite(nextRegister, offset + 4)
  val eventOffset = offset + 8
  factory.onRead(eventOffset) { readEvent := True }
  factory.onWrite(eventOffset) { writeEvent := True }
  factory.readAndWrite(fixedRegister, BigInt(0x080))
}

/** Deliberately maps two different signals through independently derived,
  * exact-equivalent typed addresses. The factory must group the mappings and
  * report its ordinary overlapping-read diagnostic.
  */
final class NativeAxi4SlaveFactoryEquivalentDoubleReadTop(
    offsetWord: ElabInt
) extends Component {
  setDefinitionName("NativeAxi4SlaveFactoryEquivalentDoubleReadTop")

  private val config = Axi4Config(
    addressWidth = 12,
    dataWidth = 32,
    idWidth = 2
  )

  val io = new Bundle {
    val axi = slave(Axi4(config))
  }

  val first = Reg(UInt(8 bits)) init (0)
  val second = Reg(UInt(8 bits)) init (0)
  val factory = Axi4SlaveFactory(io.axi)
  factory.read(first, offsetWord * 4)
  factory.read(second, offsetWord * 4)
}

class NativeAxi4SlaveFactoryParameterizedOffsetTests extends AnyFunSuite {
  private val ConcreteWitnessOffsets = Vector(0x010, 0x040, 0x070)

  private def component(default: Int = 0x040)
      : NativeAxi4SlaveFactoryParameterizedTop = {
    require(default % 4 == 0)
    val offsetWord = HdlInt.param(
      "OFFSET_WORD",
      default = BigInt(default / 4),
      min = BigInt(4),
      max = BigInt(28)
    )
    new NativeAxi4SlaveFactoryParameterizedTop(offsetWord.asElabInt * 4)
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
      val filename = "native_axi4_slave_factory_parameterized_offset.v"

      val firstReport = MorphVerilog(config(firstDirectory, filename))(component())
      MorphVerilog(config(secondDirectory, filename))(component())

      val concreteByOffset = ConcreteWitnessOffsets.map { offset =>
        val concreteDirectory = directory.resolve(s"concrete-offset-$offset")
        SpinalVerilog(config(concreteDirectory, filename))(
          new NativeConcreteAxi4SlaveFactoryTop(offset)
        )
        offset -> read(concreteDirectory.resolve(filename))
      }.toMap

      val first = read(firstDirectory.resolve(filename))
      val second = read(secondDirectory.resolve(filename))
      val concrete = concreteByOffset(0x040)

      exportArtifact("native_axi4_parameterized.v", first)
      exportArtifact("native_axi4_concrete_top.v", concrete)
      concreteByOffset.foreach { case (offset, verilog) =>
        exportArtifact(
          s"native_axi4_concrete_top_offset_$offset.v",
          verilog
        )
      }

      assert(first == second, "native factory parameterized emission is not deterministic")
      assert(firstReport.parameters.exists(_.name == "OFFSET_WORD"))
      assert(first.contains("module NativeAxi4SlaveFactoryParameterizedTop #("))
      assert(first.contains("parameter integer OFFSET_WORD = 16"))
      assert(containsMultipliedOffset(first))
      assert(containsDerivedByteOffset(first, 4))
      assert(containsDerivedByteOffset(first, 8))

      // Signals below are generated by the native AXI4 slave factory path.
      assert(first.contains("io_axi_aw_ready"))
      assert(first.contains("io_axi_w_ready"))
      assert(first.contains("io_axi_b_valid"))
      assert(first.contains("io_axi_ar_ready"))
      assert(first.contains("io_axi_r_valid"))
      assert(first.contains("baseRegister"))
      assert(first.contains("nextRegister"))
      assert(first.contains("fixedRegister"))
      assert(first.contains("readEvent"))
      assert(first.contains("writeEvent"))

      // The unrelated fixed mapping must remain an ordinary concrete address.
      assert(containsAddressLiteral(first, 0x080))
      assert(!first.contains("ParamRTL"))
      assert(!first.contains("class Axi4SlaveFactory"))

      concreteByOffset.foreach { case (offset, verilog) =>
        assert(!verilog.contains("parameter integer OFFSET_WORD"))
        assert(containsAddressLiteral(verilog, offset))
        assert(containsAddressLiteral(verilog, offset + 4))
        assert(containsAddressLiteral(verilog, offset + 8))
        assert(containsAddressLiteral(verilog, 0x080))
        assert(verilog.contains("io_axi_aw_ready"))
        assert(verilog.contains("io_axi_r_valid"))
      }
      assert(
        concreteByOffset.values.toSet.size == ConcreteWitnessOffsets.size,
        "native factory concrete witnesses were not independently specialized"
      )
    }
  }

  test("ordinary concrete native Axi4SlaveFactory remains concrete") {
    withTemporaryDirectory { directory =>
      val filename = "native_axi4_slave_factory_concrete.v"
      val report = SpinalVerilog(config(directory, filename))(
        new NativeConcreteAxi4SlaveFactoryTop(0x024)
      )
      val verilog = read(directory.resolve(filename))
      exportArtifact("native_axi4_concrete_block.v", verilog)
      assert(report.toplevelName == "NativeConcreteAxi4SlaveFactoryTopOffset36")
      assert(!verilog.contains("parameter integer OFFSET_WORD"))
      assert(containsAddressLiteral(verilog, 0x024))
      assert(containsAddressLiteral(verilog, 0x028))
      assert(containsAddressLiteral(verilog, 0x02c))
      assert(containsAddressLiteral(verilog, 0x080))
      assert(verilog.contains("io_axi_aw_ready"))
      assert(verilog.contains("io_axi_r_valid"))
    }
  }

  test("typed factory rejects every unsafe admitted address domain") {
    withTemporaryDirectory { directory =>
      val negative = expectTypedAddressFailure(
        directory.resolve("negative"),
        "negative.v",
        "SPINAL-PARAMETERIZED-VERILOG-BUS-ADDRESS-DOMAIN-NEGATIVE"
      ) {
        HdlInt
          .param("NEGATIVE_WORD", default = 0, min = -1, max = 4)
          .asElabInt * 4
      }
      assert(negative.detail.contains("negative byte address"))

      val unaligned = expectTypedAddressFailure(
        directory.resolve("unaligned"),
        "unaligned.v",
        "SPINAL-PARAMETERIZED-VERILOG-BUS-ADDRESS-UNALIGNED"
      ) {
        HdlInt
          .param("UNALIGNED_BYTE", default = 64, min = 64, max = 65)
          .asElabInt
      }
      assert(unaligned.detail.contains("4-byte bus word"))

      val tooWide = expectTypedAddressFailure(
        directory.resolve("too-wide"),
        "too_wide.v",
        "SPINAL-PARAMETERIZED-VERILOG-BUS-ADDRESS-WIDTH-INSUFFICIENT"
      ) {
        HdlInt
          .param("TOO_WIDE_WORD", default = 1023, min = 1023, max = 1025)
          .asElabInt * 4
      }
      assert(tooWide.detail.contains("12-bit bus address"))
    }
  }

  test("symbolic mappings fail closed for concrete-only operations") {
    val offsetWord =
      HdlInt.param("MAPPING_WORD", default = 4, min = 4, max = 5)
    val mapping = ElabIntSingleMapping(offsetWord.asElabInt * 4)
    val operations = Vector[() => Unit](
      () => { mapping.hit(BigInt(16)); () },
      () => { mapping.randomPick(); () },
      () => mapping.foreach(_ => ())
    )

    operations.foreach { operation =>
      val error = intercept[ParameterizedVerilogException] {
        operation()
      }
      assert(
        error.code ==
          "SPINAL-PARAMETERIZED-VERILOG-BUS-ADDRESS-CONCRETE-OPERATION-SYMBOLIC"
      )
    }
  }

  test("exact-equivalent typed mappings group and retain double-read safety") {
    val offsetWord =
      HdlInt.param("GROUPED_WORD", default = 16, min = 4, max = 28)
    val first = ElabIntSingleMapping(offsetWord.asElabInt * 4)
    val second = ElabIntSingleMapping(offsetWord.asElabInt * 4)
    val shifted = ElabIntSingleMapping(offsetWord.asElabInt * 4 + 4)
    assert(first == second)
    assert(first.hashCode == second.hashCode)
    assert(first != shifted)

    withTemporaryDirectory { directory =>
      val failure = intercept[MorphVerilogException] {
        MorphVerilog(config(directory, "equivalent_double_read.v")) {
          val word = HdlInt
            .param("DOUBLE_READ_WORD", default = 16, min = 4, max = 28)
            .asElabInt
          new NativeAxi4SlaveFactoryEquivalentDoubleReadTop(word)
        }
      }
      val messages = causeChain(failure).flatMap(error => Option(error.getMessage))
      assert(messages.exists(_.contains("BusSlaveFactory DOUBLE-READ-ERROR")))
      assert(!causeChain(failure).exists(_.isInstanceOf[ClassCastException]))
    }
  }

  private def containsMultipliedOffset(verilog: String): Boolean =
    verilog.replaceAll("\\s+", "").contains("OFFSET_WORD*4")

  private def containsDerivedByteOffset(
      verilog: String,
      addend: Int
  ): Boolean = {
    val compact = verilog.replaceAll("\\s+", "")
    compact.contains(s"(OFFSET_WORD*4)+$addend")
  }

  private def expectTypedAddressFailure(
      directory: Path,
      filename: String,
      expectedCode: String
  )(address: => ElabInt): ParameterizedVerilogException = {
    val failure = intercept[MorphVerilogException] {
      MorphVerilog(config(directory, filename)) {
        new NativeAxi4SlaveFactoryParameterizedTop(address)
      }
    }
    val error = causeChain(failure).collectFirst {
      case value: ParameterizedVerilogException => value
    }.getOrElse {
      fail(s"missing typed-address failure $expectedCode in ${failure.failure}")
    }
    assert(error.code == expectedCode)
    assert(!Files.exists(directory.resolve(filename)))
    error
  }

  private def causeChain(error: Throwable): Vector[Throwable] = {
    Iterator
      .iterate(Option(error))(_.flatMap(value => Option(value.getCause)))
      .takeWhile(_.nonEmpty)
      .flatten
      .toVector
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
