package spinal.core

import org.scalatest.funsuite.AnyFunSuite

import morphhdl.frontend.HdlInt

/** Fail-closed contracts for exact typed elaboration evidence. */
class TypedExactDomainSafetyTests extends AnyFunSuite {
  test("oversized direct domains are capped before enumeration") {
    val parameter = ElaborationIntegerParameter(
      "DEPTH",
      default = 1,
      minimum = 0,
      maximum = BigInt(Int.MaxValue)
    )

    val error = intercept[ParameterizedVerilogException] {
      ElabInt.directParameter(parameter, sourceLocation = None)
    }

    assert(error.code == "SPINAL-ELAB-DOMAIN-SIZE-UNSUPPORTED")
  }

  test("a direct parameter width is not retained as globally direct in a narrowed branch") {
    val depth = typedDepth(default = 5)
    val domain = exact(depth.expression)

    val global = depth.bits
    assert(global.parameter.contains(domain.parameter))
    assert(global.value == 5)

    ElaborationDomainContext.withAdmitted(
      domain.root,
      Set(BigInt(3)),
      sourceLocation = None
    ) {
      val narrowed = depth.bits
      assert(narrowed.value == 3)
      assert(narrowed.parameter.isEmpty)
      assert(narrowed.expression.exists(_.default == 3))
      assert(narrowed.expression.exists(_.minimum == 3))
      assert(narrowed.expression.exists(_.maximum == 3))
    }
  }

  test("partial division modulo and pow2 evidence evaluates only admitted values") {
    val depth = typedDepth(default = 5)
    val root = exact(depth.expression).root
    val divisor = depth - 1
    val exponent = depth * 10

    var quotient: ElabInt = null
    var remainder: ElabInt = null
    var power: ElabInt = null
    var predicate: ElabBool = null

    ElaborationDomainContext.withAdmitted(
      root,
      Set(BigInt(2), BigInt(3), BigInt(4)),
      sourceLocation = None
    ) {
      // DEPTH == 1 is invalid for the divisor, so division and modulo use the
      // admitted subset without ever evaluating that excluded zero.
      quotient = ElabInt.literal(12) / divisor
      remainder = ElabInt.literal(13) % divisor
      predicate = quotient > 4

      assert(
        exact(quotient.expression).evaluations == Vector(
          BigInt(2) -> BigInt(12),
          BigInt(3) -> BigInt(6),
          BigInt(4) -> BigInt(4)
        )
      )
      assert(
        exact(remainder.expression).evaluations == Vector(
          BigInt(2) -> BigInt(0),
          BigInt(3) -> BigInt(1),
          BigInt(4) -> BigInt(1)
        )
      )
      assert(predicate.isSymbolic)
    }

    ElaborationDomainContext.withAdmitted(
      root,
      Set(BigInt(1), BigInt(2), BigInt(3)),
      sourceLocation = None
    ) {
      // The global exponent reaches 80, but this branch stays in [10, 30].
      power = exponent.pow2
      assert(
        exact(power.expression).evaluations == Vector(
          BigInt(1) -> (BigInt(1) << 10),
          BigInt(2) -> (BigInt(1) << 20),
          BigInt(3) -> (BigInt(1) << 30)
        )
      )
      assert(power.minimum == (BigInt(1) << 10))
      assert(power.maximum == (BigInt(1) << 30))
    }

    Vector(quotient, remainder, power).foreach { escaped =>
      val error = intercept[ParameterizedVerilogException] {
        escaped.minimum
      }
      assert(error.code == "SPINAL-ELAB-DOMAIN-EVIDENCE-SCOPE-MISMATCH")
    }

    val expressionError = intercept[ParameterizedVerilogException] {
      predicate.witness
    }
    assert(
      expressionError.code ==
        "SPINAL-ELAB-DOMAIN-EVIDENCE-SCOPE-MISMATCH"
    )

    val truthError = intercept[ParameterizedVerilogException] {
      predicate.isSymbolic
    }
    assert(truthError.code == "SPINAL-ELAB-DOMAIN-EVIDENCE-SCOPE-MISMATCH")
  }

