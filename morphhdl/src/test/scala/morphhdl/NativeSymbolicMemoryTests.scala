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

    val memory = morphhdl.frontend.Mem(morphhdl.frontend.HardType(morphhdl.frontend.Bits(width bits)), depth).setName("memory")
    val read_word = memory.readSync(
      address,
      enable = read_enable,
      readUnderWrite = readFirst
    )
    memory.write(address, write_data, enable = write_enable)
    read_data := read_word
  }

  private final class NativeAddressDepthRootMemory(
      memoryDepth: HdlInt,
      addressDepth: HdlInt
  ) extends Component {
    setDefinitionName("NativeAddressDepthRootMemory")

    val read_enable = in(Bool())
    val write_enable = in(Bool())
    val address = in(
      morphhdl.frontend.UInt(addressDepth.addressWidth bits)
    )
    val write_data = in(Bits(8 bits))
    val read_data = out(Bits(8 bits))

    val memory = morphhdl.frontend
      .Mem(
        morphhdl.frontend.HardType(Bits(8 bits)),
        memoryDepth
      )
      .setName("memory")
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

    val memory = morphhdl.frontend.Mem(morphhdl.frontend.HardType(morphhdl.frontend.Bits(width bits)), depth).setName("memory")
    val read_word = memory.readSync(
      read_address,
      enable = read_enable,
      readUnderWrite = readFirst
    )
    memory.write(write_address, write_data, enable = write_enable)
    read_data := read_word
  }

  private final class NativeDontCareSimpleDualPortMemory(
      width: HdlInt,
      depth: HdlInt
  ) extends Component {
    setDefinitionName("NativeDontCareSimpleDualPortMemory")

    val read_enable = in(Bool())
    val write_enable = in(Bool())
    val read_address = in(morphhdl.frontend.UInt(depth.addressWidth bits))
    val write_address = in(morphhdl.frontend.UInt(depth.addressWidth bits))
    val write_data = in(morphhdl.frontend.Bits(width bits))
    val read_data = out(morphhdl.frontend.Bits(width bits))

    val memory = morphhdl.frontend
      .Mem(
        morphhdl.frontend.HardType(morphhdl.frontend.Bits(width bits)),
        depth
      )
      .setName("memory")
    val read_word = memory.readSync(read_address, enable = read_enable)
    memory.write(write_address, write_data, enable = write_enable)
    read_data := read_word
  }

  private final class ResizedNativeMemoryAddress(
      width: HdlInt,
      depth: HdlInt
  ) extends Component {
    setDefinitionName("ResizedNativeMemoryAddress")

    val read_enable = in(Bool())
    val write_enable = in(Bool())
    @dontName val pointerWidth = depth
      .hdlEq(HdlInt.literal(BigInt(8)))
      .select(HdlInt.literal(BigInt(4)), HdlInt.literal(BigInt(3)))
    val pointer = in(
      morphhdl.frontend.UInt(pointerWidth bits)
    )
    val write_data = in(morphhdl.frontend.Bits(width bits))
    val read_data = out(morphhdl.frontend.Bits(width bits))

    // This is deliberately an ordinary, untagged native memory-address
    // carrier. At the DEPTH=5 witness it has the same width as pointer, while
    // the retained pointer grows an extra wrap bit only at DEPTH=8.
    val address = UInt(3 bits).setName("address")
    address := pointer.resized
    val memory = morphhdl.frontend
      .Mem(
        morphhdl.frontend.HardType(morphhdl.frontend.Bits(width bits)),
        depth
      )
      .setName("memory")
    val read_word = memory.readSync(
      address,
      enable = read_enable,
      readUnderWrite = readFirst
    )
    memory.write(address, write_data, enable = write_enable)
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

  test("memory address width accepts one shared depth declaration root") {
    withTemporaryDirectory { directory =>
      val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
      val verilog = emitMorph(
        directory,
        "native_shared_address_depth_root.v",
        new NativeAddressDepthRootMemory(depth, depth)
      )

      val declaration = "parameter\\s+integer\\s+DEPTH\\s*=\\s*5".r
      assert(declaration.findAllMatchIn(verilog).size == 1)
      assert(verilog.contains("clog2(DEPTH, 1)"))
      assert(verilog.contains("if (address < DEPTH) begin"))
    }
  }

  test("memory address width rejects an independent same-schema depth root") {
    withTemporaryDirectory { directory =>
      val memoryDepth =
        HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
      val addressDepth =
        HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
      val result = MorphVerilog.tryGenerate(
        config(directory, "native_independent_address_depth_root.v")
      ) {
        new NativeAddressDepthRootMemory(memoryDepth, addressDepth)
      }

      assertFailure(
        result,
        "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED"
      )
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

  test("independent-address dontCare retains one deterministic legal collision outcome") {
    withTemporaryDirectory { directory =>
      val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
      val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
      val verilog = emitMorph(
        directory,
        "native_dontcare_simple_dual_port_memory.v",
        new NativeDontCareSimpleDualPortMemory(width, depth)
      )

      assert(verilog.contains("if (read_address < DEPTH) begin"))
      assert(verilog.contains("if (write_address < DEPTH) begin"))
      assert(verilog.contains("<= memory[read_address];"))
      assert(verilog.contains("memory[write_address] <= write_data;"))
      val readIndex = verilog.indexOf("<= memory[read_address];")
      val writeIndex = verilog.indexOf("memory[write_address] <= write_data;")
      assert(readIndex >= 0 && writeIndex > readIndex)
      assert(count(verilog, "always @(posedge clk)") == 1)
    }
  }

  test("validated memory address width survives generic resized-carrier inference") {
    withTemporaryDirectory { directory =>
      val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
      val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
      val verilog = emitMorph(
        directory,
        "resized_native_memory_address.v",
        new ResizedNativeMemoryAddress(width, depth)
      )

      assert(
        """(?m)^\s*wire\s+\[2:0\]\s+address\s*;\s*$""".r
          .findFirstIn(verilog)
          .nonEmpty,
        verilog
      )
      assert("""DEPTH\)?\s*==\s*\(?8""".r.findFirstIn(verilog).nonEmpty)
      assert(verilog.contains("if (address < DEPTH) begin"))
      assert(verilog.contains("memory[address] <= write_data;"))
    }
  }

  test("ordinary native Mem with static depth discovers symbolic element width externally") {
    withTemporaryDirectory { directory =>
      val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
      val verilog = emitMorph(
        directory,
        "native_static_depth_memory.v",
        new Component {
          setDefinitionName("NativeStaticDepthMemory")
          val read_enable = in(Bool())
          val write_enable = in(Bool())
          val address = in(morphhdl.frontend.UInt(3 bits))
          val write_data = in(morphhdl.frontend.Bits(width bits))
          val read_data = out(morphhdl.frontend.Bits(width bits))
          val memory = spinal.core.Mem(
            morphhdl.frontend.HardType(morphhdl.frontend.Bits(width bits)),
            5
          ).setName("memory")
          val read_word = memory.readSync(
            address,
            enable = read_enable,
            readUnderWrite = readFirst
          )
          memory.write(address, write_data, enable = write_enable)
          read_data := read_word
        }
      )

      assert(verilog.contains("parameter integer WIDTH = 8"))
      assert(!verilog.contains("parameter integer DEPTH"))
      assert(verilog.contains("reg [WIDTH-1:0] memory [0:4];"))
      assert(verilog.contains("if (address < 5) begin"))
      assert(verilog.contains("memory[address] <= write_data;"))
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
          val memory = morphhdl.frontend.Mem(morphhdl.frontend.HardType(morphhdl.frontend.Bits(width bits)), depth).setName("memory")
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
          val memory = morphhdl.frontend.Mem(morphhdl.frontend.HardType(morphhdl.frontend.Bits(width bits)), depth).setName("memory")
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
          val memory = morphhdl.frontend.Mem(morphhdl.frontend.HardType(morphhdl.frontend.Bits(width bits)), depth).setName("memory")
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
          val memory = morphhdl.frontend.Mem(morphhdl.frontend.HardType(morphhdl.frontend.Bits(width bits)), depth).setName("memory")
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
