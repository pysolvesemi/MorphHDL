`timescale 1ns/1ps

module TimingExpressionExampleTb;
  parameter PPC4 = 0;
  localparam PPC = PPC4 * 3 + 1;
  reg clk, reset, cfgLoad;
  reg [15:0] hActive, hFront, hSync, hBack, vActive, vFront, vSync;
  reg [12:0] hTotal, laneX;
  reg [11:0] vTotal, laneY;
  reg [PPC-1:0] laneMaskIn;
  wire legal, finalGroup;
  wire [17:0] proposedHTotal;
  wire [11:0] vSyncEnd;
  wire [PPC-1:0] laneMaskOut;

  TimingExpressionExample #(.PPC4(PPC4)) dut (
    .clk(clk), .reset(reset), .io_cfgLoad(cfgLoad),
    .io_hActive(hActive), .io_hFront(hFront), .io_hSync(hSync), .io_hBack(hBack),
    .io_vActive(vActive), .io_vFront(vFront), .io_vSync(vSync),
    .io_hTotal(hTotal), .io_vTotal(vTotal), .io_laneX(laneX), .io_laneY(laneY),
    .io_laneMaskIn(laneMaskIn), .io_legal(legal), .io_finalGroup(finalGroup),
    .io_proposedHTotal(proposedHTotal), .io_vSyncEnd(vSyncEnd), .io_laneMaskOut(laneMaskOut)
  );

  reg [17:0] expectedH, expectedV;
  reg [12:0] expectedLastX;
  reg [11:0] expectedLastY, expectedState;
  reg expectedLegal, expectedFinal;
  integer checks, i, seed;

  task fail;
    input [255:0] reason;
    begin
      $display("WA10_FAIL sample=%0d reason=%0s hTotal=%h vTotal=%h laneX=%h laneY=%h", checks, reason, hTotal, vTotal, laneX, laneY);
      $fatal(1);
    end
  endtask

  task check;
    begin
      clk = 0;
      expectedH = {2'b0, hActive} + {2'b0, hFront} + {2'b0, hSync} + {2'b0, hBack};
      expectedV = {2'b0, vActive} + {2'b0, vFront} + {2'b0, vSync};
      expectedLastX = hTotal - 13'd1;
      expectedLastY = vTotal - 12'd1;
      expectedFinal = (laneX == expectedLastX) && (laneY == expectedLastY);
      expectedLegal = (hActive >= 16'd1) && (hActive <= 16'd1920) &&
        (vActive >= 16'd1) && (vActive <= 16'd1080) && (expectedH <= 18'd4096);
      #1;
      if (proposedHTotal !== expectedH) fail("18-bit total arithmetic");
      if (legal !== expectedLegal) fail("literal comparison tree");
      if (finalGroup !== expectedFinal) fail("13/12-bit underflow compare");
      if (laneMaskOut !== laneMaskIn) fail("PPC4 identity");
`ifdef WA10_MUTATE_REFERENCE
      if (checks == 1 && finalGroup !==
        (({1'b0,laneX} == ({1'b0,hTotal} - 14'd1)) &&
         ({1'b0,laneY} == ({1'b0,vTotal} - 13'd1)))) fail("mutation detected");
`endif
      if (reset) expectedState = 0;
      else if (cfgLoad) expectedState = expectedV[11:0];
      clk = 1;
      #1;
      if (vSyncEnd !== expectedState) fail("conditional truncated register");
      $display("TRACE %0d %b %b %h %h %h", checks, legal, finalGroup, proposedHTotal, vSyncEnd, laneMaskOut);
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
    checks = 0; seed = 32'h4a10cafe; clk = 0; reset = 1; cfgLoad = 0;
    hActive = 0; hFront = 0; hSync = 0; hBack = 0;
    vActive = 0; vFront = 0; vSync = 0;
    hTotal = 0; vTotal = 0; laneX = 13'h1fff; laneY = 12'hfff;
    laneMaskIn = 0; expectedState = 0;
    check; reset = 0; check;
    for (i = 0; i < 256; i = i + 1) begin
      hActive = boundary(i); hFront = boundary(i / 16);
      hSync = boundary(i + 5); hBack = boundary(i + 9);
      vActive = boundary(i / 16); vFront = boundary(i + 2); vSync = boundary(i + 15);
      hTotal = boundary(i); vTotal = boundary(i / 16);
      laneX = hTotal - 13'd1; laneY = vTotal - 12'd1;
      cfgLoad = i[0]; laneMaskIn = i; check;
    end
    // Exact legal/illegal threshold neighborhoods with other porch inputs zero.
    hSync = 0; hBack = 0; vFront = 0; vSync = 0; cfgLoad = 1;
    for (i = 0; i < 64; i = i + 1) begin
      hActive = boundary(i); vActive = boundary(i / 4);
      hFront = 4096 - hActive + (i % 3) - 1; check;
    end
    for (i = 0; i < 512; i = i + 1) begin
      hActive = $random(seed); hFront = $random(seed); hSync = $random(seed); hBack = $random(seed);
      vActive = $random(seed); vFront = $random(seed); vSync = $random(seed);
      hTotal = $random(seed); vTotal = $random(seed); laneX = $random(seed); laneY = $random(seed);
      cfgLoad = $random(seed); laneMaskIn = $random(seed); check;
    end
    for (i = 0; i < 12; i = i + 1) begin
      hActive = (i[0] ? 16'hzzzz : 16'hxxxx); hFront = 16'h0001; hSync = 16'h010z; hBack = 16'h0x00;
      vActive = 16'h00x1; vFront = 16'hz010; vSync = 16'h1x0z;
      hTotal = 13'b01x01z1010110; vTotal = 12'b1x01z1010100;
      laneX = 13'b01x01z1010101; laneY = 12'b1x01z1010011;
      cfgLoad = (i[1] ? 1'bx : 1'b1); laneMaskIn = (i[0] ? {PPC{1'bx}} : {PPC{1'bz}}); check;
    end
    reset = 1; check; reset = 0;
    vActive = 16'hffff; vFront = 16'hffff; vSync = 16'hffff; cfgLoad = 1; check;
    $display("WA10_TIMING_PASS PPC4=%0d samples=%0d", PPC4, checks);
    $finish;
  end
endmodule
