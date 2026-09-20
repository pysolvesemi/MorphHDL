package morphhdl.backend.verilog2001

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{Literal, ParameterRef}
import morphhdl.paramrtl.ModuleItem.CombinationalIf
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import morphhdl.paramrtl._
import org.scalatest.funsuite.AnyFunSuite

class CombinationalIfEmitterTests extends AnyFunSuite {
  test("emits strict Verilog-2001 output reg and canonical always-at-star if else") {
    val expected =
      """module RuntimeMux (
        |  input  wire [7:0] data_false,
        |  input  wire [7:0] data_true,
        |  output reg [7:0] result,
        |  input  wire [0:0] select
        |);
        |
        |  always @* begin : p_runtime_mux
        |    if (select == 1'b1) begin
        |      result = data_true;
        |    end else begin
        |      result = data_false;
        |    end
        |  end
        |
        |endmodule
        |""".stripMargin

    assert(emit(muxDesign()) == expected)
  }

  test("sorts procedural assignments deterministically without changing branch semantics") {
    val normal = emit(twoOutputDesign(reverse = false))
    val reversed = emit(twoOutputDesign(reverse = true))

    assert(normal == reversed)
    assert(normal.indexOf("aux = data_true;") < normal.indexOf("result = data_true;"))
    assert(normal.indexOf("aux = data_false;") < normal.indexOf("result = data_false;"))
  }

  test("emits exact parameterized packed widths while retaining a one-bit runtime condition") {
    val verilog = emit(muxDesign(parameterized = true))

    assert(verilog.contains("parameter integer WIDTH = 8"))
    assert(verilog.contains("input  wire [WIDTH-1:0] data_false"))
    assert(verilog.contains("output reg [WIDTH-1:0] result"))
    assert(verilog.contains("if (select == 1'b1) begin"))
  }

  test("retains portable signed-32 checks for process-module width parameters") {
    val outside = BigInt(Int.MaxValue) + 1
    val base = muxDesign(parameterized = true).modules.head
    val parameter = IntegerParameter(
      "WIDTH",
      default = 8,
      constraints = Vector(MinInclusive(1), MaxInclusive(outside))
    )
    val invalid = Design(base.name, Vector(base.copy(parameters = Vector(parameter))))

    val diagnostics = emitFailure(invalid)
    assert(
      diagnostics.codes.contains("V2001-INTEGER-DOMAIN-OUT-OF-RANGE"),
      diagnostics.values.mkString("\n")
    )
  }

  test("keeps legacy continuous outputs as wires in other modules") {
    val packed = PackedBits(Literal(8), Unsigned)
    val legacy = ModuleDef(
      "LegacyWire",
      Vector.empty,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(ModuleItem.ContinuousAssign(Ref("dout"), Ref("din")))
    )
    val runtime = muxDesign().modules.head
    val verilog = emit(Design(runtime.name, Vector(legacy, runtime)))

    assert(verilog.contains("output wire [7:0] dout"))
    assert(verilog.contains("output reg [7:0] result"))
  }

  test("rejects reserved process labels before emission") {
    val top = muxDesign().modules.head
    val process = top.items.head.asInstanceOf[CombinationalIf]
    val invalid = Design(top.name, Vector(top.copy(items = Vector(process.copy(label = "always")))))

    val diagnostics = emitFailure(invalid)
    assert(diagnostics.codes.contains("V2001-RESERVED-IDENTIFIER"), diagnostics.values.mkString("\n"))
  }

  private def muxDesign(parameterized: Boolean = false): Design = {
    val width: IntExpr = if (parameterized) ParameterRef("WIDTH") else Literal(8)
    val parameters =
      if (parameterized)
        Vector(IntegerParameter("WIDTH", 8, Vector(MinInclusive(1), MaxInclusive(32))))
      else Vector.empty
    val packed = PackedBits(width, Unsigned)
    val top = ModuleDef(
      "RuntimeMux",
      parameters,
      Vector(
        Port("data_false", Input, packed),
        Port("data_true", Input, packed),
        Port("result", Output, packed),
        Port("select", Input, PackedBits(Literal(1), Unsigned))
      ),
      Vector(CombinationalIf(
        "p_runtime_mux",
        Ref("select"),
        Vector(ProceduralAssign(Ref("result"), Ref("data_true"))),
        Vector(ProceduralAssign(Ref("result"), Ref("data_false")))
      ))
    )
    Design(top.name, Vector(top))
  }

  private def twoOutputDesign(reverse: Boolean): Design = {
    val base = muxDesign().modules.head
    val packed = PackedBits(Literal(8), Unsigned)
    val whenTrue = Vector(
      ProceduralAssign(Ref("result"), Ref("data_true")),
      ProceduralAssign(Ref("aux"), Ref("data_true"))
    )
    val whenFalse = Vector(
      ProceduralAssign(Ref("result"), Ref("data_false")),
      ProceduralAssign(Ref("aux"), Ref("data_false"))
    )
    val process = CombinationalIf(
      "p_runtime_mux",
      Ref("select"),
      if (reverse) whenTrue.reverse else whenTrue,
      if (reverse) whenFalse.reverse else whenFalse
    )
    val ports = base.ports :+ Port("aux", Output, packed)
    val top = base.copy(ports = if (reverse) ports.reverse else ports, items = Vector(process))
    Design(top.name, Vector(top))
  }

  private def emit(design: Design): String = Verilog2001Emitter.emit(design) match {
    case Right(value)      => value
    case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
  }

  private def emitFailure(design: Design): DiagnosticSet = Verilog2001Emitter.emit(design) match {
    case Left(diagnostics) => diagnostics
    case Right(verilog)    => fail(s"Expected target diagnostics, emitted:\n$verilog")
  }
}