  test("supplied exact results reject null out-of-range and out-of-bounds values") {
    val parameter = testParameter
    val root = ElaborationIntegerParameterRoot.fresh(parameter.name)

    val nullResult = intercept[ParameterizedVerilogException] {
      ElabInt.fromSingleRootExpression(
        testExpression(parameter, root, default = 2, minimum = 1, maximum = 3),
        Vector(
          BigInt(1) -> BigInt(1),
          BigInt(2) -> null.asInstanceOf[BigInt],
          BigInt(3) -> BigInt(3)
        )
      )
    }
    assert(nullResult.code == "SPINAL-ELAB-DOMAIN-EVIDENCE-RESULT-NULL")

    val tooLarge = BigInt(Int.MaxValue) + 1
    val outOfRange = intercept[ParameterizedVerilogException] {
      ElabInt.fromSingleRootExpression(
        testExpression(
          parameter,
          root,
          default = 0,
          minimum = 0,
          maximum = tooLarge
        ),
        Vector(
          BigInt(1) -> tooLarge,
          BigInt(2) -> BigInt(0),
          BigInt(3) -> BigInt(1)
        )
      )
    }
    assert(
      outOfRange.code ==
        "SPINAL-ELAB-DOMAIN-EVIDENCE-RESULT-OUT-OF-RANGE"
    )

    val outsideBounds = intercept[ParameterizedVerilogException] {
      ElabInt.fromSingleRootExpression(
        testExpression(parameter, root, default = 1, minimum = 0, maximum = 2),
        Vector(
          BigInt(1) -> BigInt(0),
          BigInt(2) -> BigInt(1),
          BigInt(3) -> BigInt(3)
        )
      )
    }
    assert(
      outsideBounds.code ==
        "SPINAL-ELAB-DOMAIN-EVIDENCE-RESULT-OUTSIDE-BOUNDS"
    )
  }

  test("conservative supplied bounds normalize to exact extrema") {
    val parameter = testParameter
    val root = ElaborationIntegerParameterRoot.fresh(parameter.name)
    val value = ElabInt.fromSingleRootExpression(
      testExpression(
        parameter,
        root,
        default = 0,
        minimum = -2,
        maximum = 2,
        verilog = "(DEPTH - DEPTH)"
      ),
      Vector(
        BigInt(1) -> BigInt(0),
        BigInt(2) -> BigInt(0),
        BigInt(3) -> BigInt(0)
      )
    )

    assert(value.minimum == 0)
    assert(value.maximum == 0)
    assert(value.expression.minimum == 0)
    assert(value.expression.maximum == 0)
  }

  test("derived exact integer results must fit the emitted integer domain") {
    val value = typedParameter("VALUE", default = 1, minimum = 1, maximum = 2)

    val error = intercept[ParameterizedVerilogException] {
      value * Int.MaxValue
    }

    assert(
      error.code == "SPINAL-ELAB-DOMAIN-EVIDENCE-RESULT-OUT-OF-RANGE"
    )
    assert(error.detail.contains("VALUE=2"))
  }

  test("branch-partial evidence cannot be downgraded across independent roots") {
    val left = typedParameter("LEFT", default = 1, minimum = 1, maximum = 3)
    val right = typedParameter("RIGHT", default = 1, minimum = 1, maximum = 3)
    val leftRoot = exact(left.expression).root

    ElaborationDomainContext.withAdmitted(
      leftRoot,
      Set(BigInt(2), BigInt(3)),
      sourceLocation = None
    ) {
      val partial = left + 1
      val error = intercept[ParameterizedVerilogException] {
        partial + right
      }
      assert(
        error.code ==
          "SPINAL-ELAB-DOMAIN-PARTIAL-CORRELATION-UNSUPPORTED"
      )
    }
  }

