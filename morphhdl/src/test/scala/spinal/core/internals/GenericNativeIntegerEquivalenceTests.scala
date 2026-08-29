package spinal.core.internals

import org.scalatest.funsuite.AnyFunSuite

import spinal.core._

/**
  * Non-library-specific contract for the generic native integer equivalence
  * engine used by parameterized SpinalHDL lowering.
  */
class GenericNativeIntegerEquivalenceTests extends AnyFunSuite {
  private sealed trait Formula
  private case object AddressWidthPlusOne extends Formula
  private case object AddressWidthOfTwice extends Formula
  private case object AddressWidthOnly extends Formula
  private case object AddressWidthOfNext extends Formula

  test("independent native expressions are proven equal over their complete formal domain") {
    val parameter = ElaborationIntegerParameter(
      name = "SIZE",
      default = BigInt(8),
      minimum = BigInt(2),
      maximum = BigInt(64)
    )
    val left = capture(parameter, AddressWidthPlusOne, "left")
    val right = capture(parameter, AddressWidthOfTwice, "right")

    assert(ExternalParameterizedIntegerEquivalence.proves(left, right))
  }

  test("equal defaults do not authorize formulas that diverge elsewhere") {
    val parameter = ElaborationIntegerParameter(
      name = "SIZE",
      default = BigInt(5),
      minimum = BigInt(2),
      maximum = BigInt(8)
    )
    val left = capture(parameter, AddressWidthOnly, "left")
    val right = capture(parameter, AddressWidthOfNext, "right")

    assert(left.default == right.default)
    assert(!ExternalParameterizedIntegerEquivalence.proves(left, right))
  }

  test("an expression without exact compiler provenance is rejected") {
    val parameter = ElaborationIntegerParameter(
      name = "SIZE",
      default = BigInt(8),
      minimum = BigInt(2),
      maximum = BigInt(64)
    )
    val proven = capture(parameter, AddressWidthPlusOne, "proven")
    val fabricated = proven.copy(sourceLocation = Some("fabricated"))

    assert(!ExternalParameterizedIntegerEquivalence.proves(proven, fabricated))
  }

  test("different formal schemas are rejected even when defaults agree") {
    val leftParameter = ElaborationIntegerParameter(
      name = "SIZE",
      default = BigInt(8),
      minimum = BigInt(2),
      maximum = BigInt(64)
    )
    val rightParameter = leftParameter.copy(maximum = BigInt(32))
    val left = capture(leftParameter, AddressWidthPlusOne, "left")
    val right = capture(rightParameter, AddressWidthOfTwice, "right")

    assert(!ExternalParameterizedIntegerEquivalence.proves(left, right))
  }

  private def capture(
      parameter: ElaborationIntegerParameter,
      formula: Formula,
      role: String
  ): ElaborationIntegerExpression = {
    val location = s"GenericNativeIntegerEquivalenceTests:$role:$formula"
    val root = ElaborationIntegerExpression(
      verilog = parameter.name,
      default = parameter.default,
      minimum = parameter.minimum,
      maximum = parameter.maximum,
      parameters = Vector(parameter),
      sourceLocation = Some(location)
    )
    val token = ExternalNativeIntFormalizationToken(
      callSite = location,
      valueOrigin = location,
      role = role
    )
    var result: Option[ElaborationIntegerExpression] = None

    ExternalNativeIntShadowRegistry.capture(root, token, "selected") {
      val witness = parameter.default.toInt
      val selected = ExternalNativeIntShadowRegistry.captureArgumentTracked(
        witness,
        name = "selectedRoot",
        reference = "root",
        sourceLocation = location
      )

      val (resultWitness, resultReference) = formula match {
        case AddressWidthPlusOne =>
          val addressWidth = ExternalNativeIntShadowRegistry.unaryTracked(
            operation = "addressWidth",
            value = selected,
            valueReference = "root",
            resultReference = "addressWidth",
            name = "addressWidth",
            sourceLocation = location
          )
          val sum = ExternalNativeIntShadowRegistry.binaryTracked(
            operation = "+",
            left = addressWidth,
            leftReference = "addressWidth",
            leftLiteral = false,
            right = 1,
            rightReference = "",
            rightLiteral = true,
            resultReference = "result",
            name = "result",
            sourceLocation = location
          )
          sum -> "result"

        case AddressWidthOfTwice =>
          val twice = ExternalNativeIntShadowRegistry.binaryTracked(
            operation = "*",
            left = selected,
            leftReference = "root",
            leftLiteral = false,
            right = 2,
            rightReference = "",
            rightLiteral = true,
            resultReference = "twice",
            name = "twice",
            sourceLocation = location
          )
          val addressWidth = ExternalNativeIntShadowRegistry.unaryTracked(
            operation = "addressWidth",
            value = twice,
            valueReference = "twice",
            resultReference = "result",
            name = "result",
            sourceLocation = location
          )
          addressWidth -> "result"

        case AddressWidthOnly =>
          val addressWidth = ExternalNativeIntShadowRegistry.unaryTracked(
            operation = "addressWidth",
            value = selected,
            valueReference = "root",
            resultReference = "result",
            name = "result",
            sourceLocation = location
          )
          addressWidth -> "result"

        case AddressWidthOfNext =>
          val next = ExternalNativeIntShadowRegistry.binaryTracked(
            operation = "+",
            left = selected,
            leftReference = "root",
            leftLiteral = false,
            right = 1,
            rightReference = "",
            rightLiteral = true,
            resultReference = "next",
            name = "next",
            sourceLocation = location
          )
          val addressWidth = ExternalNativeIntShadowRegistry.unaryTracked(
            operation = "addressWidth",
            value = next,
            valueReference = "next",
            resultReference = "result",
            name = "result",
            sourceLocation = location
          )
          addressWidth -> "result"
      }

      result = ExternalNativeIntShadowRegistry.definitionExpressionTracked(
        reference = resultReference,
        witness = resultWitness,
        sourceLocation = location,
        positiveWidth = true
      )
      ()
    }

    result.getOrElse {
      fail(s"formula $formula did not retain one native integer expression")
    }
  }
}
