module SymbolicDataShapes #(
  parameter integer WIDTH = 8
) (
  input  wire [WIDTH-1:0]    bits_in,
  output wire [WIDTH-1:0]    bits_out,
  input  wire [WIDTH-1:0]    uint_in,
  output wire [WIDTH-1:0]    uint_out,
  input  wire [WIDTH-1:0]    sint_in,
  output wire [WIDTH-1:0]    sint_out,
  input  wire [WIDTH-1:0]    bundle_in_bits,
  input  wire [WIDTH-1:0]    bundle_in_uint,
  input  wire [WIDTH-1:0]    bundle_in_sint,
  output wire [WIDTH-1:0]    bundle_out_bits,
  output wire [WIDTH-1:0]    bundle_out_uint,
  output wire [WIDTH-1:0]    bundle_out_sint,
  input  wire [WIDTH-1:0]    vec_in_0_bits,
  input  wire [WIDTH-1:0]    vec_in_0_uint,
  input  wire [WIDTH-1:0]    vec_in_0_sint,
  input  wire [WIDTH-1:0]    vec_in_1_bits,
  input  wire [WIDTH-1:0]    vec_in_1_uint,
  input  wire [WIDTH-1:0]    vec_in_1_sint,
  output wire [WIDTH-1:0]    vec_out_0_bits,
  output wire [WIDTH-1:0]    vec_out_0_uint,
  output wire [WIDTH-1:0]    vec_out_0_sint,
  output wire [WIDTH-1:0]    vec_out_1_bits,
  output wire [WIDTH-1:0]    vec_out_1_uint,
  output wire [WIDTH-1:0]    vec_out_1_sint,
  input  wire          stream_in_valid,
  output wire          stream_in_ready,
  input  wire [WIDTH-1:0]    stream_in_payload_bits,
  input  wire [WIDTH-1:0]    stream_in_payload_uint,
  input  wire [WIDTH-1:0]    stream_in_payload_sint,
  output wire          stream_out_valid,
  input  wire          stream_out_ready,
  output wire [WIDTH-1:0]    stream_out_payload_bits,
  output wire [WIDTH-1:0]    stream_out_payload_uint,
  output wire [WIDTH-1:0]    stream_out_payload_sint,
  input  wire          flow_in_valid,
  input  wire [WIDTH-1:0]    flow_in_payload_bits,
  input  wire [WIDTH-1:0]    flow_in_payload_uint,
  input  wire [WIDTH-1:0]    flow_in_payload_sint,
  output wire          flow_out_valid,
  output wire [WIDTH-1:0]    flow_out_payload_bits,
  output wire [WIDTH-1:0]    flow_out_payload_uint,
  output wire [WIDTH-1:0]    flow_out_payload_sint,
  input  wire          clk,
  output wire [WIDTH-1:0]    register_out_bits,
  output wire [WIDTH-1:0]    register_out_uint,
  output wire [WIDTH-1:0]    register_out_sint
);

  wire       [WIDTH-1:0]    internal_payload_bits;
  wire       [WIDTH-1:0]    internal_payload_uint;
  wire       [WIDTH-1:0]    internal_payload_sint;
  reg        [WIDTH-1:0]    payload_register_bits;
  reg        [WIDTH-1:0]    payload_register_uint;
  reg        [WIDTH-1:0]    payload_register_sint;

  assign bits_out = bits_in;
  assign uint_out = uint_in;
  assign sint_out = sint_in;
  assign internal_payload_bits = bundle_in_bits;
  assign internal_payload_uint = bundle_in_uint;
  assign internal_payload_sint = bundle_in_sint;
  assign bundle_out_bits = internal_payload_bits;
  assign bundle_out_uint = internal_payload_uint;
  assign bundle_out_sint = internal_payload_sint;
  assign vec_out_0_bits = vec_in_0_bits;
  assign vec_out_0_uint = vec_in_0_uint;
  assign vec_out_0_sint = vec_in_0_sint;
  assign vec_out_1_bits = vec_in_1_bits;
  assign vec_out_1_uint = vec_in_1_uint;
  assign vec_out_1_sint = vec_in_1_sint;
  assign stream_out_valid = stream_in_valid;
  assign stream_out_payload_bits = stream_in_payload_bits;
  assign stream_out_payload_uint = stream_in_payload_uint;
  assign stream_out_payload_sint = stream_in_payload_sint;
  assign stream_in_ready = stream_out_ready;
  assign flow_out_valid = flow_in_valid;
  assign flow_out_payload_bits = flow_in_payload_bits;
  assign flow_out_payload_uint = flow_in_payload_uint;
  assign flow_out_payload_sint = flow_in_payload_sint;
  assign register_out_bits = payload_register_bits;
  assign register_out_uint = payload_register_uint;
  assign register_out_sint = payload_register_sint;
  always @(posedge clk) begin
    payload_register_bits <= bundle_in_bits;
    payload_register_uint <= bundle_in_uint;
    payload_register_sint <= bundle_in_sint;
  end


endmodule
