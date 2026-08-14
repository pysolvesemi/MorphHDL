module ParameterizedCounterTb;
  reg clk;
  reg reset;
  reg enable;

  wire [0:0] count_limit_one;
  wire [0:0] count_limit_two;
  wire [1:0] count_limit_three;
  wire [2:0] count_default;
  wire [2:0] count_limit_eight;

  reg [0:0] expected_limit_one;
  reg [0:0] expected_limit_two;
  reg [1:0] expected_limit_three;
  reg [2:0] expected_default;
  reg [2:0] expected_limit_eight;
  integer step;

  ParameterizedCounter #(
    .LIMIT(1)
  ) limit_one (
    .clk(clk),
    .count(count_limit_one),
    .enable(enable),
    .reset(reset)
  );

  ParameterizedCounter #(
    .LIMIT(2)
  ) limit_two (
    .clk(clk),
    .count(count_limit_two),
    .enable(enable),
    .reset(reset)
  );

  ParameterizedCounter #(
    .LIMIT(3)
  ) limit_three (
    .clk(clk),
    .count(count_limit_three),
    .enable(enable),
    .reset(reset)
  );

  ParameterizedCounter default_limit (
    .clk(clk),
    .count(count_default),
    .enable(enable),
    .reset(reset)
  );

  ParameterizedCounter #(
    .LIMIT(8)
  ) limit_eight (
    .clk(clk),
    .count(count_limit_eight),
    .enable(enable),
    .reset(reset)
  );

  initial begin
    clk = 1'b0;
    reset = 1'b1;
    enable = 1'b1;

    #1;
    clk = 1'b1;
    #1;
    if (count_limit_one !== 1'd0 ||
        count_limit_two !== 1'd0 ||
        count_limit_three !== 2'd0 ||
        count_default !== 3'd0 ||
        count_limit_eight !== 3'd0) begin
      $display("FAIL: ParameterizedCounter reset priority");
      $finish;
    end

    clk = 1'b0;
    reset = 1'b0;
    enable = 1'b0;
    #1;
    clk = 1'b1;
    #1;
    if (count_limit_one !== 1'd0 ||
        count_limit_two !== 1'd0 ||
        count_limit_three !== 2'd0 ||
        count_default !== 3'd0 ||
        count_limit_eight !== 3'd0) begin
      $display("FAIL: ParameterizedCounter disabled hold after reset");
      $finish;
    end

    clk = 1'b0;
    enable = 1'b1;
    for (step = 1; step <= 8; step = step + 1) begin
      expected_limit_one = 0;
      expected_limit_two = step % 2;
      expected_limit_three = step % 3;
      expected_default = step % 5;
      expected_limit_eight = step % 8;

      #1;
      clk = 1'b1;
      #1;
      if (count_limit_one !== expected_limit_one ||
          count_limit_two !== expected_limit_two ||
          count_limit_three !== expected_limit_three ||
          count_default !== expected_default ||
          count_limit_eight !== expected_limit_eight) begin
        $display("FAIL: ParameterizedCounter step %0d", step);
        $finish;
      end
      if (count_limit_one >= 1 ||
          count_limit_three >= 3 ||
          count_default >= 5) begin
        $display("FAIL: ParameterizedCounter out-of-range state at step %0d", step);
        $finish;
      end
      clk = 1'b0;
    end

    enable = 1'b0;
    #1;
    clk = 1'b1;
    #1;
    if (count_limit_one !== 1'd0 ||
        count_limit_two !== 1'd0 ||
        count_limit_three !== 2'd2 ||
        count_default !== 3'd3 ||
        count_limit_eight !== 3'd0) begin
      $display("FAIL: ParameterizedCounter later disabled hold");
      $finish;
    end

    clk = 1'b0;
    reset = 1'b1;
    enable = 1'b1;
    #1;
    if (count_limit_one !== 1'd0 ||
        count_limit_two !== 1'd0 ||
        count_limit_three !== 2'd2 ||
        count_default !== 3'd3 ||
        count_limit_eight !== 3'd0) begin
      $display("FAIL: ParameterizedCounter reset acted away from posedge");
      $finish;
    end
    clk = 1'b1;
    #1;
    if (count_limit_one !== 1'd0 ||
        count_limit_two !== 1'd0 ||
        count_limit_three !== 2'd0 ||
        count_default !== 3'd0 ||
        count_limit_eight !== 3'd0) begin
      $display("FAIL: ParameterizedCounter final reset priority");
      $finish;
    end

    $display("PASS: ParameterizedCounter");
    $finish;
  end
endmodule
