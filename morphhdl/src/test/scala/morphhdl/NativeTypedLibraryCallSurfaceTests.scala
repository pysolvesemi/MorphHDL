package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import nativeapplication.NativeTypedLibraryCallSurfaceFixture
import nativeapplication.NativeTypedLibraryCallSurfaceFixture._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.{ElabBool, ElabInt, ParameterizedVerilogException, SpinalConfig, SpinalVerilog}

import morphhdl.frontend.{FrontendException, HdlBool, HdlInt}

class NativeTypedLibraryCallSurfaceTests extends AnyFunSuite {
  test("literal and parameter arguments select their native concrete and typed overloads") {
    assert(literalSelections == ("int" -> "boolean"))
    assert(parameterSelections == (("elab-int", "elab-bool", "hdl-int")))

    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 16)
    val typedWidth: ElabInt = width
    assert(typedWidth.parameters.map(_.name) == Vector("WIDTH"))
    assert(typedWidth.minimum == 1)
    assert(typedWidth.maximum == 16)

    val enabled = HdlBool.param("ENABLE", default = true)
    val typedEnabled: ElabBool = enabled
    assert(typedEnabled.parameters.map(_.name) == Vector("ENABLE"))
    assert(typedEnabled.isSymbolic)

    val literalEnabled: ElabBool = HdlBool.literal(false)
    assert(literalEnabled.isAlwaysFalse)
  }

  test("typed ingress remains one way and cannot expose a concrete witness") {
    assertDoesNotCompile(
      """
        |val depth = morphhdl.frontend.HdlInt
        |  .param("DEPTH", default = 5, min = 1, max = 8)
        |val erased: Int = depth
        |""".stripMargin
    )
    assertDoesNotCompile(
      """
        |val enabled = morphhdl.frontend.HdlBool.param("ENABLE", default = true)
        |val erased: Boolean = enabled
        |""".stripMargin
    )
    assertDoesNotCompile(
      """
        |val enabled: spinal.core.ElabBool =
        |  morphhdl.frontend.HdlBool.param("ENABLE", default = true)
        |val erased: Boolean = enabled
        |""".stripMargin
    )
    assertDoesNotCompile(
      """
        |val width: spinal.core.ElabInt = morphhdl.frontend.HdlInt
        |  .param("WIDTH", default = 8, min = 1, max = 16)
        |val leaked: Int = width.witness
        |""".stripMargin
    )
    assertDoesNotCompile(
      """
        |val enabled: spinal.core.ElabBool =
        |  morphhdl.frontend.HdlBool.param("ENABLE", default = true)
        |val leaked: Boolean = enabled.witness
        |""".stripMargin
    )
  }

  test("null and inexact typed ingress fails closed") {
    val nullInteger = intercept[FrontendException] {
      val value: ElabInt = null.asInstanceOf[HdlInt]
      value
    }
    assert(nullInteger.code == "MORPH-FRONTEND-TYPED-INTEGER-NULL")

    val nullBoolean = intercept[FrontendException] {
      val value: ElabBool = null.asInstanceOf[HdlBool]
      value
    }
    assert(nullBoolean.code == "MORPH-FRONTEND-TYPED-BOOLEAN-NULL")

    val left = HdlInt.param("LEFT", default = 3, min = 1, max = 4)
    val right = HdlInt.param("RIGHT", default = 3, min = 1, max = 4)
    val error = intercept[ParameterizedVerilogException] {
      val predicate: ElabBool = left < right
      predicate.isSymbolic
    }
    assert(error.code == "SPINAL-ELAB-DOMAIN-EVIDENCE-MISSING")
  }

  test("ordinary native calls emit typed Counter Stream Flow Mem Vec and hierarchy RTL") {
    withTemporaryDirectory { firstDirectory =>
      withTemporaryDirectory { secondDirectory =>
        val first = emitParameterized(firstDirectory, "native_typed_surface.v")
        val second = emitParameterized(secondDirectory, "native_typed_surface.v")
        assert(first == second)

        assert(first.contains("module NativeTypedLibraryTop #("))
        assert(first.contains("parameter integer PARENT_WIDTH = 8"))
        assert(first.contains("parameter integer DEPTH = 5"))
        assert(first.contains("parameter integer ENABLE = 1"))
        assert(first.contains("clog2(DEPTH, 1)"))
        Vector(
          "stream_in_payload",
          "stream_out_payload",
          "flow_in_payload",
          "flow_out_payload"
        ).foreach { name =>
          assert(
            ("\\[PARENT_WIDTH-1:0\\]\\s+" + name + "\\b").r
              .findFirstIn(first)
              .nonEmpty
          )
        }
        assert(first.contains("[0:DEPTH-1]"))
        assert(first.contains("(PARENT_WIDTH * DEPTH)"))
        assert(first.contains("module NativeTypedLibraryChild #("))
        assert(first.contains(".WIDTH(PARENT_WIDTH)"))
        assert(
          "(?m)^module NativeTypedLibraryChild\\b".r
            .findAllMatchIn(first)
            .size == 1
        )
        assert(first.contains("ENABLE == 1"))
        assert(first.contains("NativeTypedFeatureEnabledSink"))
        assert(first.contains("NativeTypedFeatureDisabledSink"))
        assert(!first.contains("ParamRTL"))
        assert(!first.contains("NativeIntShadow"))
      }
    }
  }

  test("ordinary literal calls stay concrete parameter-free and deterministic") {
    withTemporaryDirectory { firstDirectory =>
      withTemporaryDirectory { secondDirectory =>
        val first = emitLiteral(firstDirectory, "native_literal_surface.v")
        val second = emitLiteral(secondDirectory, "native_literal_surface.v")
        assert(first == second)
        assert(first.contains("module NativeTypedLibraryLiteralTop ("))
        assert(!first.contains("parameter integer"))
        assert(!first.contains("ParamRTL"))
      }
    }
  }

  private def emitParameterized(directory: Path, filename: String): String = {
    val config = generationConfig(directory, filename)
    MorphVerilog(config)(NativeTypedLibraryCallSurfaceFixture.parameterized())
    read(directory.resolve(filename))
  }

  private def emitLiteral(directory: Path, filename: String): String = {
    val config = generationConfig(directory, filename)
    SpinalVerilog(config)(new NativeTypedLibraryCallSurfaceFixture.LiteralTop)
    read(directory.resolve(filename))
  }

  private def generationConfig(directory: Path, filename: String): SpinalConfig = {
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    config
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-native-typed-surface-")
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
