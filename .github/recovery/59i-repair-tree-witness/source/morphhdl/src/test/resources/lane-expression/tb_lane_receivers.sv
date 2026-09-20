`timescale 1ns/1ps
module tb_lane_receivers;
  integer checks = 0;
  reg running;
  reg [12:0] x;
  reg [11:0] y;
  reg [15:0] hActive;
  reg [15:0] vActive;
  reg [3:0] mask;
  reg overrideEnable;
  reg [1:0] index;
  wire got_whole_0;
  wire got_de_0;
  wire [7:0] got_ranged_0;
  wire [3:0] got_overlap_0;
  wire [3:0] got_dynamic_0;
  wire [3:0] got_protectedBits_0;
  wire got_sharedLess_0;
  wire got_sharedEqual_0;
  LaneReceiverCoverageExample #(.PPC4(1'b0)) dut0(
    .io_running(running),
    .io_x(x),
    .io_y(y),
    .io_hActive(hActive),
    .io_vActive(vActive),
    .io_mask(mask),
    .io_overrideEnable(overrideEnable),
    .io_index(index),
    .io_whole(got_whole_0),
    .io_de(got_de_0),
    .io_ranged(got_ranged_0),
    .io_overlap(got_overlap_0),
    .io_dynamic(got_dynamic_0),
    .io_protectedBits(got_protectedBits_0),
    .io_sharedLess(got_sharedLess_0),
    .io_sharedEqual(got_sharedEqual_0)
  );
  wire got_whole_1;
  wire [3:0] got_de_1;
  wire [7:0] got_ranged_1;
  wire [3:0] got_overlap_1;
  wire [3:0] got_dynamic_1;
  wire [3:0] got_protectedBits_1;
  wire got_sharedLess_1;
  wire got_sharedEqual_1;
  LaneReceiverCoverageExample #(.PPC4(1'b1)) dut1(
    .io_running(running),
    .io_x(x),
    .io_y(y),
    .io_hActive(hActive),
    .io_vActive(vActive),
    .io_mask(mask),
    .io_overrideEnable(overrideEnable),
    .io_index(index),
    .io_whole(got_whole_1),
    .io_de(got_de_1),
    .io_ranged(got_ranged_1),
    .io_overlap(got_overlap_1),
    .io_dynamic(got_dynamic_1),
    .io_protectedBits(got_protectedBits_1),
    .io_sharedLess(got_sharedLess_1),
    .io_sharedEqual(got_sharedEqual_1)
  );
  reg [31:0] seed = 32'h19b50a73;
  function automatic [31:0] random_word;
    begin
      seed = seed * 32'd1664525 + 32'd1013904223;
      random_word = seed;
    end
  endfunction
  function automatic [15:0] active_value(input integer n);
    begin
      case(n)
        0: active_value=16'd0; 1: active_value=16'd1;
        2: active_value=16'd2; 3: active_value=16'd4095;
        4: active_value=16'd4096; 5: active_value=16'd8191;
        6: active_value=16'd8192; default: active_value=16'hffff;
      endcase
    end
  endfunction
  function automatic [12:0] x_value(input integer n);
    begin
      case(n)
        0: x_value=0; 1: x_value=1; 2: x_value=2;
        3: x_value=4095; 4: x_value=4096;
        5: x_value=8190; default: x_value=8191;
      endcase
    end
  endfunction
  function automatic [11:0] y_value(input integer n);
    begin
      case(n)
        0: y_value=0; 1: y_value=1; 2: y_value=2;
        3: y_value=2047; 4: y_value=2048;
        5: y_value=4094; default: y_value=4095;
      endcase
    end
  endfunction
  function automatic [12:0] htotal_value(input integer n);
    begin
      case(n)
        0: htotal_value=0; 1: htotal_value=1; 2: htotal_value=2;
        3: htotal_value=3; 4: htotal_value=8190; default: htotal_value=8191;
      endcase
    end
  endfunction
  function automatic [11:0] vtotal_value(input integer n);
    begin
      case(n)
        0: vtotal_value=0; 1: vtotal_value=1; 2: vtotal_value=2;
        3: vtotal_value=3; 4: vtotal_value=4094; default: vtotal_value=4095;
      endcase
    end
  endfunction

  task automatic check;
    reg exp_whole, exp_sharedLess, exp_sharedEqual;
    reg [3:0] exp_de, exp_overlap, exp_dynamic, exp_protectedBits;
    reg [7:0] exp_ranged;
    reg [15:0] hLast;
    integer n;
    begin
      hLast=hActive-16'd1;
      exp_whole=running && ({3'd0,x}<hActive) && ({4'd0,y}<vActive);
      for(n=0;n<4;n=n+1) begin
        exp_de[n]=mask[n] && exp_whole;
        exp_ranged[2*n+1]=running && ({3'd0,x}<hActive);
        exp_ranged[2*n]=running && ({4'd0,y}<vActive);
      end
      exp_overlap=exp_de;
      exp_dynamic=mask;
      if(overrideEnable) begin
        exp_overlap[0]=!exp_whole;
        exp_dynamic[index]=exp_whole;
      end
      exp_protectedBits=exp_de;
      exp_sharedLess=({3'd0,x}<hActive);
      exp_sharedEqual=({3'd0,x}==hLast);
      #2;
      if (got_whole_0 !== exp_whole) begin
        $display("FAIL check=%0d output=whole PPC4=0 actual=%b expected=%b", checks, got_whole_0, exp_whole);
        $fatal(1, "four-state result mismatch");
      end
      if (got_whole_1 !== exp_whole) begin
        $display("FAIL check=%0d output=whole PPC4=1 actual=%b expected=%b", checks, got_whole_1, exp_whole);
        $fatal(1, "four-state result mismatch");
      end
      if (got_de_0 !== exp_de[0]) begin
        $display("FAIL check=%0d output=de PPC4=0 actual=%b expected=%b", checks, got_de_0, exp_de[0]);
        $fatal(1, "four-state result mismatch");
      end
      if (got_de_1 !== exp_de) begin
        $display("FAIL check=%0d output=de PPC4=1 actual=%b expected=%b", checks, got_de_1, exp_de);
        $fatal(1, "four-state result mismatch");
      end
      if (got_ranged_0 !== exp_ranged) begin
        $display("FAIL check=%0d output=ranged PPC4=0 actual=%b expected=%b", checks, got_ranged_0, exp_ranged);
        $fatal(1, "four-state result mismatch");
      end
      if (got_ranged_1 !== exp_ranged) begin
        $display("FAIL check=%0d output=ranged PPC4=1 actual=%b expected=%b", checks, got_ranged_1, exp_ranged);
        $fatal(1, "four-state result mismatch");
      end
      if (got_overlap_0 !== exp_overlap) begin
        $display("FAIL check=%0d output=overlap PPC4=0 actual=%b expected=%b", checks, got_overlap_0, exp_overlap);
        $fatal(1, "four-state result mismatch");
      end
      if (got_overlap_1 !== exp_overlap) begin
        $display("FAIL check=%0d output=overlap PPC4=1 actual=%b expected=%b", checks, got_overlap_1, exp_overlap);
        $fatal(1, "four-state result mismatch");
      end
      if (got_dynamic_0 !== exp_dynamic) begin
        $display("FAIL check=%0d output=dynamic PPC4=0 actual=%b expected=%b", checks, got_dynamic_0, exp_dynamic);
        $fatal(1, "four-state result mismatch");
      end
      if (got_dynamic_1 !== exp_dynamic) begin
        $display("FAIL check=%0d output=dynamic PPC4=1 actual=%b expected=%b", checks, got_dynamic_1, exp_dynamic);
        $fatal(1, "four-state result mismatch");
      end
      if (got_protectedBits_0 !== exp_protectedBits) begin
        $display("FAIL check=%0d output=protectedBits PPC4=0 actual=%b expected=%b", checks, got_protectedBits_0, exp_protectedBits);
        $fatal(1, "four-state result mismatch");
      end
      if (got_protectedBits_1 !== exp_protectedBits) begin
        $display("FAIL check=%0d output=protectedBits PPC4=1 actual=%b expected=%b", checks, got_protectedBits_1, exp_protectedBits);
        $fatal(1, "four-state result mismatch");
      end
      if (got_sharedLess_0 !== exp_sharedLess) begin
        $display("FAIL check=%0d output=sharedLess PPC4=0 actual=%b expected=%b", checks, got_sharedLess_0, exp_sharedLess);
        $fatal(1, "four-state result mismatch");
      end
      if (got_sharedLess_1 !== exp_sharedLess) begin
        $display("FAIL check=%0d output=sharedLess PPC4=1 actual=%b expected=%b", checks, got_sharedLess_1, exp_sharedLess);
        $fatal(1, "four-state result mismatch");
      end
      if (got_sharedEqual_0 !== exp_sharedEqual) begin
        $display("FAIL check=%0d output=sharedEqual PPC4=0 actual=%b expected=%b", checks, got_sharedEqual_0, exp_sharedEqual);
        $fatal(1, "four-state result mismatch");
      end
      if (got_sharedEqual_1 !== exp_sharedEqual) begin
        $display("FAIL check=%0d output=sharedEqual PPC4=1 actual=%b expected=%b", checks, got_sharedEqual_1, exp_sharedEqual);
        $fatal(1, "four-state result mismatch");
      end
      checks=checks+1;
    end
  endtask
  integer r,a,b,c,d,i,k;
  initial begin
    running=0;x=0;y=0;hActive=0;vActive=0;mask=0;overrideEnable=0;index=0;
    #1;
    if($bits(dut0.io_de)!=1 || $bits(dut1.io_de)!=4)
      $fatal(1,"wrong elaborated PPC interface widths");
    for(r=0;r<2;r=r+1) for(a=0;a<7;a=a+1) for(b=0;b<7;b=b+1)
      for(c=0;c<8;c=c+1) for(d=0;d<8;d=d+1) begin
        running=r; x=x_value(a);y=y_value(b);hActive=active_value(c);vActive=active_value(d);
        mask=random_word();overrideEnable=random_word();index=random_word();check;
      end
    // Every selected bit, both override choices, and all static mask values.
    running=1;x=0;y=0;hActive=1;vActive=1;
    for(a=0;a<16;a=a+1) for(i=0;i<4;i=i+1) for(r=0;r<2;r=r+1) begin
      mask=a;index=i;overrideEnable=r;check;
    end
    for(k=0;k<4096;k=k+1) begin
      running=random_word();x=random_word();y=random_word();hActive=random_word();vActive=random_word();
      mask=random_word();overrideEnable=random_word();index=random_word();check;
    end
    for(k=0;k<2;k=k+1) begin
      running=1;x=0;y=0;hActive=1;vActive=1;mask=4'b1010;overrideEnable=1;index=0;
      if(k==0) running=1'bx; else running=1'bz;check;
      running=1;if(k==0) x=13'bx; else x=13'bz;check;
      x=0;if(k==0) y=12'bx; else y=12'bz;check;
      y=0;if(k==0) hActive=16'bx; else hActive=16'bz;check;
      hActive=1;if(k==0) vActive=16'bx; else vActive=16'bz;check;
      vActive=1;if(k==0) overrideEnable=1'bx; else overrideEnable=1'bz;check;
      overrideEnable=1;if(k==0) index=2'bx; else index=2'bz;check;
    end
    $display("PASS topology=receivers checks=%0d PPC4=0,1", checks);
    $finish;
  end
  initial begin #1000000; $fatal(1,"testbench watchdog"); end
endmodule
