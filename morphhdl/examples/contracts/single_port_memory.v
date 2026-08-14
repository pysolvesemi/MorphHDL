module SinglePortMemory #(
  parameter integer DEPTH = 5,
  parameter integer WIDTH = 8
) (
  input  wire [2:0] address,
  input  wire [0:0] clk,
  output reg [WIDTH-1:0] read_data,
  input  wire [WIDTH-1:0] write_data,
  input  wire [0:0] write_enable
);

  reg [WIDTH-1:0] memory [0:DEPTH-1];

  always @(posedge clk) begin : p_memory
    if (address < DEPTH) begin
      read_data <= memory[address];
      if (write_enable == 1'b1) begin
        memory[address] <= write_data;
      end
    end else begin
      read_data <= {WIDTH{1'b0}};
    end
  end

endmodule
