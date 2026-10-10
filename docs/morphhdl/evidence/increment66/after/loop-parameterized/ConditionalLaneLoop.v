// Generator : SpinalHDL dev    git head : b6e83061d8c2b1ece94e76abadc956a2201a99dc
// Component : ConditionalLaneLoop
// Git hash  : b6e83061d8c2b1ece94e76abadc956a2201a99dc

`timescale 1ns/1ps 
module ConditionalLaneLoop #(
  parameter integer LANES = 4,
  parameter integer PIXEL_WIDTH = 30
) (
  input  wire [(LANES * PIXEL_WIDTH)-1:0]  previousData,
  input  wire [LANES-1:0]    previousMask,
  input  wire          clear,
  input  wire [2:0]    selected,
  input  wire [PIXEL_WIDTH-1:0]   pixel,
  (* keep *) output reg  [(LANES * PIXEL_WIDTH)-1:0]  assembled,
  output reg  [LANES-1:0]    mask
);

  wire                when_ParameterizedProcess_l357;
  integer selected_1;
  integer selected_2;

  always @(*) begin
    assembled = previousData;
    if(clear) begin
      assembled = 120'h0;
    end
    for (selected_1 = 0; selected_1 < LANES; selected_1 = selected_1 + 1) begin
      if (selected == selected_1[2:0]) begin
        assembled[selected_1 * (PIXEL_WIDTH) +: PIXEL_WIDTH] = pixel;
      end
    end
  end

  always @(*) begin
    mask = previousMask;
    if(clear) begin
      mask = 4'b0000;
    end
    for (selected_2 = 0; selected_2 < LANES; selected_2 = selected_2 + 1) begin
      if (selected == selected_2[2:0]) begin
        mask[selected_2] = 1'b1;
      end
    end
  end

  assign when_ParameterizedProcess_l357 = (selected == 3'b000);

endmodule
