package morphhdl.passes.transform

import morphhdl.ir.v1._
import morphhdl.passes.adapter.CanonicalIrPassAdapter
import morphhdl.passes.api.{DiagnosticSeverity, PassDiagnostic, PassExecutionStatus, PassId}

/** Local expression evidence; a ternary rewrite is not a removed wire. */
final case class BooleanTernaryRewrite(
    module: ModuleId,
    driver: DriverId,
    expressionPath: String,
    rule: String
)

final case class BooleanTernarySimplificationResult(
    output: Design,
    status: PassExecutionStatus,
    rewrites: Vector[BooleanTernaryRewrite],
    diagnostics: Vector[PassDiagnostic]
) {
  def changed: Boolean = status.changed
  def isSuccess: Boolean = !status.failed
}

/**
  * WA-07b: remove opposite-Boolean-constant mux branches in pure continuous RHSs.
  *
  * The condition is self-determined in a mux. Replacements therefore also use
  * self-determined, unsigned, one-bit truth producers. In particular, logical
  * NOT is used instead of a context-sized bitwise complement: !p and ~p agree
  * for a one-bit Boolean, but only the former stays one bit under widening.
  * A raw Bool reference can carry Z; positive passthrough must normalize it.
  * This pass does not infer values from drivers, names or parameter defaults.
  */
object BooleanTernarySimplificationPass {
  val passId: PassId = PassId.BooleanTernarySimplification
  private val one = IntExpr.Literal(BigInt(1))

  def run(handoff: CanonicalIrHandoff): BooleanTernarySimplificationResult = {
    require(handoff != null, "canonical IR handoff must not be null")
    run(CanonicalIrPassAdapter.bind(handoff).design)
  }

  def run(design: Design): BooleanTernarySimplificationResult = {
    require(design != null, "canonical IR design must not be null")
    CanonicalIrPassAdapter.bindFixture(design) match {
      case Left(_) => failure(design, "input canonical IR validation failed")
      case Right(_) =>
        val evidence = Vector.newBuilder[BooleanTernaryRewrite]
        // Keep input order and every object except an eligible driver's value.
        val output = design.copy(modules = design.modules.map { module =>
          val declarations = module.declarations.map(d => d.id -> d).toMap
          module.copy(drivers = module.drivers.map { driver =>
            declarations.get(driver.target) match {
              case Some(target) if eligible(driver, target) =>
                val value = rewrite(driver.value, "rhs", (path, rule) =>
                  evidence += BooleanTernaryRewrite(module.id, driver.id, path, rule))
                driver.copy(value = value)
              case _ => driver
            }
          })
        })
        CanonicalIrPassAdapter.bindFixture(output) match {
          case Left(_) => failure(design, "output canonical IR validation failed; input retained")
          case Right(_) =>
            val rewrites = evidence.result().sortBy(r =>
              (r.module.value, r.driver.value, r.expressionPath, r.rule))
            BooleanTernarySimplificationResult(
              if (rewrites.isEmpty) design else output,
              if (rewrites.isEmpty) PassExecutionStatus.Unchanged else PassExecutionStatus.Changed,
              rewrites, Vector.empty)
        }
    }
  }

  private def failure(input: Design, message: String): BooleanTernarySimplificationResult =
    BooleanTernarySimplificationResult(input, PassExecutionStatus.Failed, Vector.empty,
      Vector(PassDiagnostic("WA07B-INVALID-IR", DiagnosticSeverity.Error, message, Some(passId))))

  private def eligible(driver: Driver, target: Declaration): Boolean = {
    val observable = target.observability
    val kindAllowed = target.kind match {
      case DeclarationKind.InternalCombinational => true
      case DeclarationKind.Port(PortDirection.Output) => true
      case _ => false
    }
    driver.kind == DriverKind.Continuous &&
      driver.coverage == DriverCoverage.FullObject && kindAllowed &&
      target.packedType.nonEmpty && observable.complete &&
      !observable.keep && !observable.dontTouch && !observable.probe &&
      !observable.preserve && !observable.publicExport &&
      !observable.blackBoxBoundary && !observable.hierarchyBoundary &&
      target.attributes.isEmpty && driver.attributes.isEmpty
  }

