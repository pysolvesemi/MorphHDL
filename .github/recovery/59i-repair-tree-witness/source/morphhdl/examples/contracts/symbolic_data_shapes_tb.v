module SymbolicDataShapesCase #(
  parameter integer WIDTH = 8,
  parameter integer CASE_ID = 0,
  parameter integer USE_DEFAULT = 0
) (
  output reg done,
  output reg failed
);

  localparam integer VEC_WIDTH = 6 * WIDTH;

  reg clk;
  reg [WIDTH-1:0] bits_in;
  reg [WIDTH-1:0] bundle_in_bits;
  reg [WIDTH-1:0] bundle_in_sint;
  reg [WIDTH-1:0] bundle_in_uint;
  reg [WIDTH-1:0] flow_in_payload_bits;
  reg [WIDTH-1:0] flow_in_payload_sint;
  reg [WIDTH-1:0] flow_in_payload_uint;
  reg flow_in_valid;
  reg [WIDTH-1:0] sint_in;
  reg [WIDTH-1:0] stream_in_payload_bits;
  reg [WIDTH-1:0] stream_in_payload_sint;
  reg [WIDTH-1:0] stream_in_payload_uint;
  reg stream_in_valid;
  reg stream_out_ready;
  reg [WIDTH-1:0] uint_in;
  reg [VEC_WIDTH-1:0] vec_in;

  wire [WIDTH-1:0] bits_out;
  wire [WIDTH-1:0] bundle_out_bits;
  wire [WIDTH-1:0] bundle_out_sint;
  wire [WIDTH-1:0] bundle_out_uint;
  wire [WIDTH-1:0] flow_out_payload_bits;
  wire [WIDTH-1:0] flow_out_payload_sint;
  wire [WIDTH-1:0] flow_out_payload_uint;
  wire flow_out_valid;
  wire [WIDTH-1:0] register_out_bits;
  wire [WIDTH-1:0] register_out_sint;
  wire [WIDTH-1:0] register_out_uint;
  wire [WIDTH-1:0] sint_out;
  wire stream_in_ready;
  wire [WIDTH-1:0] stream_out_payload_bits;
  wire [WIDTH-1:0] stream_out_payload_sint;
  wire [WIDTH-1:0] stream_out_payload_uint;
  wire stream_out_valid;
  wire [WIDTH-1:0] uint_out;
  wire [VEC_WIDTH-1:0] vec_out;

  function [WIDTH-1:0] pattern;
    input integer seed;
    integer bit_index;
    begin
      for (bit_index = 0; bit_index < WIDTH; bit_index = bit_index + 1) begin
        pattern[bit_index] = (bit_index + seed) % 2;
      end
    end
  endfunction

  function [VEC_WIDTH-1:0] vec_pattern;
    input integer seed;
    integer bit_index;
    begin
      for (bit_index = 0; bit_index < VEC_WIDTH; bit_index = bit_index + 1) begin
        vec_pattern[bit_index] = (bit_index + seed) % 2;
      end
    end
  endfunction

  generate
    if (USE_DEFAULT == 1) begin : g_default
      SymbolicDataShapes dut (
        .bits_in                   (bits_in),
        .bundle_in_bits            (bundle_in_bits),
        .bundle_in_sint            (bundle_in_sint),
        .bundle_in_uint            (bundle_in_uint),
        .clk                       (clk),
        .flow_in_payload_bits      (flow_in_payload_bits),
        .flow_in_payload_sint      (flow_in_payload_sint),
        .flow_in_payload_uint      (flow_in_payload_uint),
        .flow_in_valid             (flow_in_valid),
        .sint_in                   (sint_in),
        .stream_in_payload_bits    (stream_in_payload_bits),
        .stream_in_payload_sint    (stream_in_payload_sint),
        .stream_in_payload_uint    (stream_in_payload_uint),
        .stream_in_valid           (stream_in_valid),
        .stream_out_ready          (stream_out_ready),
        .uint_in                   (uint_in),
        .vec_in                    (vec_in),
        .bits_out                  (bits_out),
        .bundle_out_bits           (bundle_out_bits),
        .bundle_out_sint           (bundle_out_sint),
        .bundle_out_uint           (bundle_out_uint),
        .flow_out_payload_bits     (flow_out_payload_bits),
        .flow_out_payload_sint     (flow_out_payload_sint),
        .flow_out_payload_uint     (flow_out_payload_uint),
        .flow_out_valid            (flow_out_valid),
        .register_out_bits         (register_out_bits),
        .register_out_sint         (register_out_sint),
        .register_out_uint         (register_out_uint),
        .sint_out                  (sint_out),
        .stream_in_ready           (stream_in_ready),
        .stream_out_payload_bits   (stream_out_payload_bits),
        .stream_out_payload_sint   (stream_out_payload_sint),
        .stream_out_payload_uint   (stream_out_payload_uint),
        .stream_out_valid          (stream_out_valid),
        .uint_out                  (uint_out),
        .vec_out                   (vec_out)
      );
    end else begin : g_override
      SymbolicDataShapes #(
        .WIDTH(WIDTH)
      ) dut (
        .bits_in                   (bits_in),
        .bundle_in_bits            (bundle_in_bits),
        .bundle_in_sint            (bundle_in_sint),
        .bundle_in_uint            (bundle_in_uint),
        .clk                       (clk),
        .flow_in_payload_bits      (flow_in_payload_bits),
        .flow_in_payload_sint      (flow_in_payload_sint),
        .flow_in_payload_uint      (flow_in_payload_uint),
        .flow_in_valid             (flow_in_valid),
        .sint_in                   (sint_in),
        .stream_in_payload_bits    (stream_in_payload_bits),
        .stream_in_payload_sint    (stream_in_payload_sint),
        .stream_in_payload_uint    (stream_in_payload_uint),
        .stream_in_valid           (stream_in_valid),
        .stream_out_ready          (stream_out_ready),
        .uint_in                   (uint_in),
        .vec_in                    (vec_in),
        .bits_out                  (bits_out),
        .bundle_out_bits           (bundle_out_bits),
        .bundle_out_sint           (bundle_out_sint),
        .bundle_out_uint           (bundle_out_uint),
        .flow_out_payload_bits     (flow_out_payload_bits),
        .flow_out_payload_sint     (flow_out_payload_sint),
        .flow_out_payload_uint     (flow_out_payload_uint),
        .flow_out_valid            (flow_out_valid),
        .register_out_bits         (register_out_bits),
        .register_out_sint         (register_out_sint),
        .register_out_uint         (register_out_uint),
        .sint_out                  (sint_out),
        .stream_in_ready           (stream_in_ready),
        .stream_out_payload_bits   (stream_out_payload_bits),
        .stream_out_payload_sint   (stream_out_payload_sint),
        .stream_out_payload_uint   (stream_out_payload_uint),
        .stream_out_valid          (stream_out_valid),
        .uint_out                  (uint_out),
        .vec_out                   (vec_out)
      );
    end
  endgenerate

  task check_combinational_paths;
    begin
      if ((bits_out !== bits_in) ||
          (uint_out !== uint_in) ||
          (sint_out !== sint_in) ||
          (bundle_out_bits !== bundle_in_bits) ||
          (bundle_out_uint !== bundle_in_uint) ||
          (bundle_out_sint !== bundle_in_sint) ||
          (vec_out !== vec_in) ||
          (stream_out_valid !== stream_in_valid) ||
          (stream_out_payload_bits !== stream_in_payload_bits) ||
          (stream_out_payload_uint !== stream_in_payload_uint) ||
          (stream_out_payload_sint !== stream_in_payload_sint) ||
          (stream_in_ready !== stream_out_ready) ||
          (flow_out_valid !== flow_in_valid) ||
          (flow_out_payload_bits !== flow_in_payload_bits) ||
          (flow_out_payload_uint !== flow_in_payload_uint) ||
          (flow_out_payload_sint !== flow_in_payload_sint)) begin
        $display("FAIL: SymbolicDataShapes combinational case %0d width %0d", CASE_ID, WIDTH);
        failed = 1'b1;
      end
    end
  endtask

  initial begin
    done = 1'b0;
    failed = 1'b0;
    clk = 1'b0;
    bits_in = pattern(1);
    uint_in = pattern(2);
    sint_in = pattern(3);
    bundle_in_bits = pattern(4);
    bundle_in_uint = pattern(5);
    bundle_in_sint = pattern(6);
    vec_in = vec_pattern(7);
    stream_in_valid = CASE_ID[0];
    stream_out_ready = !CASE_ID[0];
    stream_in_payload_bits = pattern(13);
    stream_in_payload_uint = pattern(14);
    stream_in_payload_sint = pattern(15);
    flow_in_valid = !CASE_ID[0];
    flow_in_payload_bits = pattern(16);
    flow_in_payload_uint = pattern(17);
    flow_in_payload_sint = pattern(18);
    #1;
    check_combinational_paths;

    clk = 1'b1;
    #1;
    clk = 1'b0;
    #1;
    if ((register_out_bits !== bundle_in_bits) ||
        (register_out_uint !== bundle_in_uint) ||
        (register_out_sint !== bundle_in_sint)) begin
      $display("FAIL: SymbolicDataShapes register case %0d width %0d", CASE_ID, WIDTH);
      failed = 1'b1;
    end

    done = 1'b1;
  end

