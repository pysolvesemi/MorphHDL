module DerivedWidth #(
  parameter integer DATA_WIDTH = 8,
  parameter integer LANES = 4
) (
  input  wire [PADDED_WIDTH-1:0] din,
  output wire [PADDED_WIDTH-1:0] dout
);

  function integer clog2;
    input integer value;
    input integer minimum_result;
    integer remaining;
    begin
      clog2 = 0;
      for (remaining = value - 1; remaining > 0; remaining = remaining >> 1) begin
        clog2 = clog2 + 1;
      end
      if (clog2 < minimum_result) begin
        clog2 = minimum_result;
      end
    end
  endfunction

  localparam integer CLAMPED_PADDING = (DATA_WIDTH < 3) ? DATA_WIDTH : 3;
  localparam integer LANE_INDEX_WIDTH = clog2(LANES, 0);
  localparam integer TOTAL_WIDTH = LANES * DATA_WIDTH;
  localparam integer PADDED_WIDTH = ((TOTAL_WIDTH + CLAMPED_PADDING > 4) ? TOTAL_WIDTH + CLAMPED_PADDING : 4) + LANE_INDEX_WIDTH;

  assign dout = din;

endmodule
