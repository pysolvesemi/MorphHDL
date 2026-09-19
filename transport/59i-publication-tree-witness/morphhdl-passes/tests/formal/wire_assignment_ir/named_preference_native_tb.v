`timescale 1ns/1ps

// Exercise the actual native direct-alias preference writeback. Four-state
// simulation retains X/Z behavior; formal proves all two-state inputs against
// the separately emitted pre-pass module.
`ifdef FORMAL
module NamedWirePreferenceNativeMiter(
    input wire a,
    input wire b,
    output wire ok
);
  wire [4:0] reference_value;
  wire [4:0] candidate_value;

  NamedWirePreferenceNativeReference reference_dut(
    .a(a), .b(b),
    .reverseResult(reference_value[0]),
    .forwardResult(reference_value[1]),
    .lexicalResult(reference_value[2]),
    .unnamedResult(reference_value[3]),
    .protectedResult(reference_value[4])
  );
  NamedWirePreferenceNativeCandidate candidate_dut(
    .a(a), .b(b),
    .reverseResult(candidate_value[0]),
    .forwardResult(candidate_value[1]),
    .lexicalResult(candidate_value[2]),
    .unnamedResult(candidate_value[3]),
    .protectedResult(candidate_value[4])
  );

  assign ok = reference_value == candidate_value;
endmodule
`else
module NamedWirePreferenceNativeTb;
  reg a;
  reg b;
  wire [4:0] reference_value;
  wire [4:0] candidate_value;
  integer pattern;

  NamedWirePreferenceNativeReference reference_dut(
    .a(a), .b(b),
    .reverseResult(reference_value[0]),
    .forwardResult(reference_value[1]),
    .lexicalResult(reference_value[2]),
    .unnamedResult(reference_value[3]),
    .protectedResult(reference_value[4])
  );
  NamedWirePreferenceNativeCandidate candidate_dut(
    .a(a), .b(b),
    .reverseResult(candidate_value[0]),
    .forwardResult(candidate_value[1]),
    .lexicalResult(candidate_value[2]),
    .unnamedResult(candidate_value[3]),
    .protectedResult(candidate_value[4])
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
      if (reference_value !== candidate_value) begin
        $display("WA09_PREFERENCE_NATIVE_FAIL a=%b b=%b before=%b after=%b",
                 a, b, reference_value, candidate_value);
        $finish;
      end
    end
    $display("WA09_PREFERENCE_NATIVE_PASS patterns=16 outputs=5 candidate=1");
    $finish;
  end
endmodule
`endif
