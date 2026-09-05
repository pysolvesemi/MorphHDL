package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.util.regex.Pattern

import scala.collection.JavaConverters._

import nativeapplication.BoundedRecursivePowerFixture
import nativeapplication.BoundedRecursivePowerFixture._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.{ParameterizedVerilogException, SpinalConfig, SpinalVerilog}

class BoundedRecursivePowerTests extends AnyFunSuite {
  test("one typed component emits one deterministic bounded recursive Verilog module") {
    withTemporaryDirectory { firstDirectory =>
      withTemporaryDirectory { secondDirectory =>
        val first = emitParameterized(firstDirectory, "recursive_power.v")
        val second = emitParameterized(secondDirectory, "recursive_power.v")
        assert(first == second)

        assert(moduleDefinitionCount(first, "BoundedRecursivePower") == 1)
        assert(first.contains("parameter integer N = 5"))
        assert(first.contains("generate"))
        assert(first.contains("g_base"))
        assert(first.contains("g_step"))
        assert(first.contains("output wire"))
        assert(!first.contains("always @"), "a constant base branch must use continuous assignment")

        val recursive =
          instanceBlock(first, "BoundedRecursivePower", "recursive")
        val nextExponent = associationLine(recursive, "N")
          .replaceAll("\\s+", "")
          .replace("(", "")
          .replace(")", "")
        assert(nextExponent.contains(".NN-1"))
        assert(portAssociation(recursive, "x").contains("x"))
        assert(portAssociation(recursive, "y").contains("y"))

        assert(!first.contains("setInlineVerilog"))
        assert(!first.contains("ParamRTL"))
        assert(!first.contains("NativeIntShadow"))
      }
    }
  }

  test("flat concrete specializations remain parameter-free independent oracles") {
    Exponents.foreach { exponent =>
      withTemporaryDirectory { directory =>
        val filename = s"concrete_power_n$exponent.v"
        SpinalVerilog(generationConfig(directory, filename))(
          new BoundedRecursivePowerFixture.ConcretePower(exponent)
        )
        val verilog = read(directory.resolve(filename))
        assert(
          moduleDefinitionCount(
            verilog,
            s"BoundedRecursivePowerConcreteN$exponent"
          ) == 1
        )
        assert(!verilog.contains("parameter integer"))
        assert(!verilog.contains("BoundedRecursivePower #("))
      }
    }
  }

  test("a same-name self-reference without a decreasing metric fails closed") {
    withTemporaryDirectory { directory =>
      val error = intercept[MorphVerilogException] {
        MorphVerilog(generationConfig(directory, "nondecreasing.v"))(
          BoundedRecursivePowerFixture.nonDecreasing()
        )
      }
      assert(
        parameterizedDiagnostic(error).code ==
          "SPINAL-PARAMETERIZED-VERILOG-RECURSION-METRIC-NONDECREASING"
      )
    }
  }

  test("a recursive metric with a negative parameter domain fails closed") {
    withTemporaryDirectory { directory =>
      val error = intercept[MorphVerilogException] {
        MorphVerilog(generationConfig(directory, "negative_domain.v"))(
          BoundedRecursivePowerFixture.negativeDomain()
        )
      }
      assert(
        parameterizedDiagnostic(error).code ==
          "SPINAL-PARAMETERIZED-VERILOG-RECURSION-BINDING-UNPROVEN"
      )
    }
  }

  private def emitParameterized(directory: Path, filename: String): String = {
    MorphVerilog(generationConfig(directory, filename))(
      BoundedRecursivePowerFixture.parameterized()
    )
    read(directory.resolve(filename))
  }

  private def generationConfig(directory: Path, filename: String): SpinalConfig = {
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    config
  }

  private def moduleDefinitionCount(verilog: String, name: String): Int =
    ("(?m)^\\s*module\\s+" + Pattern.quote(name) + "\\b").r
      .findAllIn(verilog)
      .size