  test("full exact evidence cannot be downgraded across independent roots") {
    val left = typedParameter("LEFT", default = 1, minimum = 1, maximum = 3)
    val right = typedParameter("RIGHT", default = 1, minimum = 1, maximum = 3)
    val expected = "SPINAL-ELAB-DOMAIN-EXACT-CORRELATION-UNSUPPORTED"

    assert(intercept[ParameterizedVerilogException](left + right).code == expected)
    assert(intercept[ParameterizedVerilogException](left < right).code == expected)
    assert(
      intercept[ParameterizedVerilogException] {
        (left > 1) && (right > 1)
      }.code == expected
    )

    val rawParameter = ElaborationIntegerParameter("RAW", 1, 1, 3)
    val raw = ElabInt.fromExpression(
      ElaborationIntegerExpression(
        verilog = "RAW",
        default = 1,
        minimum = 1,
        maximum = 3,
        parameters = Vector(rawParameter),
        parameterRoots = Vector(rawParameter.declarationRoot)
      )
    )
    assert(intercept[ParameterizedVerilogException](left + raw).code == expected)
  }

  test("partial Boolean evidence cannot escape into an independent root") {
    val left = typedParameter("LEFT", default = 1, minimum = 1, maximum = 3)
    val right = typedParameter("RIGHT", default = 1, minimum = 1, maximum = 3)
    val leftRoot = exact(left.expression).root
    var partial: ElabBool = null

    ElaborationDomainContext.withAdmitted(
      leftRoot,
      Set(BigInt(2)),
      sourceLocation = None
    ) {
      partial = left > 1
    }

    val error = intercept[ParameterizedVerilogException] {
      partial && (right > 1)
    }
    assert(
      error.code ==
        "SPINAL-ELAB-DOMAIN-PARTIAL-CORRELATION-UNSUPPORTED"
    )
  }

  test("identity-valued compound widths do not masquerade as direct parameters") {
    val depth = typedDepth(default = 5)
    val width = (depth + 0).bits

    assert(width.parameter.isEmpty)
    assert(width.expression.nonEmpty)
    assert(width.expression.exists(_.verilog != "DEPTH"))
  }

  test("equal summaries from disjoint projections remain identity-distinct") {
    val depth = typedDepth(default = 1)
    val parity = depth % 2
    val root = exact(depth.expression).root
    var first: ElaborationIntegerExpression = null
    var second: ElaborationIntegerExpression = null

    ElaborationDomainContext.withAdmitted(
      root,
      Set(BigInt(1)),
      sourceLocation = None
    ) {
      first = parity.projectedExpression("first disjoint projection")
    }
    ElaborationDomainContext.withAdmitted(
      root,
      Set(BigInt(3)),
      sourceLocation = None
    ) {
      second = parity.projectedExpression("second disjoint projection")
    }

    assert(first.default == second.default)
    assert(first.minimum == second.minimum)
    assert(first.maximum == second.maximum)
    assert(!ElabInt.equivalentExpression(first, second))
    assert(!ExternalFormalParameterRegistry.equivalentExpression(first, second))
    assert(!ElabInt.equivalentExpression(first, first.copy()))
  }

  test("projected expressions cannot be recertified with replacement evidence") {
    val depth = typedDepth(default = 1)
    val parity = depth % 2
    val domain = exact(depth.expression)
    var projected: ElaborationIntegerExpression = null

    ElaborationDomainContext.withAdmitted(
      domain.root,
      Set(BigInt(1)),
      sourceLocation = None
    ) {
      projected = parity.projectedExpression("recertification source")
    }

    val error = intercept[ParameterizedVerilogException] {
      ElabInt.fromSingleRootExpression(
        projected,
        domain.universe.toVector.sorted.map(value => value -> (value % 2))
      )
    }
    assert(
      error.code ==
        "SPINAL-ELAB-DOMAIN-PROJECTION-RECERTIFICATION-UNSUPPORTED"
    )
    val copiedError = intercept[ParameterizedVerilogException] {
      ElabInt.fromSingleRootExpression(
        projected.copy(),
        domain.universe.toVector.sorted.map(value => value -> (value % 2))
      )
    }
    assert(
      copiedError.code ==
        "SPINAL-ELAB-DOMAIN-PROJECTION-RECERTIFICATION-UNSUPPORTED"
    )
  }

