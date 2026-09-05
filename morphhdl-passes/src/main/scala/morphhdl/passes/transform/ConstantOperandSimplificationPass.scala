package morphhdl.passes.transform

import morphhdl.ir.v1._
import morphhdl.passes.adapter.CanonicalIrPassAdapter
import morphhdl.passes.api.{DiagnosticSeverity, PassDiagnostic, PassExecutionStatus, PassId}

/** An expression rewrite, not a removed declaration or assignment. */
final case class ConstantOperandRewrite(
    module: ModuleId,
    driver: DriverId,
    expressionPath: String,
    rule: String
)

final case class ConstantOperandSimplificationResult(
    output: Design,
    status: PassExecutionStatus,
    rewrites: Vector[ConstantOperandRewrite],
    diagnostics: Vector[PassDiagnostic]
) {
  def changed: Boolean = status.changed
  def isSuccess: Boolean = !status.failed
}

/**
  * Local, four-state-safe simplification of pure canonical continuous RHSs.
  *
  * This pass neither follows drivers to propagate constants nor deletes wires.
  * A Boolean value interpretation is NOT evidence that an input cannot be Z.
  * Neutral bitwise rewrites require a syntactically proven non-Z producer.
  * Width-sensitive masks are checked against the enclosing evaluation width;
  * symbolic widths are never replaced with defaults or concrete witnesses.
  * Production selection and writeback belong to the common pipeline handoff.
  */
object ConstantOperandSimplificationPass {
  val passId: PassId = PassId.ConstantOperandSimplification
  private val one = IntExpr.Literal(BigInt(1))
  private val maximumFoldWidth = 4096
  private final case class Shape(width: IntExpr, signed: Boolean)
  private val booleanShape = Shape(one, signed = false)

  def run(handoff: CanonicalIrHandoff): ConstantOperandSimplificationResult = {
    require(handoff != null, "canonical IR handoff must not be null")
    run(CanonicalIrPassAdapter.bind(handoff).design)
  }

  def run(design: Design): ConstantOperandSimplificationResult = {
    require(design != null, "canonical IR design must not be null")
    CanonicalIrPassAdapter.bindFixture(design) match {
      case Left(_) => failure(design, "input canonical IR validation failed")
      case Right(view) =>
        val evidence = Vector.newBuilder[ConstantOperandRewrite]
        val output = view.design.copy(modules = view.design.modules.map { module =>
          val declarations = module.declarations.map(d => d.id -> d).toMap
          val rewriter = new Rewriter(declarations)
          module.copy(drivers = module.drivers.map { driver =>
            declarations.get(driver.target) match {
              case Some(target) if eligible(driver, target) =>
                val value = rewriter.rewrite(
                  driver.value,
                  target.packedType.map(_.width),
                  "rhs",
                  (path, rule) => evidence += ConstantOperandRewrite(
                    module.id, driver.id, path, rule
                  )
                )
                driver.copy(value = value)
              case _ => driver
            }
          })
        })
        CanonicalIrPassAdapter.bindFixture(output) match {
          case Left(_) => failure(design, "output canonical IR validation failed; input retained")
          case Right(validated) =>
            val rewrites = evidence.result().sortBy(r =>
              (r.module.value, r.driver.value, r.expressionPath, r.rule)
            )
            ConstantOperandSimplificationResult(
              if (rewrites.isEmpty) design else validated.design,
              if (rewrites.isEmpty) PassExecutionStatus.Unchanged else PassExecutionStatus.Changed,
              rewrites,
              Vector.empty
            )
        }
    }
  }

  private def failure(input: Design, message: String): ConstantOperandSimplificationResult =
    ConstantOperandSimplificationResult(
      input, PassExecutionStatus.Failed, Vector.empty,
      Vector(PassDiagnostic("WA07A-INVALID-IR", DiagnosticSeverity.Error, message, Some(passId)))
    )

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

