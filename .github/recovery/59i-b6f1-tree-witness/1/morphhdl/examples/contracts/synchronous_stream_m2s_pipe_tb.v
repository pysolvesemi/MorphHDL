module SynchronousStreamM2sPipeTb;
  reg clk;
  reg reset;

  reg push_valid_default;
  wire push_ready_default;
  reg [7:0] push_data_default;
  wire pop_valid_default;
  reg pop_ready_default;
  wire [7:0] pop_data_default;

  reg push_valid_minimum;
  wire push_ready_minimum;
  reg [0:0] push_data_minimum;
  wire pop_valid_minimum;
  reg pop_ready_minimum;
  wire [0:0] pop_data_minimum;

  reg push_valid_awkward;
  wire push_ready_awkward;
  reg [4:0] push_data_awkward;
  wire pop_valid_awkward;
  reg pop_ready_awkward;
  wire [4:0] pop_data_awkward;

  SynchronousStreamM2sPipe default_pipe (
    .clk(clk),
    .pop_data(pop_data_default),
    .pop_ready(pop_ready_default),
    .pop_valid(pop_valid_default),
    .push_data(push_data_default),
    .push_ready(push_ready_default),
    .push_valid(push_valid_default),
    .reset(reset)
  );

  SynchronousStreamM2sPipe #(
    .WIDTH(1)
  ) minimum_pipe (
    .clk(clk),
    .pop_data(pop_data_minimum),
    .pop_ready(pop_ready_minimum),
    .pop_valid(pop_valid_minimum),
    .push_data(push_data_minimum),
    .push_ready(push_ready_minimum),
    .push_valid(push_valid_minimum),
    .reset(reset)
  );

  SynchronousStreamM2sPipe #(
    .WIDTH(5)
  ) awkward_pipe (
    .clk(clk),
    .pop_data(pop_data_awkward),
    .pop_ready(pop_ready_awkward),
    .pop_valid(pop_valid_awkward),
    .push_data(push_data_awkward),
    .push_ready(push_ready_awkward),
    .push_valid(push_valid_awkward),
    .reset(reset)
  );

  always #5 clk = ~clk;

  initial begin
    clk = 1'b0;
    reset = 1'b1;
    push_valid_default = 1'b1;
    push_data_default = 8'ha1;
    pop_ready_default = 1'b0;
    push_valid_minimum = 1'b1;
    push_data_minimum = 1'b1;
    pop_ready_minimum = 1'b0;
    push_valid_awkward = 1'b1;
    push_data_awkward = 5'h12;
    pop_ready_awkward = 1'b0;

    /* Reset clears valid but intentionally permits payload capture when ready. */
    @(posedge clk);
    #1;
    if (pop_valid_default !== 1'b0 || push_ready_default !== 1'b1 ||
        pop_valid_minimum !== 1'b0 || push_ready_minimum !== 1'b1 ||
        pop_valid_awkward !== 1'b0 || push_ready_awkward !== 1'b1) begin
      $display("FAIL: SynchronousStreamM2sPipe synchronous reset state");
      $finish;
    end

    @(negedge clk);
    reset = 1'b0;

    /* Empty pushes are accepted and become visible only after this edge. */
    if (pop_valid_default !== 1'b0 || push_ready_default !== 1'b1) begin
      $display("FAIL: SynchronousStreamM2sPipe empty ready/no bypass");
      $finish;
    end
    @(posedge clk);
    #1;
    if (pop_valid_default !== 1'b1 || pop_data_default !== 8'ha1 ||
        pop_valid_minimum !== 1'b1 || pop_data_minimum !== 1'b1 ||
        pop_valid_awkward !== 1'b1 || pop_data_awkward !== 5'h12) begin
      $display("FAIL: SynchronousStreamM2sPipe registered capture widths");
      $finish;
    end

    /* Full and stalled rejects input and holds both valid and payload. */
    @(negedge clk);
    push_data_default = 8'hb2;
    push_data_minimum = 1'b0;
    push_data_awkward = 5'h03;
    if (push_ready_default !== 1'b0 || push_ready_minimum !== 1'b0 ||
        push_ready_awkward !== 1'b0) begin
      $display("FAIL: SynchronousStreamM2sPipe full stall advertised ready");
      $finish;
    end
    @(posedge clk);
    #1;
    if (pop_data_default !== 8'ha1 || pop_data_minimum !== 1'b1 ||
        pop_data_awkward !== 5'h12 || pop_valid_default !== 1'b1 ||
        pop_valid_minimum !== 1'b1 || pop_valid_awkward !== 1'b1) begin
      $display("FAIL: SynchronousStreamM2sPipe stall stability");
      $finish;
    end

    /* Full pop and push replace atomically without an invalid bubble. */
    @(negedge clk);
    pop_ready_default = 1'b1;
    pop_ready_minimum = 1'b1;
    pop_ready_awkward = 1'b1;
    #1;
    if (push_ready_default !== 1'b1 || push_ready_minimum !== 1'b1 ||
        push_ready_awkward !== 1'b1) begin
      $display("FAIL: SynchronousStreamM2sPipe replacement was not ready");
      $finish;
    end
    @(posedge clk);
    #1;
    if (pop_valid_default !== 1'b1 || pop_data_default !== 8'hb2 ||
        pop_valid_minimum !== 1'b1 || pop_data_minimum !== 1'b0 ||
        pop_valid_awkward !== 1'b1 || pop_data_awkward !== 5'h03) begin
      $display("FAIL: SynchronousStreamM2sPipe bubble-free replacement");
      $finish;
    end

    /* Sustained ready/valid traffic replaces again on the very next edge. */
    @(negedge clk);
    push_data_default = 8'hc3;
    push_data_minimum = 1'b1;
    push_data_awkward = 5'h1d;
    @(posedge clk);
    #1;
    if (pop_valid_default !== 1'b1 || pop_data_default !== 8'hc3 ||
        pop_valid_minimum !== 1'b1 || pop_data_minimum !== 1'b1 ||
        pop_valid_awkward !== 1'b1 || pop_data_awkward !== 5'h1d) begin
      $display("FAIL: SynchronousStreamM2sPipe sustained throughput");
      $finish;
    end

    /* A pop without replacement clears valid; invalid payload may still capture. */
    @(negedge clk);
    push_valid_default = 1'b0;
    push_valid_minimum = 1'b0;
    push_valid_awkward = 1'b0;
    push_data_default = 8'hc4;
    push_data_minimum = 1'b0;
    push_data_awkward = 5'h1c;
    @(posedge clk);
    #1;
    if (pop_valid_default !== 1'b0 || pop_valid_minimum !== 1'b0 ||
        pop_valid_awkward !== 1'b0 || push_ready_default !== 1'b1 ||
        push_ready_minimum !== 1'b1 || push_ready_awkward !== 1'b1) begin
      $display("FAIL: SynchronousStreamM2sPipe pop-without-replacement clear");
      $finish;
    end

    /* The next valid input proves another one-edge capture after empty. */
    @(negedge clk);
    pop_ready_default = 1'b0;
    pop_ready_minimum = 1'b0;
    pop_ready_awkward = 1'b0;
    push_valid_default = 1'b1;
    push_valid_minimum = 1'b1;
    push_valid_awkward = 1'b1;
    push_data_default = 8'hd4;
    push_data_minimum = 1'b0;
    push_data_awkward = 5'h0e;
    #1;
    if (pop_valid_default !== 1'b0 || pop_valid_minimum !== 1'b0 ||
        pop_valid_awkward !== 1'b0) begin
      $display("FAIL: SynchronousStreamM2sPipe combinational valid bypass");
      $finish;
    end
    @(posedge clk);
    #1;
    if (pop_valid_default !== 1'b1 || pop_data_default !== 8'hd4 ||
        pop_valid_minimum !== 1'b1 || pop_data_minimum !== 1'b0 ||
        pop_valid_awkward !== 1'b1 || pop_data_awkward !== 5'h0e) begin
      $display("FAIL: SynchronousStreamM2sPipe refill capture");
      $finish;
    end

    /* Reset while full wins for valid and does not add a payload reset policy. */
    @(negedge clk);
    reset = 1'b1;
    #1;
    if (pop_valid_default !== 1'b1 || pop_data_default !== 8'hd4 ||
        pop_valid_minimum !== 1'b1 || pop_data_minimum !== 1'b0 ||
        pop_valid_awkward !== 1'b1 || pop_data_awkward !== 5'h0e) begin
      $display("FAIL: SynchronousStreamM2sPipe reset changed state before edge");
      $finish;
    end
    push_data_default = 8'he5;
    push_data_minimum = 1'b1;
    push_data_awkward = 5'h17;
    @(posedge clk);
    #1;
    if (pop_valid_default !== 1'b0 || pop_data_default !== 8'hd4 ||
        pop_valid_minimum !== 1'b0 || pop_data_minimum !== 1'b0 ||
        pop_valid_awkward !== 1'b0 || pop_data_awkward !== 5'h0e) begin
      $display("FAIL: SynchronousStreamM2sPipe reset priority");
      $finish;
    end

    /* While reset remains high, empty ready still enables unreset payload. */
    @(negedge clk);
    push_valid_default = 1'b0;
    push_valid_minimum = 1'b0;
    push_valid_awkward = 1'b0;
    #1;
    if (push_ready_default !== 1'b1 || push_ready_minimum !== 1'b1 ||
        push_ready_awkward !== 1'b1) begin
      $display("FAIL: SynchronousStreamM2sPipe reset-empty ready");
      $finish;
    end
    @(posedge clk);
    #1;
    if (pop_valid_default !== 1'b0 || pop_data_default !== 8'he5 ||
        pop_valid_minimum !== 1'b0 || pop_data_minimum !== 1'b1 ||
        pop_valid_awkward !== 1'b0 || pop_data_awkward !== 5'h17) begin
      $display("FAIL: SynchronousStreamM2sPipe payload capture during reset");
      $finish;
    end

    $display("PASS: SynchronousStreamM2sPipe");
    $finish;
  end
endmodule
