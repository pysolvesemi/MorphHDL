// Generator : SpinalHDL dev    git head : fc61cad03b0ee849a5b198d1442f583c1e52a70c
// Component : RecursiveFillCleanupRepro
// Git hash  : fc61cad03b0ee849a5b198d1442f583c1e52a70c

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

  wire       [17:0]   _zz_selectedUpper_1;
  wire       [17:0]   _zz_readFill;
  wire       [17:0]   fifo_capacity;
  wire       [17:0]   _zz_selectedUpper;
  wire       [17:0]   selectedUpper;
  wire       [(FIFO_LOG_DEPTH + 2)-1:0]    _zz_writeFill;

  assign _zz_selectedUpper_1 = {13'd0, upper};
  assign _zz_readFill = {13'd0, lower};
  assign fifo_capacity = (((1 << (FIFO_LOG_DEPTH)) + 1));
  assign _zz_selectedUpper = {{(((18) > ((FIFO_LOG_DEPTH + 2))) ? ((18) - ((FIFO_LOG_DEPTH + 2))) : 0){1'b0}}, upper[((FIFO_LOG_DEPTH + 2))-1:0]};
  assign selectedUpper = ((fifo_capacity < _zz_selectedUpper_1) ? fifo_capacity : _zz_selectedUpper);
  assign _zz_writeFill = selectedUpper[((FIFO_LOG_DEPTH + 2))-1:0];
  assign writeFill = (bypass ? 1'b0 : _zz_writeFill);
  assign readFill = ((bypass || (fifo_capacity < _zz_readFill)) ? 1'b0 : lower);

endmodule
