package morphhdl.backend.verilog2001

import morphhdl.frontend._
import morphhdl.frontend.ParamRtlFrontend._
import morphhdl.paramrtl.PortDirection.{Input, Output}
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
    val dataWidth = HdlInt.param("DATA_WIDTH", default = 8, min = 1, max = 1024)
    val lanes = HdlInt.param("LANES", default = 4, min = 1, max = 64)
    val lanePacked = packedBits(dataWidth)
    val arrayPacked = packedBits(lanes * dataWidth)
    val leafItems = captureItems {
      emitContinuousAssign("data_out", ref("data_in"))
    }
    val leaf = moduleDef(
      name = "PixelLane",
      parameters = Vector(integerParameter(dataWidth)),
      ports = ordered(
        Vector(
          port("data_in", Input, lanePacked),
          port("data_out", Output, lanePacked)
        ),
        reverseConstructionOrder
      ),
      items = leafItems
    )
    val generatedItems = captureItems {
      for (lane <- (0 until lanes).named(label = "g_lane", index = "lane")) {
        val sliceOffset = lane * dataWidth
        emitInstance(
          name = "lane_inst",
          moduleName = "PixelLane",
          parameterBindings = ordered(
            Vector(parameterBinding("DATA_WIDTH", dataWidth)),
            reverseConstructionOrder
          ),
          portConnections = ordered(
            Vector(
              portConnection("data_in", indexedPartSelect("data_in", sliceOffset, dataWidth)),
              portConnection("data_out", indexedPartSelect("data_out", sliceOffset, dataWidth))
            ),
            reverseConstructionOrder
          )
        )
      }
    }
    val top = moduleDef(
      name = "LaneArray",
      parameters = ordered(
        Vector(integerParameter(dataWidth), integerParameter(lanes)),
        reverseConstructionOrder
      ),
      ports = ordered(
        Vector(
          port("data_in", Input, arrayPacked),
          port("data_out", Output, arrayPacked)
        ),
        reverseConstructionOrder
      ),
      items = generatedItems
    )

    Design(
      top = top.name,
      modules = ordered(Vector(top, leaf), reverseConstructionOrder)
    )
  }

  private def ordered[A](values: Vector[A], reverse: Boolean): Vector[A] =
    if (reverse) values.reverse else values
}
