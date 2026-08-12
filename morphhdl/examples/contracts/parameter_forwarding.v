module ForwardingLeaf #(
  parameter integer WIDTH = 1
) (
  input  wire [WIDTH-1:0] din,
  output wire [WIDTH-1:0] dout
);

  assign dout = din;

endmodule

module ParameterForwarding #(
  parameter integer DATA_WIDTH = 8,
  parameter integer LANES = 4
) (
  input  wire [TOTAL_WIDTH-1:0] din,
  output wire [TOTAL_WIDTH-1:0] dout
);

  localparam integer TOTAL_WIDTH = LANES * DATA_WIDTH;

  ForwardingLeaf #(
    .WIDTH(TOTAL_WIDTH)
  ) forwarded_inst (
    .din(din),
    .dout(dout)
  );

endmodule
