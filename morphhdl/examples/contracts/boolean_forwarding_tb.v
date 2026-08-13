module BooleanForwardingTb;

  reg  [7:0] default_high_in;
  reg  [7:0] default_low_in;
  wire [7:0] default_dout;
  reg  [7:0] disabled_high_in;
  reg  [7:0] disabled_low_in;
  wire [7:0] disabled_dout;
  reg  [7:0] below_high_in;
  reg  [7:0] below_low_in;
  wire [7:0] below_dout;
  reg  [7:0] equal_high_in;
  reg  [7:0] equal_low_in;
  wire [7:0] equal_dout;

  BooleanForwarding default_dut (
    .high_in (default_high_in),
    .low_in  (default_low_in),
    .dout    (default_dout)
  );

  BooleanForwarding #(
    .ENABLE(0)
  ) disabled_dut (
    .high_in (disabled_high_in),
    .low_in  (disabled_low_in),
    .dout    (disabled_dout)
  );

  BooleanForwarding #(
    .ENABLE(1),
    .WIDTH(3),
    .OFFSET(1),
    .LIMIT(5)
  ) below_dut (
    .high_in (below_high_in),
    .low_in  (below_low_in),
    .dout    (below_dout)
  );

  BooleanForwarding #(
    .ENABLE(1),
    .WIDTH(4),
    .OFFSET(1),
    .LIMIT(5)
  ) equal_dut (
    .high_in (equal_high_in),
    .low_in  (equal_low_in),
    .dout    (equal_dout)
  );

  initial begin
    default_high_in = 8'hA5;
    default_low_in = 8'h3C;
    disabled_high_in = 8'h96;
    disabled_low_in = 8'h69;
    below_high_in = 8'hF0;
    below_low_in = 8'h0F;
    equal_high_in = 8'hC3;
    equal_low_in = 8'h5A;
    #1;

    if ((default_dout !== default_high_in) ||
        (disabled_dout !== disabled_low_in) ||
        (below_dout !== below_low_in) ||
        (equal_dout !== equal_high_in) ||
        (default_dut.route_inst.g_high.selected_inst.high_out !== default_dout) ||
        (disabled_dut.route_inst.g_low.selected_inst.low_out !== disabled_dout) ||
        (below_dut.route_inst.g_low.selected_inst.low_out !== below_dout) ||
        (equal_dut.route_inst.g_high.selected_inst.high_out !== equal_dout)) begin
      $display("FAIL: BooleanForwarding");
    end else begin
      $display("PASS: BooleanForwarding");
    end

    $finish;
  end

endmodule
