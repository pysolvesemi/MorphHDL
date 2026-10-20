// Generator : SpinalHDL dev    git head : b6e83061d8c2b1ece94e76abadc956a2201a99dc
// Component : SymbolicValueProbe
// Git hash  : b6e83061d8c2b1ece94e76abadc956a2201a99dc

`timescale 1ns/1ps 
module SymbolicValueProbe #(
  parameter integer BUS_LOG2_BYTES = 3
) (
  output wire [7:0]    io_value,
  output wire [8:0]    io_plusThree
);

  wire       [7:0]    morphhdl_typed_value_1;
  wire       [8:0]    morphhdl_typed_value_2;

  assign morphhdl_typed_value_1 = ((1 << (BUS_LOG2_BYTES)));
  assign io_value = morphhdl_typed_value_1;
  assign morphhdl_typed_value_2 = (((1 << (BUS_LOG2_BYTES)) + 3));
  assign io_plusThree = morphhdl_typed_value_2;

endmodule
