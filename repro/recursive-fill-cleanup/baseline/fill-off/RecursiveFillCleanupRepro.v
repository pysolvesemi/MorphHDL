// Generator : SpinalHDL dev    git head : 155df6eb0e38ecce04a37de2067b0794702fcb83
// Component : RecursiveFillCleanupRepro
// Git hash  : 155df6eb0e38ecce04a37de2067b0794702fcb83

`timescale 1ns/1ps 
module RecursiveFillCleanupRepro #(
  parameter integer FIFO_LOG_DEPTH = 3
) (
  input  wire          bypass,
  input  wire [(FIFO_LOG_DEPTH + 2)-1:0]    upper,
  input  wire [(FIFO_LOG_DEPTH + 2)-1:0]    lower,
  output wire [(FIFO_LOG_DEPTH + 2)-1:0]    writeFill,
  output wire [(FIFO_LOG_DEPTH + 2)-1:0]    readFill
);

  wire       [17:0]   _zz_selectedUpper;
  wire       [17:0]   _zz_readFill;
  wire       [17:0]   fifo_capacity;
  wire       [17:0]   upperWide;
  wire       [17:0]   morphhdl_resize;
  wire       [17:0]   selectedUpper;
  wire       [(FIFO_LOG_DEPTH + 2)-1:0]    clampedUpper;
  wire       [(FIFO_LOG_DEPTH + 2)-1:0]    morphhdl_resize_1;
  wire       [(FIFO_LOG_DEPTH + 2)-1:0]    zeroFill;

  assign _zz_selectedUpper = {13'd0, upper};
  assign _zz_readFill = {13'd0, lower};
  assign fifo_capacity = (((1 << (FIFO_LOG_DEPTH)) + 1));
  assign morphhdl_resize = {{(((18) > ((FIFO_LOG_DEPTH + 2))) ? ((18) - ((FIFO_LOG_DEPTH + 2))) : 0){1'b0}}, upper[((FIFO_LOG_DEPTH + 2))-1:0]};
  assign upperWide = morphhdl_resize;
  assign selectedUpper = ((fifo_capacity < _zz_selectedUpper) ? fifo_capacity : upperWide);
  assign morphhdl_resize_1 = selectedUpper[((FIFO_LOG_DEPTH + 2))-1:0];
  assign clampedUpper = morphhdl_resize_1;
  assign zeroFill = {(FIFO_LOG_DEPTH + 2){1'b0}};
  assign writeFill = (bypass ? zeroFill : clampedUpper);
  assign readFill = ((bypass || (fifo_capacity < _zz_readFill)) ? zeroFill : lower);

endmodule
