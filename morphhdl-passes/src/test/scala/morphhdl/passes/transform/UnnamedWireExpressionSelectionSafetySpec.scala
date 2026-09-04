package morphhdl.passes.transform

import morphhdl.ir.v1.CanonicalIrSchema
import morphhdl.ir.v1.Declaration
import morphhdl.ir.v1.DeclarationKind
import morphhdl.ir.v1.Design
import morphhdl.ir.v1.Driver
import morphhdl.ir.v1.DriverCoverage
import morphhdl.ir.v1.DriverId
import morphhdl.ir.v1.DriverKind
import morphhdl.ir.v1.IntExpr
import morphhdl.ir.v1.Module
import morphhdl.ir.v1.ModuleId
import morphhdl.ir.v1.NameOrigin
import morphhdl.ir.v1.Observability
import morphhdl.ir.v1.PackedType
import morphhdl.ir.v1.PackedValueSemantics
import morphhdl.ir.v1.PortDirection
import morphhdl.ir.v1.ReferenceId
import morphhdl.ir.v1.RtlBinaryOperator
import morphhdl.ir.v1.RtlExpr
import morphhdl.ir.v1.Scope
import morphhdl.ir.v1.ScopeId
import morphhdl.ir.v1.ScopeKind
import morphhdl.ir.v1.Signedness
import morphhdl.ir.v1.SymbolId
import morphhdl.passes.api.PassExecutionStatus
import morphhdl.passes.api.WireAliasPassConfiguration
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class UnnamedWireExpressionSelectionSafetySpec
    extends AnyFunSuite
    with Matchers {
  private val moduleId = ModuleId.unsafe("module.expression-selection")
  private val scopeId = ScopeId.unsafe("scope.expression-selection")
  private val sourceA = SymbolId.unsafe("symbol.expression-selection-a")
  private val sourceB = SymbolId.unsafe("symbol.expression-selection-b")
  private val alias = SymbolId.unsafe("symbol.expression-selection-alias")
  private val sink = SymbolId.unsafe("symbol.expression-selection-sink")
  private val aliasDriver = DriverId.unsafe("driver.expression-selection-alias")
  private val sinkDriver = DriverId.unsafe("driver.expression-selection-sink")
  private val enabled = WireAliasPassConfiguration(enabled = true)

  private def bits(width: Int): PackedType =
    PackedType(
      width = IntExpr.Literal(BigInt(width)),
      signedness = Signedness.Unsigned,
      valueSemantics = PackedValueSemantics.BitVector
    )

  private def declaration(
      id: SymbolId,
      kind: DeclarationKind,
      width: Int,
      origin: NameOrigin,
      visible: Boolean = false
  ): Declaration =
    Declaration(
      id = id,
      owner = scopeId,
      kind = kind,
      packedType = Some(bits(width)),
      nameOrigin = origin,
      sourceLocation = None,
      observability = Observability(
        complete = true,
        externallyVisible = visible
      )
    )

  private def reference(name: String, target: SymbolId): RtlExpr.Ref =
    RtlExpr.Ref(
      id = ReferenceId.unsafe(s"reference.expression-selection.$name"),
      target = target,
      owner = scopeId
    )

  private def design(
      sourceWidth: Int,
      aliasWidth: Int,
      sinkWidth: Int,
      rhs: RtlExpr,
      receiver: RtlExpr
  ): Design =
    Design(
      version = CanonicalIrSchema.schemaVersion,
      stage = CanonicalIrSchema.stage,
      top = moduleId,
      modules = Vector(
        Module(
          id = moduleId,
          logicalName = "GenericSelectionSafetyFixture",
          parameters = Vector.empty,
          scopes = Vector(
            Scope(
              id = scopeId,
              parent = None,
              kind = ScopeKind.Module
            )
          ),
          generateIndices = Vector.empty,
          declarations = Vector(
            declaration(
              sourceA,
              DeclarationKind.Port(PortDirection.Input),
              sourceWidth,
              NameOrigin.Explicit("sourceA"),
              visible = true
            ),
            declaration(
              sourceB,
              DeclarationKind.Port(PortDirection.Input),
              sourceWidth,
              NameOrigin.Explicit("sourceB"),
              visible = true
            ),
            declaration(
              alias,
              DeclarationKind.InternalCombinational,
              aliasWidth,
              NameOrigin.Unnamed
            ),
            declaration(
              sink,
              DeclarationKind.Port(PortDirection.Output),
              sinkWidth,
              NameOrigin.Explicit("sink"),
              visible = true
            )
          ),
          drivers = Vector(
            Driver(
              id = aliasDriver,
              owner = scopeId,
              target = alias,
              kind = DriverKind.Continuous,
              coverage = DriverCoverage.FullObject,
              value = rhs
            ),
            Driver(
              id = sinkDriver,
              owner = scopeId,
              target = sink,
              kind = DriverKind.Continuous,
              coverage = DriverCoverage.FullObject,
              value = receiver
            )
          )
        )
      )
    )

  private def add(width: Int): RtlExpr =
    RtlExpr.Binary(
      RtlBinaryOperator.Add,
      reference(s"rhs-add-a-$width", sourceA),
      reference(s"rhs-add-b-$width", sourceB)
    )

  private def outputDriver(result: Design): Driver =
    result.modules.head.drivers.find(_.id == sinkDriver).get

  test("partial select of an arithmetic RHS retains the unnamed temporary") {
    val input = design(
      sourceWidth = 8,
      aliasWidth = 8,
      sinkWidth = 4,
      rhs = add(8),
      receiver = RtlExpr.PartSelect(
        reference("receiver-arithmetic-part", alias),
        IntExpr.Literal(BigInt(0)),
        IntExpr.Literal(BigInt(4))
      )
    )

    val result = UnnamedWireExpressionEliminationPass.run(input, enabled)

    result.status shouldBe PassExecutionStatus.Unchanged
    result.output shouldBe input.normalized
    result.output.modules.head.declarations.map(_.id) should contain(alias)
    result.eliminationReport.rejected.map(_.reasonCode) should contain(
      UnnamedWireExpressionSafetyReason.ReceiverPartialSelect
    )
  }

  test("partial select through a direct source part-select is composed without nested selects") {
    val input = design(
      sourceWidth = 16,
      aliasWidth = 8,
      sinkWidth = 4,
      rhs = RtlExpr.PartSelect(
        reference("rhs-source-part", sourceA),
        IntExpr.Literal(BigInt(2)),
        IntExpr.Literal(BigInt(8))
      ),
      receiver = RtlExpr.PartSelect(
        reference("receiver-composed-part", alias),
        IntExpr.Literal(BigInt(1)),
        IntExpr.Literal(BigInt(4))
      )
    )

    val result = UnnamedWireExpressionEliminationPass.run(input, enabled)

    result.status shouldBe PassExecutionStatus.Changed
    result.output.modules.head.declarations.map(_.id) should not contain alias
    outputDriver(result.output).value match {
      case RtlExpr.Resize(
            RtlExpr.PartSelect(
              source: RtlExpr.Ref,
              IntExpr.Literal(offset),
              IntExpr.Literal(width)
            ),
            IntExpr.Literal(fenceWidth),
            Signedness.Unsigned
          ) =>
        source.target shouldBe sourceA
        source.id.value should include("wa07-inline")
        offset shouldBe BigInt(3)
        width shouldBe BigInt(4)
        fenceWidth shouldBe BigInt(4)
      case other =>
        fail(s"expected one composed source part-select, observed $other")
    }
  }

  test("full-width receiver select is simplified before arithmetic inlining") {
    val input = design(
      sourceWidth = 4,
      aliasWidth = 4,
      sinkWidth = 4,
      rhs = add(4),
      receiver = RtlExpr.PartSelect(
        reference("receiver-full-part", alias),
        IntExpr.Literal(BigInt(0)),
        IntExpr.Literal(BigInt(4))
      )
    )

    val result = UnnamedWireExpressionEliminationPass.run(input, enabled)

    result.status shouldBe PassExecutionStatus.Changed
    outputDriver(result.output).value match {
      case RtlExpr.Resize(
            expression: RtlExpr.Binary,
            IntExpr.Literal(width),
            Signedness.Unsigned
          ) =>
        expression.operator shouldBe RtlBinaryOperator.Add
        width shouldBe BigInt(4)
      case other =>
        fail(s"expected a whole-object arithmetic replacement, observed $other")
    }
  }

  test("bit select of a multi-bit arithmetic temporary is retained") {
    val input = design(
      sourceWidth = 8,
      aliasWidth = 8,
      sinkWidth = 1,
      rhs = add(8),
      receiver = RtlExpr.BitSelect(
        reference("receiver-partial-bit", alias),
        RtlExpr.Literal(BigInt(0), 3)
      )
    )

    val result = UnnamedWireExpressionEliminationPass.run(input, enabled)

    result.status shouldBe PassExecutionStatus.Unchanged
    result.eliminationReport.rejected.map(_.reasonCode) should contain(
      UnnamedWireExpressionSafetyReason.ReceiverPartialSelect
    )
  }

  test("bit zero of a one-bit temporary is simplified as a whole-object use") {
    val input = design(
      sourceWidth = 1,
      aliasWidth = 1,
      sinkWidth = 1,
      rhs = RtlExpr.Binary(
        RtlBinaryOperator.BitwiseXor,
        reference("rhs-one-bit-a", sourceA),
        reference("rhs-one-bit-b", sourceB)
      ),
      receiver = RtlExpr.BitSelect(
        reference("receiver-full-bit", alias),
        RtlExpr.Literal(BigInt(0), 1)
      )
    )

    val result = UnnamedWireExpressionEliminationPass.run(input, enabled)

    result.status shouldBe PassExecutionStatus.Changed
    outputDriver(result.output).value match {
      case RtlExpr.Resize(
            _: RtlExpr.Binary,
            IntExpr.Literal(width),
            Signedness.Unsigned
          ) => width shouldBe BigInt(1)
      case other =>
        fail(s"expected a one-bit whole-object replacement, observed $other")
    }
  }

  test("selection through a non-direct alias expression fails closed") {
    val input = design(
      sourceWidth = 8,
      aliasWidth = 8,
      sinkWidth = 4,
      rhs = add(8),
      receiver = RtlExpr.PartSelect(
        RtlExpr.Cast(
          reference("receiver-cast-part", alias),
          Signedness.Unsigned
        ),
        IntExpr.Literal(BigInt(0)),
        IntExpr.Literal(BigInt(4))
      )
    )

    val result = UnnamedWireExpressionEliminationPass.run(input, enabled)

    result.status shouldBe PassExecutionStatus.Unchanged
    result.eliminationReport.rejected.map(_.reasonCode) should contain(
      UnnamedWireExpressionSafetyReason.ReceiverPartialSelect
    )
  }
}
