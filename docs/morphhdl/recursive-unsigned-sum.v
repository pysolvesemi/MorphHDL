module RecursiveUnsignedSum #(
  parameter integer COUNT = 16,
  parameter integer SUM_WIDTH = 36,
  parameter integer TERM_WIDTH = 32
) (
  input  wire [(COUNT * TERM_WIDTH)-1:0] terms,
  output reg  [SUM_WIDTH-1:0]   sum
);

  wire       [SUM_WIDTH-1:0] left_sum;
  wire       [SUM_WIDTH-1:0] right_sum;



`ifndef SYNTHESIS
  generate
    if ((COUNT < 1) || (COUNT > 17) || (^COUNT === 1'bx)) begin : G_PARAMETER_DOMAIN_COUNT
      initial begin $display("%s", "COUNT must be in 1..17"); $finish; end
    end
    if ((SUM_WIDTH < 1) || (SUM_WIDTH > 36) || (^SUM_WIDTH === 1'bx)) begin : G_PARAMETER_DOMAIN_SUM_WIDTH
      initial begin $display("%s", "SUM_WIDTH must be in 1..36"); $finish; end
    end
    if ((TERM_WIDTH < 1) || (TERM_WIDTH > 32) || (^TERM_WIDTH === 1'bx)) begin : G_PARAMETER_DOMAIN_TERM_WIDTH
      initial begin $display("%s", "TERM_WIDTH must be in 1..32"); $finish; end
    end
  endgenerate
`endif


  generate
    if (((COUNT) <= (1))) begin : g_if_RecursiveUnsignedSum_l1_true
      wire       [TERM_WIDTH-1:0] _zz_sum;
        wire       [TERM_WIDTH-1:0] _zz_sum_1;
        wire       [SUM_WIDTH-1:0] _zz_sum_2;
        assign _zz_sum = terms[0 +: TERM_WIDTH];
        assign _zz_sum_1 = _zz_sum;
        assign _zz_sum_2 = {{(((SUM_WIDTH) > (TERM_WIDTH)) ? ((SUM_WIDTH) - (TERM_WIDTH)) : 0){1'b0}}, _zz_sum_1[(((SUM_WIDTH) < (TERM_WIDTH)) ? (SUM_WIDTH) : (TERM_WIDTH))-1:0]};
        always @(*) begin
          sum = _zz_sum_2;
        end
    end else begin : g_if_RecursiveUnsignedSum_l1_false
      wire       [((COUNT / 2) * TERM_WIDTH)-1:0] _zz_terms;
        wire       [((COUNT - (COUNT / 2)) * TERM_WIDTH)-1:0] _zz_terms_1;
        RecursiveUnsignedSum #(
          .COUNT      ((COUNT / 2)),
          .TERM_WIDTH (TERM_WIDTH ),
          .SUM_WIDTH  (SUM_WIDTH  )
        ) left (
          .terms (_zz_terms[((COUNT / 2) * TERM_WIDTH)-1:0]), //i
          .sum   (left_sum[SUM_WIDTH-1:0]                  )  //o
        );
        RecursiveUnsignedSum #(
          .COUNT      ((COUNT - (COUNT / 2))),
          .TERM_WIDTH (TERM_WIDTH           ),
          .SUM_WIDTH  (SUM_WIDTH            )
        ) right (
          .terms (_zz_terms_1[((COUNT - (COUNT / 2)) * TERM_WIDTH)-1:0]), //i
          .sum   (right_sum[SUM_WIDTH-1:0]                             )  //o
        );
        always @(*) begin
          sum = (left_sum + right_sum);
        end
        assign _zz_terms = terms[0 +: ((COUNT / 2) * TERM_WIDTH)];
        assign _zz_terms_1 = terms[((COUNT / 2) * TERM_WIDTH) +: ((COUNT - (COUNT / 2)) * TERM_WIDTH)];
    end
  endgenerate
endmodule
