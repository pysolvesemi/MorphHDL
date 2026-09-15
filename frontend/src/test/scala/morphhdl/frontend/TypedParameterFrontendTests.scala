package morphhdl.frontend

import org.scalatest.funsuite.AnyFunSuite
import spinal.core.{ElabInt, ParameterizedVerilogException}

class TypedParameterFrontendTests extends AnyFunSuite {
  test("direct typed parameter import preserves the exact schema and declaration root") {
    val native = HdlInt.param("MODE", 1, 1, 2).asElabInt
    val original = native.bits.expression.get
    val imported = HdlInt.fromElabIntParameter(native)
    val retained = imported.asElabInt.bits.expression.get

    assert(imported.expression == morphhdl.paramrtl.IntExpr.ParameterRef("MODE"))
    assert(retained.parameters.head eq original.parameters.head)
    assert(retained.parameterRoots.head eq original.parameterRoots.head)
    assert(retained.default == 1)
    assert(retained.minimum == 1)
    assert(retained.maximum == 2)
  }

  test("typed control parameter import preserves both values of its logical encoding") {
    for (default <- 1 to 2) {
      val native = HdlInt.param("MODE", default, 1, 2).asElabInt
      val imported = HdlInt.fromElabIntParameter(native) - HdlInt.literal(1)
      val analyzed = StructuralExpressionBridge.analyzedWidth(imported, "logical MODE")

      assert(analyzed.expression.default == default - 1)
      assert(analyzed.expression.minimum == 0)
      assert(analyzed.expression.maximum == 1)
      assert(analyzed.singleRootEvaluations.contains(
        Vector(BigInt(1) -> BigInt(0), BigInt(2) -> BigInt(1))))
      assert(analyzed.expression.parameterRoots.head eq native.bits.expression.get.parameterRoots.head)
    }
  }

  test("repeated imports retain one native declaration identity") {
    val native = HdlInt.param("COUNT", 3, 1, 17).asElabInt
    val first = HdlInt.fromElabIntParameter(native).asElabInt.bits.expression.get
    val second = HdlInt.fromElabIntParameter(native).asElabInt.bits.expression.get
    assert(first.parameters.head eq second.parameters.head)
    assert(first.parameterRoots.head eq second.parameterRoots.head)
  }

  test("typed parameter import rejects arithmetic even when its value is unchanged") {
    val native = HdlInt.param("MODE", 1, 1, 2).asElabInt
    val failure = intercept[FrontendException] {
      HdlInt.fromElabIntParameter(native + 0)
    }
    assert(failure.code == "MORPH-FRONTEND-TYPED-PARAMETER-NOT-DIRECT")
  }

  test("typed parameter import rejects literals and null") {
    val literal = intercept[FrontendException] {
      HdlInt.fromElabIntParameter(ElabInt.literal(1))
    }
    assert(literal.code == "MORPH-FRONTEND-TYPED-PARAMETER-NOT-DIRECT")
    val absent = intercept[FrontendException] {
      HdlInt.fromElabIntParameter(null)
    }
    assert(absent.code == "MORPH-FRONTEND-TYPED-PARAMETER-NULL")
  }

  test("typed parameter import cannot certify copied descriptive metadata") {
    val native = HdlInt.param("MODE", 1, 1, 2).asElabInt
    val source = native.bits.expression.get
    val failure = intercept[ParameterizedVerilogException] {
      HdlInt.fromElabIntParameter(ElabInt.fromExpression(source.copy()))
    }
    assert(failure.code == "SPINAL-ELAB-DOMAIN-EXACT-AUTHORITY-MISSING", failure.getMessage)
  }
}
