// Hand-authored semantic reproducer, NOT captured SpinalHDL output.
// Current native emission cuts SInt subexpressions into temporary wires.
// This small independent fixture freezes the nested-cast target rule for 60d.
module SignedCastNesting(
  input wire [7:0] value,
  output wire [10:0] nested_result,
  output wire [10:0] reference_result,
  output wire equal_result
);
  assign nested_result = $signed($signed(value));
  assign reference_result = $signed(value);
  assign equal_result = nested_result == reference_result;
endmodule

`ifndef SYNTHESIS
module SignedCastNestingTb;
  reg [7:0] value;
  wire [10:0] nested_result, reference_result;
  wire equal_result;
  integer index;
  SignedCastNesting dut(value, nested_result, reference_result, equal_result);
  initial begin
    for (index = 0; index < 256; index = index + 1) begin
      value = index;
      #1;
      if (equal_result !== 1'b1 || nested_result !== {{3{value[7]}},value}) begin
        $display("FAIL:NESTED_SIGNED_CAST"); $finish;
      end
    end
    $display("NESTED_SIGNED_CAST_OK"); $finish;
  end
endmodule
`endif
