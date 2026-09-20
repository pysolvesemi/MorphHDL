`timescale 1ns/1ps

module SIntCastHeavyBaseline (
  input  wire          clk,
  input  wire          enable,
  input  wire          choose_left,
  input  wire          write_enable,
  input  wire [1:0]    address,
  input  wire [7:0]    left,
  input  wire [7:0]    right,
  input  wire [7:0]    third,
  input  wire [7:0]    divisor,
  input  wire [7:0]    memory_write_data,
  output wire [7:0]    sum_out,
  output wire [7:0]    difference_out,
  output wire [15:0]   product_out,
  output wire [7:0]    quotient_out,
  output wire [7:0]    remainder_out,
  output wire [7:0]    nested_cast_out,
  output wire [7:0]    negative_out,
  output wire [7:0]    shifted_out,
  output wire [7:0]    mux_out,
  output wire [7:0]    combinational_out,
  output wire [3:0]    resized_out,
  output wire [3:0]    slice_out,
  output wire [7:0]    concat_out,
  output wire          less_out,
  output wire          greater_or_equal_out,
  output wire [7:0]    register_out,
  output wire [7:0]    procedural_out,
  output wire [7:0]    memory_out,
  output wire [7:0]    child_out,
  output wire [7:0]    blackbox_out
);

  reg        [7:0]    signed_memory_spinal_port1;
  wire       [7:0]    signed_child_dout;
  wire       [7:0]    signed_external_dout;
  wire       [7:0]    _zz_nested_signed;
  wire       [7:0]    _zz_nested_signed_1;
  wire       [3:0]    _zz_concat_out;
  wire       [3:0]    _zz_concat_out_1;
  wire       [7:0]    _zz_signed_memory_port;
  wire       [7:0]    signed_sum;
  wire       [7:0]    signed_difference;
  wire       [15:0]   signed_product;
  wire       [7:0]    signed_quotient;
  wire       [7:0]    signed_remainder;
  wire       [7:0]    nested_signed;
  wire       [7:0]    signed_negative;
  wire       [7:0]    signed_shifted;
  wire       [7:0]    signed_mux;
  wire       [3:0]    signed_resized;
  reg        [7:0]    signed_combinational;
  reg        [7:0]    signed_register;
  reg        [7:0]    signed_procedural;
  wire       [7:0]    signed_memory_read;
  reg [7:0] signed_memory [0:3];

  assign _zz_nested_signed = left;
  assign _zz_nested_signed_1 = ($signed(third) - $signed(right));
  assign _zz_concat_out = left[3 : 0];
  assign _zz_concat_out_1 = right[3 : 0];
  assign _zz_signed_memory_port = memory_write_data;
  always @(posedge clk) begin
    if(enable) begin
      signed_memory_spinal_port1 <= signed_memory[address];
    end
    if(write_enable) begin
      signed_memory[address] <= _zz_signed_memory_port;
    end
  end

  SIntCastHeavyChild signed_child (
    .din    (left[7:0]             ), //i
    .addend (third[7:0]            ), //i
    .dout   (signed_child_dout[7:0])  //o
  );
  SIntCastHeavyExternal #(
    .WIDTH (8)
  ) signed_external (
    .din  (signed_mux[7:0]          ), //i
    .dout (signed_external_dout[7:0])  //o
  );
  assign signed_sum = ($signed(left) + $signed(right));
  assign signed_difference = ($signed(left) - $signed(right));
  assign signed_product = ($signed(left) * $signed(right));
  assign signed_quotient = ($signed(left) / $signed(divisor));
  assign signed_remainder = ($signed(left) % $signed(divisor));
  assign nested_signed = ($signed(_zz_nested_signed) + $signed(_zz_nested_signed_1));
  assign signed_negative = (- left);
  assign signed_shifted = ($signed(left) >>> 2);
  assign signed_mux = (choose_left ? left : right);
  assign signed_resized = left[3:0];
  always @(*) begin
    signed_combinational = signed_difference;
    if(choose_left) begin
      signed_combinational = signed_sum;
    end
  end

  assign sum_out = signed_sum;
  assign difference_out = signed_difference;
  assign product_out = signed_product;
  assign quotient_out = signed_quotient;
  assign remainder_out = signed_remainder;
  assign nested_cast_out = nested_signed;
  assign negative_out = signed_negative;
  assign shifted_out = signed_shifted;
  assign mux_out = signed_mux;
  assign combinational_out = signed_combinational;
  assign resized_out = signed_resized;
  assign slice_out = left[3 : 0];
  assign concat_out = {_zz_concat_out,_zz_concat_out_1};
  assign less_out = ($signed(left) < $signed(right));
  assign greater_or_equal_out = ($signed(right) <= $signed(left));
  assign signed_memory_read = signed_memory_spinal_port1;
  assign register_out = signed_register;
  assign procedural_out = signed_procedural;
  assign memory_out = signed_memory_read;
  assign child_out = signed_child_dout;
  assign blackbox_out = signed_external_dout;
  always @(posedge clk) begin
    signed_register <= signed_mux;
    if(enable) begin
      signed_procedural <= nested_signed;
    end
  end


endmodule

module SIntCastHeavyChild (
  input  wire [7:0]    din,
  input  wire [7:0]    addend,
  output wire [7:0]    dout
);

  wire       [7:0]    child_sum;

  assign child_sum = ($signed(din) + $signed(addend));
  assign dout = child_sum;

endmodule
