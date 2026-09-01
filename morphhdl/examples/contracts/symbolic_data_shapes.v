module SymbolicDataShapes #(
  parameter integer WIDTH = 8
) (
  input  wire [WIDTH-1:0] bits_in,
  input  wire [WIDTH-1:0] bundle_in_bits,
  input  wire [WIDTH-1:0] bundle_in_sint,
  input  wire [WIDTH-1:0] bundle_in_uint,
  input  wire          clk,
  input  wire [WIDTH-1:0] flow_in_payload_bits,
  input  wire [WIDTH-1:0] flow_in_payload_sint,
  input  wire [WIDTH-1:0] flow_in_payload_uint,
  input  wire          flow_in_valid,
  input  wire [WIDTH-1:0] sint_in,
  input  wire [WIDTH-1:0] stream_in_payload_bits,
  input  wire [WIDTH-1:0] stream_in_payload_sint,
  input  wire [WIDTH-1:0] stream_in_payload_uint,
  input  wire          stream_in_valid,
  input  wire          stream_out_ready,
  input  wire [WIDTH-1:0] uint_in,
  input wire [((WIDTH + WIDTH + WIDTH) * 2)-1:0] vec_in,
  output wire [WIDTH-1:0] bits_out,
  output wire [WIDTH-1:0] bundle_out_bits,
  output wire [WIDTH-1:0] bundle_out_sint,
  output wire [WIDTH-1:0] bundle_out_uint,
  output wire [WIDTH-1:0] flow_out_payload_bits,
  output wire [WIDTH-1:0] flow_out_payload_sint,
  output wire [WIDTH-1:0] flow_out_payload_uint,
  output wire          flow_out_valid,
  output wire [WIDTH-1:0] register_out_bits,
  output wire [WIDTH-1:0] register_out_sint,
  output wire [WIDTH-1:0] register_out_uint,
  output wire [WIDTH-1:0] sint_out,
  output wire          stream_in_ready,
  output wire [WIDTH-1:0] stream_out_payload_bits,
  output wire [WIDTH-1:0] stream_out_payload_sint,
  output wire [WIDTH-1:0] stream_out_payload_uint,
  output wire          stream_out_valid,
  output wire [WIDTH-1:0] uint_out,
  output wire [((WIDTH + WIDTH + WIDTH) * 2)-1:0] vec_out
);

  wire       [WIDTH-1:0] internal_payload_bits;
  wire       [WIDTH-1:0] internal_payload_sint;
  wire       [WIDTH-1:0] internal_payload_uint;
  reg        [WIDTH-1:0] payload_register_bits;
  reg        [WIDTH-1:0] payload_register_sint;
  reg        [WIDTH-1:0] payload_register_uint;

  assign bits_out = bits_in;
  assign uint_out = uint_in;
  assign sint_out = sint_in;
  assign internal_payload_bits = bundle_in_bits;
  assign internal_payload_uint = bundle_in_uint;
  assign internal_payload_sint = bundle_in_sint;
  assign bundle_out_bits = internal_payload_bits;
  assign bundle_out_uint = internal_payload_uint;
  assign bundle_out_sint = internal_payload_sint;
  assign vec_out = vec_in;
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
