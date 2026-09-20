module ConditionalLeaf #(
  parameter integer WIDTH = 1
) (
  input  wire [WIDTH-1:0] din,
  output wire [WIDTH-1:0] dout
);

  assign dout = din;

endmodule

module ConditionalForwarding #(
  parameter integer ENABLE = 1,
  parameter integer WIDTH = 8
) (
  input  wire [WIDTH-1:0] din,
  output wire [WIDTH-1:0] dout
);

  generate
    if (ENABLE == 1) begin : g_enabled
      ConditionalLeaf #(
        .WIDTH(WIDTH)
      ) selected_inst (
        .din(din),
        .dout(dout)
      );
    end else begin : g_disabled
      ConditionalLeaf #(
        .WIDTH(WIDTH)
      ) selected_inst (
        .din(din),
        .dout(dout)
      );
    end
  endgenerate

endmodule
