package morphhdl.frontend

import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.IntExpr.{Add, Literal, LocalParameterRef, Multiply, ParameterRef}
import morphhdl.paramrtl.PortDirection.Input
import morphhdl.paramrtl._
import org.scalatest.funsuite.AnyFunSuite

class LocalParameterFrontendTests extends AnyFunSuite {
  test("emits declared locals dependency-first with exact expressions and witnesses") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    val total = localParam("TOTAL_WIDTH", width * 2)
    val padded = localParam("PADDED_WIDTH", total + 3)

    assert(total.witness == 16)
    assert(padded.witness == 19)
    assert(total.expression == LocalParameterRef("TOTAL_WIDTH"))
    assert(padded.expression == LocalParameterRef("PADDED_WIDTH"))

    val module = moduleDef(
      name = "DerivedWidth",
      parameters = Vector(integerParameter(width)),
      ports = Vector(port("data", Input, packedBits(padded))),
      items = captureItems {},
      localParameters = Vector(integerLocalParameter(padded), integerLocalParameter(total))
    )

    assert(module.localParameters == Vector(
      IntegerLocalParameter("TOTAL_WIDTH", Multiply(ParameterRef("WIDTH"), Literal(2))),
      IntegerLocalParameter("PADDED_WIDTH", Add(LocalParameterRef("TOTAL_WIDTH"), Literal(3)))
    ))
    assert(module.ports.head.dataType.width == LocalParameterRef("PADDED_WIDTH"))
    assert(ParamRtlValidator.validate(Design(module.name, Vector(module))).isRight)
  }

  test("preserves unused but explicitly declared transitive locals") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    val base = localParam("BASE", width + 1)
    val unused = localParam("UNUSED_DERIVED", base * 2)

    val module = moduleDef(
      name = "UnusedLocals",
      parameters = Vector(integerParameter(width)),
      ports = Vector.empty,
      items = captureItems {},
      localParameters = Vector(integerLocalParameter(unused), integerLocalParameter(base))
    )

    assert(module.localParameters.map(_.name) == Vector("BASE", "UNUSED_DERIVED"))
  }

  test("orders independent locals lexically regardless of declaration-vector order") {
    def names(reverse: Boolean): Vector[String] = {
      val zed = localParam("ZED", HdlInt.literal(1))
      val alpha = localParam("ALPHA", HdlInt.literal(2))
      val declarations = Vector(integerLocalParameter(zed), integerLocalParameter(alpha))
      moduleDef(
        name = if (reverse) "LexicalReverse" else "LexicalForward",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = captureItems {},
        localParameters = if (reverse) declarations.reverse else declarations
      ).localParameters.map(_.name)
    }

    assert(names(reverse = false) == Vector("ALPHA", "ZED"))
    assert(names(reverse = true) == Vector("ALPHA", "ZED"))
  }

  test("rejects a missing transitive local declaration") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    val base = localParam("BASE", width + 1)
    val padded = localParam("PADDED", base + 1)

    val error = intercept[FrontendException] {
      moduleDef(
        name = "MissingBase",
        parameters = Vector(integerParameter(width)),
        ports = Vector.empty,
        items = captureItems {},
        localParameters = Vector(integerLocalParameter(padded))
      )
    }

    assert(error.code == "MORPH-FRONTEND-LOCAL-PARAMETER-NOT-DECLARED")
    assert(error.origin == base.origin)
    assert(error.suggestedReplacement.contains("integerLocalParameter"))
  }

  test("rejects duplicate handles and distinct duplicate names") {
    val one = localParam("ONE", HdlInt.literal(1))
    val duplicateHandle = intercept[FrontendException] {
      moduleDef(
        name = "DuplicateHandle",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = captureItems {},
        localParameters = Vector(integerLocalParameter(one), integerLocalParameter(one))
      )
    }
    assert(duplicateHandle.code == "MORPH-FRONTEND-LOCAL-PARAMETER-DECLARATION-DUPLICATE")

    val first = localParam("SAME", HdlInt.literal(1))
    val second = localParam("SAME", HdlInt.literal(2))
    val duplicateName = intercept[FrontendException] {
      moduleDef(
        name = "DuplicateName",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = captureItems {},
        localParameters = Vector(integerLocalParameter(first), integerLocalParameter(second))
      )
    }
    assert(duplicateName.code == "MORPH-FRONTEND-LOCAL-PARAMETER-NAME-DUPLICATE")
  }

  test("rejects a distinct same-named local handle at its use site") {
    val declared = localParam("LOCAL_WIDTH", HdlInt.literal(8))
    val used = localParam("LOCAL_WIDTH", HdlInt.literal(4))

    val error = intercept[FrontendException] {
      moduleDef(
        name = "LocalAlias",
        parameters = Vector.empty,
        ports = Vector(port("data", Input, packedBits(used))),
        items = captureItems {},
        localParameters = Vector(integerLocalParameter(declared))
      )
    }

    assert(error.code == "MORPH-FRONTEND-LOCAL-PARAMETER-TOKEN-MISMATCH")
    assert(error.origin == used.origin)
    assert(error.detail.contains(declared.origin.rendered))
  }

  test("rejects local use without an explicit declaration") {
    val width = localParam("LOCAL_WIDTH", HdlInt.literal(8))
    val error = intercept[FrontendException] {
      moduleDef(
        name = "UndeclaredLocal",
        parameters = Vector.empty,
        ports = Vector(port("data", Input, packedBits(width))),
        items = captureItems {}
      )
    }

    assert(error.code == "MORPH-FRONTEND-LOCAL-PARAMETER-NOT-DECLARED")
    assert(error.origin == width.origin)
  }

  test("rejects reusing a claimed local handle in another module") {
    val local = localParam("LOCAL_WIDTH", HdlInt.literal(8))
    val declaration = integerLocalParameter(local)
    moduleDef(
      name = "FirstOwner",
      parameters = Vector.empty,
      ports = Vector.empty,
      items = captureItems {},
      localParameters = Vector(declaration)
    )

    val otherModule = intercept[FrontendException] {
      moduleDef(
        name = "SecondOwner",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = captureItems {},
        localParameters = Vector(declaration)
      )
    }
    val repeatedBoundary = intercept[FrontendException] {
      moduleDef(
        name = "FirstOwner",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = captureItems {},
        localParameters = Vector(declaration)
      )
    }

    Vector(otherModule, repeatedBoundary).foreach { error =>
      assert(error.code == "MORPH-FRONTEND-LOCAL-PARAMETER-FOREIGN")
      assert(error.origin == local.origin)
      assert(error.detail.contains("FirstOwner"))
    }

    val useOnly = intercept[FrontendException] {
      moduleDef(
        name = "UseOnlyOwner",
        parameters = Vector.empty,
        ports = Vector(port("data", Input, packedBits(local))),
        items = captureItems {}
      )
    }
    val derived = localParam("DERIVED", local + 1)
    val dependencyOnly = intercept[FrontendException] {
      moduleDef(
        name = "DependencyOwner",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = captureItems {},
        localParameters = Vector(integerLocalParameter(derived))
      )
    }
    Vector(useOnly, dependencyOnly).foreach { error =>
      assert(error.code == "MORPH-FRONTEND-LOCAL-PARAMETER-FOREIGN")
      assert(error.origin == local.origin)
      assert(error.detail.contains("FirstOwner"))
    }
  }

  test("rejects public and local parameters with the same name") {
    val public = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    val local = localParam("WIDTH", HdlInt.literal(4))
    val error = intercept[FrontendException] {
      moduleDef(
        name = "NameCollision",
        parameters = Vector(integerParameter(public)),
        ports = Vector.empty,
        items = captureItems {},
        localParameters = Vector(integerLocalParameter(local))
      )
    }

    assert(error.code == "MORPH-FRONTEND-LOCAL-PARAMETER-NAME-COLLISION")
    assert(error.origin == local.origin)
  }

  test("rejects unresolved declaration provenance and non-local expressions") {
    val raw = IntegerLocalParameter("RAW", Literal(1))
    val forged = FrontendNode(raw, origin = SourceOrigin("forged.scala", 7))
    val unresolved = intercept[FrontendException] {
      moduleDef(
        name = "ForgedLocal",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = captureItems {},
        localParameters = Vector(forged)
      )
    }
    assert(unresolved.code == "MORPH-FRONTEND-LOCAL-PARAMETER-IDENTITY-UNRESOLVED")
    assert(unresolved.sourceLocation == "forged.scala:7")

    val nonLocal = intercept[FrontendException] {
      integerLocalParameter(HdlInt.literal(1) + 2)
    }
    assert(nonLocal.code == "MORPH-FRONTEND-NOT-A-LOCAL-PARAMETER")
  }

  test("requires all public dependencies of a local declaration") {
    val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 64)
    val local = localParam("LOCAL_WIDTH", width + 1)
    val error = intercept[FrontendException] {
      moduleDef(
        name = "MissingPublic",
        parameters = Vector.empty,
        ports = Vector.empty,
        items = captureItems {},
        localParameters = Vector(integerLocalParameter(local))
      )
    }

    assert(error.code == "MORPH-FRONTEND-PARAMETER-NOT-DECLARED")
    assert(error.origin == width.origin)
  }

  test("rejects invalid names and generate-index-dependent locals at construction") {
    val invalid = intercept[FrontendException] {
      localParam("not-portable", HdlInt.literal(1))
    }
    assert(invalid.code == "MORPH-FRONTEND-INVALID-LOCAL-PARAMETER-NAME")

    val lanes = HdlInt.param("LANES", default = 1, min = 1, max = 4)
    var loopError: FrontendException = null
    captureItems {
      for (lane <- 0 until lanes) {
        loopError = intercept[FrontendException] {
          localParam("INDEXED", lane * HdlInt.literal(1))
        }
      }
    }
    assert(loopError.code == "MORPH-FRONTEND-GENINDEX-CONSUMER-UNSUPPORTED")
  }

  test("lets ParamRTL reject a denominator whose legal domain contains zero") {
    val denominator = HdlInt.param("DENOMINATOR", default = 1, min = -1, max = 1)
    val quotient = localParam("QUOTIENT", HdlInt.literal(10) / denominator)
    val remainder = localParam("REMAINDER", HdlInt.literal(10) % denominator)
    val module = moduleDef(
      name = "ZeroDomain",
      parameters = Vector(integerParameter(denominator)),
      ports = Vector.empty,
      items = captureItems {},
      localParameters = Vector(integerLocalParameter(quotient), integerLocalParameter(remainder))
    )

    val diagnostics = ParamRtlValidator.validate(Design(module.name, Vector(module))) match {
      case Left(values) => values
      case Right(_)     => fail("expected full-domain divisor rejection")
    }
    assert(diagnostics.codes == Vector("PRTL-DIVISOR-MAY-BE-ZERO", "PRTL-DIVISOR-MAY-BE-ZERO"))
    assert(diagnostics.values.map(_.pathString).forall(_.matches(".*localParameters/(QUOTIENT|REMAINDER)/value")))
  }

  test("checks escaped operands before inspecting a zero divisor witness") {
    val lanes = HdlInt.param("LANES", default = 1, min = 1, max = 1)
    var escapedZero: HdlInt = null
    captureItems {
      for (lane <- 0 until lanes) {
        escapedZero = lane * HdlInt.literal(0)
      }
    }

    val divide = intercept[FrontendException](HdlInt.literal(8) / escapedZero)
    val modulo = intercept[FrontendException](HdlInt.literal(8) % escapedZero)
    assert(divide.code == "MORPH-FRONTEND-GENINDEX-ESCAPED")
    assert(modulo.code == "MORPH-FRONTEND-GENINDEX-ESCAPED")
  }
}
