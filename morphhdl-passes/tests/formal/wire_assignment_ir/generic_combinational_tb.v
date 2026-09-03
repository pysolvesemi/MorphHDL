`timescale 1ns/1ps

module Wa03CombinationalTb;
  parameter WIDTH = 4;

  reg  [WIDTH-1:0] source;
  wire [WIDTH-1:0] reference_sink;
  wire [WIDTH-1:0] candidate_sink;
  integer value;

  Wa03CombinationalReference #(.WIDTH(WIDTH)) reference_dut (
    .source(source),
    .sink(reference_sink)
  );

  Wa03CombinationalCandidate #(.WIDTH(WIDTH)) candidate_dut (
    .source(source),
    .sink(candidate_sink)
  );

  initial begin
    source = {WIDTH{1'b0}};
    for (value = 0; value < (1 << WIDTH); value = value + 1) begin
      source = value;
      #1;
      if (reference_sink !== candidate_sink) begin
        $display("WA03_SIM_FAIL combinational WIDTH=%0d input=%0d reference=%0h candidate=%0h",
                 WIDTH, value, reference_sink, candidate_sink);
        $finish;
      end
    end
    $display("WA03_SIM_PASS combinational WIDTH=%0d", WIDTH);
    $finish;
  end
endmodule
