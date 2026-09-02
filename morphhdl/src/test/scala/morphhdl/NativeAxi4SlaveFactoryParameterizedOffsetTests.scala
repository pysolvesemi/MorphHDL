package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.regex.Pattern

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._
import spinal.lib.bus.amba3.ahblite.{AhbLite3, AhbLite3Config, AhbLite3SlaveFactory}
import spinal.lib.bus.amba3.apb.{Apb3, Apb3Config, Apb3SlaveFactory}
import spinal.lib.bus.amba4.axi.{Axi4, Axi4Config, Axi4SlaveFactory}
import spinal.lib.bus.bram.{BRAM, BRAMConfig, BRAMSlaveFactory}
import spinal.lib.bus.misc.ElabIntSingleMapping
import spinal.lib.bus.wishbone.{AddressGranularity, Wishbone, WishboneConfig, WishboneSlaveFactory}

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

/** Exercises the native BusSlaveFactory address wrapper so its fixed offset is
  * applied before the underlying factory validates the effective typed
  * address.
  */
final class NativeAxi4SlaveFactoryAddressWrapperTop(
    address: ElabInt,
    addressOffset: BigInt
) extends Component {
  setDefinitionName("NativeAxi4SlaveFactoryAddressWrapperTop")

  private val config = Axi4Config(
    addressWidth = 12,
    dataWidth = 32,
    idWidth = 2
  )

  val io = new Bundle {
    val axi = slave(Axi4(config))
    val observed = out UInt (32 bits)
  }

  val register = Reg(UInt(32 bits)) init (0)
  io.observed := register
  Axi4SlaveFactory(io.axi)
    .withOffset(addressOffset)
    .readAndWrite(register, address)
}

/** Full-address APB decoding admits byte addresses independently of bus word
  * width. The same top also provides a literal typed-versus-BigInt parity
  * witness.
  */
final class NativeApb3SlaveFactoryTypedAddressTop(
    address: ElabInt,
    useTypedLiteral: Option[Boolean] = None
) extends Component {
  setDefinitionName("NativeApb3SlaveFactoryTypedAddressTop")

  val io = new Bundle {
    val apb = slave(Apb3(Apb3Config(addressWidth = 12, dataWidth = 32)))
    val observed = out UInt (32 bits)
  }

  val register = Reg(UInt(32 bits)) init (0)
  io.observed := register
  val factory = Apb3SlaveFactory(io.apb)
  useTypedLiteral match {
    case Some(true)  => factory.readAndWrite(register, ElabInt.literal(1))
    case Some(false) => factory.readAndWrite(register, BigInt(1))
    case None        => factory.readAndWrite(register, address)
  }
}

/** These delayed factories historically optimized only SingleMapping. The
  * generic typed mapping must use their native delayed read/write timing too.
  */
final class NativeBramSlaveFactoryTypedAddressTop(address: ElabInt)
    extends Component {
  setDefinitionName("NativeBramSlaveFactoryTypedAddressTop")

  val io = new Bundle {
    val bus = slave(BRAM(BRAMConfig(dataWidth = 32, addressWidth = 12)))
    val observed = out UInt (32 bits)
    val observedReadEvent = out Bool ()
    val observedWriteEvent = out Bool ()
  }

  val register = Reg(UInt(32 bits)) init (0)
  val readEvent = Reg(Bool()) init (False)
  val writeEvent = Reg(Bool()) init (False)
  io.observed := register
  io.observedReadEvent := readEvent
  io.observedWriteEvent := writeEvent

  val factory = BRAMSlaveFactory(io.bus)
  factory.readAndWrite(register, address)
  factory.onRead(address) { readEvent := True }
  factory.onWrite(address) { writeEvent := True }
}

