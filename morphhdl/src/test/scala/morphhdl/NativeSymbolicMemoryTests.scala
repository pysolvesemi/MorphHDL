package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import morphhdl.frontend.HdlInt

class NativeSymbolicMemoryTests extends AnyFunSuite {
  private final class NativeSinglePortMemory(
      width: HdlInt,
      depth: HdlInt
  ) extends Component {
    setDefinitionName("NativeSinglePortMemory")

    val read_enable = in(Bool())
    val write_enable = in(Bool())
    val address = in(morphhdl.frontend.UInt(depth.addressWidth bits))
    val write_data = in(morphhdl.frontend.Bits(width bits))
    val read_data = out(morphhdl.frontend.Bits(width bits))

    val memory = Mem(morphhdl.frontend.HardType(morphhdl.frontend.Bits(width bits)), depth).setName("memory")
    val read_word = memory.readSync(
      address,
      enable = read_enable,
      readUnderWrite = readFirst
    )
    memory.write(address, write_data, enable = write_enable)
    read_data := read_word
  }

  private final class NativeSimpleDualPortMemory(
      width: HdlInt,
      depth: HdlInt
  ) extends Component {
    setDefinitionName("NativeSimpleDualPortMemory")

    val read_enable = in(Bool())
    val write_enable = in(Bool())
    val read_address = in(morphhdl.frontend.UInt(depth.addressWidth bits))
    val write_address = in(morphhdl.frontend.UInt(depth.addressWidth bits))
    val write_data = in(morphhdl.frontend.Bits(width bits))
    val read_data = out(morphhdl.frontend.Bits(width bits))

    val memory = Mem(morphhdl.frontend.HardType(morphhdl.frontend.Bits(width bits)), depth).setName("memory")
    val read_word = memory.readSync(
      read_address,
      enable = read_enable,
      readUnderWrite = readFirst
    )
    memory.write(write_address, write_data, enable = write_enable)
    read_data := read_word
  }

  test("ordinary Mem readSync and write emit the guarded native single-port contract") {
    withTemporaryDirectory { directory =>
      val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
      val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
      val verilog = emitMorph(
        directory,
        "native_single_port_memory.v",
        new NativeSinglePortMemory(width, depth)
      )

      assert(verilog.contains("module NativeSinglePortMemory #("))
      assert(verilog.contains("parameter integer DEPTH = 5"))
      assert(verilog.contains("parameter integer WIDTH = 8"))
      assert(verilog.contains("function integer clog2;"))
      assert(verilog.contains("clog2(DEPTH, 1)"))
      assert(verilog.contains("reg [WIDTH-1:0] memory [0:DEPTH-1];"))
      assert(
        """(?m)^\s*reg\s+\[WIDTH-1:0\]\s+memory_spinal_port0\s*;\s*$""".r
          .findFirstIn(verilog)
          .nonEmpty
      )
      assert(verilog.contains("always @(posedge clk) begin : p_memory"))
      assert(verilog.contains("if (address < DEPTH) begin"))
      assert(verilog.contains("if (read_enable == 1'b1) begin"))
      assert(verilog.contains("if (write_enable == 1'b1) begin"))
      assert(verilog.contains("<= memory[address];"))
      assert(verilog.contains("memory[address] <= write_data;"))
      assert(verilog.contains("<= {WIDTH{1'b0}};"))

      val readIndex = verilog.indexOf("<= memory[address];")
      val writeIndex = verilog.indexOf("memory[address] <= write_data;")
      assert(readIndex >= 0 && writeIndex > readIndex)
      assert(count(verilog, "always @(posedge clk)") == 1)
    }
  }

  test("symbolic read-result storage is widened from a scalar concrete witness") {
    withTemporaryDirectory { directory =>
      val width = HdlInt.param("WIDTH", default = 1, min = 1, max = 8)
      val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
      val verilog = emitMorph(
        directory,
        "native_single_port_scalar_witness.v",
        new NativeSinglePortMemory(width, depth)
      )

      assert(verilog.contains("reg [WIDTH-1:0] memory [0:DEPTH-1];"))
      assert(
        """(?m)^\s*reg\s+\[WIDTH-1:0\]\s+memory_spinal_port0\s*;\s*$""".r
          .findFirstIn(verilog)
          .nonEmpty
      )
      assert(
        """(?m)^\s*reg\s+memory_spinal_port0\s*;\s*$""".r
          .findFirstIn(verilog)
          .isEmpty
      )
    }
  }

  test("independent read and write addresses emit the existing simple-dual-port policy") {
    withTemporaryDirectory { directory =>
      val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
      val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
      val verilog = emitMorph(
        directory,
        "native_simple_dual_port_memory.v",
        new NativeSimpleDualPortMemory(width, depth)
      )

      assert(verilog.contains("if (read_address < DEPTH) begin"))
      assert(verilog.contains("if (write_address < DEPTH) begin"))
      assert(verilog.contains("<= memory[read_address];"))
      assert(verilog.contains("memory[write_address] <= write_data;"))
      assert(verilog.contains("else if (read_enable == 1'b1) begin"))
      assert(verilog.contains("<= {WIDTH{1'b0}};"))
      assert(count(verilog, "always @(posedge clk)") == 1)
    }
  }

  test("ordinary SpinalVerilog remains concrete and ignores retained memory metadata") {
    withTemporaryDirectory { directory =>
      val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
      val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
      val verilog = emitConcrete(
        directory,
        "native_memory_concrete.v",
        new NativeSinglePortMemory(width, depth)
      )

      assert(!verilog.contains("parameter integer WIDTH"))
      assert(!verilog.contains("parameter integer DEPTH"))
      assert(!verilog.contains("function integer clog2"))
      assert(verilog.contains("memory [0:4]"))
    }
  }

