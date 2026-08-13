module BooleanLocalHighRoute (
  input  wire [7:0] high_in,
  output wire [7:0] high_out
);

  assign high_out = high_in;

endmodule

module BooleanLocalLowRoute (
  input  wire [7:0] low_in,
  output wire [7:0] low_out
);

  assign low_out = low_in;

endmodule

module BooleanLocalRoute #(
  parameter integer SELECT = 0
) (
  output wire [7:0] dout,
  input  wire [7:0] high_in,
  input  wire [7:0] low_in
);

  generate
    if (SELECT == 1) begin : g_high
      BooleanLocalHighRoute selected_inst (
        .high_in(high_in),
        .high_out(dout)
      );
    end else begin : g_low
      BooleanLocalLowRoute selected_inst (
        .low_in(low_in),
        .low_out(dout)
      );
    end
  endgenerate

endmodule

module BooleanLocalRouting #(
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
  localparam integer ROUTE_HIGH = (ENABLE == 1 && EFFECTIVE_WIDTH >= LIMIT) ? 1 : 0;

  generate
    if (ROUTE_HIGH == 1) begin : g_local_high
      BooleanLocalRoute #(
        .SELECT((ROUTE_HIGH == 1) ? 1 : 0)
      ) route_inst (
        .dout(dout),
        .high_in(high_in),
        .low_in(low_in)
      );
    end else begin : g_local_low
      BooleanLocalRoute #(
        .SELECT((ROUTE_HIGH == 1) ? 1 : 0)
      ) route_inst (
        .dout(dout),
        .high_in(high_in),
        .low_in(low_in)
      );
    end
  endgenerate

endmodule
