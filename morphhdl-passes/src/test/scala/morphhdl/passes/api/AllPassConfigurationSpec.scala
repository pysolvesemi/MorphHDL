package morphhdl.passes.api

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

final class AllPassConfigurationSpec extends AnyFunSuite with Matchers {
  test("one public flag disables every wire-assignment pass") {
    val configuration = WireAliasPassConfiguration(enabled = false)
    configuration.enabled shouldBe false
    configuration.isDisabled shouldBe true
    configuration.enabledPasses shouldBe Vector.empty
  }

  test("one public flag enables every wire-assignment pass in the fixed order") {
    val configuration = WireAliasPassConfiguration(enabled = true)
    configuration.enabled shouldBe true
    configuration.enabledPasses shouldBe Vector(
      PassId.UnnamedWireAliasElimination,
      PassId.NamedWireAliasElimination,
      PassId.UnnamedWireExpressionElimination,
      PassId.ConstantOperandSimplification
    )
    PassId.allWireAssignmentPasses shouldBe configuration.enabledPasses
    PassId.historicalWireAssignmentPasses shouldBe configuration.enabledPasses.take(3)
  }

  test("expression elimination evidence normalizes independently of discovery order") {
    val expression = EliminatedWireExpression(
      aliasSymbol = IrSymbolId.unsafe("alias-expression"),
      nameOrigin = AliasNameOrigin.Unnamed,
      rootOperator = "binary:bitwise-xor",
      expressionNodeCount = 3,
      receiverCount = 2,
      referencedSymbols = Vector(
        IrSymbolId.unsafe("source-z"),
        IrSymbolId.unsafe("source-a"),
        IrSymbolId.unsafe("source-z")
      )
    )
    val report = EliminationReport(
      passId = PassId.UnnamedWireExpressionElimination,
      eliminatedExpressions = Vector(expression)
    ).normalized
    report.eliminatedCount shouldBe 1
    report.eliminated shouldBe empty
    report.eliminatedExpressions.head.referencedSymbols.map(_.value) shouldBe Vector("source-a", "source-z")
    PassResult.changed("ir-after", report).changed shouldBe true
  }

  test("constant-expression rewrites are not reported as removed wires") {
    val rewrite = SimplifiedExpression("module.a", "driver.a", "rhs.left", "bitwise-and-zero")
    val report = EliminationReport(PassId.ConstantOperandSimplification,
      simplifiedExpressions = Vector(rewrite))
    report.eliminatedCount shouldBe 0
    report.simplifiedCount shouldBe 1
    report.changedCount shouldBe 1
    report.isEmpty shouldBe false
    PassResult.changed("ir-after", report).changed shouldBe true
    intercept[IllegalArgumentException] { PassResult.unchanged("ir-before", report) }
    intercept[IllegalArgumentException] {
      PassResult.changed("ir-after", EliminationReport(PassId.ConstantOperandSimplification))
    }
  }

  test("rewrite reports sort deterministically without removing repeated-round evidence") {
    val later = SimplifiedExpression("module.z", "driver.a", "rhs", "rule")
    val earlier = SimplifiedExpression("module.a", "driver.a", "rhs", "rule")
    val report = EliminationReport(PassId.ConstantOperandSimplification,
      simplifiedExpressions = Vector(later, earlier, later)).normalized
    report.simplifiedExpressions shouldBe Vector(earlier, later, later)
  }
}
