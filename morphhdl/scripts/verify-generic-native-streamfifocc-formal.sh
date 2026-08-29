#!/usr/bin/env bash
set -euo pipefail

workspace=${1:?usage: verify-generic-native-streamfifocc-formal.sh WORKSPACE}
workspace=$(cd "$workspace" && pwd)
command -v yosys >/dev/null

prepare_candidate() {
  local mode=$1 depth=$2
  local source="$workspace/candidate-$mode/candidate_${mode}.v"
  local top="MorphStreamFifoCC_${mode}"
  local output="$workspace/equiv_candidate_${mode}_${depth}.il"
  local script="$workspace/equiv_prepare_candidate_${mode}_${depth}.ys"
  cat >"$script" <<EOF
read_verilog -defer $source
chparam -set DEPTH $depth $top
hierarchy -check -top $top
flatten
proc
async2sync
opt
memory_dff
memory_map
opt_clean
setundef -undriven -zero
check -assert
rename -top candidate_${mode}_${depth}
write_rtlil $output
EOF
  yosys -Q -s "$script" >"$workspace/equiv_prepare_candidate_${mode}_${depth}.log"
}

prepare_reference() {
  local mode=$1 depth=$2
  local suffix="d${depth}_${mode}"
  local source="$workspace/reference-$suffix/reference_${suffix}.v"
  local top="ConcreteStreamFifoCC_${suffix}"
  local output="$workspace/equiv_reference_${mode}_${depth}.il"
  local script="$workspace/equiv_prepare_reference_${mode}_${depth}.ys"
  cat >"$script" <<EOF
read_verilog -defer $source
hierarchy -check -top $top
flatten
proc
async2sync
opt
memory_dff
memory_map
opt_clean
setundef -undriven -zero
check -assert
rename -top reference_${mode}_${depth}
write_rtlil $output
EOF
  yosys -Q -s "$script" >"$workspace/equiv_prepare_reference_${mode}_${depth}.log"
}

prove_configuration() {
  local mode=$1 depth=$2
  prepare_candidate "$mode" "$depth"
  prepare_reference "$mode" "$depth"
  local script="$workspace/equiv_prove_${mode}_${depth}.ys"
  cat >"$script" <<EOF
read_rtlil $workspace/equiv_reference_${mode}_${depth}.il
read_rtlil $workspace/equiv_candidate_${mode}_${depth}.il
equiv_make reference_${mode}_${depth} candidate_${mode}_${depth} equiv_${mode}_${depth}
hierarchy -check -top equiv_${mode}_${depth}
prep -top equiv_${mode}_${depth}
select -assert-any t:\$equiv
equiv_simple
equiv_induct -undef -seq 20
equiv_status -assert
EOF
  yosys -Q -s "$script" | tee "$workspace/equiv_prove_${mode}_${depth}.log"
  grep -Eq 'Equivalence successfully proven|Successfully proved' \
    "$workspace/equiv_prove_${mode}_${depth}.log"
}

for mode in direct buffered; do
  for depth in 4 8 16; do
    prove_configuration "$mode" "$depth"
  done
done

# Negative control: wrap one prepared candidate and invert only pushReady.
mode=direct
depth=4
mutation="$workspace/equiv_mutated_ready.v"
cat >"$mutation" <<'EOF'
module mutated_candidate_direct_4 (
  input wire io_pushValid,
  output wire io_pushReady,
  input wire [7:0] io_pushPayload,
  output wire io_popValid,
  input wire io_popReady,
  output wire [7:0] io_popPayload,
  output wire [4:0] io_pushOccupancy,
  output wire [4:0] io_popOccupancy,
  input wire push_clk,
  input wire push_reset,
  input wire pop_clk,
  input wire pop_reset
);
  wire native_pushReady;
  candidate_direct_4 wrapped (
    .io_pushValid(io_pushValid),
    .io_pushReady(native_pushReady),
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
  assign io_pushReady = ~native_pushReady;
endmodule
EOF

mutation_il="$workspace/equiv_mutated_ready.il"
mutation_prepare="$workspace/equiv_prepare_mutation.ys"
cat >"$mutation_prepare" <<EOF
read_rtlil $workspace/equiv_candidate_direct_4.il
read_verilog $mutation
hierarchy -check -top mutated_candidate_direct_4
flatten
proc
opt_clean
check -assert
write_rtlil $mutation_il
EOF
yosys -Q -s "$mutation_prepare" >"$workspace/equiv_prepare_mutation.log"

mutation_proof="$workspace/equiv_prove_mutation.ys"
cat >"$mutation_proof" <<EOF
read_rtlil $workspace/equiv_reference_direct_4.il
read_rtlil $mutation_il
equiv_make reference_direct_4 mutated_candidate_direct_4 equiv_mutated_ready
hierarchy -check -top equiv_mutated_ready
prep -top equiv_mutated_ready
select -assert-any t:\$equiv
equiv_simple
equiv_induct -undef -seq 4
equiv_status -assert
EOF
set +e
yosys -Q -s "$mutation_proof" >"$workspace/equiv_mutation.log" 2>&1
mutation_status=$?
set -e
if test "$mutation_status" = 0; then
  echo "negative-control ready inversion was incorrectly proven equivalent" >&2
  exit 1
fi
grep -Eq 'unproven|failed|Found [1-9][0-9]* unproven' "$workspace/equiv_mutation.log"
echo "PASS: all native StreamFifoCC configurations proved; ready mutation rejected"
