// Generator : SpinalHDL dev    git head : d2154190f9a739ae145f304474d2f6e4f28cc600
// Component : SymbolicResizeProbe
// Git hash  : d2154190f9a739ae145f304474d2f6e4f28cc600

`timescale 1ns/1ps 
module SymbolicResizeProbe #(
  parameter integer WIDTH = 8
) (
  input  wire [WIDTH-1:0]    keep,
  output wire [(WIDTH + 1)-1:0]    wide
);

  wire       [(WIDTH + 1)-1:0]    _zz_wide;

  assign _zz_wide = {{((((WIDTH + 1)) > (WIDTH)) ? (((WIDTH + 1)) - (WIDTH)) : 0){1'b0}}, keep[((((WIDTH + 1)) < (WIDTH)) ? ((WIDTH + 1)) : (WIDTH))-1:0]};
  assign wide = _zz_wide;

endmodule