  private def maximum(a: IntExpr, b: IntExpr): IntExpr = (a, b) match {
    case _ if a == b => a
    case (IntExpr.Literal(x), IntExpr.Literal(y)) => IntExpr.Literal(x.max(y))
    case _ => IntExpr.Max(a, b)
  }

  private def fixedWidth(width: IntExpr): Option[Int] = width match {
    case IntExpr.Literal(n) if n > 0 && n <= maximumFoldWidth => Some(n.toInt)
    case _ => None
  }

  private def zero(shape: Shape): RtlExpr = fixedWidth(shape.width) match {
    case Some(width) => RtlExpr.Literal(BigInt(0), width, shape.signed)
    case None => RtlExpr.Resize(
      RtlExpr.Literal(BigInt(0), 1), shape.width,
      if (shape.signed) Signedness.Signed else Signedness.Unsigned
    )
  }

  private def isZero(expr: RtlExpr): Boolean = expr match {
    case RtlExpr.Literal(value, _, _) => value == 0
    case RtlExpr.Resize(value, _, _) => isZero(value)
    case RtlExpr.Cast(value, _) => isZero(value)
    case _ => false
  }

  private def literalBits(value: RtlExpr.Literal): Option[BigInt] =
    if (value.width > 0 && value.width <= maximumFoldWidth)
      Some(value.value & ((BigInt(1) << value.width) - 1))
    else None

  private def literalTruth(expr: RtlExpr): Option[Boolean] = expr match {
    case value: RtlExpr.Literal => literalBits(value).map(_ != 0)
    case _ => None
  }

  /** Shifts, selections, casts and resizes can preserve Z; ordinary operators cannot. */
  private def cannotProduceZ(expr: RtlExpr): Boolean = expr match {
    case _: RtlExpr.Literal => true
    case _: RtlExpr.Ref => false
    case _: RtlExpr.Unary => true
    case RtlExpr.Binary(RtlBinaryOperator.ShiftLeft, value, _) => cannotProduceZ(value)
    case RtlExpr.Binary(RtlBinaryOperator.ShiftRight, value, _) => cannotProduceZ(value)
    case _: RtlExpr.Binary => true
    case RtlExpr.Mux(_, yes, no) => cannotProduceZ(yes) && cannotProduceZ(no)
    case RtlExpr.Concat(values) => values.forall(cannotProduceZ)
    case RtlExpr.BitSelect(value, _) => cannotProduceZ(value)
    case RtlExpr.PartSelect(value, _, _) => cannotProduceZ(value)
    case RtlExpr.Resize(value, _, _) => cannotProduceZ(value)
    case RtlExpr.Cast(value, _) => cannotProduceZ(value)
  }

  private def isPredicate(operator: RtlBinaryOperator): Boolean = operator match {
    case RtlBinaryOperator.LogicalAnd | RtlBinaryOperator.LogicalOr |
        RtlBinaryOperator.Equal | RtlBinaryOperator.NotEqual |
        RtlBinaryOperator.LessThan | RtlBinaryOperator.LessThanOrEqual |
        RtlBinaryOperator.GreaterThan | RtlBinaryOperator.GreaterThanOrEqual => true
    case _ => false
  }

  /** These producers are one bit regardless of a surrounding width context. */
  private def isTruthProducer(value: RtlExpr): Boolean = value match {
    case RtlExpr.Binary(op, _, _) => isPredicate(op)
    case RtlExpr.Unary(RtlUnaryOperator.LogicalNot, _) => true
    case _ => false
  }

