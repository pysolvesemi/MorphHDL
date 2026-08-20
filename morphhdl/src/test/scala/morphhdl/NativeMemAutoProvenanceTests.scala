package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

import morphhdl.frontend.{
  HdlInt,
  NativeMemAutoProvenance,
  NativeMemFactoryOps,
  SourceOrigin
}

class NativeMemAutoProvenanceTests extends AnyFunSuite {
  private final class AutoNativeMemory(
      depth: HdlInt,
      definition: String
  ) extends Component {
    setDefinitionName(definition)

    val read_enable = in(Bool())
    val write_enable = in(Bool())
    val address = in(UInt(3 bits))
    val write_data = in(Bits(8 bits))
    val read_data = out(Bits(8 bits))

    val memory = spinal.core
      .Mem(HardType(Bits(8 bits)), depth)
      .setName("memory")
    val read_word = memory.readSync(
      address,
      enable = read_enable,
      readUnderWrite = readFirst
    )
    memory.write(address, write_data, enable = write_enable)
    read_data := read_word
  }

  private final class TwoAutoNativeMemories(
      depthA: HdlInt,
      depthB: HdlInt,
      definition: String
  ) extends Component {
    setDefinitionName(definition)

    val read_enable = in(Bool())
    val write_enable = in(Bool())
    val address = in(UInt(3 bits))
    val write_data = in(Bits(8 bits))
    val read_data_a = out(Bits(8 bits))
    val read_data_b = out(Bits(8 bits))

    val memoryA = spinal.core
      .Mem(HardType(Bits(8 bits)), depthA)
      .setName("memory_a")
    val memoryB = spinal.core
      .Mem(HardType(Bits(8 bits)), depthB)
      .setName("memory_b")

    val readA = memoryA.readSync(
      address,
      enable = read_enable,
      readUnderWrite = readFirst
    )
    val readB = memoryB.readSync(
      address,
      enable = read_enable,
      readUnderWrite = readFirst
    )
    memoryA.write(address, write_data, enable = write_enable)
    memoryB.write(address, write_data, enable = write_enable)
    read_data_a := readA
    read_data_b := readB
  }

  private final class LiteralNativeMemory(probeWidth: HdlInt)
      extends Component {
    setDefinitionName("LiteralNativeMemory")

    val probe_in = in(morphhdl.frontend.Bits(probeWidth bits))
    val probe_out = out(morphhdl.frontend.Bits(probeWidth bits))
    val read_enable = in(Bool())
    val write_enable = in(Bool())
    val address = in(UInt(3 bits))
    val write_data = in(Bits(8 bits))
    val read_data = out(Bits(8 bits))

    probe_out := probe_in

    val memory = spinal.core
      .Mem(HardType(Bits(8 bits)), 5)
      .setName("memory")
    val read_word = memory.readSync(
      address,
      enable = read_enable,
      readUnderWrite = readFirst
    )
    memory.write(address, write_data, enable = write_enable)
    read_data := read_word
  }

  test("ordinary native Mem syntax automatically retains a direct HdlInt depth") {
    withTemporaryDirectory { directory =>
      val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
      val verilog = emitMorph(
        directory,
        "auto_native_mem.v",
        new AutoNativeMemory(depth, "AutoNativeMemory")
      )

      assert(verilog.contains("module AutoNativeMemory #("))
      assert(verilog.contains("parameter integer DEPTH = 5"))
      assert(verilog.contains("reg [7:0] memory [0:DEPTH-1];"))
      assert(verilog.contains("if (address < DEPTH) begin"))
    }
  }

  test("literal native Mem depth remains concrete and creates no depth parameter") {
    withTemporaryDirectory { directory =>
      val probeWidth =
        HdlInt.param("PROBE_WIDTH", default = 2, min = 1, max = 4)
      val verilog = emitMorph(
        directory,
        "literal_native_mem.v",
        new LiteralNativeMemory(probeWidth)
      )

      assert(verilog.contains("parameter integer PROBE_WIDTH = 2"))
      assert(!verilog.contains("parameter integer DEPTH"))
      assert(verilog.contains("reg [7:0] memory [0:4];"))
    }
  }

  test("an inlined compound HdlInt depth retains its exact symbolic bound") {
    withTemporaryDirectory { directory =>
      val base = HdlInt.param("BASE", default = 4, min = 1, max = 7)
      val depth = base + HdlInt.literal(BigInt(1))
      val verilog = emitMorph(
        directory,
        "compound_auto_native_mem.v",
        new AutoNativeMemory(depth, "CompoundAutoNativeMemory")
      )

      assert(verilog.contains("parameter integer BASE = 4"))
      assert(verilog.contains("(BASE + 1)"))
      assert(verilog.contains("memory [0:(BASE + 1)-1]"))
      assert(verilog.contains("if (address < (BASE + 1)) begin"))
    }
  }

