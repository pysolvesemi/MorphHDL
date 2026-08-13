module ConditionalWidth #(
  parameter integer NARROW_WIDTH = 4,
  parameter integer WIDE = 1,
  parameter integer WIDE_WIDTH = 12
) (
  input  wire [ACTIVE_WIDTH-1:0] din,
  output wire [ACTIVE_WIDTH-1:0] dout
);

  localparam integer ACTIVE_WIDTH = (WIDE == 1) ? WIDE_WIDTH : NARROW_WIDTH;

  assign dout = din;

endmodule