  private final class Rewriter(declarations: Map[SymbolId, Declaration]) {
    private def shape(expr: RtlExpr): Option[Shape] = expr match {
      case RtlExpr.Ref(_, target, _, _) => declarations.get(target).flatMap(_.packedType).map(t =>
        Shape(t.width, t.signedness == Signedness.Signed)
      )
      case RtlExpr.Literal(_, width, signed) => Some(Shape(IntExpr.Literal(BigInt(width)), signed))
      case RtlExpr.Unary(RtlUnaryOperator.LogicalNot, _) => Some(booleanShape)
      case RtlExpr.Unary(_, value) => shape(value)
      case RtlExpr.Binary(operator, _, _) if isPredicate(operator) => Some(booleanShape)
      case RtlExpr.Binary(RtlBinaryOperator.ShiftLeft, left, _) => shape(left)
      case RtlExpr.Binary(RtlBinaryOperator.ShiftRight, left, _) => shape(left)
      case RtlExpr.Binary(_, left, right) => joinedShape(left, right)
      case RtlExpr.Mux(_, yes, no) => joinedShape(yes, no)
      case RtlExpr.Concat(values) =>
        val shapes = values.map(shape)
        if (shapes.nonEmpty && shapes.forall(_.nonEmpty)) {
          val widths = shapes.flatten.map(_.width)
          Some(Shape(widths.reduceLeft { (a, b) => (a, b) match {
            case (IntExpr.Literal(x), IntExpr.Literal(y)) => IntExpr.Literal(x + y)
            case _ => IntExpr.Add(a, b)
          } }, signed = false))
        } else None
      case RtlExpr.BitSelect(_, _) => Some(booleanShape)
      case RtlExpr.PartSelect(_, _, width) => Some(Shape(width, signed = false))
      case RtlExpr.Resize(_, width, signedness) => Some(Shape(width, signedness == Signedness.Signed))
      case RtlExpr.Cast(value, signedness) => shape(value).map(_.copy(signed = signedness == Signedness.Signed))
    }

    private def joinedShape(left: RtlExpr, right: RtlExpr): Option[Shape] =
      for (a <- shape(left); b <- shape(right))
        yield Shape(maximum(a.width, b.width), a.signed && b.signed)

    private def booleanize(value: RtlExpr): RtlExpr = literalTruth(value) match {
      case Some(truth) => RtlExpr.Literal(if (truth) BigInt(1) else BigInt(0), 1)
      case None if isTruthProducer(value) => value
      // Preserve both truth conversion and its self-determined width boundary.
      // Even a non-Z one-bit bitwise expression may widen in the outer context.
      case None => RtlExpr.Unary(RtlUnaryOperator.LogicalNot,
        RtlExpr.Unary(RtlUnaryOperator.LogicalNot, value))
    }

    private def allOnesAt(value: RtlExpr, width: IntExpr): Boolean = value match {
      case literal: RtlExpr.Literal =>
        fixedWidth(width).contains(literal.width) &&
          literalBits(literal).contains((BigInt(1) << literal.width) - 1)
      case _ => false
    }

