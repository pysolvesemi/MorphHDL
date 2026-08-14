package morphhdl.backend.verilog2001

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{AddressWidth, Literal, ParameterRef}
import morphhdl.paramrtl.ModuleItem.SynchronousReadFirstSimpleDualPortMemory
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.{Signed, Unsigned}
import morphhdl.paramrtl._
import org.scalatest.funsuite.AnyFunSuite

class SynchronousReadFirstSimpleDualPortMemoryEmitterTests extends AnyFunSuite {
  test("emits the exact canonical strict Verilog-2001 simple dual-port memory") {
    val expected =
      """module SimpleDualPortMemory #(
        |  parameter integer DEPTH = 5,
        |  parameter integer WIDTH = 8
        |) (
        |  input  wire [0:0] clk,
        |  input  wire [(morphhdl$ceil_log2(DEPTH, 1))-1:0] read_address,
        |  output reg [WIDTH-1:0] read_data,
        |  input  wire [0:0] read_enable,
        |  input  wire [(morphhdl$ceil_log2(DEPTH, 1))-1:0] write_address,
        |  input  wire [WIDTH-1:0] write_data,
        |  input  wire [0:0] write_enable
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
        |  reg [WIDTH-1:0] memory [0:DEPTH-1];
        |
        |  always @(posedge clk) begin : p_memory
        |    if (read_address < DEPTH) begin
        |      if (read_enable == 1'b1) begin
        |        read_data <= memory[read_address];
        |      end
        |    end else if (read_enable == 1'b1) begin
        |      read_data <= {WIDTH{1'b0}};
        |    end
        |    if (write_address < DEPTH) begin
        |      if (write_enable == 1'b1) begin
        |        memory[write_address] <= write_data;
        |      end
        |    end
        |  end
        |
        |endmodule
        |""".stripMargin

    assert(emit(memoryDesign()) == expected)
  }

  test("keeps read and write independent in one read-first nonblocking process") {
    val verilog = emit(memoryDesign())
    assert(count(verilog, "always @(") == 1, verilog)
    assert(count(verilog, " <= ") == 3, verilog)
    assert(verilog.contains("read_data <= memory[read_address];"), verilog)
    assert(verilog.contains("memory[write_address] <= write_data;"), verilog)
    assert(verilog.indexOf("if (write_address < DEPTH)") > verilog.indexOf("end else if (read_enable"), verilog)
    assert(!verilog.contains("$clog2"), verilog)
    assert(!verilog.contains("initial"), verilog)
    assert(!verilog.contains("reset"), verilog)
  }

  test("emits one portable helper definition and two correlated address calls") {
    val verilog = emit(memoryDesign())
    assert(count(verilog, "function integer morphhdl$ceil_log2;") == 1, verilog)
    assert(count(verilog, "morphhdl$ceil_log2(DEPTH, 1)") == 2, verilog)
  }

  test("emits capacity-safe overwide addresses without the portable helper") {
    val verilog = emit(memoryDesign(addressWidth = Literal(4)))
    val canonicalProcess =
      """  always @(posedge clk) begin : p_memory
        |    if (read_address < DEPTH) begin
        |      if (read_enable == 1'b1) begin
        |        read_data <= memory[read_address];
        |      end
        |    end else if (read_enable == 1'b1) begin
        |      read_data <= {WIDTH{1'b0}};
        |    end
        |    if (write_address < DEPTH) begin
        |      if (write_enable == 1'b1) begin
        |        memory[write_address] <= write_data;
        |      end
        |    end
        |  end""".stripMargin

    assert(verilog.contains("input  wire [3:0] read_address"), verilog)
    assert(verilog.contains("input  wire [3:0] write_address"), verilog)
    assert(!verilog.contains("morphhdl$ceil_log2"), verilog)
    assert(verilog.contains(canonicalProcess), verilog)
  }

  test("preserves signed storage data types and deterministic port sorting") {
    val signed = emit(memoryDesign(signed = true))
    assert(signed.contains("input  wire signed [WIDTH-1:0] write_data"), signed)
    assert(signed.contains("output reg signed [WIDTH-1:0] read_data"), signed)
    assert(signed.contains("reg signed [WIDTH-1:0] memory [0:DEPTH-1];"), signed)
    assert(emit(memoryDesign()) == emit(memoryDesign(reversePorts = true)))
  }

  test("retains reserved-name and signed-32 capability checks") {
    assertDiagnostic(memoryDesign(memoryName = "reg"), "V2001-RESERVED-IDENTIFIER")
    assertDiagnostic(memoryDesign(label = "always"), "V2001-RESERVED-IDENTIFIER")

    val outside = BigInt(Int.MaxValue) + 1
    assertDiagnostic(
      memoryDesign(
        addressWidth = Literal(32),
        parameters = Vector(
          IntegerParameter("WIDTH", 8, Vector(MinInclusive(1), MaxInclusive(32))),
          IntegerParameter("DEPTH", 5, Vector(MinInclusive(1), MaxInclusive(outside)))
        )
      ),
      "V2001-INTEGER-DOMAIN-OUT-OF-RANGE"
    )
  }

  private def memoryDesign(
      label: String = "p_memory",
      memoryName: String = "memory",
      signed: Boolean = false,
      reversePorts: Boolean = false,
      addressWidth: IntExpr = AddressWidth(ParameterRef("DEPTH")),
      parameters: Vector[IntegerParameter] = Vector(
        IntegerParameter("WIDTH", 8, Vector(MinInclusive(1), MaxInclusive(32))),
        IntegerParameter("DEPTH", 5, Vector(MinInclusive(1), MaxInclusive(8)))
      )
  ): Design = {
    val elementType = PackedBits(ParameterRef("WIDTH"), if (signed) Signed else Unsigned)
    val ports = Vector(
      Port("clk", Input, PackedBits(Literal(1), Unsigned)),
      Port("read_enable", Input, PackedBits(Literal(1), Unsigned)),
      Port("write_enable", Input, PackedBits(Literal(1), Unsigned)),
      Port("read_address", Input, PackedBits(addressWidth, Unsigned)),
      Port("write_address", Input, PackedBits(addressWidth, Unsigned)),
      Port("write_data", Input, elementType),
      Port("read_data", Output, elementType)
    )
    val module = ModuleDef(
      "SimpleDualPortMemory",
      parameters,
      if (reversePorts) ports.reverse else ports,
      Vector(SynchronousReadFirstSimpleDualPortMemory(
        label,
        memoryName,
        Ref("clk"),
        Ref("read_enable"),
        Ref("write_enable"),
        Ref("read_address"),
        Ref("write_address"),
        Ref("write_data"),
        Ref("read_data"),
        elementType,
        ParameterRef("DEPTH")
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
