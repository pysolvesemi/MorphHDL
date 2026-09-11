`timescale 1ns/1ps

// Compare the actual native backend output before and after WA-09.  The
// simulation intentionally retains X and Z inputs; the formal branch proves
// the same relationship for every two-state input accepted by Yosys SAT.
`ifdef FORMAL
module NamedWireExpressionNativeMiter(
    input wire [7:0] a,
    input wire [7:0] b,
    input wire c,
    input wire d,
    output wire ok
);
  wire [7:0] reference_result;
  wire [7:0] candidate_result;
  wire reference_flag;
  wire candidate_flag;

  NamedWireExpressionNativeReference reference_dut(
    .a(a), .b(b), .result(reference_result),
    .c(c), .d(d), .flagResult(reference_flag)
  );
  NamedWireExpressionNativeCandidate candidate_dut(
    .a(a), .b(b), .result(candidate_result),
    .c(c), .d(d), .flagResult(candidate_flag)
  );

  assign ok = (reference_result == candidate_result) &&
              (reference_flag == candidate_flag);
endmodule
`else
module NamedWireExpressionNativeTb;
  reg [7:0] a;
  reg [7:0] b;
  reg c;
  reg d;
  wire [7:0] reference_result;
  wire [7:0] candidate_result;
  wire reference_flag;
  wire candidate_flag;
  integer pattern;
  integer bit_index;

  NamedWireExpressionNativeReference reference_dut(
    .a(a), .b(b), .result(reference_result),
    .c(c), .d(d), .flagResult(reference_flag)
  );
  NamedWireExpressionNativeCandidate candidate_dut(
    .a(a), .b(b), .result(candidate_result),
    .c(c), .d(d), .flagResult(candidate_flag)
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
    // Every bit sees every ordered four-state pair repeatedly, while adjacent
    // bits carry different states.  This exercises vector XOR and the separate
    // scalar XOR without constraining unknowns away.
    for (pattern = 0; pattern < 256; pattern = pattern + 1) begin
      for (bit_index = 0; bit_index < 8; bit_index = bit_index + 1) begin
        a[bit_index] = four_state((pattern + bit_index) & 3);
        b[bit_index] = four_state(((pattern >> 2) + (3 * bit_index)) & 3);
      end
      c = four_state((pattern >> 4) & 3);
      d = four_state((pattern >> 6) & 3);
      #1;
      if (reference_result !== candidate_result ||
          reference_flag !== candidate_flag) begin
        $display("WA09_NATIVE_FAIL pattern=%0d a=%b b=%b c=%b d=%b before=%b/%b after=%b/%b",
                 pattern, a, b, c, d, reference_result, reference_flag,
                 candidate_result, candidate_flag);
        $finish;
      end
    end
    $display("WA09_NATIVE_PASS patterns=256 outputs=9 candidate=1");
    $finish;
  end
endmodule
`endif
