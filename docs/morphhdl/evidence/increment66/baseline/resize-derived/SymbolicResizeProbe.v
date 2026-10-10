// Generator : SpinalHDL dev    git head : d2154190f9a739ae145f304474d2f6e4f28cc600
// Component : SymbolicResizeProbe
// Git hash  : d2154190f9a739ae145f304474d2f6e4f28cc600

`timescale 1ns/1ps 
module SymbolicResizeProbe #(
  parameter integer BUS_LOG2_BYTES = 3
) (
  input  wire [(((1 << (BUS_LOG2_BYTES)) * 8) / 8)-1:0]    keep,
  output wire [((((1 << (BUS_LOG2_BYTES)) * 8) / 8) + 1)-1:0]    wide
);

  wire       [((((1 << (BUS_LOG2_BYTES)) * 8) / 8) + 1)-1:0]    _zz_wide;

  assign _zz_wide = {{(((((((1 << (BUS_LOG2_BYTES)) * 8) / 8) + 1)) > ((((1 << (BUS_LOG2_BYTES)) * 8) / 8))) ? ((((((1 << (BUS_LOG2_BYTES)) * 8) / 8) + 1)) - ((((1 << (BUS_LOG2_BYTES)) * 8) / 8))) : 0){1'b0}}, keep[(((((((1 << (BUS_LOG2_BYTES)) * 8) / 8) + 1)) < ((((1 << (BUS_LOG2_BYTES)) * 8) / 8))) ? (((((1 << (BUS_LOG2_BYTES)) * 8) / 8) + 1)) : ((((1 << (BUS_LOG2_BYTES)) * 8) / 8)))-1:0]};
  assign wide = _zz_wide;

endmodule
