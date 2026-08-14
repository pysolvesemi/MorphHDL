module ParameterizedCounter #(
  parameter integer LIMIT = 5
) (
  input  wire [0:0] clk,
  output reg [(morphhdl$ceil_log2(LIMIT, 1))-1:0] count,
  input  wire [0:0] enable,
  input  wire [0:0] reset
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

  always @(posedge clk) begin : p_counter
    if (reset == 1'b1) begin
      count <= {morphhdl$ceil_log2(LIMIT, 1){1'b0}};
    end else if (enable == 1'b1) begin
      if (count == LIMIT - 1) begin
        count <= {morphhdl$ceil_log2(LIMIT, 1){1'b0}};
      end else begin
        count <= count + 1'b1;
      end
    end
  end

endmodule