  test("projected expressions cannot expand beyond their original branch") {
    val depth = typedDepth(default = 1)
    val root = exact(depth.expression).root
    var escapedInteger: ElaborationIntegerExpression = null
    var escapedBoolean: ElaborationBooleanExpression = null

    ElaborationDomainContext.withAdmitted(
      root,
      Set(BigInt(5), BigInt(6), BigInt(7), BigInt(8)),
      sourceLocation = None
    ) {
      escapedInteger = (depth + 0).projectedExpression("integer escape source")
      escapedBoolean = (depth > 4).projectedExpression("Boolean escape source")
      assert(ElabInt.fromExpression(escapedInteger).bits.value == 5)
      assert(ElabBool(escapedBoolean, ElabBool.Unknown).isAlwaysTrue)
    }

    val integerError = intercept[ParameterizedVerilogException] {
      ElabInt.fromExpression(escapedInteger).bits
    }
    assert(
      integerError.code == "SPINAL-ELAB-DOMAIN-PROJECTION-SCOPE-EXPANSION"
    )
    val booleanError = intercept[ParameterizedVerilogException] {
      ElabBool(escapedBoolean, ElabBool.Unknown).witness
    }
    assert(
      booleanError.code == "SPINAL-ELAB-DOMAIN-PROJECTION-SCOPE-EXPANSION"
    )
    val truthError = intercept[ParameterizedVerilogException] {
      ElabBool(escapedBoolean, ElabBool.Unknown).isAlwaysTrue
    }
    assert(
      truthError.code == "SPINAL-ELAB-DOMAIN-PROJECTION-SCOPE-EXPANSION"
    )
    val copiedError = intercept[ParameterizedVerilogException] {
      ElabInt.fromExpression(escapedInteger.copy()).bits
    }
    assert(
      copiedError.code == "SPINAL-ELAB-DOMAIN-EVIDENCE-SCOPE-MISMATCH"
    )
  }

  test("equal exact summaries with different evaluations remain distinct") {
    val parameter = testParameter
    val root = ElaborationIntegerParameterRoot.fresh(parameter.name)
    val expression = testExpression(
      parameter,
      root,
      default = 1,
      minimum = 0,
      maximum = 2,
      verilog = "crossed(DEPTH)"
    )
    val first = ElabInt.fromSingleRootExpression(
      expression,
      Vector(BigInt(1) -> BigInt(0), BigInt(2) -> BigInt(1), BigInt(3) -> BigInt(2))
    )
    val second = ElabInt.fromSingleRootExpression(
      expression,
      Vector(BigInt(1) -> BigInt(2), BigInt(2) -> BigInt(1), BigInt(3) -> BigInt(0))
    )
    val firstProjected = first.projectedExpression("first crossed table")
    val secondProjected = second.projectedExpression("second crossed table")

    assert(firstProjected.default == secondProjected.default)
    assert(firstProjected.minimum == secondProjected.minimum)
    assert(firstProjected.maximum == secondProjected.maximum)
    assert(!ElabInt.equivalentExpression(firstProjected, secondProjected))
    assert(
      !ExternalFormalParameterRegistry.equivalentExpression(
        firstProjected,
        secondProjected
      )
    )
  }

  private def typedDepth(default: Int): ElabInt =
    typedParameter("DEPTH", default, minimum = 1, maximum = 8)

  private def typedParameter(
      name: String,
      default: Int,
      minimum: Int,
      maximum: Int
  ): ElabInt =
    HdlInt
      .param(
        name,
        default = BigInt(default),
        min = BigInt(minimum),
        max = BigInt(maximum)
      )
      .asElabInt

  private def exact(
      expression: ElaborationIntegerExpression
  ): ElaborationExactDomain[BigInt] =
    expression.exactDomain.getOrElse(fail("integer exact evidence is missing"))

  private def testParameter: ElaborationIntegerParameter =
    ElaborationIntegerParameter(
      "DEPTH",
      default = 2,
      minimum = 1,
      maximum = 3
    )

  private def testExpression(
      parameter: ElaborationIntegerParameter,
      root: ElaborationIntegerParameterRoot,
      default: BigInt,
      minimum: BigInt,
      maximum: BigInt,
      verilog: String = "DEPTH"
  ): ElaborationIntegerExpression =
    ElaborationIntegerExpression(
      verilog = verilog,
      default = default,
      minimum = minimum,
      maximum = maximum,
      parameters = Vector(parameter),
      parameterRoots = Vector(root)
    )
}
