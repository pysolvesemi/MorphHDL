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
        assert(
          portAssociation(parameterOnly, "din").contains("fixed_in") &&
            !portAssociation(parameterOnly, "din").contains("[")
        )

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
        fail(s"missing ParameterizedVerilogException in ${causeChain(error).mkString(" -> ")}")
      }
      assert(
        diagnostic.code ==
          "SPINAL-PARAMETERIZED-VERILOG-BLACKBOX-GENERIC-DUPLICATE"
      )
    }
  }

  private def emitParameterized(directory: Path, filename: String): String = {
    MorphVerilog(generationConfig(directory, filename))(
      TypedBlackBoxGenericBindingFixture.parameterized()
    )
    read(directory.resolve(filename))
  }

  private def emitLiteral(directory: Path, filename: String): String = {
    SpinalVerilog(generationConfig(directory, filename))(
      new TypedBlackBoxGenericBindingFixture.LiteralTop
    )
    read(directory.resolve(filename))
  }

  private def generationConfig(directory: Path, filename: String): SpinalConfig = {
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    config
  }

  private def moduleIsDefined(verilog: String, name: String): Boolean =
    ("(?m)^\\s*module\\s+" + Pattern.quote(name) + "\\b").r
      .findFirstIn(verilog)
      .nonEmpty

  private def instanceBlock(
      verilog: String,
      definition: String,
      instance: String
  ): String = {
    val lines = verilog.split("\\n", -1).toVector
    val parameterizedBody =
      ("^\\s*\\)\\s+" + Pattern.quote(instance) + "\\s*\\(\\s*$").r
    val plainBody =
      ("^\\s*" + Pattern.quote(definition) + "\\s+" +
        Pattern.quote(instance) + "\\s*\\(\\s*$").r
    val bodies = lines.zipWithIndex.collect {
      case (line, index)
          if parameterizedBody.findFirstIn(line).nonEmpty ||
            plainBody.findFirstIn(line).nonEmpty => index
    }
    assert(
      bodies.size == 1,
      s"expected one '$definition $instance' body, found ${bodies.size}"
    )
    val body = bodies.head
    val start =
      if (plainBody.findFirstIn(lines(body)).nonEmpty) body
      else {
        val parameterizedStart =
          ("^\\s*" + Pattern.quote(definition) + "\\s*#\\s*\\(\\s*$").r
        (body - 1 to 0 by -1)
          .find(index => parameterizedStart.findFirstIn(lines(index)).nonEmpty)
          .getOrElse(fail(s"missing parameterized start for '$definition $instance'"))
      }
    val end = (body + 1 until lines.size)
      .find(index => lines(index).trim == ");")
      .getOrElse(fail(s"missing terminator for '$definition $instance'"))
    lines.slice(start, end + 1).mkString("\n")
  }

  private def associationLine(block: String, name: String): String = {
    val marker = ("(?m)^.*\\." + Pattern.quote(name) + "\\s*\\(.*$").r
    val matches = marker.findAllIn(block).toVector
    assert(matches.size == 1, s"expected one generic '$name' in:\n$block")
    matches.head
  }

  private def portAssociation(block: String, name: String): String = {
    val marker = ("(?m)^.*\\." + Pattern.quote(name) + "\\s*\\(.*//.*$").r
    val matches = marker.findAllIn(block).toVector
    assert(matches.size == 1, s"expected one port '$name' in:\n$block")
    matches.head
  }

  private def declarationLine(verilog: String, name: String): String = {
    val marker =
      ("(?m)^\\s*(?:input|output|inout|wire|reg|logic)\\b.*\\b" +
        Pattern.quote(name) + "\\b.*$").r
    val matches = marker.findAllIn(verilog).toVector
    assert(matches.nonEmpty, s"missing declaration for '$name'")
    matches.head
  }

  private def causeChain(error: Throwable): Vector[Throwable] = {
    val values = Vector.newBuilder[Throwable]
    var current: Throwable = error
    while (current != null) {
      values += current
      current = current.getCause
    }
    values.result()
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

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

object TypedBlackBoxGenericBindingArtifactWriter {
  def main(arguments: Array[String]): Unit = {
    require(arguments.length == 1, "expected one output directory")
    val directory = Paths.get(arguments(0)).toAbsolutePath.normalize()
    Files.createDirectories(directory)
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = "typed_blackbox.v"
    MorphVerilog(config)(TypedBlackBoxGenericBindingFixture.parameterized())
    val output = directory.resolve(config.netlistFileName)
    require(Files.isRegularFile(output), s"missing generated artifact $output")

    val witnessConfig = SpinalConfig(targetDirectory = directory.toString)
    witnessConfig.netlistFileName = "typed_blackbox_witness.v"
    SpinalVerilog(witnessConfig)(
      new TypedBlackBoxGenericBindingFixture.ConcreteMatrixTop(
        width = 5,
        enabled = false,
        latency = 3
      )
    )
    val witness = directory.resolve(witnessConfig.netlistFileName)
    require(Files.isRegularFile(witness), s"missing generated witness $witness")
  }
}
