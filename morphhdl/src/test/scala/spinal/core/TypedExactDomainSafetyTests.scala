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
      assert(error.code == "SPINAL-ELAB-DOMAIN-PROJECTION-SCOPE-EXPANSION")
    }

    val expressionError = intercept[ParameterizedVerilogException] {
      predicate.witness
    }
    assert(
      expressionError.code ==
        "SPINAL-ELAB-DOMAIN-PROJECTION-SCOPE-EXPANSION"
    )

    val truthError = intercept[ParameterizedVerilogException] {
      predicate.isSymbolic
    }
    assert(
      truthError.code == "SPINAL-ELAB-DOMAIN-PROJECTION-SCOPE-EXPANSION"
    )
  }

  test("supplied exact results reject null out-of-range and out-of-bounds values") {
    val parameter = testParameter
    val root = ElaborationIntegerParameterRoot.fresh(parameter.name)

    val nullResult = intercept[ParameterizedVerilogException] {
      ElabInt.fromSingleRootExpressionTrusted(
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
      ElabInt.fromSingleRootExpressionTrusted(
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
      ElabInt.fromSingleRootExpressionTrusted(
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

  test("public raw exact-domain mint and copied rendered metadata fail closed") {
    val parameter = testParameter
    val root = ElaborationIntegerParameterRoot.fresh(parameter.name)
    val raw = testExpression(
      parameter,
      root,
      default = 2,
      minimum = 1,
      maximum = 3,
      verilog = "DEPTH + 1000"
    )
    val evaluations = Vector(
      BigInt(1) -> BigInt(1),
      BigInt(2) -> BigInt(2),
      BigInt(3) -> BigInt(3)
    )

    val rawMint = intercept[ParameterizedVerilogException] {
      ElabInt.fromSingleRootExpression(raw, evaluations)
    }
    assert(
      rawMint.code ==
        "SPINAL-ELAB-INT-ANALYZED-SOURCE-AUTHORIZATION-REQUIRED"
    )

    val trusted = typedDepth(default = 2)
    val copiedInteger = trusted.expression.copy(verilog = "DEPTH + 1000")
    assert(copiedInteger.exactDomain.nonEmpty)
    val copiedIntegerError = intercept[ParameterizedVerilogException] {
      ElabInt.fromExpression(copiedInteger)
    }
    assert(
      copiedIntegerError.code ==
        "SPINAL-ELAB-DOMAIN-EXACT-AUTHORITY-MISSING"
    )

    val predicate = trusted > 1
    val copiedPredicate = predicate.expression.copy(verilog = "DEPTH == 1000")
    assert(copiedPredicate.exactDomain.nonEmpty)
    val copiedPredicateError = intercept[ParameterizedVerilogException] {
      ElabInt.requireAuthoritativeBooleanDomain(
        copiedPredicate,
        "copied predicate",
        "SPINAL-ELAB-BOOL-EXACT-DOMAIN-REQUIRED"
      )
    }
    assert(
      copiedPredicateError.code ==
        "SPINAL-ELAB-DOMAIN-EXACT-AUTHORITY-MISSING"
    )
  }

  test("conservative supplied bounds normalize to exact extrema") {
    val parameter = testParameter
    val root = ElaborationIntegerParameterRoot.fresh(parameter.name)
    val value = ElabInt.fromSingleRootExpressionTrusted(
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
    assert(
      intercept[ParameterizedVerilogException](left + raw).code ==
        "SPINAL-ELAB-DOMAIN-EVIDENCE-MISSING"
    )
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
        "SPINAL-ELAB-DOMAIN-PROJECTION-SCOPE-EXPANSION"
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
      ElabInt.fromSingleRootExpressionTrusted(
        projected,
        domain.universe.toVector.sorted.map(value => value -> (value % 2))
      )
    }
    assert(
      error.code ==
        "SPINAL-ELAB-DOMAIN-PROJECTION-RECERTIFICATION-UNSUPPORTED"
    )
    val copiedError = intercept[ParameterizedVerilogException] {
      ElabInt.fromSingleRootExpressionTrusted(
        projected.copy(),
        domain.universe.toVector.sorted.map(value => value -> (value % 2))
      )
    }
    assert(
      copiedError.code ==
        "SPINAL-ELAB-DOMAIN-EXACT-AUTHORITY-MISSING"
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
      copiedError.code == "SPINAL-ELAB-DOMAIN-EXACT-AUTHORITY-MISSING"
    )
  }

  test("derived projections retain authority while copied partial evidence cannot reacquire it") {
    val depth = typedDepth(default = 1)
    val root = exact(depth.expression).root

    ElaborationDomainContext.withAdmitted(
      root,
      Set(BigInt(1), BigInt(2), BigInt(3)),
      sourceLocation = None
    ) {
      val derived = (depth + 1).log2Up
      assert(derived.minimum == 1)
      assert(derived.maximum == 2)

      val projectedInteger =
        (depth + 0).projectedExpression("copied integer projection")
      val integerError = intercept[ParameterizedVerilogException] {
        ElabInt.fromExpression(projectedInteger.copy()).bits
      }
      assert(
        integerError.code ==
          "SPINAL-ELAB-DOMAIN-EXACT-AUTHORITY-MISSING"
      )

      val projectedBoolean =
        (depth > 1).projectedExpression("copied Boolean projection")
      val authorizedPredicate = depth > 1
      val authorizedNegation = !authorizedPredicate
      val authorizedConjunction = authorizedPredicate && true
      val authorizedInteger = authorizedPredicate.toElabInt
      assert(authorizedNegation.expression.projectionProvenance.nonEmpty)
      assert(authorizedConjunction.expression.projectionProvenance.nonEmpty)
      assert(authorizedInteger.expression.projectionProvenance.nonEmpty)

      val copiedPredicate =
        ElabBool(projectedBoolean.copy(), ElabBool.Unknown)
      val copiedWitnessError = intercept[ParameterizedVerilogException] {
        copiedPredicate.witness
      }
      val copiedTruthError = intercept[ParameterizedVerilogException] {
        copiedPredicate.isAlwaysTrue
      }
      val copiedNegationError = intercept[ParameterizedVerilogException] {
        !copiedPredicate
      }
      val copiedConjunctionError = intercept[ParameterizedVerilogException] {
        copiedPredicate && true
      }
      val copiedIntegerError = intercept[ParameterizedVerilogException] {
        copiedPredicate.toElabInt
      }
      Vector(
        copiedWitnessError,
        copiedTruthError,
        copiedNegationError,
        copiedConjunctionError,
        copiedIntegerError
      ).foreach { error =>
        assert(
          error.code ==
            "SPINAL-ELAB-DOMAIN-PROJECTION-IDENTITY-MISSING"
        )
      }
    }
  }

  test("authoritative Boolean domains reject forged summaries keys and schemas") {
    val schema = ElaborationIntegerParameter(
      "BOOLEAN_AUTHORITY",
      default = 2,
      minimum = 1,
      maximum = 3
    )
    val root = ElaborationIntegerParameterRoot.fresh("BOOLEAN_AUTHORITY")
    ElaborationExactDomain.checked[Boolean](
      root,
      schema,
      Vector(
        BigInt(1) -> false,
        BigInt(2) -> true,
        BigInt(3) -> true
      ),
      sourceLocation = None,
      role = "Boolean authority root binding"
    )

    def predicate(
        default: Boolean,
        expressionSchema: ElaborationIntegerParameter,
        domainSchema: ElaborationIntegerParameter,
        evaluations: Vector[(BigInt, Boolean)]
    ): ElabBool =
      ElabBool(
        ElaborationBooleanExpression(
          verilog = "(BOOLEAN_AUTHORITY > 1)",
          default = default,
          parameters = Vector(expressionSchema),
          parameterRoots = Vector(root),
          exactDomain = Some(
            ElaborationExactDomain[Boolean](
              root,
              domainSchema,
              evaluations
            )
          )
        ),
        ElabBool.Unknown
      )

    val representativeMismatch = predicate(
      default = false,
      expressionSchema = schema,
      domainSchema = schema,
      evaluations = Vector(
        BigInt(1) -> false,
        BigInt(2) -> true,
        BigInt(3) -> false
      )
    )
    val representativeError = intercept[ParameterizedVerilogException] {
      representativeMismatch.isAlwaysTrue
    }

    val foreignSchema = predicate(
      default = true,
      expressionSchema = schema.copy(),
      domainSchema = schema,
      evaluations = Vector(
        BigInt(1) -> false,
        BigInt(2) -> true,
        BigInt(3) -> true
      )
    )
    val schemaError = intercept[ParameterizedVerilogException] {
      foreignSchema.witness
    }

    val copiedSchema = schema.copy()
    val copiedSchemaEverywhere = predicate(
      default = true,
      expressionSchema = copiedSchema,
      domainSchema = copiedSchema,
      evaluations = Vector(
        BigInt(1) -> false,
        BigInt(2) -> true,
        BigInt(3) -> true
      )
    )
    val copiedSchemaError = intercept[ParameterizedVerilogException] {
      copiedSchemaEverywhere.isAlwaysTrue
    }

    val duplicateKeys = predicate(
      default = true,
      expressionSchema = schema,
      domainSchema = schema,
      evaluations = Vector(
        BigInt(1) -> false,
        BigInt(1) -> true,
        BigInt(2) -> true,
        BigInt(3) -> true
      )
    )
    val duplicateError = intercept[ParameterizedVerilogException] {
      duplicateKeys.isAlwaysTrue
    }

    Vector(
      representativeError,
      schemaError,
      copiedSchemaError,
      duplicateError
    ).foreach { error =>
      assert(error.code == "SPINAL-ELAB-BOOL-EXACT-DOMAIN-REQUIRED")
    }

    val concreteDerived = ElabInt.literal(1).elabEq(1)
    assert(concreteDerived.isAlwaysTrue)
    assert(concreteDerived.witness)
  }

  test("integer derivations reject malformed raw evidence before projection") {
    val schema = ElaborationIntegerParameter(
      "INTEGER_DERIVATION_AUTHORITY",
      default = 2,
      minimum = 1,
      maximum = 3
    )
    val root =
      ElaborationIntegerParameterRoot.fresh("INTEGER_DERIVATION_AUTHORITY")
    ElaborationExactDomain.checked[BigInt](
      root,
      schema,
      Vector(
        BigInt(1) -> BigInt(1),
        BigInt(2) -> BigInt(2),
        BigInt(3) -> BigInt(3)
      ),
      sourceLocation = None,
      role = "integer derivation root binding"
    )
    val malformed = ElabInt.fromTrustedExactExpressionForTest(
      ElaborationIntegerExpression(
        verilog = "INTEGER_DERIVATION_AUTHORITY",
        default = 2,
        minimum = 1,
        maximum = 3,
        parameters = Vector(schema),
        parameterRoots = Vector(root),
        exactDomain = Some(
          ElaborationExactDomain[BigInt](
            root,
            schema,
            Vector(
              BigInt(1) -> BigInt(4),
              BigInt(2) -> BigInt(5),
              BigInt(3) -> BigInt(6)
            )
          )
        )
      )
    )

    val arithmeticError = intercept[ParameterizedVerilogException] {
      malformed + 0
    }
    val transformError = intercept[ParameterizedVerilogException] {
      malformed.log2Up
    }
    val predicateError = intercept[ParameterizedVerilogException] {
      malformed > 0
    }
    val copiedSchema = schema.copy()
    val replacementBindingError = intercept[ParameterizedVerilogException] {
      ElaborationExactDomain.checked[BigInt](
        root,
        copiedSchema,
        Vector(
          BigInt(1) -> BigInt(1),
          BigInt(2) -> BigInt(2),
          BigInt(3) -> BigInt(3)
        ),
        sourceLocation = None,
        role = "replacement integer derivation root binding"
      )
    }
    assert(
      replacementBindingError.code ==
        "SPINAL-ELAB-DOMAIN-ROOT-SCHEMA-IDENTITY-CONFLICT"
    )
    val copiedSchemaEverywhere = ElabInt.fromTrustedExactExpressionForTest(
      ElaborationIntegerExpression(
        verilog = "INTEGER_DERIVATION_AUTHORITY",
        default = 2,
        minimum = 1,
        maximum = 3,
        parameters = Vector(copiedSchema),
        parameterRoots = Vector(root),
        exactDomain = Some(
          ElaborationExactDomain[BigInt](
            root,
            copiedSchema,
            Vector(
              BigInt(1) -> BigInt(1),
              BigInt(2) -> BigInt(2),
              BigInt(3) -> BigInt(3)
            )
          )
        )
      )
    )
    val copiedSchemaError = intercept[ParameterizedVerilogException] {
      copiedSchemaEverywhere + 0
    }
    Vector(
      arithmeticError,
      transformError,
      predicateError,
      copiedSchemaError
    ).foreach { error =>
      assert(error.code == "SPINAL-ELAB-DOMAIN-EVIDENCE-MISSING")
    }

    val depth = typedDepth(default = 2)
    val domain = exact(depth.expression)
    ElaborationDomainContext.withAdmitted(
      domain.root,
      Set(BigInt(1), BigInt(2), BigInt(3)),
      sourceLocation = None
    ) {
      val authorizedTransform = (depth + 0).log2Up
      val authorizedPredicate = (depth + 0) > 1
      assert(authorizedTransform.expression.projectionProvenance.nonEmpty)
      assert(authorizedPredicate.expression.projectionProvenance.nonEmpty)
      assert(authorizedTransform.minimum == 0)
      assert(authorizedTransform.maximum == 2)
      assert(authorizedPredicate.isSymbolic)
    }
  }

  test("derived-domain operations cannot bind fresh public source metadata") {
    val integerSchema = ElaborationIntegerParameter(
      "FRESH_INTEGER_DERIVATION",
      default = 2,
      minimum = 1,
      maximum = 3
    )
    val integerRoot =
      ElaborationIntegerParameterRoot.fresh("FRESH_INTEGER_DERIVATION")
    val integerDomain = ElaborationExactDomain[BigInt](
      integerRoot,
      integerSchema,
      Vector(
        BigInt(1) -> BigInt(1),
        BigInt(2) -> BigInt(2),
        BigInt(3) -> BigInt(3)
      )
    )
    val rawInteger = ElabInt.fromTrustedExactExpressionForTest(
      ElaborationIntegerExpression(
        verilog = "FRESH_INTEGER_DERIVATION",
        default = 2,
        minimum = 1,
        maximum = 3,
        parameters = Vector(integerSchema),
        parameterRoots = Vector(integerRoot),
        exactDomain = Some(integerDomain)
      )
    )

    assert(!integerRoot.isAuthoritativeSchema(integerSchema))
    val pow2Error = intercept[ParameterizedVerilogException] {
      rawInteger.isPow2
    }
    val genericHelperError = intercept[ParameterizedVerilogException] {
      ElabInt.checkedDerivedDomain(
        integerDomain,
        integerDomain.evaluations.map { case (rootValue, result) =>
          rootValue -> (result > 1)
        },
        sourceLocation = None,
        role = "fresh public generic derivation"
      )
    }
    Vector(pow2Error, genericHelperError).foreach { error =>
      assert(error.code == "SPINAL-ELAB-DOMAIN-EVIDENCE-MISSING")
    }
    assert(!integerRoot.isAuthoritativeSchema(integerSchema))

    val booleanSchema = ElaborationIntegerParameter(
      "FRESH_BOOLEAN_DERIVATION",
      default = 2,
      minimum = 1,
      maximum = 3
    )
    val booleanRoot =
      ElaborationIntegerParameterRoot.fresh("FRESH_BOOLEAN_DERIVATION")
    val rawBoolean = ElabBool(
      ElaborationBooleanExpression(
        verilog = "(FRESH_BOOLEAN_DERIVATION > 1)",
        default = true,
        parameters = Vector(booleanSchema),
        parameterRoots = Vector(booleanRoot),
        exactDomain = Some(
          ElaborationExactDomain[Boolean](
            booleanRoot,
            booleanSchema,
            Vector(
              BigInt(1) -> false,
              BigInt(2) -> true,
              BigInt(3) -> true
            )
          )
        )
      ),
      ElabBool.Unknown
    )

    assert(!booleanRoot.isAuthoritativeSchema(booleanSchema))
    val negationError = intercept[ParameterizedVerilogException] {
      !rawBoolean
    }
    val conjunctionError = intercept[ParameterizedVerilogException] {
      rawBoolean && true
    }
    val disjunctionError = intercept[ParameterizedVerilogException] {
      rawBoolean || false
    }
    val integerConversionError = intercept[ParameterizedVerilogException] {
      rawBoolean.toElabInt
    }
    Vector(
      negationError,
      conjunctionError,
      disjunctionError,
      integerConversionError
    ).foreach { error =>
      assert(error.code == "SPINAL-ELAB-BOOL-EXACT-DOMAIN-REQUIRED")
    }
    assert(!booleanRoot.isAuthoritativeSchema(booleanSchema))
  }

  test("Boolean binary derivations validate both source identities and tables") {
    val source = typedParameter(
      "BOOLEAN_BINARY_AUTHORITY",
      default = 2,
      minimum = 1,
      maximum = 3
    )
    val canonical = source > 1
    val domain = exact(source.expression)
    val schema = domain.parameter
    val copiedSchema = schema.copy()

    def predicate(
        expressionSchema: ElaborationIntegerParameter,
        domainSchema: ElaborationIntegerParameter,
        evaluations: Vector[(BigInt, Boolean)]
    ): ElabBool =
      ElabBool(
        ElaborationBooleanExpression(
          verilog = "(BOOLEAN_BINARY_AUTHORITY > 1)",
          default = true,
          parameters = Vector(expressionSchema),
          parameterRoots = Vector(domain.root),
          exactDomain = Some(
            ElaborationExactDomain[Boolean](
              domain.root,
              domainSchema,
              evaluations
            )
          )
        ),
        ElabBool.Unknown
      )

    val copiedEverywhere = predicate(
      copiedSchema,
      copiedSchema,
      Vector(
        BigInt(1) -> false,
        BigInt(2) -> true,
        BigInt(3) -> true
      )
    )
    val duplicateTable = predicate(
      schema,
      schema,
      Vector(
        BigInt(1) -> false,
        BigInt(1) -> true,
        BigInt(2) -> true,
        BigInt(3) -> true
      )
    )

    val copiedConjunctionError = intercept[ParameterizedVerilogException] {
      canonical && copiedEverywhere
    }
    val copiedDisjunctionError = intercept[ParameterizedVerilogException] {
      canonical || copiedEverywhere
    }
    val duplicateConjunctionError = intercept[ParameterizedVerilogException] {
      canonical && duplicateTable
    }
    val duplicateDisjunctionError = intercept[ParameterizedVerilogException] {
      canonical || duplicateTable
    }
    Vector(
      copiedConjunctionError,
      copiedDisjunctionError,
      duplicateConjunctionError,
      duplicateDisjunctionError
    ).foreach { error =>
      assert(error.code == "SPINAL-ELAB-BOOL-EXACT-DOMAIN-REQUIRED")
    }
    assert(domain.root.isAuthoritativeSchema(schema))
    assert(!domain.root.isAuthoritativeSchema(copiedSchema))
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
    val first = ElabInt.fromSingleRootExpressionTrusted(
      expression,
      Vector(BigInt(1) -> BigInt(0), BigInt(2) -> BigInt(1), BigInt(3) -> BigInt(2))
    )
    val second = ElabInt.fromSingleRootExpressionTrusted(
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
