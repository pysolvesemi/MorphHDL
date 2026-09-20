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
import morphhdl.ir.v1.RtlUnaryOperator
import morphhdl.ir.v1.Scope
import morphhdl.ir.v1.ScopeId
import morphhdl.ir.v1.ScopeKind
import morphhdl.ir.v1.Signedness
import morphhdl.ir.v1.SymbolId
import morphhdl.passes.api.PassExecutionStatus
import morphhdl.passes.api.WireAliasPassConfiguration
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class UnnamedWireExpressionAlgebraSpec
    extends AnyFunSuite
    with Matchers {
  private final class ExpressionCase(
      val label: String,
      val aliasType: PackedType,
      val expression: Fixture => RtlExpr,
      val expectedRoot: String,
      val expectedSources: Set[SymbolId]
  )

  private final class Fixture(
      val moduleId: ModuleId,
      val scopeId: ScopeId,
      val sourceA: SymbolId,
      val sourceB: SymbolId,
      val condition: SymbolId,
      val alias: SymbolId,
      val sink: SymbolId
  ) {
    def reference(name: String, target: SymbolId): RtlExpr.Ref =
      RtlExpr.Ref(
        id = ReferenceId.unsafe(s"reference.$label.$name"),
        target = target,
        owner = scopeId
      )

    private def label: String = moduleId.value.stripPrefix("module.expression-algebra-")
  }

  private val bits8 = PackedType(
    width = IntExpr.Literal(BigInt(8)),
    signedness = Signedness.Unsigned,
    valueSemantics = PackedValueSemantics.BitVector
  )
  private val bits4 = bits8.copy(width = IntExpr.Literal(BigInt(4)))
  private val bits1 = bits8.copy(width = IntExpr.Literal(BigInt(1)))
  private val sint8 = PackedType(
    width = IntExpr.Literal(BigInt(8)),
    signedness = Signedness.Signed,
    valueSemantics = PackedValueSemantics.SignedInteger
  )
  private val bool1 = PackedType(
    width = IntExpr.Literal(BigInt(1)),
    signedness = Signedness.Unsigned,
    valueSemantics = PackedValueSemantics.Boolean
  )

  private val cases = Vector(
    new ExpressionCase(
      "literal",
      bits8,
      _ => RtlExpr.Literal(BigInt(90), 8),
      "literal",
      Set.empty
    ),
    new ExpressionCase(
      "unary",
      bits8,
      fixture =>
        RtlExpr.Unary(
          RtlUnaryOperator.BitwiseNot,
          fixture.reference("unary-a", fixture.sourceA)
        ),
      "unary:bitwise-not",
      Set.empty
    ),
    new ExpressionCase(
      "binary",
      bits8,
      fixture =>
        RtlExpr.Binary(
          RtlBinaryOperator.Add,
          fixture.reference("binary-a", fixture.sourceA),
          fixture.reference("binary-b", fixture.sourceB)
        ),
      "binary:add",
      Set.empty
    ),
    new ExpressionCase(
      "mux",
      bits8,
      fixture =>
        RtlExpr.Mux(
          fixture.reference("mux-condition", fixture.condition),
          fixture.reference("mux-a", fixture.sourceA),
          fixture.reference("mux-b", fixture.sourceB)
        ),
      "mux",
      Set.empty
    ),
    new ExpressionCase(
      "concat",
      bits8,
      fixture =>
        RtlExpr.Concat(
          Vector(
            RtlExpr.PartSelect(
              fixture.reference("concat-a", fixture.sourceA),
              IntExpr.Literal(BigInt(0)),
              IntExpr.Literal(BigInt(4))
            ),
            RtlExpr.PartSelect(
              fixture.reference("concat-b", fixture.sourceB),
              IntExpr.Literal(BigInt(0)),
              IntExpr.Literal(BigInt(4))
            )
          )
        ),
      "concat",
      Set.empty
    ),
    new ExpressionCase(
      "bit-select",
      bits1,
      fixture =>
        RtlExpr.BitSelect(
          fixture.reference("bit-select-a", fixture.sourceA),
          RtlExpr.Literal(BigInt(2), 3)
        ),
      "bit-select",
      Set.empty
    ),
    new ExpressionCase(
      "part-select",
      bits4,
      fixture =>
        RtlExpr.PartSelect(
          fixture.reference("part-select-a", fixture.sourceA),
          IntExpr.Literal(BigInt(2)),
          IntExpr.Literal(BigInt(4))
        ),
      "part-select",
      Set.empty
    ),
    new ExpressionCase(
      "resize",
      bits4,
      fixture =>
        RtlExpr.Resize(
          fixture.reference("resize-a", fixture.sourceA),
          IntExpr.Literal(BigInt(4)),
          Signedness.Unsigned
        ),
      "resize",
      Set.empty
    ),
    new ExpressionCase(
      "cast",
      sint8,
      fixture =>
        RtlExpr.Cast(
          fixture.reference("cast-a", fixture.sourceA),
          Signedness.Signed
        ),
      "cast",
      Set.empty
    ),
    new ExpressionCase(
      "nested",
      bits8,
      fixture =>
        RtlExpr.Mux(
          fixture.reference("nested-condition", fixture.condition),
          RtlExpr.Binary(
            RtlBinaryOperator.BitwiseXor,
            fixture.reference("nested-a", fixture.sourceA),
            RtlExpr.Unary(
              RtlUnaryOperator.BitwiseNot,
              fixture.reference("nested-b", fixture.sourceB)
            )
          ),
          RtlExpr.Concat(
            Vector(
              RtlExpr.PartSelect(
                fixture.reference("nested-false-a", fixture.sourceA),
                IntExpr.Literal(BigInt(0)),
                IntExpr.Literal(BigInt(4))
              ),
              RtlExpr.PartSelect(
                fixture.reference("nested-false-b", fixture.sourceB),
                IntExpr.Literal(BigInt(4)),
                IntExpr.Literal(BigInt(4))
              )
            )
          )
        ),
      "mux",
      Set.empty
    )
  )

  private def declaration(
      id: SymbolId,
      owner: ScopeId,
      kind: DeclarationKind,
      packedType: PackedType,
      nameOrigin: NameOrigin,
      externallyVisible: Boolean = false
  ): Declaration =
    Declaration(
      id = id,
      owner = owner,
      kind = kind,
      packedType = Some(packedType),
      nameOrigin = nameOrigin,
      sourceLocation = None,
      observability = Observability(
        complete = true,
        externallyVisible = externallyVisible
      )
    )

  private def designFor(value: ExpressionCase): (Fixture, Design) = {
    val moduleId = ModuleId.unsafe(s"module.expression-algebra-${value.label}")
    val scopeId = ScopeId.unsafe(s"scope.expression-algebra-${value.label}")
    val fixture = new Fixture(
      moduleId = moduleId,
      scopeId = scopeId,
      sourceA = SymbolId.unsafe(s"symbol.expression-algebra-${value.label}-a"),
      sourceB = SymbolId.unsafe(s"symbol.expression-algebra-${value.label}-b"),
      condition = SymbolId.unsafe(s"symbol.expression-algebra-${value.label}-condition"),
      alias = SymbolId.unsafe(s"symbol.expression-algebra-${value.label}-alias"),
      sink = SymbolId.unsafe(s"symbol.expression-algebra-${value.label}-sink")
    )
    val expression = value.expression(fixture)
    val design = Design(
      version = CanonicalIrSchema.schemaVersion,
      stage = CanonicalIrSchema.stage,
      top = moduleId,
      modules = Vector(
        Module(
          id = moduleId,
          logicalName = "ExpressionAlgebraFixture",
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
              fixture.sourceA,
              scopeId,
              DeclarationKind.Port(PortDirection.Input),
              bits8,
              NameOrigin.Explicit("sourceA"),
              externallyVisible = true
            ),
            declaration(
              fixture.sourceB,
              scopeId,
              DeclarationKind.Port(PortDirection.Input),
              bits8,
              NameOrigin.Explicit("sourceB"),
              externallyVisible = true
            ),
            declaration(
              fixture.condition,
              scopeId,
              DeclarationKind.Port(PortDirection.Input),
              bool1,
              NameOrigin.Explicit("condition"),
              externallyVisible = true
            ),
            declaration(
              fixture.alias,
              scopeId,
              DeclarationKind.InternalCombinational,
              value.aliasType,
              NameOrigin.Unnamed
            ),
            declaration(
              fixture.sink,
              scopeId,
              DeclarationKind.Port(PortDirection.Output),
              value.aliasType,
              NameOrigin.Explicit("sink"),
              externallyVisible = true
            )
          ),
          drivers = Vector(
            Driver(
              id = DriverId.unsafe(s"driver.expression-algebra-${value.label}-alias"),
              owner = scopeId,
              target = fixture.alias,
              kind = DriverKind.Continuous,
              coverage = DriverCoverage.FullObject,
              value = expression
            ),
            Driver(
              id = DriverId.unsafe(s"driver.expression-algebra-${value.label}-sink"),
              owner = scopeId,
              target = fixture.sink,
              kind = DriverKind.Continuous,
              coverage = DriverCoverage.FullObject,
              value = fixture.reference("sink-alias", fixture.alias)
            )
          )
        )
      )
    )
    fixture -> design
  }

  test("every canonical pure RHS expression form is cloned into a continuous receiver") {
    cases.foreach { value =>
      val (fixture, input) = designFor(value)
      val originalExpression = input.modules.head.drivers
        .find(_.target == fixture.alias)
        .get
        .value
      val expectedSources = originalExpression.referencedSymbols.toSet

      val result = UnnamedWireExpressionEliminationPass.run(
        input,
        WireAliasPassConfiguration(enabled = true)
      )

      withClue(s"expression case ${value.label}: ") {
        result.status shouldBe PassExecutionStatus.Changed
        result.eliminationReport.eliminatedExpressions.size shouldBe 1
        result.eliminationReport.eliminatedExpressions.head.rootOperator shouldBe
          value.expectedRoot
        result.eliminationReport.eliminatedExpressions.head.receiverCount shouldBe 1

        val module = result.output.modules.head
        module.declarations.map(_.id) should not contain fixture.alias
        module.drivers.map(_.target) shouldBe Vector(fixture.sink)
        module.drivers.head.value match {
          case RtlExpr.Resize(inlined, width, signedness) =>
            width shouldBe value.aliasType.width
            signedness shouldBe value.aliasType.signedness
            inlined.referencedSymbols.toSet shouldBe expectedSources
            inlined.referenceOccurrences.foreach { reference =>
              reference.id.value should include("wa07-inline")
              reference.owner shouldBe fixture.scopeId
            }
          case other =>
            fail(s"expected assignment type fence, observed $other")
        }
      }
    }
  }
}
