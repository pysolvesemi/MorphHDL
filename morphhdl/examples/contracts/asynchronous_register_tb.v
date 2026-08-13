module AsynchronousRegisterTb;
  reg clk;
  reg reset;
  reg [7:0] data_in_default;
  wire [7:0] data_out_default;
  reg [4:0] data_in_width_five;
  wire [4:0] data_out_width_five;

  AsynchronousRegister default_width (
    .clk(clk),
    .data_in(data_in_default),
    .data_out(data_out_default),
    .reset(reset)
  );

  AsynchronousRegister #(
    .WIDTH(5)
  ) width_five (
    .clk(clk),
    .data_in(data_in_width_five),
    .data_out(data_out_width_five),
    .reset(reset)
  );

  initial begin
    clk = 1'b0;
    reset = 1'b0;
    data_in_default = 8'ha5;
    data_in_width_five = 5'h13;

    #1;
    clk = 1'b1;
    #1;
    if (data_out_default !== 8'ha5 || data_out_width_five !== 5'h13) begin
      $display("FAIL: AsynchronousRegister initial capture");
      $finish;
    end

    clk = 1'b0;
    data_in_default = 8'h3c;
    data_in_width_five = 5'h0b;
    #1;
    reset = 1'b1;
    #1;
    if (data_out_default !== 8'h00 || data_out_width_five !== 5'h00) begin
      $display("FAIL: AsynchronousRegister asynchronous reset assertion");
      $finish;
    end

    data_in_default = 8'hff;
    data_in_width_five = 5'h1f;
    clk = 1'b1;
    #1;
    if (data_out_default !== 8'h00 || data_out_width_five !== 5'h00) begin
      $display("FAIL: AsynchronousRegister reset priority");
      $finish;
    end

    clk = 1'b0;
    reset = 1'b0;
    data_in_default = 8'h7e;
    data_in_width_five = 5'h1d;
    #1;
    if (data_out_default !== 8'h00 || data_out_width_five !== 5'h00) begin
      $display("FAIL: AsynchronousRegister deassertion changed output away from posedge");
      $finish;
    end

    clk = 1'b1;
    #1;
    if (data_out_default !== 8'h7e || data_out_width_five !== 5'h1d) begin
      $display("FAIL: AsynchronousRegister post-reset capture");
      $finish;
    end

    $display("PASS: AsynchronousRegister");
    $finish;
  end
endmodule
