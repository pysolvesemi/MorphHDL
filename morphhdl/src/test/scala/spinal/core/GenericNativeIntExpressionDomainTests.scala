package spinal.core

import org.scalatest.funsuite.AnyFunSuite

/**
  * Component-independent contract for the native-Int expression provenance
  * used by parameterized width validation. These tests deliberately construct
  * no StreamFifo, StreamFifoCC or other library component.
  */
class GenericNativeIntExpressionDomainTests extends AnyFunSuite {
  private val Location = "GenericNativeIntExpressionDomainTests.scala:1"

  private final case class CapturedExpressions(
      left: ElaborationIntegerExpression,
      right: ElaborationIntegerExpression,
      unequal: ElaborationIntegerExpression
  )

  private def captureExpressions(
      parameterName: String = "SIZE"
  ): CapturedExpressions = {
    val parameter = ElaborationIntegerParameter(
      name = parameterName,
      default = BigInt(8),
      minimum = BigInt(2),
      maximum = BigInt(16)
    )
    val rootExpression = ElaborationIntegerExpression(
      verilog = parameterName,
      default = parameter.default,
      minimum = parameter.minimum,
      maximum = parameter.maximum,
      parameters = Vector(parameter),
      sourceLocation = Some(Location)
    )
    val token = ExternalNativeIntFormalizationToken(
      callSite = Location,
      valueOrigin = s"test parameter $parameterName",
      role = "generic native expression proof"
    )

    var leftExpression: ElaborationIntegerExpression = null
    var rightExpression: ElaborationIntegerExpression = null
    var unequalExpression: ElaborationIntegerExpression = null

    ExternalNativeIntShadowRegistry.capture(
      expression = rootExpression,
      token = token,
      argumentName = "size"
    ) {
      val size = ExternalNativeIntShadowRegistry.captureArgumentTracked(
        value = 8,
        name = "size",
        reference = "size-ref",
        sourceLocation = Location
      )
      val sizeAddressWidth = ExternalNativeIntShadowRegistry.unaryTracked(
        operation = "addressWidth",
        value = size,
        valueReference = "size-ref",
        resultReference = "size-address-width-ref",
        name = "sizeAddressWidth",
        sourceLocation = Location
      )
      val left = ExternalNativeIntShadowRegistry.binaryTracked(
        operation = "+",
        left = sizeAddressWidth,
        leftReference = "size-address-width-ref",
        leftLiteral = false,
        right = 1,
        rightReference = "",
        rightLiteral = true,
        resultReference = "left-ref",
        name = "leftWidth",
        sourceLocation = Location
      )
      val doubledSize = ExternalNativeIntShadowRegistry.binaryTracked(
        operation = "*",
        left = size,
        leftReference = "size-ref",
        leftLiteral = false,
        right = 2,
        rightReference = "",
        rightLiteral = true,
        resultReference = "doubled-size-ref",
        name = "doubledSize",
        sourceLocation = Location
      )
      val right = ExternalNativeIntShadowRegistry.unaryTracked(
        operation = "addressWidth",
        value = doubledSize,
        valueReference = "doubled-size-ref",
        resultReference = "right-ref",
        name = "rightWidth",
        sourceLocation = Location
      )
      val tripledSize = ExternalNativeIntShadowRegistry.binaryTracked(
        operation = "*",
        left = size,
        leftReference = "size-ref",
        leftLiteral = false,
        right = 3,
        rightReference = "",
        rightLiteral = true,
        resultReference = "tripled-size-ref",
        name = "tripledSize",
        sourceLocation = Location
      )
      val unequal = ExternalNativeIntShadowRegistry.unaryTracked(
        operation = "addressWidth",
        value = tripledSize,
        valueReference = "tripled-size-ref",
        resultReference = "unequal-ref",
        name = "unequalWidth",
        sourceLocation = Location
      )

      leftExpression = ExternalNativeIntShadowRegistry
        .definitionExpressionTracked(
          reference = "left-ref",
          witness = left,
          sourceLocation = Location,
          positiveWidth = true
        )
        .get
      rightExpression = ExternalNativeIntShadowRegistry
        .definitionExpressionTracked(
          reference = "right-ref",
          witness = right,
          sourceLocation = Location,
          positiveWidth = true
        )
        .get
      unequalExpression = ExternalNativeIntShadowRegistry
        .definitionExpressionTracked(
          reference = "unequal-ref",
          witness = unequal,
          sourceLocation = Location,
          positiveWidth = true
        )
        .get
    }

    CapturedExpressions(leftExpression, rightExpression, unequalExpression)
  }

  test("independently retained native expressions expose one exact shared root") {
    val expressions = captureExpressions()
    val leftRoot = ExternalNativeIntShadowRegistry
      .definitionExpressionRootOf(expressions.left)
      .get
    val rightRoot = ExternalNativeIntShadowRegistry
      .definitionExpressionRootOf(expressions.right)
      .get

    assert(leftRoot eq rightRoot)
    (leftRoot.minimum to leftRoot.maximum).foreach { value =>
      val left = ExternalNativeIntShadowRegistry.evaluateDefinitionExpression(
        expressions.left,
        leftRoot,
        value
      )
      val right = ExternalNativeIntShadowRegistry.evaluateDefinitionExpression(
        expressions.right,
        rightRoot,
        value
      )
      assert(left.nonEmpty)
      assert(left == right)
      assert(left.exists(_ > 0))
    }
  }

  test("bounded evaluation rejects a witness-equal but domain-unequal expression") {
    val expressions = captureExpressions()
    val root = ExternalNativeIntShadowRegistry
      .definitionExpressionRootOf(expressions.left)
      .get

    assert(expressions.left.default == expressions.unequal.default)
    val differs = (root.minimum to root.maximum).exists { value =>
      ExternalNativeIntShadowRegistry.evaluateDefinitionExpression(
        expressions.left,
        root,
        value
      ) != ExternalNativeIntShadowRegistry.evaluateDefinitionExpression(
        expressions.unequal,
        root,
        value
      )
    }
    assert(differs)
  }

  test("equal rendered schemas from independent captures do not share identity") {
    val first = captureExpressions("SIZE")
    val second = captureExpressions("SIZE")
    val firstRoot = ExternalNativeIntShadowRegistry
      .definitionExpressionRootOf(first.left)
      .get
    val secondRoot = ExternalNativeIntShadowRegistry
      .definitionExpressionRootOf(second.left)
      .get

    assert(first.left.verilog == second.left.verilog)
    assert(first.left.default == second.left.default)
    assert(!(firstRoot eq secondRoot))
    assert(
      ExternalNativeIntShadowRegistry.evaluateDefinitionExpression(
        first.left,
        secondRoot,
        secondRoot.default
      ).isEmpty
    )
  }
}
