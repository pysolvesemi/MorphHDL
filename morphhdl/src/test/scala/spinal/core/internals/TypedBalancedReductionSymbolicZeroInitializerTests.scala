package spinal.core.internals

import java.nio.file.Files
import morphhdl.frontend.HdlInt
import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.lib._

/** Independent graph proofs for the native zero aliases introduced by clone
  * initialization. These tests use the internal capture seam to mutate IR;
  * they do not expand callback bytecode admission.
  */
class TypedBalancedReductionSymbolicZeroInitializerTests extends AnyFunSuite {
  private def native: ElabBalancedReduction.Native[UInt] =
    (values, op, bridge) => new TraversableOnceAnyPimped[UInt](values).reduceBalancedTree(op, bridge)

  private def withWords(body: Vec[UInt] => Unit): Unit = {
    val width = HdlInt.param("WIDTH", 5, 1, 9).asElabInt
    SpinalConfig(targetDirectory = Files.createTempDirectory("symbolic-zero-initializer-").toString,
      headerWithDate = false).generateVerilog(new Component {
      val words = Vec(UInt(width bits), HdlInt.param("COUNT", 1, 1, 3))
      words.vec.foreach(_ := 0)
      body(words)
    })
  }

  private def capture(words: Vec[UInt], bridge: (UInt, Int) => UInt) =
    TypedBalancedReductionStageReplay.capture(words, (a: UInt, b: UInt) => a + b, bridge, native)

  private def zero(value: UInt): UInt = {
    val initial = cloneOf(value)
    initial := initial.getZero
    val registered = RegNext(value)
    registered.init(initial)
    registered
  }

  private def rejects(code: String)(body: => Any): Unit = {
    val error = intercept[IllegalArgumentException](body)
    assert(error.getMessage.contains(code), error.getMessage)
  }

  test("symbolic native zero aliases replay to the minimum one-bit domain") {
    withWords { words =>
      val certificate = capture(words, (value: UInt, _: Int) => zero(value))
      certificate.requireFreshness()
      assert(certificate.replay(words.vec.toVector).isReg)
      val oneBit = UInt(1 bits)
      oneBit := U(0)
      val narrowed = certificate.stages.head.bridges.head
        .replayWithWidth(oneBit, ElabInt.literal(1).expression)
      assert(narrowed.isReg && narrowed.getBitsWidth == 1)
      val resets = scala.collection.mutable.ArrayBuffer.empty[InitAssignmentStatement]
      narrowed.foreachStatements {
        case assignment: InitAssignmentStatement => resets += assignment
        case _ =>
      }
      assert(resets.size == 1)
      assert(resets.head.source.asInstanceOf[UIntLiteral].value == 0)
    }
  }

  test("symbolic aliases do not authorize nonzero initializer terminals") {
    withWords { words =>
      rejects("BRIDGE-INITIALIZER") {
        capture(words, (value: UInt, _: Int) => {
          val initial = cloneOf(value)
          initial := U(1)
          val registered = RegNext(value)
          registered.init(initial)
          registered
        })
      }
    }
  }

  test("same-witness foreign declaration roots cannot authorize a zero alias") {
    withWords { words =>
      val foreign = HdlInt.param("FOREIGN", 5, 1, 9).asElabInt.expression
      rejects("BRIDGE-INITIALIZER") {
        capture(words, (value: UInt, _: Int) => {
          val initial = cloneOf(value)
          ParameterizedWidth.retainNativeMuxWidth(initial, Some(foreign))
          initial := U(0)
          val registered = RegNext(value)
          registered.init(initial)
          registered
        })
      }
    }
  }

  test("same-default changed width functions cannot authorize a zero alias") {
    withWords { words =>
      rejects("BRIDGE-INITIALIZER") {
        capture(words, (value: UInt, _: Int) => {
          val initial = cloneOf(value)
          val width = ParameterizedWidth.expressionOf(initial).get
          val changed = ElaborationWidthAuthority.subtract(ElabInt.literal(10).expression, width)
          assert(changed.default == width.default)
          assert(changed.minimum == width.minimum && changed.maximum == width.maximum)
          ParameterizedWidth.retainNativeMuxWidth(initial, Some(changed))
          initial := U(0)
          val registered = RegNext(value)
          registered.init(initial)
          registered
        })
      }
    }
  }

  test("certified symbolic zero aliases freeze their exact retained width function") {
    withWords { words =>
      val certificate = capture(words, (value: UInt, _: Int) => zero(value))
      val initial = certificate.captured.rows.head.bridge.declarations.find(value =>
        !value.isReg && ParameterizedWidth.expressionOf(value).exists(_.parameters.nonEmpty)).get
      val width = ParameterizedWidth.expressionOf(initial).get
      val changed = ElaborationWidthAuthority.subtract(ElabInt.literal(10).expression, width)
      ParameterizedWidth.retainNativeMuxWidth(initial, Some(changed))
      rejects("GRAPH-CHANGED") { certificate.requireFreshness() }
    }
  }

  test("a concrete zero descendant cannot gain same-witness symbolic metadata after certification") {
    withWords { words =>
      val certificate = capture(words, (value: UInt, _: Int) => zero(value))
      val descendant = certificate.captured.rows.head.bridge.declarations.find(value =>
        !value.isReg && ParameterizedWidth.expressionOf(value).isEmpty && value.getBitsWidth == 5).get
      val foreign = HdlInt.param("FOREIGN", 5, 1, 9).asElabInt.expression
      ParameterizedWidth.retainNativeMuxWidth(descendant, Some(foreign))
      rejects("GRAPH-CHANGED") { certificate.requireFreshness() }
    }
  }

  test("ordinary initializer traversal cannot reuse a cached zero-only descendant proof") {
    withWords { words =>
      var shared: UInt = null
      try rejects("ordinary initializer cannot reuse a zero-only width proof") {
        capture(words, (value: UInt, _: Int) => {
          shared = UInt(1 bits)
          shared.assignFrom(UIntLiteral(BigInt(0), 5))
          val initial = cloneOf(value)
          initial := shared
          val first = RegNext(value)
          first.init(shared)
          val second = RegNext(first)
          second.init(initial)
          second
        })
      } finally {
        // Restore the deliberately oversized native literal after the proof
        // rejection so ordinary elaboration does not add an unrelated error.
        if (shared != null) shared.head.source = UIntLiteral(BigInt(0), 1)
      }
    }
  }
}
