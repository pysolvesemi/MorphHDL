package morphhdl.passes.api

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class PassContractsSpec extends AnyFunSuite with Matchers {
  test("wire-alias passes are disabled by default") {
    val config = WireAliasPassConfiguration()

    config.isDisabled shouldBe true
    config.enabledPasses shouldBe Vector.empty
  }

  test("combined configuration has a fixed unnamed-then-named order") {
    val config = WireAliasPassConfiguration.selectedForTesting(
        morphhdl.passes.api.PassId.UnnamedWireAliasElimination,
        morphhdl.passes.api.PassId.NamedWireAliasElimination
      )

    config.enabledPasses shouldBe Vector(
      PassId.UnnamedWireAliasElimination,
      PassId.NamedWireAliasElimination
    )
  }

  test("pass and symbol identifiers reject ambiguous whitespace") {
    PassId.from("wire-alias-unnamed") shouldBe Right(
      PassId.UnnamedWireAliasElimination
    )
    PassId.from("bad id").isLeft shouldBe true
    IrSymbolId.from("module:signal#1").isRight shouldBe true
    IrSymbolId.from("module signal").isLeft shouldBe true
  }

  test("elimination reports normalize independently of discovery order") {
    val passId = PassId.UnnamedWireAliasElimination
    val later = EliminatedWireAlias(
      IrSymbolId.unsafe("alias-b"),
      IrSymbolId.unsafe("source-b"),
      AliasNameOrigin.Unnamed,
      Some(SourceLocation("Example.scala", 20, 3))
    )
    val earlier = EliminatedWireAlias(
      IrSymbolId.unsafe("alias-a"),
      IrSymbolId.unsafe("source-a"),
      AliasNameOrigin.Unnamed,
      Some(SourceLocation("Example.scala", 10, 7))
    )

    val normalized = EliminationReport(passId, Vector(later, earlier)).normalized

    normalized.eliminated shouldBe Vector(earlier, later)
    normalized.eliminatedCount shouldBe 2
    normalized.rejectedCount shouldBe 0
  }

  test("changed result requires reported elimination evidence") {
    val passId = PassId.UnnamedWireAliasElimination
    val eliminated = EliminatedWireAlias(
      IrSymbolId.unsafe("alias"),
      IrSymbolId.unsafe("source"),
      AliasNameOrigin.Unnamed
    )
    val report = EliminationReport(passId, eliminated = Vector(eliminated))

    val result = PassResult.changed("ir-after", report)

    result.changed shouldBe true
    result.isSuccess shouldBe true
    result.hasErrors shouldBe false
  }

  test("failed result requires an error diagnostic") {
    val passId = PassId.NamedWireAliasElimination
    val error = PassDiagnostic(
      code = "WA-UNRESOLVED-SYMBOL",
      severity = DiagnosticSeverity.Error,
      message = "exact source identity was not available",
      passId = Some(passId)
    )

    val result = PassResult.failed(
      output = "ir-before",
      report = EliminationReport(passId),
      diagnostics = Vector(error)
    )

    result.isSuccess shouldBe false
    result.hasErrors shouldBe true
    result.changed shouldBe false
  }

  test("named alias evidence retains the explicit name without renaming anything") {
    val nameOrigin = AliasNameOrigin.Explicit("payloadAlias")
    val eliminated = EliminatedWireAlias(
      IrSymbolId.unsafe("alias-symbol"),
      IrSymbolId.unsafe("source-symbol"),
      nameOrigin,
      Some(SourceLocation("NamedAlias.scala", 12, 5))
    )

    eliminated.nameOrigin.explicitName shouldBe Some("payloadAlias")
    eliminated.sourceSymbol shouldBe IrSymbolId.unsafe("source-symbol")
  }

  test("non-changing results cannot claim eliminated aliases") {
    val eliminated = EliminatedWireAlias(
      IrSymbolId.unsafe("alias"),
      IrSymbolId.unsafe("source"),
      AliasNameOrigin.Unnamed
    )

    an[IllegalArgumentException] should be thrownBy {
      PassResult.unchanged(
        output = "ir",
        report = EliminationReport(
          PassId.UnnamedWireAliasElimination,
          eliminated = Vector(eliminated)
        )
      )
    }
  }
}
