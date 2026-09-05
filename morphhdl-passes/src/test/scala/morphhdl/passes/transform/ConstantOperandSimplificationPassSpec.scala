package morphhdl.passes.transform

import morphhdl.ir.v1._
import morphhdl.passes.adapter.CanonicalIrPassAdapter
import morphhdl.passes.api.PassExecutionStatus
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class ConstantOperandSimplificationPassSpec extends AnyFunSuite with Matchers {
  private val moduleId = ModuleId.unsafe("module.constant-operands")
  private val scopeId = ScopeId.unsafe("scope.constant-operands")
  private val processId = ScopeId.unsafe("scope.constant-operands.process")
  private val sourceId = SymbolId.unsafe("symbol.source")
  private val booleanId = SymbolId.unsafe("symbol.raw-boolean")
  private val sinkId = SymbolId.unsafe("symbol.sink")
  private val driverId = DriverId.unsafe("driver.sink")
  private val widthId = ParameterId.unsafe("parameter.width")
  private def width(n: Int): IntExpr = IntExpr.Literal(BigInt(n))
  private def literal(n: Int, bits: Int = 1, signed: Boolean = false): RtlExpr.Literal =
    RtlExpr.Literal(BigInt(n), bits, signed)
  private def reference(suffix: String = "a", boolean: Boolean = false): RtlExpr.Ref =
    RtlExpr.Ref(ReferenceId.unsafe("reference." + suffix), if (boolean) booleanId else sourceId, scopeId)
  private def predicate(suffix: String = "p"): RtlExpr =
    RtlExpr.Binary(RtlBinaryOperator.GreaterThan, reference(suffix), literal(5, 8))
  private def bits(w: IntExpr, signed: Boolean = false): PackedType =
    PackedType(w, if (signed) Signedness.Signed else Signedness.Unsigned,
      if (signed) PackedValueSemantics.SignedInteger else PackedValueSemantics.BitVector)

  private def fixture(
      expression: RtlExpr,
      outputWidth: IntExpr = IntExpr.Literal(BigInt(1)),
      sourceWidth: IntExpr = IntExpr.Literal(BigInt(8)),
      signed: Boolean = false,
      procedural: Boolean = false,
      observability: Observability = Observability(complete = true, externallyVisible = true),
      attributes: Vector[IrAttribute] = Vector.empty
  ): Design = {
    val parameters: Vector[Parameter] =
      if (sourceWidth == IntExpr.ParameterRef(widthId) || outputWidth == IntExpr.ParameterRef(widthId))
        Vector(IntegerParameter(widthId, "WIDTH", BigInt(4),
          IntegerParameterDomain(BigInt(1), BigInt(8), (1 to 8).map(BigInt(_)).toVector)))
      else Vector.empty
    val driverOwner = if (procedural) processId else scopeId
    val rhs = if (procedural) expression match {
      case RtlExpr.Binary(op, left, right) =>
        def reowner(value: RtlExpr): RtlExpr = value match {
          case ref: RtlExpr.Ref => ref.copy(owner = processId)
          case other => other
        }
        RtlExpr.Binary(op, reowner(left), reowner(right))
      case other => other
    } else expression
    Design(CanonicalIrSchema.schemaVersion, CanonicalIrSchema.stage, moduleId,
      Vector(Module(moduleId, "ConstantOperandFixture", parameters,
        Vector(Scope(scopeId, None, ScopeKind.Module)) ++
          (if (procedural) Vector(Scope(processId, Some(scopeId), ScopeKind.Process)) else Vector.empty),
        Vector.empty,
        Vector(
          Declaration(sourceId, scopeId, DeclarationKind.Port(PortDirection.Input), Some(bits(sourceWidth, signed)),
            NameOrigin.Explicit("source"), None, Observability(complete = true, externallyVisible = true)),
          Declaration(booleanId, scopeId, DeclarationKind.Port(PortDirection.Input),
            Some(PackedType(width(1), Signedness.Unsigned, PackedValueSemantics.Boolean)),
            NameOrigin.Explicit("rawBoolean"), None, Observability(complete = true, externallyVisible = true)),
          Declaration(sinkId, scopeId,
            if (procedural) DeclarationKind.Register else DeclarationKind.Port(PortDirection.Output),
            Some(bits(outputWidth, signed)), NameOrigin.Explicit("sink"),
            Some(SourceLocation("fixture.scala", 12, 3)), observability, attributes,
            Vector(IrComment("Retain this declaration comment.")))
        ),
        Vector(Driver(driverId, driverOwner, sinkId,
          if (procedural) DriverKind.Procedural else DriverKind.Continuous,
          DriverCoverage.FullObject, rhs,
          comments = Vector(IrComment("Retain this assignment comment.")))))))
  }

  private def value(design: Design): RtlExpr = design.modules.head.drivers.head.value
  private def checked(input: Design): ConstantOperandSimplificationResult = {
    withClue("fixture must be valid before the pass: ") {
      CanonicalIrPassAdapter.bindFixture(input).isRight shouldBe true
    }
    val result = ConstantOperandSimplificationPass.run(input)
    withClue(result.diagnostics.mkString("; ")) { result.isSuccess shouldBe true }
    result
  }

  test("comparison AND OR and XOR constant operands simplify in both orders") {
    val p = predicate()
    val rules = Vector(
      (RtlBinaryOperator.BitwiseAnd, literal(1), p),
      (RtlBinaryOperator.BitwiseAnd, literal(0), literal(0)),
      (RtlBinaryOperator.BitwiseOr, literal(0), p),
      (RtlBinaryOperator.BitwiseOr, literal(1), literal(1)),
      (RtlBinaryOperator.BitwiseXor, literal(0), p),
      (RtlBinaryOperator.BitwiseXor, literal(1), RtlExpr.Unary(RtlUnaryOperator.BitwiseNot, p))
    )
    rules.foreach { case (operator, constant, expected) =>
      Vector(false, true).foreach { swap =>
        val expr = if (swap) RtlExpr.Binary(operator, constant, p) else RtlExpr.Binary(operator, p, constant)
        val input = fixture(expr)
        val result = checked(input)
        withClue(s"${operator.label}, constant=$constant, swap=$swap: ") {
          result.status shouldBe PassExecutionStatus.Changed
          value(result.output) shouldBe expected
          result.rewrites.size shouldBe 1
          result.rewrites.head.module shouldBe moduleId
          result.rewrites.head.driver shouldBe driverId
          result.rewrites.head.expressionPath shouldBe "rhs"
          result.output.modules.head.declarations shouldBe input.modules.head.declarations
          result.output.modules.head.drivers.head.comments shouldBe input.modules.head.drivers.head.comments
        }
      }
    }
  }

  test("Boolean metadata does not authorize Z-changing raw-reference identities") {
    val raw = reference("raw", boolean = true)
    Vector(
      RtlExpr.Binary(RtlBinaryOperator.BitwiseAnd, raw, literal(1)),
      RtlExpr.Binary(RtlBinaryOperator.BitwiseOr, raw, literal(0)),
      RtlExpr.Binary(RtlBinaryOperator.BitwiseXor, raw, literal(0)),
      RtlExpr.Unary(RtlUnaryOperator.BitwiseNot, RtlExpr.Unary(RtlUnaryOperator.BitwiseNot, raw)),
      RtlExpr.Unary(RtlUnaryOperator.LogicalNot, RtlExpr.Unary(RtlUnaryOperator.LogicalNot, raw))
    ).foreach { expression =>
      val result = checked(fixture(expression))
      result.status shouldBe PassExecutionStatus.Unchanged
      value(result.output) shouldBe expression
      result.rewrites shouldBe empty
    }
  }

  test("raw Z remains safe for annihilating masks and XOR with all ones") {
    val raw = reference("raw", boolean = true)
    Vector(
      RtlExpr.Binary(RtlBinaryOperator.BitwiseAnd, raw, literal(0)) -> literal(0),
      RtlExpr.Binary(RtlBinaryOperator.BitwiseOr, raw, literal(1)) -> literal(1),
      RtlExpr.Binary(RtlBinaryOperator.BitwiseXor, raw, literal(1)) ->
        RtlExpr.Unary(RtlUnaryOperator.BitwiseNot, raw)
    ).foreach { case (expression, expected) =>
      value(checked(fixture(expression)).output) shouldBe expected
    }
  }

  test("numeric one is not an all-ones mask for a multi-bit value") {
    val word = RtlExpr.Unary(RtlUnaryOperator.BitwiseNot, reference())
    Vector(RtlBinaryOperator.BitwiseAnd, RtlBinaryOperator.BitwiseOr, RtlBinaryOperator.BitwiseXor).foreach { op =>
      val input = fixture(RtlExpr.Binary(op, word, literal(1, 8)), width(8))
      checked(input).status shouldBe PassExecutionStatus.Unchanged
    }
    val fullMask = fixture(RtlExpr.Binary(RtlBinaryOperator.BitwiseAnd, word, literal(255, 8)), width(8))
    value(checked(fullMask).output) shouldBe word
  }

  test("all-ones masks are checked at the effective enclosing evaluation width") {
    val word = RtlExpr.Unary(RtlUnaryOperator.BitwiseNot, reference())
    Vector(RtlBinaryOperator.BitwiseAnd, RtlBinaryOperator.BitwiseOr, RtlBinaryOperator.BitwiseXor).foreach { op =>
      val input = fixture(RtlExpr.Binary(op, word, literal(255, 8)), width(16))
      checked(input).status shouldBe PassExecutionStatus.Unchanged
    }
    val nested = RtlExpr.Binary(RtlBinaryOperator.Add,
      RtlExpr.Binary(RtlBinaryOperator.BitwiseAnd, word, literal(255, 8)), literal(256, 16))
    checked(fixture(nested, width(16))).status shouldBe PassExecutionStatus.Unchanged
  }

  test("signedness changes and differently sized branches are not erased") {
    val word = RtlExpr.Unary(RtlUnaryOperator.BitwiseNot, reference())
    val mixed = fixture(RtlExpr.Binary(RtlBinaryOperator.BitwiseAnd, word, literal(255, 8)),
      outputWidth = width(8), signed = true)
    checked(mixed).status shouldBe PassExecutionStatus.Unchanged
    val mux = fixture(RtlExpr.Mux(literal(1), reference(), literal(0, 16)), width(16))
    checked(mux).status shouldBe PassExecutionStatus.Unchanged
  }

  test("logical identities Booleanize vectors and normalize raw Z instead of returning raw bits") {
    val raw = reference()
    val truth = RtlExpr.Unary(RtlUnaryOperator.LogicalNot, RtlExpr.Unary(RtlUnaryOperator.LogicalNot, raw))
    Vector(
      RtlExpr.Binary(RtlBinaryOperator.LogicalAnd, raw, literal(1)) -> truth,
      RtlExpr.Binary(RtlBinaryOperator.LogicalOr, raw, literal(0)) -> truth,
      RtlExpr.Binary(RtlBinaryOperator.LogicalAnd, literal(0), raw) -> literal(0),
      RtlExpr.Binary(RtlBinaryOperator.LogicalOr, literal(1), raw) -> literal(1)
    ).foreach { case (expression, expected) =>
      value(checked(fixture(expression)).output) shouldBe expected
    }
    val p = predicate()
    value(checked(fixture(RtlExpr.Binary(RtlBinaryOperator.LogicalAnd, p, literal(1)))).output) shouldBe p
  }

  test("zero shifts and equal-type constant mux branches preserve selected values") {
    Vector(RtlBinaryOperator.ShiftLeft, RtlBinaryOperator.ShiftRight).foreach { op =>
      val raw = reference()
      value(checked(fixture(RtlExpr.Binary(op, raw, literal(0, 8)), width(8))).output) shouldBe raw
    }
    val raw = reference()
    value(checked(fixture(RtlExpr.Mux(literal(1), raw, literal(0, 8)), width(8))).output) shouldBe raw
    value(checked(fixture(RtlExpr.Mux(literal(0), literal(0, 8), raw), width(8))).output) shouldBe raw
  }

  test("arithmetic and self-cancellation identities remain outside the bounded contract") {
    Vector(RtlBinaryOperator.Add, RtlBinaryOperator.Subtract, RtlBinaryOperator.Multiply,
      RtlBinaryOperator.Divide, RtlBinaryOperator.Modulo).foreach { op =>
      val input = fixture(RtlExpr.Binary(op, reference(), literal(0, 8)), width(8))
      checked(input).status shouldBe PassExecutionStatus.Unchanged
    }
    val selfXor = fixture(RtlExpr.Binary(RtlBinaryOperator.BitwiseXor, reference("left"), reference("right")), width(8))
    checked(selfXor).status shouldBe PassExecutionStatus.Unchanged
  }

  test("nested rewrites are deterministic and reach an idempotent fixed point") {
    val p = predicate()
    val input = fixture(RtlExpr.Binary(RtlBinaryOperator.BitwiseAnd,
      RtlExpr.Binary(RtlBinaryOperator.BitwiseOr,
        RtlExpr.Binary(RtlBinaryOperator.BitwiseXor, p, literal(0)), literal(0)), literal(1)))
    val first = checked(input)
    value(first.output) shouldBe p
    first.rewrites.size shouldBe 3
    checked(input) shouldBe first
    val second = checked(first.output)
    second.output shouldBe first.output
    second.status shouldBe PassExecutionStatus.Unchanged
    second.rewrites shouldBe empty
  }

  test("parameterized zero masks preserve WIDTH rather than a default binding") {
    val w = IntExpr.ParameterRef(widthId)
    val zero = RtlExpr.Resize(literal(0), w, Signedness.Unsigned)
    val input = fixture(RtlExpr.Binary(RtlBinaryOperator.BitwiseAnd, reference(), zero), w, w)
    val result = checked(input)
    result.status shouldBe PassExecutionStatus.Changed
    value(result.output) shouldBe zero
    result.output.modules.head.parameters shouldBe input.modules.head.parameters
    result.output.modules.head.declarations shouldBe input.modules.head.declarations
    checked(result.output).status shouldBe PassExecutionStatus.Unchanged
  }

  test("preservation attributes and procedural drivers are unchanged") {
    val expression = RtlExpr.Binary(RtlBinaryOperator.BitwiseAnd, reference("raw", boolean = true), literal(0))
    Vector(
      Observability(complete = true, externallyVisible = true, keep = true),
      Observability(complete = true, externallyVisible = true, dontTouch = true),
      Observability(complete = true, externallyVisible = true, probe = true),
      Observability(complete = true, externallyVisible = true, preserve = true)
    ).foreach { observation =>
      checked(fixture(expression, observability = observation)).status shouldBe PassExecutionStatus.Unchanged
    }
    checked(fixture(expression, attributes = Vector(IrAttribute("keep", None, AttributeKind.Semantic)))).status shouldBe
      PassExecutionStatus.Unchanged
    checked(fixture(expression, procedural = true,
      observability = Observability.Unobserved)).status shouldBe PassExecutionStatus.Unchanged
  }

  test("module names and source paths do not select optimization behavior") {
    val input = fixture(RtlExpr.Binary(RtlBinaryOperator.BitwiseAnd, predicate(), literal(1)))
    val renamed = input.copy(modules = input.modules.map(_.copy(
      logicalName = "UnrelatedFixture", sourceLocation = Some(SourceLocation("different.scala", 99, 7)))))
    checked(input).rewrites shouldBe checked(renamed).rewrites
    value(checked(input).output) shouldBe value(checked(renamed).output)
  }

  test("invalid canonical input fails closed and returns the original design") {
    val missing = RtlExpr.Ref(ReferenceId.unsafe("reference.missing"), SymbolId.unsafe("symbol.missing"), scopeId)
    val input = fixture(RtlExpr.Binary(RtlBinaryOperator.BitwiseAnd, missing, literal(0)))
    val result = ConstantOperandSimplificationPass.run(input)
    result.status shouldBe PassExecutionStatus.Failed
    result.output shouldBe input
    result.rewrites shouldBe empty
    result.diagnostics should not be empty
  }
}
