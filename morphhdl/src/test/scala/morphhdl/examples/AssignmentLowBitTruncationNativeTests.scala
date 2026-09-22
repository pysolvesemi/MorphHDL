package morphhdl.examples

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import org.scalatest.funsuite.AnyFunSuite
import morphhdl.{MorphVerilog, MorphWireAssignmentPasses}
import morphhdl.frontend.HdlInt
import spinal.core._
import spinal.core.internals._

class AssignmentLowBitTruncationNativeTests extends AnyFunSuite {
  private def temporary(body: Path => Unit): Unit = {
    val directory = Files.createTempDirectory("wire-trunc-01-")
    try body(directory)
    finally {
      val files = Files.walk(directory)
      try files.iterator.asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists(_))
      finally files.close()
    }
  }

  private def references(expression: Expression, alias: BaseType): Boolean = {
    var found = expression eq alias
    expression.walkDrivingExpressions {
      case value: BaseType if value eq alias => found = true
      case _ =>
    }
    found
  }

  private def inspect(kind: String): Unit = temporary { directory =>
    var carrier: UInt = null
    var low: UInt = null
    var protectedUse: UInt = null
    var observed = false
    val config = MorphWireAssignmentPasses(SpinalConfig(targetDirectory = directory.toString,
      headerWithDate = false))
    config.phasesInserters += { phases: ArrayBuffer[Phase] =>
      val index = phases.indexWhere(_.getClass.getName == "morphhdl.examples.ProductionWireAssignmentPhase")
      require(index >= 0)
      val intent = phases.collectFirst { case value: NativeConditionSourceIntent => value }.get
      phases.insert(index, new Phase {
        override def hasNetlistImpact: Boolean = true
        override def impl(pc: PhaseContext): Unit = {
          val sourceBefore = low.head.asInstanceOf[DataAssignmentStatement].source
          val carrierName = NativeWireNameProvenance.origin(carrier)
          val phase = new AssignmentLowBitTruncationNativePhase(intent)
          phase.impl(pc)
          val sourceAfter = low.head.asInstanceOf[DataAssignmentStatement].source
          kind match {
            case "safe" | "mixed" | "repeated" =>
              assert(carrierName.exists(value => value == morphhdl.ir.v1.NameOrigin.Unnamed ||
                value == morphhdl.ir.v1.NameOrigin.Generated), carrierName)
              assert(phase.rewrittenAssignments >= 1, "eligible low-bit receiver was not rewritten")
              assert(phase.rewrittenReferences >= 1, "the receiver changed without inlining its carrier")
              assert(!references(sourceAfter, carrier), "low-bit receiver still reads the removed boundary")
              if (kind == "mixed") {
                assert(carrier.parentScope != null, "shared carrier was deleted while an unsupported receiver remains")
                assert(references(protectedUse.head.asInstanceOf[DataAssignmentStatement].source, carrier))
              } else assert(carrier.parentScope == null, "dead eligible carrier was not removed")
            case _ =>
              assert(sourceAfter eq sourceBefore, "protected or unsupported receiver was changed")
              assert(carrier.parentScope != null, "protected carrier identity was removed")
          }
          val second = new AssignmentLowBitTruncationNativePhase(intent)
          second.impl(pc)
          assert(second.rewrittenAssignments == 0 && second.rewrittenReferences == 0 &&
            second.removedDeclarations == 0, "assignment lowering is not idempotent")
          observed = true
        }
      })
    }
    val witnessWidth = HdlInt.param("WITNESS_WIDTH", 2, 1, 4)
    MorphVerilog(config) {
      new Component {
        setDefinitionName("LowBitNativeBoundary")
        val witnessIn = in Bits(witnessWidth bits)
        val witnessOut = out Bits(witnessWidth bits)
        witnessOut := witnessIn
        val a, b, c = in UInt(16 bits)
        @dontName val first = a.resize(18) + b.resize(18)
        @dontName val bridge = UInt(18 bits)
        bridge := first
        @dontName val expression = if (kind == "repeated") bridge ^ bridge else bridge ^ c.resize(18)
        carrier = expression
        kind match {
          case "keep" => expression.addAttribute("keep")
          case "vital" => expression.setAsVital()
          case "explicit" => expression.setName("userExpression")
          case "no-merge" => expression.addTag(noBackendCombMerge)
          case _ =>
        }
        val result = out UInt((if (kind == "widening") 20 else 13) bits)
        low = result
        if (kind == "high-slice") result := expression(17 downto 5)
        else result := expression.resize(if (kind == "widening") 20 else 13)
        if (kind == "mixed") {
          val other = out UInt(13 bits)
          protectedUse = other
          other := expression(17 downto 5)
        }
      }
    }
    assert(observed, "native boundary observer did not execute")
  }

  for (kind <- Vector("safe", "repeated", "mixed", "keep", "vital", "explicit", "no-merge", "high-slice", "widening")) {
    test("native assignment absorption: " + kind) { inspect(kind) }
  }

  test("public production emission is deterministic and does not recreate the simple sum carrier") {
    temporary { directory =>
      LowBitTruncationArtifactWriter.main(Array(directory.toString))
      def generated(mode: String): String = new String(Files.readAllBytes(
        directory.resolve(mode).resolve("LowBitTruncationFixture.v")), StandardCharsets.UTF_8)
      val enabled = generated("enabled")
      assert(enabled == generated("default"), "default and explicit-enabled outputs differ")
      assert(enabled == generated("repeat"), "repeat emission is not byte-identical")
      assert(enabled.contains("OUT_WIDTH"), "the artifact lost its parameter")
      // Test the complete RHS, not the number or spelling of temporary wires.
      // Each named application input must occur directly at the assignment.
      val comb = "(?s)assign\\s+comb\\s*=\\s*([^;]+);".r.findFirstMatchIn(enabled)
        .map(_.group(1)).getOrElse(fail("missing comb assignment\n" + enabled))
      for (input <- Vector("a", "b", "c"))
        assert(("\\b" + input + "\\b").r.findFirstIn(comb).nonEmpty,
          "sum operand was hidden behind an emitter-created carrier: " + comb)
      assert(!comb.contains("[12:0]"), "a default-only low slice survived: " + comb)
      val disabled = generated("disabled")
      assert(disabled != enabled, "enabled and disabled fixtures did not exercise different lowering")
    }
  }
}
