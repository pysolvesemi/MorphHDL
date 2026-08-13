module CaseDefaultRoute (
  input  wire [7:0] default_in,
  output wire [7:0] default_out
);

  assign default_out = default_in;

endmodule

module CaseOneRoute (
  input  wire [7:0] one_in,
  output wire [7:0] one_out
);

  assign one_out = one_in;

endmodule

module CaseZeroRoute (
  input  wire [7:0] zero_in,
  output wire [7:0] zero_out
);

  assign zero_out = zero_in;

endmodule

module CaseRouting #(
  parameter integer MODE = 0,
  parameter integer OFFSET = 0
) (
  input  wire [7:0] din,
  output wire [7:0] dout
);

  localparam integer SELECTOR = MODE + OFFSET;

  generate
    case (SELECTOR)
      0: begin : g_zero
        CaseZeroRoute selected_inst (
          .zero_in(din),
          .zero_out(dout)
        );
      end
      1: begin : g_one
        CaseOneRoute selected_inst (
          .one_in(din),
          .one_out(dout)
        );
      end
      default: begin : g_default
        CaseDefaultRoute selected_inst (
          .default_in(din),
          .default_out(dout)
        );
      end
    endcase
  endgenerate

endmodule
