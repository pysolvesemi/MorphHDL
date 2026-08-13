module HighRoute (
  input  wire [7:0] high_in,
  output wire [7:0] high_out
);

  assign high_out = high_in;

endmodule

module LowRoute (
  input  wire [7:0] low_in,
  output wire [7:0] low_out
);

  assign low_out = low_in;

endmodule

module ComparisonRouting #(
  parameter integer SELECT = 8,
  parameter integer THRESHOLD = 5
) (
  input  wire [7:0] din,
  output wire [7:0] dout
);

  generate
    if (SELECT >= THRESHOLD) begin : g_high
      HighRoute selected_inst (
        .high_in(din),
        .high_out(dout)
      );
    end else begin : g_low
      LowRoute selected_inst (
        .low_in(din),
        .low_out(dout)
      );
    end
  endgenerate

endmodule