    def rewrite(
        original: RtlExpr,
        contextWidth: Option[IntExpr],
        path: String,
        record: (String, String) => Unit
    ): RtlExpr = {
      val effectiveWidth = shape(original).map(s => contextWidth.map(maximum(s.width, _)).getOrElse(s.width))
      def child(value: RtlExpr, width: Option[IntExpr], suffix: String): RtlExpr =
        rewrite(value, width, path + "." + suffix, record)
      def self(value: RtlExpr, suffix: String): RtlExpr = child(value, None, suffix)
      val nested: RtlExpr = original match {
        case value: RtlExpr.Ref => value
        case value: RtlExpr.Literal => value
        case RtlExpr.Unary(RtlUnaryOperator.LogicalNot, value) =>
          RtlExpr.Unary(RtlUnaryOperator.LogicalNot, self(value, "value"))
        case RtlExpr.Unary(op, value) => RtlExpr.Unary(op, child(value, effectiveWidth, "value"))
        case RtlExpr.Binary(op, left, right) if op == RtlBinaryOperator.LogicalAnd || op == RtlBinaryOperator.LogicalOr =>
          RtlExpr.Binary(op, self(left, "left"), self(right, "right"))
        case RtlExpr.Binary(op, left, right) if isPredicate(op) =>
          val operandWidth = joinedShape(left, right).map(_.width)
          RtlExpr.Binary(op, child(left, operandWidth, "left"), child(right, operandWidth, "right"))
        case RtlExpr.Binary(op, left, right) if op == RtlBinaryOperator.ShiftLeft || op == RtlBinaryOperator.ShiftRight =>
          RtlExpr.Binary(op, child(left, effectiveWidth, "left"), self(right, "right"))
        case RtlExpr.Binary(op, left, right) =>
          RtlExpr.Binary(op, child(left, effectiveWidth, "left"), child(right, effectiveWidth, "right"))
        case RtlExpr.Mux(condition, yes, no) =>
          RtlExpr.Mux(self(condition, "condition"), child(yes, effectiveWidth, "yes"), child(no, effectiveWidth, "no"))
        case RtlExpr.Concat(values) => RtlExpr.Concat(values.zipWithIndex.map { case (v, i) => self(v, s"item-$i") })
        // Never turn a legal selected source into an illegal expression selection.
        case RtlExpr.BitSelect(value, index) => RtlExpr.BitSelect(value, self(index, "index"))
        case value: RtlExpr.PartSelect => value
        // Retain the assignment fence. Passing its width inward is conservative
        // even for a backend that first evaluates the child at its natural width.
        case RtlExpr.Resize(value, width, signedness) =>
          RtlExpr.Resize(child(value, Some(width), "value"), width, signedness)
        case RtlExpr.Cast(value, signedness) => RtlExpr.Cast(self(value, "value"), signedness)
      }
      simplify(nested, effectiveWidth) match {
        case Some((value, rule)) if value != nested => record(path, rule); value
        case _ => nested
      }
    }

    /**
      * Comparison and logical results stay self-determined one-bit values even
      * beside a wider (including captured unsized) literal. Preserve the binary
      * result's original packed type with a fence instead of dropping widening.
      * A one-bit bitwise NOT is intentionally not a predicate: outer context
      * can widen its computation before inversion.
      */
    private def widenedTruthMask(expr: RtlExpr): Option[(RtlExpr, String)] = expr match {
      case RtlExpr.Binary(op, left, right) if op == RtlBinaryOperator.BitwiseAnd ||
          op == RtlBinaryOperator.BitwiseOr || op == RtlBinaryOperator.BitwiseXor =>
        Vector(left -> right, right -> left).iterator.flatMap { case (value, mask) =>
          mask match {
            case literal: RtlExpr.Literal if isTruthProducer(value) =>
              for {
                bits <- literalBits(literal) if bits == 0 || bits == 1
                resultShape <- shape(expr) if !resultShape.signed
              } yield {
                val replacement: RtlExpr = op match {
                  case RtlBinaryOperator.BitwiseAnd => if (bits == 0) RtlExpr.Literal(BigInt(0), 1) else value
                  case RtlBinaryOperator.BitwiseOr => if (bits == 1) RtlExpr.Literal(BigInt(1), 1) else value
                  case RtlBinaryOperator.BitwiseXor => if (bits == 0) value else RtlExpr.Unary(RtlUnaryOperator.LogicalNot, value)
                  case _ => value
                }
                val fenced = if (resultShape == booleanShape) replacement
                  else RtlExpr.Resize(replacement, resultShape.width, Signedness.Unsigned)
                fenced -> "predicate-constant-mask"
              }
            case _ => None
          }
        }.take(1).toVector.headOption
      case _ => None
    }

