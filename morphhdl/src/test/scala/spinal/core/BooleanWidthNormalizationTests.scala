package spinal.core

import morphhdl.frontend.{HdlBool, HdlInt}
import org.scalatest.funsuite.AnyFunSuite

/** Exercise the existing native typed child boundary, whose API is package scoped. */
object BooleanWidthNormalizationChildFixture {
  final class Child(width: ElabInt) extends Component {
    setDefinitionName("Wa11BooleanWidthChild")
    val dataIn = in Bits(width bits)
    val dataOut = out Bits(width bits)
    dataOut := dataIn
  }

  final class Top(width: ElabInt) extends Component {
    setDefinitionName("Wa11BooleanWidthChildTop")
    val dataIn = in Bits(width bits)
    val dataOut = out Bits(width bits)
    val child = ElabFormalComponent
      .parameter(width, "CHILD_WIDTH", BigInt(1), BigInt(4))(formal => new Child(formal))
      .setName("child")
    child.dataIn := dataIn
    dataOut := child.dataOut
  }
}

/** WA-11: simplification must retain typed integer semantics and exact authority. */
class BooleanWidthNormalizationTests extends AnyFunSuite {
  private def values(value: ElabInt): Vector[(BigInt, BigInt)] =
    value.expression.exactDomain.getOrElse(fail("missing exact integer evidence")).evaluations

  private def root(value: ElabInt): ElaborationIntegerParameterRoot =
    value.expression.exactDomain.getOrElse(fail("missing exact integer evidence")).root

  private def parameter(name: String, minimum: Int, maximum: Int, default: Int): ElabInt =
    HdlInt.param(name, BigInt(default), BigInt(minimum), BigInt(maximum)).asElabInt

  test("Boolean roots normalize without specializing either default or losing provenance") {
    Vector(false, true).foreach { default =>
      val predicate = HdlBool.param("PPC4", default = default).asElabBool
      val encoded = predicate.toElabInt
      val width = encoded * 3 + 1
      val predicateDomain = predicate.expression.exactDomain.get
      val integerDomain = encoded.expression.exactDomain.get

      assert(encoded.expression.verilog == "PPC4", encoded.expression.verilog)
      assert(encoded.expression.hasExactAuthority)
      assert(integerDomain.root eq predicateDomain.root)
      assert(integerDomain.parameter eq predicateDomain.parameter)
      assert(encoded.parameters == predicate.parameters)
      assert(encoded.sourceLocation == predicate.sourceLocation)
      assert(encoded.sourceLocation.nonEmpty)
      assert(encoded.expression.parameterRoots.head eq predicate.expression.parameterRoots.head)
      assert(integerDomain.hasCompleteCoverage)
      assert(values(encoded) == Vector(BigInt(0) -> BigInt(0), BigInt(1) -> BigInt(1)))
      assert(values(width) == Vector(BigInt(0) -> BigInt(1), BigInt(1) -> BigInt(4)))
      assert(width.expression.default == (if (default) 4 else 1))
      assert(width.minimum == 1 && width.maximum == 4)
    }
  }

  test("repeated Boolean integer round trips do not accumulate conversions") {
    val direct = HdlBool.param("PPC4", default = false).asElabBool.toElabInt
    var result = direct
    (1 to 12).foreach { _ =>
      result = result.elabEq(1).toElabInt
      assert(result.expression.verilog == direct.expression.verilog)
      assert(root(result) eq root(direct))
      assert(values(result) == values(direct))
      assert(result.sourceLocation == direct.sourceLocation)
      assert(result.expression.hasExactAuthority)
    }
    var inverted = direct
    (1 to 8).foreach { iteration =>
      inverted = inverted.elabEq(0).toElabInt
      assert(values(inverted).map(_._2) == (if (iteration % 2 == 0) Vector(BigInt(0), BigInt(1)) else Vector(BigInt(1), BigInt(0))))
      assert(root(inverted) eq root(direct))
      assert(inverted.expression.verilog.count(_ == '?') <= 1, inverted.expression.verilog)
    }
  }

  test("compound predicates and negation retain one integer encoding through round trips") {
    val mode = parameter("MODE", 0, 3, 0)
    val compound = (mode >= 1) && !(mode >= 3)
    val cases = Vector(
      compound -> Vector(0, 1, 1, 0),
      !compound -> Vector(1, 0, 0, 1)
    )
    cases.foreach { case (predicate, expected) =>
      val first = predicate.toElabInt
      var encoded = first
      (1 to 8).foreach { _ => encoded = encoded.elabEq(1).toElabInt }
      assert(encoded.expression.verilog.count(_ == '?') <= 1, encoded.expression.verilog)
      assert(encoded.expression.verilog == first.expression.verilog)
      assert(root(encoded) eq root(mode))
      assert(values(encoded) == expected.zipWithIndex.map { case (value, index) =>
        BigInt(index) -> BigInt(value)
      })
      assert(values(encoded * 3 + 1).map(_._2) == expected.map(value => BigInt(value * 3 + 1)))
    }
  }

