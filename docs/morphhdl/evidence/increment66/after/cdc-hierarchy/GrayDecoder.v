// Generator : SpinalHDL dev    git head : b6e83061d8c2b1ece94e76abadc956a2201a99dc
// Component : GrayDecoder
// Git hash  : b6e83061d8c2b1ece94e76abadc956a2201a99dc

`timescale 1ns/1ps 
module GrayDecoder #(
  parameter integer WIDTH = 5
) (
  input  wire [WIDTH-1:0]    gray,
  output wire [WIDTH-1:0]    binary
);

  wire       [WIDTH-1:0]    _zz_binary;
  wire       [WIDTH-1:0]    _zz_binary_1;
  wire       [WIDTH-1:0]    _zz_binary_2;
  wire       [WIDTH-1:0]    _zz_binary_3;
  wire       [WIDTH-1:0]    _zz_binary_4;
  wire       [WIDTH-1:0]    _zz_binary_5;
  wire       [WIDTH-1:0]    _zz_binary_6;
  wire       [WIDTH-1:0]    _zz_binary_7;
  wire       [WIDTH-1:0]    _zz_binary_8;
  wire       [WIDTH-1:0]    _zz_binary_9;
  wire       [WIDTH-1:0]    _zz_binary_10;

  assign _zz_binary = gray;
  assign _zz_binary_1 = (_zz_binary >>> 1);
  assign _zz_binary_2 = (_zz_binary ^ _zz_binary_1);
  assign _zz_binary_3 = (_zz_binary_2 >>> 2);
  assign _zz_binary_4 = (_zz_binary_2 ^ _zz_binary_3);
  assign _zz_binary_5 = (_zz_binary_4 >>> 4);
  assign _zz_binary_6 = (_zz_binary_4 ^ _zz_binary_5);
  assign _zz_binary_7 = (_zz_binary_6 >>> 8);
  assign _zz_binary_8 = (_zz_binary_6 ^ _zz_binary_7);
  assign _zz_binary_9 = (_zz_binary_8 >>> 16);
  assign _zz_binary_10 = (_zz_binary_8 ^ _zz_binary_9);
  assign binary = _zz_binary_10;

`ifndef SYNTHESIS
  generate
    if ((WIDTH < 1) || (WIDTH > 18) || (^WIDTH === 1'bx)) begin : G_PARAMETER_DOMAIN_WIDTH
      initial $fatal(1, "%s", "WIDTH must be in 1..18");
    end
  endgenerate
`endif

endmodule
