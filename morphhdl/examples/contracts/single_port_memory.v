module SinglePortMemory #(
  parameter integer DEPTH = 5,
  parameter integer WIDTH = 8
) (
  input  wire [(morphhdl$ceil_log2(DEPTH, 1))-1:0] address,
  input  wire [0:0] clk,
  output reg [WIDTH-1:0] read_data,
  input  wire [0:0] read_enable,
  input  wire [WIDTH-1:0] write_data,
  input  wire [0:0] write_enable
);

  function integer morphhdl$ceil_log2;
    input integer value;
    input integer minimum_result;
    integer remaining;
    begin
      morphhdl$ceil_log2 = 0;
      for (remaining = value - 1; remaining > 0; remaining = remaining >> 1) begin
        morphhdl$ceil_log2 = morphhdl$ceil_log2 + 1;
      end
      if (morphhdl$ceil_log2 < minimum_result) begin
        morphhdl$ceil_log2 = minimum_result;
      end
    end
  endfunction

  reg [WIDTH-1:0] memory [0:DEPTH-1];

  always @(posedge clk) begin : p_memory
    if (address < DEPTH) begin
      if (read_enable == 1'b1) begin
        read_data <= memory[address];
      end
      if (write_enable == 1'b1) begin
        memory[address] <= write_data;
      end
    end else if (read_enable == 1'b1) begin
      read_data <= {WIDTH{1'b0}};
    end
  end

endmodule
