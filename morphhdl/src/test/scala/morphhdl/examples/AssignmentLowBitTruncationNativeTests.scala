package morphhdl.examples {

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
          val assignment = low.head.asInstanceOf[DataAssignmentStatement]
          // Recreate the exact boundary left when an earlier canonical stage has
          // already consumed the explicit resize: the whole assignment now owns
          // the low projection of this wider generated carrier.
          if (kind == "absorbed") assignment.source = carrier
          val sourceBefore = assignment.source
          val carrierLiveBefore = carrier.parentScope != null
          val carrierReferencedBefore = references(sourceBefore, carrier)
          val carrierName = NativeWireNameProvenance.origin(carrier)
          val phase = new AssignmentLowBitTruncationNativePhase(intent)
          phase.impl(pc)
          val sourceAfter = assignment.source
          kind match {
            case "safe" | "absorbed" | "mixed" | "repeated" =>
              assert(carrierName.exists(value => value == morphhdl.ir.v1.NameOrigin.Unnamed ||
                value == morphhdl.ir.v1.NameOrigin.Generated), carrierName)
              assert(phase.rewrittenAssignments >= 1, "eligible low-bit receiver was not rewritten")
              assert(phase.rewrittenReferences >= 1, "the receiver changed without inlining its carrier")
              assert(!references(sourceAfter, carrier), "low-bit receiver still reads the removed boundary")
              if (kind == "mixed") {
                assert(carrier.parentScope != null, "shared carrier was deleted while an unsupported receiver remains")
                assert(references(protectedUse.head.asInstanceOf[DataAssignmentStatement].source, carrier))
              } else assert(carrier.parentScope == null, "dead eligible carrier was not removed")
            case "keep" | "vital" | "explicit" | "no-merge" =>
              // Protection belongs to the carrier identity. An inherited phase
              // may already have copied an independent expression away from it;
              // WIRE-TRUNC must never make the protected carrier newly dead or
              // rewrite an assignment that still references that identity.
              assert((carrier.parentScope != null) == carrierLiveBefore,
                "WIRE-TRUNC changed protected carrier liveness")
              if (carrierReferencedBefore) {
                assert(sourceAfter eq sourceBefore, "protected carrier receiver was changed")
                assert(references(sourceAfter, carrier), "protected carrier reference was removed")
              }
            case _ =>
              assert(sourceAfter eq sourceBefore, "unsupported receiver was changed")
              assert((carrier.parentScope != null) == carrierLiveBefore,
                "WIRE-TRUNC changed pre-existing carrier liveness for an unsupported receiver")
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
    val symbolicWidth = HdlInt.param("SYMBOLIC_WIDTH", 18, 14, 18)
    MorphVerilog(config) {
      new Component {
        setDefinitionName("LowBitNativeBoundary")
        val witnessIn = in Bits(witnessWidth bits)
        val witnessOut = out Bits(witnessWidth bits)
        witnessOut := witnessIn
        val a, b, c = in UInt(16 bits)
        val select = in UInt(5 bits)
        @dontName val first = a.resize(18) + b.resize(18)
        @dontName val bridge = UInt(18 bits)
        bridge := first
        @dontName val ordinary = bridge ^ c.resize(18)
        @dontName val expression = if (kind == "repeated") bridge ^ bridge
          else if (kind == "symbolic") {
            val symbolic = UInt(symbolicWidth bits)
            symbolic := ordinary.resize(symbolicWidth.asElabInt)
            symbolic
          } else ordinary
        carrier = expression
        kind match {
          case "keep" => expression.addAttribute("keep")
          case "vital" => expression.setAsVital()
          case "explicit" => expression.setName("userExpression")
          case "no-merge" => AssignmentLowBitTruncationTestAccess.preventCombMerge(expression)
          case _ =>
        }
        val resultWidth = if (kind == "widening") 20 else if (kind == "part-target") 18 else 13
        val result = out UInt(resultWidth bits)
        low = result
        kind match {
          case "high-slice" => result := expression(17 downto 5)
          case "widening" => result := expression.resize(20)
          case "signed" => result := expression.asSInt.resize(13).asUInt
          case "dynamic" => result := expression(select).asUInt.resize(13)
          case "part-target" => result(12 downto 0) := expression(12 downto 0)
          case "unsafe-blocking" =>
            val unsafe = UInt(18 bits).setName("unsafeComb")
            unsafe := a.resize(18)
            result := 0
            when(witnessIn.orR) { result := (expression ^ unsafe).resize(13) }
          case "budget" =>
            val expanded = (0 until 33).map(_ => expression).reduce(_ ^ _)
            result := expanded.resize(13)
          case _ => result := expression.resize(13)
        }
        if (kind == "mixed") {
          val other = out UInt(13 bits)
          protectedUse = other
          other := expression(17 downto 5)
        }
      }
    }
    assert(observed, "native boundary observer did not execute")
  }

  for (kind <- Vector("safe", "absorbed", "repeated", "mixed", "keep", "vital", "explicit", "no-merge",
      "high-slice", "widening", "signed", "dynamic", "part-target", "symbolic",
      "unsafe-blocking", "budget")) {
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
}

package spinal.core.internals {
  /** Test-only access to the real native no-merge tag, whose visibility is
    * restricted to spinal.core. Do not replace the tag with a weaker attribute.
    */
  object AssignmentLowBitTruncationTestAccess {
    def preventCombMerge(value: spinal.core.BaseType): Unit = {
      value.addTag(spinal.core.noBackendCombMerge)
      ()
    }
  }
}
