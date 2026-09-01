package spinal.core.internals

import org.scalatest.funsuite.AnyFunSuite

import spinal.core._

/** Adversarial coverage for the one deliberate projection-provenance
  * relaxation in native assignment width comparison.
  */
class RetainedWidthExpressionEquivalenceTests extends AnyFunSuite {
  test("a complete same-root domain permits projection provenance loss in either order") {
    val schema = parameterSchema
    val projected = completeExpression(schema, freshRoot(schema))
    val unprojected = projected.copy()

    assert(projected.projectionProvenance.nonEmpty)
    assert(unprojected.projectionProvenance.isEmpty)
    assert(projected.exactDomain.exists(domain => domain.evidenceValues == domain.universe))
    assert(equivalent(projected, unprojected))
    assert(equivalent(unprojected, projected))
  }

  test("a partial same-root domain rejects projection provenance loss in either order") {
    val schema = parameterSchema
    val root = freshRoot(schema)
    val value = directExpression(schema, root)
    var projected: ElaborationIntegerExpression = null

    ElaborationDomainContext.withAdmitted(
      root,
      Set(BigInt(3), BigInt(4)),
      sourceLocation = None
    ) {
      projected = value.projectedExpression("partial retained-width test")
    }
    val unprojected = projected.copy()

    assert(projected.projectionProvenance.nonEmpty)
    assert(unprojected.projectionProvenance.isEmpty)
    assert(projected.exactDomain.exists(domain => domain.evidenceValues != domain.universe))
    assert(!equivalent(projected, unprojected))
    assert(!equivalent(unprojected, projected))
  }

  test("complete and partial evidence never compare equal in either order") {
    val schema = parameterSchema
    val root = freshRoot(schema)
    val value = directExpression(schema, root)
    val complete = value.projectedExpression("complete evidence test").copy()
    var partial: ElaborationIntegerExpression = null

    ElaborationDomainContext.withAdmitted(
      root,
      Set(BigInt(3), BigInt(4)),
      sourceLocation = None
    ) {
      partial = value.projectedExpression("partial evidence test").copy()
    }

    assert(complete.projectionProvenance.isEmpty)
    assert(partial.projectionProvenance.isEmpty)
    assert(complete.exactDomain.exists(domain => domain.evidenceValues == domain.universe))
    assert(partial.exactDomain.exists(domain => domain.evidenceValues != domain.universe))
    assert(!equivalent(complete, partial))
    assert(!equivalent(partial, complete))
  }

  test("complete exact evidence cannot excuse changed width summaries") {
    val schema = parameterSchema
    val base = completeExpression(schema, freshRoot(schema)).copy()
    val altered = Vector(
      "default" -> base.copy(default = BigInt(4)),
      "minimum" -> base.copy(minimum = BigInt(1)),
      "maximum" -> base.copy(maximum = BigInt(6))
    )

    assert(base.projectionProvenance.isEmpty)
    altered.foreach { case (field, expression) =>
      withClue(s"changed $field was accepted: ") {
        assert(!equivalent(base, expression))
        assert(!equivalent(expression, base))
      }
    }
  }

  test("equal complete tables with independent same-schema roots remain distinct") {
    val schema = parameterSchema
    val left = completeExpression(schema, freshRoot(schema)).copy()
    val right = completeExpression(schema, freshRoot(schema)).copy()

    assert(left.parameters == right.parameters)
    assert(left.parameterRoots.size == 1)
    assert(right.parameterRoots.size == 1)
    assert(left.parameterRoots.head ne right.parameterRoots.head)
    assert(left.exactDomain.map(_.evaluations) == right.exactDomain.map(_.evaluations))
    assert(!equivalent(left, right))
    assert(!equivalent(right, left))
  }

  private def equivalent(
      left: ElaborationIntegerExpression,
      right: ElaborationIntegerExpression
  ): Boolean =
    ExternalParameterizedVerilogNativeFallback
      .equivalentRetainedWidthExpressions(left, right)

  private def parameterSchema: ElaborationIntegerParameter =
    ElaborationIntegerParameter(
      "DEPTH",
      default = BigInt(3),
      minimum = BigInt(2),
      maximum = BigInt(5)
    )

  private def freshRoot(
      schema: ElaborationIntegerParameter
  ): ElaborationIntegerParameterRoot =
    ElaborationIntegerParameterRoot.fresh(schema.name)

  private def completeExpression(
      schema: ElaborationIntegerParameter,
      root: ElaborationIntegerParameterRoot
  ): ElaborationIntegerExpression =
    directExpression(schema, root)
      .projectedExpression("complete retained-width test")

  private def directExpression(
      schema: ElaborationIntegerParameter,
      root: ElaborationIntegerParameterRoot
  ): ElabInt =
    ElabInt.fromSingleRootExpressionTrusted(
      ElaborationIntegerExpression(
        verilog = schema.name,
        default = schema.default,
        minimum = schema.minimum,
        maximum = schema.maximum,
        parameters = Vector(schema),
        parameterRoots = Vector(root)
      ),
      ElaborationExactDomain
        .boundedValues(schema.minimum, schema.maximum)
        .map(value => value -> value)
    )
}
