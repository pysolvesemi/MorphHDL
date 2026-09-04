package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.regex.Pattern

import scala.collection.JavaConverters._

import nativeapplication.TypedBlackBoxGenericBindingFixture
import nativeapplication.TypedBlackBoxGenericBindingFixture._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.{
  ParameterizedVerilogException,
  SpinalConfig,
  SpinalVerilog,
  SpinalVhdl
}

class TypedBlackBoxGenericBindingTests extends AnyFunSuite {
  test("typed BlackBox generics bind exact parent parameters and preserve external ownership") {
    withTemporaryDirectory { firstDirectory =>
      withTemporaryDirectory { secondDirectory =>
        val first = emitParameterized(firstDirectory, "typed_blackbox.v")
        val second = emitParameterized(secondDirectory, "typed_blackbox.v")
        assert(first == second)

        assert(first.contains("module TypedBlackBoxGenericTop #("))
        assert(first.contains("parameter integer ENABLE = 1"))
        assert(first.contains("parameter integer LATENCY = 2"))
        assert(first.contains("parameter integer WIDTH = 8"))
        assert(!moduleIsDefined(first, "TypedExternalLeaf"))
        assert(!moduleIsDefined(first, "TypedParameterOnlyExternal"))

        val externalA = instanceBlock(first, "TypedExternalLeaf", "external_a")
        val externalB = instanceBlock(first, "TypedExternalLeaf", "external_b")
        val parameterOnly =
          instanceBlock(
            first,
            "TypedParameterOnlyExternal",
            "parameter_only"
          )

        assert(associationLine(externalA, "LABEL").contains("\"typed\""))
        assert(associationLine(externalA, "WIDTH").count(_ == 'W') >= 2)
        assert(!associationLine(externalA, "WIDTH").matches(".*\\(\\s*8\\s*\\).*"))
        assert(associationLine(externalA, "DOUBLE_WIDTH").contains("WIDTH"))
        assert(associationLine(externalA, "ENABLED").contains("ENABLE"))
        assert(associationLine(externalA, "DEPTH").contains("4"))
        assert(
          associationLine(externalA, "CONCRETE_ENABLE").contains("1'b1")
        )

        assert(associationLine(externalB, "WIDTH").contains("WIDTH"))
        assert(associationLine(externalB, "WIDTH").contains("1"))
        assert(associationLine(externalB, "DOUBLE_WIDTH").contains("WIDTH"))
        assert(associationLine(externalB, "ENABLED").contains("ENABLE"))
        assert(associationLine(parameterOnly, "LATENCY").contains("LATENCY"))

        assert(
          portAssociation(externalA, "din").contains("narrow_in") &&
            portAssociation(externalA, "din").contains("WIDTH")
        )
        assert(
          portAssociation(externalB, "din").contains("wide_in") &&
            portAssociation(externalB, "din").contains("WIDTH")
        )
        val fixedPort = portAssociation(parameterOnly, "din")
        assert(fixedPort.contains("fixed_in"))
        assert(fixedPort.contains("[7:0]"))
        assert(!fixedPort.contains("WIDTH"))
        assert(!fixedPort.contains("LATENCY"))

        assert(declarationLine(first, "narrow_in").contains("WIDTH"))
        assert(declarationLine(first, "wide_in").contains("WIDTH"))
        assert(!first.contains("ParamRTL"))
        assert(!first.contains("NativeIntShadow"))
      }
    }
  }

  test("ordinary concrete BlackBox generics remain parameter-free and deterministic") {
    withTemporaryDirectory { firstDirectory =>
      withTemporaryDirectory { secondDirectory =>
        val first = emitLiteral(firstDirectory, "literal_blackbox.v")
        val second = emitLiteral(secondDirectory, "literal_blackbox.v")
        assert(first == second)

        assert(first.contains("module TypedBlackBoxLiteralTop ("))
        assert(!first.contains("parameter integer"))
        val external = instanceBlock(first, "TypedExternalLeaf", "external")
        assert(associationLine(external, "WIDTH").contains("8"))
        assert(associationLine(external, "ENABLED").contains("1'b1"))
        assert(!moduleIsDefined(first, "TypedExternalLeaf"))
      }
    }
  }

