`timescale 1ns/1ps
module ConstantOperandNativeTb;
  reg a, b;
  wire [7:0] reference_values, candidate_values;
  integer pattern;
  ConstantOperandNativeReference reference_dut(
    .a(a), .b(b), .y0(reference_values[0]), .y1(reference_values[1]),
    .y2(reference_values[2]), .y3(reference_values[3]), .y4(reference_values[4]),
    .y5(reference_values[5]), .y6(reference_values[6]), .y7(reference_values[7])
  );
  ConstantOperandNativeCandidate candidate_dut(
    .a(a), .b(b), .y0(candidate_values[0]), .y1(candidate_values[1]),
    .y2(candidate_values[2]), .y3(candidate_values[3]), .y4(candidate_values[4]),
    .y5(candidate_values[5]), .y6(candidate_values[6]), .y7(candidate_values[7])
  );
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
      if (reference_values !== candidate_values) begin
        $display("WA07A_NATIVE_FAIL a=%b b=%b before=%b after=%b", a, b, reference_values, candidate_values);
        $finish;
      end
    end
    $display("WA07A_NATIVE_PASS patterns=16 outputs=8");
    $finish;
  end
endmodule