  test("two memories using the same expression retain one shared formal signature") {
    withTemporaryDirectory { directory =>
      val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
      var built: TwoAutoNativeMemories = null
      val verilog = emitConcrete(
        directory,
        "same_expression_auto_native_mem.v",
        {
          built = new TwoAutoNativeMemories(
            depth,
            depth,
            "SameExpressionAutoNativeMemories"
          )
          built
        }
      )

      assert(!verilog.contains("parameter integer DEPTH"))
      assert(verilog.contains("memory_a [0:4]"))
      assert(verilog.contains("memory_b [0:4]"))

      val recordA = NativeMemAutoProvenance.recordOf(built.memoryA).get
      val recordB = NativeMemAutoProvenance.recordOf(built.memoryB).get
      assert(recordA.depth.value == BigInt(5))
      assert(recordB.depth.value == BigInt(5))
      assert(recordA.token.signature.verilog == "DEPTH")
      assert(recordB.token.signature.verilog == "DEPTH")
    }
  }

  test("equal witnesses with distinct symbolic origins remain distinguishable") {
    withTemporaryDirectory { directory =>
      val depthA = HdlInt.param("DEPTH_A", default = 5, min = 1, max = 8)
      val depthB = HdlInt.param("DEPTH_B", default = 5, min = 1, max = 8)
      var built: TwoAutoNativeMemories = null
      val verilog = emitConcrete(
        directory,
        "distinct_origin_auto_native_mem.v",
        {
          built = new TwoAutoNativeMemories(
            depthA,
            depthB,
            "DistinctOriginAutoNativeMemories"
          )
          built
        }
      )

      assert(!verilog.contains("parameter integer DEPTH_A"))
      assert(!verilog.contains("parameter integer DEPTH_B"))
      assert(verilog.contains("memory_a [0:4]"))
      assert(verilog.contains("memory_b [0:4]"))

      val recordA = NativeMemAutoProvenance.recordOf(built.memoryA).get
      val recordB = NativeMemAutoProvenance.recordOf(built.memoryB).get
      assert(recordA.depth.value == recordB.depth.value)
      assert(recordA.token.signature.verilog == "DEPTH_A")
      assert(recordB.token.signature.verilog == "DEPTH_B")
      assert(recordA.token != recordB.token)
    }
  }

  test("conflicting provenance for one exact native Mem object is rejected") {
    withTemporaryDirectory { directory =>
      val result = MorphVerilog.tryGenerate(
        config(directory, "conflicting_auto_native_mem.v")
      ) {
        val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
        new Component {
          setDefinitionName("ConflictingAutoNativeMemory")
          val memory = spinal.core.Mem(HardType(Bits(8 bits)), depth)
          val retained = NativeMemAutoProvenance.recordOf(memory).get
          NativeMemAutoProvenance.attach(
            memory,
            retained.depth,
            retained.token.copy(callSite = SourceOrigin("conflict.scala", 1))
          )
        }
      }

      assertFailure(
        result,
        "MORPH-FRONTEND-NATIVE-MEM-PROVENANCE-CONFLICT"
      )
    }
  }

  test("null symbolic depth fails before native Mem construction") {
    withTemporaryDirectory { directory =>
      val result = MorphVerilog.tryGenerate(
        config(directory, "null_auto_native_mem.v")
      ) {
        val depth: HdlInt = null
        new Component {
          setDefinitionName("NullAutoNativeMemory")
          val memory = spinal.core.Mem(HardType(Bits(8 bits)), depth)
        }
      }

      assertFailure(result, "MORPH-FRONTEND-NATIVE-MEM-DEPTH-NULL")
    }
  }

  test("nonpositive symbolic depth domain remains fail closed") {
    withTemporaryDirectory { directory =>
      val result = MorphVerilog.tryGenerate(
        config(directory, "invalid_domain_auto_native_mem.v")
      ) {
        val depth = HdlInt.param("DEPTH", default = 5, min = 0, max = 8)
        new Component {
          setDefinitionName("InvalidDomainAutoNativeMemory")
          val memory = spinal.core.Mem(HardType(Bits(8 bits)), depth)
        }
      }

      assertFailure(
        result,
        "MORPH-FRONTEND-SPINAL-MEMORY-DEPTH-DOMAIN-INVALID"
      )
    }
  }

  test("ordinary SpinalVerilog keeps the automatic native Mem concrete") {
    withTemporaryDirectory { directory =>
      val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
      val verilog = emitConcrete(
        directory,
        "auto_native_mem_concrete.v",
        new AutoNativeMemory(depth, "AutoNativeMemoryConcrete")
      )

      assert(!verilog.contains("parameter integer DEPTH"))
      assert(verilog.contains("memory [0:4]"))
    }
  }

  test("automatic native Mem replay is byte deterministic") {
    withTemporaryDirectory { first =>
      withTemporaryDirectory { second =>
        val firstDepth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
        val secondDepth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
        val firstVerilog = emitMorph(
          first,
          "deterministic_auto_native_mem.v",
          new AutoNativeMemory(firstDepth, "DeterministicAutoNativeMemory")
        )
        val secondVerilog = emitMorph(
          second,
          "deterministic_auto_native_mem.v",
          new AutoNativeMemory(secondDepth, "DeterministicAutoNativeMemory")
        )

        assert(firstVerilog == secondVerilog)
      }
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

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-native-mem-auto-test-")
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
