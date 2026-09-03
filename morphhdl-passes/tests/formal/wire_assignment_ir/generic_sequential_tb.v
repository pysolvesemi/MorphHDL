`timescale 1ns/1ps

module Wa03SequentialTb;
  parameter WIDTH = 4;

  reg              clk;
  reg              reset;
  reg              enable;
  reg  [WIDTH-1:0] source;
  wire [WIDTH-1:0] reference_sink;
  wire [WIDTH-1:0] candidate_sink;
  reg  [WIDTH-1:0] expected;

  Wa03SequentialReference #(.WIDTH(WIDTH)) reference_dut (
    .clk(clk),
    .reset(reset),
    .enable(enable),
    .source(source),
    .sink(reference_sink)
  );

  Wa03SequentialCandidate #(.WIDTH(WIDTH)) candidate_dut (
    .clk(clk),
    .reset(reset),
    .enable(enable),
    .source(source),
    .sink(candidate_sink)
  );

  always #5 clk = ~clk;

  task check_outputs;
    begin
      #1;
      if (reference_sink !== candidate_sink) begin
        $display("WA03_SIM_FAIL sequential WIDTH=%0d reference=%0h candidate=%0h",
                 WIDTH, reference_sink, candidate_sink);
        $finish;
      end
      if (reference_sink !== expected) begin
        $display("WA03_SIM_FAIL sequential WIDTH=%0d expected=%0h observed=%0h",
                 WIDTH, expected, reference_sink);
        $finish;
      end
    end
  endtask

  initial begin
    clk = 1'b0;
    reset = 1'b1;
    enable = 1'b0;
    source = {WIDTH{1'b0}};
    expected = {WIDTH{1'b0}};

    @(posedge clk);
    check_outputs;

    reset = 1'b0;
    enable = 1'b1;
    source = {WIDTH{1'b1}};
    expected = {WIDTH{1'b1}};
    @(posedge clk);
    check_outputs;

    enable = 1'b0;
    source = {WIDTH{1'b0}};
    @(posedge clk);
    check_outputs;

    enable = 1'b1;
    expected = {WIDTH{1'b0}};
    @(posedge clk);
    check_outputs;

    reset = 1'b1;
    enable = 1'b0;
    expected = {WIDTH{1'b0}};
    @(posedge clk);
    check_outputs;

    $display("WA03_SIM_PASS sequential WIDTH=%0d", WIDTH);
    $finish;
  end
endmodule
