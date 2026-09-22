// Generator : SpinalHDL dev    git head : fc61cad03b0ee849a5b198d1442f583c1e52a70c
// Component : ResizeTemporaryNamingRepro
// Git hash  : fc61cad03b0ee849a5b198d1442f583c1e52a70c

`timescale 1ns/1ps 
module ResizeTemporaryNamingRepro #(
  parameter integer COUNT_BITS = 5
) (
  input  wire [COUNT_BITS-1:0]    a,
  input  wire [COUNT_BITS-1:0]    b,
  output wire [17:0]   wide,
  output wire [17:0]   namedWide,
  output wire [17:0]   zzNamedWide,
  output wire [17:0]   userPrefixWide,
  output wire [17:0]   collisionWide,
  output wire [COUNT_BITS-1:0]    roundTrip
);

  wire       [COUNT_BITS-1:0]    _zz_wide_1;
  wire       [17:0]   _zz_wide_2;
  wire       [COUNT_BITS-1:0]    _zz_roundTrip;
  wire       [COUNT_BITS-1:0]    kept_difference;
  wire       [17:0]   _zz_namedWide;
  wire       [COUNT_BITS-1:0]    _zz_user_kept;
  wire       [17:0]   _zz_zzNamedWide;
  wire       [COUNT_BITS-1:0]    morphhdl_resize_source;
  wire       [17:0]   _zz_userPrefixWide;
  wire       [COUNT_BITS-1:0]    _zz_wide;
  wire       [17:0]   _zz_collisionWide;

  assign _zz_wide_1 = (a - b);
  assign _zz_wide_2 = {{(((18) > (COUNT_BITS)) ? ((18) - (COUNT_BITS)) : 0){1'b0}}, _zz_wide_1[(COUNT_BITS)-1:0]};
  assign wide = _zz_wide_2;
  assign _zz_roundTrip = wide[(COUNT_BITS)-1:0];
  assign roundTrip = _zz_roundTrip;
  assign kept_difference = (a - b);
  assign _zz_namedWide = {{(((18) > (COUNT_BITS)) ? ((18) - (COUNT_BITS)) : 0){1'b0}}, kept_difference[(COUNT_BITS)-1:0]};
  assign namedWide = _zz_namedWide;
  assign _zz_user_kept = (a - b);
  assign _zz_zzNamedWide = {{(((18) > (COUNT_BITS)) ? ((18) - (COUNT_BITS)) : 0){1'b0}}, _zz_user_kept[(COUNT_BITS)-1:0]};
  assign zzNamedWide = _zz_zzNamedWide;
  assign morphhdl_resize_source = (a - b);
  assign _zz_userPrefixWide = {{(((18) > (COUNT_BITS)) ? ((18) - (COUNT_BITS)) : 0){1'b0}}, morphhdl_resize_source[(COUNT_BITS)-1:0]};
  assign userPrefixWide = _zz_userPrefixWide;
  assign _zz_wide = (a - b);
  assign _zz_collisionWide = {{(((18) > (COUNT_BITS)) ? ((18) - (COUNT_BITS)) : 0){1'b0}}, _zz_wide[(COUNT_BITS)-1:0]};
  assign collisionWide = _zz_collisionWide;

endmodule
