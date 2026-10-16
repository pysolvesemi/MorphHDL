module Reduction #(
  parameter integer COUNT = 1,
  parameter integer DATA_WIDTH = 8
) (
  input  wire          clk,
  input  wire          reset,
  input  wire          softReset,
  input  wire          enable,
  input wire [(DATA_WIDTH * COUNT)-1:0] terms,
  output wire  [DATA_WIDTH-1:0]    result
);




`ifndef SYNTHESIS
  generate
    if ((COUNT < 1) || (COUNT > 17) || (^COUNT === 1'bx)) begin : G_PARAMETER_DOMAIN_COUNT
      initial $fatal(1, "%s", "COUNT must be in 1..17");
    end
    if ((DATA_WIDTH < 1) || (DATA_WIDTH > 64) || (^DATA_WIDTH === 1'bx)) begin : G_PARAMETER_DOMAIN_DATA_WIDTH
      initial $fatal(1, "%s", "DATA_WIDTH must be in 1..64");
    end
  endgenerate
`endif


  genvar i;
  generate
    if (((COUNT) == (1))) begin : g_GEN_1_TRUE
      assign result = terms[(0) +: DATA_WIDTH];
    end else begin : g_GEN_1_FALSE
      wire [(DATA_WIDTH)-1:0] combined [0:(((COUNT + 1) / 2))-1];
      wire [((DATA_WIDTH) * (((COUNT + 1) / 2)))-1:0] combined_packed_view;
      Reduction #(
        .COUNT      (((COUNT + 1) / 2)),
        .DATA_WIDTH (DATA_WIDTH       )
      ) nextLevel (
        .clk       (clk                             ), //i
        .reset     (reset                           ), //i
        .softReset (softReset                       ), //i
        .enable    (enable                          ), //i
        .terms (combined_packed_view),
        .result    (result)  //o
      );
      for (i = 0; i < ((COUNT + 1) / 2); i = i + 1) begin : g_GEN_2
        assign combined_packed_view[(i * (DATA_WIDTH)) +: DATA_WIDTH] = combined[i];
        if (((2 * i + 1) < COUNT)) begin : g_GEN_3_TRUE
          assign combined[i] = (terms[(((2 * i)) * DATA_WIDTH) +: DATA_WIDTH] + terms[(((2 * i + 1)) * DATA_WIDTH) +: DATA_WIDTH]);
        end else begin : g_GEN_3_FALSE
          assign combined[i] = terms[(((2 * i)) * DATA_WIDTH) +: DATA_WIDTH];
        end
      end
    end
  endgenerate
endmodule
