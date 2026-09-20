module ParameterizedWireTb;

  localparam integer MINIMUM_WIDTH = 1;
  localparam integer AWKWARD_WIDTH = 13;
  localparam integer MAXIMUM_WIDTH = 64;

  reg  [7:0] default_din;
  wire [7:0] default_dout;
  reg  [MINIMUM_WIDTH-1:0] minimum_din;
  wire [MINIMUM_WIDTH-1:0] minimum_dout;
  reg  [AWKWARD_WIDTH-1:0] awkward_din;
  wire [AWKWARD_WIDTH-1:0] awkward_dout;
  reg  [MAXIMUM_WIDTH-1:0] maximum_din;
  wire [MAXIMUM_WIDTH-1:0] maximum_dout;

  ParameterizedWire default_dut (
    .din  (default_din),
    .dout (default_dout)
  );

  ParameterizedWire #(
    .WIDTH(MINIMUM_WIDTH)
  ) minimum_dut (
    .din  (minimum_din),
    .dout (minimum_dout)
  );

  ParameterizedWire #(
    .WIDTH(AWKWARD_WIDTH)
  ) awkward_dut (
    .din  (awkward_din),
    .dout (awkward_dout)
  );

  ParameterizedWire #(
    .WIDTH(MAXIMUM_WIDTH)
  ) maximum_dut (
    .din  (maximum_din),
    .dout (maximum_dout)
  );

  initial begin
    default_din = 8'hA5;
    minimum_din = 1'b1;
    awkward_din = 13'h15A5;
    maximum_din = 64'h0123_4567_89AB_CDEF;
    #1;

    if ((default_dout !== default_din) ||
        (minimum_dout !== minimum_din) ||
        (awkward_dout !== awkward_din) ||
        (maximum_dout !== maximum_din)) begin
      $display("FAIL: ParameterizedWire");
    end else begin
      $display("PASS: ParameterizedWire");
    end

    $finish;
  end

endmodule
