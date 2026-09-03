module Wa03SequentialCandidate #(
  parameter WIDTH = 4
) (
  input  wire             clk,
  input  wire             reset,
  input  wire             enable,
  input  wire [WIDTH-1:0] source,
  output wire [WIDTH-1:0] sink
);
  reg [WIDTH-1:0] state;

  always @(posedge clk) begin
    if (reset)
      state <= {WIDTH{1'b0}};
    else if (enable)
      state <= source;
  end

  assign sink = state;
endmodule