  test("integer domains containing non Boolean values cannot be returned from equality encoding") {
    Vector(-1, 0, 1, 2).foreach { default =>
      val integer = parameter("INTEGER_VALUE", -1, 2, default)
      val encoded = integer.elabEq(1).toElabInt
      assert(encoded.expression.verilog != "INTEGER_VALUE", encoded.expression.verilog)
      assert(encoded.expression.verilog.contains("=="), encoded.expression.verilog)
      assert(encoded.expression.verilog.count(_ == '?') <= 1, encoded.expression.verilog)
      assert(root(encoded) eq root(integer))
      assert(encoded.parameters == integer.parameters)
      assert(values(encoded) == (-1 to 2).map(value =>
        BigInt(value) -> (if (value == 1) BigInt(1) else BigInt(0))
      ).toVector)
      assert(encoded.minimum == 0 && encoded.maximum == 1)
      val unsupportedWidthRoot = intercept[ParameterizedVerilogException] {
        ParameterizedWidth.validatedWidthExpression((encoded * 3 + 1).bits)
      }
      assert(unsupportedWidthRoot.code == "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-DOMAIN-INVALID")
    }
  }

  test("integer arithmetic uses signed 32 bit values at both boundaries") {
    val encoded = HdlBool.param("PPC4", default = false).asElabBool.toElabInt
    assert(values(encoded * Int.MinValue).map(_._2) == Vector(BigInt(0), BigInt(Int.MinValue)))
    assert(values(encoded * Int.MaxValue).map(_._2) == Vector(BigInt(0), BigInt(Int.MaxValue)))
    assert(values((encoded * Int.MinValue < 0).toElabInt).map(_._2) == Vector(BigInt(0), BigInt(1)))
    assert(values((encoded * Int.MaxValue > 0).toElabInt).map(_._2) == Vector(BigInt(0), BigInt(1)))

    val nearMinimum = parameter("MINIMUM", Int.MinValue, Int.MinValue + 1, Int.MinValue)
    assert(values((nearMinimum < 0).toElabInt).map(_._2) == Vector(BigInt(1), BigInt(1)))
    val nearMaximum = parameter("MAXIMUM", Int.MaxValue - 1, Int.MaxValue, Int.MaxValue)
    assert(values((nearMaximum > 0).toElabInt).map(_._2) == Vector(BigInt(1), BigInt(1)))

    val highError = intercept[ParameterizedVerilogException] { encoded * Int.MaxValue + 1 }
    assert(highError.code == "SPINAL-ELAB-DOMAIN-EVIDENCE-RESULT-OUT-OF-RANGE")
    val lowError = intercept[ParameterizedVerilogException] { encoded * Int.MinValue - 1 }
    assert(lowError.code == "SPINAL-ELAB-DOMAIN-EVIDENCE-RESULT-OUT-OF-RANGE")
  }

  test("predicate encodings stay integers when addition would overflow one bit") {
    val mode = parameter("MODE", 0, 3, 0)
    val encoded = (mode >= 2).toElabInt
    val doubled = encoded + encoded
    val predicate = doubled > encoded
    assert(values(doubled).map(_._2) == Vector(0, 0, 2, 2).map(BigInt(_)))
    assert(values(predicate.toElabInt).map(_._2) == Vector(0, 0, 1, 1).map(BigInt(_)))
  }

  test("normalization retains branch projection restrictions and global legal domains") {
    val integer = parameter("INTEGER_VALUE", 0, 2, 0)
    var projected: ElabInt = null
    ElaborationDomainContext.withAdmitted(root(integer), Set(BigInt(0), BigInt(1)), None) {
      projected = integer.elabEq(1).toElabInt.elabEq(1).toElabInt
      assert(projected.minimum == 0 && projected.maximum == 1)
      assert(root(projected) eq root(integer))
      assert(projected.parameters.head.maximum == 2)
      assert(projected.expression.projectionProvenance.nonEmpty)
    }
    val error = intercept[ParameterizedVerilogException] { projected.minimum }
    assert(error.code == "SPINAL-ELAB-DOMAIN-PROJECTION-SCOPE-EXPANSION")
    val complete = integer.elabEq(1).toElabInt
    assert(values(complete).last == (BigInt(2) -> BigInt(0)))
  }

  test("normalization does not relax independent root or invalid width diagnostics") {
    val first = HdlBool.param("SAME", default = false).asElabBool
    val second = HdlBool.param("SAME", default = false).asElabBool
    val independent = intercept[ParameterizedVerilogException] { first && second }
    assert(independent.code == "SPINAL-ELAB-DOMAIN-EXACT-CORRELATION-UNSUPPORTED")
    val width = intercept[ParameterizedVerilogException] { first.toElabInt.bits }
    assert(width.code == "SPINAL-ELAB-INT-WIDTH-DOMAIN-INVALID")
  }

  test("frontend predicates retain portable address width helper support") {
    val depth = HdlInt.param("DEPTH", default = 1, min = 1, max = 4)
    val predicate = depth.addressWidth.hdlEq(HdlInt.literal(2)).asElabBool
    val encoded = predicate.toElabInt
    assert(values(encoded) == Vector(1 -> 0, 2 -> 0, 3 -> 1, 4 -> 1).map {
      case (input, output) => BigInt(input) -> BigInt(output)
    })
    assert(encoded.expression.verilog.count(_ == '?') <= 2, encoded.expression.verilog)
    assert(encoded.sourceLocation == predicate.sourceLocation)
  }

  test("copied normalized integer metadata cannot reacquire exact authority") {
    val integer = HdlBool.param("PPC4", default = false).asElabBool.toElabInt
    val copied = integer.expression.copy()
    assert(copied.exactDomain.nonEmpty)
    assert(!copied.hasExactAuthority)
    val failure = intercept[ParameterizedVerilogException] {
      ElabInt.fromExpression(copied).elabEq(1).toElabInt
    }
    assert(failure.code == "SPINAL-ELAB-DOMAIN-EXACT-AUTHORITY-MISSING")
  }
}
