// Generator : SpinalHDL dev    git head : 155df6eb0e38ecce04a37de2067b0794702fcb83
// Component : ResizeTemporaryNamingRepro
// Git hash  : 155df6eb0e38ecce04a37de2067b0794702fcb83

`timescale 1ns/1ps 
module ResizeTemporaryNamingRepro #(
  parameter integer FIFO_LOG_DEPTH = 3
) (
  input  wire [(FIFO_LOG_DEPTH + 2)-1:0]    a,
  input  wire [(FIFO_LOG_DEPTH + 2)-1:0]    b,
  output wire [17:0]   wide,
  output wire [17:0]   namedWide,
  output wire [17:0]   zzNamedWide,
  output wire [(FIFO_LOG_DEPTH + 2)-1:0]    roundTrip
);

  wire       [(FIFO_LOG_DEPTH + 2)-1:0]    morphhdl_resize_source;
  wire       [17:0]   morphhdl_resize;
  wire       [(FIFO_LOG_DEPTH + 2)-1:0]    morphhdl_resize_1;
  wire       [(FIFO_LOG_DEPTH + 2)-1:0]    kept_difference;
  wire       [17:0]   morphhdl_resize_2;
  wire       [(FIFO_LOG_DEPTH + 2)-1:0]    _zz_user_kept;
  wire       [17:0]   morphhdl_resize_3;

  assign morphhdl_resize_source = (a - b);
  assign morphhdl_resize = {{(((18) > ((FIFO_LOG_DEPTH + 2))) ? ((18) - ((FIFO_LOG_DEPTH + 2))) : 0){1'b0}}, morphhdl_resize_source[((FIFO_LOG_DEPTH + 2))-1:0]};
  assign wide = morphhdl_resize;
  assign morphhdl_resize_1 = wide[((FIFO_LOG_DEPTH + 2))-1:0];
  assign roundTrip = morphhdl_resize_1;
  assign kept_difference = (a - b);
  assign morphhdl_resize_2 = {{(((18) > ((FIFO_LOG_DEPTH + 2))) ? ((18) - ((FIFO_LOG_DEPTH + 2))) : 0){1'b0}}, kept_difference[((FIFO_LOG_DEPTH + 2))-1:0]};
  assign namedWide = morphhdl_resize_2;
  assign _zz_user_kept = (a - b);
  assign morphhdl_resize_3 = {{(((18) > ((FIFO_LOG_DEPTH + 2))) ? ((18) - ((FIFO_LOG_DEPTH + 2))) : 0){1'b0}}, _zz_user_kept[((FIFO_LOG_DEPTH + 2))-1:0]};
  assign zzNamedWide = morphhdl_resize_3;

endmodule
