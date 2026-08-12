module DerivedWidth #(
  parameter integer DATA_WIDTH = 8,
  parameter integer LANES = 4
) (
  input  wire [PADDED_WIDTH-1:0] din,
  output wire [PADDED_WIDTH-1:0] dout
);

  localparam integer TOTAL_WIDTH = LANES * DATA_WIDTH;
  localparam integer PADDED_WIDTH = TOTAL_WIDTH + 3;

  assign dout = din;

endmodule
