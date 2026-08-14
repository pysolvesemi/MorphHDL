module ParameterizedCounter #(
  parameter integer LIMIT = 5
) (
  input  wire [0:0] clk,
  output reg [(clog2(LIMIT, 1))-1:0] count,
  input  wire [0:0] enable,
  input  wire [0:0] reset
);

  function integer clog2;
    input integer value;
    input integer minimum_result;
    integer remaining;
    begin
      clog2 = 0;
      for (remaining = value - 1; remaining > 0; remaining = remaining >> 1) begin
        clog2 = clog2 + 1;
      end
      if (clog2 < minimum_result) begin
        clog2 = minimum_result;
      end
    end
  endfunction

  always @(posedge clk) begin : p_counter
    if (reset == 1'b1) begin
      count <= {clog2(LIMIT, 1){1'b0}};
    end else if (enable == 1'b1) begin
      if (count == LIMIT - 1) begin
        count <= {clog2(LIMIT, 1){1'b0}};
      end else begin
        count <= count + 1'b1;
      end
    end
  end

endmodule
