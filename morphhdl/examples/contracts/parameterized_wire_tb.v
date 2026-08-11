module ParameterizedWireTb;

  localparam integer OVERRIDE_WIDTH = 13;

  reg  [7:0] default_din;
  wire [7:0] default_dout;
  reg  [OVERRIDE_WIDTH-1:0] override_din;
  wire [OVERRIDE_WIDTH-1:0] override_dout;

  ParameterizedWire default_dut (
    .din  (default_din),
    .dout (default_dout)
  );

  ParameterizedWire #(
    .WIDTH(OVERRIDE_WIDTH)
  ) override_dut (
    .din  (override_din),
    .dout (override_dout)
  );

  initial begin
    default_din = 8'hA5;
    override_din = {OVERRIDE_WIDTH{1'b1}};
    #1;

    if ((default_dout !== default_din) ||
        (override_dout !== override_din)) begin
      $display("FAIL: ParameterizedWire");
    end else begin
      $display("PASS: ParameterizedWire");
    end

    $finish;
  end

endmodule
