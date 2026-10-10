// Generator : SpinalHDL dev    git head : b6e83061d8c2b1ece94e76abadc956a2201a99dc
// Component : CdcSynchronizer
// Git hash  : b6e83061d8c2b1ece94e76abadc956a2201a99dc

`timescale 1ns/1ps 
module CdcSynchronizer #(
  parameter integer STAGES = 2,
  parameter integer WIDTH = 5
) (
  input  wire [WIDTH-1:0]    dataIn,
  output reg  [WIDTH-1:0]    dataOut,
  input  wire          clk,
  input  wire          resetn
);

  (* ASYNC_REG = "TRUE" , async_reg = "true" *) reg        [WIDTH-1:0]    stage_0;
  (* ASYNC_REG = "TRUE" *) reg        [WIDTH-1:0]    stage_1;
  (* ASYNC_REG = "TRUE" *) reg        [WIDTH-1:0]    stage_2;
  (* ASYNC_REG = "TRUE" *) reg        [WIDTH-1:0]    stage_3;


  always @(posedge clk or negedge resetn) begin
    if(!resetn) begin
      stage_0 <= {WIDTH{1'b0}};
      stage_1 <= {WIDTH{1'b0}};
      stage_2 <= {WIDTH{1'b0}};
      stage_3 <= {WIDTH{1'b0}};
    end else begin
      stage_0 <= dataIn;
      stage_1 <= stage_0;
      stage_2 <= stage_1;
      stage_3 <= stage_2;
    end
  end


`ifndef SYNTHESIS
  generate
    if ((STAGES < 2) || (STAGES > 4) || (^STAGES === 1'bx)) begin : G_PARAMETER_DOMAIN_STAGES
      initial $fatal(1, "%s", "STAGES must be in 2..4");
    end
    if ((WIDTH < 1) || (WIDTH > 18) || (^WIDTH === 1'bx)) begin : G_PARAMETER_DOMAIN_WIDTH
      initial $fatal(1, "%s", "WIDTH must be in 1..18");
    end
  endgenerate
`endif


  generate
    if (((STAGES) == (4))) begin : g_if_NativeCdcFormalTests_l27_true
      always @(*) begin
          dataOut = stage_3;
        end
    end else if (((STAGES) == (3))) begin : g_if_NativeCdcFormalTests_l28_true
      always @(*) begin
          dataOut = stage_2;
        end
    end else begin : g_if_NativeCdcFormalTests_l28_false
      always @(*) begin
          dataOut = stage_1;
        end
    end
  endgenerate
endmodule
