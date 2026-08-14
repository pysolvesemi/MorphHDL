module SinglePortMemoryTb;
  reg clk;

  reg [2:0] address_default;
  reg read_enable_default;
  reg [7:0] write_data_default;
  reg write_enable_default;
  wire [7:0] read_data_default;

  reg [1:0] address_awkward;
  reg read_enable_awkward;
  reg [4:0] write_data_awkward;
  reg write_enable_awkward;
  wire [4:0] read_data_awkward;

  reg [0:0] address_minimum;
  reg read_enable_minimum;
  reg [0:0] write_data_minimum;
  reg write_enable_minimum;
  wire [0:0] read_data_minimum;

  SinglePortMemory default_shape (
    .address(address_default),
    .clk(clk),
    .read_data(read_data_default),
    .read_enable(read_enable_default),
    .write_data(write_data_default),
    .write_enable(write_enable_default)
  );

  SinglePortMemory #(
    .DEPTH(3),
    .WIDTH(5)
  ) awkward_shape (
    .address(address_awkward),
    .clk(clk),
    .read_data(read_data_awkward),
    .read_enable(read_enable_awkward),
    .write_data(write_data_awkward),
    .write_enable(write_enable_awkward)
  );

  SinglePortMemory #(
    .DEPTH(1),
    .WIDTH(1)
  ) minimum_shape (
    .address(address_minimum),
    .clk(clk),
    .read_data(read_data_minimum),
    .read_enable(read_enable_minimum),
    .write_data(write_data_minimum),
    .write_enable(write_enable_minimum)
  );

  initial begin
    clk = 1'b0;
    address_default = 3'd7;
    address_awkward = 2'd3;
    address_minimum = 1'd1;
    write_data_default = 8'ha5;
    write_data_awkward = 5'h13;
    write_data_minimum = 1'b1;
    read_enable_default = 1'b1;
    read_enable_awkward = 1'b1;
    read_enable_minimum = 1'b1;
    write_enable_default = 1'b0;
    write_enable_awkward = 1'b0;
    write_enable_minimum = 1'b0;

    #1;
    clk = 1'b1;
    #1;
    if (read_data_default !== 8'h00 ||
        read_data_awkward !== 5'h00 ||
        read_data_minimum !== 1'b0) begin
      $display("FAIL: SinglePortMemory surplus address did not synchronously read zero");
      $finish;
    end
    clk = 1'b0;

    address_default = 3'd4;
    address_awkward = 2'd2;
    address_minimum = 1'd0;
    read_enable_default = 1'b0;
    read_enable_awkward = 1'b0;
    read_enable_minimum = 1'b0;
    write_enable_default = 1'b1;
    write_enable_awkward = 1'b1;
    write_enable_minimum = 1'b1;
    #1;
    clk = 1'b1;
    #1;
    if (read_data_default !== 8'h00 ||
        read_data_awkward !== 5'h00 ||
        read_data_minimum !== 1'b0) begin
      $display("FAIL: SinglePortMemory write with read disabled did not hold output");
      $finish;
    end
    clk = 1'b0;

    read_enable_default = 1'b1;
    read_enable_awkward = 1'b1;
    read_enable_minimum = 1'b1;
    write_enable_default = 1'b0;
    write_enable_awkward = 1'b0;
    write_enable_minimum = 1'b0;
    #1;
    clk = 1'b1;
    #1;
    if (read_data_default !== 8'ha5 ||
        read_data_awkward !== 5'h13 ||
        read_data_minimum !== 1'b1) begin
      $display("FAIL: SinglePortMemory synchronous read after initial write");
      $finish;
    end
    clk = 1'b0;

    address_default = 3'd0;
    address_awkward = 2'd0;
    address_minimum = 1'd0;
    read_enable_default = 1'b0;
    read_enable_awkward = 1'b0;
    read_enable_minimum = 1'b0;
    #1;
    clk = 1'b1;
    #1;
    if (read_data_default !== 8'ha5 ||
        read_data_awkward !== 5'h13 ||
        read_data_minimum !== 1'b1) begin
      $display("FAIL: SinglePortMemory valid disabled read did not hold output");
      $finish;
    end
    clk = 1'b0;

    address_default = 3'd7;
    address_awkward = 2'd3;
    address_minimum = 1'd1;
    #1;
    clk = 1'b1;
    #1;
    if (read_data_default !== 8'ha5 ||
        read_data_awkward !== 5'h13 ||
        read_data_minimum !== 1'b1) begin
      $display("FAIL: SinglePortMemory surplus disabled read did not hold output");
      $finish;
    end
    clk = 1'b0;

    address_default = 3'd4;
    address_awkward = 2'd2;
    address_minimum = 1'd0;
    write_data_default = 8'h3c;
    write_data_awkward = 5'h0b;
    write_data_minimum = 1'b0;
    read_enable_default = 1'b1;
    read_enable_awkward = 1'b1;
    read_enable_minimum = 1'b1;
    write_enable_default = 1'b1;
    write_enable_awkward = 1'b1;
    write_enable_minimum = 1'b1;
    #1;
    clk = 1'b1;
    #1;
    if (read_data_default !== 8'ha5 ||
        read_data_awkward !== 5'h13 ||
        read_data_minimum !== 1'b1) begin
      $display("FAIL: SinglePortMemory same-address collision was not read-first");
      $finish;
    end
    clk = 1'b0;

    write_enable_default = 1'b0;
    write_enable_awkward = 1'b0;
    write_enable_minimum = 1'b0;
    #1;
    clk = 1'b1;
    #1;
    if (read_data_default !== 8'h3c ||
        read_data_awkward !== 5'h0b ||
        read_data_minimum !== 1'b0) begin
      $display("FAIL: SinglePortMemory collision write was not retained");
      $finish;
    end
    clk = 1'b0;

    write_data_default = 8'h7e;
    write_data_awkward = 5'h1d;
    write_data_minimum = 1'b1;
    read_enable_default = 1'b0;
    read_enable_awkward = 1'b0;
    read_enable_minimum = 1'b0;
    #1;
    clk = 1'b1;
    #1;
    if (read_data_default !== 8'h3c ||
        read_data_awkward !== 5'h0b ||
        read_data_minimum !== 1'b0) begin
      $display("FAIL: SinglePortMemory disabled read changed output");
      $finish;
    end
    clk = 1'b0;
    read_enable_default = 1'b1;
    read_enable_awkward = 1'b1;
    read_enable_minimum = 1'b1;
    #1;
    clk = 1'b1;
    #1;
    if (read_data_default !== 8'h3c ||
        read_data_awkward !== 5'h0b ||
        read_data_minimum !== 1'b0) begin
      $display("FAIL: SinglePortMemory disabled write changed storage");
      $finish;
    end
    clk = 1'b0;

    address_default = 3'd7;
    address_awkward = 2'd3;
    address_minimum = 1'd1;
    read_enable_default = 1'b1;
    read_enable_awkward = 1'b1;
    read_enable_minimum = 1'b1;
    write_enable_default = 1'b1;
    write_enable_awkward = 1'b1;
    write_enable_minimum = 1'b1;
    #1;
    if (read_data_default !== 8'h3c ||
        read_data_awkward !== 5'h0b ||
        read_data_minimum !== 1'b0) begin
      $display("FAIL: SinglePortMemory surplus address changed output before the edge");
      $finish;
    end
    clk = 1'b1;
    #1;
    if (read_data_default !== 8'h00 ||
        read_data_awkward !== 5'h00 ||
        read_data_minimum !== 1'b0) begin
      $display("FAIL: SinglePortMemory surplus address result");
      $finish;
    end
    clk = 1'b0;

    address_default = 3'd4;
    address_awkward = 2'd2;
    address_minimum = 1'd0;
    read_enable_default = 1'b1;
    read_enable_awkward = 1'b1;
    read_enable_minimum = 1'b1;
    write_enable_default = 1'b0;
    write_enable_awkward = 1'b0;
    write_enable_minimum = 1'b0;
    #1;
    clk = 1'b1;
    #1;
    if (read_data_default !== 8'h3c ||
        read_data_awkward !== 5'h0b ||
        read_data_minimum !== 1'b0) begin
      $display("FAIL: SinglePortMemory surplus write was not ignored");
      $finish;
    end

    $display("PASS: SinglePortMemory");
    $finish;
  end
endmodule
