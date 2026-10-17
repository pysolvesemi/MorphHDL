// Generator : SpinalHDL dev    git head : b6e83061d8c2b1ece94e76abadc956a2201a99dc
// Component : ScalarChild
// Git hash  : b6e83061d8c2b1ece94e76abadc956a2201a99dc

`timescale 1ns/1ps 
module ScalarChild #(
  parameter integer BUS_LOG2_BYTES = 3
) (
  input  wire [((1 << (BUS_LOG2_BYTES)) * 8)-1:0] din,
  output wire [((1 << (BUS_LOG2_BYTES)) * 8)-1:0] dout
);

  assign dout = din;

endmodule
