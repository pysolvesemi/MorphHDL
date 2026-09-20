`timescale 1ns/1ps
module tb_lane_conditions;
  integer checks = 0;
  reg [12:0] x;
  reg [11:0] y;
  reg [12:0] hTotal;
  reg [11:0] vTotal;
  reg [3:0] witnessIn;
  wire got_witnessOut_0;
  wire [12:0] got_singleX_0;
  wire [12:0] got_sharedX_0;
  wire [11:0] got_sharedY_0;
  wire [12:0] got_nestedX_0;
  wire [11:0] got_nestedY_0;
  wire [1:0] got_priority_0;
  wire [12:0] got_mixedX_0;
  wire got_booleanRhs_0;
  wire [12:0] got_protectedX_0;
  wire got_protectedBoolean_0;
  wire [12:0] got_keptGeneratedX_0;
  LaneConditionCoverageExample #(.PPC4(1'b0)) dut0(
    .io_x(x),
    .io_y(y),
    .io_hTotal(hTotal),
    .io_vTotal(vTotal),
    .io_witnessIn(witnessIn[0]),
    .io_witnessOut(got_witnessOut_0),
    .io_singleX(got_singleX_0),
    .io_sharedX(got_sharedX_0),
    .io_sharedY(got_sharedY_0),
    .io_nestedX(got_nestedX_0),
    .io_nestedY(got_nestedY_0),
    .io_priority(got_priority_0),
    .io_mixedX(got_mixedX_0),
    .io_booleanRhs(got_booleanRhs_0),
    .io_protectedX(got_protectedX_0),
    .io_protectedBoolean(got_protectedBoolean_0),
    .io_keptGeneratedX(got_keptGeneratedX_0)
  );
  wire [3:0] got_witnessOut_1;
  wire [12:0] got_singleX_1;
  wire [12:0] got_sharedX_1;
  wire [11:0] got_sharedY_1;
  wire [12:0] got_nestedX_1;
  wire [11:0] got_nestedY_1;
  wire [1:0] got_priority_1;
  wire [12:0] got_mixedX_1;
  wire got_booleanRhs_1;
  wire [12:0] got_protectedX_1;
  wire got_protectedBoolean_1;
  wire [12:0] got_keptGeneratedX_1;
  LaneConditionCoverageExample #(.PPC4(1'b1)) dut1(
    .io_x(x),
    .io_y(y),
    .io_hTotal(hTotal),
    .io_vTotal(vTotal),
    .io_witnessIn(witnessIn),
    .io_witnessOut(got_witnessOut_1),
    .io_singleX(got_singleX_1),
    .io_sharedX(got_sharedX_1),
    .io_sharedY(got_sharedY_1),
    .io_nestedX(got_nestedX_1),
    .io_nestedY(got_nestedY_1),
    .io_priority(got_priority_1),
    .io_mixedX(got_mixedX_1),
    .io_booleanRhs(got_booleanRhs_1),
    .io_protectedX(got_protectedX_1),
    .io_protectedBoolean(got_protectedBoolean_1),
    .io_keptGeneratedX(got_keptGeneratedX_1)
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
    reg [3:0] exp_witnessOut;
    reg [12:0] hLast, exp_singleX, exp_sharedX, exp_nestedX, exp_mixedX, exp_protectedX, exp_keptGeneratedX;
    reg [11:0] vLast, exp_sharedY, exp_nestedY;
    reg [1:0] exp_priority;
    reg exp_booleanRhs, exp_protectedBoolean;
    begin
      hLast=hTotal-13'd1; vLast=vTotal-12'd1;
      exp_witnessOut=witnessIn;
      exp_singleX=x+13'd1;
      if(x==hLast) exp_singleX=0;
      exp_sharedX=x+13'd1; exp_sharedY=y;
      if(x==hLast) exp_sharedX=0;
      if(x==hLast) exp_sharedY=y+12'd1;
      exp_nestedX=x+13'd1; exp_nestedY=y;
      if(x==hLast) begin
        exp_nestedX=0; exp_nestedY=y+12'd1;
        if(y==vLast) exp_nestedY=0;
      end
      if(x==13'd0) exp_priority=2'd0;
      else if(x==hLast) exp_priority=2'd1;
      else exp_priority=2'd2;
      exp_booleanRhs=(y==vLast);
      exp_mixedX=x;
      if(y==vLast) exp_mixedX=0;
      exp_protectedBoolean=(x==hLast);
      exp_protectedX=x+13'd1;
      if(x==hLast) exp_protectedX=0;
      exp_keptGeneratedX=x+13'd1;
      if(x==hLast) exp_keptGeneratedX=0;
      #2;
      if (got_witnessOut_0 !== exp_witnessOut[0]) begin
        $display("FAIL check=%0d output=witnessOut PPC4=0 actual=%b expected=%b", checks, got_witnessOut_0, exp_witnessOut[0]);
        $fatal(1, "four-state result mismatch");
      end
      if (got_witnessOut_1 !== exp_witnessOut) begin
        $display("FAIL check=%0d output=witnessOut PPC4=1 actual=%b expected=%b", checks, got_witnessOut_1, exp_witnessOut);
        $fatal(1, "four-state result mismatch");
      end
      if (got_singleX_0 !== exp_singleX) begin
        $display("FAIL check=%0d output=singleX PPC4=0 actual=%b expected=%b", checks, got_singleX_0, exp_singleX);
        $fatal(1, "four-state result mismatch");
      end
      if (got_singleX_1 !== exp_singleX) begin
        $display("FAIL check=%0d output=singleX PPC4=1 actual=%b expected=%b", checks, got_singleX_1, exp_singleX);
        $fatal(1, "four-state result mismatch");
      end
      if (got_sharedX_0 !== exp_sharedX) begin
        $display("FAIL check=%0d output=sharedX PPC4=0 actual=%b expected=%b", checks, got_sharedX_0, exp_sharedX);
        $fatal(1, "four-state result mismatch");
      end
      if (got_sharedX_1 !== exp_sharedX) begin
        $display("FAIL check=%0d output=sharedX PPC4=1 actual=%b expected=%b", checks, got_sharedX_1, exp_sharedX);
        $fatal(1, "four-state result mismatch");
      end
      if (got_sharedY_0 !== exp_sharedY) begin
        $display("FAIL check=%0d output=sharedY PPC4=0 actual=%b expected=%b", checks, got_sharedY_0, exp_sharedY);
        $fatal(1, "four-state result mismatch");
      end
      if (got_sharedY_1 !== exp_sharedY) begin
        $display("FAIL check=%0d output=sharedY PPC4=1 actual=%b expected=%b", checks, got_sharedY_1, exp_sharedY);
        $fatal(1, "four-state result mismatch");
      end
      if (got_nestedX_0 !== exp_nestedX) begin
        $display("FAIL check=%0d output=nestedX PPC4=0 actual=%b expected=%b", checks, got_nestedX_0, exp_nestedX);
        $fatal(1, "four-state result mismatch");
      end
      if (got_nestedX_1 !== exp_nestedX) begin
        $display("FAIL check=%0d output=nestedX PPC4=1 actual=%b expected=%b", checks, got_nestedX_1, exp_nestedX);
        $fatal(1, "four-state result mismatch");
      end
      if (got_nestedY_0 !== exp_nestedY) begin
        $display("FAIL check=%0d output=nestedY PPC4=0 actual=%b expected=%b", checks, got_nestedY_0, exp_nestedY);
        $fatal(1, "four-state result mismatch");
      end
      if (got_nestedY_1 !== exp_nestedY) begin
        $display("FAIL check=%0d output=nestedY PPC4=1 actual=%b expected=%b", checks, got_nestedY_1, exp_nestedY);
        $fatal(1, "four-state result mismatch");
      end
      if (got_priority_0 !== exp_priority) begin
        $display("FAIL check=%0d output=priority PPC4=0 actual=%b expected=%b", checks, got_priority_0, exp_priority);
        $fatal(1, "four-state result mismatch");
      end
      if (got_priority_1 !== exp_priority) begin
        $display("FAIL check=%0d output=priority PPC4=1 actual=%b expected=%b", checks, got_priority_1, exp_priority);
        $fatal(1, "four-state result mismatch");
      end
      if (got_mixedX_0 !== exp_mixedX) begin
        $display("FAIL check=%0d output=mixedX PPC4=0 actual=%b expected=%b", checks, got_mixedX_0, exp_mixedX);
        $fatal(1, "four-state result mismatch");
      end
      if (got_mixedX_1 !== exp_mixedX) begin
        $display("FAIL check=%0d output=mixedX PPC4=1 actual=%b expected=%b", checks, got_mixedX_1, exp_mixedX);
        $fatal(1, "four-state result mismatch");
      end
      if (got_booleanRhs_0 !== exp_booleanRhs) begin
        $display("FAIL check=%0d output=booleanRhs PPC4=0 actual=%b expected=%b", checks, got_booleanRhs_0, exp_booleanRhs);
        $fatal(1, "four-state result mismatch");
      end
      if (got_booleanRhs_1 !== exp_booleanRhs) begin
        $display("FAIL check=%0d output=booleanRhs PPC4=1 actual=%b expected=%b", checks, got_booleanRhs_1, exp_booleanRhs);
        $fatal(1, "four-state result mismatch");
      end
      if (got_protectedX_0 !== exp_protectedX) begin
        $display("FAIL check=%0d output=protectedX PPC4=0 actual=%b expected=%b", checks, got_protectedX_0, exp_protectedX);
        $fatal(1, "four-state result mismatch");
      end
      if (got_protectedX_1 !== exp_protectedX) begin
        $display("FAIL check=%0d output=protectedX PPC4=1 actual=%b expected=%b", checks, got_protectedX_1, exp_protectedX);
        $fatal(1, "four-state result mismatch");
      end
      if (got_protectedBoolean_0 !== exp_protectedBoolean) begin
        $display("FAIL check=%0d output=protectedBoolean PPC4=0 actual=%b expected=%b", checks, got_protectedBoolean_0, exp_protectedBoolean);
        $fatal(1, "four-state result mismatch");
      end
      if (got_protectedBoolean_1 !== exp_protectedBoolean) begin
        $display("FAIL check=%0d output=protectedBoolean PPC4=1 actual=%b expected=%b", checks, got_protectedBoolean_1, exp_protectedBoolean);
        $fatal(1, "four-state result mismatch");
      end
      if (got_keptGeneratedX_0 !== exp_keptGeneratedX) begin
        $display("FAIL check=%0d output=keptGeneratedX PPC4=0 actual=%b expected=%b", checks, got_keptGeneratedX_0, exp_keptGeneratedX);
        $fatal(1, "four-state result mismatch");
      end
      if (got_keptGeneratedX_1 !== exp_keptGeneratedX) begin
        $display("FAIL check=%0d output=keptGeneratedX PPC4=1 actual=%b expected=%b", checks, got_keptGeneratedX_1, exp_keptGeneratedX);
        $fatal(1, "four-state result mismatch");
      end
      checks=checks+1;
    end
  endtask
  integer h,v,a,b,k;
  initial begin
    x=0;y=0;hTotal=0;vTotal=0;witnessIn=4'b1011;
    #1;
    if($bits(dut0.io_witnessOut)!=1 || $bits(dut1.io_witnessOut)!=4)
      $fatal(1,"wrong elaborated witness widths");
    for(h=0;h<6;h=h+1) for(v=0;v<6;v=v+1)
      for(a=0;a<8;a=a+1) for(b=0;b<8;b=b+1) begin
        hTotal=htotal_value(h); vTotal=vtotal_value(v);
        case(a)
          0:x=0; 1:x=1; 6:x=8190; 7:x=8191;
          default:x=hTotal-(a-1);
        endcase
        case(b)
          0:y=0; 1:y=1; 6:y=4094; 7:y=4095;
          default:y=vTotal-(b-1);
        endcase
        witnessIn=random_word(); check;
      end
    for(k=0;k<4096;k=k+1) begin
      x=random_word();y=random_word();hTotal=random_word();vTotal=random_word();
      witnessIn=random_word(); check;
    end
    for(k=0;k<2;k=k+1) begin
      x=0;y=0;hTotal=1;vTotal=1;witnessIn=4'bx10z;
      if(k==0) x=13'bx; else x=13'bz; check;
      x=0; if(k==0) y=12'bx; else y=12'bz; check;
      y=0; if(k==0) hTotal=13'bx; else hTotal=13'bz; check;
      hTotal=1; if(k==0) vTotal=12'bx; else vTotal=12'bz; check;
    end
    // Correlated unknowns distinguish == from === at procedural receivers.
    x=13'bx;hTotal=13'bx;y=0;vTotal=1;check;
    x=13'bz;hTotal=13'bz;y=0;vTotal=1;check;
    x=13'd7;hTotal=13'd8;y=12'bx;vTotal=12'bx;check;
    x=13'd7;hTotal=13'd8;y=12'bz;vTotal=12'bz;check;
    $display("PASS topology=conditions checks=%0d PPC4=0,1", checks);
    $finish;
  end
  initial begin #1000000; $fatal(1,"testbench watchdog"); end
endmodule
