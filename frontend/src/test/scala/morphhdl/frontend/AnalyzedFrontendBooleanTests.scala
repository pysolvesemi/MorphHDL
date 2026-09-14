package morphhdl.frontend

import org.scalatest.funsuite.AnyFunSuite

import spinal.core.{ExternalAnalyzedFrontendPermitIssuer, ParameterizedVerilogException}

class AnalyzedFrontendBooleanTests extends AnyFunSuite {
  test("native ingress keeps direct parameter compound and negated predicates") {
    val enabled = HdlBool.param("ENABLE", default = false).asElabBool
    assert(enabled.toString.contains("ENABLE == 1"))
    assert(!enabled.toString.contains("?"))

    val width = HdlInt.param("WIDTH", default = 4, min = 1, max = 8)
    val compound = ((width > 2) && !width.hdlEq(5)).asElabBool
    assert(compound.isSymbolic)
    assert(compound.toString.contains("&&"))
    assert(compound.toString.contains("!"))
    assert(!compound.toString.contains("?"))
    assert(compound.parameters.map(_.name) == Vector("WIDTH"))
  }

  test("direct Boolean analysis authorizes exactly one native publication") {
    val source = HdlBool.param("ENABLE", default = false)
    val analyzed = StructuralExpressionBridge.analyzedBoolean(source, "test predicate")
    val value = ExternalAnalyzedFrontendPermitIssuer.boolean(analyzed)
    assert(value.isSymbolic)
    assert(value.parameters.map(_.name) == Vector("ENABLE"))

    val replay = intercept[FrontendException] {
      ExternalAnalyzedFrontendPermitIssuer.boolean(analyzed)
    }
    assert(replay.code == "MORPH-FRONTEND-ANALYZED-BOOLEAN-AUTHORIZATION-CONSUMED")
  }

  test("a copied predicate and valid integer analysis cannot forge Boolean authority") {
    val source = HdlBool.param("ENABLE", default = false)
    val encoded = HdlInt.select(
      source,
      HdlInt.literalAt(BigInt(1), source.origin),
      HdlInt.literalAt(BigInt(0), source.origin),
      source.origin
    )
    val integer = StructuralExpressionBridge.analyzedWidth(encoded, "test integer")
    val predicate = StructuralExpressionBridge.boolean(source, "test predicate")
    val forged = new AnalyzedFrontendBoolean(
      source,
      predicate.copy(verilog = "FOREIGN_PARAMETER"),
      encoded,
      integer,
      new Object
    )
    val error = intercept[FrontendException] {
      ExternalAnalyzedFrontendPermitIssuer.boolean(forged)
    }
    assert(error.code == "MORPH-FRONTEND-ANALYZED-BOOLEAN-AUTHORIZATION-INVALID")

    // A forged Boolean publication must not consume the independent valid
    // integer analyzer permit before its own authentication fails.
    ExternalAnalyzedFrontendPermitIssuer.singleRoot(integer)
  }

  test("direct predicates preserve portable width-helper support") {
    val depth = HdlInt.param("DEPTH", default = 4, min = 1, max = 8)
    val ceiling = (depth.ceilLog2 > 1).asElabBool
    val address = (depth.addressWidth > 1).asElabBool
    Vector(ceiling, address).foreach { predicate =>
      assert(predicate.isSymbolic)
      assert(predicate.toString.contains("clog2(DEPTH,"))
      assert(!predicate.toString.contains("?"))
      assert(predicate.parameters.map(_.name) == Vector("DEPTH"))
    }
  }

  test("native Boolean normalization retains unsupported local diagnostics") {
    val enabled = HdlBool.param("ENABLE", default = false)
    val local = ParamRtlFrontend.localParam("LOCAL_ENABLE", enabled)
    val error = intercept[FrontendException] {
      local.asElabBool
    }
    assert(error.code == "MORPH-FRONTEND-STRUCTURAL-LOCAL-PARAMETER-UNSUPPORTED")
  }

  test("native Boolean normalization retains independent-root diagnostics") {
    val enabled = HdlBool.param("ENABLE", default = false)
    val bypass = HdlBool.param("BYPASS", default = true)
    val error = intercept[ParameterizedVerilogException] {
      (enabled || bypass).asElabBool
    }
    assert(error.code == "SPINAL-ELAB-DOMAIN-EVIDENCE-MISSING")
  }
}