  test("native VHDL emission consumes typed generic witnesses without symbolic rewriting") {
    withTemporaryDirectory { directory =>
      val filename = "typed_blackbox.vhd"
      val config = generationConfig(directory, filename)
      SpinalVhdl(config)(TypedBlackBoxGenericBindingFixture.parameterized())
      val vhdl = read(directory.resolve(filename))

      assert(vhdl.contains("external_a : TypedExternalLeaf"))
      assert("(?s)WIDTH\\s*=>\\s*8".r.findFirstIn(vhdl).nonEmpty)
      assert("(?s)ENABLED\\s*=>\\s*true".r.findFirstIn(vhdl).nonEmpty)
      assert(!vhdl.contains("parameter integer"))
    }
  }

  test("duplicate generic names fail closed before publication") {
    withTemporaryDirectory { directory =>
      val error = intercept[MorphVerilogException] {
        MorphVerilog(generationConfig(directory, "duplicate.v"))(
          TypedBlackBoxGenericBindingFixture.duplicate()
        )
      }
      val diagnostic = causeChain(error).collectFirst {
        case value: ParameterizedVerilogException => value
      }.getOrElse {
        fail(s"missing ParameterizedVerilogException in cause chain: ${causeChain(error)}")
      }
      assert(
        diagnostic.code ==
          "SPINAL-PARAMETERIZED-VERILOG-BLACKBOX-GENERIC-NAME-DUPLICATE"
      )
    }
  }

  private def emitParameterized(directory: Path, filename: String): String = {
    val config = generationConfig(directory, filename)
    MorphVerilog(config)(TypedBlackBoxGenericBindingFixture.parameterized())
    read(directory.resolve(filename))
  }

  private def emitLiteral(directory: Path, filename: String): String = {
    val config = generationConfig(directory, filename)
    SpinalVerilog(config)(TypedBlackBoxGenericBindingFixture.literal())
    read(directory.resolve(filename))
  }

  private def generationConfig(directory: Path, filename: String): SpinalConfig = {
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    config
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def moduleIsDefined(verilog: String, name: String): Boolean =
    ("(?m)^\\s*module\\s+" + Pattern.quote(name) + "\\b").r
      .findFirstIn(verilog)
      .nonEmpty

  private def instanceBlock(
      verilog: String,
      definitionName: String,
      instanceName: String
  ): String = {
    val lines = verilog.split("\\n", -1).toVector
    val start = lines.indexWhere(line =>
      line.trim.startsWith(definitionName + " #(") ||
        line.trim == definitionName + " # ("
    )
    assert(start >= 0, s"missing instance definition $definitionName")
    val end = (start until lines.size).find(index =>
      lines(index).contains(") " + instanceName + " (")
    ).getOrElse(fail(s"missing instance terminator for $instanceName"))
    val close = (end until lines.size).find(index => lines(index).trim == ");")
      .getOrElse(fail(s"missing instance close for $instanceName"))
    lines.slice(start, close + 1).mkString("\n")
  }

  private def associationLine(block: String, name: String): String =
    block
      .split("\\n", -1)
      .find(_.trim.startsWith("." + name))
      .getOrElse(fail(s"missing generic association .$name in:\n$block"))

  private def portAssociation(block: String, name: String): String = {
    val lines = block.split("\\n", -1).toVector
    val instanceLine = lines.indexWhere(_.contains(") "))
    assert(instanceLine >= 0, s"missing instance line in:\n$block")
    lines
      .drop(instanceLine + 1)
      .find(_.trim.startsWith("." + name))
      .getOrElse(fail(s"missing port association .$name in:\n$block"))
  }

  private def declarationLine(verilog: String, name: String): String =
    verilog
      .split("\\n", -1)
      .find(line => line.contains(name) && line.trim.endsWith(";"))
      .getOrElse(fail(s"missing declaration for $name"))

  private def causeChain(error: Throwable): Vector[Throwable] = {
    val values = Vector.newBuilder[Throwable]
    var current: Throwable = error
    while (current != null) {
      values += current
      current = current.getCause
    }
    values.result()
  }

  private def withTemporaryDirectory(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("morphhdl-typed-blackbox-")
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
