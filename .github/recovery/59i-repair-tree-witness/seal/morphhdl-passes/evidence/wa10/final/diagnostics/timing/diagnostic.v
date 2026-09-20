module TimingExpressionExample #(
  parameter integer PPC4 = 0
) (
  input  wire          io_cfgLoad,
  input  wire [15:0]   io_hActive,
  input  wire [15:0]   io_hFront,
  input  wire [15:0]   io_hSync,
  input  wire [15:0]   io_hBack,
  input  wire [15:0]   io_vActive,
  input  wire [15:0]   io_vFront,
  input  wire [15:0]   io_vSync,
  input  wire [12:0]   io_hTotal,
  input  wire [11:0]   io_vTotal,
  input  wire [12:0]   io_laneX,
  input  wire [11:0]   io_laneY,
  output wire          io_legal,
  output wire          io_finalGroup,
  output wire [17:0]   io_proposedHTotal,
  output wire [11:0]   io_vSyncEnd,
  input  wire [(((((((((PPC4 == 1)) ? (1) : (0))) == (1))) ? 1 : 0) * 3) + 1)-1:0]    io_laneMaskIn,
  output wire [(((((((((PPC4 == 1)) ? (1) : (0))) == (1))) ? 1 : 0) * 3) + 1)-1:0]    io_laneMaskOut,
  input  wire          clk,
  input  wire          reset
);

  wire       [17:0]   _zz_vSyncEnd;
  wire       [17:0]   proposedHTotal;
  reg        [11:0]   vSyncEnd;

  assign _zz_vSyncEnd = (({2'd0, io_vActive} + {2'd0, io_vFront}) + {2'd0, io_vSync});
  assign proposedHTotal = ((({2'd0, io_hActive} + {2'd0, io_hFront}) + {2'd0, io_hSync}) + {2'd0, io_hBack});
  assign io_legal = (((((16'h0001 <= io_hActive) && (io_hActive <= 16'h0780)) && (16'h0001 <= io_vActive)) && (io_vActive <= 16'h0438)) && (proposedHTotal <= 18'h01000));
  assign io_proposedHTotal = proposedHTotal;
  assign io_finalGroup = ((io_laneX == (io_hTotal - 13'h0001)) && (io_laneY == (io_vTotal - 12'h001)));
  assign io_vSyncEnd = vSyncEnd;
  assign io_laneMaskOut = io_laneMaskIn;
  always @(posedge clk or posedge reset) begin
    if(reset) begin
      vSyncEnd <= 12'h0;
    end else begin
      if(io_cfgLoad) begin
        vSyncEnd <= _zz_vSyncEnd[11:0];
      end
    end
  end


endmodule
