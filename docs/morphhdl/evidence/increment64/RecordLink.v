// Generator : SpinalHDL dev    git head : e3d02da846e2ed75d9648999114b21c267cc80de
// Component : RecordLink
// Git hash  : e3d02da846e2ed75d9648999114b21c267cc80de

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
