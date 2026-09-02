package morphhdl.frontend

import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.BoolExpr.{And, Equal, GreaterThanOrEqual, Literal, Not, Or, ParameterRef}
import morphhdl.paramrtl.IntExpr.{
  Add,
  Literal => IntLiteral,
  LocalParameterRef,
  ParameterRef => IntParameterRef,
  Select
}
import morphhdl.paramrtl.{BooleanParameter, PortDirection}
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.{ElabBool, ParameterizedVerilogException}

class HdlBoolTests extends AnyFunSuite {
  test("native typed ingress authenticates literal Boolean and integer-root predicates") {
    val enabled = HdlBool.param("ENABLE", default = true)
    val typedEnabled: ElabBool = enabled
    assert(typedEnabled.parameters.map(_.name) == Vector("ENABLE"))
    assert(typedEnabled.isSymbolic)

    val literal: ElabBool = HdlBool.literal(false)
    assert(literal.isAlwaysFalse)

    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 16)
    val typedComparison: ElabBool = width > HdlInt.literal(4)
    assert(typedComparison.parameters.map(_.name) == Vector("WIDTH"))
    assert(typedComparison.isSymbolic)

    val nullError = intercept[FrontendException] {
      val value: ElabBool = null.asInstanceOf[HdlBool]
      value
    }
    assert(nullError.code == "MORPH-FRONTEND-TYPED-BOOLEAN-NULL")
  }

  test("native typed Boolean ingress rejects independent roots") {
    val enabled = HdlBool.param("ENABLE", default = true)
    val bypass = HdlBool.param("BYPASS", default = false)
    val error = intercept[ParameterizedVerilogException] {
      val value: ElabBool = enabled && bypass
      value
    }
    assert(error.code == "SPINAL-ELAB-DOMAIN-EVIDENCE-MISSING")
  }

  test("retains literal and public-parameter witnesses and expressions") {
    val literal = HdlBool.literal(value = true)
    val enabled = HdlBool.param("ENABLED", default = false)

    assert(literal.witness)
    assert(literal.expression == Literal(true))
    assert(!enabled.witness)
    assert(enabled.expression == ParameterRef("ENABLED"))
    assert(booleanParameter(enabled).raw == BooleanParameter("ENABLED", default = false))
  }

  test("retains witnesses, expression trees and parameter identities through Boolean logic") {
    val enabled = HdlBool.param("ENABLED", default = true)
    val bypass = HdlBool.param("BYPASS", default = false)
    val expression = enabled && !bypass || HdlBool.literal(value = false)

    assert(expression.witness)
    assert(
      expression.expression == Or(
        And(ParameterRef("ENABLED"), Not(ParameterRef("BYPASS"))),
        Literal(false)
      )
    )
    assert(expression.parameters == Set(enabled.declaration.get, bypass.declaration.get))
  }

  test("preserves integer provenance through chained comparison and Boolean logic") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    val threshold = HdlInt.param("THRESHOLD", default = 5, min = 0, max = 64)
    val enabled = HdlBool.param("ENABLED", default = false)
    val expression = (width >= threshold && enabled) || !width.hdlEq(threshold)

    assert(expression.witness)
    assert(
      expression.expression == Or(
        And(
          GreaterThanOrEqual(IntParameterRef("WIDTH"), IntParameterRef("THRESHOLD")),
          ParameterRef("ENABLED")
        ),
        Not(Equal(IntParameterRef("WIDTH"), IntParameterRef("THRESHOLD")))
      )
    )
    assert(expression.parameters == Set(enabled.declaration.get))
    assert(expression.integerParameters == width.parameters ++ threshold.parameters)
    assert(expression.localParameters.isEmpty)
  }

  test("preserves local and transitive public provenance through negation and mixed logic") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    val base = HdlInt.param("BASE", default = 3, min = 0, max = 64)
    val local = ParamRtlFrontend.localParam("LOCAL_THRESHOLD", base + 2)
    val enabled = HdlBool.param("ENABLED", default = true)
    val expression = !local.hdlEq(width) && ((local < width) || enabled)

    assert(expression.witness)
    assert(expression.parameters == Set(enabled.declaration.get))
    assert(expression.integerParameters == width.parameters ++ base.parameters)
    assert(expression.localParameters == local.localParameters)
  }

  test("selects exact witnesses while retaining the condition and both integer branches") {
    val enabled = HdlBool.param("ENABLED", default = true)
    val disabled = HdlBool.param("DISABLED", default = false)
    val wide = HdlInt.param("WIDE", default = 16, min = 1, max = 64)
    val narrow = HdlInt.param("NARROW", default = 8, min = 1, max = 64)

    val selectedWide = enabled.select(wide, narrow)
    val selectedNarrow = disabled.select(wide, narrow)

    assert(selectedWide.witness == 16)
    assert(selectedNarrow.witness == 8)
    assert(
      selectedWide.expression == Select(
        ParameterRef("ENABLED"),
        IntParameterRef("WIDE"),
        IntParameterRef("NARROW")
      )
    )
    assert(
      selectedNarrow.expression == Select(
        ParameterRef("DISABLED"),
        IntParameterRef("WIDE"),
        IntParameterRef("NARROW")
      )
    )
    assert(selectedWide.parameters == wide.parameters ++ narrow.parameters)
    assert(selectedWide.booleanParameters == enabled.parameters)
    assert(selectedWide.localParameters.isEmpty)
  }

  test("accepts Int branches without leaving the symbolic selection domain") {
    val enabled = HdlBool.param("ENABLED", default = false)
    val literalSelection = enabled.select(12, 5)
    val mixedSelection = enabled.select(12, HdlInt.literal(7))

    assert(literalSelection.witness == 5)
    assert(
      literalSelection.expression == Select(
        ParameterRef("ENABLED"),
        IntLiteral(12),
        IntLiteral(5)
      )
    )
    assert(mixedSelection.witness == 7)
  }

  test("rejects null integer selection branches at the call site") {
    val enabled = HdlBool.param("ENABLED", default = true)
    val trueBranchLine = sourcecode.Line() + 1
    val trueBranch = intercept[FrontendException](enabled.select(null, 4))
    val falseBranchLine = sourcecode.Line() + 1
    val falseBranch = intercept[FrontendException](enabled.select(4, null))

    Vector(trueBranch -> trueBranchLine, falseBranch -> falseBranchLine).foreach { case (error, expectedLine) =>
      assert(error.code == "MORPH-FRONTEND-INTEGER-SELECT-BRANCH-NULL")
      assert(error.origin.file.endsWith("HdlBoolTests.scala"))
      assert(error.origin.line == expectedLine)
    }
  }

  test("unions Boolean, integer and local provenance from the condition and both branches") {
    val outer = HdlBool.param("OUTER", default = true)
    val inner = HdlBool.param("INNER", default = false)
    val wide = HdlInt.param("WIDE", default = 16, min = 1, max = 64)
    val narrow = HdlInt.param("NARROW", default = 8, min = 1, max = 64)
    val local = localParam("LOCAL_WIDTH", inner.select(wide + 1, narrow + 2))
    val selected = outer.select(local, narrow) + 3

    assert(selected.witness == 13)
    assert(selected.parameters == wide.parameters ++ narrow.parameters)
    assert(selected.booleanParameters == outer.parameters ++ inner.parameters)
    assert(selected.localParameters == local.localParameters)
    assert(
      selected.expression == Add(
        Select(ParameterRef("OUTER"), LocalParameterRef("LOCAL_WIDTH"), IntParameterRef("NARROW")),
        IntLiteral(3)
      )
    )

    val selectedLocal = localParam("SELECTED_WIDTH", selected)
    val module = moduleDef(
      name = "SelectedWidth",
      parameters = Vector(integerParameter(wide), integerParameter(narrow)),
      ports = Vector(port("data", PortDirection.Input, packedBits(selectedLocal))),
      items = captureItems {},
      localParameters = Vector(integerLocalParameter(local), integerLocalParameter(selectedLocal)),
      booleanParameters = Vector(booleanParameter(outer), booleanParameter(inner))
    )

    assert(module.localParameters.map(_.name) == Vector("LOCAL_WIDTH", "SELECTED_WIDTH"))
  }

  test("carries selected-value provenance through guarded frontend consumers") {
    val enabled = HdlBool.param("ENABLED", default = true)
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    val selected = enabled.select(width, 4)

    assert(packedBits(selected).booleanParameters == enabled.parameters)
    assert(parameterBinding("WIDTH", selected).booleanParameters == enabled.parameters)
    assert(
      indexedPartSelect("data", selected - 1, selected).booleanParameters == enabled.parameters
    )

    val items = captureItems {
      for (_ <- 0 until selected) {
        emitInstance(name = "lane_inst", moduleName = "Lane")
      }
    }
    assert(items.booleanParameters == enabled.parameters)
  }

  test("discharges selected-value Boolean provenance through generateIf") {
    val enabled = HdlBool.param("ENABLED", default = false)
    val wide = HdlInt.param("WIDE", default = 16, min = 1, max = 64)
    val selected = enabled.select(wide, 4)
    val items = captureItems {
      generateIf(selected >= 8) {
        emitInstance(name = "wide_inst", moduleName = "Wide")
      }.otherwise {
        emitInstance(name = "narrow_inst", moduleName = "Narrow")
      }
    }
    val module = moduleDef(
      name = "SelectedGenerateIf",
      parameters = Vector(integerParameter(wide)),
      ports = Vector.empty,
      items = items,
      booleanParameters = Vector(booleanParameter(enabled))
    )

    assert(items.booleanParameters == enabled.parameters)
    assert(module.booleanParameters.map(_.name) == Vector("ENABLED"))
  }

  test("checks an inactive escaped selection branch before choosing its witness") {
    val enabled = HdlBool.param("ENABLED", default = true)
    val lanes = HdlInt.param("LANES", default = 1, min = 1, max = 1)
    var escaped: HdlInt = null
    captureItems {
      for (lane <- 0 until lanes) {
        escaped = lane * HdlInt.literal(4)
      }
    }

    val error = intercept[FrontendException] {
      enabled.select(HdlInt.literal(8), escaped)
    }

    assert(error.code == "MORPH-FRONTEND-GENINDEX-ESCAPED")
    assert(error.origin == escaped.origin)
  }

  test("rejects a loop-variant selection branch even when its witness is inactive") {
    val enabled = HdlBool.param("ENABLED", default = false)
    val lanes = HdlInt.param("LANES", default = 1, min = 1, max = 1)
    var error: FrontendException = null

    captureItems {
      for (lane <- 0 until lanes) {
        val indexed = lane * HdlInt.literal(4)
        error = intercept[FrontendException] {
          enabled.select(indexed, HdlInt.literal(8))
        }
      }
    }

    assert(error.code == "MORPH-FRONTEND-GENINDEX-CONSUMER-UNSUPPORTED")
  }

  test("supports one-way Boolean conversion without changing ordinary Boolean operators") {
    final case class Config(enabled: HdlBool)

    val config = Config(enabled = true)
    val ordinary: Boolean = true && false

    assert(config.enabled.witness)
    assert(config.enabled.expression == Literal(true))
    assert(!ordinary)
  }

  test("retains caller origins on declarations and operations") {
    val declarationLine = sourcecode.Line() + 1
    val enabled = HdlBool.param("ENABLED", default = true)
    val operationLine = sourcecode.Line() + 1
    val inverted = !enabled

    assert(enabled.origin.file.endsWith("HdlBoolTests.scala"))
    assert(enabled.origin.line == declarationLine)
    assert(inverted.origin.file.endsWith("HdlBoolTests.scala"))
    assert(inverted.origin.line == operationLine)
  }

  test("fails closed for forward equality, inequality and hashing") {
    val enabled = HdlBool.param("ENABLED", default = true)
    val other = HdlBool.literal(value = true)
    val errors = Vector(
      intercept[FrontendException](enabled == other),
      intercept[FrontendException](enabled != other),
      intercept[FrontendException](enabled == true),
      intercept[FrontendException](enabled.hashCode)
    )

    errors.foreach { error =>
      assert(error.code == "MORPH-FRONTEND-SYMBOLIC-COMPARISON-UNSUPPORTED")
      assert(error.origin == enabled.origin)
    }
  }

  test("does not convert HdlBool back to Scala Boolean or accept a literal declaration") {
    assertTypeError("""
      import morphhdl.frontend._
      val enabled = HdlBool.param("ENABLED", default = true)
      val static: Boolean = enabled
    """)
    assertTypeError("""
      import morphhdl.frontend._
      val enabled = HdlBool.param("ENABLED", default = true)
      if (enabled) ()
    """)
    assertTypeError("""
      import morphhdl.frontend._
      val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
      val threshold = HdlInt.param("THRESHOLD", default = 5, min = 0, max = 64)
      if (width < threshold) ()
    """)

    val error = intercept[FrontendException] {
      booleanParameter(HdlBool.literal(value = true))
    }
    assert(error.code == "MORPH-FRONTEND-NOT-A-BOOLEAN-PARAMETER")
  }
}
