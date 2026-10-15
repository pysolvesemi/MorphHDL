// Generator : SpinalHDL dev    git head : b6e83061d8c2b1ece94e76abadc956a2201a99dc
// Component : Parent
// Git hash  : b6e83061d8c2b1ece94e76abadc956a2201a99dc

`timescale 1ns/1ps 
module Parent #(
  parameter integer PARENT_LOG_BYTES = 3
) (
  input  wire [((1 << (PARENT_LOG_BYTES)) * 8)-1:0]   din,
  output wire [((1 << (PARENT_LOG_BYTES)) * 8)-1:0]   dout
);

  wire       [((1 << (PARENT_LOG_BYTES)) * 8)-1:0]   widthChild_1_dout;

  WidthChild #(
    .WIDTH(((1 << (PARENT_LOG_BYTES)) * 8))
  ) widthChild_1 (
    .din  (din[((1 << (PARENT_LOG_BYTES)) * 8)-1:0]              ), //i
    .dout (widthChild_1_dout[((1 << (PARENT_LOG_BYTES)) * 8)-1:0])  //o
  );
  assign dout = widthChild_1_dout;

`ifndef SYNTHESIS
  generate
    if ((PARENT_LOG_BYTES < 2) || (PARENT_LOG_BYTES > 5) || (^PARENT_LOG_BYTES === 1'bx)) begin : G_PARAMETER_DOMAIN_PARENT_LOG_BYTES
      initial $fatal(1, "%s", "PARENT_LOG_BYTES must be in 2..5");
    end
  endgenerate
`endif

endmodule