final class NativeAhbLite3SlaveFactoryTypedAddressTop(address: ElabInt)
    extends Component {
  setDefinitionName("NativeAhbLite3SlaveFactoryTypedAddressTop")

  val io = new Bundle {
    val bus = slave(AhbLite3(AhbLite3Config(addressWidth = 12, dataWidth = 32)))
    val observed = out UInt (32 bits)
    val observedReadEvent = out Bool ()
    val observedWriteEvent = out Bool ()
  }

  val register = Reg(UInt(32 bits)) init (0)
  val readEvent = Reg(Bool()) init (False)
  val writeEvent = Reg(Bool()) init (False)
  io.observed := register
  io.observedReadEvent := readEvent
  io.observedWriteEvent := writeEvent

  val factory = AhbLite3SlaveFactory(io.bus)
  factory.readAndWrite(register, address)
  factory.onRead(address) { readEvent := True }
  factory.onWrite(address) { writeEvent := True }
}

final class NativeWishboneSlaveFactoryTypedAddressTop(
    address: ElabInt,
    granularity: AddressGranularity.AddressGranularity
) extends Component {
  setDefinitionName("NativeWishboneSlaveFactoryTypedAddressTop")

  private val busConfig = WishboneConfig(
    addressWidth = 12,
    dataWidth = 24,
    addressGranularity = granularity
  )
  val io = new Bundle {
    val bus = slave(Wishbone(busConfig))
    val observed = out Bits (24 bits)
  }

  val register = Reg(Bits(24 bits)) init (0)
  io.observed := register
  WishboneSlaveFactory(io.bus).readAndWrite(register, address)
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

  test("native address wrapper preserves and revalidates the effective typed address") {
    withTemporaryDirectory { directory =>
      val filename = "native_axi4_slave_factory_wrapped.v"
      MorphVerilog(config(directory.resolve("aligned"), filename)) {
        val word = HdlInt
          .param("WRAPPED_WORD", default = 4, min = 4, max = 8)
          .asElabInt
        new NativeAxi4SlaveFactoryAddressWrapperTop(word * 4, 4)
      }
      val verilog = read(directory.resolve("aligned").resolve(filename))
      assert(verilog.contains("parameter integer WRAPPED_WORD = 4"))
      assert(verilog.sliding("WRAPPED_WORD".length).count(_ == "WRAPPED_WORD") >= 2)

      val failure = intercept[MorphVerilogException] {
        MorphVerilog(config(directory.resolve("unaligned"), "unaligned.v")) {
          val word = HdlInt
            .param("WRAPPED_UNALIGNED_WORD", default = 4, min = 4, max = 8)
            .asElabInt
          new NativeAxi4SlaveFactoryAddressWrapperTop(word * 4, 2)
        }
      }
      val error = causeChain(failure).collectFirst {
        case value: ParameterizedVerilogException => value
      }.getOrElse(fail("missing wrapped typed-address failure"))
      assert(
        error.code == "SPINAL-PARAMETERIZED-VERILOG-BUS-ADDRESS-UNALIGNED"
      )
      assert(error.detail.contains("4-byte address boundary"))
    }
  }

  test("typed APB addresses preserve full byte-address decoding and literal parity") {
    withTemporaryDirectory { directory =>
      val literalFilename = "native_apb3_slave_factory_literal.v"
      val typedDirectory = directory.resolve("typed-literal")
      val concreteDirectory = directory.resolve("concrete-literal")
      SpinalVerilog(config(typedDirectory, literalFilename))(
        new NativeApb3SlaveFactoryTypedAddressTop(
          ElabInt.literal(1),
          useTypedLiteral = Some(true)
        )
      )
      SpinalVerilog(config(concreteDirectory, literalFilename))(
        new NativeApb3SlaveFactoryTypedAddressTop(
          ElabInt.literal(1),
          useTypedLiteral = Some(false)
        )
      )
      assert(
        read(typedDirectory.resolve(literalFilename)) ==
          read(concreteDirectory.resolve(literalFilename)),
        "typed APB literal must delegate to the byte-identical BigInt mapping"
      )

      val symbolicFilename = "native_apb3_slave_factory_parameterized.v"
      val firstDirectory = directory.resolve("symbolic-first")
      val secondDirectory = directory.resolve("symbolic-second")
      val first = emitTypedFactory(firstDirectory, symbolicFilename, "APB_OFFSET") {
        address => new NativeApb3SlaveFactoryTypedAddressTop(address)
      }
      val second = emitTypedFactory(secondDirectory, symbolicFilename, "APB_OFFSET") {
        address => new NativeApb3SlaveFactoryTypedAddressTop(address)
      }
      assert(first == second)
      assert(first.contains("parameter integer APB_OFFSET = 1"))
      assert(first.sliding("APB_OFFSET".length).count(_ == "APB_OFFSET") >= 2)
      assert(first.contains("io_apb_PADDR"))
    }
  }

  test("BRAM and AHB delayed factories consume exact typed mappings") {
    withTemporaryDirectory { directory =>
      val cases = Vector[(String, ElabInt => Component)](
        "BRAM_OFFSET" -> ((address: ElabInt) =>
          new NativeBramSlaveFactoryTypedAddressTop(address)),
        "AHB_OFFSET" -> ((address: ElabInt) =>
          new NativeAhbLite3SlaveFactoryTypedAddressTop(address))
      )

      cases.foreach { case (parameter, build) =>
        val filename = s"native_${parameter.toLowerCase}_slave_factory.v"
        val first = emitTypedFactory(
          directory.resolve(s"$parameter-first"),
          filename,
          parameter
        )(build)
        val second = emitTypedFactory(
          directory.resolve(s"$parameter-second"),
          filename,
          parameter
        )(build)
        assert(first == second)
        assert(first.contains(s"parameter integer $parameter = 1"))
        assert(first.sliding(parameter.length).count(_ == parameter) >= 2)
        assert(first.contains("observedReadEvent"))
        assert(first.contains("observedWriteEvent"))
      }
    }
  }

  test("Wishbone typed alignment follows the exact byte-address shift") {
    withTemporaryDirectory { directory =>
      val byteFilename = "native_wishbone_byte_granularity.v"
      val byteVerilog = emitTypedFactory(
        directory.resolve("byte"),
        byteFilename,
        "WISHBONE_BYTE_OFFSET"
      ) { address =>
        new NativeWishboneSlaveFactoryTypedAddressTop(
          address,
          AddressGranularity.BYTE
        )
      }
      assert(byteVerilog.contains("parameter integer WISHBONE_BYTE_OFFSET = 1"))

      val wordFilename = "native_wishbone_word_granularity.v"
      MorphVerilog(config(directory.resolve("word"), wordFilename)) {
        val word = HdlInt
          .param("WISHBONE_WORD", default = 1, min = 1, max = 2)
          .asElabInt
        new NativeWishboneSlaveFactoryTypedAddressTop(
          word * 4,
          AddressGranularity.WORD
        )
      }
      val wordVerilog = read(directory.resolve("word").resolve(wordFilename))
      assert(wordVerilog.replaceAll("\\s+", "").contains("WISHBONE_WORD*4"))

      val failure = intercept[MorphVerilogException] {
        MorphVerilog(config(directory.resolve("word-unaligned"), "unaligned.v")) {
          val address = HdlInt
            .param("WISHBONE_UNALIGNED", default = 3, min = 3, max = 3)
            .asElabInt
          new NativeWishboneSlaveFactoryTypedAddressTop(
            address,
            AddressGranularity.WORD
          )
        }
      }
      val error = causeChain(failure).collectFirst {
        case value: ParameterizedVerilogException => value
      }.getOrElse(fail("missing Wishbone typed-alignment failure"))
      assert(
        error.code == "SPINAL-PARAMETERIZED-VERILOG-BUS-ADDRESS-UNALIGNED"
      )
      assert(error.detail.contains("4-byte address boundary"))
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
      assert(unaligned.detail.contains("4-byte address boundary"))

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

  private def emitTypedFactory(
      directory: Path,
      filename: String,
      parameter: String
  )(build: ElabInt => Component): String = {
    MorphVerilog(config(directory, filename)) {
      val address = HdlInt
        .param(parameter, default = 1, min = 1, max = 3)
        .asElabInt
      build(address)
    }
    read(directory.resolve(filename))
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
