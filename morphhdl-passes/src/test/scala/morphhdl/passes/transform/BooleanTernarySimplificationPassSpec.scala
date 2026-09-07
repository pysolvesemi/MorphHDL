package morphhdl.passes.transform

import morphhdl.ir.v1._
import morphhdl.passes.adapter.CanonicalIrPassAdapter
import morphhdl.passes.api.{PassExecutionStatus, PassId, WireAliasPassConfiguration}
import morphhdl.passes.pipeline.WireAliasPassPipeline
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

private[transform] object BooleanTernaryTestSupport {
  val moduleId = ModuleId.unsafe("module.ternary")
  val scopeId = ScopeId.unsafe("scope.ternary")
  val processId = ScopeId.unsafe("scope.ternary.process")
  val aId = SymbolId.unsafe("symbol.ternary.a")
  val bId = SymbolId.unsafe("symbol.ternary.b")
  val rawId = SymbolId.unsafe("symbol.ternary.raw")
  val sinkId = SymbolId.unsafe("symbol.ternary.sink")
  val widthId = ParameterId.unsafe("parameter.ternary.width")
  def w(n: Int): IntExpr = IntExpr.Literal(BigInt(n))
  def lit(n: Int, bits: Int = 1, signed: Boolean = false): RtlExpr = RtlExpr.Literal(BigInt(n), bits, signed)
  def ref(id: SymbolId, suffix: String, owner: ScopeId = scopeId): RtlExpr =
    RtlExpr.Ref(ReferenceId.unsafe("reference.ternary." + suffix), id, owner)
  def raw(suffix: String = "raw"): RtlExpr = ref(rawId, suffix)
  def predicate(suffix: String = "predicate"): RtlExpr =
    RtlExpr.Binary(RtlBinaryOperator.LogicalAnd,
      RtlExpr.Binary(RtlBinaryOperator.Equal, ref(aId, suffix + ".a"), lit(1, 3)),
      RtlExpr.Binary(RtlBinaryOperator.GreaterThan, ref(bId, suffix + ".b"), lit(5, 3)))
  def mux(condition: RtlExpr, inverse: Boolean = false): RtlExpr =
    RtlExpr.Mux(condition, lit(if (inverse) 0 else 1), lit(if (inverse) 1 else 0))
  def not(value: RtlExpr): RtlExpr = RtlExpr.Unary(RtlUnaryOperator.LogicalNot, value)
  def truth(value: RtlExpr): RtlExpr = not(not(value))
  def packed(width: IntExpr, signed: Boolean = false): PackedType =
    PackedType(width, if (signed) Signedness.Signed else Signedness.Unsigned,
      if (signed) PackedValueSemantics.SignedInteger else PackedValueSemantics.BitVector)
  def declaration(id: SymbolId, name: String, width: IntExpr, direction: PortDirection,
      signed: Boolean = false): Declaration =
    Declaration(id, scopeId, DeclarationKind.Port(direction), Some(packed(width, signed)),
      NameOrigin.Explicit(name), None, Observability(complete = true, externallyVisible = true))

  def fixture(expression: RtlExpr, width: IntExpr = w(1), signed: Boolean = false): Design = {
    val parameters = if (width == IntExpr.ParameterRef(widthId))
      Vector(IntegerParameter(widthId, "WIDTH", BigInt(1),
        IntegerParameterDomain(BigInt(1), BigInt(8), (1 to 8).map(BigInt(_)).toVector)))
      else Vector.empty
    Design(CanonicalIrSchema.schemaVersion, CanonicalIrSchema.stage, moduleId,
      Vector(Module(moduleId, "GenericTernaryFixture", parameters,
        Vector(Scope(scopeId, None, ScopeKind.Module)), Vector.empty,
        Vector(declaration(aId, "a", w(3), PortDirection.Input),
          declaration(bId, "b", w(3), PortDirection.Input),
          declaration(rawId, "p", w(1), PortDirection.Input).copy(
            packedType = Some(PackedType(w(1), Signedness.Unsigned, PackedValueSemantics.Boolean))),
          declaration(sinkId, "sink", width, PortDirection.Output, signed).copy(
            comments = Vector(IrComment("Retain declaration comment.")))),
        Vector(Driver(DriverId.unsafe("driver.ternary.sink"), scopeId, sinkId,
          DriverKind.Continuous, DriverCoverage.FullObject, expression,
          comments = Vector(IrComment("Retain assignment comment.")))))))
  }
  def value(design: Design): RtlExpr = design.modules.head.drivers.head.value
}

