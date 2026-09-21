// Generator : SpinalHDL dev    git head : fc61cad03b0ee849a5b198d1442f583c1e52a70c
// Component : ResizeNamingHierarchy
// Git hash  : fc61cad03b0ee849a5b198d1442f583c1e52a70c

`timescale 1ns/1ps 
module ResizeNamingHierarchy #(
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

  wire       [17:0]   first_wide;
  wire       [17:0]   first_namedWide;
  wire       [17:0]   first_zzNamedWide;
  wire       [17:0]   first_userPrefixWide;
  wire       [17:0]   first_collisionWide;
  wire       [COUNT_BITS-1:0]    first_roundTrip;
  wire       [17:0]   second_wide;
  wire       [17:0]   second_namedWide;
  wire       [17:0]   second_zzNamedWide;
  wire       [17:0]   second_userPrefixWide;
  wire       [17:0]   second_collisionWide;
  wire       [COUNT_BITS-1:0]    second_roundTrip;

  ResizeTemporaryNamingRepro #(
    .COUNT_BITS(COUNT_BITS)
  ) first (
    .a              (a[COUNT_BITS-1:0]                    ), //i
    .b              (b[COUNT_BITS-1:0]                    ), //i
    .wide           (first_wide[17:0]          ), //o
    .namedWide      (first_namedWide[17:0]     ), //o
    .zzNamedWide    (first_zzNamedWide[17:0]   ), //o
    .userPrefixWide (first_userPrefixWide[17:0]), //o
    .collisionWide  (first_collisionWide[17:0] ), //o
    .roundTrip      (first_roundTrip[COUNT_BITS-1:0]      )  //o
  );
  ResizeTemporaryNamingRepro #(
    .COUNT_BITS(COUNT_BITS)
  ) second (
    .a              (a[COUNT_BITS-1:0]                     ), //i
    .b              (b[COUNT_BITS-1:0]                     ), //i
    .wide           (second_wide[17:0]          ), //o
    .namedWide      (second_namedWide[17:0]     ), //o
    .zzNamedWide    (second_zzNamedWide[17:0]   ), //o
    .userPrefixWide (second_userPrefixWide[17:0]), //o
    .collisionWide  (second_collisionWide[17:0] ), //o
    .roundTrip      (second_roundTrip[COUNT_BITS-1:0]      )  //o
  );
  assign wide = first_wide;
  assign namedWide = second_namedWide;
  assign zzNamedWide = first_zzNamedWide;
  assign userPrefixWide = second_userPrefixWide;
  assign collisionWide = first_collisionWide;
  assign roundTrip = second_roundTrip;

endmodule
