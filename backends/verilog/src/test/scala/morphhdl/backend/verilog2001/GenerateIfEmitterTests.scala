package morphhdl.backend.verilog2001

import morphhdl.paramrtl.BoolExpr.{And, Literal => BoolLiteral, Not, Or, ParameterRef => BoolParameterRef}
import morphhdl.paramrtl.IntExpr.Literal
import morphhdl.paramrtl.ModuleItem.{ContinuousAssign, GenerateIf}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import morphhdl.paramrtl._
import org.scalatest.funsuite.AnyFunSuite

class GenerateIfEmitterTests extends AnyFunSuite {
  test("emits typed Boolean parameters as strict Verilog-2001 integer 1/0") {
    val verilog = emit(design())
    val expected =
      """module ConditionalWire #(
        |  parameter integer BYPASS = 0,
        |  parameter integer ENABLE = 1
        |) (
        |  output wire [7:0] aux,
        |  input  wire [7:0] din,
        |  output wire [7:0] dout
        |);
        |
        |  generate
        |    if (ENABLE == 1 && !(BYPASS == 1)) begin : g_enabled
        |      assign aux = din;
        |      assign dout = din;
        |    end else begin : g_disabled
        |      assign aux = din;
        |      assign dout = din;
        |    end
        |  endgenerate
        |
        |endmodule
        |""".stripMargin

    assert(verilog == expected)
  }

  test("renders Boolean precedence and literals exactly") {
    val condition = Or(
      Not(And(BoolParameterRef("ENABLE"), BoolParameterRef("BYPASS"))),
      BoolLiteral(false)
    )
    val verilog = emit(design(condition = condition))

    assert(verilog.contains("if (!(ENABLE == 1 && BYPASS == 1) || 1'b0) begin : g_enabled"))
  }

  test("sorts branch items deterministically without changing branch semantics") {
    val normal = emit(design(reverseBranchItems = false))
    val reversed = emit(design(reverseBranchItems = true))

    assert(normal == reversed)
    assert(normal.indexOf("assign aux = din;") < normal.indexOf("assign dout = din;"))
  }

  test("rejects reserved Boolean parameter and branch identifiers before emission") {
    val reservedParameter = design().copy(modules = design().modules.map(_.copy(
      booleanParameters = Vector(BooleanParameter("wire", default = true)),
      items = conditionalItems(BoolParameterRef("wire"), reverse = false)
    )))
    assertDiagnostic(reservedParameter, "V2001-RESERVED-IDENTIFIER")

    val top = design().modules.head
    val generate = top.items.head.asInstanceOf[GenerateIf]
    val reservedLabel = design().copy(modules = Vector(top.copy(items = Vector(
      generate.copy(whenTrue = generate.whenTrue.copy(label = "generate"))
    ))))
    assertDiagnostic(reservedLabel, "V2001-RESERVED-IDENTIFIER")
  }

  private def design(
      condition: BoolExpr = And(BoolParameterRef("ENABLE"), Not(BoolParameterRef("BYPASS"))),
      reverseBranchItems: Boolean = false
  ): Design = {
    val packed = PackedBits(Literal(8), Unsigned)
    val top = ModuleDef(
      name = "ConditionalWire",
      parameters = Vector.empty,
      ports = Vector(
        Port("aux", Output, packed),
        Port("din", Input, packed),
        Port("dout", Output, packed)
      ),
      items = conditionalItems(condition, reverseBranchItems),
      booleanParameters = Vector(
        BooleanParameter("ENABLE", default = true),
        BooleanParameter("BYPASS", default = false)
      )
    )
    Design(top.name, Vector(top))
  }

  private def conditionalItems(condition: BoolExpr, reverse: Boolean): Vector[ModuleItem] = {
    val assignments = Vector(
      ContinuousAssign(Ref("dout"), Ref("din")),
      ContinuousAssign(Ref("aux"), Ref("din"))
    )
    val body = if (reverse) assignments.reverse else assignments
    Vector(
      GenerateIf(
        condition,
        GenerateBlock("g_enabled", body),
        GenerateBlock("g_disabled", body.reverse)
      )
    )
  }

  private def emit(design: Design): String = Verilog2001Emitter.emit(design) match {
    case Right(value)      => value
    case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
  }

  private def assertDiagnostic(design: Design, code: String): Unit = Verilog2001Emitter.emit(design) match {
    case Left(diagnostics) => assert(diagnostics.codes.contains(code), diagnostics.values.mkString("\n"))
    case Right(verilog)    => fail(s"Expected $code, emitted:\n$verilog")
  }
}
