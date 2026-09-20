package spinal.core

import org.scalatest.funsuite.AnyFunSuite

class ElaborationWidthAuthorityTests extends AnyFunSuite {
  private def parameter(name: String, default: Int, minimum: Int, maximum: Int): ElabInt =
    ElabInt.directParameter(ElaborationIntegerParameter(name, default, minimum, maximum), None)

  private def root(value: ElabInt): ElaborationIntegerParameterRoot =
    value.expression.completedParameterRoots.head

  test("independent root widths retain exact Cartesian values and their declaration identities") {
    val width = parameter("WIDTH", 4, 1, 8)
    val count = parameter("COUNT", 3, 1, 7)
    val product = ElaborationWidthAuthority.multiply(width, count)
    val sum = ElaborationWidthAuthority.add(product, width)
    val factored = ElaborationWidthAuthority.multiply(width,
      ElaborationWidthAuthority.add(count, ElabInt.literal(1)))
    assert(sum.minimum == 2)
    assert(sum.maximum == 64)
    assert(sum.bits.value == 16)
    assert(ElaborationWidthAuthority.equivalent(sum.expression, factored.expression))
    for (w <- 1 to 8; n <- 1 to 7) {
      assert(ElaborationWidthAuthority.evaluate(sum.expression,
        Vector(root(width) -> BigInt(w), root(count) -> BigInt(n))).contains(BigInt(w * (n + 1))))
    }
    assert(sum.expression.completedParameterRoots.exists(_ eq root(width)))
    assert(sum.expression.completedParameterRoots.exists(_ eq root(count)))
  }

  test("typed conditional widths correlate COUNT and preserve an independent WIDTH") {
    val width = parameter("WIDTH", 4, 1, 8)
    val count = parameter("COUNT", 3, 1, 7)
    val doubled = ElaborationWidthAuthority.add(width, width)
    val selected = ElaborationWidthAuthority.choose(count > 2, doubled, width)
    assert(selected.bits.value == 8)
    for (w <- 1 to 8; n <- 1 to 7) {
      assert(ElaborationWidthAuthority.evaluate(selected.expression,
        Vector(root(width) -> BigInt(w), root(count) -> BigInt(n)))
        .contains(BigInt(if (n > 2) 2 * w else w)))
    }
    val correlated = ElaborationWidthAuthority.subtract(selected, selected)
    assert(correlated.minimum == 0 && correlated.maximum == 0)
    intercept[ParameterizedVerilogException](correlated.bits)
  }

  test("copying or fabricating public width metadata cannot copy private authority") {
    val width = parameter("WIDTH", 4, 1, 8)
    val count = parameter("COUNT", 3, 1, 7)
    val sum = ElaborationWidthAuthority.add(width, count)
    val copied = sum.expression.copy()
    assert(!ElaborationWidthAuthority.isAuthoritative(copied))
    intercept[ParameterizedVerilogException](ElabInt.fromExpression(copied).bits)
    intercept[ParameterizedVerilogException] {
      ElaborationWidthAuthority.add(copied, width.expression)
    }
    intercept[ParameterizedVerilogException] {
      ElaborationWidthAuthority.requireAuthoritative(
        sum.expression.copy(parameters = sum.expression.parameters.map(_.copy())),
        "copied schema", "WIDTH-COPY-REJECTED")
    }
    val oneRoot = ElaborationWidthAuthority.add(width, width)
    assert(oneRoot.expression.projectionProvenance.exists(_.admitted.size == 8))
    intercept[ParameterizedVerilogException](ElabInt.fromExpression(oneRoot.expression.copy()))
  }