  /** Only proven unsigned one-bit constants, including identity type fences. */
  private def booleanConstant(value: RtlExpr): Option[Boolean] = value match {
    case RtlExpr.Literal(n, 1, false) if n == 0 || n == 1 => Some(n == 1)
    case RtlExpr.Cast(inner, Signedness.Unsigned) => booleanConstant(inner)
    case RtlExpr.Resize(inner, width, Signedness.Unsigned) if width == one =>
      booleanConstant(inner)
    // Wider, signed, unknown and parameter-dependent branches are not guessed.
    case _ => None
  }

  /** Both width independence and no-Z behavior must be evident from the node. */
  private def isTruthProducer(value: RtlExpr): Boolean = value match {
    case RtlExpr.Unary(RtlUnaryOperator.LogicalNot, _) => true
    case RtlExpr.Binary(operator, _, _) => operator match {
      case RtlBinaryOperator.LogicalAnd | RtlBinaryOperator.LogicalOr |
          RtlBinaryOperator.Equal | RtlBinaryOperator.NotEqual |
          RtlBinaryOperator.LessThan | RtlBinaryOperator.LessThanOrEqual |
          RtlBinaryOperator.GreaterThan | RtlBinaryOperator.GreaterThanOrEqual => true
      case _ => false
    }
    case _ => false
  }

  private def not(value: RtlExpr): RtlExpr =
    RtlExpr.Unary(RtlUnaryOperator.LogicalNot, value)

  private def booleanize(value: RtlExpr): RtlExpr = booleanConstant(value) match {
    case Some(truth) => RtlExpr.Literal(if (truth) BigInt(1) else BigInt(0), 1)
    case None if isTruthProducer(value) => value
    // !! preserves Verilog truth conversion, including a known 1 among X/Z
    // bits, and normalizes scalar Z to X. Boolean metadata alone cannot do so.
    case None => not(not(value))
  }

  private def inverse(value: RtlExpr): RtlExpr = booleanConstant(value) match {
    case Some(truth) => RtlExpr.Literal(if (truth) BigInt(0) else BigInt(1), 1)
    case None => value match {
      // A nested inverse mux may expose double logical negation. Normalize
      // instead of returning an unconverted multi-bit or raw-Z operand.
      case RtlExpr.Unary(RtlUnaryOperator.LogicalNot, inner) => booleanize(inner)
      case _ => not(value)
    }
  }

  /** Bottom-up traversal; a nonmatching parent never hides a supported child. */
  private def rewrite(original: RtlExpr, path: String, record: (String, String) => Unit): RtlExpr = {
    def child(value: RtlExpr, suffix: String): RtlExpr = rewrite(value, path + "." + suffix, record)
    val nested: RtlExpr = original match {
      case value: RtlExpr.Ref => value
      case value: RtlExpr.Literal => value
      case RtlExpr.Unary(operator, value) => RtlExpr.Unary(operator, child(value, "value"))
      case RtlExpr.Binary(operator, left, right) =>
        RtlExpr.Binary(operator, child(left, "left"), child(right, "right"))
      case RtlExpr.Mux(condition, yes, no) =>
        RtlExpr.Mux(child(condition, "condition"), child(yes, "yes"), child(no, "no"))
      case RtlExpr.Concat(values) =>
        RtlExpr.Concat(values.zipWithIndex.map { case (value, index) => child(value, s"item-$index") })
      case RtlExpr.BitSelect(value, index) =>
        RtlExpr.BitSelect(child(value, "value"), child(index, "index"))
      case RtlExpr.PartSelect(value, offset, width) =>
        RtlExpr.PartSelect(child(value, "value"), offset, width)
      case RtlExpr.Resize(value, width, signedness) =>
        RtlExpr.Resize(child(value, "value"), width, signedness)
      case RtlExpr.Cast(value, signedness) => RtlExpr.Cast(child(value, "value"), signedness)
    }
    nested match {
      case RtlExpr.Mux(condition, yes, no) =>
        (booleanConstant(yes), booleanConstant(no)) match {
          case (Some(true), Some(false)) =>
            record(path, "boolean-ternary-positive")
            booleanize(condition)
          case (Some(false), Some(true)) =>
            record(path, "boolean-ternary-inverse")
            inverse(condition)
          case _ => nested
        }
      case _ => nested
    }
  }
}
