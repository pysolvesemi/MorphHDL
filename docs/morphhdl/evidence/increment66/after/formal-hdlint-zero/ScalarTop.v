// Generator : SpinalHDL dev    git head : b6e83061d8c2b1ece94e76abadc956a2201a99dc
// Component : ScalarTop
// Git hash  : b6e83061d8c2b1ece94e76abadc956a2201a99dc

`timescale 1ns/1ps 
module ScalarTop #(
  parameter integer PARENT_PPC4 = 0
) (
  input  wire [WIDTH-1:0] din,
  output wire [WIDTH-1:0] dout
);
  localparam integer WIDTH = ((PARENT_PPC4 * 3) + 1);

  wire       [WIDTH-1:0]    child_dout;

  ScalarChild #(
    .PPC4(PARENT_PPC4)
  ) child (
    .din  (din       [WIDTH-1:0]), //i
    .dout (child_dout[WIDTH-1:0])  //o
  );
  assign dout = child_dout;

endmodule