final class BooleanTernarySimplificationPassSpec extends AnyFunSuite with Matchers {
  import BooleanTernaryTestSupport.{not => logicalNot, _}

  private def checked(input: Design): BooleanTernarySimplificationResult = {
    withClue("input validation: ") { CanonicalIrPassAdapter.bindFixture(input).isRight shouldBe true }
    val result = BooleanTernarySimplificationPass.run(input)
    withClue(result.diagnostics.mkString("; ")) { result.isSuccess shouldBe true }
    result.output.modules.head.declarations shouldBe input.modules.head.declarations
    result.output.modules.head.scopes shouldBe input.modules.head.scopes
    result.output.modules.head.drivers.head.comments shouldBe input.modules.head.drivers.head.comments
    result
  }

  test("both polarities remove the user's compound comparison ternary") {
    val p = predicate()
    Vector(false, true).foreach { inverted =>
      val result = checked(fixture(mux(p, inverted)))
      result.status shouldBe PassExecutionStatus.Changed
      value(result.output) shouldBe (if (inverted) logicalNot(p) else p)
      result.rewrites.map(_.rule) shouldBe Vector(if (inverted) "boolean-ternary-inverse" else "boolean-ternary-positive")
      result.rewrites.head.expressionPath shouldBe "rhs"
      result.rewrites.head.module shouldBe moduleId
      result.rewrites.head.driver.value shouldBe "driver.ternary.sink"
    }
  }

  test("raw Bool Z and multi-bit conditions retain truth conversion") {
    Vector(raw(), ref(aId, "word"), RtlExpr.Unary(RtlUnaryOperator.BitwiseNot, raw("bitwise"))).foreach { condition =>
      value(checked(fixture(mux(condition))).output) shouldBe truth(condition)
      value(checked(fixture(mux(condition, inverse = true))).output) shouldBe logicalNot(condition)
    }
  }

  test("self-determined logical inversion stays one bit in widened signed and symbolic contexts") {
    val p = predicate()
    for (width <- Vector(w(1), w(8), w(32), IntExpr.ParameterRef(widthId)); signed <- Vector(false, true)) {
      val input = fixture(mux(p, inverse = true), width, signed)
      val result = checked(input)
      value(result.output) shouldBe logicalNot(p)
      result.output.modules.head.parameters shouldBe input.modules.head.parameters
    }
  }

  test("bottom-up traversal visits every supported expression child and nonmatching parents") {
    val cases: Vector[(RtlExpr => RtlExpr, String)] = Vector(
      ((x: RtlExpr) => RtlExpr.Unary(RtlUnaryOperator.BitwiseNot, x)) -> "rhs.value",
      ((x: RtlExpr) => RtlExpr.Unary(RtlUnaryOperator.Negate, x)) -> "rhs.value",
      ((x: RtlExpr) => RtlExpr.Binary(RtlBinaryOperator.Add, x, lit(2, 8))) -> "rhs.left",
      ((x: RtlExpr) => RtlExpr.Binary(RtlBinaryOperator.BitwiseXor, lit(2, 8), x)) -> "rhs.right",
      ((x: RtlExpr) => RtlExpr.Mux(x, lit(2, 8), lit(3, 8))) -> "rhs.condition",
      ((x: RtlExpr) => RtlExpr.Mux(raw("parent-yes"), x, lit(2, 8))) -> "rhs.yes",
      ((x: RtlExpr) => RtlExpr.Mux(raw("parent-no"), lit(2, 8), x)) -> "rhs.no",
      ((x: RtlExpr) => RtlExpr.Concat(Vector(lit(0, 7), x))) -> "rhs.item-1",
      ((x: RtlExpr) => RtlExpr.BitSelect(x, lit(0))) -> "rhs.value",
      ((x: RtlExpr) => RtlExpr.BitSelect(ref(aId, "indexed"), x)) -> "rhs.index",
      ((x: RtlExpr) => RtlExpr.PartSelect(x, w(0), w(1))) -> "rhs.value",
      ((x: RtlExpr) => RtlExpr.Resize(x, w(8), Signedness.Unsigned)) -> "rhs.value",
      ((x: RtlExpr) => RtlExpr.Cast(x, Signedness.Signed)) -> "rhs.value"
    )
    cases.foreach { case (parent, path) =>
      val p = predicate()
      val result = checked(fixture(parent(mux(p)), w(8)))
      withClue(path + ": ") {
        value(result.output) shouldBe parent(p)
        result.rewrites.map(_.expressionPath) shouldBe Vector(path)
      }
    }
  }

