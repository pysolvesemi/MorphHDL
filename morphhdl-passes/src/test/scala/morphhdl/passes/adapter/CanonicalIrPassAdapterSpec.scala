package morphhdl.passes.adapter

import morphhdl.ir.v1.BooleanParameterDomain
import morphhdl.ir.v1.CanonicalIrSchema
import morphhdl.ir.v1.Declaration
import morphhdl.ir.v1.DeclarationKind
import morphhdl.ir.v1.Design
import morphhdl.ir.v1.Driver
import morphhdl.ir.v1.DriverCoverage
import morphhdl.ir.v1.DriverId
import morphhdl.ir.v1.DriverKind
import morphhdl.ir.v1.IntegerParameter
import morphhdl.ir.v1.IntegerParameterDomain
import morphhdl.ir.v1.IntExpr
import morphhdl.ir.v1.IrDiagnosticCode
import morphhdl.ir.v1.Module
import morphhdl.ir.v1.ModuleId
import morphhdl.ir.v1.NameOrigin
import morphhdl.ir.v1.Observability
import morphhdl.ir.v1.PackedType
import morphhdl.ir.v1.PackedValueSemantics
import morphhdl.ir.v1.ParameterId
import morphhdl.ir.v1.PortDirection
import morphhdl.ir.v1.ReferenceId
import morphhdl.ir.v1.RtlExpr
import morphhdl.ir.v1.Scope
import morphhdl.ir.v1.ScopeId
import morphhdl.ir.v1.ScopeKind
import morphhdl.ir.v1.Signedness
import morphhdl.ir.v1.SourceLocation
import morphhdl.ir.v1.SymbolId
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class CanonicalIrPassAdapterSpec extends AnyFunSuite with Matchers {
  private val moduleId = ModuleId.unsafe("module.top")
  private val scopeId = ScopeId.unsafe("scope.top")
  private val widthParameterId = ParameterId.unsafe("parameter.width")
  private val featureParameterId = ParameterId.unsafe("parameter.feature")
  private val sourceId = SymbolId.unsafe("symbol.source")
  private val aliasId = SymbolId.unsafe("symbol.alias")
  private val sinkId = SymbolId.unsafe("symbol.sink")
  private val aliasDriverId = DriverId.unsafe("driver.alias")
  private val sinkDriverId = DriverId.unsafe("driver.sink")
  private val sourceReferenceId = ReferenceId.unsafe("reference.alias.source")
  private val aliasReferenceId = ReferenceId.unsafe("reference.sink.alias")

  private val packedType = PackedType(
    IntExpr.ParameterRef(widthParameterId),
    Signedness.Unsigned,
    PackedValueSemantics.BitVector
  )

  private def location(path: String, line: Int): SourceLocation =
    SourceLocation(path, line, 1)

  private def validDesign(
      logicalModuleName: String = "GenericBlock",
      sourcePath: String = "src/GenericBlock.scala"
  ): Design = {
    val width = IntegerParameter(
      widthParameterId,
      "WIDTH",
      default = BigInt(8),
      domain = IntegerParameterDomain(
        minimum = BigInt(1),
        maximum = BigInt(16),
        admittedValues = Vector(BigInt(16), BigInt(1), BigInt(8))
      ),
      sourceLocation = Some(location(sourcePath, 3))
    )
    val feature = morphhdl.ir.v1.BooleanParameter(
      featureParameterId,
      "FEATURE",
      default = true,
      domain = BooleanParameterDomain(Vector(true, false)),
      sourceLocation = Some(location(sourcePath, 4))
    )
    val root = Scope(
      scopeId,
      parent = None,
      kind = ScopeKind.Module,
      label = Some("top"),
      sourceLocation = Some(location(sourcePath, 7))
    )
    val source = Declaration(
      sourceId,
      scopeId,
      DeclarationKind.Port(PortDirection.Input),
      Some(packedType),
      NameOrigin.Explicit("source"),
      Some(location(sourcePath, 10)),
      Observability(complete = true, externallyVisible = true)
    )
    val alias = Declaration(
      aliasId,
      scopeId,
      DeclarationKind.InternalCombinational,
      Some(packedType),
      NameOrigin.Unnamed,
      Some(location(sourcePath, 11)),
      Observability.Unobserved
    )
    val sink = Declaration(
      sinkId,
      scopeId,
      DeclarationKind.Port(PortDirection.Output),
      Some(packedType),
      NameOrigin.Explicit("sink"),
      Some(location(sourcePath, 12)),
      Observability(complete = true, externallyVisible = true)
    )
    val aliasDriver = Driver(
      aliasDriverId,
      scopeId,
      aliasId,
      DriverKind.Continuous,
      DriverCoverage.FullObject,
      RtlExpr.Ref(
        sourceReferenceId,
        sourceId,
        scopeId,
        Some(location(sourcePath, 14))
      ),
      Some(location(sourcePath, 14))
    )
    val sinkDriver = Driver(
      sinkDriverId,
      scopeId,
      sinkId,
      DriverKind.Continuous,
      DriverCoverage.FullObject,
      RtlExpr.Ref(
        aliasReferenceId,
        aliasId,
        scopeId,
        Some(location(sourcePath, 15))
      ),
      Some(location(sourcePath, 15))
    )
    val module = Module(
      moduleId,
      logicalModuleName,
      parameters = Vector(feature, width),
      scopes = Vector(root),
      generateIndices = Vector.empty,
      declarations = Vector(sink, alias, source),
      drivers = Vector(sinkDriver, aliasDriver),
      sourceLocation = Some(location(sourcePath, 2))
    )
    Design(
      CanonicalIrSchema.schemaVersion,
      CanonicalIrSchema.stage,
      moduleId,
      Vector(module)
    )
  }

  private def bound(design: Design): CanonicalIrPassView =
    CanonicalIrPassAdapter.bind(design) match {
      case Right(value) => value
      case Left(failure) => fail(failure.diagnostics.values.mkString("\n"))
    }

  test("adapter binds only the versioned post-parameterization canonical stage") {
    val view = bound(validDesign())

    view.version shouldBe CanonicalIrSchema.schemaVersion
    view.stage shouldBe CanonicalIrSchema.stage
    view.top shouldBe moduleId
    CanonicalIrPassAdapter.supportedVersion shouldBe CanonicalIrSchema.schemaVersion
    CanonicalIrPassAdapter.supportedStage shouldBe CanonicalIrSchema.stage
  }

  test("adapter exposes exact declaration driver reference type name and observability facts") {
    val view = bound(validDesign())
    val module = view.module(moduleId).get
    val alias = module.symbol(aliasId).get

    alias.declaration shouldBe view.declaration(aliasId).get
    alias.id shouldBe aliasId
    alias.packedType shouldBe Some(packedType)
    alias.nameOrigin shouldBe NameOrigin.Unnamed
    alias.sourceLocation shouldBe Some(location("src/GenericBlock.scala", 11))
    alias.observability shouldBe Observability.Unobserved
    alias.drivers.map(_.id) shouldBe Vector(aliasDriverId)
    alias.drivers.head.directReference shouldBe Some(sourceId)
    alias.references.map(_.id) shouldBe Vector(aliasReferenceId)
    module.reference(sourceReferenceId).get.target shouldBe sourceId
    view.reference(aliasReferenceId).get.target shouldBe aliasId
  }

  test("adapter exposes complete canonical parameter domains without reconstructing them") {
    val view = bound(validDesign())
    val width = view.integerParameter(widthParameterId).get
    val feature = view.booleanParameter(featureParameterId).get

    width.domain shouldBe IntegerParameterDomain(
      minimum = BigInt(1),
      maximum = BigInt(16),
      admittedValues = Vector(BigInt(1), BigInt(8), BigInt(16))
    )
    feature.domain shouldBe BooleanParameterDomain(Vector(false, true))
  }

  test("adapter is read-only and retains the complete alias declaration and assignment") {
    val input = validDesign()
    val view = bound(input)
    val outputModule = view.design.modules.head

    outputModule.declarations.map(_.id) should contain allOf (sourceId, aliasId, sinkId)
    outputModule.drivers.map(_.id) should contain allOf (aliasDriverId, sinkDriverId)
    outputModule.drivers.find(_.id == aliasDriverId).get.target shouldBe aliasId
    outputModule.drivers.find(_.id == sinkDriverId).get.directReference shouldBe Some(aliasId)
  }

  test("adapter fails closed with canonical diagnostics when required metadata is unavailable") {
    val design = validDesign()
    val module = design.modules.head
    val declarations = module.declarations.map {
      case value if value.id == aliasId =>
        value.copy(
          nameOrigin = NameOrigin.Unknown,
          observability = Observability(complete = false)
        )
      case value => value
    }
    val malformed = design.copy(
      modules = Vector(module.copy(declarations = declarations))
    )

    val failure = CanonicalIrPassAdapter.bind(malformed) match {
      case Left(value) => value
      case Right(_) => fail("expected malformed canonical IR to be rejected")
    }
    failure.diagnostics.codes.toSet should contain allOf (
      IrDiagnosticCode.NameOriginUnknown,
      IrDiagnosticCode.ObservabilityIncomplete
    )
  }

  test("component and source names do not select a special adapter path") {
    val libraryNamed = bound(
      validDesign(
        logicalModuleName = "StreamFifo",
        sourcePath = "src/StreamFifo.scala"
      )
    )
    val unrelated = bound(
      validDesign(
        logicalModuleName = "CompletelyUnrelatedBlock",
        sourcePath = "src/CompletelyUnrelatedBlock.scala"
      )
    )

    def identityProjection(view: CanonicalIrPassView) = {
      val module = view.module(moduleId).get
      (
        module.parameters.map(_.id),
        module.scopes.map(_.id),
        module.declarations.map(_.id),
        module.drivers.map(_.id),
        module.references.map(_.id),
        module.symbol(aliasId).get.drivers.head.directReference
      )
    }

    identityProjection(libraryNamed) shouldBe identityProjection(unrelated)
  }
}
