package spinal.core

/**
  * Immutable construction provenance for one branch-projected typed
  * expression. The root is compared only by JVM identity.
  *
  * Instances live in a private, non-constructor slot on the exact expression
  * object. Consequently case-class equality and Product arity are unchanged,
  * while `.copy` deliberately creates an expression with no provenance.
  */
private[spinal] final class ElaborationProjectionProvenance private[core] (
    val root: ElaborationIntegerParameterRoot,
    val admitted: Set[BigInt],
    val representative: BigInt
) {
  private[core] def sameAs(
      that: ElaborationProjectionProvenance
  ): Boolean =
    (that ne null) &&
      (root eq that.root) &&
      admitted == that.admitted &&
      representative == that.representative

  override def toString: String =
    s"ElaborationProjectionProvenance(${root.name}, admitted=${admitted.size}, representative=$representative)"
}

/** Validation shared by the private per-expression projection slots. */
private[spinal] object ElaborationProjectionProvenance {
  private[spinal] def integer(
      expression: ElaborationIntegerExpression,
      domain: ElaborationExactDomain[BigInt],
      admitted: Set[BigInt],
      representative: BigInt,
      role: String,
      sourceLocation: Option[String]
  ): ElaborationProjectionProvenance = {
    validateCommon(
      expression,
      expression.exactDomain,
      domain,
      admitted,
      representative,
      role,
      sourceLocation
    )
    val evaluated = admitted.toVector.sorted.map { rootValue =>
      domain.evaluate(rootValue).getOrElse {
        fail(
          "SPINAL-ELAB-DOMAIN-PROJECTION-EVIDENCE-INCOMPLETE",
          s"$role expression '${expression.verilog}' has no exact evaluation at ${domain.root.name}=$rootValue",
          sourceLocation.orElse(expression.sourceLocation)
        )
      }
    }
    val expectedDefault = domain.evaluate(representative).getOrElse {
      fail(
        "SPINAL-ELAB-DOMAIN-PROJECTION-EVIDENCE-INCOMPLETE",
        s"$role expression '${expression.verilog}' has no exact evaluation at representative ${domain.root.name}=$representative",
        sourceLocation.orElse(expression.sourceLocation)
      )
    }
    if (
      expression.default != expectedDefault ||
      expression.minimum != evaluated.min ||
      expression.maximum != evaluated.max
    ) {
      fail(
        "SPINAL-ELAB-DOMAIN-PROJECTION-SUMMARY-MISMATCH",
        s"$role expression '${expression.verilog}' retains default ${expression.default} in [${expression.minimum}, ${expression.maximum}], but its exact projection requires default $expectedDefault in [${evaluated.min}, ${evaluated.max}]",
        sourceLocation.orElse(expression.sourceLocation)
      )
    }
    new ElaborationProjectionProvenance(
      domain.root,
      admitted.toSet,
      representative
    )
  }

  private[spinal] def boolean(
      expression: ElaborationBooleanExpression,
      domain: ElaborationExactDomain[Boolean],
      admitted: Set[BigInt],
      representative: BigInt,
      role: String,
      sourceLocation: Option[String]
  ): ElaborationProjectionProvenance = {
    validateCommon(
      expression,
      expression.exactDomain,
      domain,
      admitted,
      representative,
      role,
      sourceLocation
    )
    val expectedDefault = domain.evaluate(representative).getOrElse {
      fail(
        "SPINAL-ELAB-DOMAIN-PROJECTION-EVIDENCE-INCOMPLETE",
        s"$role predicate '${expression.verilog}' has no exact evaluation at representative ${domain.root.name}=$representative",
        sourceLocation.orElse(expression.sourceLocation)
      )
    }
    if (expression.default != expectedDefault) {
      fail(
        "SPINAL-ELAB-DOMAIN-PROJECTION-SUMMARY-MISMATCH",
        s"$role predicate '${expression.verilog}' retains default ${expression.default}, but its exact projection requires $expectedDefault",
        sourceLocation.orElse(expression.sourceLocation)
      )
    }
    new ElaborationProjectionProvenance(
      domain.root,
      admitted.toSet,
      representative
    )
  }

  private def validateCommon[A, E](
      expression: E,
      expressionDomain: Option[ElaborationExactDomain[A]],
      domain: ElaborationExactDomain[A],
      admitted: Set[BigInt],
      representative: BigInt,
      role: String,
      sourceLocation: Option[String]
  ): Unit = {
    if (
      expression == null || domain == null || admitted == null ||
      sourceLocation == null
    ) {
      fail(
        "SPINAL-ELAB-DOMAIN-PROJECTION-NULL",
        "typed projection requires a non-null expression, exact domain, admitted set and source-location option",
        Option(sourceLocation).flatten
      )
    }
    if (sourceLocation.exists(_ == null)) {
      fail(
        "SPINAL-ELAB-DOMAIN-PROJECTION-SOURCE-OPTION-NULL",
        s"${Option(role).getOrElse("typed expression")} projection source-location option contains null",
        None
      )
    }
    if (role == null || role.trim.isEmpty) {
      fail(
        "SPINAL-ELAB-DOMAIN-PROJECTION-ROLE-INVALID",
        "typed projection requires a non-empty role",
        sourceLocation
      )
    }
    expressionDomain match {
      case Some(value) if value eq domain =>
      case Some(_) =>
        fail(
          "SPINAL-ELAB-DOMAIN-PROJECTION-EVIDENCE-IDENTITY-MISMATCH",
          s"$role carries a different exact-domain object than its projection evidence",
          sourceLocation
        )
      case None =>
        fail(
          "SPINAL-ELAB-DOMAIN-PROJECTION-EVIDENCE-MISSING",
          s"$role lacks exact evaluation evidence",
          sourceLocation
        )
    }
    if (
      admitted.isEmpty ||
      BigInt(admitted.size) > ElaborationExactDomain.MaximumDomainSize
    ) {
      fail(
        "SPINAL-ELAB-DOMAIN-PROJECTION-SIZE-UNSUPPORTED",
        s"$role projection must retain between one and ${ElaborationExactDomain.MaximumDomainSize} root values",
        sourceLocation
      )
    }
    if (!admitted.subsetOf(domain.evidenceValues)) {
      fail(
        "SPINAL-ELAB-DOMAIN-PROJECTION-OUTSIDE-EVIDENCE",
        s"$role projection admits root values without exact evaluation evidence",
        sourceLocation.orElse(domain.root.sourceLocation)
      )
    }
    val deterministicRepresentative =
      if (admitted.contains(domain.parameter.default)) domain.parameter.default
      else admitted.min
    if (representative != deterministicRepresentative) {
      fail(
        "SPINAL-ELAB-DOMAIN-PROJECTION-REPRESENTATIVE-INVALID",
        s"$role projection representative $representative does not match deterministic representative $deterministicRepresentative",
        sourceLocation.orElse(domain.root.sourceLocation)
      )
    }
  }

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String]
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
