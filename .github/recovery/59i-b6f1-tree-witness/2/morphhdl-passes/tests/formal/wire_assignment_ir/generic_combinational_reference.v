module Wa03CombinationalReference #(
  parameter WIDTH = 4
) (
  input  wire [WIDTH-1:0] source,
  output wire [WIDTH-1:0] sink
);
  wire [WIDTH-1:0] alias_wire;

  assign alias_wire = source;
  assign sink = alias_wire;
endmodule
