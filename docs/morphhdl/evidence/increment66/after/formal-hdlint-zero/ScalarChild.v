// Generator : SpinalHDL dev    git head : b6e83061d8c2b1ece94e76abadc956a2201a99dc
// Component : ScalarChild
// Git hash  : b6e83061d8c2b1ece94e76abadc956a2201a99dc

`timescale 1ns/1ps 
module ScalarChild #(
  parameter integer PPC4 = 0
) (
  input  wire [((PPC4 * 3) + 1)-1:0] din,
  output wire [((PPC4 * 3) + 1)-1:0] dout
);

  assign dout = din;

endmodule