  test("deep nested conditions and inverse muxes reach a deterministic standalone fixed point") {
    val p = raw()
    val nested = (0 until 128).foldLeft(p)((value, _) => mux(value, inverse = true))
    val input = fixture(nested)
    val first = checked(input)
    value(first.output) shouldBe truth(p)
    first.rewrites.size shouldBe 128
    checked(input) shouldBe first
    val again = checked(first.output)
    again.status shouldBe PassExecutionStatus.Unchanged
    again.output shouldBe first.output
    again.rewrites shouldBe empty
  }

  test("opposite constants exposed by recursive branch rewrites are handled immediately") {
    val input = fixture(RtlExpr.Mux(predicate(), mux(lit(1)), mux(lit(0))))
    val result = checked(input)
    value(result.output) shouldBe predicate()
    result.rewrites.size shouldBe 3
  }

  test("identity unsigned constant fences are recognized but wider signed or parameter branches are retained") {
    val p = predicate()
    val accepted = RtlExpr.Mux(p, RtlExpr.Resize(lit(1), w(1), Signedness.Unsigned),
      RtlExpr.Cast(lit(0), Signedness.Unsigned))
    value(checked(fixture(accepted)).output) shouldBe p
    Vector(
      RtlExpr.Mux(p, lit(1, 8), lit(0, 8)),
      RtlExpr.Mux(p, lit(1, 32, signed = true), lit(0, 32, signed = true)),
      RtlExpr.Mux(p, lit(-1, 1, signed = true), lit(0, 1, signed = true)),
      RtlExpr.Mux(p, lit(1), lit(1)),
      RtlExpr.Mux(p, lit(0), lit(0)),
      RtlExpr.Mux(p, lit(0, 8), lit(255, 8))
    ).foreach { expression => checked(fixture(expression, w(32))).status shouldBe PassExecutionStatus.Unchanged }
    val symbolic = IntExpr.ParameterRef(widthId)
    val expression = RtlExpr.Mux(p, RtlExpr.Resize(lit(1), symbolic, Signedness.Unsigned), lit(0))
    checked(fixture(expression, symbolic)).status shouldBe PassExecutionStatus.Unchanged
  }

  test("all preservation metadata attributes and procedural drivers remain unchanged") {
    val original = fixture(mux(predicate()))
    val observations = Vector(
      Observability(complete = true, keep = true), Observability(complete = true, dontTouch = true),
      Observability(complete = true, probe = true), Observability(complete = true, preserve = true),
      Observability(complete = true, publicExport = true),
      Observability(complete = true, blackBoxBoundary = true),
      Observability(complete = true, hierarchyBoundary = true))
    observations.foreach { observation =>
      val input = original.copy(modules = original.modules.map(module => module.copy(
        declarations = module.declarations.map(d => if (d.id == sinkId)
          d.copy(observability = observation.copy(externallyVisible = true)) else d))))
      checked(input).status shouldBe PassExecutionStatus.Unchanged
    }
    Vector(true, false).foreach { onDriver =>
      val attributes = Vector(IrAttribute("keep", None, AttributeKind.Semantic))
      val input = original.copy(modules = original.modules.map(module =>
        if (onDriver) module.copy(drivers = module.drivers.map(_.copy(attributes = attributes)))
        else module.copy(declarations = module.declarations.map(d => if (d.id == sinkId) d.copy(attributes = attributes) else d))))
      checked(input).status shouldBe PassExecutionStatus.Unchanged
    }
    val proceduralBase = fixture(mux(ref(rawId, "procedural", processId)))
    val procedural = proceduralBase.copy(modules = proceduralBase.modules.map(module => module.copy(
      scopes = module.scopes :+ Scope(processId, Some(scopeId), ScopeKind.Process),
      declarations = module.declarations.map(d => if (d.id == sinkId)
        d.copy(kind = DeclarationKind.Register, observability = Observability.Unobserved) else d),
      drivers = module.drivers.map(_.copy(owner = processId, kind = DriverKind.Procedural)))))
    checked(procedural).status shouldBe PassExecutionStatus.Unchanged
  }

  test("continuous drivers inside nested represented scopes retain owners and scope structure") {
    val generated = ScopeId.unsafe("scope.ternary.generate")
    val block = ScopeId.unsafe("scope.ternary.generate.block")
    val condition = ref(rawId, "scoped", block)
    val original = fixture(mux(condition))
    val input = original.copy(modules = original.modules.map(module => module.copy(
      scopes = module.scopes ++ Vector(
        Scope(generated, Some(scopeId), ScopeKind.Generate, Some("generated")),
        Scope(block, Some(generated), ScopeKind.Block, Some("nested"))),
      drivers = module.drivers.map(_.copy(owner = block)))))
    val result = checked(input)
    value(result.output) shouldBe truth(condition)
    result.output.modules.head.drivers.head.owner shouldBe block
    result.output.modules.head.scopes shouldBe input.modules.head.scopes
  }

