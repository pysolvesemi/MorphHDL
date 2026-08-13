module CaseRoutingTb;

  reg  [7:0] default_din;
  wire [7:0] default_dout;
  reg  [7:0] one_din;
  wire [7:0] one_dout;
  reg  [7:0] offset_din;
  wire [7:0] offset_dout;
  reg  [7:0] unmatched_din;
  wire [7:0] unmatched_dout;

  CaseRouting default_dut (
    .din  (default_din),
    .dout (default_dout)
  );

  CaseRouting #(
    .MODE(1)
  ) one_dut (
    .din  (one_din),
    .dout (one_dout)
  );

  CaseRouting #(
    .MODE(0),
    .OFFSET(1)
  ) offset_dut (
    .din  (offset_din),
    .dout (offset_dout)
  );

  CaseRouting #(
    .MODE(3)
  ) unmatched_dut (
    .din  (unmatched_din),
    .dout (unmatched_dout)
  );

  initial begin
    default_din = 8'hA5;
    one_din = 8'h3C;
    offset_din = 8'h96;
    unmatched_din = 8'h69;
    #1;

    if ((default_dout !== default_din) ||
        (one_dout !== one_din) ||
        (offset_dout !== offset_din) ||
        (unmatched_dout !== unmatched_din) ||
        (default_dut.g_zero.selected_inst.zero_out !== default_dout) ||
        (one_dut.g_one.selected_inst.one_out !== one_dout) ||
        (offset_dut.g_one.selected_inst.one_out !== offset_dout) ||
        (unmatched_dut.g_default.selected_inst.default_out !== unmatched_dout)) begin
      $display("FAIL: CaseRouting");
    end else begin
      $display("PASS: CaseRouting");
    end

    $finish;
  end

endmodule
