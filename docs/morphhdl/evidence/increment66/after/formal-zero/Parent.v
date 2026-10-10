// Generator : SpinalHDL dev    git head : b6e83061d8c2b1ece94e76abadc956a2201a99dc
// Component : Parent
// Git hash  : b6e83061d8c2b1ece94e76abadc956a2201a99dc

`timescale 1ns/1ps 
module Parent #(
  parameter integer PARENT_PPC4 = 0
) (
  input  wire [((PARENT_PPC4 * 3) + 1)-1:0]    din,
  output wire [((PARENT_PPC4 * 3) + 1)-1:0]    dout
);

  wire       [((PARENT_PPC4 * 3) + 1)-1:0]    scalarChild_1_dout;

  ScalarChild #(
    .PPC4(PARENT_PPC4)
  ) scalarChild_1 (
    .din  (din               [((PARENT_PPC4 * 3) + 1)-1:0]), //i
    .dout (scalarChild_1_dout[((PARENT_PPC4 * 3) + 1)-1:0])  //o
  );
  assign dout = scalarChild_1_dout;

`ifndef SYNTHESIS
  generate
    if ((PARENT_PPC4 < 0) || (PARENT_PPC4 > 1) || (^PARENT_PPC4 === 1'bx)) begin : G_PARAMETER_DOMAIN_PARENT_PPC4
      initial $fatal(1, "%s", "PARENT_PPC4 must be in 0..1");
    end
  endgenerate
`endif

endmodule
