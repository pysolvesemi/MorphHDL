module AsynchronousEnabledRegisterTb;
  reg clk;
  reg reset;
  reg enable;
  reg [7:0] data_in_default;
  wire [7:0] data_out_default;
  reg [4:0] data_in_width_five;
  wire [4:0] data_out_width_five;

  AsynchronousEnabledRegister default_width (
    .clk(clk),
    .data_in(data_in_default),
    .data_out(data_out_default),
    .enable(enable),
    .reset(reset)
  );

  AsynchronousEnabledRegister #(
    .WIDTH(5)
  ) width_five (
    .clk(clk),
    .data_in(data_in_width_five),
    .data_out(data_out_width_five),
    .enable(enable),
    .reset(reset)
  );

  initial begin
    clk = 1'b0;
    reset = 1'b0;
    enable = 1'b1;
    data_in_default = 8'hff;
    data_in_width_five = 5'h1f;

    #1;
    reset = 1'b1;
    #1;
    if (data_out_default !== 8'h00 || data_out_width_five !== 5'h00) begin
      $display("FAIL: AsynchronousEnabledRegister reset did not assert immediately");
      $finish;
    end

    #1;
    clk = 1'b1;
    #1;
    if (data_out_default !== 8'h00 || data_out_width_five !== 5'h00) begin
      $display("FAIL: AsynchronousEnabledRegister reset priority");
      $finish;
    end

    clk = 1'b0;
    reset = 1'b0;
    data_in_default = 8'ha5;
    data_in_width_five = 5'h13;
    #1;
    if (data_out_default !== 8'h00 || data_out_width_five !== 5'h00) begin
      $display("FAIL: AsynchronousEnabledRegister reset deassertion captured data");
      $finish;
    end
    clk = 1'b1;
    #1;
    if (data_out_default !== 8'ha5 || data_out_width_five !== 5'h13) begin
      $display("FAIL: AsynchronousEnabledRegister enabled capture");
      $finish;
    end

    clk = 1'b0;
    enable = 1'b0;
    data_in_default = 8'h3c;
    data_in_width_five = 5'h0b;
    #1;
    clk = 1'b1;
    #1;
    if (data_out_default !== 8'ha5 || data_out_width_five !== 5'h13) begin
      $display("FAIL: AsynchronousEnabledRegister disabled hold");
      $finish;
    end

    clk = 1'b0;
    enable = 1'b1;
    data_in_default = 8'h7e;
    data_in_width_five = 5'h1d;
    #1;
    clk = 1'b1;
    #1;
    if (data_out_default !== 8'h7e || data_out_width_five !== 5'h1d) begin
      $display("FAIL: AsynchronousEnabledRegister later capture");
      $finish;
    end

    clk = 1'b0;
    data_in_default = 8'h55;
    data_in_width_five = 5'h15;
    #1;
    reset = 1'b1;
    #1;
    if (data_out_default !== 8'h00 || data_out_width_five !== 5'h00) begin
      $display("FAIL: AsynchronousEnabledRegister final immediate reset");
      $finish;
    end

    $display("PASS: AsynchronousEnabledRegister");
    $finish;
  end
endmodule
