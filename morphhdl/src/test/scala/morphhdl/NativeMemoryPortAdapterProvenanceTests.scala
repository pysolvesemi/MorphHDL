package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite

import spinal.core._
import spinal.lib._

import morphhdl.frontend.HdlInt

private object NativeMemoryPortAdapterProvenanceFixture {
  final class ExactNativeAdapters(depth: HdlInt) extends Component {
    setDefinitionName("ExactNativeMemoryPortAdapters")

    val readEnable = in(Bool())
    val writeEnable = in(Bool())
    val readAddress = in(morphhdl.frontend.UInt(depth.addressWidth bits))
    val writeAddress = in(morphhdl.frontend.UInt(depth.addressWidth bits))
    val writeData = in(spinal.core.Bits(8 bits))
    val readData = out(spinal.core.Bits(8 bits))

    val memory = morphhdl.frontend
      .Mem(
        morphhdl.frontend.HardType(spinal.core.Bits(8 bits)),
        depth
      )
      .setName("adapter_memory")

    val write = memory.writePort()
    write.valid := writeEnable
    write.address := writeAddress
    write.data := writeData

    val read = memory.readSyncPort
    read.cmd.valid := readEnable
    read.cmd.payload := readAddress
    readData := read.rsp
  }

  final class CallerConstructedFixedAdapters(depth: HdlInt)
      extends Component {
    setDefinitionName("CallerConstructedFixedMemoryPortAdapters")

    val readEnable = in(Bool())
    val writeEnable = in(Bool())
    val readAddress = in(spinal.core.UInt(3 bits))
    val writeAddress = in(spinal.core.UInt(3 bits))
    val writeData = in(spinal.core.Bits(8 bits))
    val readData = out(spinal.core.Bits(8 bits))

    val memory = morphhdl.frontend
      .Mem(
        morphhdl.frontend.HardType(spinal.core.Bits(8 bits)),
        depth
      )
      .setName("fixed_adapter_memory")

    // These look like the internals of MemPimped's helpers but are owned by
    // this caller. The typed plugin must not infer provenance from constructor
    // spelling, concrete width, field shape or the shared Mem object alone.
    val write = Flow(MemWriteCmd(memory))
    write.valid := writeEnable
    write.address := writeAddress
    write.data := writeData
    when(write.valid) {
      memory.write(write.address, write.data)
    }

    val read = MemReadPort(memory.wordType(), memory.addressWidth)
    read.cmd.valid := readEnable
    read.cmd.payload := readAddress
    read.rsp := memory.readSync(read.cmd.payload, read.cmd.valid)
    readData := read.rsp
  }

  final class AdapterDataReusedAsAddress(depth: HdlInt) extends Component {
    setDefinitionName("NativeMemoryAdapterCrossLeafAddress")

    val readEnable = in(Bool())
    val writeEnable = in(Bool())
    val writeAddress = in(spinal.core.UInt(3 bits))
    val writeData = in(spinal.core.UInt(3 bits))
    val readData = out(spinal.core.UInt(3 bits))

    val memory = morphhdl.frontend
      .Mem(
        morphhdl.frontend.HardType(spinal.core.UInt(3 bits)),
        depth
      )
      .setName("cross_leaf_memory")

    val write = memory.writePort()
    write.valid := writeEnable
    write.address := writeAddress
    write.data := writeData

    // `write.data` is a UInt leaf of the captured adapter, but it is not the
    // exact address of the MemWrite created by writePort. Reusing it for a new
    // port must therefore retain its ordinary fixed three-bit width.
    readData := memory.readSync(
      write.data,
      enable = readEnable,
      readUnderWrite = readFirst
    )
  }
}

final class NativeMemoryPortAdapterProvenanceTests extends AnyFunSuite {
  import NativeMemoryPortAdapterProvenanceFixture._

  test("typed native memory adapters retain the exact symbolic address boundary") {
    withTemporaryDirectory { directory =>
      // A fixed three-bit witness cannot conservatively cover max=9. Passing
      // therefore requires exact typed adapter provenance, not width luck.
      val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 9)
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "native_memory_port_adapters.v"
      MorphVerilog(config)(new ExactNativeAdapters(depth))
      val verilog = read(directory.resolve("native_memory_port_adapters.v"))

      assert(verilog.contains("clog2(DEPTH, 1)"), verilog)
      assert(verilog.contains("adapter_memory"), verilog)
      // Native emission may consume the write-address input directly while
      // retaining a private carrier for the synchronous read address. Count
      // exact symbolic declarations in both the module port list (comma) and
      // body (semicolon), rather than requiring two optimizer-owned body nets.
      // The DEPTH maximum of nine above remains the provenance discriminator:
      // an unproven fixed three-bit adapter cannot reach this assertion.
      assert(
        "(?m)^\\s*(?:(?:input|output)\\s+)?(?:wire|reg)\\s+\\[clog2\\(DEPTH,\\s*1\\)-1:0\\]\\s+[^,;]+[,;]\\s*$".r
          .findAllIn(verilog)
          .size >= 3,
        verilog
      )
    }
  }

  test("caller-constructed fixed adapters do not acquire native memory provenance") {
    withTemporaryDirectory { directory =>
      val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 9)
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "caller_fixed_memory_port_adapters.v"
      MorphVerilog.tryGenerate(config)(
        new CallerConstructedFixedAdapters(depth)
      ) match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ADDRESS-CAPACITY-NOT-PROVEN"
            ),
            failure.detail
          )
        case Right(report) =>
          fail(s"Expected fixed adapter capacity rejection, received $report")
      }
    }
  }

  test("a captured UInt data leaf cannot prove another port address") {
    withTemporaryDirectory { directory =>
      val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 9)
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "native_memory_adapter_cross_leaf.v"
      MorphVerilog.tryGenerate(config)(
        new AdapterDataReusedAsAddress(depth)
      ) match {
        case Left(failure) =>
          assert(
            failure.detail.contains(
              "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ADDRESS-CAPACITY-NOT-PROVEN"
            ),
            failure.detail
          )
        case Right(report) =>
          fail(s"Expected cross-leaf address rejection, received $report")
      }
    }
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory[A](body: Path => A): A = {
    val directory = Files.createTempDirectory("morphhdl-native-mem-adapter-test-")
    try body(directory)
    finally {
      val stream = Files.walk(directory)
      try stream.iterator().asScala.toVector.reverse.foreach(Files.deleteIfExists)
      finally stream.close()
    }
  }
}
