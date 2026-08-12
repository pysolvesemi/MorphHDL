package morphhdl.backend.verilog2001

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{GenerateIndexRef, Multiply, ParameterRef}
import morphhdl.paramrtl.ModuleItem.{ContinuousAssign, GenerateFor, ModuleInstance}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.{IndexedPartSelect, Ref}
import morphhdl.paramrtl.Signedness.Unsigned
import morphhdl.paramrtl._

object LaneArrayFixture {
  val expected: String =
    """module PixelLane #(
      |  parameter integer DATA_WIDTH = 8
      |) (
      |  input  wire [DATA_WIDTH-1:0] data_in,
      |  output wire [DATA_WIDTH-1:0] data_out
      |);
      |
      |  assign data_out = data_in;
      |
      |endmodule
      |
      |module LaneArray #(
      |  parameter integer DATA_WIDTH = 8,
      |  parameter integer LANES = 4
      |) (
      |  input  wire [(LANES * DATA_WIDTH)-1:0] data_in,
      |  output wire [(LANES * DATA_WIDTH)-1:0] data_out
      |);
      |
      |  genvar lane;
      |  generate
      |    for (lane = 0; lane < LANES; lane = lane + 1) begin : g_lane
      |      PixelLane #(
      |        .DATA_WIDTH(DATA_WIDTH)
      |      ) lane_inst (
      |        .data_in(data_in[lane * DATA_WIDTH +: DATA_WIDTH]),
      |        .data_out(data_out[lane * DATA_WIDTH +: DATA_WIDTH])
      |      );
      |    end
      |  endgenerate
      |
      |endmodule
      |""".stripMargin

  def design(reverseConstructionOrder: Boolean = false): Design = {
    val dataWidth = IntegerParameter(
      "DATA_WIDTH",
      8,
      Vector(MinInclusive(1), MaxInclusive(1024))
    )
    val lanes = IntegerParameter(
      "LANES",
      4,
      Vector(MinInclusive(1), MaxInclusive(64))
    )
    val lanePacked = PackedBits(ParameterRef("DATA_WIDTH"), Unsigned)
    val arrayPacked = PackedBits(Multiply(ParameterRef("LANES"), ParameterRef("DATA_WIDTH")), Unsigned)
    val sliceOffset = Multiply(GenerateIndexRef("lane"), ParameterRef("DATA_WIDTH"))
    val laneInstance = ModuleInstance(
      name = "lane_inst",
      moduleName = "PixelLane",
      parameterBindings = Vector(ParameterBinding("DATA_WIDTH", ParameterRef("DATA_WIDTH"))),
      portConnections = Vector(
        PortConnection(
          "data_in",
          IndexedPartSelect(Ref("data_in"), sliceOffset, ParameterRef("DATA_WIDTH"))
        ),
        PortConnection(
          "data_out",
          IndexedPartSelect(Ref("data_out"), sliceOffset, ParameterRef("DATA_WIDTH"))
        )
      )
    )
    val leaf = ModuleDef(
      name = "PixelLane",
      parameters = Vector(dataWidth),
      ports = ordered(
        Vector(
          Port("data_in", Input, lanePacked),
          Port("data_out", Output, lanePacked)
        ),
        reverseConstructionOrder
      ),
      items = Vector(ContinuousAssign(Ref("data_out"), Ref("data_in")))
    )
    val generate = GenerateFor(
      label = "g_lane",
      indexName = "lane",
      count = ParameterRef("LANES"),
      body = Vector(
        laneInstance.copy(
          parameterBindings = ordered(laneInstance.parameterBindings, reverseConstructionOrder),
          portConnections = ordered(laneInstance.portConnections, reverseConstructionOrder)
        )
      )
    )
    val top = ModuleDef(
      name = "LaneArray",
      parameters = ordered(Vector(dataWidth, lanes), reverseConstructionOrder),
      ports = ordered(
        Vector(
          Port("data_in", Input, arrayPacked),
          Port("data_out", Output, arrayPacked)
        ),
        reverseConstructionOrder
      ),
      items = Vector(generate)
    )

    Design(
      top = top.name,
      modules = ordered(Vector(top, leaf), reverseConstructionOrder)
    )
  }

  private def ordered[A](values: Vector[A], reverse: Boolean): Vector[A] =
    if (reverse) values.reverse else values
}
