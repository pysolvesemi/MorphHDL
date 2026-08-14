package morphhdl.backend.verilog2001

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{AddressWidth, Literal, ParameterRef}
import morphhdl.paramrtl.ModuleItem.SynchronousCounter
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.Unsigned
import morphhdl.paramrtl._
import org.scalatest.funsuite.AnyFunSuite

class SynchronousCounterEmitterTests extends AnyFunSuite {
  test("emits the canonical bounded synchronous counter") {
    val expected =
      """module ParameterizedCounter #(
        |  parameter integer LIMIT = 5
        |) (
        |  input  wire [0:0] clk,
        |  output reg [(morphhdl$ceil_log2(LIMIT, 1))-1:0] count,
        |  input  wire [0:0] enable,
        |  input  wire [0:0] reset
        |);
        |
        |  function integer morphhdl$ceil_log2;
        |    input integer value;
        |    input integer minimum_result;
        |    integer remaining;
        |    begin
        |      morphhdl$ceil_log2 = 0;
        |      for (remaining = value - 1; remaining > 0; remaining = remaining >> 1) begin
        |        morphhdl$ceil_log2 = morphhdl$ceil_log2 + 1;
        |      end
        |      if (morphhdl$ceil_log2 < minimum_result) begin
        |        morphhdl$ceil_log2 = minimum_result;
        |      end
        |    end
        |  endfunction
        |
        |  always @(posedge clk) begin : p_counter
        |    if (reset == 1'b1) begin
        |      count <= {morphhdl$ceil_log2(LIMIT, 1){1'b0}};
        |    end else if (enable == 1'b1) begin
        |      if (count == LIMIT - 1) begin
        |        count <= {morphhdl$ceil_log2(LIMIT, 1){1'b0}};
        |      end else begin
        |        count <= count + 1'b1;
        |      end
        |    end
        |  end
        |
        |endmodule
        |""".stripMargin

    val actual = emit(counterDesign())
    assert(actual == expected, actual)
    assert(!expected.contains("$clog2"))
  }

  test("keeps LIMIT one legal with a one-bit count and identical rollover logic") {
    val verilog = emit(counterDesign(defaultLimit = 1, maximumLimit = 1))
    assert(verilog.contains("parameter integer LIMIT = 1"), verilog)
    assert(verilog.contains("morphhdl$ceil_log2(LIMIT, 1)"), verilog)
    assert(verilog.contains("if (count == LIMIT - 1) begin"), verilog)
  }

  test("sorts ports deterministically without changing counter roles") {
    assert(emit(counterDesign()) == emit(counterDesign(reversePorts = true)))
  }

  test("rejects reserved process names before emission") {
    assertDiagnostic(counterDesign(label = "always"), "V2001-RESERVED-IDENTIFIER")
  }

  test("retains portable signed-32 checks for the counter limit domain") {
    val outside = BigInt(Int.MaxValue) + 1
    assertDiagnostic(
      counterDesign(maximumLimit = outside),
      "V2001-INTEGER-DOMAIN-OUT-OF-RANGE"
    )
  }

  private def counterDesign(
      label: String = "p_counter",
      defaultLimit: BigInt = 5,
      maximumLimit: BigInt = 8,
      reversePorts: Boolean = false
  ): Design = {
    val limit = ParameterRef("LIMIT")
    val ports = Vector(
      Port("clk", Input, PackedBits(Literal(1), Unsigned)),
      Port("reset", Input, PackedBits(Literal(1), Unsigned)),
      Port("enable", Input, PackedBits(Literal(1), Unsigned)),
      Port("count", Output, PackedBits(AddressWidth(limit), Unsigned))
    )
    val module = ModuleDef(
      "ParameterizedCounter",
      Vector(IntegerParameter(
        "LIMIT",
        defaultLimit,
        Vector(MinInclusive(1), MaxInclusive(maximumLimit))
      )),
      if (reversePorts) ports.reverse else ports,
      Vector(SynchronousCounter(
        label,
        Ref("clk"),
        Ref("reset"),
        Ref("enable"),
        Ref("count"),
        limit
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
}
