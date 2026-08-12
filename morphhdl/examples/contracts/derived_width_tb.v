module DerivedWidthTb;

  localparam integer DEFAULT_WIDTH = 35;
  localparam integer MINIMUM_WIDTH = 4;
  localparam integer AWKWARD_WIDTH = 18;
  localparam integer LANES_ONLY_WIDTH = 27;
  localparam integer DATA_WIDTH_ONLY_WIDTH = 23;

  reg  [DEFAULT_WIDTH-1:0] default_din;
  wire [DEFAULT_WIDTH-1:0] default_dout;
  reg  [MINIMUM_WIDTH-1:0] minimum_din;
  wire [MINIMUM_WIDTH-1:0] minimum_dout;
  reg  [AWKWARD_WIDTH-1:0] awkward_din;
  wire [AWKWARD_WIDTH-1:0] awkward_dout;
  reg  [LANES_ONLY_WIDTH-1:0] lanes_only_din;
  wire [LANES_ONLY_WIDTH-1:0] lanes_only_dout;
  reg  [DATA_WIDTH_ONLY_WIDTH-1:0] data_width_only_din;
  wire [DATA_WIDTH_ONLY_WIDTH-1:0] data_width_only_dout;

  DerivedWidth default_dut (
    .din  (default_din),
    .dout (default_dout)
  );

  DerivedWidth #(
    .DATA_WIDTH(1),
    .LANES(1)
  ) minimum_dut (
    .din  (minimum_din),
    .dout (minimum_dout)
  );

  DerivedWidth #(
    .DATA_WIDTH(5),
    .LANES(3)
  ) awkward_dut (
    .din  (awkward_din),
    .dout (awkward_dout)
  );

  DerivedWidth #(
    .LANES(3)
  ) lanes_only_dut (
    .din  (lanes_only_din),
    .dout (lanes_only_dout)
  );

  DerivedWidth #(
    .DATA_WIDTH(5)
  ) data_width_only_dut (
    .din  (data_width_only_din),
    .dout (data_width_only_dout)
  );

  initial begin
    default_din = {DEFAULT_WIDTH{1'b1}};
    minimum_din = {MINIMUM_WIDTH{1'b1}};
    awkward_din = {AWKWARD_WIDTH{1'b1}};
    lanes_only_din = {LANES_ONLY_WIDTH{1'b1}};
    data_width_only_din = {DATA_WIDTH_ONLY_WIDTH{1'b1}};
    #1;

    if ((default_dout !== default_din) ||
        (minimum_dout !== minimum_din) ||
        (awkward_dout !== awkward_din) ||
        (lanes_only_dout !== lanes_only_din) ||
        (data_width_only_dout !== data_width_only_din)) begin
      $display("FAIL: DerivedWidth");
    end else begin
      $display("PASS: DerivedWidth");
    end

    $finish;
  end

endmodule
