module RuntimeMux #(
  parameter integer WIDTH = 8
) (
  input  wire [WIDTH-1:0] data_false,
  input  wire [WIDTH-1:0] data_true,
  output reg [WIDTH-1:0] result,
  input  wire [0:0] sel
);

  always @* begin : p_runtime_mux
    if (sel == 1'b1) begin
      result = data_true;
    end else begin
      result = data_false;
    end
  end

endmodule
