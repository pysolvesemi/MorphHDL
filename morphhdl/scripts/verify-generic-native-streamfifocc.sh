#!/usr/bin/env bash
set -euo pipefail

workspace=${1:?usage: verify-generic-native-streamfifocc.sh WORKSPACE}
workspace=$(cd "$workspace" && pwd)

for tool in verilator yosys iverilog vvp; do
  command -v "$tool" >/dev/null
 done

for mode in direct buffered; do
  candidate="$workspace/candidate-$mode/candidate_${mode}.v"
  top="MorphStreamFifoCC_${mode}"
  test -s "$candidate"

  for depth in 4 8 16; do
    verilator \
      --lint-only \
      --language 1364-2001 \
      -Wno-DECLFILENAME \
      -Wno-WIDTH \
      -Wno-UNUSED \
      --top-module "$top" \
      -GDEPTH="$depth" \
      "$candidate"

    yosys -Q -p "
      read_verilog -defer $candidate
      chparam -set DEPTH $depth $top
      hierarchy -check -top $top
      proc
      memory
      opt
      synth -top $top
      check -assert
      stat
    " >"$workspace/yosys_${mode}_${depth}.log"

    tb="$workspace/tb_${mode}_${depth}.v"
    cat >"$tb" <<EOF
\`timescale 1ns/1ps
module tb;
  localparam integer DEPTH = $depth;
  localparam integer TOTAL = DEPTH * 3;

  reg push_clk;
  reg push_reset;
  reg pop_clk;
  reg pop_reset;
  reg io_pushValid;
  wire io_pushReady;
  reg [7:0] io_pushPayload;
  wire io_popValid;
  reg io_popReady;
  wire [7:0] io_popPayload;
  wire [4:0] io_pushOccupancy;
  wire [4:0] io_popOccupancy;

  reg [7:0] expected [0:TOTAL-1];
  integer write_count;
  integer read_count;
  integer pop_cycles;
  integer saw_backpressure;

  $top #(
    .DEPTH(DEPTH)
  ) dut (
    .io_pushValid(io_pushValid),
    .io_pushReady(io_pushReady),
    .io_pushPayload(io_pushPayload),
    .io_popValid(io_popValid),
    .io_popReady(io_popReady),
    .io_popPayload(io_popPayload),
    .io_pushOccupancy(io_pushOccupancy),
    .io_popOccupancy(io_popOccupancy),
    .push_clk(push_clk),
    .push_reset(push_reset),
    .pop_clk(pop_clk),
    .pop_reset(pop_reset)
  );

  initial begin
    push_clk = 1'b0;
    forever #5 push_clk = ~push_clk;
  end

  initial begin
    pop_clk = 1'b0;
    forever #7 pop_clk = ~pop_clk;
  end

  initial begin
    push_reset = 1'b1;
    pop_reset = 1'b1;
    io_pushValid = 1'b0;
    io_pushPayload = 8'h31;
    io_popReady = 1'b0;
    write_count = 0;
    read_count = 0;
    pop_cycles = 0;
    saw_backpressure = 0;

    #23 push_reset = 1'b0;
    #14 pop_reset = 1'b0;
    #3 io_pushValid = 1'b1;
  end

  always @(posedge push_clk) begin
    if (push_reset) begin
      io_pushValid <= 1'b0;
      io_pushPayload <= 8'h31;
      write_count <= 0;
      saw_backpressure <= 0;
    end else begin
      if (write_count < TOTAL)
        io_pushValid <= 1'b1;
      if (io_pushValid && !io_pushReady)
        saw_backpressure <= 1;
      if (io_pushValid && io_pushReady) begin
        expected[write_count] <= io_pushPayload;
        write_count <= write_count + 1;
        io_pushPayload <= io_pushPayload + 8'h17;
        if (write_count + 1 == TOTAL)
          io_pushValid <= 1'b0;
      end
    end
  end

  always @(posedge pop_clk) begin
    if (pop_reset) begin
      io_popReady <= 1'b0;
      read_count <= 0;
      pop_cycles <= 0;
    end else begin
      pop_cycles <= pop_cycles + 1;
      if (pop_cycles > DEPTH + 5)
        io_popReady <= ((pop_cycles % 5) != 1);
      if (io_popValid && io_popReady) begin
        if (io_popPayload !== expected[read_count]) begin
          \$display("FAIL mode=$mode depth=%0d index=%0d got=%02x expected=%02x", DEPTH, read_count, io_popPayload, expected[read_count]);
          \$fatal(1);
        end
        read_count <= read_count + 1;
        if (read_count + 1 == TOTAL) begin
          if (!saw_backpressure) begin
            \$display("FAIL mode=$mode depth=%0d never observed full backpressure", DEPTH);
            \$fatal(1);
          end
          \$display("PASS mode=$mode depth=%0d transferred=%0d", DEPTH, TOTAL);
          #20 \$finish;
        end
      end
    end
  end

  initial begin
    #250000;
    \$display("FAIL mode=$mode depth=%0d watchdog write=%0d read=%0d", DEPTH, write_count, read_count);
    \$fatal(1);
  end
endmodule
EOF

    image="$workspace/sim_${mode}_${depth}"
    iverilog -g2001 -s tb -o "$image" "$candidate" "$tb"
    vvp "$image" | tee "$workspace/sim_${mode}_${depth}.log"
    grep -Fq "PASS mode=$mode depth=$depth" "$workspace/sim_${mode}_${depth}.log"
  done
done
