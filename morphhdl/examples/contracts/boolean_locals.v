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
  input  wire [7:0] din,
  output wire [7:0] dout
);

  generate
    if (SELECT == 1) begin : g_high
      BooleanLocalHighRoute selected_inst (
        .high_in(din),
        .high_out(dout)
      );
    end else begin : g_low
      BooleanLocalLowRoute selected_inst (
        .low_in(din),
        .low_out(dout)
      );
    end
  endgenerate

endmodule

module BooleanLocals #(
  parameter integer ENABLE = 1,
  parameter integer LIMIT = 8,
  parameter integer WIDTH = 8
) (
  input  wire [7:0] din,
  output wire [7:0] dout
);

  localparam integer EFFECTIVE_WIDTH = WIDTH;
  localparam integer WIDTH_OK = (EFFECTIVE_WIDTH >= LIMIT) ? 1 : 0;
  localparam integer ROUTE_HIGH = (ENABLE == 1 && WIDTH_OK == 1) ? 1 : 0;
  localparam integer ROUTE_CODE = (ROUTE_HIGH == 1) ? 1 : 0;

  BooleanLocalRoute #(
    .SELECT((ROUTE_CODE == 1) ? 1 : 0)
  ) route_inst (
    .din(din),
    .dout(dout)
  );

endmodule
