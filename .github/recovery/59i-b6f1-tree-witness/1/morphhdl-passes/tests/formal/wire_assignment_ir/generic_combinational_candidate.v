module Wa03CombinationalCandidate #(
  parameter WIDTH = 4
) (
  input  wire [WIDTH-1:0] source,
  output wire [WIDTH-1:0] sink
);
  assign sink = source;
endmodule
