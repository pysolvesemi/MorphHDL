package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import morphhdl.frontend._
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._

/** These fixtures deliberately vary names, operators and rejected topology.
  * None of their names is consulted by the production recursion validator.
  */
private object RecursiveSafetyFixture {
  final class Reference(
      definition: String,
      next: ElabInt,
      mode: String
  ) extends BlackBox {
    setBlackBoxName(if (mode == "foreign") "UnrelatedExternalModule" else definition)
    if (mode == "untyped") addGeneric("STEPS", 2)
    else addGeneric(if (mode == "wrongFormal") "OTHER" else "STEPS", next)
    if (mode == "extraGeneric") addGeneric("EXTRA", 1)
    if (mode == "inline") setInlineVerilog("module Unused(); endmodule")
    if (mode == "external") addRTLPath("not-an-implementation-of-the-owner.v")

    val valueIn = in UInt (8 bits)
    val valueOut = out UInt ((if (mode == "wrongWidth") 7 else 8) bits)
    valueIn.setName("valueIn")
    valueOut.setName(if (mode == "wrongPort") "differentOutput" else "valueOut")
  }

  final class Accumulator(steps: HdlInt, mode: String) extends Component {
    val moduleName = "RecursiveAccumulator" + mode.capitalize
    setDefinitionName(moduleName)
    val valueIn = in UInt (8 bits)
    val valueOut = out UInt (8 bits)
    valueIn.setName("valueIn")
    valueOut.setName("valueOut")

    def recursiveStep(): Unit = {
      val next = if (mode == "increasing") steps + 1 else steps - 1
      val reference = new Reference(moduleName, next, mode)
      reference.setName("inner")
      reference.valueIn := valueIn
      if (mode == "multiple") {
        val second = new Reference(moduleName, steps - 1, "valid")
        second.setName("second")
        second.valueIn := valueIn
        valueOut := (reference.valueOut.resize(8) + second.valueOut).resize(8)
      } else {
        valueOut := (valueIn + reference.valueOut.resize(8)).resize(8)
      }
    }
    if (mode == "unguarded") recursiveStep()
    else {
      val base = if (mode == "wrongBase") 1 else 0
      steps.hdlEq(base).generateIf("base", "step") {
        valueOut := U(0, 8 bits)
      }.otherwise {
        recursiveStep()
      }
    }
  }

  def apply(mode: String, default: Int = 3): Accumulator =
    new Accumulator(HdlInt.param("STEPS", default = default, min = 0, max = 8), mode)
}

class BoundedRecursiveSafetyTests extends AnyFunSuite {
  test("recursive capture is generic across module port parameter and operation names") {
    withDirectory { directory =>
      val config = SpinalConfig(targetDirectory = directory.toString)
      config.netlistFileName = "renamed.v"
      MorphVerilog(config)(RecursiveSafetyFixture("valid"))
      val text = new String(Files.readAllBytes(directory.resolve("renamed.v")), StandardCharsets.UTF_8)
      assert(text.contains("module RecursiveAccumulatorValid"))
      assert(text.contains("parameter integer STEPS = 3"))
      assert(text.contains(".STEPS"))
      assert(text.contains("valueIn"))
      assert(text.contains("valueOut"))
      assert(!text.contains("BoundedRecursivePower"))
    }
  }

  for (default <- Vector(0, 1, 8)) {
    test(s"recursive generation retains the complete topology at source default $default") {
      withDirectory { directory =>
        val config = SpinalConfig(targetDirectory = directory.toString)
        config.netlistFileName = "source_default.v"
        MorphVerilog(config)(new nativeapplication.BoundedRecursivePowerFixture.ParameterizedPower(
          HdlInt.param("N", default = default, min = 0, max = 8)
        ))
        val text = new String(Files.readAllBytes(directory.resolve("source_default.v")), StandardCharsets.UTF_8)
        assert(text.contains(s"parameter integer N = $default"))
        assert(text.contains("g_base"))
        assert(text.contains("g_step"))
        assert(text.contains(".N"))
      }
    }
  }

  private val rejected = Vector(
    "increasing" -> "RECURSION-BINDING-UNPROVEN",
    "unguarded" -> "RECURSION-BINDING-UNPROVEN",
    "wrongBase" -> "RECURSION-BINDING-UNPROVEN",
    "wrongFormal" -> "RECURSION-BINDING-UNPROVEN",
    "multiple" -> "RECURSION-SELF-REFERENCE-COUNT",
    "extraGeneric" -> "RECURSION-GENERIC-SCHEMA-UNSUPPORTED",
    "wrongWidth" -> "RECURSION-PORT-SCHEMA-MISMATCH",
    "wrongPort" -> "RECURSION-PORT-SCHEMA-MISMATCH",
    "untyped" -> "STRUCTURAL-BLACKBOX-UNSUPPORTED",
    "foreign" -> "STRUCTURAL-BLACKBOX-UNSUPPORTED",
    "inline" -> "STRUCTURAL-BLACKBOX-UNSUPPORTED",
    "external" -> "STRUCTURAL-BLACKBOX-UNSUPPORTED"
  )
  rejected.foreach { case (mode, suffix) =>
    test(s"recursive safety rejects $mode without accepting a parameterized candidate") {
      withDirectory { directory =>
        val config = SpinalConfig(targetDirectory = directory.toString)
        config.netlistFileName = "rejected.v"
        val error = intercept[MorphVerilogException] {
          MorphVerilog(config)(RecursiveSafetyFixture(mode))
        }
        val chain = Iterator.iterate[Throwable](error)(_.getCause).takeWhile(_ != null).toVector
        val diagnostic = chain.collectFirst { case value: ParameterizedVerilogException => value }
          .getOrElse(fail(chain.mkString(" -> ")))
        assert(diagnostic.code == "SPINAL-PARAMETERIZED-VERILOG-" + suffix)
      }
    }
  }

  private def withDirectory[T](body: Path => T): T = {
    val directory = Files.createTempDirectory("recursive-safety-")
    try body(directory)
    finally {
      val stream = Files.walk(directory)
      try stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists)
      finally stream.close()
    }
  }
}
