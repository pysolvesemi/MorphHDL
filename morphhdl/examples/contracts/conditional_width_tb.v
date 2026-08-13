module ConditionalWidthTb;

  localparam integer DEFAULT_WIDTH = 12;
  localparam integer NARROW_WIDTH = 4;
  localparam integer CUSTOM_WIDE_WIDTH = 15;
  localparam integer CUSTOM_NARROW_WIDTH = 7;

  reg  [DEFAULT_WIDTH-1:0] default_din;
  wire [DEFAULT_WIDTH-1:0] default_dout;
  reg  [NARROW_WIDTH-1:0] narrow_din;
  wire [NARROW_WIDTH-1:0] narrow_dout;
  reg  [CUSTOM_WIDE_WIDTH-1:0] custom_wide_din;
  wire [CUSTOM_WIDE_WIDTH-1:0] custom_wide_dout;
  reg  [CUSTOM_NARROW_WIDTH-1:0] custom_narrow_din;
  wire [CUSTOM_NARROW_WIDTH-1:0] custom_narrow_dout;

  ConditionalWidth default_dut (
    .din  (default_din),
    .dout (default_dout)
  );

  ConditionalWidth #(
    .WIDE(0)
  ) narrow_dut (
    .din  (narrow_din),
    .dout (narrow_dout)
  );

  ConditionalWidth #(
    .WIDE(1),
    .NARROW_WIDTH(5),
    .WIDE_WIDTH(CUSTOM_WIDE_WIDTH)
  ) custom_wide_dut (
    .din  (custom_wide_din),
    .dout (custom_wide_dout)
  );

  ConditionalWidth #(
    .WIDE(0),
    .NARROW_WIDTH(CUSTOM_NARROW_WIDTH),
    .WIDE_WIDTH(20)
  ) custom_narrow_dut (
    .din  (custom_narrow_din),
    .dout (custom_narrow_dout)
  );

  initial begin
    default_din = 12'hA5C;
    narrow_din = 4'h9;
    custom_wide_din = 15'h5A3C;
    custom_narrow_din = 7'h55;
    #1;

    if ((default_dout !== default_din) ||
        (narrow_dout !== narrow_din) ||
        (custom_wide_dout !== custom_wide_din) ||
        (custom_narrow_dout !== custom_narrow_din)) begin
      $display("FAIL: ConditionalWidth");
    end else begin
      $display("PASS: ConditionalWidth");
    end

    $finish;
  end

endmodule
