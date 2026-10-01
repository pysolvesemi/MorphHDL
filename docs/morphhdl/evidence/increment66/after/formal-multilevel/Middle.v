// Generator : SpinalHDL dev    git head : b6e83061d8c2b1ece94e76abadc956a2201a99dc
// Component : Middle
// Git hash  : b6e83061d8c2b1ece94e76abadc956a2201a99dc

`timescale 1ns/1ps 
module Middle #(
  parameter integer MIDDLE_WIDTH = 64
) (
  input  wire [MIDDLE_WIDTH-1:0]   din,
  output wire [MIDDLE_WIDTH-1:0]   dout
);

  wire       [MIDDLE_WIDTH-1:0]   child_dout;

  WidthChild #(
    .WIDTH(MIDDLE_WIDTH)
  ) child (
    .din  (din[MIDDLE_WIDTH-1:0]       ), //i
    .dout (child_dout[MIDDLE_WIDTH-1:0])  //o
  );
  assign dout = child_dout;

`ifndef SYNTHESIS
  generate
    if ((MIDDLE_WIDTH < 1) || (MIDDLE_WIDTH > 256) || (^MIDDLE_WIDTH === 1'bx)) begin : G_PARAMETER_DOMAIN_MIDDLE_WIDTH
      initial $fatal(1, "%s", "MIDDLE_WIDTH must be in 1..256");
    end
  endgenerate
`endif

endmodule
