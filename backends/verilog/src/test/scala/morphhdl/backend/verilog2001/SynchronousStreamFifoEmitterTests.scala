package morphhdl.backend.verilog2001

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{Literal, ParameterRef}
import morphhdl.paramrtl.ModuleItem.SynchronousStreamFifo
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.{Signed, Unsigned}
import morphhdl.paramrtl._
import org.scalatest.funsuite.AnyFunSuite

class SynchronousStreamFifoEmitterTests extends AnyFunSuite {
  test("emits the exact canonical strict Verilog-2001 synchronous stream FIFO") {
    val expected =
      """module SynchronousStreamFifo #(
        |  parameter integer DEPTH = 5,
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
        |  function integer clog2;
        |    input integer value;
        |    input integer minimum_result;
        |    integer remaining;
        |    begin
        |      clog2 = 0;
        |      for (remaining = value - 1; remaining > 0; remaining = remaining >> 1) begin
        |        clog2 = clog2 + 1;
        |      end
        |      if (clog2 < minimum_result) begin
        |        clog2 = minimum_result;
        |      end
        |    end
        |  endfunction
        |
        |  localparam integer POINTER_WIDTH = clog2(DEPTH, 1);
        |  localparam integer OCCUPANCY_WIDTH = clog2(DEPTH + 1, 1);
        |
        |  reg [WIDTH-1:0] memory [0:DEPTH-1];
        |  reg [POINTER_WIDTH-1:0] read_pointer;
        |  reg [POINTER_WIDTH-1:0] write_pointer;
        |  reg [OCCUPANCY_WIDTH-1:0] occupancy;
        |  wire push_fire;
        |  wire pop_fire;
        |
        |  assign push_ready = occupancy < DEPTH;
        |  assign push_fire = push_valid && push_ready;
        |  assign pop_fire = pop_valid && pop_ready;
        |
        |  always @(posedge clk) begin : p_fifo
        |    if (reset == 1'b1) begin
        |      read_pointer <= {POINTER_WIDTH{1'b0}};
        |      write_pointer <= {POINTER_WIDTH{1'b0}};
        |      occupancy <= {OCCUPANCY_WIDTH{1'b0}};
        |      pop_valid <= 1'b0;
        |    end else begin
        |      if (push_fire == 1'b1) begin
        |        memory[write_pointer] <= push_data;
        |        if (write_pointer == DEPTH - 1) begin
        |          write_pointer <= {POINTER_WIDTH{1'b0}};
        |        end else begin
        |          write_pointer <= write_pointer + 1'b1;
        |        end
        |      end
        |      if (pop_valid == 1'b0) begin
        |        if (occupancy > 0) begin
        |          pop_data <= memory[read_pointer];
        |          pop_valid <= 1'b1;
        |          if (read_pointer == DEPTH - 1) begin
        |            read_pointer <= {POINTER_WIDTH{1'b0}};
        |          end else begin
        |            read_pointer <= read_pointer + 1'b1;
        |          end
        |        end
        |      end else if (pop_fire == 1'b1) begin
        |        if (occupancy > 1) begin
        |          pop_data <= memory[read_pointer];
        |          pop_valid <= 1'b1;
        |          if (read_pointer == DEPTH - 1) begin
        |            read_pointer <= {POINTER_WIDTH{1'b0}};
        |          end else begin
        |            read_pointer <= read_pointer + 1'b1;
        |          end
        |        end else begin
        |          pop_valid <= 1'b0;
        |        end
        |      end
        |      if (push_fire != pop_fire) begin
        |        if (push_fire == 1'b1) begin
        |          occupancy <= occupancy + 1'b1;
        |        end else begin
        |          occupancy <= occupancy - 1'b1;
        |        end
        |      end
        |    end
        |  end
        |
        |endmodule
        |""".stripMargin

    assert(emit(fifoDesign()) == expected)
  }

  test("preserves total-capacity handshake and synchronous no-bypass prefetch semantics") {
    val verilog = emit(fifoDesign())
    assert(verilog.contains("assign push_ready = occupancy < DEPTH;"), verilog)
    assert(verilog.contains("assign push_fire = push_valid && push_ready;"), verilog)
    assert(verilog.contains("assign pop_fire = pop_valid && pop_ready;"), verilog)
    assert(verilog.contains("if (pop_valid == 1'b0) begin\n        if (occupancy > 0) begin"), verilog)
    assert(verilog.contains("end else if (pop_fire == 1'b1) begin\n        if (occupancy > 1) begin"), verilog)
    assert(count(verilog, "pop_data <= memory[read_pointer];") == 2, verilog)
    assert(!verilog.contains("pop_data <= {"), verilog)
    assert(!verilog.contains("initial"), verilog)
    assert(!verilog.contains("$clog2"), verilog)
  }