  private def instanceBlock(
      verilog: String,
      definition: String,
      instance: String
  ): String = {
    val lines = verilog.split("\\n", -1).toVector
    val body =
      ("^\\s*\\)\\s+" + Pattern.quote(instance) + "\\s*\\(\\s*$").r
    val bodies = lines.zipWithIndex.collect {
      case (line, index) if body.findFirstIn(line).nonEmpty => index
    }
    assert(bodies.size == 1, s"expected one recursive instance, found ${bodies.size}")
    val bodyIndex = bodies.head
    val startMarker =
      ("^\\s*" + Pattern.quote(definition) + "\\s*#\\s*\\(\\s*$").r
    val start = (bodyIndex - 1 to 0 by -1)
      .find(index => startMarker.findFirstIn(lines(index)).nonEmpty)
      .getOrElse(fail("missing recursive instance start"))
    val end = (bodyIndex + 1 until lines.size)
      .find(index => lines(index).trim == ");")
      .getOrElse(fail("missing recursive instance terminator"))
    lines.slice(start, end + 1).mkString("\n")
  }

  private def associationLine(block: String, name: String): String = {
    val marker = ("(?m)^.*\\." + Pattern.quote(name) + "\\s*\\(.*$").r
    val matches = marker.findAllIn(block).toVector
    assert(matches.size == 1, s"expected one generic '$name' in:\n$block")
    matches.head
  }

  private def portAssociation(block: String, name: String): String = {
    val marker = ("(?m)^.*\\." + Pattern.quote(name) + "\\s*\\(.*$").r
    val matches = marker.findAllIn(block).toVector
    assert(matches.size == 1, s"expected one port '$name' in:\n$block")
    matches.head
  }

  private def parameterizedDiagnostic(
      error: Throwable
  ): ParameterizedVerilogException =
    causeChain(error).collectFirst {
      case value: ParameterizedVerilogException => value
    }.getOrElse {
      fail(s"missing ParameterizedVerilogException in ${causeChain(error).mkString(" -> ")}")
    }

  private def causeChain(error: Throwable): Vector[Throwable] = {
    val builder = Vector.newBuilder[Throwable]
    var current: Throwable = error
    while (current != null) {
      builder += current
      current = current.getCause
    }
    builder.result()
  }

  private def read(path: Path): String =
    new String(Files.readAllBytes(path), StandardCharsets.UTF_8)

  private def withTemporaryDirectory[T](body: Path => T): T = {
    val directory = Files.createTempDirectory("morphhdl-recursive-power-")
    try body(directory)
    finally {
      if (Files.exists(directory)) {
        Files
          .walk(directory)
          .iterator()
          .asScala
          .toVector
          .sortBy(_.getNameCount)
          .reverse
          .foreach(Files.deleteIfExists)
      }
    }
  }
}

object BoundedRecursivePowerArtifactWriter {
  def main(arguments: Array[String]): Unit = {
    val outputDirectory = parseOutputDirectory(arguments)
    Files.createDirectories(outputDirectory)

    MorphVerilog(
      generationConfig(outputDirectory, "bounded_recursive_power.v")
    )(BoundedRecursivePowerFixture.parameterized())

    Exponents.foreach { exponent =>
      SpinalVerilog(
        generationConfig(outputDirectory, s"bounded_recursive_power_concrete_n$exponent.v")
      )(new BoundedRecursivePowerFixture.ConcretePower(exponent))
    }
  }

  private def parseOutputDirectory(arguments: Array[String]): Path =
    arguments.toList match {
      case "--output-dir" :: value :: Nil => Paths.get(value).toAbsolutePath.normalize()
      case _ =>
        throw new IllegalArgumentException(
          "usage: BoundedRecursivePowerArtifactWriter --output-dir <directory>"
        )
    }

  private def generationConfig(directory: Path, filename: String): SpinalConfig = {
    val config = SpinalConfig(targetDirectory = directory.toString)
    config.netlistFileName = filename
    config
  }
}
