package morphhdl.frontend

import morphhdl.frontend.ParamRtlFrontend.booleanParameter
import morphhdl.paramrtl.BoolExpr.{And, Literal, Not, Or, ParameterRef}
import morphhdl.paramrtl.BooleanParameter
import org.scalatest.funsuite.AnyFunSuite

class HdlBoolTests extends AnyFunSuite {
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

    val error = intercept[FrontendException] {
      booleanParameter(HdlBool.literal(value = true))
    }
    assert(error.code == "MORPH-FRONTEND-NOT-A-BOOLEAN-PARAMETER")
  }
}
