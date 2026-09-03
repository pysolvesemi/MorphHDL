module Wa03SequentialReference #(
  parameter WIDTH = 4
) (
  input  wire             clk,
  input  wire             reset,
  input  wire             enable,
  input  wire [WIDTH-1:0] source,
  output wire [WIDTH-1:0] sink
);
  wire [WIDTH-1:0] alias_wire;
  reg  [WIDTH-1:0] state;

  assign alias_wire = source;

  always @(posedge clk) begin
    if (reset)
      state <= {WIDTH{1'b0}};
    else if (enable)
      state <= alias_wire;
  end

  assign sink = state;
endmodule
