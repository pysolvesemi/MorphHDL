package morphhdl.paramrtl

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{Literal, ParameterRef}
import morphhdl.paramrtl.ModuleItem.ContinuousAssign
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import org.scalatest.funsuite.AnyFunSuite

class ParamRtlValidatorTests extends AnyFunSuite {
  test("validates a constrained symbolic packed width") {
    assert(ParamRtlValidator.validate(parameterizedWire()).isRight)
  }

  test("does not use a positive default as proof that a symbolic width is positive") {
    val design = parameterizedWire(
      parameter = IntegerParameter("WIDTH", 8)
    )

    assertCodes(design, "PRTL-WIDTH-NOT-PROVEN-POSITIVE")
  }

  test("rejects duplicate declarations deterministically") {
    val design = parameterizedWire().copy(
      modules = Vector(
        parameterizedWire().modules.head.copy(
          parameters = Vector(
            IntegerParameter("WIDTH", 8, Vector(MinInclusive(1))),
            IntegerParameter("WIDTH", 13, Vector(MinInclusive(1)))
          )
        )
      )
    )

    assertCodes(design, "PRTL-DUPLICATE-PARAMETER")
  }

  test("rejects unresolved parameter and RTL references") {
    val base = parameterizedWire().modules.head
    val design = Design(
      top = base.name,
      modules = Vector(
        base.copy(
          ports = base.ports.map(_.copy(dataType = PackedBits(ParameterRef("MISSING"), Unsigned))),
          items = Vector(ContinuousAssign(Ref("dout"), Ref("missing_signal")))
        )
      )
    )

    assertCodes(
      design,
      "PRTL-UNRESOLVED-PARAMETER",
      "PRTL-UNRESOLVED-RTL-REFERENCE"
    )
  }

  test("rejects illegal input drivers and missing output drivers") {
    val base = parameterizedWire().modules.head
    val design = Design(
      top = base.name,
      modules = Vector(base.copy(items = Vector(ContinuousAssign(Ref("din"), Ref("dout")))))
    )

    assertCodes(design, "PRTL-ILLEGAL-INPUT-DRIVER", "PRTL-UNDRIVEN-OUTPUT")
  }

  test("rejects structurally different assignment types") {
    val base = parameterizedWire().modules.head
    val design = Design(
      top = base.name,
      modules = Vector(
        base.copy(
          ports = Vector(
            Port("din", Input, PackedBits(Literal(8), Unsigned)),
            Port("dout", Output, PackedBits(ParameterRef("WIDTH"), Unsigned))
          )
        )
      )
    )

    assertCodes(design, "PRTL-TYPE-MISMATCH")
  }

  test("rejects inconsistent constraints and illegal defaults") {
    val design = parameterizedWire(
      parameter = IntegerParameter(
        "WIDTH",
        8,
        Vector(MinInclusive(10), MaxInclusive(4))
      )
    )

    assertCodes(
      design,
      "PRTL-DEFAULT-VIOLATES-CONSTRAINT",
      "PRTL-INCONSISTENT-CONSTRAINTS"
    )
  }

  test("rejects multiple drivers") {
    val base = parameterizedWire().modules.head
    val assignment = ContinuousAssign(Ref("dout"), Ref("din"))
    val design = Design(
      top = base.name,
      modules = Vector(base.copy(items = Vector(assignment, assignment)))
    )

    assertCodes(design, "PRTL-MULTIPLE-DRIVERS")
  }

  private def parameterizedWire(
      parameter: IntegerParameter = IntegerParameter("WIDTH", 8, Vector(MinInclusive(1)))
  ): Design = {
    val width = ParameterRef(parameter.name)
    val packed = PackedBits(width, Unsigned)
    Design(
      top = "ParameterizedWire",
      modules = Vector(
        ModuleDef(
          name = "ParameterizedWire",
          parameters = Vector(parameter),
          ports = Vector(
            Port("din", Input, packed),
            Port("dout", Output, packed)
          ),
          items = Vector(ContinuousAssign(Ref("dout"), Ref("din")))
        )
      )
    )
  }

  private def assertCodes(design: Design, expected: String*): Unit =
    ParamRtlValidator.validate(design) match {
      case Left(diagnostics) =>
        expected.foreach(code => assert(diagnostics.codes.contains(code), diagnostics.values.mkString("\n")))
        assert(diagnostics.values == diagnostics.values.sortBy(d => (d.pathString, d.code, d.message)))
      case Right(_) => fail(s"Expected diagnostics ${expected.mkString(", ")}, but validation passed")
    }
}
