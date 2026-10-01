// Generator : SpinalHDL dev    git head : 0c2f07b4a49cacc918923663dbd0859f27b57c7d
// Component : RecordLink
// Git hash  : 0c2f07b4a49cacc918923663dbd0859f27b57c7d

`timescale 1ns/1ps 
module RecordLink #(
  parameter integer DATA_BITS = 32,
  parameter integer GENERATION_BITS = 8
) (
  input  wire [TOTAL_BITS-1:0] dataIn,
  output wire [TOTAL_BITS-1:0] dataOut
);
  localparam integer TOTAL_BITS = (DATA_BITS + GENERATION_BITS);


  assign dataOut = dataIn;

endmodule
