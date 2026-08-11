module LaneArrayTb;

  localparam integer DEFAULT_LANES = 4;
  localparam integer DEFAULT_DATA_WIDTH = 8;
  localparam integer OVERRIDE_LANES = 3;
  localparam integer OVERRIDE_DATA_WIDTH = 5;
  localparam integer DEFAULT_FLAT_WIDTH = DEFAULT_LANES * DEFAULT_DATA_WIDTH;
  localparam integer OVERRIDE_FLAT_WIDTH = OVERRIDE_LANES * OVERRIDE_DATA_WIDTH;

  reg  [DEFAULT_FLAT_WIDTH-1:0] default_data_in;
  wire [DEFAULT_FLAT_WIDTH-1:0] default_data_out;
  reg  [OVERRIDE_FLAT_WIDTH-1:0] override_data_in;
  wire [OVERRIDE_FLAT_WIDTH-1:0] override_data_out;

  LaneArray default_dut (
    .data_in  (default_data_in),
    .data_out (default_data_out)
  );

  LaneArray #(
    .LANES(OVERRIDE_LANES),
    .DATA_WIDTH(OVERRIDE_DATA_WIDTH)
  ) override_dut (
    .data_in  (override_data_in),
    .data_out (override_data_out)
  );

  initial begin
    default_data_in = 32'hA5C3_5A3C;
    override_data_in = {OVERRIDE_FLAT_WIDTH{1'b1}};
    #1;

    if ((default_data_out !== default_data_in) ||
        (override_data_out !== override_data_in)) begin
      $display("FAIL: LaneArray");
    end else begin
      $display("PASS: LaneArray");
    end

    $finish;
  end

endmodule
