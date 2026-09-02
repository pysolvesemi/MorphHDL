package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._
import scala.sys.process.{Process, ProcessLogger}

import nativeapplication.NativeLibraryMigrationFixture
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

import morphhdl.frontend.HdlInt

class NativeLibraryMigrationTests extends AnyFunSuite {
  test("typed Stream and Flow pipeline choices retain one exact native algorithm surface") {
    withTemporaryDirectory { directory =>
      val first = emitPipeline(directory.resolve("first"), default = 0)
      val second = emitPipeline(directory.resolve("second"), default = 0)

      assert(first == second)
      assert(first.contains("module NativeLibraryMigrationPipelineTop #("))
      assert(first.contains("parameter integer PIPE_MODE = 0"))
      assert(first.contains("PIPE_MODE"))
      assert(first.contains("stream_in_valid"))
      assert(first.contains("stream_out_ready"))
      assert(first.contains("flow_in_valid"))
      assert(first.contains("flow_out_payload"))
      assert(!first.contains("MorphStream"))
      assert(!first.contains("MorphFlow"))
      assert(!first.contains("NativeIntShadow"))

      Vector(1, 2).foreach { mode =>
        compileOverride(
          directory,
          first,
          top = "NativeLibraryMigrationPipelineTop",
          parameter = "PIPE_MODE",
          value = mode
        )
      }
    }
  }

  test("typed queue helpers preserve FIFO memory Counter and Flow geometry") {
    withTemporaryDirectory { directory =>
      val first = emitQueueMemory(directory.resolve("first"), default = 5)
      val second = emitQueueMemory(directory.resolve("second"), default = 5)

      assert(first == second)
      assert(first.contains("module NativeLibraryMigrationQueueMemoryTop #("))
      assert(first.contains("parameter integer DEPTH = 5"))
      assert(first.contains("module StreamFifo #("))
      assert(first.contains(".DEPTH(DEPTH)"))
      assert(first.contains("[0:DEPTH-1]"))
      assert(first.contains("clog2(DEPTH, 1)"))
      assert(first.contains("stream_occupancy"))
      assert(first.contains("flow_availability"))
      assert(first.contains("counter_complete"))
      assert(first.contains("memory_read_data"))
      assert("(?m)^module StreamFifo\\b".r.findAllMatchIn(first).size == 1)
      assert(!first.contains("MorphCounter"))
      assert(!first.contains("NativeIntShadow"))

      Vector(2, 3, 8).foreach { depth =>
        compileOverride(
          directory,
          first,
          top = "NativeLibraryMigrationQueueMemoryTop",
          parameter = "DEPTH",
          value = depth
        )
      }
    }
  }

  test("ordinary literal migration calls remain parameter-free") {
    withTemporaryDirectory { directory =>
      val first = emitLiteral(directory.resolve("first"))
      val second = emitLiteral(directory.resolve("second"))
      assert(first == second)
      assert(first.contains("module NativeLibraryMigrationLiteralTop ("))
      assert(!first.contains("parameter integer"))
      assert(!first.contains("NativeIntShadow"))
    }
  }

  test("typed pipeline control rejects independent roots and illegal domains") {
    val first = HdlInt
      .param("FIRST_MODE", default = 0, min = 0, max = 1)
      .asElabInt
    val second = HdlInt
      .param("SECOND_MODE", default = 0, min = 0, max = 1)
      .asElabInt

    val independent = intercept[ParameterizedVerilogException] {
      first.elabEq(0) || second.elabEq(0)
    }
    assert(
      independent.code ==
        "SPINAL-ELAB-DOMAIN-EXACT-CORRELATION-UNSUPPORTED"
    )

    withTemporaryDirectory { directory =>
      val config = generationConfig(
        directory,
        "native_library_illegal_pipeline.v"
      )
      val error = intercept[MorphVerilogException] {
        MorphVerilog(config) {
          val mode = HdlInt
            .param("ILLEGAL_MODE", default = 0, min = 0, max = 1)
            .asElabInt
          new Component {
            setDefinitionName("NativeLibraryIllegalPipelineTop")
            val source = Stream(Bits(8 bits))
            source.pipelined(
              m2s = mode.elabEq(1),
              s2m = ElabBool.literal(false),
              halfRate = mode.elabEq(1)
            )
          }
        }
      }
      val cause = error.failure.cause.collect {
        case parameterized: ParameterizedVerilogException => parameterized
      }
      assert(cause.exists(_.code == "SPINAL-ELAB-REQUIRE-DOMAIN-UNPROVEN"))
    }
  }

  private def emitPipeline(directory: Path, default: Int): String = {
    Files.createDirectories(directory)
    val config = generationConfig(directory, "native_library_pipeline.v")
    MorphVerilog(config)(NativeLibraryMigrationFixture.pipeline(default))
    read(directory.resolve(config.netlistFileName))
  }

  private def emitQueueMemory(directory: Path, default: Int): String = {
    Files.createDirectories(directory)
    val config = generationConfig(directory, "native_library_queue_memory.v")
    MorphVerilog(config)(NativeLibraryMigrationFixture.queueMemory(default))
    read(directory.resolve(config.netlistFileName))
  }

  private def emitLiteral(directory: Path): String = {
    Files.createDirectories(directory)
    val config = generationConfig(directory, "native_library_literal.v")
    SpinalVerilog(config)(new NativeLibraryMigrationFixture.LiteralTop)
    read(directory.resolve(config.netlistFileName))
  }

  private def generationConfig(
      directory: Path,
      filename: String
  ): SpinalConfig = {
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    config
  }

  private def compileOverride(
      directory: Path,
      verilog: String,
      top: String,
      parameter: String,
      value: Int
  ): Unit = {
    val stem = s"${top}_${parameter}_$value"
    val source = directory.resolve(stem + ".v")
    val wrapper = directory.resolve(stem + "_wrapper.v")
    Files.write(source, verilog.getBytes(StandardCharsets.UTF_8))
    Files.write(
      wrapper,
      s"module ${stem}_wrapper; $top #(.${parameter}($value)) dut(); endmodule\n"
        .getBytes(StandardCharsets.UTF_8)
    )
    val output = directory.resolve(stem + ".out")
    val log = new StringBuilder
    val status = Process(
      Seq(
        "iverilog",
        "-g2001",
        "-s",
        stem + "_wrapper",
        "-o",
        output.toString,
        source.toString,
        wrapper.toString
      ),
      directory.toFile
    ).!(ProcessLogger(line => log.append(line).append('\n')))
    assert(status == 0, log.toString)
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-native-library-migration-")
    try body(directory)
    finally {
      val stream = Files.walk(directory)
      try
        stream
          .iterator()
          .asScala
          .toVector
          .sortBy(_.getNameCount)
          .reverse
          .foreach(Files.deleteIfExists)
      finally stream.close()
    }
  }
}
