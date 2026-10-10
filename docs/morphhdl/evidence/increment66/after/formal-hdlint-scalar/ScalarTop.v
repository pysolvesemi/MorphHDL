// Generator : SpinalHDL dev    git head : b6e83061d8c2b1ece94e76abadc956a2201a99dc
// Component : ScalarTop
// Git hash  : b6e83061d8c2b1ece94e76abadc956a2201a99dc

`timescale 1ns/1ps 
module ScalarTop #(
  parameter integer PARENT_LOG_BYTES = 3
) (
  input  wire [WIDTH-1:0] din,
  output wire [WIDTH-1:0] dout
);
  localparam integer WIDTH = ((1 << (PARENT_LOG_BYTES)) * 8);

  wire       [WIDTH-1:0]   child_dout;

  ScalarChild #(
    .BUS_LOG2_BYTES(PARENT_LOG_BYTES)
  ) child (
    .din  (din[WIDTH-1:0]       ), //i
    .dout (child_dout[WIDTH-1:0])  //o
  );
  assign dout = child_dout;

endmodule
