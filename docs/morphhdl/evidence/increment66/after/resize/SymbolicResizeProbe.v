// Generator : SpinalHDL dev    git head : b6e83061d8c2b1ece94e76abadc956a2201a99dc
// Component : SymbolicResizeProbe
// Git hash  : b6e83061d8c2b1ece94e76abadc956a2201a99dc

`timescale 1ns/1ps 
module SymbolicResizeProbe #(
  parameter integer BUS_LOG2_BYTES = 3
) (
  input  wire [(((1 << (BUS_LOG2_BYTES)) * 8) / 8)-1:0]    keep,
  output wire [((((1 << (BUS_LOG2_BYTES)) * 8) / 8) + 1)-1:0]    wide
);

  wire       [((((1 << (BUS_LOG2_BYTES)) * 8) / 8) + 1)-1:0]    _zz_wide;

  assign _zz_wide = {{1{1'b0}}, keep};
  assign wide = _zz_wide;

endmodule
