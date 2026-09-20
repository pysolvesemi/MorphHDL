package morphhdl.frontend

import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.BoolExpr.{LocalParameterRef => BoolLocalParameterRef}
import morphhdl.paramrtl.IntExpr.{CeilLog2, Literal, LocalParameterRef, ParameterRef, Select}
import morphhdl.paramrtl.ModuleItem.ModuleInstance
import morphhdl.paramrtl._
import org.scalatest.funsuite.AnyFunSuite

class CeilLog2FrontendTests extends AnyFunSuite {
  test("computes exact witnesses at power-of-two boundaries and retains the unary expression") {
    Vector(
      BigInt(1) -> BigInt(0),
      BigInt(2) -> BigInt(1),
      BigInt(3) -> BigInt(2),
      BigInt(4) -> BigInt(2),
      BigInt(5) -> BigInt(3),
      BigInt(8) -> BigInt(3),
      ((BigInt(1) << 4096) + 1) -> BigInt(4097)
    ).foreach { case (value, expected) =>
      val result = HdlInt.literal(value).ceilLog2

      assert(result.witness == expected)
      assert(result.expression == CeilLog2(Literal(value)))
      assert(result.parameters.isEmpty)
      assert(result.booleanParameters.isEmpty)
      assert(result.localParameters.isEmpty)
      assert(result.booleanLocalParameters.isEmpty)
      assert(result.scope.isEmpty)
    }
  }

  test("rejects nonpositive witnesses at the operation source with an exact suggestion") {
    val expectedSuggestion =
      "Choose a positive concrete witness and declare the full symbolic input domain as strictly positive before using ceilLog2."
    val zeroLine = sourcecode.Line() + 1
    val zero = intercept[FrontendException](HdlInt.literal(0).ceilLog2)
    val negativeLine = sourcecode.Line() + 1
    val negative = intercept[FrontendException](HdlInt.literal(-7).ceilLog2)

    Vector(zero -> zeroLine, negative -> negativeLine).foreach { case (error, expectedLine) =>
      assert(error.code == "MORPH-FRONTEND-CEIL-LOG2-WITNESS-NONPOSITIVE")
      assert(error.origin.file.endsWith("CeilLog2FrontendTests.scala"))
      assert(error.origin.line == expectedLine)
      assert(error.detail.contains("positive concrete witness"))
      assert(error.suggestion == expectedSuggestion)
      assert(error.suggestedReplacement == expectedSuggestion)
    }
  }

  test("preserves integer Boolean and local provenance through ceilLog2") {
    val primary = HdlInt.param("PRIMARY", default = 5, min = 1, max = 32)
    val fallback = HdlInt.param("FALLBACK", default = 2, min = 1, max = 8)
    val enabled = HdlBool.param("ENABLED", default = true)
    val localEnabled = localParam("LOCAL_ENABLED", enabled)
    val selected = localEnabled.select(primary, fallback)
    val localSelected = localParam("LOCAL_SELECTED", selected)
    val result = localSelected.ceilLog2

    assert(result.witness == 3)
    assert(result.expression == CeilLog2(LocalParameterRef("LOCAL_SELECTED")))
    assert(result.parameters == primary.parameters ++ fallback.parameters)
    assert(result.booleanParameters == enabled.parameters)
    assert(result.localParameters == localSelected.localParameters)
    assert(result.booleanLocalParameters == localEnabled.booleanLocalParameters)
    assert(
      localSelected.localDeclaration.get.declaration.value == Select(
        BoolLocalParameterRef("LOCAL_ENABLED"),
        ParameterRef("PRIMARY"),
        ParameterRef("FALLBACK")
      )
    )
  }

  test("propagates a legal zero result through local parameters and child bindings") {
    val lanes = HdlInt.param("LANES", default = 1, min = 1, max = 32)
    val selectWidth = localParam("SELECT_WIDTH", lanes.ceilLog2)
    val items = captureItems {
      emitInstance(
        name = "child_inst",
        moduleName = "Child",
        parameterBindings = Vector(parameterBinding("SELECT_WIDTH", selectWidth))
      )
    }
    val module = moduleDef(
      "CeilLog2Consumers",
      Vector(integerParameter(lanes)),
      Vector.empty,
      items,
      localParameters = Vector(integerLocalParameter(selectWidth))
    )

    assert(selectWidth.witness == 0)
    assert(
      module.localParameters ==
        Vector(IntegerLocalParameter("SELECT_WIDTH", CeilLog2(ParameterRef("LANES"))))
    )
    assert(
      module.items == Vector(
        ModuleInstance(
          "child_inst",
          "Child",
          Vector(ParameterBinding("SELECT_WIDTH", LocalParameterRef("SELECT_WIDTH")))
        )
      )
    )
  }

  test("preflights generate scope before witness positivity and rejects escaped indices") {
    val count = HdlInt.param("COUNT", default = 1, min = 1, max = 1)
    var loopError: FrontendException = null
    var escaped: HdlInt = null

    captureItems {
      for (index <- 0 until count) {
        val indexed = index * HdlInt.literal(1)
        loopError = intercept[FrontendException](indexed.ceilLog2)
        escaped = indexed
      }
    }

    assert(loopError.code == "MORPH-FRONTEND-GENINDEX-CONSUMER-UNSUPPORTED")
    assert(loopError.code != "MORPH-FRONTEND-CEIL-LOG2-WITNESS-NONPOSITIVE")
    assert(intercept[FrontendException](escaped.ceilLog2).code == "MORPH-FRONTEND-GENINDEX-ESCAPED")
  }
}
