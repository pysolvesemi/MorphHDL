// Generator : SpinalHDL dev    git head : 6350cbb5ec2e1122fbfd07548c0de70ea59a6c92
// Component : OptionalPipeline
// Git hash  : 6350cbb5ec2e1122fbfd07548c0de70ea59a6c92

`timescale 1ns/1ps 
module OptionalPipeline #(
  parameter integer USE_PIPELINE = 0,
  parameter integer WIDTH = 16
) (
  input  wire [WIDTH-1:0]   dataIn,
  output reg  [WIDTH-1:0]   dataOut,
  input  wire          clk,
  input  wire          reset
);





`ifndef SYNTHESIS
  generate
    if (((USE_PIPELINE) == (1))) begin : G_PARAMETER_LEGALITY_ACTIVE
    if (!(((WIDTH) >= (8)))) begin : G_PARAMETER_LEGALITY_0
      initial $fatal(1, "%s", "Pipeline mode requires WIDTH >= 8");
    end
    end
  endgenerate
`endif


  generate
    if (((USE_PIPELINE) == (1))) begin : g_if_ScopedLegalityArtifactWriter_l11_true
      reg        [WIDTH-1:0]   dataIn_regNext;
        always @(*) begin
          dataOut = dataIn_regNext;
        end
        always @(posedge clk) begin
          dataIn_regNext <= dataIn;
        end
    end else begin : g_if_ScopedLegalityArtifactWriter_l11_false
      always @(*) begin
          dataOut = dataIn;
        end
    end
  endgenerate
endmodule
