module PixelLane #(
  parameter integer DATA_WIDTH = 8
) (
  input  wire [DATA_WIDTH-1:0] data_in,
  output wire [DATA_WIDTH-1:0] data_out
);

  assign data_out = data_in;

endmodule

module LaneArray #(
  parameter integer LANES = 4,
  parameter integer DATA_WIDTH = 8
) (
  input  wire [LANES*DATA_WIDTH-1:0] data_in,
  output wire [LANES*DATA_WIDTH-1:0] data_out
);

  genvar lane;
  generate
    for (lane = 0; lane < LANES; lane = lane + 1) begin : g_lane
      PixelLane #(
        .DATA_WIDTH(DATA_WIDTH)
      ) lane_inst (
        .data_in  (data_in[lane*DATA_WIDTH +: DATA_WIDTH]),
        .data_out (data_out[lane*DATA_WIDTH +: DATA_WIDTH])
      );
    end
  endgenerate

endmodule
