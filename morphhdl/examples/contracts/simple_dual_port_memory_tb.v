module SimpleDualPortMemoryTb;
  reg clk;
  reg read_enable;
  reg write_enable;

  reg [2:0] read_address_default;
  reg [2:0] write_address_default;
  reg [7:0] write_data_default;
  wire [7:0] read_data_default;

  reg [1:0] read_address_awkward;
  reg [1:0] write_address_awkward;
  reg [4:0] write_data_awkward;
  wire [4:0] read_data_awkward;

  reg [0:0] read_address_minimum;
  reg [0:0] write_address_minimum;
  reg [0:0] write_data_minimum;
  wire [0:0] read_data_minimum;

  reg [2:0] read_address_power;
  reg [2:0] write_address_power;
  reg [3:0] write_data_power;
  wire [3:0] read_data_power;

  SimpleDualPortMemory default_memory (
    .clk(clk),
    .read_address(read_address_default),
    .read_data(read_data_default),
    .read_enable(read_enable),
    .write_address(write_address_default),
    .write_data(write_data_default),
    .write_enable(write_enable)
  );

  SimpleDualPortMemory #(
    .DEPTH(3),
    .WIDTH(5)
  ) awkward_memory (
    .clk(clk),
    .read_address(read_address_awkward),
    .read_data(read_data_awkward),
    .read_enable(read_enable),
    .write_address(write_address_awkward),
    .write_data(write_data_awkward),
    .write_enable(write_enable)
  );

  SimpleDualPortMemory #(
    .DEPTH(1),
    .WIDTH(1)
  ) minimum_memory (
    .clk(clk),
    .read_address(read_address_minimum),
    .read_data(read_data_minimum),
    .read_enable(read_enable),
    .write_address(write_address_minimum),
    .write_data(write_data_minimum),
    .write_enable(write_enable)
  );

  SimpleDualPortMemory #(
    .DEPTH(8),
    .WIDTH(4)
  ) power_memory (
    .clk(clk),
    .read_address(read_address_power),
    .read_data(read_data_power),
    .read_enable(read_enable),
    .write_address(write_address_power),
    .write_data(write_data_power),
    .write_enable(write_enable)
  );

  always #5 clk = ~clk;

  initial begin
    clk = 1'b0;
    read_enable = 1'b0;
    write_enable = 1'b0;
    read_address_default = 3'd0;
    write_address_default = 3'd0;
    write_data_default = 8'h00;
    read_address_awkward = 2'd0;
    write_address_awkward = 2'd0;
    write_data_awkward = 5'h00;
    read_address_minimum = 1'd0;
    write_address_minimum = 1'd0;
    write_data_minimum = 1'b0;
    read_address_power = 3'd0;
    write_address_power = 3'd0;
    write_data_power = 4'h0;

    /* Preload one known A word without reading uninitialized storage. */
    @(negedge clk);
    write_enable = 1'b1;
    write_address_default = 3'd1;
    write_data_default = 8'ha1;
    write_address_awkward = 2'd1;
    write_data_awkward = 5'h11;
    write_address_minimum = 1'd0;
    write_data_minimum = 1'b1;
    write_address_power = 3'd1;
    write_data_power = 4'h9;
    @(posedge clk);
    #1;

    /* Preload the last valid B word; minimum depth keeps its sole word. */
    @(negedge clk);
    write_address_default = 3'd4;
    write_data_default = 8'hb4;
    write_address_awkward = 2'd2;
    write_data_awkward = 5'h12;
    write_address_minimum = 1'd0;
    write_data_minimum = 1'b1;
    write_address_power = 3'd7;
    write_data_power = 4'he;
    @(posedge clk);
    #1;

    /* A valid enabled read updates synchronously. */
    @(negedge clk);
    read_enable = 1'b1;
    write_enable = 1'b0;
    read_address_default = 3'd1;
    read_address_awkward = 2'd1;
    read_address_minimum = 1'd0;
    read_address_power = 3'd1;
    @(posedge clk);
    #1;
    if (read_data_default !== 8'ha1 ||
        read_data_awkward !== 5'h11 ||
        read_data_minimum !== 1'b1 ||
        read_data_power !== 4'h9) begin
      $display("FAIL: SimpleDualPortMemory initial synchronous read");
      $finish;
    end

    /* A disabled read holds while the independent write port updates B. */
    @(negedge clk);
    read_enable = 1'b0;
    write_enable = 1'b1;
    write_address_default = 3'd4;
    write_data_default = 8'hc4;
    write_address_awkward = 2'd2;
    write_data_awkward = 5'h0c;
    write_address_minimum = 1'd0;
    write_data_minimum = 1'b0;
    write_address_power = 3'd7;
    write_data_power = 4'h6;
    @(posedge clk);
    #1;
    if (read_data_default !== 8'ha1 ||
        read_data_awkward !== 5'h11 ||
        read_data_minimum !== 1'b1 ||
        read_data_power !== 4'h9) begin
      $display("FAIL: SimpleDualPortMemory disabled read did not hold during write");
      $finish;
    end

    @(negedge clk);
    read_enable = 1'b1;
    write_enable = 1'b0;
    read_address_default = 3'd4;
    read_address_awkward = 2'd2;
    read_address_minimum = 1'd0;
    read_address_power = 3'd7;
    @(posedge clk);
    #1;
    if (read_data_default !== 8'hc4 ||
        read_data_awkward !== 5'h0c ||
        read_data_minimum !== 1'b0 ||
        read_data_power !== 4'h6) begin
      $display("FAIL: SimpleDualPortMemory independent write was not retained");
      $finish;
    end

    /* Different addresses operate simultaneously; depth one is a collision. */
    @(negedge clk);
    write_enable = 1'b1;
    read_address_default = 3'd1;
    write_address_default = 3'd4;
    write_data_default = 8'hd4;
    read_address_awkward = 2'd1;
    write_address_awkward = 2'd2;
    write_data_awkward = 5'h0d;
    read_address_minimum = 1'd0;
    write_address_minimum = 1'd0;
    write_data_minimum = 1'b1;
    read_address_power = 3'd1;
    write_address_power = 3'd7;
    write_data_power = 4'h7;
    @(posedge clk);
    #1;
    if (read_data_default !== 8'ha1 ||
        read_data_awkward !== 5'h11 ||
        read_data_minimum !== 1'b0 ||
        read_data_power !== 4'h9) begin
      $display("FAIL: SimpleDualPortMemory simultaneous independent read/write");
      $finish;
    end

    @(negedge clk);
    write_enable = 1'b0;
    read_address_default = 3'd4;
    read_address_awkward = 2'd2;
    read_address_minimum = 1'd0;
    read_address_power = 3'd7;
    @(posedge clk);
    #1;
    if (read_data_default !== 8'hd4 ||
        read_data_awkward !== 5'h0d ||
        read_data_minimum !== 1'b1 ||
        read_data_power !== 4'h7) begin
      $display("FAIL: SimpleDualPortMemory simultaneous write visibility");
      $finish;
    end

    /* A same-address collision returns the old word, then retains the write. */
    @(negedge clk);
    write_enable = 1'b1;
    write_address_default = 3'd4;
    write_data_default = 8'he4;
    write_address_awkward = 2'd2;
    write_data_awkward = 5'h0e;
    write_address_minimum = 1'd0;
    write_data_minimum = 1'b0;
    write_address_power = 3'd7;
    write_data_power = 4'h8;
    @(posedge clk);
    #1;
    if (read_data_default !== 8'hd4 ||
        read_data_awkward !== 5'h0d ||
        read_data_minimum !== 1'b1 ||
        read_data_power !== 4'h7) begin
      $display("FAIL: SimpleDualPortMemory same-address collision was not read-first");
      $finish;
    end

    @(negedge clk);
    write_enable = 1'b0;
    @(posedge clk);
    #1;
    if (read_data_default !== 8'he4 ||
        read_data_awkward !== 5'h0e ||
        read_data_minimum !== 1'b0 ||
        read_data_power !== 4'h8) begin
      $display("FAIL: SimpleDualPortMemory collision write was not retained");
      $finish;
    end

    /* A surplus read stays synchronous and does not block an independent valid write. */
    @(negedge clk);
    read_address_default = 3'd7;
    read_address_awkward = 2'd3;
    read_address_minimum = 1'd1;
    read_address_power = 3'd1;
    write_enable = 1'b1;
    write_address_default = 3'd1;
    write_data_default = 8'h5a;
    write_address_awkward = 2'd1;
    write_data_awkward = 5'h05;
    write_address_minimum = 1'd0;
    write_data_minimum = 1'b1;
    write_address_power = 3'd1;
    write_data_power = 4'ha;
    #1;
    if (read_data_default !== 8'he4 ||
        read_data_awkward !== 5'h0e ||
        read_data_minimum !== 1'b0 ||
        read_data_power !== 4'h8) begin
      $display("FAIL: SimpleDualPortMemory read changed before the edge");
      $finish;
    end
    @(posedge clk);
    #1;
    if (read_data_default !== 8'h00 ||
        read_data_awkward !== 5'h00 ||
        read_data_minimum !== 1'b0 ||
        read_data_power !== 4'h9) begin
      $display("FAIL: SimpleDualPortMemory surplus read result");
      $finish;
    end

    @(negedge clk);
    write_enable = 1'b0;
    read_address_default = 3'd1;
    read_address_awkward = 2'd1;
    read_address_minimum = 1'd0;
    read_address_power = 3'd1;
    @(posedge clk);
    #1;
    if (read_data_default !== 8'h5a ||
        read_data_awkward !== 5'h05 ||
        read_data_minimum !== 1'b1 ||
        read_data_power !== 4'ha) begin
      $display("FAIL: SimpleDualPortMemory valid write during surplus read");
      $finish;
    end

    /* Surplus writes are ignored while an independent valid read still occurs. */
    @(negedge clk);
    write_enable = 1'b1;
    write_address_default = 3'd7;
    write_data_default = 8'hff;
    write_address_awkward = 2'd3;
    write_data_awkward = 5'h1f;
    write_address_minimum = 1'd1;
    write_data_minimum = 1'b0;
    write_address_power = 3'd1;
    write_data_power = 4'ha;
    @(posedge clk);
    #1;
    if (read_data_default !== 8'h5a ||
        read_data_awkward !== 5'h05 ||
        read_data_minimum !== 1'b1 ||
        read_data_power !== 4'ha) begin
      $display("FAIL: SimpleDualPortMemory valid read during surplus write");
      $finish;
    end

    /* A disabled write cannot change any valid location. */
    @(negedge clk);
    write_enable = 1'b0;
    write_address_default = 3'd1;
    write_data_default = 8'h00;
    write_address_awkward = 2'd1;
    write_data_awkward = 5'h00;
    write_address_minimum = 1'd0;
    write_data_minimum = 1'b0;
    write_address_power = 3'd1;
    write_data_power = 4'h0;
    @(posedge clk);
    #1;
    if (read_data_default !== 8'h5a ||
        read_data_awkward !== 5'h05 ||
        read_data_minimum !== 1'b1 ||
        read_data_power !== 4'ha) begin
      $display("FAIL: SimpleDualPortMemory disabled write changed the concurrent read");
      $finish;
    end

    /* Reread after another edge so read-first cannot hide an illegal disabled write. */
    @(negedge clk);
    @(posedge clk);
    #1;
    if (read_data_default !== 8'h5a ||
        read_data_awkward !== 5'h05 ||
        read_data_minimum !== 1'b1 ||
        read_data_power !== 4'ha) begin
      $display("FAIL: SimpleDualPortMemory disabled write changed storage");
      $finish;
    end

    /* Disabled valid or surplus reads hold the most recent enabled result. */
    @(negedge clk);
    read_enable = 1'b0;
    read_address_default = 3'd7;
    read_address_awkward = 2'd3;
    read_address_minimum = 1'd1;
    read_address_power = 3'd7;
    @(posedge clk);
    #1;
    if (read_data_default !== 8'h5a ||
        read_data_awkward !== 5'h05 ||
        read_data_minimum !== 1'b1 ||
        read_data_power !== 4'ha) begin
      $display("FAIL: SimpleDualPortMemory disabled read did not hold");
      $finish;
    end

    $display("PASS: SimpleDualPortMemory");
    $finish;
  end
endmodule
