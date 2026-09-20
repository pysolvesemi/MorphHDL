module SynchronousStreamFifoTb;
  reg clk;
  reg reset;

  reg push_valid_default;
  wire push_ready_default;
  reg [7:0] push_data_default;
  wire pop_valid_default;
  reg pop_ready_default;
  wire [7:0] pop_data_default;

  reg push_valid_awkward;
  wire push_ready_awkward;
  reg [4:0] push_data_awkward;
  wire pop_valid_awkward;
  reg pop_ready_awkward;
  wire [4:0] pop_data_awkward;

  reg push_valid_minimum;
  wire push_ready_minimum;
  reg [0:0] push_data_minimum;
  wire pop_valid_minimum;
  reg pop_ready_minimum;
  wire [0:0] pop_data_minimum;

  reg push_valid_power;
  wire push_ready_power;
  reg [3:0] push_data_power;
  wire pop_valid_power;
  reg pop_ready_power;
  wire [3:0] pop_data_power;

  integer i;

  SynchronousStreamFifo default_fifo (
    .clk(clk),
    .pop_data(pop_data_default),
    .pop_ready(pop_ready_default),
    .pop_valid(pop_valid_default),
    .push_data(push_data_default),
    .push_ready(push_ready_default),
    .push_valid(push_valid_default),
    .reset(reset)
  );

  SynchronousStreamFifo #(
    .DEPTH(3),
    .WIDTH(5)
  ) awkward_fifo (
    .clk(clk),
    .pop_data(pop_data_awkward),
    .pop_ready(pop_ready_awkward),
    .pop_valid(pop_valid_awkward),
    .push_data(push_data_awkward),
    .push_ready(push_ready_awkward),
    .push_valid(push_valid_awkward),
    .reset(reset)
  );

  SynchronousStreamFifo #(
    .DEPTH(1),
    .WIDTH(1)
  ) minimum_fifo (
    .clk(clk),
    .pop_data(pop_data_minimum),
    .pop_ready(pop_ready_minimum),
    .pop_valid(pop_valid_minimum),
    .push_data(push_data_minimum),
    .push_ready(push_ready_minimum),
    .push_valid(push_valid_minimum),
    .reset(reset)
  );

  SynchronousStreamFifo #(
    .DEPTH(8),
    .WIDTH(4)
  ) power_fifo (
    .clk(clk),
    .pop_data(pop_data_power),
    .pop_ready(pop_ready_power),
    .pop_valid(pop_valid_power),
    .push_data(push_data_power),
    .push_ready(push_ready_power),
    .push_valid(push_valid_power),
    .reset(reset)
  );

  always #5 clk = ~clk;

  initial begin
    clk = 1'b0;
    reset = 1'b1;
    /* Assert both sides during reset to prove reset wins over handshakes. */
    push_valid_default = 1'b1;
    push_data_default = 8'hee;
    pop_ready_default = 1'b1;
    push_valid_awkward = 1'b0;
    push_data_awkward = 5'h00;
    pop_ready_awkward = 1'b0;
    push_valid_minimum = 1'b0;
    push_data_minimum = 1'b0;
    pop_ready_minimum = 1'b0;
    push_valid_power = 1'b0;
    push_data_power = 4'h0;
    pop_ready_power = 1'b0;

    @(posedge clk);
    #1;
    if (pop_valid_default !== 1'b0 || push_ready_default !== 1'b1 ||
        pop_valid_awkward !== 1'b0 || push_ready_awkward !== 1'b1 ||
        pop_valid_minimum !== 1'b0 || push_ready_minimum !== 1'b1 ||
        pop_valid_power !== 1'b0 || push_ready_power !== 1'b1) begin
      $display("FAIL: SynchronousStreamFifo synchronous reset state");
      $finish;
    end

    @(negedge clk);
    reset = 1'b0;
    push_valid_default = 1'b0;
    pop_ready_default = 1'b0;

    /* Empty push is accepted but never bypasses directly to the pop side. */
    push_valid_default = 1'b1;
    push_data_default = 8'ha1;
    @(posedge clk);
    #1;
    if (pop_valid_default !== 1'b0 || push_ready_default !== 1'b1) begin
      $display("FAIL: SynchronousStreamFifo empty push bypassed");
      $finish;
    end
    @(negedge clk);
    push_valid_default = 1'b0;
    @(posedge clk);
    #1;
    if (pop_valid_default !== 1'b1 || pop_data_default !== 8'ha1) begin
      $display("FAIL: SynchronousStreamFifo registered first fetch");
      $finish;
    end

    /* A stalled pop holds while two more pushes enter the RAM. */
    @(negedge clk);
    push_valid_default = 1'b1;
    push_data_default = 8'hb2;
    @(posedge clk);
    #1;
    if (pop_valid_default !== 1'b1 || pop_data_default !== 8'ha1) begin
      $display("FAIL: SynchronousStreamFifo stalled first payload");
      $finish;
    end
    @(negedge clk);
    push_data_default = 8'hc3;
    @(posedge clk);
    #1;
    if (pop_valid_default !== 1'b1 || pop_data_default !== 8'ha1) begin
      $display("FAIL: SynchronousStreamFifo stall stability");
      $finish;
    end

    /* Middle-state push and pop both fire and preserve order without a bubble. */
    @(negedge clk);
    pop_ready_default = 1'b1;
    push_data_default = 8'hd4;
    @(posedge clk);
    #1;
    if (pop_valid_default !== 1'b1 || pop_data_default !== 8'hb2) begin
      $display("FAIL: SynchronousStreamFifo simultaneous middle transfer B");
      $finish;
    end
    @(negedge clk);
    push_data_default = 8'he5;
    @(posedge clk);
    #1;
    if (pop_valid_default !== 1'b1 || pop_data_default !== 8'hc3) begin
      $display("FAIL: SynchronousStreamFifo simultaneous middle transfer C");
      $finish;
    end
    @(negedge clk);
    push_data_default = 8'hf6;
    @(posedge clk);
    #1;
    if (pop_valid_default !== 1'b1 || pop_data_default !== 8'hd4) begin
      $display("FAIL: SynchronousStreamFifo pointer wrap transfer D");
      $finish;
    end
    @(negedge clk);
    push_valid_default = 1'b0;
    @(posedge clk);
    #1;
    if (pop_valid_default !== 1'b1 || pop_data_default !== 8'he5) begin
      $display("FAIL: SynchronousStreamFifo ordered transfer E");
      $finish;
    end
    @(negedge clk);
    @(posedge clk);
    #1;
    if (pop_valid_default !== 1'b1 || pop_data_default !== 8'hf6) begin
      $display("FAIL: SynchronousStreamFifo ordered transfer F");
      $finish;
    end
    @(negedge clk);
    @(posedge clk);
    #1;
    if (pop_valid_default !== 1'b0) begin
      $display("FAIL: SynchronousStreamFifo did not become empty");
      $finish;
    end

    /* Occupancy one accepts push and pop together, then incurs one refill bubble. */
    @(negedge clk);
    pop_ready_default = 1'b0;
    push_valid_default = 1'b1;
    push_data_default = 8'h71;
    @(posedge clk);
    #1;
    @(negedge clk);
    push_valid_default = 1'b0;
    @(posedge clk);
    #1;
    if (pop_valid_default !== 1'b1 || pop_data_default !== 8'h71) begin
      $display("FAIL: SynchronousStreamFifo occupancy-one setup");
      $finish;
    end
    @(negedge clk);
    pop_ready_default = 1'b1;
    push_valid_default = 1'b1;
    push_data_default = 8'h72;
    if (push_ready_default !== 1'b1) begin
      $display("FAIL: SynchronousStreamFifo occupancy-one push was not ready");
      $finish;
    end
    @(posedge clk);
    #1;
    if (pop_valid_default !== 1'b0) begin
      $display("FAIL: SynchronousStreamFifo occupancy-one refill lacked bubble");
      $finish;
    end
    @(negedge clk);
    push_valid_default = 1'b0;
    pop_ready_default = 1'b0;
    @(posedge clk);
    #1;
    if (pop_valid_default !== 1'b1 || pop_data_default !== 8'h72) begin
      $display("FAIL: SynchronousStreamFifo occupancy-one refill data");
      $finish;
    end

    /* Reset, fill exactly DEPTH entries and prove full-pop push rejection. */
    @(negedge clk);
    reset = 1'b1;
    @(posedge clk);
    #1;
    @(negedge clk);
    reset = 1'b0;
    for (i = 1; i <= 5; i = i + 1) begin
      push_valid_default = 1'b1;
      push_data_default = i[7:0];
      @(posedge clk);
      #1;
      @(negedge clk);
    end
    if (push_ready_default !== 1'b0 || pop_valid_default !== 1'b1 ||
        pop_data_default !== 8'h01) begin
      $display("FAIL: SynchronousStreamFifo exact public capacity");
      $finish;
    end
    pop_ready_default = 1'b1;
    push_data_default = 8'h06;
    #1;
    if (push_ready_default !== 1'b0) begin
      $display("FAIL: SynchronousStreamFifo full pop exposed same-edge space");
      $finish;
    end
    @(posedge clk);
    #1;
    if (pop_valid_default !== 1'b1 || pop_data_default !== 8'h02 ||
        push_ready_default !== 1'b1) begin
      $display("FAIL: SynchronousStreamFifo full boundary pop");
      $finish;
    end
    @(negedge clk);
    /* The previously rejected six is accepted only after space is visible. */
    @(posedge clk);
    #1;
    if (pop_data_default !== 8'h03) begin
      $display("FAIL: SynchronousStreamFifo post-full accepted push ordering");
      $finish;
    end
    @(negedge clk);
    push_valid_default = 1'b0;
    for (i = 4; i <= 6; i = i + 1) begin
      @(posedge clk);
      #1;
      if (pop_valid_default !== 1'b1 || pop_data_default !== i[7:0]) begin
        $display("FAIL: SynchronousStreamFifo full-drain order %0d", i);
        $finish;
      end
      @(negedge clk);
    end
    @(posedge clk);
    #1;
    if (pop_valid_default !== 1'b0) begin
      $display("FAIL: SynchronousStreamFifo full-drain empty state");
      $finish;
    end

    /* Sustained middle transfers cross both pointers more than twice. */
    @(negedge clk);
    pop_ready_default = 1'b0;
    for (i = 16; i <= 18; i = i + 1) begin
      push_valid_default = 1'b1;
      push_data_default = i[7:0];
      @(posedge clk);
      #1;
      @(negedge clk);
    end
    push_valid_default = 1'b0;
    @(posedge clk);
    #1;
    @(negedge clk);
    if (pop_valid_default !== 1'b1 || pop_data_default !== 8'h10) begin
      $display("FAIL: SynchronousStreamFifo multi-wrap setup");
      $finish;
    end
    pop_ready_default = 1'b1;
    push_valid_default = 1'b1;
    for (i = 0; i < 12; i = i + 1) begin
      push_data_default = 8'h20 + i[7:0];
      if (i == 0 && pop_data_default !== 8'h10) begin
        $display("FAIL: SynchronousStreamFifo multi-wrap initial 10");
        $finish;
      end
      if (i == 1 && pop_data_default !== 8'h11) begin
        $display("FAIL: SynchronousStreamFifo multi-wrap initial 11");
        $finish;
      end
      if (i == 2 && pop_data_default !== 8'h12) begin
        $display("FAIL: SynchronousStreamFifo multi-wrap initial 12");
        $finish;
      end
      if (i >= 3 && pop_data_default !== 8'h20 + (i - 3)) begin
        $display("FAIL: SynchronousStreamFifo sustained multi-wrap %0d", i);
        $finish;
      end
      @(posedge clk);
      #1;
      @(negedge clk);
    end
    push_valid_default = 1'b0;
    for (i = 9; i < 12; i = i + 1) begin
      if (pop_valid_default !== 1'b1 ||
          pop_data_default !== 8'h20 + i[7:0]) begin
        $display("FAIL: SynchronousStreamFifo multi-wrap drain %0d", i);
        $finish;
      end
      @(posedge clk);
      #1;
      @(negedge clk);
    end
    if (pop_valid_default !== 1'b0) begin
      $display("FAIL: SynchronousStreamFifo multi-wrap empty state");
      $finish;
    end

    /* Awkward DEPTH=3 fills, blocks and drains in order. */
    @(negedge clk);
    reset = 1'b1;
    @(posedge clk);
    #1;
    @(negedge clk);
    reset = 1'b0;
    for (i = 1; i <= 3; i = i + 1) begin
      push_valid_awkward = 1'b1;
      push_data_awkward = i[4:0];
      @(posedge clk);
      #1;
      @(negedge clk);
    end
    if (push_ready_awkward !== 1'b0 || pop_data_awkward !== 5'h01) begin
      $display("FAIL: SynchronousStreamFifo awkward capacity");
      $finish;
    end
    push_valid_awkward = 1'b0;
    pop_ready_awkward = 1'b1;
    for (i = 1; i <= 3; i = i + 1) begin
      if (pop_valid_awkward !== 1'b1 || pop_data_awkward !== i[4:0]) begin
        $display("FAIL: SynchronousStreamFifo awkward order %0d", i);
        $finish;
      end
      @(posedge clk);
      #1;
      @(negedge clk);
    end
    if (pop_valid_awkward !== 1'b0) begin
      $display("FAIL: SynchronousStreamFifo awkward empty state");
      $finish;
    end

    /* DEPTH=1 is uniformly full and rejects a simultaneous replacement. */
    push_valid_minimum = 1'b1;
    push_data_minimum = 1'b1;
    @(posedge clk);
    #1;
    @(negedge clk);
    push_valid_minimum = 1'b0;
    @(posedge clk);
    #1;
    if (pop_valid_minimum !== 1'b1 || pop_data_minimum !== 1'b1 ||
        push_ready_minimum !== 1'b0) begin
      $display("FAIL: SynchronousStreamFifo minimum fill");
      $finish;
    end
    @(negedge clk);
    pop_ready_minimum = 1'b1;
    push_valid_minimum = 1'b1;
    push_data_minimum = 1'b0;
    #1;
    if (push_ready_minimum !== 1'b0) begin
      $display("FAIL: SynchronousStreamFifo minimum pop exposed same-edge space");
      $finish;
    end
    @(posedge clk);
    #1;
    if (pop_valid_minimum !== 1'b0 || push_ready_minimum !== 1'b1) begin
      $display("FAIL: SynchronousStreamFifo minimum full replacement was accepted");
      $finish;
    end
    @(negedge clk);
    pop_ready_minimum = 1'b0;
    @(posedge clk);
    #1;
    if (pop_valid_minimum !== 1'b0 || push_ready_minimum !== 1'b0) begin
      $display("FAIL: SynchronousStreamFifo minimum accepted push bypassed fetch");
      $finish;
    end
    @(negedge clk);
    push_valid_minimum = 1'b0;
    @(posedge clk);
    #1;
    if (pop_valid_minimum !== 1'b1 || pop_data_minimum !== 1'b0) begin
      $display("FAIL: SynchronousStreamFifo minimum refill");
      $finish;
    end

    /* Power-of-two DEPTH=8 crosses every pointer value and returns in order. */
    @(negedge clk);
    reset = 1'b1;
    @(posedge clk);
    #1;
    @(negedge clk);
    reset = 1'b0;
    for (i = 1; i <= 8; i = i + 1) begin
      push_valid_power = 1'b1;
      push_data_power = i[3:0];
      @(posedge clk);
      #1;
      @(negedge clk);
    end
    push_valid_power = 1'b0;
    pop_ready_power = 1'b1;
    if (push_ready_power !== 1'b0) begin
      $display("FAIL: SynchronousStreamFifo power-of-two full state");
      $finish;
    end
    for (i = 1; i <= 8; i = i + 1) begin
      if (pop_valid_power !== 1'b1 || pop_data_power !== i[3:0]) begin
        $display("FAIL: SynchronousStreamFifo power-of-two order %0d", i);
        $finish;
      end
      @(posedge clk);
      #1;
      @(negedge clk);
    end
    if (pop_valid_power !== 1'b0 || push_ready_power !== 1'b1) begin
      $display("FAIL: SynchronousStreamFifo power-of-two empty state");
      $finish;
    end

    $display("PASS: SynchronousStreamFifo contract");
    $finish;
  end
endmodule