  test("renamed unrelated components have identical rewrite decisions") {
    val input = fixture(mux(predicate()))
    val renamed = input.copy(modules = input.modules.map(_.copy(logicalName = "UnrelatedComponent",
      sourceLocation = Some(SourceLocation("unrelated.scala", 17, 2)))))
    checked(input).rewrites shouldBe checked(renamed).rewrites
    value(checked(input).output) shouldBe value(checked(renamed).output)
  }

  test("invalid inputs roll back standalone and pipeline output without rewrite evidence") {
    val missing = ref(SymbolId.unsafe("symbol.missing"), "missing")
    val input = fixture(mux(missing))
    val result = BooleanTernarySimplificationPass.run(input)
    result.status shouldBe PassExecutionStatus.Failed
    result.output shouldBe input
    result.rewrites shouldBe empty
    result.diagnostics.map(_.code) shouldBe Vector("WA07B-INVALID-IR")
    val pipeline = WireAliasPassPipeline.run(input,
      WireAliasPassConfiguration.selectedForTesting(PassId.BooleanTernarySimplification))
    pipeline.status shouldBe PassExecutionStatus.Failed
    pipeline.output shouldBe input
    pipeline.simplifiedExpressions shouldBe empty
  }

  test("WA-07b exposes a WA-07a double-negation rewrite and the common flag closes both in one invocation") {
    val p = predicate()
    val input = fixture(truth(mux(p)))
    ConstantOperandSimplificationPass.run(input).status shouldBe PassExecutionStatus.Unchanged
    value(checked(input).output) shouldBe truth(p)
    val first = WireAliasPassPipeline.run(input, WireAliasPassConfiguration(enabled = true))
    withClue(first.diagnostics.mkString("; ")) { first.isSuccess shouldBe true }
    value(first.output) shouldBe p
    first.executedPasses shouldBe PassId.allWireAssignmentPasses
    first.executedPasses.last shouldBe PassId.BooleanTernarySimplification
    first.eliminationReports.map(_.simplifiedCount) shouldBe Vector(0, 0, 0, 1, 1)
    first.eliminated shouldBe empty
    first.eliminatedExpressions shouldBe empty
    first.simplifiedExpressions.map(_.rule) should contain("boolean-ternary-positive")
    val again = WireAliasPassPipeline.run(first.output, WireAliasPassConfiguration(enabled = true))
    again.status shouldBe PassExecutionStatus.Unchanged
    again.output shouldBe first.output
    WireAliasPassPipeline.run(input, WireAliasPassConfiguration(enabled = true)) shouldBe first
    val disabled = WireAliasPassPipeline.run(input, WireAliasPassConfiguration(enabled = false))
    disabled.status shouldBe PassExecutionStatus.Skipped
    disabled.output shouldBe input
    disabled.stages shouldBe empty
  }

  test("WA-07a exposes opposite branches for WA-07b without a second product invocation") {
    val p = predicate()
    val input = fixture(RtlExpr.Mux(p,
      RtlExpr.Binary(RtlBinaryOperator.BitwiseAnd, raw("mask-zero"), lit(0)),
      RtlExpr.Binary(RtlBinaryOperator.BitwiseOr, raw("mask-one"), lit(1))))
    checked(input).status shouldBe PassExecutionStatus.Unchanged
    val first = WireAliasPassPipeline.run(input, WireAliasPassConfiguration(enabled = true))
    withClue(first.diagnostics.mkString("; ")) { first.isSuccess shouldBe true }
    value(first.output) shouldBe logicalNot(p)
    first.eliminationReports.map(_.simplifiedCount) shouldBe Vector(0, 0, 0, 2, 1)
    WireAliasPassPipeline.run(first.output, WireAliasPassConfiguration(enabled = true)).status shouldBe PassExecutionStatus.Unchanged
  }

  test("historical four-stage selection excludes the new ternary rule") {
    val input = fixture(mux(predicate()))
    val result = WireAliasPassPipeline.run(input,
      WireAliasPassConfiguration.selectedForTesting(PassId.historicalConstantOperandPasses: _*))
    result.executedPasses shouldBe PassId.historicalConstantOperandPasses
    result.output shouldBe input
    result.status shouldBe PassExecutionStatus.Unchanged
  }
}