endmodule

module SymbolicDataShapesTb;

  wire default_done;
  wire default_failed;
  wire minimum_done;
  wire minimum_failed;
  wire awkward_done;
  wire awkward_failed;
  wire maximum_done;
  wire maximum_failed;

  SymbolicDataShapesCase #(
    .WIDTH(8),
    .CASE_ID(0),
    .USE_DEFAULT(1)
  ) default_case (
    .done(default_done),
    .failed(default_failed)
  );

  SymbolicDataShapesCase #(
    .WIDTH(1),
    .CASE_ID(1)
  ) minimum_case (
    .done(minimum_done),
    .failed(minimum_failed)
  );

  SymbolicDataShapesCase #(
    .WIDTH(13),
    .CASE_ID(2)
  ) awkward_case (
    .done(awkward_done),
    .failed(awkward_failed)
  );

  SymbolicDataShapesCase #(
    .WIDTH(64),
    .CASE_ID(3)
  ) maximum_case (
    .done(maximum_done),
    .failed(maximum_failed)
  );

  initial begin
    wait (default_done && minimum_done && awkward_done && maximum_done);
    #1;
    if (default_failed || minimum_failed || awkward_failed || maximum_failed) begin
      $display("FAIL: SymbolicDataShapes");
    end else begin
      $display("PASS: SymbolicDataShapes");
    end
    $finish;
  end

endmodule
