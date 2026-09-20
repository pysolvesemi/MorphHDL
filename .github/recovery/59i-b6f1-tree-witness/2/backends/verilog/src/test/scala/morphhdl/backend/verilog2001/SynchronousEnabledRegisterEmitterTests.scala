package morphhdl.backend.verilog2001

import morphhdl.paramrtl.BoolExpr.{ParameterRef => BoolParameterRef}
import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{Add, Literal, ParameterRef, Select}
import morphhdl.paramrtl.ModuleItem.{ContinuousAssign, SynchronousEnabledRegister}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.{Signed, Unsigned}
import morphhdl.paramrtl._
import org.scalatest.funsuite.AnyFunSuite

class SynchronousEnabledRegisterEmitterTests extends AnyFunSuite {
  test("emits reset-priority capture-on-enable with an implicit hold") {
    val expected =
      """module SyncEnabledRegister (
        |  input  wire [0:0] clk,
        |  input  wire [7:0] data_in,
        |  output reg [7:0] data_out,
        |  input  wire [0:0] enable,
        |  input  wire [0:0] reset
        |);
        |
        |  always @(posedge clk) begin : p_sync_enabled_register
        |    if (reset == 1'b1) begin
        |      data_out <= {8{1'b0}};
        |    end else if (enable == 1'b1) begin
        |      data_out <= data_in;
        |    end
        |  end
        |
        |endmodule
        |""".stripMargin

    val verilog = emit(registerDesign())
    assert(verilog == expected)
    assert(!verilog.contains("else begin\n      data_out <= data_out"), verilog)
  }

  test("uses atomic and parenthesized replication widths exactly") {
    val parameter = emit(registerDesign(width = ParameterRef("WIDTH"), parameterized = true))
    assert(parameter.contains("data_out <= {WIDTH{1'b0}};"), parameter)

    val additive = emit(
      registerDesign(width = Add(ParameterRef("WIDTH"), Literal(1)), parameterized = true)
    )
    assert(additive.contains("data_out <= {(WIDTH + 1){1'b0}};"), additive)

    val selectedWidth = Select(
      BoolParameterRef("WIDE"),
      Add(ParameterRef("WIDTH"), Literal(1)),
      Literal(4)
    )
    val selected = emit(
      registerDesign(width = selectedWidth, parameterized = true, withBooleanParameter = true)
    )
    assert(
      selected.contains("data_out <= {((WIDE == 1) ? WIDTH + 1 : 4){1'b0}};"),
      selected
    )
  }

  test("retains exact signed packed data types and zero reset semantics") {
    val verilog = emit(registerDesign(signed = true))
    assert(verilog.contains("input  wire signed [7:0] data_in"), verilog)
    assert(verilog.contains("output reg signed [7:0] data_out"), verilog)
    assert(verilog.contains("data_out <= {8{1'b0}};"), verilog)
  }

  test("sorts ports deterministically without changing enable roles") {
    assert(emit(registerDesign()) == emit(registerDesign(reversePorts = true)))
  }

  test("keeps continuous outputs as wires in modules without an enabled register") {
    val packed = PackedBits(Literal(8), Unsigned)
    val legacy = ModuleDef(
      "LegacyWire",
      Vector.empty,
      Vector(Port("din", Input, packed), Port("dout", Output, packed)),
      Vector(ContinuousAssign(Ref("dout"), Ref("din")))
    )
    val register = registerDesign().modules.head
    val verilog = emit(Design(register.name, Vector(legacy, register)))

    assert(verilog.contains("output wire [7:0] dout"), verilog)
    assert(verilog.contains("output reg [7:0] data_out"), verilog)
  }

  test("rejects reserved process labels before emission") {
    val top = registerDesign().modules.head
    val process = top.items.head.asInstanceOf[SynchronousEnabledRegister]
    val invalid = Design(top.name, Vector(top.copy(items = Vector(process.copy(label = "always")))))

    val diagnostics = emitFailure(invalid)
    assert(diagnostics.codes.contains("V2001-RESERVED-IDENTIFIER"), diagnostics.values.mkString("\n"))
  }

  test("retains portable signed-32 checks for enabled-register width parameters") {
    val outside = BigInt(Int.MaxValue) + 1
    val width = IntegerParameter(
      "WIDTH",
      default = 8,
      constraints = Vector(MinInclusive(1), MaxInclusive(outside))
    )
    val base = registerDesign(width = ParameterRef("WIDTH"), parameterized = true).modules.head
    val invalid = Design(base.name, Vector(base.copy(parameters = Vector(width))))

    val diagnostics = emitFailure(invalid)
    assert(
      diagnostics.codes.contains("V2001-INTEGER-DOMAIN-OUT-OF-RANGE"),
      diagnostics.values.mkString("\n")
    )
  }

  private def registerDesign(
      width: IntExpr = Literal(8),
      signed: Boolean = false,
      parameterized: Boolean = false,
      withBooleanParameter: Boolean = false,
      reversePorts: Boolean = false
  ): Design = {
    val parameters =
      if (parameterized)
        Vector(IntegerParameter("WIDTH", 8, Vector(MinInclusive(1), MaxInclusive(31))))
      else Vector.empty
    val booleanParameters =
      if (withBooleanParameter) Vector(BooleanParameter("WIDE", default = true)) else Vector.empty
    val packed = PackedBits(width, if (signed) Signed else Unsigned)
    val ports = Vector(
      Port("clk", Input, PackedBits(Literal(1), Unsigned)),
      Port("data_in", Input, packed),
      Port("data_out", Output, packed),
      Port("enable", Input, PackedBits(Literal(1), Unsigned)),
      Port("reset", Input, PackedBits(Literal(1), Unsigned))
    )
    val top = ModuleDef(
      "SyncEnabledRegister",
      parameters,
      if (reversePorts) ports.reverse else ports,
      Vector(SynchronousEnabledRegister(
        "p_sync_enabled_register",
        Ref("clk"),
        Ref("reset"),
        Ref("enable"),
        ProceduralAssign(Ref("data_out"), Ref("data_in"))
      )),
      booleanParameters = booleanParameters
    )
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
