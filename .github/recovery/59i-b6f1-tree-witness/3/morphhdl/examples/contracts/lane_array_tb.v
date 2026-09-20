module LaneArrayTb;

  localparam integer DEFAULT_LANES = 4;
  localparam integer DEFAULT_DATA_WIDTH = 8;
  localparam integer MINIMUM_LANES = 1;
  localparam integer MINIMUM_DATA_WIDTH = 1;
  localparam integer AWKWARD_LANES = 3;
  localparam integer AWKWARD_DATA_WIDTH = 5;
  localparam integer LANES_ONLY_LANES = 3;
  localparam integer DATA_WIDTH_ONLY_DATA_WIDTH = 5;
  localparam integer DEFAULT_WIDTH = DEFAULT_LANES * DEFAULT_DATA_WIDTH;
  localparam integer MINIMUM_WIDTH = MINIMUM_LANES * MINIMUM_DATA_WIDTH;
  localparam integer AWKWARD_WIDTH = AWKWARD_LANES * AWKWARD_DATA_WIDTH;
  localparam integer LANES_ONLY_WIDTH = LANES_ONLY_LANES * DEFAULT_DATA_WIDTH;
  localparam integer DATA_WIDTH_ONLY_WIDTH = DEFAULT_LANES * DATA_WIDTH_ONLY_DATA_WIDTH;

  reg  [DEFAULT_WIDTH-1:0] default_data_in;
  wire [DEFAULT_WIDTH-1:0] default_data_out;
  reg  [MINIMUM_WIDTH-1:0] minimum_data_in;
  wire [MINIMUM_WIDTH-1:0] minimum_data_out;
  reg  [AWKWARD_WIDTH-1:0] awkward_data_in;
  wire [AWKWARD_WIDTH-1:0] awkward_data_out;
  reg  [LANES_ONLY_WIDTH-1:0] lanes_only_data_in;
  wire [LANES_ONLY_WIDTH-1:0] lanes_only_data_out;
  reg  [DATA_WIDTH_ONLY_WIDTH-1:0] data_width_only_data_in;
  wire [DATA_WIDTH_ONLY_WIDTH-1:0] data_width_only_data_out;

  LaneArray default_dut (
    .data_in  (default_data_in),
    .data_out (default_data_out)
  );

  LaneArray #(
    .DATA_WIDTH(MINIMUM_DATA_WIDTH),
    .LANES(MINIMUM_LANES)
  ) minimum_dut (
    .data_in  (minimum_data_in),
    .data_out (minimum_data_out)
  );

  LaneArray #(
    .DATA_WIDTH(AWKWARD_DATA_WIDTH),
    .LANES(AWKWARD_LANES)
  ) awkward_dut (
    .data_in  (awkward_data_in),
    .data_out (awkward_data_out)
  );

  LaneArray #(
    .LANES(LANES_ONLY_LANES)
  ) lanes_only_dut (
    .data_in  (lanes_only_data_in),
    .data_out (lanes_only_data_out)
  );

  LaneArray #(
    .DATA_WIDTH(DATA_WIDTH_ONLY_DATA_WIDTH)
  ) data_width_only_dut (
    .data_in  (data_width_only_data_in),
    .data_out (data_width_only_data_out)
  );

  initial begin
    default_data_in = 32'hA5C3_5A3C;
    minimum_data_in = 1'b1;
    awkward_data_in = 15'h5A3C;
    lanes_only_data_in = 24'hC3_5A_3C;
    data_width_only_data_in = 20'hA_5A3C;
    #1;

    if ((default_data_out !== default_data_in) ||
        (minimum_data_out !== minimum_data_in) ||
        (awkward_data_out !== awkward_data_in) ||
        (lanes_only_data_out !== lanes_only_data_in) ||
        (data_width_only_data_out !== data_width_only_data_in)) begin
      $display("FAIL: LaneArray");
    end else begin
      $display("PASS: LaneArray");
    end

    $finish;
  end

endmodule
