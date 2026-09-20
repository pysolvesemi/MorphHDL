module DerivedWidthTb;

  localparam integer DEFAULT_WIDTH = 37;
  localparam integer MINIMUM_WIDTH = 4;
  localparam integer AWKWARD_WIDTH = 20;
  localparam integer LANES_ONLY_WIDTH = 29;
  localparam integer DATA_WIDTH_ONLY_WIDTH = 25;
  localparam integer DYNAMIC_MIN_WIDTH = 7;

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
  reg  [DYNAMIC_MIN_WIDTH-1:0] dynamic_min_din;
  wire [DYNAMIC_MIN_WIDTH-1:0] dynamic_min_dout;

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

  DerivedWidth #(
    .DATA_WIDTH(2),
    .LANES(2)
  ) dynamic_min_dut (
    .din  (dynamic_min_din),
    .dout (dynamic_min_dout)
  );

  initial begin
    default_din = {DEFAULT_WIDTH{1'b1}};
    minimum_din = {MINIMUM_WIDTH{1'b1}};
    awkward_din = {AWKWARD_WIDTH{1'b1}};
    lanes_only_din = {LANES_ONLY_WIDTH{1'b1}};
    data_width_only_din = {DATA_WIDTH_ONLY_WIDTH{1'b1}};
    dynamic_min_din = {DYNAMIC_MIN_WIDTH{1'b1}};
    #1;

    if ((default_dout !== default_din) ||
        (minimum_dout !== minimum_din) ||
        (awkward_dout !== awkward_din) ||
        (lanes_only_dout !== lanes_only_din) ||
        (data_width_only_dout !== data_width_only_din) ||
        (dynamic_min_dout !== dynamic_min_din)) begin
      $display("FAIL: DerivedWidth");
    end else begin
      $display("PASS: DerivedWidth");
    end

    $finish;
  end

endmodule