  test("read-under-write must be explicitly read-first") {
    withTemporaryDirectory { directory =>
      val result = MorphVerilog.tryGenerate(config(directory, "bad_collision.v")) {
        val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
        val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
        new Component {
          setDefinitionName("BadNativeMemoryCollision")
          val read_enable = in(Bool())
          val write_enable = in(Bool())
          val address = in(morphhdl.frontend.UInt(depth.addressWidth bits))
          val write_data = in(morphhdl.frontend.Bits(width bits))
          val read_data = out(morphhdl.frontend.Bits(width bits))
          val memory = Mem(morphhdl.frontend.HardType(morphhdl.frontend.Bits(width bits)), depth).setName("memory")
          val read_word = memory.readSync(address, enable = read_enable)
          memory.write(address, write_data, enable = write_enable)
          read_data := read_word
        }
      }
      assertFailure(
        result,
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-COLLISION-POLICY-UNSUPPORTED"
      )
    }
  }

  test("both read and write require explicit active-high enables") {
    withTemporaryDirectory { directory =>
      val result = MorphVerilog.tryGenerate(config(directory, "missing_enable.v")) {
        val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
        val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
        new Component {
          setDefinitionName("MissingNativeMemoryEnable")
          val write_enable = in(Bool())
          val address = in(morphhdl.frontend.UInt(depth.addressWidth bits))
          val write_data = in(morphhdl.frontend.Bits(width bits))
          val read_data = out(morphhdl.frontend.Bits(width bits))
          val memory = Mem(morphhdl.frontend.HardType(morphhdl.frontend.Bits(width bits)), depth).setName("memory")
          val read_word = memory.readSync(
            address,
            readUnderWrite = readFirst
          )
          memory.write(address, write_data, enable = write_enable)
          read_data := read_word
        }
      }
      assertFailure(
        result,
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ENABLE-POLICY-UNSUPPORTED"
      )
    }
  }

  test("address capacity is proven over the complete depth domain") {
    withTemporaryDirectory { directory =>
      val result = MorphVerilog.tryGenerate(config(directory, "bad_capacity.v")) {
        val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
        val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 9)
        new Component {
          setDefinitionName("BadNativeMemoryCapacity")
          val read_enable = in(Bool())
          val write_enable = in(Bool())
          val address = in(morphhdl.frontend.UInt(3 bits))
          val write_data = in(morphhdl.frontend.Bits(width bits))
          val read_data = out(morphhdl.frontend.Bits(width bits))
          val memory = Mem(morphhdl.frontend.HardType(morphhdl.frontend.Bits(width bits)), depth).setName("memory")
          val read_word = memory.readSync(
            address,
            enable = read_enable,
            readUnderWrite = readFirst
          )
          memory.write(address, write_data, enable = write_enable)
          read_data := read_word
        }
      }
      assertFailure(
        result,
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-ADDRESS-CAPACITY-NOT-PROVEN"
      )
    }
  }

  test("write masks remain outside the whole-word Increment 35 contract") {
    withTemporaryDirectory { directory =>
      val result = MorphVerilog.tryGenerate(config(directory, "masked_write.v")) {
        val width = HdlInt.param("WIDTH", default = 8, min = 8, max = 8)
        val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
        new Component {
          setDefinitionName("MaskedNativeMemory")
          val read_enable = in(Bool())
          val write_enable = in(Bool())
          val address = in(morphhdl.frontend.UInt(depth.addressWidth bits))
          val write_data = in(morphhdl.frontend.Bits(width bits))
          val read_data = out(morphhdl.frontend.Bits(width bits))
          val memory = Mem(morphhdl.frontend.HardType(morphhdl.frontend.Bits(width bits)), depth).setName("memory")
          val read_word = memory.readSync(
            address,
            enable = read_enable,
            readUnderWrite = readFirst
          )
          memory.write(
            address,
            write_data,
            enable = write_enable,
            mask = B"1111"
          )
          read_data := read_word
        }
      }
      assertFailure(
        result,
        "SPINAL-PARAMETERIZED-VERILOG-MEMORY-MASK-UNSUPPORTED"
      )
    }
  }

  private def config(directory: Path, filename: String): SpinalConfig = {
    val value = SpinalConfig(targetDirectory = directory.toString)
    value.netlistFileName = filename
    value
  }

  private def emitMorph(
      directory: Path,
      filename: String,
      component: => Component
  ): String = {
    MorphVerilog(config(directory, filename))(component)
    read(directory.resolve(filename))
  }

  private def emitConcrete(
      directory: Path,
      filename: String,
      component: => Component
  ): String = {
    SpinalVerilog(config(directory, filename))(component)
    read(directory.resolve(filename))
  }

  private def assertFailure[T](
      result: Either[MorphVerilogFailure, T],
      code: String
  ): Unit = result match {
    case Left(failure) => assert(failure.detail.contains(code), failure.detail)
    case Right(report) => fail(s"Expected $code, received $report")
  }

  private def count(value: String, needle: String): Int =
    value.sliding(needle.length).count(_ == needle)

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-native-memory-test-")
    try body(directory)
    finally {
      val stream = Files.walk(directory)
      try {
        stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
          Files.deleteIfExists(path)
        }
      } finally stream.close()
    }
  }
}
