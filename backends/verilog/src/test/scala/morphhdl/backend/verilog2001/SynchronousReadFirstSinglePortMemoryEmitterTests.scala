package morphhdl.backend.verilog2001

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{Add, Literal, ParameterRef}
import morphhdl.paramrtl.ModuleItem.SynchronousReadFirstSinglePortMemory
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.{Signed, Unsigned}
import morphhdl.paramrtl._
import org.scalatest.funsuite.AnyFunSuite

class SynchronousReadFirstSinglePortMemoryEmitterTests extends AnyFunSuite {
  test("emits the canonical guarded synchronous read-first single-port memory") {
    val expected =
      """module SinglePortMemory #(
        |  parameter integer DEPTH = 3,
        |  parameter integer WIDTH = 8
        |) (
        |  input  wire [2:0] address,
        |  input  wire [0:0] clk,
        |  output reg [WIDTH-1:0] read_data,
        |  input  wire [WIDTH-1:0] write_data,
        |  input  wire [0:0] write_enable
        |);
        |
        |  reg [WIDTH-1:0] memory [0:DEPTH-1];
        |
        |  always @(posedge clk) begin : p_memory
        |    if (address < DEPTH) begin
        |      read_data <= memory[address];
        |      if (write_enable == 1'b1) begin
        |        memory[address] <= write_data;
        |      end
        |    end else begin
        |      read_data <= {WIDTH{1'b0}};
        |    end
        |  end
        |
        |endmodule
        |""".stripMargin

    assert(emit(memoryDesign()) == expected)
  }

  test("preserves signed element types in ports and memory storage") {
    val verilog = emit(memoryDesign(signed = true))

    assert(verilog.contains("input  wire signed [WIDTH-1:0] write_data"), verilog)
    assert(verilog.contains("output reg signed [WIDTH-1:0] read_data"), verilog)
    assert(verilog.contains("reg signed [WIDTH-1:0] memory [0:DEPTH-1];"), verilog)
  }

  test("parenthesizes compound element widths and depths without changing semantics") {
    val top = memoryDesign().modules.head
    val memory = top.items.head.asInstanceOf[SynchronousReadFirstSinglePortMemory]
    val width = Add(ParameterRef("WIDTH"), Literal(1))
    val depth = Add(ParameterRef("DEPTH"), Literal(1))
    val elementType = PackedBits(width, Unsigned)
    val rewritten = top.copy(
      ports = top.ports.map {
        case port if port.name == "write_data" || port.name == "read_data" =>
          port.copy(dataType = elementType)
        case port => port
      },
      items = Vector(memory.copy(elementType = elementType, depth = depth))
    )
    val verilog = emit(Design(rewritten.name, Vector(rewritten)))

    assert(verilog.contains("reg [(WIDTH + 1)-1:0] memory [0:(DEPTH + 1)-1];"), verilog)
    assert(verilog.contains("if (address < DEPTH + 1) begin"), verilog)
    assert(verilog.contains("read_data <= {(WIDTH + 1){1'b0}};"), verilog)
  }

  test("sorts ports deterministically without changing memory roles") {
    assert(emit(memoryDesign()) == emit(memoryDesign(reversePorts = true)))
  }

  test("rejects reserved memory and process names before emission") {
    assertDiagnostic(memoryDesign(memoryName = "reg"), "V2001-RESERVED-IDENTIFIER")
    assertDiagnostic(memoryDesign(label = "always"), "V2001-RESERVED-IDENTIFIER")
  }

  test("retains portable signed-32 checks for the memory depth expression") {
    val outside = BigInt(Int.MaxValue) + 1
    val invalid = memoryDesign(
      addressWidth = Literal(32),
      parameters = Vector(
        IntegerParameter("WIDTH", 8, Vector(MinInclusive(1), MaxInclusive(31))),
        IntegerParameter("DEPTH", 3, Vector(MinInclusive(1), MaxInclusive(outside)))
      )
    )

    assertDiagnostic(invalid, "V2001-INTEGER-DOMAIN-OUT-OF-RANGE")
  }

  private def memoryDesign(
      label: String = "p_memory",
      memoryName: String = "memory",
      signed: Boolean = false,
      reversePorts: Boolean = false,
      addressWidth: IntExpr = Literal(3),
      parameters: Vector[IntegerParameter] = Vector(
        IntegerParameter("WIDTH", 8, Vector(MinInclusive(1), MaxInclusive(31))),
        IntegerParameter("DEPTH", 3, Vector(MinInclusive(1), MaxInclusive(5)))
      )
  ): Design = {
    val elementType = PackedBits(ParameterRef("WIDTH"), if (signed) Signed else Unsigned)
    val ports = Vector(
      Port("clk", Input, PackedBits(Literal(1), Unsigned)),
      Port("write_enable", Input, PackedBits(Literal(1), Unsigned)),
      Port("address", Input, PackedBits(addressWidth, Unsigned)),
      Port("write_data", Input, elementType),
      Port("read_data", Output, elementType)
    )
    val top = ModuleDef(
      "SinglePortMemory",
      parameters,
      if (reversePorts) ports.reverse else ports,
      Vector(SynchronousReadFirstSinglePortMemory(
        label,
        memoryName,
        Ref("clk"),
        Ref("write_enable"),
        Ref("address"),
        Ref("write_data"),
        Ref("read_data"),
        elementType,
        ParameterRef("DEPTH")
      ))
    )
    Design(top.name, Vector(top))
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
