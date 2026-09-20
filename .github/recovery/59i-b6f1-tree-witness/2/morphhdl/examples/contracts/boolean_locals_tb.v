module BooleanLocalsTb;

  reg  [7:0] default_din;
  wire [7:0] default_dout;
  reg  [7:0] disabled_din;
  wire [7:0] disabled_dout;
  reg  [7:0] below_din;
  wire [7:0] below_dout;
  reg  [7:0] equal_din;
  wire [7:0] equal_dout;

  BooleanLocals default_dut (
    .din  (default_din),
    .dout (default_dout)
  );

  BooleanLocals #(
    .ENABLE(0)
  ) disabled_dut (
    .din  (disabled_din),
    .dout (disabled_dout)
  );

  BooleanLocals #(
    .ENABLE(1),
    .WIDTH(7),
    .LIMIT(8)
  ) below_dut (
    .din  (below_din),
    .dout (below_dout)
  );

  BooleanLocals #(
    .ENABLE(1),
    .WIDTH(8),
    .LIMIT(8)
  ) equal_dut (
    .din  (equal_din),
    .dout (equal_dout)
  );

  initial begin
    default_din = 8'hA5;
    disabled_din = 8'h69;
    below_din = 8'h0F;
    equal_din = 8'hC3;
    #1;

    if ((default_dout !== default_din) ||
        (disabled_dout !== disabled_din) ||
        (below_dout !== below_din) ||
        (equal_dout !== equal_din) ||
        (default_dut.route_inst.g_high.selected_inst.high_out !== default_dout) ||
        (disabled_dut.route_inst.g_low.selected_inst.low_out !== disabled_dout) ||
        (below_dut.route_inst.g_low.selected_inst.low_out !== below_dout) ||
        (equal_dut.route_inst.g_high.selected_inst.high_out !== equal_dout)) begin
      $display("FAIL: BooleanLocals");
    end else begin
      $display("PASS: BooleanLocals");
    end

    $finish;
  end

endmodule
