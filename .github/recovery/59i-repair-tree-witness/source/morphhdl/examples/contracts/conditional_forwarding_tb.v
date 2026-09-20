module ConditionalForwardingTb;

  localparam integer DEFAULT_WIDTH = 8;
  localparam integer ENABLED_WIDTH = 5;
  localparam integer DISABLED_WIDTH = 13;

  reg  [DEFAULT_WIDTH-1:0] default_din;
  wire [DEFAULT_WIDTH-1:0] default_dout;
  reg  [DEFAULT_WIDTH-1:0] disabled_din;
  wire [DEFAULT_WIDTH-1:0] disabled_dout;
  reg  [ENABLED_WIDTH-1:0] enabled_width_din;
  wire [ENABLED_WIDTH-1:0] enabled_width_dout;
  reg  [DISABLED_WIDTH-1:0] disabled_width_din;
  wire [DISABLED_WIDTH-1:0] disabled_width_dout;

  ConditionalForwarding default_dut (
    .din  (default_din),
    .dout (default_dout)
  );

  ConditionalForwarding #(
    .ENABLE(0)
  ) disabled_dut (
    .din  (disabled_din),
    .dout (disabled_dout)
  );

  ConditionalForwarding #(
    .ENABLE(1),
    .WIDTH(ENABLED_WIDTH)
  ) enabled_width_dut (
    .din  (enabled_width_din),
    .dout (enabled_width_dout)
  );

  ConditionalForwarding #(
    .ENABLE(0),
    .WIDTH(DISABLED_WIDTH)
  ) disabled_width_dut (
    .din  (disabled_width_din),
    .dout (disabled_width_dout)
  );

  initial begin
    default_din = 8'hA5;
    disabled_din = 8'h3C;
    enabled_width_din = 5'h15;
    disabled_width_din = 13'h15A3;
    #1;

    if ((default_dout !== default_din) ||
        (disabled_dout !== disabled_din) ||
        (enabled_width_dout !== enabled_width_din) ||
        (disabled_width_dout !== disabled_width_din)) begin
      $display("FAIL: ConditionalForwarding");
    end else begin
      $display("PASS: ConditionalForwarding");
    end

    $finish;
  end

endmodule
