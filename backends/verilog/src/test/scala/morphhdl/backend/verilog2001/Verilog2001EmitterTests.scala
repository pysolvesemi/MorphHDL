package morphhdl.backend.verilog2001

import morphhdl.paramrtl.IntConstraint.MinInclusive
import morphhdl.paramrtl.IntExpr.ParameterRef
import morphhdl.paramrtl.ModuleItem.ContinuousAssign
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import morphhdl.paramrtl._
import org.scalatest.funsuite.AnyFunSuite

class Verilog2001EmitterTests extends AnyFunSuite {
  test("emits the ParameterizedWire contract byte for byte") {
    assertEmission(ParameterizedWireFixture.design(), ParameterizedWireFixture.expected)
  }

  test("emission is deterministic and independent of construction order") {
    val normal = emit(ParameterizedWireFixture.design())
    val reversed = emit(ParameterizedWireFixture.design(reverseConstructionOrder = true))

    assert(normal == emit(ParameterizedWireFixture.design()))
    assert(normal == reversed)
    assert(normal.endsWith("\n"))
    assert(!normal.endsWith("\n\n"))
  }

  test("does not expose a validation bypass") {
    val invalid = ParameterizedWireFixture
      .design()
      .copy(
        modules = Vector(
          ParameterizedWireFixture
            .design()
            .modules
            .head
            .copy(
              parameters = Vector(IntegerParameter("WIDTH", 8))
            )
        )
      )

    assertDiagnostic(invalid, "PRTL-WIDTH-NOT-PROVEN-POSITIVE")
  }

  test("rejects Verilog-2001 reserved identifiers") {
    val packed = PackedBits(ParameterRef("WIDTH"), Unsigned)
    val design = Design(
      top = "module",
      modules = Vector(
        ModuleDef(
          name = "module",
          parameters = Vector(IntegerParameter("WIDTH", 8, Vector(MinInclusive(1)))),
          ports = Vector(Port("din", Input, packed), Port("dout", Output, packed)),
          items = Vector(ContinuousAssign(Ref("dout"), Ref("din")))
        )
      )
    )

    assertDiagnostic(design, "V2001-RESERVED-IDENTIFIER")
  }

  test("rejects parameter integers that Verilog-2001 cannot represent portably") {
    val invalid = ParameterizedWireFixture
      .design()
      .copy(
        modules = Vector(
          ParameterizedWireFixture
            .design()
            .modules
            .head
            .copy(
              parameters = Vector(
                IntegerParameter(
                  "WIDTH",
                  BigInt(Int.MaxValue) + 1,
                  Vector(MinInclusive(1))
                )
              )
            )
        )
      )

    assertDiagnostic(invalid, "V2001-INTEGER-OUT-OF-RANGE")
  }

  test("rejects an integer parameter whose legal domain is not target bounded") {
    val invalid = ParameterizedWireFixture
      .design()
      .copy(
        modules = Vector(
          ParameterizedWireFixture
            .design()
            .modules
            .head
            .copy(
              parameters = Vector(IntegerParameter("WIDTH", 8, Vector(MinInclusive(1))))
            )
        )
      )

    assertDiagnostic(invalid, "V2001-INTEGER-DOMAIN-OUT-OF-RANGE")
  }

  test("preserves one-bit packed-vector intent") {
    val packed = PackedBits(IntExpr.Literal(1), Unsigned)
    val design = Design(
      top = "OneBitPacked",
      modules = Vector(
        ModuleDef(
          name = "OneBitPacked",
          parameters = Vector.empty,
          ports = Vector(Port("din", Input, packed), Port("dout", Output, packed)),
          items = Vector(ContinuousAssign(Ref("dout"), Ref("din")))
        )
      )
    )

    assert(emit(design).contains("wire [0:0] din"))
    assert(emit(design).contains("wire [0:0] dout"))
  }

  test("rejects syntax-injection identifiers before emission") {
    val invalidName = "bad); endmodule"
    val invalid = ParameterizedWireFixture
      .design()
      .copy(
        top = invalidName,
        modules = Vector(ParameterizedWireFixture.design().modules.head.copy(name = invalidName))
      )

    assertDiagnostic(invalid, "PRTL-INVALID-IDENTIFIER")
  }

  test("rejects duplicate logical module definitions") {
    val module = ParameterizedWireFixture.design().modules.head
    val invalid = ParameterizedWireFixture.design().copy(modules = Vector(module, module))

    assertDiagnostic(invalid, "PRTL-DUPLICATE-MODULE")
  }

  test("rejects an unresolved top module") {
    val invalid = ParameterizedWireFixture.design().copy(top = "MissingTop")

    assertDiagnostic(invalid, "PRTL-UNRESOLVED-TOP")
  }

  private def emit(design: Design): String =
    Verilog2001Emitter.emit(design) match {
      case Right(value)      => value
      case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
    }

  private def assertEmission(design: Design, expected: String): Unit =
    assert(emit(design) == expected)

  private def assertDiagnostic(design: Design, code: String): Unit =
    Verilog2001Emitter.emit(design) match {
      case Left(diagnostics) => assert(diagnostics.codes.contains(code), diagnostics.values.mkString("\n"))
      case Right(verilog)    => fail(s"Expected diagnostic $code, emitted:\n$verilog")
    }
}
