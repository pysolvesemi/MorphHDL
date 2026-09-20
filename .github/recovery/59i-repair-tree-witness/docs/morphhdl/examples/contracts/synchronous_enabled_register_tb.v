module SynchronousEnabledRegisterTb;
  reg clk;
  reg reset;
  reg enable;
  reg [7:0] data_in_default;
  wire [7:0] data_out_default;
  reg [4:0] data_in_width_five;
  wire [4:0] data_out_width_five;

  SynchronousEnabledRegister default_width (
    .clk(clk),
    .data_in(data_in_default),
    .data_out(data_out_default),
    .enable(enable),
    .reset(reset)
  );

  SynchronousEnabledRegister #(
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
    reset = 1'b1;
    enable = 1'b1;
    data_in_default = 8'hff;
    data_in_width_five = 5'h1f;

    #1;
    clk = 1'b1;
    #1;
    if (data_out_default !== 8'h00 || data_out_width_five !== 5'h00) begin
      $display("FAIL: SynchronousEnabledRegister reset priority");
      $finish;
    end

    clk = 1'b0;
    reset = 1'b0;
    data_in_default = 8'ha5;
    data_in_width_five = 5'h13;
    #1;
    clk = 1'b1;
    #1;
    if (data_out_default !== 8'ha5 || data_out_width_five !== 5'h13) begin
      $display("FAIL: SynchronousEnabledRegister enabled capture");
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
      $display("FAIL: SynchronousEnabledRegister disabled hold");
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
      $display("FAIL: SynchronousEnabledRegister later capture");
      $finish;
    end

    clk = 1'b0;
    reset = 1'b1;
    enable = 1'b1;
    data_in_default = 8'h55;
    data_in_width_five = 5'h15;
    #1;
    if (data_out_default !== 8'h7e || data_out_width_five !== 5'h1d) begin
      $display("FAIL: SynchronousEnabledRegister reset acted away from posedge");
      $finish;
    end
    clk = 1'b1;
    #1;
    if (data_out_default !== 8'h00 || data_out_width_five !== 5'h00) begin
      $display("FAIL: SynchronousEnabledRegister final reset priority");
      $finish;
    end

    $display("PASS: SynchronousEnabledRegister");
    $finish;
  end
endmodule
