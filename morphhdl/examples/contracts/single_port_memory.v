module SinglePortMemory #(
  parameter integer DEPTH = 5,
  parameter integer WIDTH = 8
) (
  input  wire [((DEPTH <= 2) ? 1 : ((DEPTH <= 4) ? 2 : ((DEPTH <= 8) ? 3 : ((DEPTH <= 16) ? 4 : ((DEPTH <= 32) ? 5 : ((DEPTH <= 64) ? 6 : ((DEPTH <= 128) ? 7 : ((DEPTH <= 256) ? 8 : ((DEPTH <= 512) ? 9 : ((DEPTH <= 1024) ? 10 : ((DEPTH <= 2048) ? 11 : ((DEPTH <= 4096) ? 12 : ((DEPTH <= 8192) ? 13 : ((DEPTH <= 16384) ? 14 : ((DEPTH <= 32768) ? 15 : ((DEPTH <= 65536) ? 16 : ((DEPTH <= 131072) ? 17 : ((DEPTH <= 262144) ? 18 : ((DEPTH <= 524288) ? 19 : ((DEPTH <= 1048576) ? 20 : ((DEPTH <= 2097152) ? 21 : ((DEPTH <= 4194304) ? 22 : ((DEPTH <= 8388608) ? 23 : ((DEPTH <= 16777216) ? 24 : ((DEPTH <= 33554432) ? 25 : ((DEPTH <= 67108864) ? 26 : ((DEPTH <= 134217728) ? 27 : ((DEPTH <= 268435456) ? 28 : ((DEPTH <= 536870912) ? 29 : ((DEPTH <= 1073741824) ? 30 : 31))))))))))))))))))))))))))))))-1:0] address,
  input  wire [0:0] clk,
  output reg [WIDTH-1:0] read_data,
  input  wire [0:0] read_enable,
  input  wire [WIDTH-1:0] write_data,
  input  wire [0:0] write_enable
);

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
