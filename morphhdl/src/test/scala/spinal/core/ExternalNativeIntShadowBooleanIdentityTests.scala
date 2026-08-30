package spinal.core

import org.scalatest.funsuite.AnyFunSuite

class ExternalNativeIntShadowBooleanIdentityTests extends AnyFunSuite {
  private def schema: ElaborationIntegerParameter =
    ElaborationIntegerParameter(
      name = "FLAG",
      default = 1,
      minimum = 0,
      maximum = 1
    )

  private def predicate(
      parameter: ElaborationIntegerParameter,
      sourceLocation: String
  ): ElaborationBooleanExpression =
    ElaborationBooleanExpression(
      verilog = "(FLAG == 1)",
      default = true,
      parameters = Vector(parameter),
      sourceLocation = Some(sourceLocation)
    )

  test("Boolean shadow predicate equivalence accepts one shared completed root") {
    val declaration = schema
    val left = predicate(declaration, "left.scala:1")
    val right = predicate(declaration, "right.scala:2")

    assert(
      ExternalNativeIntShadowRegistry.equivalentBooleanExpression(left, right)
    )
  }

  test("Boolean shadow predicate equivalence rejects independent completed roots") {
    // The two schemas are value-equal, so text/default/schema comparison alone
    // cannot distinguish these separately declared Boolean roots.
    val left = predicate(schema, "shared.scala:1")
    val right = predicate(schema, "shared.scala:1")

    assert(
      !ExternalNativeIntShadowRegistry.equivalentBooleanExpression(left, right)
    )
  }
}

/** Test-only access to exact roots completed inside the owning core package. */
object ExternalNativeIntCompletedRootTestProbe {
  def apply(
      expression: ElaborationIntegerExpression
  ): Vector[ElaborationIntegerParameterRoot] =
    expression.completedParameterRoots

  def apply(
      expression: ElaborationBooleanExpression
  ): Vector[ElaborationIntegerParameterRoot] =
    expression.completedParameterRoots
}
