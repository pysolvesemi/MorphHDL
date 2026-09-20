module SynchronousStreamM2sPipe #(
  parameter integer WIDTH = 8
) (
  input  wire [0:0] clk,
  output reg [WIDTH-1:0] pop_data,
  input  wire [0:0] pop_ready,
  output reg [0:0] pop_valid,
  input  wire [WIDTH-1:0] push_data,
  output wire [0:0] push_ready,
  input  wire [0:0] push_valid,
  input  wire [0:0] reset
);

  assign push_ready = pop_ready || !pop_valid;

  always @(posedge clk) begin : p_m2s_pipe
    if (reset == 1'b1) begin
      pop_valid <= 1'b0;
    end else if (push_ready == 1'b1) begin
      pop_valid <= push_valid;
    end
    if (push_ready == 1'b1) begin
      pop_data <= push_data;
    end
  end

endmodule
