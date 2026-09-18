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
  wire       [((PPC4 * 3) + 1)-1:0]    morphhdl_resize;
  wire       [((PPC4 * 3) + 1)-1:0]    morphhdl_resize_1;

  assign laneX_0 = io_baseX;
  assign laneY_0 = io_baseY;
  always @(*) begin
    laneX_1 = (laneX_0 + 13'h0001);
    if((laneX_0 == (io_hTotal - 13'h0001))) begin
      laneX_1 = 13'h0;
    end
  end

  always @(*) begin
    laneY_1 = laneY_0;
    if((laneX_0 == (io_hTotal - 13'h0001))) begin
      laneY_1 = (laneY_0 + 12'h001);
      if((laneY_0 == (io_vTotal - 12'h001))) begin
        laneY_1 = 12'h0;
      end
    end
  end

  always @(*) begin
    laneDe[0] = ((io_running && ({3'd0, laneX_0} < io_hActive)) && ({4'd0, laneY_0} < io_vActive));
    laneDe[1] = ((io_running && ({3'd0, laneX_1} < io_hActive)) && ({4'd0, laneY_1} < io_vActive));
    laneDe[2] = ((io_running && ({3'd0, laneX_2} < io_hActive)) && ({4'd0, laneY_2} < io_vActive));
    laneDe[3] = ((io_running && ({3'd0, laneX_3} < io_hActive)) && ({4'd0, laneY_3} < io_vActive));
  end

  always @(*) begin
    laneFrameEnd[0] = ((io_running && ({3'd0, laneX_0} == (io_hActive - 16'h0001))) && ({4'd0, laneY_0} == (io_vActive - 16'h0001)));
    laneFrameEnd[1] = ((io_running && ({3'd0, laneX_1} == (io_hActive - 16'h0001))) && ({4'd0, laneY_1} == (io_vActive - 16'h0001)));
    laneFrameEnd[2] = ((io_running && ({3'd0, laneX_2} == (io_hActive - 16'h0001))) && ({4'd0, laneY_2} == (io_vActive - 16'h0001)));
    laneFrameEnd[3] = ((io_running && ({3'd0, laneX_3} == (io_hActive - 16'h0001))) && ({4'd0, laneY_3} == (io_vActive - 16'h0001)));
  end

  always @(*) begin
    laneX_2 = (laneX_1 + 13'h0001);
    if((laneX_1 == (io_hTotal - 13'h0001))) begin
      laneX_2 = 13'h0;
    end
  end

  always @(*) begin
    laneY_2 = laneY_1;
    if((laneX_1 == (io_hTotal - 13'h0001))) begin
      laneY_2 = (laneY_1 + 12'h001);
      if((laneY_1 == (io_vTotal - 12'h001))) begin
        laneY_2 = 12'h0;
      end
    end
  end

  always @(*) begin
    laneX_3 = (laneX_2 + 13'h0001);
    if((laneX_2 == (io_hTotal - 13'h0001))) begin
      laneX_3 = 13'h0;
    end
  end

  always @(*) begin
    laneY_3 = laneY_2;
    if((laneX_2 == (io_hTotal - 13'h0001))) begin
      laneY_3 = (laneY_2 + 12'h001);
      if((laneY_2 == (io_vTotal - 12'h001))) begin
        laneY_3 = 12'h0;
      end
    end
  end

  always @(*) begin
    laneX_4 = (laneX_3 + 13'h0001);
    if((laneX_3 == (io_hTotal - 13'h0001))) begin
      laneX_4 = 13'h0;
    end
  end

  always @(*) begin
    laneY_4 = laneY_3;
    if((laneX_3 == (io_hTotal - 13'h0001))) begin
      laneY_4 = (laneY_3 + 12'h001);
      if((laneY_3 == (io_vTotal - 12'h001))) begin
        laneY_4 = 12'h0;
      end
    end
  end

  assign io_nextX = laneX_4;
  assign io_nextY = laneY_4;
  assign morphhdl_resize = laneDe[(((PPC4 * 3) + 1))-1:0];
  assign io_de = morphhdl_resize;
  assign morphhdl_resize_1 = laneFrameEnd[(((PPC4 * 3) + 1))-1:0];
  assign io_frameEnd = morphhdl_resize_1;

endmodule
