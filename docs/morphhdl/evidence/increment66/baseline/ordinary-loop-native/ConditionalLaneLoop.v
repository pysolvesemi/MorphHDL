// Generator : SpinalHDL dev    git head : d2154190f9a739ae145f304474d2f6e4f28cc600
// Component : ConditionalLaneLoop
// Git hash  : d2154190f9a739ae145f304474d2f6e4f28cc600

`timescale 1ns/1ps

module ConditionalLaneLoop (
  input  wire [119:0]  previousData,
  input  wire [3:0]    previousMask,
  input  wire          clear,
  input  wire [2:0]    selected,
  input  wire [29:0]   pixel,
  output reg  [119:0]  assembled,
  output reg  [3:0]    mask
);

  wire                when_ParameterExtensionsBaselineWriter_l25;
  wire                when_ParameterExtensionsBaselineWriter_l25_1;
  wire                when_ParameterExtensionsBaselineWriter_l25_2;
  wire                when_ParameterExtensionsBaselineWriter_l25_3;

  always @(*) begin
    assembled = previousData;
    if(clear) begin
      assembled = 120'h0;
    end
    if(when_ParameterExtensionsBaselineWriter_l25) begin
      assembled[29 : 0] = pixel;
    end
    if(when_ParameterExtensionsBaselineWriter_l25_1) begin
      assembled[59 : 30] = pixel;
    end
    if(when_ParameterExtensionsBaselineWriter_l25_2) begin
      assembled[89 : 60] = pixel;
    end
    if(when_ParameterExtensionsBaselineWriter_l25_3) begin
      assembled[119 : 90] = pixel;
    end
  end

  always @(*) begin
    mask = previousMask;
    if(clear) begin
      mask = 4'b0000;
    end
    if(when_ParameterExtensionsBaselineWriter_l25) begin
      mask[0] = 1'b1;
    end
    if(when_ParameterExtensionsBaselineWriter_l25_1) begin
      mask[1] = 1'b1;
    end
    if(when_ParameterExtensionsBaselineWriter_l25_2) begin
      mask[2] = 1'b1;
    end
    if(when_ParameterExtensionsBaselineWriter_l25_3) begin
      mask[3] = 1'b1;
    end
  end

  assign when_ParameterExtensionsBaselineWriter_l25 = (selected == 3'b000);
  assign when_ParameterExtensionsBaselineWriter_l25_1 = (selected == 3'b001);
  assign when_ParameterExtensionsBaselineWriter_l25_2 = (selected == 3'b010);
  assign when_ParameterExtensionsBaselineWriter_l25_3 = (selected == 3'b011);

endmodule
