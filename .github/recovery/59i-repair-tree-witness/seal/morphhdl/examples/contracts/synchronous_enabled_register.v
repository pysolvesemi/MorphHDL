module SynchronousEnabledRegister #(
  parameter integer WIDTH = 8
) (
  input  wire [0:0] clk,
  input  wire [WIDTH-1:0] data_in,
  output reg [WIDTH-1:0] data_out,
  input  wire [0:0] enable,
  input  wire [0:0] reset
);

  always @(posedge clk) begin : p_sync_enabled_register
    if (reset == 1'b1) begin
      data_out <= {WIDTH{1'b0}};
    end else if (enable == 1'b1) begin
      data_out <= data_in;
    end
  end

endmodule
