module RuntimeMuxTb;

  reg        default_select;
  reg  [7:0] default_false;
  reg  [7:0] default_true;
  wire [7:0] default_result;

  reg        narrow_select;
  reg  [4:0] narrow_false;
  reg  [4:0] narrow_true;
  wire [4:0] narrow_result;

  RuntimeMux default_dut (
    .data_false (default_false),
    .data_true  (default_true),
    .result     (default_result),
    .sel        (default_select)
  );

  RuntimeMux #(
    .WIDTH(5)
  ) narrow_dut (
    .data_false (narrow_false),
    .data_true  (narrow_true),
    .result     (narrow_result),
    .sel        (narrow_select)
  );

  initial begin
    default_false = 8'hA5;
    default_true = 8'h3C;
    narrow_false = 5'h09;
    narrow_true = 5'h16;

    default_select = 1'b0;
    narrow_select = 1'b0;
    #1;
    if ((default_result !== default_false) ||
        (narrow_result !== narrow_false)) begin
      $display("FAIL: RuntimeMux false branch");
      $finish;
    end

    default_select = 1'b1;
    narrow_select = 1'b1;
    #1;
    if ((default_result !== default_true) ||
        (narrow_result !== narrow_true)) begin
      $display("FAIL: RuntimeMux true branch");
    end else begin
      $display("PASS: RuntimeMux");
    end

    $finish;
  end

endmodule
