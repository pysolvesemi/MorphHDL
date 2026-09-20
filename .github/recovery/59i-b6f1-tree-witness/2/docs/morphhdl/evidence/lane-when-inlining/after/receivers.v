module LaneReceiverCoverageExample #(
  parameter integer PPC4 = 0
) (
  input  wire          io_running,
  input  wire [12:0]   io_x,
  input  wire [11:0]   io_y,
  input  wire [15:0]   io_hActive,
  input  wire [15:0]   io_vActive,
  input  wire [3:0]    io_mask,
  input  wire          io_overrideEnable,
  input  wire [1:0]    io_index,
  output wire          io_whole,
  output wire [((PPC4 * 3) + 1)-1:0]    io_de,
  output wire [7:0]    io_ranged,
  output wire [3:0]    io_overlap,
  output wire [3:0]    io_dynamic,
  output wire [3:0]    io_protectedBits,
  output wire          io_sharedLess,
  output wire          io_sharedEqual
);

  wire       [15:0]   _zz_ranges;
  wire       [15:0]   _zz_ranges_1;
  wire       [15:0]   _zz_overlapping;
  wire       [15:0]   _zz_overlapping_1;
  wire       [15:0]   _zz_keptLane;
  wire       [15:0]   _zz_keptLane_1;
  wire       [15:0]   _zz_ranges_2;
  wire       [15:0]   _zz_ranges_3;
  wire       [15:0]   _zz_overlapping_2;
  wire       [15:0]   _zz_overlapping_3;
  wire       [15:0]   _zz_keptLane_2;
  wire       [15:0]   _zz_keptLane_3;
  wire       [15:0]   _zz_ranges_4;
  wire       [15:0]   _zz_ranges_5;
  wire       [15:0]   _zz_overlapping_4;
  wire       [15:0]   _zz_overlapping_5;
  wire       [15:0]   _zz_keptLane_4;
  wire       [15:0]   _zz_keptLane_5;
  wire       [15:0]   _zz_ranges_6;
  wire       [15:0]   _zz_ranges_7;
  wire       [15:0]   _zz_overlapping_6;
  wire       [15:0]   _zz_overlapping_7;
  wire       [15:0]   _zz_keptLane_6;
  wire       [15:0]   _zz_keptLane_7;
  wire       [15:0]   _zz_overlapping_8;
  wire       [15:0]   _zz_overlapping_9;
  wire       [15:0]   _zz_dynamicallySelected;
  wire       [15:0]   _zz_dynamicallySelected_1;
  reg        [3:0]    lanes;
  reg        [7:0]    ranges;
  reg        [3:0]    overlapping;
  reg        [3:0]    dynamicallySelected;
  (* keep *) reg        [3:0]    keptLane;
  wire       [((PPC4 * 3) + 1)-1:0]    morphhdl_resize;

  assign _zz_ranges = {3'd0, io_x};
  assign _zz_ranges_1 = {4'd0, io_y};
  assign _zz_overlapping = {3'd0, io_x};
  assign _zz_overlapping_1 = {4'd0, io_y};
  assign _zz_keptLane = {3'd0, io_x};
  assign _zz_keptLane_1 = {4'd0, io_y};
  assign _zz_ranges_2 = {3'd0, io_x};
  assign _zz_ranges_3 = {4'd0, io_y};
  assign _zz_overlapping_2 = {3'd0, io_x};
  assign _zz_overlapping_3 = {4'd0, io_y};
  assign _zz_keptLane_2 = {3'd0, io_x};
  assign _zz_keptLane_3 = {4'd0, io_y};
  assign _zz_ranges_4 = {3'd0, io_x};
  assign _zz_ranges_5 = {4'd0, io_y};
  assign _zz_overlapping_4 = {3'd0, io_x};
  assign _zz_overlapping_5 = {4'd0, io_y};
  assign _zz_keptLane_4 = {3'd0, io_x};
  assign _zz_keptLane_5 = {4'd0, io_y};
  assign _zz_ranges_6 = {3'd0, io_x};
  assign _zz_ranges_7 = {4'd0, io_y};
  assign _zz_overlapping_6 = {3'd0, io_x};
  assign _zz_overlapping_7 = {4'd0, io_y};
  assign _zz_keptLane_6 = {3'd0, io_x};
  assign _zz_keptLane_7 = {4'd0, io_y};
  assign _zz_overlapping_8 = {3'd0, io_x};
  assign _zz_overlapping_9 = {4'd0, io_y};
  assign _zz_dynamicallySelected = {3'd0, io_x};
  assign _zz_dynamicallySelected_1 = {4'd0, io_y};
  assign io_whole = ((io_running && ({3'd0, io_x} < io_hActive)) && ({4'd0, io_y} < io_vActive));
  always @(*) begin
    lanes[0] = (io_mask[0] && ((io_running && ({3'd0, io_x} < io_hActive)) && ({4'd0, io_y} < io_vActive)));
    lanes[1] = (io_mask[1] && ((io_running && ({3'd0, io_x} < io_hActive)) && ({4'd0, io_y} < io_vActive)));
    lanes[2] = (io_mask[2] && ((io_running && ({3'd0, io_x} < io_hActive)) && ({4'd0, io_y} < io_vActive)));
    lanes[3] = (io_mask[3] && ((io_running && ({3'd0, io_x} < io_hActive)) && ({4'd0, io_y} < io_vActive)));
  end

  always @(*) begin
    ranges[1 : 0] = {(io_running && (_zz_ranges < io_hActive)),(io_running && (_zz_ranges_1 < io_vActive))};
    ranges[3 : 2] = {(io_running && (_zz_ranges_2 < io_hActive)),(io_running && (_zz_ranges_3 < io_vActive))};
    ranges[5 : 4] = {(io_running && (_zz_ranges_4 < io_hActive)),(io_running && (_zz_ranges_5 < io_vActive))};
    ranges[7 : 6] = {(io_running && (_zz_ranges_6 < io_hActive)),(io_running && (_zz_ranges_7 < io_vActive))};
  end

  always @(*) begin
    overlapping[0] = (io_mask[0] && ((io_running && (_zz_overlapping < io_hActive)) && (_zz_overlapping_1 < io_vActive)));
    overlapping[1] = (io_mask[1] && ((io_running && (_zz_overlapping_2 < io_hActive)) && (_zz_overlapping_3 < io_vActive)));
    overlapping[2] = (io_mask[2] && ((io_running && (_zz_overlapping_4 < io_hActive)) && (_zz_overlapping_5 < io_vActive)));
    overlapping[3] = (io_mask[3] && ((io_running && (_zz_overlapping_6 < io_hActive)) && (_zz_overlapping_7 < io_vActive)));
    if(io_overrideEnable) begin
      overlapping[0] = (! ((io_running && (_zz_overlapping_8 < io_hActive)) && (_zz_overlapping_9 < io_vActive)));
    end
  end

  always @(*) begin
    dynamicallySelected[0] = io_mask[0];
    dynamicallySelected[1] = io_mask[1];
    dynamicallySelected[2] = io_mask[2];
    dynamicallySelected[3] = io_mask[3];
    if(io_overrideEnable) begin
      dynamicallySelected[io_index] = ((io_running && (_zz_dynamicallySelected < io_hActive)) && (_zz_dynamicallySelected_1 < io_vActive));
    end
  end

  always @(*) begin
    keptLane[0] = (io_mask[0] && ((io_running && (_zz_keptLane < io_hActive)) && (_zz_keptLane_1 < io_vActive)));
    keptLane[1] = (io_mask[1] && ((io_running && (_zz_keptLane_2 < io_hActive)) && (_zz_keptLane_3 < io_vActive)));
    keptLane[2] = (io_mask[2] && ((io_running && (_zz_keptLane_4 < io_hActive)) && (_zz_keptLane_5 < io_vActive)));
    keptLane[3] = (io_mask[3] && ((io_running && (_zz_keptLane_6 < io_hActive)) && (_zz_keptLane_7 < io_vActive)));
  end

  assign morphhdl_resize = lanes[(((PPC4 * 3) + 1))-1:0];
  assign io_de = morphhdl_resize;
  assign io_ranged = ranges;
  assign io_overlap = overlapping;
  assign io_dynamic = dynamicallySelected;
  assign io_protectedBits = keptLane;
  assign io_sharedLess = ({3'd0, io_x} < io_hActive);
  assign io_sharedEqual = ({3'd0, io_x} == (io_hActive - 16'h0001));

endmodule
