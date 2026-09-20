module LaneExpressionExample #(
  parameter integer PPC4 = 0
) (
  input  wire          io_running,
  input  wire [15:0]   io_hActive,
  input  wire [15:0]   io_vActive,
  input  wire [12:0]   io_baseX,
  input  wire [11:0]   io_baseY,
  input  wire [12:0]   io_hTotal,
  input  wire [11:0]   io_vTotal,
  output wire [12:0]   io_nextX,
  output wire [11:0]   io_nextY,
  output wire [((PPC4 * 3) + 1)-1:0]    io_de,
  output wire [((PPC4 * 3) + 1)-1:0]    io_frameEnd
);

  wire       [15:0]   _zz_laneDe;
  wire       [15:0]   _zz_laneDe_1;
  wire       [15:0]   _zz_laneFrameEnd;
  wire       [15:0]   _zz_laneFrameEnd_1;
  wire       [15:0]   _zz_laneFrameEnd_2;
  wire       [15:0]   _zz_laneFrameEnd_3;
  wire       [15:0]   _zz_laneDe_2;
  wire       [15:0]   _zz_laneDe_3;
  wire       [15:0]   _zz_laneFrameEnd_4;
  wire       [15:0]   _zz_laneFrameEnd_5;
  wire       [15:0]   _zz_laneFrameEnd_6;
  wire       [15:0]   _zz_laneFrameEnd_7;
  wire       [15:0]   _zz_laneDe_4;
  wire       [15:0]   _zz_laneDe_5;
  wire       [15:0]   _zz_laneFrameEnd_8;
  wire       [15:0]   _zz_laneFrameEnd_9;
  wire       [15:0]   _zz_laneFrameEnd_10;
  wire       [15:0]   _zz_laneFrameEnd_11;
  wire       [15:0]   _zz_laneDe_6;
  wire       [15:0]   _zz_laneDe_7;
  wire       [15:0]   _zz_laneFrameEnd_12;
  wire       [15:0]   _zz_laneFrameEnd_13;
  wire       [15:0]   _zz_laneFrameEnd_14;
  wire       [15:0]   _zz_laneFrameEnd_15;
  wire       [12:0]   laneX_0;
  reg        [12:0]   laneX_1;
  reg        [12:0]   laneX_2;
  reg        [12:0]   laneX_3;
  reg        [12:0]   laneX_4;
  wire       [11:0]   laneY_0;
  reg        [11:0]   laneY_1;
  reg        [11:0]   laneY_2;
  reg        [11:0]   laneY_3;
  reg        [11:0]   laneY_4;
  reg        [3:0]    laneDe;
  reg        [3:0]    laneFrameEnd;
  wire                when_GenerateLaneExpressionExample_l35;
  wire                when_GenerateLaneExpressionExample_l38;
  wire                when_GenerateLaneExpressionExample_l35_1;
  wire                when_GenerateLaneExpressionExample_l38_1;
  wire                when_GenerateLaneExpressionExample_l35_2;
  wire                when_GenerateLaneExpressionExample_l38_2;
  wire                when_GenerateLaneExpressionExample_l35_3;
  wire                when_GenerateLaneExpressionExample_l38_3;
  wire       [((PPC4 * 3) + 1)-1:0]    morphhdl_resize;
  wire       [((PPC4 * 3) + 1)-1:0]    morphhdl_resize_1;

  assign _zz_laneDe = {3'd0, laneX_0};
  assign _zz_laneDe_1 = {4'd0, laneY_0};
  assign _zz_laneFrameEnd = {3'd0, laneX_0};
  assign _zz_laneFrameEnd_1 = (io_hActive - 16'h0001);
  assign _zz_laneFrameEnd_2 = {4'd0, laneY_0};
  assign _zz_laneFrameEnd_3 = (io_vActive - 16'h0001);
  assign _zz_laneDe_2 = {3'd0, laneX_1};
  assign _zz_laneDe_3 = {4'd0, laneY_1};
  assign _zz_laneFrameEnd_4 = {3'd0, laneX_1};
  assign _zz_laneFrameEnd_5 = (io_hActive - 16'h0001);
  assign _zz_laneFrameEnd_6 = {4'd0, laneY_1};
  assign _zz_laneFrameEnd_7 = (io_vActive - 16'h0001);
  assign _zz_laneDe_4 = {3'd0, laneX_2};
  assign _zz_laneDe_5 = {4'd0, laneY_2};
  assign _zz_laneFrameEnd_8 = {3'd0, laneX_2};
  assign _zz_laneFrameEnd_9 = (io_hActive - 16'h0001);
  assign _zz_laneFrameEnd_10 = {4'd0, laneY_2};
  assign _zz_laneFrameEnd_11 = (io_vActive - 16'h0001);
  assign _zz_laneDe_6 = {3'd0, laneX_3};
  assign _zz_laneDe_7 = {4'd0, laneY_3};
  assign _zz_laneFrameEnd_12 = {3'd0, laneX_3};
  assign _zz_laneFrameEnd_13 = (io_hActive - 16'h0001);
  assign _zz_laneFrameEnd_14 = {4'd0, laneY_3};
  assign _zz_laneFrameEnd_15 = (io_vActive - 16'h0001);
  assign laneX_0 = io_baseX;
  assign laneY_0 = io_baseY;
  always @(*) begin
    laneX_1 = (laneX_0 + 13'h0001);
    if(when_GenerateLaneExpressionExample_l35) begin
      laneX_1 = 13'h0;
    end
  end

  always @(*) begin
    laneY_1 = laneY_0;
    if(when_GenerateLaneExpressionExample_l35) begin
      laneY_1 = (laneY_0 + 12'h001);
      if(when_GenerateLaneExpressionExample_l38) begin
        laneY_1 = 12'h0;
      end
    end
  end

  assign when_GenerateLaneExpressionExample_l35 = (laneX_0 == (io_hTotal - 13'h0001));
  assign when_GenerateLaneExpressionExample_l38 = (laneY_0 == (io_vTotal - 12'h001));
  always @(*) begin
    laneDe[0] = ((io_running && (_zz_laneDe < io_hActive)) && (_zz_laneDe_1 < io_vActive));
    laneDe[1] = ((io_running && (_zz_laneDe_2 < io_hActive)) && (_zz_laneDe_3 < io_vActive));
    laneDe[2] = ((io_running && (_zz_laneDe_4 < io_hActive)) && (_zz_laneDe_5 < io_vActive));
    laneDe[3] = ((io_running && (_zz_laneDe_6 < io_hActive)) && (_zz_laneDe_7 < io_vActive));
  end

  always @(*) begin
    laneFrameEnd[0] = ((io_running && (_zz_laneFrameEnd == _zz_laneFrameEnd_1)) && (_zz_laneFrameEnd_2 == _zz_laneFrameEnd_3));
    laneFrameEnd[1] = ((io_running && (_zz_laneFrameEnd_4 == _zz_laneFrameEnd_5)) && (_zz_laneFrameEnd_6 == _zz_laneFrameEnd_7));
    laneFrameEnd[2] = ((io_running && (_zz_laneFrameEnd_8 == _zz_laneFrameEnd_9)) && (_zz_laneFrameEnd_10 == _zz_laneFrameEnd_11));
    laneFrameEnd[3] = ((io_running && (_zz_laneFrameEnd_12 == _zz_laneFrameEnd_13)) && (_zz_laneFrameEnd_14 == _zz_laneFrameEnd_15));
  end

  always @(*) begin
    laneX_2 = (laneX_1 + 13'h0001);
    if(when_GenerateLaneExpressionExample_l35_1) begin
      laneX_2 = 13'h0;
    end
  end

  always @(*) begin
    laneY_2 = laneY_1;
    if(when_GenerateLaneExpressionExample_l35_1) begin
      laneY_2 = (laneY_1 + 12'h001);
      if(when_GenerateLaneExpressionExample_l38_1) begin
        laneY_2 = 12'h0;
      end
    end
  end

  assign when_GenerateLaneExpressionExample_l35_1 = (laneX_1 == (io_hTotal - 13'h0001));
  assign when_GenerateLaneExpressionExample_l38_1 = (laneY_1 == (io_vTotal - 12'h001));
  always @(*) begin
    laneX_3 = (laneX_2 + 13'h0001);
    if(when_GenerateLaneExpressionExample_l35_2) begin
      laneX_3 = 13'h0;
    end
  end

  always @(*) begin
    laneY_3 = laneY_2;
    if(when_GenerateLaneExpressionExample_l35_2) begin
      laneY_3 = (laneY_2 + 12'h001);
      if(when_GenerateLaneExpressionExample_l38_2) begin
        laneY_3 = 12'h0;
      end
    end
  end

  assign when_GenerateLaneExpressionExample_l35_2 = (laneX_2 == (io_hTotal - 13'h0001));
  assign when_GenerateLaneExpressionExample_l38_2 = (laneY_2 == (io_vTotal - 12'h001));
  always @(*) begin
    laneX_4 = (laneX_3 + 13'h0001);
    if(when_GenerateLaneExpressionExample_l35_3) begin
      laneX_4 = 13'h0;
    end
  end

  always @(*) begin
    laneY_4 = laneY_3;
    if(when_GenerateLaneExpressionExample_l35_3) begin
      laneY_4 = (laneY_3 + 12'h001);
      if(when_GenerateLaneExpressionExample_l38_3) begin
        laneY_4 = 12'h0;
      end
    end
  end

  assign when_GenerateLaneExpressionExample_l35_3 = (laneX_3 == (io_hTotal - 13'h0001));
  assign when_GenerateLaneExpressionExample_l38_3 = (laneY_3 == (io_vTotal - 12'h001));
  assign io_nextX = laneX_4;
  assign io_nextY = laneY_4;
  assign morphhdl_resize = laneDe[(((PPC4 * 3) + 1))-1:0];
  assign io_de = morphhdl_resize;
  assign morphhdl_resize_1 = laneFrameEnd[(((PPC4 * 3) + 1))-1:0];
  assign io_frameEnd = morphhdl_resize_1;

endmodule
