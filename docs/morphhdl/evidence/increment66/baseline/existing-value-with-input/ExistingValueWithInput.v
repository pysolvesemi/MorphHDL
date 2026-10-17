// Generator : SpinalHDL dev    git head : d2154190f9a739ae145f304474d2f6e4f28cc600
// Component : ExistingValueWithInput
// Git hash  : d2154190f9a739ae145f304474d2f6e4f28cc600

`timescale 1ns/1ps 
module ExistingValueWithInput #(
  parameter integer BUS_LOG2_BYTES = 3
) (
  output wire [7:0]    value,
  input  wire          unrelated,
  output wire          passthrough
);

  wire       [7:0]    busBytesValue;

  assign busBytesValue = ((1 << (BUS_LOG2_BYTES)));
  assign value = busBytesValue;
  assign passthrough = unrelated;

endmodule
