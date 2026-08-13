module BooleanHighRoute (
  input  wire [7:0] high_in,
  output wire [7:0] high_out
);

  assign high_out = high_in;

endmodule

module BooleanLowRoute (
  input  wire [7:0] low_in,
  output wire [7:0] low_out
);

  assign low_out = low_in;

endmodule

module BooleanRoute #(
  parameter integer SELECT = 0
) (
  output wire [7:0] dout,
  input  wire [7:0] high_in,
  input  wire [7:0] low_in
);

  generate
    if (SELECT == 1) begin : g_high
      BooleanHighRoute selected_inst (
        .high_in(high_in),
        .high_out(dout)
      );
    end else begin : g_low
      BooleanLowRoute selected_inst (
        .low_in(low_in),
        .low_out(dout)
      );
    end
  endgenerate

endmodule

module BooleanForwarding #(
  parameter integer ENABLE = 1,
  parameter integer LIMIT = 8,
  parameter integer OFFSET = 1,
  parameter integer WIDTH = 7
) (
  output wire [7:0] dout,
  input  wire [7:0] high_in,
  input  wire [7:0] low_in
);

  localparam integer EFFECTIVE_WIDTH = WIDTH + OFFSET;

  BooleanRoute #(
    .SELECT(ENABLE == 1 && EFFECTIVE_WIDTH >= LIMIT)
  ) route_inst (
    .dout(dout),
    .high_in(high_in),
    .low_in(low_in)
  );

endmodule
