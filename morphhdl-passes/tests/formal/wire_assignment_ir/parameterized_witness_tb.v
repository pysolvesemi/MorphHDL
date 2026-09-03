`timescale 1ns/1ps

module Wa03ParameterizedWitnessTb;
  parameter WIDTH = 8;
  parameter DEPTH = 3;

  reg                  clk;
  reg                  reset;
  reg                  io_push_valid;
  wire                 io_push_ready;
  reg  [WIDTH-1:0]     io_push_payload;
  wire                 io_pop_valid;
  reg                  io_pop_ready;
  wire [WIDTH-1:0]     io_pop_payload;
  reg                  io_flush;
  wire [3:0]           io_occupancy;
  wire [3:0]           io_availability;

  ParameterizedStreamFifo #(
    .WIDTH(WIDTH),
    .DEPTH(DEPTH)
  ) dut (
    .io_push_valid(io_push_valid),
    .io_push_ready(io_push_ready),
    .io_push_payload(io_push_payload),
    .io_pop_valid(io_pop_valid),
    .io_pop_ready(io_pop_ready),
    .io_pop_payload(io_pop_payload),
    .io_flush(io_flush),
    .io_occupancy(io_occupancy),
    .io_availability(io_availability),
    .clk(clk),
    .reset(reset)
  );

  always #5 clk = ~clk;

  task fail_test;
    input [8*96-1:0] message;
    begin
      $display("WA03_SIM_FAIL witness WIDTH=%0d DEPTH=%0d %0s", WIDTH, DEPTH, message);
      $finish;
    end
  endtask

  initial begin
    clk = 1'b0;
    reset = 1'b1;
    io_push_valid = 1'b0;
    io_push_payload = {WIDTH{1'b0}};
    io_pop_ready = 1'b0;
    io_flush = 1'b0;

    repeat (2) @(posedge clk);
    #1;
    reset = 1'b0;
    @(posedge clk);
    #1;

    if (io_occupancy !== 4'd0)
      fail_test("occupancy was not zero after reset");
    if (io_availability !== DEPTH)
      fail_test("availability did not equal depth after reset");

    io_push_payload = {WIDTH{1'b1}};
    io_push_valid = 1'b1;
    while (!io_push_ready)
      @(posedge clk);
    @(posedge clk);
    #1;
    io_push_valid = 1'b0;

    if (io_occupancy !== 4'd1)
      fail_test("single push did not increment occupancy");
    if (!io_pop_valid)
      fail_test("single push did not make pop valid");
    if (io_pop_payload !== {WIDTH{1'b1}})
      fail_test("single pushed payload was not retained");

    io_pop_ready = 1'b1;
    @(posedge clk);
    #1;
    io_pop_ready = 1'b0;

    if (io_occupancy !== 4'd0)
      fail_test("single pop did not decrement occupancy");

    io_push_payload = {WIDTH{1'b0}};
    io_push_valid = 1'b1;
    @(posedge clk);
    #1;
    io_push_valid = 1'b0;
    if (io_occupancy !== 4'd1)
      fail_test("second push did not increment occupancy");

    io_flush = 1'b1;
    @(posedge clk);
    #1;
    io_flush = 1'b0;
    if (io_occupancy !== 4'd0)
      fail_test("flush did not clear occupancy");

    $display("WA03_SIM_PASS witness WIDTH=%0d DEPTH=%0d", WIDTH, DEPTH);
    $finish;
  end
endmodule
