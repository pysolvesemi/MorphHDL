`timescale 1ns/1ps

module GeneralExpressionInliningTb;
  parameter PPC4 = 0;
  localparam PPC = PPC4 * 3 + 1;
  reg [15:0] word, other;
  reg [12:0] total, index;
  reg [7:0] narrow;
  reg [8:0] unsignedWide;
  reg signed [7:0] signedA, signedB;
  reg choose, load, clk, reset;
  reg [PPC-1:0] laneMaskIn;
  wire constantOK, extensionOK, subtractionOK, signedOK;
  wire [15:0] constantMux;
  wire [17:0] extensionSum, muxResult;
  wire [12:0] subtractionMux, keptResult, guardedResult;
  wire [7:0] wrappedAdd, wrappedSub;
  wire [8:0] widenedAdd, widenedSub;
  wire signed [11:0] signedExtended;
  wire signed [9:0] mixedSigned;
  wire [4:0] truncated;
  wire [6:0] sliced;
  wire [11:0] registerResult;
  wire [PPC-1:0] laneMaskOut;

  GeneralExpressionInlining #(.PPC4(PPC4)) dut (
    .word(word), .other(other), .total(total), .index(index), .narrow(narrow),
    .unsignedWide(unsignedWide), .signedA(signedA), .signedB(signedB),
    .choose(choose), .load(load), .clk(clk), .reset(reset),
    .laneMaskIn(laneMaskIn), .constantOK(constantOK), .extensionOK(extensionOK),
    .subtractionOK(subtractionOK), .signedOK(signedOK), .constantMux(constantMux),
    .extensionSum(extensionSum), .muxResult(muxResult),
    .subtractionMux(subtractionMux), .wrappedAdd(wrappedAdd), .wrappedSub(wrappedSub),
    .widenedAdd(widenedAdd), .widenedSub(widenedSub), .signedExtended(signedExtended),
    .mixedSigned(mixedSigned), .truncated(truncated), .sliced(sliced),
    .registerResult(registerResult), .keptResult(keptResult),
    .guardedResult(guardedResult), .laneMaskOut(laneMaskOut)
  );

  integer checks, i, seed;
  reg [12:0] expectedDifference;
  reg [7:0] expectedAdd, expectedSub;
  reg signed [7:0] expectedSignedDifference;
  reg signed [11:0] expectedSignedExtended;
  reg signed [9:0] expectedMixed;
  reg [17:0] expectedSum, expectedMux;
  reg [11:0] expectedState;

  task fail;
    input [255:0] reason;
    begin
      $display("WA10_FAIL sample=%0d reason=%0s word=%h other=%h total=%h narrow=%h signedA=%h", checks, reason, word, other, total, narrow, signedA);
      $fatal(1);
    end
  endtask

  task check;
    begin
      clk = 0;
      expectedDifference = total - 13'd1;
      expectedAdd = narrow + 8'd1;
      expectedSub = narrow - 8'd1;
      expectedSignedDifference = signedA - 8'sd1;
      expectedSignedExtended = {{4{expectedSignedDifference[7]}}, expectedSignedDifference};
      expectedMixed = $signed({{2{signedA[7]}}, signedA}) + $signed({1'b0, unsignedWide});
      expectedSum = {2'b0, word} + {2'b0, other};
      expectedMux = (choose ? {2'b0, word} : {2'b0, other}) + 18'd1;
      #1;
      if (constantOK !== (word >= 16'd1)) fail("constant comparison");
      if (constantMux !== (choose ? 16'd1 : other)) fail("constant ternary");
      if (extensionOK !== ({2'b0, word} <= 18'd65535)) fail("extension comparison");
      if (extensionSum !== expectedSum) fail("18-bit extension/add");
      if (subtractionOK !== (index == expectedDifference)) fail("13-bit subtract compare");
      if (subtractionMux !== (choose ? expectedDifference : index)) fail("subtract ternary");
      if (wrappedAdd !== expectedAdd || widenedAdd !== {1'b0, expectedAdd}) fail("add truncation fence");
      if (wrappedSub !== expectedSub || widenedSub !== {1'b0, expectedSub}) fail("sub truncation fence");
      if (signedOK !== ($signed(signedB) < $signed(expectedSignedDifference))) fail("signed comparison");
      if (signedExtended !== expectedSignedExtended) fail("signed extension fence");
      if (mixedSigned !== expectedMixed) fail("mixed signed/unsigned");
      if (truncated !== expectedSum[4:0]) fail("explicit final truncation");
      if (sliced !== expectedDifference[8:2]) fail("selected expression");
      if (muxResult !== expectedMux) fail("nested ternary arithmetic");
      if (keptResult !== expectedDifference || guardedResult !== expectedDifference) fail("protected expressions");
      if (laneMaskOut !== laneMaskIn) fail("PPC4 identity");
`ifdef WA10_MUTATE_REFERENCE
      // A deliberately wrong nine-bit subtract oracle must be rejected by the
      // zero-input witness. This tests the same assertion/reporting harness.
      if (checks == 1 && widenedSub !== ({1'b0, narrow} - 9'd1)) fail("mutation detected");
`endif
      if (reset) expectedState = 0;
      else if (load) expectedState = expectedSum[11:0];
      clk = 1;
      #1;
      if (registerResult !== expectedState) fail("conditional register update");
      $display("TRACE %0d %b %b %b %b %h %h %h %h %h %h %h %h %h %h %h %h %h %h %h %h",
        checks, constantOK, extensionOK, subtractionOK, signedOK, constantMux,
        extensionSum, muxResult, subtractionMux, wrappedAdd, wrappedSub,
        widenedAdd, widenedSub, signedExtended, mixedSigned, truncated, sliced,
        registerResult, keptResult, guardedResult, laneMaskOut);
      clk = 0;
      #1;
      checks = checks + 1;
    end
  endtask

  function [15:0] boundary;
    input integer value;
    begin
      case (value % 16)
        0: boundary = 0; 1: boundary = 1; 2: boundary = 2;
        3: boundary = 1079; 4: boundary = 1080; 5: boundary = 1081;
        6: boundary = 1919; 7: boundary = 1920; 8: boundary = 1921;
        9: boundary = 4095; 10: boundary = 4096; 11: boundary = 4097;
        12: boundary = 32767; 13: boundary = 32768;
        14: boundary = 65534; default: boundary = 65535;
      endcase
    end
  endfunction

  initial begin
    checks = 0; seed = 32'h1b10cafe; clk = 0; reset = 1;
    word = 0; other = 0; total = 0; index = 13'h1fff; narrow = 0;
    unsignedWide = 0; signedA = -128; signedB = 127; choose = 1; load = 0;
    laneMaskIn = 0; expectedState = 0;
    check;
    reset = 0;
    check;
    for (i = 0; i < 256; i = i + 1) begin
      word = boundary(i); other = boundary(i / 16);
      total = boundary(i); index = total - 13'd1;
      narrow = i; unsignedWide = i * 2 + 1;
      signedA = i; signedB = 255 - i; choose = i[0]; load = i[1];
      laneMaskIn = i; check;
    end
    for (i = 0; i < 512; i = i + 1) begin
      word = $random(seed); other = $random(seed); total = $random(seed);
      index = $random(seed); narrow = $random(seed); unsignedWide = $random(seed);
      signedA = $random(seed); signedB = $random(seed); choose = $random(seed);
      load = $random(seed); laneMaskIn = $random(seed); check;
    end
    // Complete and partial four-state operands, unknown ternary selectors,
    // and unknown register enables exercise Verilog's case-equality contract.
    for (i = 0; i < 12; i = i + 1) begin
      word = (i[0] ? 16'hzzzz : 16'hxxxx); other = (i[1] ? 16'hffff : 16'h0001);
      total = 13'b0x101z01010x1; index = 13'b0x101z01010x0;
      narrow = (i[0] ? 8'hzz : 8'hxx); unsignedWide = 9'b01x010z01;
      signedA = 8'bx0010z01; signedB = 8'bz1010x10;
      choose = (i[1] ? 1'bx : 1'bz); load = (i[2] ? 1'bx : 1'b1);
      laneMaskIn = (i[0] ? {PPC{1'bx}} : {PPC{1'bz}}); check;
    end
    reset = 1; check; reset = 0;
    word = 16'hffff; other = 16'hffff; load = 1; check;
    $display("WA10_GENERAL_PASS PPC4=%0d samples=%0d", PPC4, checks);
    $finish;
  end
endmodule