    private def simplify(expr: RtlExpr, effectiveWidth: Option[IntExpr]): Option[(RtlExpr, String)] = {
      def replace(value: RtlExpr, rule: String): Option[(RtlExpr, String)] = Some(value -> rule)
      def sameShape(value: RtlExpr): Boolean = shape(value).nonEmpty && shape(value) == shape(expr)
      def neutral(value: RtlExpr, rule: String): Option[(RtlExpr, String)] =
        if (sameShape(value) && cannotProduceZ(value)) replace(value, rule) else None
      val ordinary: Option[(RtlExpr, String)] = expr match {
        case RtlExpr.Binary(RtlBinaryOperator.BitwiseAnd, left, right) if isZero(left) || isZero(right) =>
          shape(expr).map(s => zero(s) -> "bitwise-and-zero")
        case RtlExpr.Binary(RtlBinaryOperator.BitwiseOr, left, right) if isZero(left) => neutral(right, "bitwise-or-zero")
        case RtlExpr.Binary(RtlBinaryOperator.BitwiseOr, left, right) if isZero(right) => neutral(left, "bitwise-or-zero")
        case RtlExpr.Binary(RtlBinaryOperator.BitwiseXor, left, right) if isZero(left) => neutral(right, "bitwise-xor-zero")
        case RtlExpr.Binary(RtlBinaryOperator.BitwiseXor, left, right) if isZero(right) => neutral(left, "bitwise-xor-zero")
        case RtlExpr.Binary(op, left, right) if op == RtlBinaryOperator.BitwiseAnd || op == RtlBinaryOperator.BitwiseOr || op == RtlBinaryOperator.BitwiseXor =>
          val sides = Vector(left -> right, right -> left)
          sides.iterator.flatMap { case (value, mask) =>
            val fullMask = effectiveWidth.exists(allOnesAt(mask, _))
            if (!fullMask || !sameShape(value) || !sameShape(mask)) None
            else op match {
              case RtlBinaryOperator.BitwiseAnd => neutral(value, "bitwise-and-ones")
              case RtlBinaryOperator.BitwiseOr => replace(mask, "bitwise-or-ones")
              case RtlBinaryOperator.BitwiseXor => replace(RtlExpr.Unary(RtlUnaryOperator.BitwiseNot, value), "bitwise-xor-ones")
              case _ => None
            }
          }.take(1).toVector.headOption
        case RtlExpr.Binary(RtlBinaryOperator.LogicalAnd, left, right) =>
          if (literalTruth(left).contains(false) || literalTruth(right).contains(false))
            replace(RtlExpr.Literal(BigInt(0), 1), "logical-and-false")
          else if (literalTruth(left).contains(true)) replace(booleanize(right), "logical-and-true")
          else if (literalTruth(right).contains(true)) replace(booleanize(left), "logical-and-true")
          else None
        case RtlExpr.Binary(RtlBinaryOperator.LogicalOr, left, right) =>
          if (literalTruth(left).contains(true) || literalTruth(right).contains(true))
            replace(RtlExpr.Literal(BigInt(1), 1), "logical-or-true")
          else if (literalTruth(left).contains(false)) replace(booleanize(right), "logical-or-false")
          else if (literalTruth(right).contains(false)) replace(booleanize(left), "logical-or-false")
          else None
        case RtlExpr.Binary(op, left, right) if (op == RtlBinaryOperator.ShiftLeft || op == RtlBinaryOperator.ShiftRight) && isZero(right) =>
          if (sameShape(left)) replace(left, "shift-by-zero") else None
        case RtlExpr.Unary(RtlUnaryOperator.LogicalNot, value) if literalTruth(value).nonEmpty =>
          replace(RtlExpr.Literal(if (literalTruth(value).get) BigInt(0) else BigInt(1), 1), "logical-not-constant")
        case RtlExpr.Unary(RtlUnaryOperator.LogicalNot,
            RtlExpr.Unary(RtlUnaryOperator.LogicalNot, value)) if isTruthProducer(value) =>
          replace(value, "double-logical-negation")
        case RtlExpr.Unary(RtlUnaryOperator.BitwiseNot,
            RtlExpr.Unary(RtlUnaryOperator.BitwiseNot, value)) =>
          neutral(value, "double-bitwise-negation")
        case RtlExpr.Mux(condition, yes, no) =>
          literalTruth(condition).flatMap { truth =>
            val selected = if (truth) yes else no
            if (sameShape(selected)) replace(selected, "constant-mux-condition") else None
          }
        case _ => None
      }
      ordinary.orElse(widenedTruthMask(expr))
    }
  }
}
