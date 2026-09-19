// Compare both native-emitted candidates directly with the pre-ALL-passes RTL.
`ifdef FORMAL
module BooleanTernaryNativeMiter(input wire a, b, output wire ok);
  wire [7:0] reference_value, ternary_value, all_value;
  BooleanTernaryNativeReference reference_dut(
    .a(a), .b(b), .y0(reference_value[0]), .y1(reference_value[1]),
    .y2(reference_value[2]), .y3(reference_value[3]),
    .y4(reference_value[4]), .y5(reference_value[5]),
    .y6(reference_value[6]), .y7(reference_value[7]));
  BooleanTernaryNativeCandidate ternary_dut(
    .a(a), .b(b), .y0(ternary_value[0]), .y1(ternary_value[1]),
    .y2(ternary_value[2]), .y3(ternary_value[3]),
    .y4(ternary_value[4]), .y5(ternary_value[5]),
    .y6(ternary_value[6]), .y7(ternary_value[7]));
  BooleanTernaryNativeAll all_dut(
    .a(a), .b(b), .y0(all_value[0]), .y1(all_value[1]),
    .y2(all_value[2]), .y3(all_value[3]),
    .y4(all_value[4]), .y5(all_value[5]),
    .y6(all_value[6]), .y7(all_value[7]));
  assign ok = (reference_value == ternary_value) && (reference_value == all_value);
endmodule
`else
module BooleanTernaryNativeTb;
  reg a, b;
  integer pattern;
  wire [7:0] reference_value, ternary_value, all_value;
  BooleanTernaryNativeReference reference_dut(
    .a(a), .b(b), .y0(reference_value[0]), .y1(reference_value[1]),
    .y2(reference_value[2]), .y3(reference_value[3]),
    .y4(reference_value[4]), .y5(reference_value[5]),
    .y6(reference_value[6]), .y7(reference_value[7]));
  BooleanTernaryNativeCandidate ternary_dut(
    .a(a), .b(b), .y0(ternary_value[0]), .y1(ternary_value[1]),
    .y2(ternary_value[2]), .y3(ternary_value[3]),
    .y4(ternary_value[4]), .y5(ternary_value[5]),
    .y6(ternary_value[6]), .y7(ternary_value[7]));
  BooleanTernaryNativeAll all_dut(
    .a(a), .b(b), .y0(all_value[0]), .y1(all_value[1]),
    .y2(all_value[2]), .y3(all_value[3]),
    .y4(all_value[4]), .y5(all_value[5]),
    .y6(all_value[6]), .y7(all_value[7]));
  function four_state;
    input integer digit;
    begin
      case (digit)
        0: four_state = 1'b0;
        1: four_state = 1'b1;
        2: four_state = 1'bx;
        3: four_state = 1'bz;
      endcase
    end
  endfunction
  initial begin
    for (pattern = 0; pattern < 16; pattern = pattern + 1) begin
      a = four_state(pattern & 3);
      b = four_state((pattern >> 2) & 3);
      #1;
      if (reference_value !== ternary_value || reference_value !== all_value) begin
        $display("WA07B_NATIVE_FAIL a=%b b=%b before=%b ternary=%b all=%b", a, b, reference_value, ternary_value, all_value);
        $finish;
      end
    end
    $display("WA07B_NATIVE_PASS patterns=16 outputs=8 candidates=2");
    $finish;
  end
endmodule
`endif
