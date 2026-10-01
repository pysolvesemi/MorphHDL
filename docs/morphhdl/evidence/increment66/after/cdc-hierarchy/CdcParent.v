// Generator : SpinalHDL dev    git head : b6e83061d8c2b1ece94e76abadc956a2201a99dc
// Component : CdcParent
// Git hash  : b6e83061d8c2b1ece94e76abadc956a2201a99dc

`timescale 1ns/1ps 
module CdcParent #(
  parameter integer FIFO_LOG_DEPTH = 3,
  parameter integer SYNC_STAGES = 2
) (
  input  wire [(FIFO_LOG_DEPTH + 2)-1:0]    dataIn,
  input  wire [0:0]    flagIn,
  input  wire [(FIFO_LOG_DEPTH + 2)-1:0]    grayIn,
  output wire [(FIFO_LOG_DEPTH + 2)-1:0]    dataOut,
  output wire [0:0]    flagOut,
  output wire [(FIFO_LOG_DEPTH + 2)-1:0]    decoded,
  input  wire          clk,
  input  wire          resetn
);

  wire       [(FIFO_LOG_DEPTH + 2)-1:0]    dataSync_dataOut;
  wire       [0:0]    flagSync_dataOut;
  wire       [(FIFO_LOG_DEPTH + 2)-1:0]    decoder_binary;

  CdcSynchronizer #(
    .STAGES(SYNC_STAGES),
    .WIDTH((FIFO_LOG_DEPTH + 2))
  ) dataSync (
    .dataIn  (dataIn[(FIFO_LOG_DEPTH + 2)-1:0]          ), //i
    .dataOut (dataSync_dataOut[(FIFO_LOG_DEPTH + 2)-1:0]), //o
    .clk     (clk                  ), //i
    .resetn  (resetn               )  //i
  );
  CdcSynchronizer_1 #(
    .STAGES(SYNC_STAGES),
    .WIDTH(1)
  ) flagSync (
    .dataIn  (flagIn          ), //i
    .dataOut (flagSync_dataOut), //o
    .clk     (clk             ), //i
    .resetn  (resetn          )  //i
  );
  GrayDecoder #(
    .WIDTH((FIFO_LOG_DEPTH + 2))
  ) decoder (
    .gray   (grayIn[(FIFO_LOG_DEPTH + 2)-1:0]        ), //i
    .binary (decoder_binary[(FIFO_LOG_DEPTH + 2)-1:0])  //o
  );
  assign dataOut = dataSync_dataOut;
  assign flagOut = flagSync_dataOut;
  assign decoded = decoder_binary;

`ifndef SYNTHESIS
  generate
    if ((FIFO_LOG_DEPTH < 2) || (FIFO_LOG_DEPTH > 16) || (^FIFO_LOG_DEPTH === 1'bx)) begin : G_PARAMETER_DOMAIN_FIFO_LOG_DEPTH
      initial $fatal(1, "%s", "FIFO_LOG_DEPTH must be in 2..16");
    end
    if ((SYNC_STAGES < 2) || (SYNC_STAGES > 4) || (^SYNC_STAGES === 1'bx)) begin : G_PARAMETER_DOMAIN_SYNC_STAGES
      initial $fatal(1, "%s", "SYNC_STAGES must be in 2..4");
    end
  endgenerate
`endif

endmodule
