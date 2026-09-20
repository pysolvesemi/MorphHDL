package morphhdl.backend.verilog2001

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{Literal, ParameterRef}
import morphhdl.paramrtl.ModuleItem.SynchronousStreamM2sPipe
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.{Signed, Unsigned}
import morphhdl.paramrtl._
import org.scalatest.funsuite.AnyFunSuite

class SynchronousStreamM2sPipeEmitterTests extends AnyFunSuite {
  test("emits the exact canonical strict Verilog-2001 synchronous stream m2s pipe") {
    val expected =
      """module SynchronousStreamM2sPipe #(
        |  parameter integer WIDTH = 8
        |) (
        |  input  wire [0:0] clk,
        |  output reg [WIDTH-1:0] pop_data,
        |  input  wire [0:0] pop_ready,
        |  output reg [0:0] pop_valid,
        |  input  wire [WIDTH-1:0] push_data,
        |  output wire [0:0] push_ready,
        |  input  wire [0:0] push_valid,
        |  input  wire [0:0] reset
        |);
        |
        |  assign push_ready = pop_ready || !pop_valid;
        |
        |  always @(posedge clk) begin : p_m2s_pipe
        |    if (reset == 1'b1) begin
        |      pop_valid <= 1'b0;
        |    end else if (push_ready == 1'b1) begin
        |      pop_valid <= push_valid;
        |    end
        |    if (push_ready == 1'b1) begin
        |      pop_data <= push_data;
        |    end
        |  end
        |
        |endmodule
        |""".stripMargin

    assert(emit(pipeDesign()) == expected)
  }

  test("preserves collapse full replacement stall stability and valid-only reset") {
    val verilog = emit(pipeDesign())
    assert(verilog.contains("assign push_ready = pop_ready || !pop_valid;"), verilog)
    assert(
      verilog.contains(
        "if (reset == 1'b1) begin\n      pop_valid <= 1'b0;\n    end else if (push_ready == 1'b1) begin"
      ),
      verilog
    )
    assert(count(verilog, "if (push_ready == 1'b1) begin") == 2, verilog)
    assert(verilog.contains("pop_valid <= push_valid;"), verilog)
    assert(verilog.contains("pop_data <= push_data;"), verilog)
    assert(!verilog.contains("pop_data <= {"), verilog)
    assert(!verilog.contains("memory"), verilog)
    assert(!verilog.contains("clog2"), verilog)
    assert(!verilog.contains("$clog2"), verilog)
  }

  test("preserves signed payloads deterministic port sorting and reserved-name checks") {
    val signed = emit(pipeDesign(signed = true))
    assert(signed.contains("input  wire signed [WIDTH-1:0] push_data"), signed)
    assert(signed.contains("output reg signed [WIDTH-1:0] pop_data"), signed)
    assert(emit(pipeDesign()) == emit(pipeDesign(reversePorts = true)))
    assertDiagnostic(pipeDesign(label = "always"), "V2001-RESERVED-IDENTIFIER")
  }

  private def pipeDesign(
      label: String = "p_m2s_pipe",
      signed: Boolean = false,
      reversePorts: Boolean = false
  ): Design = {
    val elementType = PackedBits(ParameterRef("WIDTH"), if (signed) Signed else Unsigned)
    val ports = Vector(
      Port("clk", Input, PackedBits(Literal(1), Unsigned)),
      Port("reset", Input, PackedBits(Literal(1), Unsigned)),
      Port("push_valid", Input, PackedBits(Literal(1), Unsigned)),
      Port("push_ready", Output, PackedBits(Literal(1), Unsigned)),
      Port("push_data", Input, elementType),
      Port("pop_valid", Output, PackedBits(Literal(1), Unsigned)),
      Port("pop_ready", Input, PackedBits(Literal(1), Unsigned)),
      Port("pop_data", Output, elementType)
    )
    val module = ModuleDef(
      "SynchronousStreamM2sPipe",
      Vector(IntegerParameter("WIDTH", 8, Vector(MinInclusive(1), MaxInclusive(32)))),
      if (reversePorts) ports.reverse else ports,
      Vector(SynchronousStreamM2sPipe(
        label,
        Ref("clk"),
        Ref("reset"),
        Ref("push_valid"),
        Ref("push_ready"),
        Ref("push_data"),
        Ref("pop_valid"),
        Ref("pop_ready"),
        Ref("pop_data"),
        elementType
      ))
    )
    Design(module.name, Vector(module))
  }

  private def emit(design: Design): String = Verilog2001Emitter.emit(design) match {
    case Right(value)      => value
    case Left(diagnostics) => fail(diagnostics.values.mkString("\n"))
  }

  private def assertDiagnostic(design: Design, code: String): Unit =
    Verilog2001Emitter.emit(design) match {
      case Left(diagnostics) => assert(diagnostics.codes.contains(code), diagnostics.values.mkString("\n"))
      case Right(verilog)    => fail(s"Expected $code, emitted:\n$verilog")
    }

  private def count(value: String, needle: String): Int =
    value.sliding(needle.length).count(_ == needle)
}