  test("width projections narrow both roots and reject escape to either wider domain") {
    val width = parameter("WIDTH", 4, 1, 8)
    val count = parameter("COUNT", 3, 1, 7)
    val sum = ElaborationWidthAuthority.add(width, count)
    var projected: ElabInt = null
    ElaborationDomainContext.withAdmitted(root(width), Set(BigInt(2), BigInt(3)), None) {
      ElaborationDomainContext.withAdmitted(root(count), Set(BigInt(5), BigInt(6)), None) {
        projected = ElabInt.fromExpression(sum.bits.expression.get)
        assert(projected.minimum == 7 && projected.maximum == 9)
        assert(projected.bits.value == 7)
      }
      intercept[ParameterizedVerilogException](projected.bits)
    }
    intercept[ParameterizedVerilogException](projected.minimum)
    val owned = ElaborationWidthAuthority.ownerEvaluation(projected.expression,
      "captured test owner", None) { (identity, _) =>
      if (identity eq root(width)) Set(BigInt(2), BigInt(3))
      else Set(BigInt(5), BigInt(6))
    }.get
    assert(owned.results.values.toSet == Set(BigInt(7), BigInt(8), BigInt(9)))
    val escaped = intercept[ParameterizedVerilogException] {
      ElaborationWidthAuthority.ownerEvaluation(projected.expression,
        "escaped test owner", None)((_, universe) => universe)
    }
    assert(escaped.code == "SPINAL-ELAB-DOMAIN-PROJECTION-OWNER-SCOPE-MISMATCH")
    assert(sum.bits.value == 7)
  }

  test("exact domination removes irrelevant roots only after validating identities") {
    val width = parameter("WIDTH", 4, 1, 8)
    val count = parameter("COUNT", 3, 1, 7)
    val full = ElaborationWidthAuthority.add(width, width)
    val tail = ElaborationWidthAuthority.choose(count > 2, full, width)
    val maximum = ElaborationWidthAuthority.maximum(full, tail)
    assert(maximum.expression.completedParameterRoots == Vector(root(width)))
    assert(ElaborationWidthAuthority.equivalent(maximum.expression, full.expression))
    assert(ElaborationWidthAuthority.minimumWhen(tail.expression, (count > 2).expression)
      .contains(BigInt(2)))
    assert(ElaborationWidthAuthority.minimumWhen(tail.expression, (count > 7).expression).isEmpty)
    val foreign = parameter("WIDTH", 4, 1, 8)
    intercept[ParameterizedVerilogException](ElaborationWidthAuthority.maximum(full, foreign))
  }

  test("independent same-name declarations and oversized Cartesian domains fail closed") {
    val first = parameter("WIDTH", 4, 1, 8)
    val second = parameter("WIDTH", 4, 1, 8)
    val conflict = intercept[ParameterizedVerilogException] {
      ElaborationWidthAuthority.add(first, second)
    }
    assert(conflict.code == "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED")
    val width = parameter("WIDTH", 1, 1, 257)
    val count = parameter("COUNT", 1, 1, 256)
    val tooLarge = intercept[ParameterizedVerilogException] {
      ElaborationWidthAuthority.multiply(width, count)
    }
    assert(tooLarge.code == "SPINAL-ELAB-WIDTH-DOMAIN-TOO-LARGE")
  }

  test("width-specific authority does not authorize general multi-root integer consumers") {
    val width = parameter("WIDTH", 4, 1, 8)
    val count = parameter("COUNT", 3, 1, 7)
    val sum = ElaborationWidthAuthority.add(width, count)
    val error = intercept[ParameterizedVerilogException] {
      ElabInt.requireAuthoritativeIntegerDomain(sum.expression, "general integer", "SINGLE-ROOT", false)
    }
    assert(error.code == "SINGLE-ROOT")
    val maximum = parameter("LIMIT", Int.MaxValue, Int.MaxValue, Int.MaxValue)
    val overflow = intercept[ParameterizedVerilogException] {
      ElaborationWidthAuthority.add(maximum, ElabInt.literal(1))
    }
    assert(overflow.code == "SPINAL-ELAB-WIDTH-RESULT-OUT-OF-RANGE")
  }
}
