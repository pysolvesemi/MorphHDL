module LaneConditionCoverageExample #(
  parameter integer PPC4 = 0
) (
  input  wire [12:0]   io_x,
  input  wire [11:0]   io_y,
  input  wire [12:0]   io_hTotal,
  input  wire [11:0]   io_vTotal,
  input  wire [((PPC4 * 3) + 1)-1:0]    io_witnessIn,
  output wire [((PPC4 * 3) + 1)-1:0]    io_witnessOut,
  output reg  [12:0]   io_singleX,
  output reg  [12:0]   io_sharedX,
  output reg  [11:0]   io_sharedY,
  output reg  [12:0]   io_nestedX,
  output reg  [11:0]   io_nestedY,
  output reg  [1:0]    io_priority,
  output reg  [12:0]   io_mixedX,
  output wire          io_booleanRhs,
  output reg  [12:0]   io_protectedX,
  output wire          io_protectedBoolean,
  output reg  [12:0]   io_keptGeneratedX
);

  wire       [12:0]   _zz_when_example_l42;
  wire       [12:0]   _zz_when_LaneConditionCoverageExample_l81;
  (* keep *) wire                when_example_l42;
  (* keep *) wire                when_LaneConditionCoverageExample_l81;

  assign _zz_when_example_l42 = (io_hTotal - 13'h0001);
  assign _zz_when_LaneConditionCoverageExample_l81 = (io_hTotal - 13'h0001);
  assign io_witnessOut = io_witnessIn;
  always @(*) begin
    io_singleX = (io_x + 13'h0001);
    if((io_x == (io_hTotal - 13'h0001))) begin
      io_singleX = 13'h0;
    end
  end

  always @(*) begin
    io_sharedX = (io_x + 13'h0001);
    if((io_x == (io_hTotal - 13'h0001))) begin
      io_sharedX = 13'h0;
    end
  end

  always @(*) begin
    io_sharedY = io_y;
    if((io_x == (io_hTotal - 13'h0001))) begin
      io_sharedY = (io_y + 12'h001);
    end
  end

  always @(*) begin
    io_nestedX = (io_x + 13'h0001);
    if((io_x == (io_hTotal - 13'h0001))) begin
      io_nestedX = 13'h0;
    end
  end

  always @(*) begin
    io_nestedY = io_y;
    if((io_x == (io_hTotal - 13'h0001))) begin
      io_nestedY = (io_y + 12'h001);
      if((io_y == (io_vTotal - 12'h001))) begin
        io_nestedY = 12'h0;
      end
    end
  end

  always @(*) begin
    if((io_x == 13'h0)) begin
      io_priority = 2'b00;
    end else begin
      if((io_x == (io_hTotal - 13'h0001))) begin
        io_priority = 2'b01;
      end else begin
        io_priority = 2'b10;
      end
    end
  end

  assign io_booleanRhs = (io_y == (io_vTotal - 12'h001));
  always @(*) begin
    io_mixedX = io_x;
    if((io_y == (io_vTotal - 12'h001))) begin
      io_mixedX = 13'h0;
    end
  end

  assign when_example_l42 = (io_x == _zz_when_example_l42);
  assign io_protectedBoolean = when_example_l42;
  always @(*) begin
    io_protectedX = (io_x + 13'h0001);
    if(when_example_l42) begin
      io_protectedX = 13'h0;
    end
  end

  assign when_LaneConditionCoverageExample_l81 = (io_x == _zz_when_LaneConditionCoverageExample_l81);
  always @(*) begin
    io_keptGeneratedX = (io_x + 13'h0001);
    if(when_LaneConditionCoverageExample_l81) begin
      io_keptGeneratedX = 13'h0;
    end
  end


endmodule