  test("allocates every natural internal name deterministically on local collisions") {
    val collisions = Vector(
      "POINTER_WIDTH",
      "OCCUPANCY_WIDTH",
      "read_pointer",
      "write_pointer",
      "occupancy",
      "push_fire",
      "pop_fire"
    ).map(name => Port(name, Input, PackedBits(Literal(1), Unsigned)))
    val base = fifoDesign(extraPorts = collisions)
    val reversed = fifoDesign(extraPorts = collisions.reverse, reversePorts = true)
    val verilog = emit(base)

    assert(verilog == emit(reversed))
    assert(verilog.contains("localparam integer POINTER_WIDTH_1 = clog2(DEPTH, 1);"), verilog)
    assert(verilog.contains("localparam integer OCCUPANCY_WIDTH_1 = clog2(DEPTH + 1, 1);"), verilog)
    assert(verilog.contains("reg [POINTER_WIDTH_1-1:0] read_pointer_1;"), verilog)
    assert(verilog.contains("reg [POINTER_WIDTH_1-1:0] write_pointer_1;"), verilog)
    assert(verilog.contains("reg [OCCUPANCY_WIDTH_1-1:0] occupancy_1;"), verilog)
    assert(verilog.contains("wire push_fire_1;"), verilog)
    assert(verilog.contains("wire pop_fire_1;"), verilog)
  }

  test("preserves signed payload storage depth-one portability and port sorting") {
    val signed = emit(fifoDesign(signed = true))
    assert(signed.contains("input  wire signed [WIDTH-1:0] push_data"), signed)
    assert(signed.contains("output reg signed [WIDTH-1:0] pop_data"), signed)
    assert(signed.contains("reg signed [WIDTH-1:0] memory [0:DEPTH-1];"), signed)
    assert(emit(fifoDesign()) == emit(fifoDesign(reversePorts = true)))

    val depthOne = emit(fifoDesign(parameters = boundedParameters(depthDefault = 1, depthMaximum = 1)))
    assert(depthOne.contains("localparam integer POINTER_WIDTH = clog2(DEPTH, 1);"), depthOne)
    assert(depthOne.contains("assign push_ready = occupancy < DEPTH;"), depthOne)
  }

  test("retains reserved-name and occupancy signed-32 capability checks") {
    assertDiagnostic(fifoDesign(memoryName = "reg"), "V2001-RESERVED-IDENTIFIER")
    assertDiagnostic(fifoDesign(label = "always"), "V2001-RESERVED-IDENTIFIER")

    assertDiagnostic(
      fifoDesign(parameters = boundedParameters(depthMaximum = BigInt(Int.MaxValue))),
      "V2001-INTEGER-EXPRESSION-OUT-OF-RANGE"
    )
    assert(
      Verilog2001Emitter
        .emit(fifoDesign(parameters = boundedParameters(depthMaximum = BigInt(Int.MaxValue) - 1)))
        .isRight
    )
  }

  private def boundedParameters(
      depthDefault: BigInt = 5,
      depthMaximum: BigInt = 8
  ): Vector[IntegerParameter] =
    Vector(
      IntegerParameter("WIDTH", 8, Vector(MinInclusive(1), MaxInclusive(32))),
      IntegerParameter("DEPTH", depthDefault, Vector(MinInclusive(1), MaxInclusive(depthMaximum)))
    )

  private def fifoDesign(
      label: String = "p_fifo",
      memoryName: String = "memory",
      signed: Boolean = false,
      reversePorts: Boolean = false,
      extraPorts: Vector[Port] = Vector.empty,
      parameters: Vector[IntegerParameter] = boundedParameters()
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
    ) ++ extraPorts
    val module = ModuleDef(
      "SynchronousStreamFifo",
      parameters,
      if (reversePorts) ports.reverse else ports,
      Vector(SynchronousStreamFifo(
        label,
        memoryName,
        Ref("clk"),
        Ref("reset"),
        Ref("push_valid"),
        Ref("push_ready"),
        Ref("push_data"),
        Ref("pop_valid"),
        Ref("pop_ready"),
        Ref("pop_data"),
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
