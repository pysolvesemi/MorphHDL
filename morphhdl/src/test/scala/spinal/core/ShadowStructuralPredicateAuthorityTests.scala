package spinal.core

import java.nio.file.Files

import scala.collection.JavaConverters._

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.MorphVerilog

private object ShadowStructuralPredicateAuthorityFixture {
  private val Source = Some("ShadowStructuralPredicateAuthorityTests.scala:receipt")
  private val TrueLabel = "g_shadow_receipt_true"
  private val FalseLabel = "g_shadow_receipt_false"

  private def failureCode(body: => Unit): String =
    try {
      body
      "<accepted>"
    } catch {
      case error: ParameterizedVerilogException => error.code
    }

  final class ForeignOwnerProbe(
      receipt: ExternalNativeIntStructuralPredicateReceipt,
      pending: ParameterizedStructuralPending,
      whenTrue: ParameterizedStructuralBlock,
      whenFalse: ParameterizedStructuralBlock
  ) extends Component {
    setDefinitionName("ShadowStructuralPredicateForeignOwnerProbe")

    val alive = out(Bool())
    alive := False

    val rejectionCode = failureCode {
      ExternalNativeIntStructuralPublisher.registerIf(
        receipt,
        receipt.condition,
        pending,
        TrueLabel,
        FalseLabel,
        whenTrue,
        whenFalse,
        Source
      )
    }
  }

  final class ReceiptProbe extends Component {
    setDefinitionName("ShadowStructuralPredicateReceiptProbe")

    val alive = out(Bool())
    alive := True

    var copiedConditionCode = "<not-run>"
    var targetMismatchCode = "<not-run>"
    var foreignOwnerCode = "<not-run>"
    var replayCode = "<not-run>"
    var retrySucceeded = false
    var registeredRegionCount = -1
    var exactTargetRetained = false

    private val parameter =
      ElaborationIntegerParameter("SHADOW_RECEIPT_DEPTH", 2, 1, 3)
    private def shadowExpression = ElaborationIntegerExpression(
      verilog = parameter.name,
      default = parameter.default,
      minimum = parameter.minimum,
      maximum = parameter.maximum,
      parameters = Vector(parameter),
      sourceLocation = Source
    )

    ExternalNativeIntFormalizationTestAccess.withDefinitionExpressionBoundary(
      shadowExpression,
      Source.get,
      role = "shadow structural predicate authority test"
    ) {
      val root = ExternalNativeIntShadowRegistry.captureArgumentTracked(
        parameter.default.toInt,
        "depth",
        "depth-ref",
        Source.get
      )
      val witness = ExternalNativeIntShadowRegistry.comparisonTracked(
        ">",
        root,
        "depth-ref",
        leftLiteral = false,
        right = 1,
        rightReference = "",
        rightLiteral = true,
        resultReference = "predicate-ref",
        name = "predicate",
        sourceLocation = Source.get
      )
      val whenTrue = ParameterizedStructure.captureBlock(this, Source) {
        val marker = Bool().setName("shadow_receipt_true_marker")
        marker := True
      }
      val pending =
        ParameterizedStructure.beginPending(this, "generate-if", Source)
      val whenFalse = ParameterizedStructure.captureBlock(this, Source) {
        val marker = Bool().setName("shadow_receipt_false_marker")
        marker := False
      }
      val receipt =
        ExternalNativeIntStructuralPublisher.definitionPredicateTracked(
          "predicate-ref",
          witness,
          pending,
          TrueLabel,
          FalseLabel,
          whenTrue,
          whenFalse,
          Source
        )

      copiedConditionCode = failureCode {
        ExternalNativeIntStructuralPublisher.registerIf(
          receipt,
          receipt.condition.copy(),
          pending,
          TrueLabel,
          FalseLabel,
          whenTrue,
          whenFalse,
          Source
        )
      }
      targetMismatchCode = failureCode {
        ExternalNativeIntStructuralPublisher.registerIf(
          receipt,
          receipt.condition,
          pending,
          TrueLabel + "_foreign",
          FalseLabel,
          whenTrue,
          whenFalse,
          Source
        )
      }

      val foreign = new ForeignOwnerProbe(receipt, pending, whenTrue, whenFalse)
      foreignOwnerCode = foreign.rejectionCode

      ExternalNativeIntStructuralPublisher.registerIf(
        receipt,
        receipt.condition,
        pending,
        TrueLabel,
        FalseLabel,
        whenTrue,
        whenFalse,
        Source
      )
      retrySucceeded = true

      val registered = ParameterizedStructure.regionsOf(this).collect {
        case value: ParameterizedStructure.StructuralIf => value
      }
      registeredRegionCount = registered.size
      exactTargetRetained = registered.headOption.exists { value =>
        (value.condition eq receipt.condition) &&
        value.whenTrueLabel == TrueLabel &&
        value.whenFalseLabel == FalseLabel &&
        (value.whenTrue eq whenTrue) &&
        (value.whenFalse eq whenFalse)
      }

      replayCode = failureCode {
        ExternalNativeIntStructuralPublisher.registerIf(
          receipt,
          receipt.condition,
          pending,
          TrueLabel,
          FalseLabel,
          whenTrue,
          whenFalse,
          Source
        )
      }
      registeredRegionCount = ParameterizedStructure.regionsOf(this).size
    }
  }
}

class ShadowStructuralPredicateAuthorityTests extends AnyFunSuite {
  import ShadowStructuralPredicateAuthorityFixture._

  test("shadow structural predicate receipts reject copied foreign and replayed targets without mutation") {
    val directory = Files.createTempDirectory("shadow-structural-predicate-authority-")
    try {
      var probe: ReceiptProbe = null
      MorphVerilog(SpinalConfig(targetDirectory = directory.toString)) {
        probe = new ReceiptProbe
        probe
      }

      assert(
        probe.copiedConditionCode ==
          "MORPH-FRONTEND-NATIVE-INT-STRUCTURAL-PREDICATE-CONDITION-MISMATCH"
      )
      assert(
        probe.targetMismatchCode ==
          "MORPH-FRONTEND-NATIVE-INT-STRUCTURAL-PREDICATE-TARGET-MISMATCH"
      )
      assert(
        probe.foreignOwnerCode ==
          "MORPH-FRONTEND-NATIVE-INT-STRUCTURAL-PREDICATE-OWNER-MISMATCH"
      )
      assert(
        probe.replayCode ==
          "MORPH-FRONTEND-NATIVE-INT-STRUCTURAL-PREDICATE-REPLAY"
      )
      assert(probe.retrySucceeded)
      assert(probe.registeredRegionCount == 1)
      assert(probe.exactTargetRetained)
    } finally {
      val stream = Files.walk(directory)
      try {
        stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
          Files.deleteIfExists(path)
        }
      } finally stream.close()
    }
  }
}
