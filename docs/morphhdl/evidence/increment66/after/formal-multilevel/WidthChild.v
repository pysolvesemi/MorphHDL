// Generator : SpinalHDL dev    git head : b6e83061d8c2b1ece94e76abadc956a2201a99dc
// Component : WidthChild
// Git hash  : b6e83061d8c2b1ece94e76abadc956a2201a99dc

`timescale 1ns/1ps 
module WidthChild #(
  parameter integer WIDTH = 64
) (
  input  wire [WIDTH-1:0] din,
  output wire [WIDTH-1:0] dout
);

  assign dout = din;

`ifndef SYNTHESIS
  generate
    if ((WIDTH < 1) || (WIDTH > 256) || (^WIDTH === 1'bx)) begin : G_PARAMETER_DOMAIN_WIDTH
      initial $fatal(1, "%s", "WIDTH must be in 1..256");
    end
  endgenerate
`endif

endmodule
