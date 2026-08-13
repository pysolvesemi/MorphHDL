module ComparisonRoutingTb;

  reg  [7:0] default_din;
  wire [7:0] default_dout;
  reg  [7:0] low_din;
  wire [7:0] low_dout;
  reg  [7:0] equal_din;
  wire [7:0] equal_dout;

  ComparisonRouting default_dut (
    .din  (default_din),
    .dout (default_dout)
  );

  ComparisonRouting #(
    .SELECT(3),
    .THRESHOLD(5)
  ) low_dut (
    .din  (low_din),
    .dout (low_dout)
  );

  ComparisonRouting #(
    .SELECT(5),
    .THRESHOLD(5)
  ) equal_dut (
    .din  (equal_din),
    .dout (equal_dout)
  );

  initial begin
    default_din = 8'hA5;
    low_din = 8'h3C;
    equal_din = 8'h69;
    #1;

    if ((default_dout !== default_din) ||
        (low_dout !== low_din) ||
        (equal_dout !== equal_din) ||
        (default_dut.g_high.selected_inst.high_out !== default_dout) ||
        (low_dut.g_low.selected_inst.low_out !== low_dout) ||
        (equal_dut.g_high.selected_inst.high_out !== equal_dout)) begin
      $display("FAIL: ComparisonRouting");
    end else begin
      $display("PASS: ComparisonRouting");
    end

    $finish;
  end

endmodule
