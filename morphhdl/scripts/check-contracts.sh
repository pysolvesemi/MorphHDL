#!/usr/bin/env bash

set -euo pipefail

require_tools=0
generated_dir=""
using_reviewed_goldens=0
live_phase_id_files=()

usage() {
  echo "Usage: $0 [--require-tools] [--generated-dir <directory>] [--live-phase-ids <file>]..." >&2
}

while (( $# > 0 )); do
  case "$1" in
    --require-tools)
      require_tools=1
      shift
      ;;
    --generated-dir)
      if (( $# < 2 )); then
        usage
        exit 2
      fi
      generated_dir="$2"
      shift 2
      ;;
    --live-phase-ids)
      if (( $# < 2 )); then
        usage
        exit 2
      fi
      live_phase_id_files+=("$2")
      shift 2
      ;;
    *)
      usage
      exit 2
      ;;
  esac
done

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(git -C "$script_dir" rev-parse --show-toplevel)"
contract_file="$repo_root/morphhdl/contracts/verilog-2001.properties"
parity_file="$repo_root/morphhdl/contracts/validation-parity.tsv"
operator_file="$repo_root/morphhdl/contracts/parameter-operators.tsv"
examples_dir="$repo_root/morphhdl/examples/contracts"

if [[ -z "$generated_dir" ]]; then
  generated_dir="$examples_dir"
  using_reviewed_goldens=1
fi

if [[ ! -d "$generated_dir" ]]; then
  echo "Generated RTL directory does not exist: $generated_dir" >&2
  exit 1
fi

generated_contracts=(
  parameterized_wire.v
  derived_width.v
  parameter_forwarding.v
  lane_array.v
  conditional_forwarding.v
  comparison_routing.v
  conditional_width.v
  boolean_forwarding.v
  boolean_locals.v
  case_routing.v
  runtime_mux.v
  synchronous_register.v
  asynchronous_register.v
  synchronous_enabled_register.v
  asynchronous_enabled_register.v
  single_port_memory.v
)

if (( using_reviewed_goldens == 0 )); then
  expected_artifact_files="$(printf '%s f\n' "${generated_contracts[@]}" | sort)"
  actual_artifact_files="$(
    find "$generated_dir" -mindepth 1 -maxdepth 1 -printf '%f %y\n' | sort
  )"
  if [[ "$actual_artifact_files" != "$expected_artifact_files" ]]; then
    echo "Generated RTL directory has an unexpected file inventory" >&2
    diff -u \
      <(printf '%s\n' "$expected_artifact_files") \
      <(printf '%s\n' "$actual_artifact_files") || true
    exit 1
  fi
fi

for filename in "${generated_contracts[@]}"; do
  generated_file="$generated_dir/$filename"
  golden_file="$examples_dir/$filename"
  if [[ ! -s "$generated_file" ]]; then
    echo "Missing or empty generated RTL: $generated_file" >&2
    exit 1
  fi
  if ! cmp -s "$generated_file" "$golden_file"; then
    echo "Generated RTL differs from its reviewed golden file: $filename" >&2
    diff -u "$golden_file" "$generated_file" || true
    exit 1
  fi
done

parameterized_wire_file="$generated_dir/parameterized_wire.v"
derived_width_file="$generated_dir/derived_width.v"
parameter_forwarding_file="$generated_dir/parameter_forwarding.v"
lane_array_file="$generated_dir/lane_array.v"
conditional_forwarding_file="$generated_dir/conditional_forwarding.v"
comparison_routing_file="$generated_dir/comparison_routing.v"
conditional_width_file="$generated_dir/conditional_width.v"
boolean_forwarding_file="$generated_dir/boolean_forwarding.v"
boolean_locals_file="$generated_dir/boolean_locals.v"
case_routing_file="$generated_dir/case_routing.v"
runtime_mux_file="$generated_dir/runtime_mux.v"
synchronous_register_file="$generated_dir/synchronous_register.v"
asynchronous_register_file="$generated_dir/asynchronous_register.v"
synchronous_enabled_register_file="$generated_dir/synchronous_enabled_register.v"
asynchronous_enabled_register_file="$generated_dir/asynchronous_enabled_register.v"
single_port_memory_file="$generated_dir/single_port_memory.v"

parity_args=("$parity_file")
for live_phase_id_file in "${live_phase_id_files[@]}"; do
  parity_args+=(--live-phase-ids "$live_phase_id_file")
done
python3 "$repo_root/morphhdl/scripts/check-validation-parity.py" "${parity_args[@]}"
python3 "$repo_root/morphhdl/scripts/check-parameter-operators.py" "$operator_file"

design_files=(
  "$parameterized_wire_file"
  "$derived_width_file"
  "$parameter_forwarding_file"
  "$lane_array_file"
  "$conditional_forwarding_file"
  "$comparison_routing_file"
  "$conditional_width_file"
  "$boolean_forwarding_file"
  "$boolean_locals_file"
  "$case_routing_file"
  "$runtime_mux_file"
  "$synchronous_register_file"
  "$asynchronous_register_file"
  "$synchronous_enabled_register_file"
  "$asynchronous_enabled_register_file"
  "$single_port_memory_file"
)

all_verilog_files=(
  "${design_files[@]}"
  "$examples_dir/parameterized_wire_tb.v"
  "$examples_dir/derived_width_tb.v"
  "$examples_dir/parameter_forwarding_tb.v"
  "$examples_dir/lane_array_tb.v"
  "$examples_dir/conditional_forwarding_tb.v"
  "$examples_dir/comparison_routing_tb.v"
  "$examples_dir/conditional_width_tb.v"
  "$examples_dir/boolean_forwarding_tb.v"
  "$examples_dir/boolean_locals_tb.v"
  "$examples_dir/case_routing_tb.v"
  "$examples_dir/runtime_mux_tb.v"
  "$examples_dir/synchronous_register_tb.v"
  "$examples_dir/asynchronous_register_tb.v"
  "$examples_dir/synchronous_enabled_register_tb.v"
  "$examples_dir/asynchronous_enabled_register_tb.v"
  "$examples_dir/single_port_memory_tb.v"
)

read_property() {
  local key="$1"
  awk -F= -v key="$key" '
    $1 == key {
      sub(/^[^=]*=/, "")
      print
      found = 1
    }
    END { if (!found) exit 1 }
  ' "$contract_file"
}

require_property() {
  local key="$1"
  local expected="$2"
  local actual
  actual="$(read_property "$key")"
  if [[ "$actual" != "$expected" ]]; then
    echo "Contract property '$key' is '$actual', expected '$expected'" >&2
    exit 1
  fi
}

require_property contract.version 1
require_property profile.name verilog-2001-strict
require_property profile.standard IEEE-1364-2001
require_property profile.abi flat
require_property backend.canonical_ir ParamRTL
require_property backend.initial_emitter direct-verilog
require_property parameter.boolean_encoding integer
require_property parameter.integer_comparison true
require_property parameter.integer_conditional true
require_property parameter.address_width portable-ceiling-log2
require_property parameter.address_width_direct_flatten_depth 5
require_property parameter.address_width_expansion_limit 4096
require_property port.conditional_presence false
require_property structure.module_instance true
require_property structure.named_parameter_binding true
require_property structure.named_boolean_parameter_binding true
require_property structure.named_port_binding true
require_property structure.generate_for true
require_property structure.generate_if true
require_property structure.generate_case true
require_property implementation.generate_if true
require_property implementation.boolean_parameter_forwarding true
require_property parameter.boolean_local true
require_property implementation.boolean_local_parameter true
require_property implementation.generate_case true
require_property process.combinational always-at-star
require_property implementation.combinational_if true
require_property process.sequential edge-sensitive-always
require_property process.sequential_edge positive
require_property process.synchronous_reset active-high
require_property process.synchronous_reset_value zero
require_property implementation.synchronous_register true
require_property process.asynchronous_reset active-high
require_property process.asynchronous_reset_value zero
require_property implementation.asynchronous_register true
require_property process.clock_enable active-high-hold
require_property implementation.synchronous_enabled_register true
require_property implementation.asynchronous_enabled_register true
require_property memory.parameterized_depth true
require_property memory.address_width depth-derived
require_property memory.address_capacity_guard true
require_property memory.read_latency synchronous-one-cycle
require_property memory.read_during_write read-first
require_property memory.unwritten_read unspecified
require_property memory.surplus_address_read zero
require_property memory.surplus_address_write ignored
require_property memory.initialization false
require_property memory.reset false
require_property memory.read_enable active-high-hold
require_property memory.write_mask false
require_property implementation.synchronous_read_first_single_port_memory true

for file in "${all_verilog_files[@]}"; do
  if [[ ! -s "$file" ]]; then
    echo "Missing or empty contract fixture: $file" >&2
    exit 1
  fi
done

forbidden_words=(
  logic
  always_comb
  always_ff
  always_latch
  typedef
  struct
  union
  interface
  modport
  package
)

for word in "${forbidden_words[@]}"; do
  if grep -En "(^|[^[:alnum:]_])${word}([^[:alnum:]_]|$)" "${all_verilog_files[@]}"; then
    echo "SystemVerilog-only keyword found in strict Verilog fixture: $word" >&2
    exit 1
  fi
done

if grep -En '\$(clog2|bits)' "${all_verilog_files[@]}"; then
  echo "SystemVerilog sizing helper found in strict Verilog fixture" >&2
  exit 1
fi

if grep -En 'parameter[[:space:]]+type|assert[[:space:]]+property' "${all_verilog_files[@]}"; then
  echo "SystemVerilog-only declaration found in strict Verilog fixture" >&2
  exit 1
fi

if grep -En '__v_' "${all_verilog_files[@]}"; then
  echo "Configuration-specialized module naming found in contract fixture" >&2
  exit 1
fi

module_names="$(
  awk '
    /^[[:space:]]*module[[:space:]]+/ {
      name = $2
      sub(/[;#(].*$/, "", name)
      print name
    }
  ' "${all_verilog_files[@]}"
)"

duplicate_modules="$(printf '%s\n' "$module_names" | sort | uniq -d)"
if [[ -n "$duplicate_modules" ]]; then
  echo "Duplicate logical module definitions:" >&2
  echo "$duplicate_modules" >&2
  exit 1
fi

expected_modules=(
  ParameterizedWire
  ParameterizedWireTb
  DerivedWidth
  DerivedWidthTb
  ForwardingLeaf
  ParameterForwarding
  ParameterForwardingTb
  PixelLane
  LaneArray
  LaneArrayTb
  ConditionalLeaf
  ConditionalForwarding
  ConditionalForwardingTb
  HighRoute
  LowRoute
  ComparisonRouting
  ComparisonRoutingTb
  ConditionalWidth
  ConditionalWidthTb
  BooleanHighRoute
  BooleanLowRoute
  BooleanRoute
  BooleanForwarding
  BooleanForwardingTb
  BooleanLocalHighRoute
  BooleanLocalLowRoute
  BooleanLocalRoute
  BooleanLocals
  BooleanLocalsTb
  CaseDefaultRoute
  CaseOneRoute
  CaseZeroRoute
  CaseRouting
  CaseRoutingTb
  RuntimeMux
  RuntimeMuxTb
  SynchronousRegister
  SynchronousRegisterTb
  AsynchronousRegister
  AsynchronousRegisterTb
  SynchronousEnabledRegister
  SynchronousEnabledRegisterTb
  AsynchronousEnabledRegister
  AsynchronousEnabledRegisterTb
  SinglePortMemory
  SinglePortMemoryTb
)

for module_name in "${expected_modules[@]}"; do
  if ! printf '%s\n' "$module_names" | grep -qx "$module_name"; then
    echo "Expected contract module is missing: $module_name" >&2
    exit 1
  fi
done

if ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+WIDTH' "${design_files[0]}"; then
  echo "ParameterizedWire does not declare integer WIDTH" >&2
  exit 1
fi

if ! grep -Eq 'localparam[[:space:]]+integer[[:space:]]+TOTAL_WIDTH[[:space:]]*=[[:space:]]*LANES[[:space:]]*\*[[:space:]]*DATA_WIDTH' "${design_files[1]}" ||
   ! grep -Eq 'localparam[[:space:]]+integer[[:space:]]+PADDED_WIDTH[[:space:]]*=[[:space:]]*TOTAL_WIDTH[[:space:]]*\+[[:space:]]*3' "${design_files[1]}"; then
  echo "DerivedWidth does not retain its symbolic local-parameter expressions" >&2
  exit 1
fi

if ! grep -Eq 'localparam[[:space:]]+integer[[:space:]]+TOTAL_WIDTH[[:space:]]*=[[:space:]]*LANES[[:space:]]*\*[[:space:]]*DATA_WIDTH' "${design_files[2]}" ||
   ! grep -Eq '\.WIDTH[[:space:]]*\([[:space:]]*TOTAL_WIDTH[[:space:]]*\)' "${design_files[2]}" ||
   ! grep -Eq '\)[[:space:]]+forwarded_inst[[:space:]]*\(' "${design_files[2]}" ||
   ! grep -Eq '\.din[[:space:]]*\([[:space:]]*din[[:space:]]*\)' "${design_files[2]}" ||
   ! grep -Eq '\.dout[[:space:]]*\([[:space:]]*dout[[:space:]]*\)' "${design_files[2]}"; then
  echo "ParameterForwarding does not retain its named symbolic child bindings" >&2
  exit 1
fi

if ! grep -Eq 'genvar[[:space:]]+lane[[:space:]]*;' "${design_files[3]}" ||
   ! grep -Eq 'for[[:space:]]*\([[:space:]]*lane[[:space:]]*=[[:space:]]*0[[:space:]]*;[[:space:]]*lane[[:space:]]*<[[:space:]]*LANES[[:space:]]*;' "${design_files[3]}" ||
   ! grep -Eq 'begin[[:space:]]*:[[:space:]]*g_lane' "${design_files[3]}" ||
   ! grep -Eq 'lane[[:space:]]*\*[[:space:]]*DATA_WIDTH[[:space:]]*\+:[[:space:]]*DATA_WIDTH' "${design_files[3]}" ||
   [[ "$(grep -Ec '\)[[:space:]]+lane_inst[[:space:]]*\(' "${design_files[3]}")" != "1" ]]; then
  echo "LaneArray does not retain one named generate-for template and indexed part-select" >&2
  exit 1
fi

if ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+ENABLE[[:space:]]*=[[:space:]]*1' "$conditional_forwarding_file" ||
   ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+WIDTH[[:space:]]*=[[:space:]]*8' "$conditional_forwarding_file" ||
   ! grep -Eq 'if[[:space:]]*\([[:space:]]*ENABLE[[:space:]]*==[[:space:]]*1[[:space:]]*\)[[:space:]]*begin[[:space:]]*:[[:space:]]*g_enabled' "$conditional_forwarding_file" ||
   ! grep -Eq 'end[[:space:]]+else[[:space:]]+begin[[:space:]]*:[[:space:]]*g_disabled' "$conditional_forwarding_file" ||
   [[ "$(grep -Ec '\)[[:space:]]+selected_inst[[:space:]]*\(' "$conditional_forwarding_file")" != "2" ]] ||
   [[ "$(grep -Ec '\.WIDTH[[:space:]]*\([[:space:]]*WIDTH[[:space:]]*\)' "$conditional_forwarding_file")" != "2" ]] ||
   [[ "$(grep -Ec '\.din[[:space:]]*\([[:space:]]*din[[:space:]]*\)' "$conditional_forwarding_file")" != "2" ]] ||
   [[ "$(grep -Ec '\.dout[[:space:]]*\([[:space:]]*dout[[:space:]]*\)' "$conditional_forwarding_file")" != "2" ]]; then
  echo "ConditionalForwarding does not retain both named generate-if branches and bindings" >&2
  exit 1
fi

if ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+SELECT[[:space:]]*=[[:space:]]*8' "$comparison_routing_file" ||
   ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+THRESHOLD[[:space:]]*=[[:space:]]*5' "$comparison_routing_file" ||
   ! grep -Eq 'if[[:space:]]*\([[:space:]]*SELECT[[:space:]]*>=[[:space:]]*THRESHOLD[[:space:]]*\)[[:space:]]*begin[[:space:]]*:[[:space:]]*g_high' "$comparison_routing_file" ||
   ! grep -Eq 'end[[:space:]]+else[[:space:]]+begin[[:space:]]*:[[:space:]]*g_low' "$comparison_routing_file" ||
   [[ "$(grep -Ec 'HighRoute[[:space:]]+selected_inst[[:space:]]*\(' "$comparison_routing_file")" != "1" ]] ||
   [[ "$(grep -Ec 'LowRoute[[:space:]]+selected_inst[[:space:]]*\(' "$comparison_routing_file")" != "1" ]] ||
   [[ "$(grep -Ec '\.high_in[[:space:]]*\([[:space:]]*din[[:space:]]*\)' "$comparison_routing_file")" != "1" ]] ||
   [[ "$(grep -Ec '\.high_out[[:space:]]*\([[:space:]]*dout[[:space:]]*\)' "$comparison_routing_file")" != "1" ]] ||
   [[ "$(grep -Ec '\.low_in[[:space:]]*\([[:space:]]*din[[:space:]]*\)' "$comparison_routing_file")" != "1" ]] ||
   [[ "$(grep -Ec '\.low_out[[:space:]]*\([[:space:]]*dout[[:space:]]*\)' "$comparison_routing_file")" != "1" ]]; then
  echo "ComparisonRouting does not retain the integer comparison and distinct generate-if branches" >&2
  exit 1
fi

if ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+WIDE[[:space:]]*=[[:space:]]*1' "$conditional_width_file" ||
   ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+NARROW_WIDTH[[:space:]]*=[[:space:]]*4' "$conditional_width_file" ||
   ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+WIDE_WIDTH[[:space:]]*=[[:space:]]*12' "$conditional_width_file" ||
   ! grep -Eq 'localparam[[:space:]]+integer[[:space:]]+ACTIVE_WIDTH[[:space:]]*=[[:space:]]*\([[:space:]]*WIDE[[:space:]]*==[[:space:]]*1[[:space:]]*\)[[:space:]]*\?[[:space:]]*WIDE_WIDTH[[:space:]]*:[[:space:]]*NARROW_WIDTH' "$conditional_width_file" ||
   [[ "$(grep -Ec '\[ACTIVE_WIDTH-1:0\][[:space:]]+(din|dout)' "$conditional_width_file")" != "2" ]]; then
  echo "ConditionalWidth does not retain its typed conditional local width" >&2
  exit 1
fi

if ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+SELECT[[:space:]]*=[[:space:]]*0' "$boolean_forwarding_file" ||
   ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+ENABLE[[:space:]]*=[[:space:]]*1' "$boolean_forwarding_file" ||
   ! grep -Eq 'localparam[[:space:]]+integer[[:space:]]+EFFECTIVE_WIDTH[[:space:]]*=[[:space:]]*WIDTH[[:space:]]*\+[[:space:]]*OFFSET' "$boolean_forwarding_file" ||
   ! grep -Eq '\.SELECT[[:space:]]*\([[:space:]]*\([[:space:]]*ENABLE[[:space:]]*==[[:space:]]*1[[:space:]]*&&[[:space:]]*EFFECTIVE_WIDTH[[:space:]]*>=[[:space:]]*LIMIT[[:space:]]*\)[[:space:]]*\?[[:space:]]*1[[:space:]]*:[[:space:]]*0[[:space:]]*\)' "$boolean_forwarding_file" ||
   ! grep -Eq 'if[[:space:]]*\([[:space:]]*SELECT[[:space:]]*==[[:space:]]*1[[:space:]]*\)[[:space:]]*begin[[:space:]]*:[[:space:]]*g_high' "$boolean_forwarding_file" ||
   ! grep -Eq 'end[[:space:]]+else[[:space:]]+begin[[:space:]]*:[[:space:]]*g_low' "$boolean_forwarding_file" ||
   [[ "$(grep -Ec '\)[[:space:]]+route_inst[[:space:]]*\(' "$boolean_forwarding_file")" != "1" ]]; then
  echo "BooleanForwarding does not retain its typed parent predicate and named Boolean child binding" >&2
  exit 1
fi

if ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+ENABLE[[:space:]]*=[[:space:]]*1' "$boolean_locals_file" ||
   ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+WIDTH[[:space:]]*=[[:space:]]*8' "$boolean_locals_file" ||
   ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+LIMIT[[:space:]]*=[[:space:]]*8' "$boolean_locals_file" ||
   ! grep -Eq 'localparam[[:space:]]+integer[[:space:]]+EFFECTIVE_WIDTH[[:space:]]*=[[:space:]]*WIDTH' "$boolean_locals_file" ||
   ! grep -Eq 'localparam[[:space:]]+integer[[:space:]]+WIDTH_OK[[:space:]]*=' "$boolean_locals_file" ||
   ! grep -Eq 'EFFECTIVE_WIDTH[[:space:]]*>=[[:space:]]*LIMIT' "$boolean_locals_file" ||
   ! grep -Eq 'localparam[[:space:]]+integer[[:space:]]+ROUTE_HIGH[[:space:]]*=' "$boolean_locals_file" ||
   ! grep -Eq 'ENABLE[[:space:]]*==[[:space:]]*1[[:space:]]*&&[[:space:]]*WIDTH_OK[[:space:]]*==[[:space:]]*1' "$boolean_locals_file" ||
   ! grep -Eq 'localparam[[:space:]]+integer[[:space:]]+ROUTE_CODE[[:space:]]*=' "$boolean_locals_file" ||
   ! grep -Eq 'ROUTE_HIGH[[:space:]]*==[[:space:]]*1' "$boolean_locals_file" ||
   ! grep -Eq '\.SELECT[[:space:]]*\([[:space:]]*\([[:space:]]*ROUTE_CODE[[:space:]]*==[[:space:]]*1[[:space:]]*\)[[:space:]]*\?[[:space:]]*1[[:space:]]*:[[:space:]]*0[[:space:]]*\)' "$boolean_locals_file" ||
   ! grep -Eq 'if[[:space:]]*\([[:space:]]*SELECT[[:space:]]*==[[:space:]]*1[[:space:]]*\)[[:space:]]*begin[[:space:]]*:[[:space:]]*g_high' "$boolean_locals_file" ||
   ! grep -Eq 'end[[:space:]]+else[[:space:]]+begin[[:space:]]*:[[:space:]]*g_low' "$boolean_locals_file" ||
   [[ "$(grep -Ec '\)[[:space:]]+route_inst[[:space:]]*\(' "$boolean_locals_file")" != "1" ]]; then
  echo "BooleanLocals does not retain its mixed dependency-first local chain and Boolean child binding" >&2
  exit 1
fi

if ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+MODE[[:space:]]*=[[:space:]]*0' "$case_routing_file" ||
   ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+OFFSET[[:space:]]*=[[:space:]]*0' "$case_routing_file" ||
   ! grep -Eq 'localparam[[:space:]]+integer[[:space:]]+SELECTOR[[:space:]]*=[[:space:]]*MODE[[:space:]]*\+[[:space:]]*OFFSET' "$case_routing_file" ||
   ! grep -Eq 'case[[:space:]]*\([[:space:]]*SELECTOR[[:space:]]*\)' "$case_routing_file" ||
   ! grep -Eq '0:[[:space:]]*begin[[:space:]]*:[[:space:]]*g_zero' "$case_routing_file" ||
   ! grep -Eq '1:[[:space:]]*begin[[:space:]]*:[[:space:]]*g_one' "$case_routing_file" ||
   ! grep -Eq 'default:[[:space:]]*begin[[:space:]]*:[[:space:]]*g_default' "$case_routing_file" ||
   [[ "$(grep -Ec '[[:space:]]selected_inst[[:space:]]*\(' "$case_routing_file")" != "3" ]] ||
   [[ "$(grep -Ec 'endcase' "$case_routing_file")" != "1" ]]; then
  echo "CaseRouting does not retain two explicit choices and its mandatory default branch" >&2
  exit 1
fi

if ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+WIDTH[[:space:]]*=[[:space:]]*8' "$runtime_mux_file" ||
   ! grep -Eq 'input[[:space:]]+wire[[:space:]]+\[0:0\][[:space:]]+sel' "$runtime_mux_file" ||
   ! grep -Eq 'output[[:space:]]+reg[[:space:]]+\[WIDTH-1:0\][[:space:]]+result' "$runtime_mux_file" ||
   ! grep -Eq 'always[[:space:]]+@\*[[:space:]]+begin[[:space:]]*:[[:space:]]*p_runtime_mux' "$runtime_mux_file" ||
   ! grep -Eq "if[[:space:]]*\\([[:space:]]*sel[[:space:]]*==[[:space:]]*1'b1[[:space:]]*\\)[[:space:]]*begin" "$runtime_mux_file" ||
   ! grep -Eq 'result[[:space:]]*=[[:space:]]*data_true[[:space:]]*;' "$runtime_mux_file" ||
   ! grep -Eq 'end[[:space:]]+else[[:space:]]+begin' "$runtime_mux_file" ||
   ! grep -Eq 'result[[:space:]]*=[[:space:]]*data_false[[:space:]]*;' "$runtime_mux_file" ||
   grep -Eq 'always_comb|always_ff|always_latch|<=' "$runtime_mux_file"; then
  echo "RuntimeMux does not retain one complete blocking-assignment always-at-star process" >&2
  exit 1
fi

if ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+WIDTH[[:space:]]*=[[:space:]]*8' "$synchronous_register_file" ||
   ! grep -Eq 'input[[:space:]]+wire[[:space:]]+\[0:0\][[:space:]]+clk' "$synchronous_register_file" ||
   ! grep -Eq 'input[[:space:]]+wire[[:space:]]+\[0:0\][[:space:]]+reset' "$synchronous_register_file" ||
   ! grep -Eq 'output[[:space:]]+reg[[:space:]]+\[WIDTH-1:0\][[:space:]]+data_out' "$synchronous_register_file" ||
   ! grep -Eq 'always[[:space:]]+@\([[:space:]]*posedge[[:space:]]+clk[[:space:]]*\)[[:space:]]+begin[[:space:]]*:[[:space:]]*p_sync_register' "$synchronous_register_file" ||
   ! grep -Eq "if[[:space:]]*\\([[:space:]]*reset[[:space:]]*==[[:space:]]*1'b1[[:space:]]*\\)[[:space:]]*begin" "$synchronous_register_file" ||
   ! grep -Eq "data_out[[:space:]]*<=[[:space:]]*\\{WIDTH\\{1'b0\\}\\}[[:space:]]*;" "$synchronous_register_file" ||
   ! grep -Eq 'end[[:space:]]+else[[:space:]]+begin' "$synchronous_register_file" ||
   ! grep -Eq 'data_out[[:space:]]*<=[[:space:]]*data_in[[:space:]]*;' "$synchronous_register_file" ||
   [[ "$(grep -Ec 'always[[:space:]]+@\(' "$synchronous_register_file")" != "1" ]] ||
   [[ "$(grep -Ec 'data_out[[:space:]]*<=' "$synchronous_register_file")" != "2" ]] ||
   grep -Eq 'always_comb|always_ff|always_latch|always[[:space:]]+@\*|data_out[[:space:]]*=[^=]' "$synchronous_register_file"; then
  echo "SynchronousRegister does not retain one complete positive-edge synchronous reset-to-zero process" >&2
  exit 1
fi

if ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+WIDTH[[:space:]]*=[[:space:]]*8' "$asynchronous_register_file" ||
   ! grep -Eq 'input[[:space:]]+wire[[:space:]]+\[0:0\][[:space:]]+clk' "$asynchronous_register_file" ||
   ! grep -Eq 'input[[:space:]]+wire[[:space:]]+\[0:0\][[:space:]]+reset' "$asynchronous_register_file" ||
   ! grep -Eq 'output[[:space:]]+reg[[:space:]]+\[WIDTH-1:0\][[:space:]]+data_out' "$asynchronous_register_file" ||
   ! grep -Eq 'always[[:space:]]+@\([[:space:]]*posedge[[:space:]]+clk[[:space:]]+or[[:space:]]+posedge[[:space:]]+reset[[:space:]]*\)[[:space:]]+begin[[:space:]]*:[[:space:]]*p_async_register' "$asynchronous_register_file" ||
   ! grep -Eq "if[[:space:]]*\\([[:space:]]*reset[[:space:]]*==[[:space:]]*1'b1[[:space:]]*\\)[[:space:]]*begin" "$asynchronous_register_file" ||
   ! grep -Eq "data_out[[:space:]]*<=[[:space:]]*\\{WIDTH\\{1'b0\\}\\}[[:space:]]*;" "$asynchronous_register_file" ||
   ! grep -Eq 'end[[:space:]]+else[[:space:]]+begin' "$asynchronous_register_file" ||
   ! grep -Eq 'data_out[[:space:]]*<=[[:space:]]*data_in[[:space:]]*;' "$asynchronous_register_file" ||
   [[ "$(grep -Ec 'always[[:space:]]+@\(' "$asynchronous_register_file")" != "1" ]] ||
   [[ "$(grep -Ec 'data_out[[:space:]]*<=' "$asynchronous_register_file")" != "2" ]] ||
   grep -Eq 'always_comb|always_ff|always_latch|always[[:space:]]+@\*|data_out[[:space:]]*=[^=]' "$asynchronous_register_file"; then
  echo "AsynchronousRegister does not retain one complete positive-edge asynchronous reset-to-zero process" >&2
  exit 1
fi

if ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+WIDTH[[:space:]]*=[[:space:]]*8' "$synchronous_enabled_register_file" ||
   ! grep -Eq 'input[[:space:]]+wire[[:space:]]+\[0:0\][[:space:]]+clk' "$synchronous_enabled_register_file" ||
   ! grep -Eq 'input[[:space:]]+wire[[:space:]]+\[0:0\][[:space:]]+reset' "$synchronous_enabled_register_file" ||
   ! grep -Eq 'input[[:space:]]+wire[[:space:]]+\[0:0\][[:space:]]+enable' "$synchronous_enabled_register_file" ||
   ! grep -Eq 'output[[:space:]]+reg[[:space:]]+\[WIDTH-1:0\][[:space:]]+data_out' "$synchronous_enabled_register_file" ||
   ! grep -Eq 'always[[:space:]]+@\([[:space:]]*posedge[[:space:]]+clk[[:space:]]*\)[[:space:]]+begin[[:space:]]*:[[:space:]]*p_sync_enabled_register' "$synchronous_enabled_register_file" ||
   ! grep -Eq "if[[:space:]]*\\([[:space:]]*reset[[:space:]]*==[[:space:]]*1'b1[[:space:]]*\\)[[:space:]]*begin" "$synchronous_enabled_register_file" ||
   ! grep -Eq "data_out[[:space:]]*<=[[:space:]]*\\{WIDTH\\{1'b0\\}\\}[[:space:]]*;" "$synchronous_enabled_register_file" ||
   ! grep -Eq "end[[:space:]]+else[[:space:]]+if[[:space:]]*\\([[:space:]]*enable[[:space:]]*==[[:space:]]*1'b1[[:space:]]*\\)[[:space:]]*begin" "$synchronous_enabled_register_file" ||
   ! grep -Eq 'data_out[[:space:]]*<=[[:space:]]*data_in[[:space:]]*;' "$synchronous_enabled_register_file" ||
   [[ "$(grep -Ec 'always[[:space:]]+@\(' "$synchronous_enabled_register_file")" != "1" ]] ||
   [[ "$(grep -Ec 'data_out[[:space:]]*<=' "$synchronous_enabled_register_file")" != "2" ]] ||
   [[ "$(grep -Ec 'else' "$synchronous_enabled_register_file")" != "1" ]] ||
   grep -Eq 'always_comb|always_ff|always_latch|always[[:space:]]+@\*|data_out[[:space:]]*=[^=]' "$synchronous_enabled_register_file"; then
  echo "SynchronousEnabledRegister does not retain reset-priority capture-or-hold semantics" >&2
  exit 1
fi

if ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+WIDTH[[:space:]]*=[[:space:]]*8' "$asynchronous_enabled_register_file" ||
   ! grep -Eq 'input[[:space:]]+wire[[:space:]]+\[0:0\][[:space:]]+clk' "$asynchronous_enabled_register_file" ||
   ! grep -Eq 'input[[:space:]]+wire[[:space:]]+\[0:0\][[:space:]]+reset' "$asynchronous_enabled_register_file" ||
   ! grep -Eq 'input[[:space:]]+wire[[:space:]]+\[0:0\][[:space:]]+enable' "$asynchronous_enabled_register_file" ||
   ! grep -Eq 'output[[:space:]]+reg[[:space:]]+\[WIDTH-1:0\][[:space:]]+data_out' "$asynchronous_enabled_register_file" ||
   ! grep -Eq 'always[[:space:]]+@\([[:space:]]*posedge[[:space:]]+clk[[:space:]]+or[[:space:]]+posedge[[:space:]]+reset[[:space:]]*\)[[:space:]]+begin[[:space:]]*:[[:space:]]*p_async_enabled_register' "$asynchronous_enabled_register_file" ||
   ! grep -Eq "if[[:space:]]*\\([[:space:]]*reset[[:space:]]*==[[:space:]]*1'b1[[:space:]]*\\)[[:space:]]*begin" "$asynchronous_enabled_register_file" ||
   ! grep -Eq "data_out[[:space:]]*<=[[:space:]]*\\{WIDTH\\{1'b0\\}\\}[[:space:]]*;" "$asynchronous_enabled_register_file" ||
   ! grep -Eq "end[[:space:]]+else[[:space:]]+if[[:space:]]*\\([[:space:]]*enable[[:space:]]*==[[:space:]]*1'b1[[:space:]]*\\)[[:space:]]*begin" "$asynchronous_enabled_register_file" ||
   ! grep -Eq 'data_out[[:space:]]*<=[[:space:]]*data_in[[:space:]]*;' "$asynchronous_enabled_register_file" ||
   [[ "$(grep -Ec 'always[[:space:]]+@\(' "$asynchronous_enabled_register_file")" != "1" ]] ||
   [[ "$(grep -Ec 'data_out[[:space:]]*<=' "$asynchronous_enabled_register_file")" != "2" ]] ||
   [[ "$(grep -Ec 'else' "$asynchronous_enabled_register_file")" != "1" ]] ||
   grep -Eq 'always_comb|always_ff|always_latch|always[[:space:]]+@\*|data_out[[:space:]]*=[^=]' "$asynchronous_enabled_register_file"; then
  echo "AsynchronousEnabledRegister does not retain immediate reset-priority capture-or-hold semantics" >&2
  exit 1
fi

expected_address_port="$(python3 - <<'PY'
chain = "31"
for width in range(30, 0, -1):
    false_branch = chain if width == 30 else "(" + chain + ")"
    chain = "(DEPTH <= {}) ? {} : {}".format(1 << width, width, false_branch)
print("  input  wire [({})-1:0] address,".format(chain))
PY
)"

if ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+DEPTH[[:space:]]*=[[:space:]]*5' "$single_port_memory_file" ||
   ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+WIDTH[[:space:]]*=[[:space:]]*8' "$single_port_memory_file" ||
   ! grep -Fqx "$expected_address_port" "$single_port_memory_file" ||
   ! grep -Eq 'input[[:space:]]+wire[[:space:]]+\[0:0\][[:space:]]+clk' "$single_port_memory_file" ||
   ! grep -Eq 'input[[:space:]]+wire[[:space:]]+\[0:0\][[:space:]]+read_enable' "$single_port_memory_file" ||
   ! grep -Eq 'input[[:space:]]+wire[[:space:]]+\[0:0\][[:space:]]+write_enable' "$single_port_memory_file" ||
   ! grep -Eq 'input[[:space:]]+wire[[:space:]]+\[WIDTH-1:0\][[:space:]]+write_data' "$single_port_memory_file" ||
   ! grep -Eq 'output[[:space:]]+reg[[:space:]]+\[WIDTH-1:0\][[:space:]]+read_data' "$single_port_memory_file" ||
   ! grep -Eq 'reg[[:space:]]+\[WIDTH-1:0\][[:space:]]+memory[[:space:]]+\[0:DEPTH-1\][[:space:]]*;' "$single_port_memory_file" ||
   ! grep -Eq 'always[[:space:]]+@\([[:space:]]*posedge[[:space:]]+clk[[:space:]]*\)[[:space:]]+begin[[:space:]]*:[[:space:]]*p_memory' "$single_port_memory_file" ||
   ! grep -Eq 'if[[:space:]]*\([[:space:]]*address[[:space:]]*<[[:space:]]*DEPTH[[:space:]]*\)[[:space:]]*begin' "$single_port_memory_file" ||
   [[ "$(grep -Ec "read_enable[[:space:]]*==[[:space:]]*1'b1" "$single_port_memory_file")" != "2" ]] ||
   ! grep -Eq 'read_data[[:space:]]*<=[[:space:]]*memory\[address\][[:space:]]*;' "$single_port_memory_file" ||
   ! grep -Eq "if[[:space:]]*\\([[:space:]]*write_enable[[:space:]]*==[[:space:]]*1'b1[[:space:]]*\\)[[:space:]]*begin" "$single_port_memory_file" ||
   ! grep -Eq 'memory\[address\][[:space:]]*<=[[:space:]]*write_data[[:space:]]*;' "$single_port_memory_file" ||
   ! grep -Eq "end[[:space:]]+else[[:space:]]+if[[:space:]]*\\([[:space:]]*read_enable[[:space:]]*==[[:space:]]*1'b1[[:space:]]*\\)[[:space:]]*begin" "$single_port_memory_file" ||
   ! grep -Eq "read_data[[:space:]]*<=[[:space:]]*\\{WIDTH\\{1'b0\\}\\}[[:space:]]*;" "$single_port_memory_file" ||
   [[ "$(grep -Ec 'always[[:space:]]+@\(' "$single_port_memory_file")" != "1" ]] ||
   [[ "$(grep -Ec 'memory\[address\][[:space:]]*<=' "$single_port_memory_file")" != "1" ]] ||
   [[ "$(grep -Ec 'read_data[[:space:]]*<=' "$single_port_memory_file")" != "2" ]] ||
   grep -Eq 'always_comb|always_ff|always_latch|always[[:space:]]+@\*|initial[[:space:]]+begin|read_data[[:space:]]*=[^=]|memory\[address\][[:space:]]*=[^=]' "$single_port_memory_file"; then
  echo "SinglePortMemory does not retain one independently read/write-enabled synchronous read-first whole-word memory port" >&2
  exit 1
fi

if ! python3 - "$single_port_memory_file" <<'PY'
import pathlib
import sys

source = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
canonical_process = """  always @(posedge clk) begin : p_memory
    if (address < DEPTH) begin
      if (read_enable == 1'b1) begin
        read_data <= memory[address];
      end
      if (write_enable == 1'b1) begin
        memory[address] <= write_data;
      end
    end else if (read_enable == 1'b1) begin
      read_data <= {WIDTH{1'b0}};
    end
  end"""
if source.count(canonical_process) != 1:
    raise SystemExit("missing exact address-first independently enabled memory process")
PY
then
  echo "SinglePortMemory process is not the canonical address-first read-enable/write-independent form" >&2
  exit 1
fi

missing_tools=()
for tool in iverilog verilator vvp yosys; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    missing_tools+=("$tool")
  fi
done

if (( ${#missing_tools[@]} > 0 )); then
  if (( require_tools == 1 )); then
    echo "Required Verilog tools are missing: ${missing_tools[*]}" >&2
    exit 1
  fi
  echo "Structural contract checks passed; tool checks skipped: ${missing_tools[*]}"
  exit 0
fi

tmp_dir="$(mktemp -d /tmp/morphhdl-contracts.XXXXXX)"
cleanup() {
  rm -rf -- "$tmp_dir"
}
trap cleanup EXIT

# Yosys 0.33 and older do not consistently accept quoted filenames in `-p`
# command strings. Stable temporary names avoid depending on user/repository
# path spelling while preserving the exact artifact bytes.
cp "$parameterized_wire_file" "$tmp_dir/parameterized_wire.v"
cp "$derived_width_file" "$tmp_dir/derived_width.v"
cp "$parameter_forwarding_file" "$tmp_dir/parameter_forwarding.v"
cp "$lane_array_file" "$tmp_dir/lane_array.v"
cp "$conditional_forwarding_file" "$tmp_dir/conditional_forwarding.v"
cp "$comparison_routing_file" "$tmp_dir/comparison_routing.v"
cp "$conditional_width_file" "$tmp_dir/conditional_width.v"
cp "$boolean_forwarding_file" "$tmp_dir/boolean_forwarding.v"
cp "$boolean_locals_file" "$tmp_dir/boolean_locals.v"
cp "$case_routing_file" "$tmp_dir/case_routing.v"
cp "$runtime_mux_file" "$tmp_dir/runtime_mux.v"
cp "$synchronous_register_file" "$tmp_dir/synchronous_register.v"
cp "$asynchronous_register_file" "$tmp_dir/asynchronous_register.v"
cp "$synchronous_enabled_register_file" "$tmp_dir/synchronous_enabled_register.v"
cp "$asynchronous_enabled_register_file" "$tmp_dir/asynchronous_enabled_register.v"
cp "$single_port_memory_file" "$tmp_dir/single_port_memory.v"
yosys_parameterized_wire_file="$tmp_dir/parameterized_wire.v"
yosys_derived_width_file="$tmp_dir/derived_width.v"
yosys_parameter_forwarding_file="$tmp_dir/parameter_forwarding.v"
yosys_lane_array_file="$tmp_dir/lane_array.v"
yosys_conditional_forwarding_file="$tmp_dir/conditional_forwarding.v"
yosys_comparison_routing_file="$tmp_dir/comparison_routing.v"
yosys_conditional_width_file="$tmp_dir/conditional_width.v"
yosys_boolean_forwarding_file="$tmp_dir/boolean_forwarding.v"
yosys_boolean_locals_file="$tmp_dir/boolean_locals.v"
yosys_case_routing_file="$tmp_dir/case_routing.v"
yosys_runtime_mux_file="$tmp_dir/runtime_mux.v"
yosys_synchronous_register_file="$tmp_dir/synchronous_register.v"
yosys_asynchronous_register_file="$tmp_dir/asynchronous_register.v"
yosys_synchronous_enabled_register_file="$tmp_dir/synchronous_enabled_register.v"
yosys_asynchronous_enabled_register_file="$tmp_dir/asynchronous_enabled_register.v"
yosys_single_port_memory_file="$tmp_dir/single_port_memory.v"

echo "Verilator: $(verilator --version)"
echo "Icarus: $(iverilog -V 2>/dev/null | head -n 1)"
echo "Yosys: $(yosys -V)"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module ParameterizedWire \
  "$parameterized_wire_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module ParameterizedWire \
  -GWIDTH=13 \
  "$parameterized_wire_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module DerivedWidth \
  "$derived_width_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module DerivedWidth \
  -GDATA_WIDTH=1 -GLANES=1 \
  "$derived_width_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module DerivedWidth \
  -GDATA_WIDTH=5 -GLANES=3 \
  "$derived_width_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module DerivedWidth \
  -GLANES=3 \
  "$derived_width_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module DerivedWidth \
  -GDATA_WIDTH=5 \
  "$derived_width_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module ParameterForwarding \
  "$parameter_forwarding_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module ParameterForwarding \
  -GDATA_WIDTH=1 -GLANES=1 \
  "$parameter_forwarding_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module ParameterForwarding \
  -GDATA_WIDTH=5 -GLANES=3 \
  "$parameter_forwarding_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module ParameterForwarding \
  -GLANES=3 \
  "$parameter_forwarding_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module ParameterForwarding \
  -GDATA_WIDTH=5 \
  "$parameter_forwarding_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module LaneArray \
  "$lane_array_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module LaneArray \
  -GDATA_WIDTH=1 -GLANES=1 \
  "$lane_array_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module LaneArray \
  -GDATA_WIDTH=5 -GLANES=3 \
  "$lane_array_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module LaneArray \
  -GLANES=3 \
  "$lane_array_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module LaneArray \
  -GDATA_WIDTH=5 \
  "$lane_array_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module ConditionalForwarding \
  "$conditional_forwarding_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module ConditionalForwarding \
  -GENABLE=0 \
  "$conditional_forwarding_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module ConditionalForwarding \
  -GENABLE=1 -GWIDTH=5 \
  "$conditional_forwarding_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module ConditionalForwarding \
  -GENABLE=0 -GWIDTH=13 \
  "$conditional_forwarding_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module ComparisonRouting \
  "$comparison_routing_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module ComparisonRouting \
  -GSELECT=3 -GTHRESHOLD=5 \
  "$comparison_routing_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module ComparisonRouting \
  -GSELECT=5 -GTHRESHOLD=5 \
  "$comparison_routing_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module ConditionalWidth \
  "$conditional_width_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module ConditionalWidth \
  -GWIDE=0 \
  "$conditional_width_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module ConditionalWidth \
  -GWIDE=1 -GNARROW_WIDTH=5 -GWIDE_WIDTH=15 \
  "$conditional_width_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module ConditionalWidth \
  -GWIDE=0 -GNARROW_WIDTH=7 -GWIDE_WIDTH=20 \
  "$conditional_width_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  -Wno-UNUSEDSIGNAL \
  --top-module BooleanForwarding \
  "$boolean_forwarding_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  -Wno-UNUSEDSIGNAL \
  --top-module BooleanForwarding \
  -GENABLE=0 \
  "$boolean_forwarding_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  -Wno-UNUSEDSIGNAL \
  --top-module BooleanForwarding \
  -GENABLE=1 -GWIDTH=3 -GOFFSET=1 -GLIMIT=5 \
  "$boolean_forwarding_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  -Wno-UNUSEDSIGNAL \
  --top-module BooleanForwarding \
  -GENABLE=1 -GWIDTH=4 -GOFFSET=1 -GLIMIT=5 \
  "$boolean_forwarding_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module BooleanLocals \
  "$boolean_locals_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module BooleanLocals \
  -GENABLE=0 \
  "$boolean_locals_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module BooleanLocals \
  -GENABLE=1 -GWIDTH=7 -GLIMIT=8 \
  "$boolean_locals_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module BooleanLocals \
  -GENABLE=1 -GWIDTH=8 -GLIMIT=8 \
  "$boolean_locals_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module CaseRouting \
  "$case_routing_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module CaseRouting \
  -GMODE=1 \
  "$case_routing_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module CaseRouting \
  -GMODE=0 -GOFFSET=1 \
  "$case_routing_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module CaseRouting \
  -GMODE=3 \
  "$case_routing_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module RuntimeMux \
  "$runtime_mux_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module RuntimeMux \
  -GWIDTH=5 \
  "$runtime_mux_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module SynchronousRegister \
  "$synchronous_register_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module SynchronousRegister \
  -GWIDTH=5 \
  "$synchronous_register_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module AsynchronousRegister \
  "$asynchronous_register_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module AsynchronousRegister \
  -GWIDTH=5 \
  "$asynchronous_register_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module SynchronousEnabledRegister \
  "$synchronous_enabled_register_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module SynchronousEnabledRegister \
  -GWIDTH=5 \
  "$synchronous_enabled_register_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module AsynchronousEnabledRegister \
  "$asynchronous_enabled_register_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module AsynchronousEnabledRegister \
  -GWIDTH=5 \
  "$asynchronous_enabled_register_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  -Wno-WIDTHEXPAND \
  --top-module SinglePortMemory \
  "$single_port_memory_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  -Wno-WIDTHEXPAND \
  --top-module SinglePortMemory \
  -GDEPTH=3 -GWIDTH=5 \
  "$single_port_memory_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  -Wno-WIDTHEXPAND \
  --top-module SinglePortMemory \
  -GDEPTH=2 -GWIDTH=4 \
  "$single_port_memory_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  -Wno-WIDTHEXPAND \
  --top-module SinglePortMemory \
  -GDEPTH=1 -GWIDTH=1 \
  "$single_port_memory_file"

iverilog -g2001 -Wall -s ParameterizedWireTb \
  -o "$tmp_dir/parameterized_wire.vvp" \
  "$parameterized_wire_file" \
  "$examples_dir/parameterized_wire_tb.v"
wire_output="$(vvp "$tmp_dir/parameterized_wire.vvp")"
echo "$wire_output"
if ! printf '%s\n' "$wire_output" | grep -q 'PASS: ParameterizedWire'; then
  echo "ParameterizedWire simulation did not report PASS" >&2
  exit 1
fi

iverilog -g2001 -Wall -s DerivedWidthTb \
  -o "$tmp_dir/derived_width.vvp" \
  "$derived_width_file" \
  "$examples_dir/derived_width_tb.v"
derived_output="$(vvp "$tmp_dir/derived_width.vvp")"
echo "$derived_output"
if ! printf '%s\n' "$derived_output" | grep -q 'PASS: DerivedWidth'; then
  echo "DerivedWidth simulation did not report PASS" >&2
  exit 1
fi

iverilog -g2001 -Wall -s ParameterForwardingTb \
  -o "$tmp_dir/parameter_forwarding.vvp" \
  "$parameter_forwarding_file" \
  "$examples_dir/parameter_forwarding_tb.v"
forwarding_output="$(vvp "$tmp_dir/parameter_forwarding.vvp")"
echo "$forwarding_output"
if ! printf '%s\n' "$forwarding_output" | grep -q 'PASS: ParameterForwarding'; then
  echo "ParameterForwarding simulation did not report PASS" >&2
  exit 1
fi

iverilog -g2001 -Wall -s LaneArrayTb \
  -o "$tmp_dir/lane_array.vvp" \
  "$lane_array_file" \
  "$examples_dir/lane_array_tb.v"
lane_output="$(vvp "$tmp_dir/lane_array.vvp")"
echo "$lane_output"
if ! printf '%s\n' "$lane_output" | grep -q 'PASS: LaneArray'; then
  echo "LaneArray simulation did not report PASS" >&2
  exit 1
fi

iverilog -g2001 -Wall -s ConditionalForwardingTb \
  -o "$tmp_dir/conditional_forwarding.vvp" \
  "$conditional_forwarding_file" \
  "$examples_dir/conditional_forwarding_tb.v"
conditional_output="$(vvp "$tmp_dir/conditional_forwarding.vvp")"
echo "$conditional_output"
if ! printf '%s\n' "$conditional_output" | grep -q 'PASS: ConditionalForwarding'; then
  echo "ConditionalForwarding simulation did not report PASS" >&2
  exit 1
fi

iverilog -g2001 -Wall -s ComparisonRoutingTb \
  -o "$tmp_dir/comparison_routing.vvp" \
  "$comparison_routing_file" \
  "$examples_dir/comparison_routing_tb.v"
comparison_output="$(vvp "$tmp_dir/comparison_routing.vvp")"
echo "$comparison_output"
if ! printf '%s\n' "$comparison_output" | grep -q 'PASS: ComparisonRouting'; then
  echo "ComparisonRouting simulation did not report PASS" >&2
  exit 1
fi

iverilog -g2001 -Wall -s ConditionalWidthTb \
  -o "$tmp_dir/conditional_width.vvp" \
  "$conditional_width_file" \
  "$examples_dir/conditional_width_tb.v"
conditional_width_output="$(vvp "$tmp_dir/conditional_width.vvp")"
echo "$conditional_width_output"
if ! printf '%s\n' "$conditional_width_output" | grep -q 'PASS: ConditionalWidth'; then
  echo "ConditionalWidth simulation did not report PASS" >&2
  exit 1
fi

iverilog -g2001 -Wall -s BooleanForwardingTb \
  -o "$tmp_dir/boolean_forwarding.vvp" \
  "$boolean_forwarding_file" \
  "$examples_dir/boolean_forwarding_tb.v"
boolean_forwarding_output="$(vvp "$tmp_dir/boolean_forwarding.vvp")"
echo "$boolean_forwarding_output"
if ! printf '%s\n' "$boolean_forwarding_output" | grep -q 'PASS: BooleanForwarding'; then
  echo "BooleanForwarding simulation did not report PASS" >&2
  exit 1
fi

iverilog -g2001 -Wall -s BooleanLocalsTb \
  -o "$tmp_dir/boolean_locals.vvp" \
  "$boolean_locals_file" \
  "$examples_dir/boolean_locals_tb.v"
boolean_locals_output="$(vvp "$tmp_dir/boolean_locals.vvp")"
echo "$boolean_locals_output"
if ! printf '%s\n' "$boolean_locals_output" | grep -q 'PASS: BooleanLocals'; then
  echo "BooleanLocals simulation did not report PASS" >&2
  exit 1
fi

iverilog -g2001 -Wall -s CaseRoutingTb \
  -o "$tmp_dir/case_routing.vvp" \
  "$case_routing_file" \
  "$examples_dir/case_routing_tb.v"
case_routing_output="$(vvp "$tmp_dir/case_routing.vvp")"
echo "$case_routing_output"
if ! printf '%s\n' "$case_routing_output" | grep -q 'PASS: CaseRouting'; then
  echo "CaseRouting simulation did not report PASS" >&2
  exit 1
fi

iverilog -g2001 -Wall -s RuntimeMuxTb \
  -o "$tmp_dir/runtime_mux.vvp" \
  "$runtime_mux_file" \
  "$examples_dir/runtime_mux_tb.v"
runtime_mux_output="$(vvp "$tmp_dir/runtime_mux.vvp")"
echo "$runtime_mux_output"
if ! printf '%s\n' "$runtime_mux_output" | grep -q 'PASS: RuntimeMux'; then
  echo "RuntimeMux simulation did not report PASS" >&2
  exit 1
fi

iverilog -g2001 -Wall -s SynchronousRegisterTb \
  -o "$tmp_dir/synchronous_register.vvp" \
  "$synchronous_register_file" \
  "$examples_dir/synchronous_register_tb.v"
synchronous_register_output="$(vvp "$tmp_dir/synchronous_register.vvp")"
echo "$synchronous_register_output"
if ! printf '%s\n' "$synchronous_register_output" | grep -q 'PASS: SynchronousRegister'; then
  echo "SynchronousRegister simulation did not report PASS" >&2
  exit 1
fi

iverilog -g2001 -Wall -s AsynchronousRegisterTb \
  -o "$tmp_dir/asynchronous_register.vvp" \
  "$asynchronous_register_file" \
  "$examples_dir/asynchronous_register_tb.v"
asynchronous_register_output="$(vvp "$tmp_dir/asynchronous_register.vvp")"
echo "$asynchronous_register_output"
if ! printf '%s\n' "$asynchronous_register_output" | grep -q 'PASS: AsynchronousRegister'; then
  echo "AsynchronousRegister simulation did not report PASS" >&2
  exit 1
fi

iverilog -g2001 -Wall -s SynchronousEnabledRegisterTb \
  -o "$tmp_dir/synchronous_enabled_register.vvp" \
  "$synchronous_enabled_register_file" \
  "$examples_dir/synchronous_enabled_register_tb.v"
synchronous_enabled_register_output="$(vvp "$tmp_dir/synchronous_enabled_register.vvp")"
echo "$synchronous_enabled_register_output"
if ! printf '%s\n' "$synchronous_enabled_register_output" | grep -q 'PASS: SynchronousEnabledRegister'; then
  echo "SynchronousEnabledRegister simulation did not report PASS" >&2
  exit 1
fi

iverilog -g2001 -Wall -s AsynchronousEnabledRegisterTb \
  -o "$tmp_dir/asynchronous_enabled_register.vvp" \
  "$asynchronous_enabled_register_file" \
  "$examples_dir/asynchronous_enabled_register_tb.v"
asynchronous_enabled_register_output="$(vvp "$tmp_dir/asynchronous_enabled_register.vvp")"
echo "$asynchronous_enabled_register_output"
if ! printf '%s\n' "$asynchronous_enabled_register_output" | grep -q 'PASS: AsynchronousEnabledRegister'; then
  echo "AsynchronousEnabledRegister simulation did not report PASS" >&2
  exit 1
fi

iverilog -g2001 -Wall -s SinglePortMemoryTb \
  -o "$tmp_dir/single_port_memory.vvp" \
  "$single_port_memory_file" \
  "$examples_dir/single_port_memory_tb.v"
single_port_memory_output="$(vvp "$tmp_dir/single_port_memory.vvp")"
echo "$single_port_memory_output"
if ! printf '%s\n' "$single_port_memory_output" | grep -q 'PASS: SinglePortMemory'; then
  echo "SinglePortMemory simulation did not report PASS" >&2
  exit 1
fi

yosys_synthesize_and_check() {
  local input_file="$1"
  local module_name="$2"
  local label="$3"
  local expected_width="$4"
  local parameter_command="$5"
  local netlist="$tmp_dir/${module_name}-${label}.json"

  yosys -q -p \
    "read_verilog -noautowire $input_file; $parameter_command hierarchy -check -top $module_name; proc; check -assert; synth -top $module_name; check -assert; write_json $netlist"
  python3 "$repo_root/morphhdl/scripts/check-yosys-port-widths.py" \
    "$netlist" "$module_name" \
    --port "din:input:$expected_width" \
    --port "dout:output:$expected_width"
}

yosys_synthesize_and_check \
  "$yosys_parameterized_wire_file" ParameterizedWire default 8 ""
yosys_synthesize_and_check \
  "$yosys_parameterized_wire_file" ParameterizedWire width-13 13 \
  "chparam -set WIDTH 13 ParameterizedWire;"

yosys_synthesize_and_check \
  "$yosys_derived_width_file" DerivedWidth default 35 ""
yosys_synthesize_and_check \
  "$yosys_derived_width_file" DerivedWidth minimum 4 \
  "chparam -set DATA_WIDTH 1 -set LANES 1 DerivedWidth;"
yosys_synthesize_and_check \
  "$yosys_derived_width_file" DerivedWidth awkward 18 \
  "chparam -set DATA_WIDTH 5 -set LANES 3 DerivedWidth;"
yosys_synthesize_and_check \
  "$yosys_derived_width_file" DerivedWidth lanes-only 27 \
  "chparam -set LANES 3 DerivedWidth;"
yosys_synthesize_and_check \
  "$yosys_derived_width_file" DerivedWidth data-width-only 23 \
  "chparam -set DATA_WIDTH 5 DerivedWidth;"

yosys_synthesize_and_check \
  "$yosys_conditional_width_file" ConditionalWidth default 12 ""
yosys_synthesize_and_check \
  "$yosys_conditional_width_file" ConditionalWidth narrow 4 \
  "chparam -set WIDE 0 ConditionalWidth;"
yosys_synthesize_and_check \
  "$yosys_conditional_width_file" ConditionalWidth custom-wide 15 \
  "chparam -set WIDE 1 -set NARROW_WIDTH 5 -set WIDE_WIDTH 15 ConditionalWidth;"
yosys_synthesize_and_check \
  "$yosys_conditional_width_file" ConditionalWidth custom-narrow 7 \
  "chparam -set WIDE 0 -set NARROW_WIDTH 7 -set WIDE_WIDTH 20 ConditionalWidth;"

yosys_hierarchy_synthesize_and_check() {
  local input_file="$1"
  local module_name="$2"
  local instance_name="$3"
  local label="$4"
  local expected_width="$5"
  local parameter_command="$6"
  local hierarchy_netlist="$tmp_dir/${module_name}-${label}-hierarchy.json"
  local synthesized_netlist="$tmp_dir/${module_name}-${label}-synthesized.json"

  yosys -q -p \
    "read_verilog -noautowire $input_file; $parameter_command hierarchy -check -top $module_name; proc; check -assert; write_json $hierarchy_netlist; synth -top $module_name; check -assert; write_json $synthesized_netlist"
  python3 "$repo_root/morphhdl/scripts/check-yosys-instance-contract.py" \
    "$hierarchy_netlist" "$module_name" "$instance_name" \
    --binding "din:din:input:$expected_width" \
    --binding "dout:dout:output:$expected_width"
  python3 "$repo_root/morphhdl/scripts/check-yosys-port-widths.py" \
    "$synthesized_netlist" "$module_name" \
    --port "din:input:$expected_width" \
    --port "dout:output:$expected_width"
}

yosys_hierarchy_synthesize_and_check \
  "$yosys_parameter_forwarding_file" ParameterForwarding forwarded_inst default 32 ""
yosys_hierarchy_synthesize_and_check \
  "$yosys_parameter_forwarding_file" ParameterForwarding forwarded_inst minimum 1 \
  "chparam -set DATA_WIDTH 1 -set LANES 1 ParameterForwarding;"
yosys_hierarchy_synthesize_and_check \
  "$yosys_parameter_forwarding_file" ParameterForwarding forwarded_inst awkward 15 \
  "chparam -set DATA_WIDTH 5 -set LANES 3 ParameterForwarding;"
yosys_hierarchy_synthesize_and_check \
  "$yosys_parameter_forwarding_file" ParameterForwarding forwarded_inst lanes-only 24 \
  "chparam -set LANES 3 ParameterForwarding;"
yosys_hierarchy_synthesize_and_check \
  "$yosys_parameter_forwarding_file" ParameterForwarding forwarded_inst data-width-only 20 \
  "chparam -set DATA_WIDTH 5 ParameterForwarding;"

yosys_hierarchy_synthesize_and_check \
  "$yosys_conditional_forwarding_file" ConditionalForwarding \
  g_enabled.selected_inst default 8 ""
yosys_hierarchy_synthesize_and_check \
  "$yosys_conditional_forwarding_file" ConditionalForwarding \
  g_disabled.selected_inst disabled 8 \
  "chparam -set ENABLE 0 ConditionalForwarding;"
yosys_hierarchy_synthesize_and_check \
  "$yosys_conditional_forwarding_file" ConditionalForwarding \
  g_enabled.selected_inst enabled-width-5 5 \
  "chparam -set ENABLE 1 -set WIDTH 5 ConditionalForwarding;"
yosys_hierarchy_synthesize_and_check \
  "$yosys_conditional_forwarding_file" ConditionalForwarding \
  g_disabled.selected_inst disabled-width-13 13 \
  "chparam -set ENABLE 0 -set WIDTH 13 ConditionalForwarding;"

yosys_comparison_synthesize_and_check() {
  local label="$1"
  local expected_instance="$2"
  local expected_type="$3"
  local child_input="$4"
  local child_output="$5"
  local parameter_command="$6"
  local hierarchy_netlist="$tmp_dir/ComparisonRouting-${label}-hierarchy.json"
  local synthesized_netlist="$tmp_dir/ComparisonRouting-${label}-synthesized.json"

  yosys -q -p \
    "read_verilog -noautowire $yosys_comparison_routing_file; $parameter_command hierarchy -check -top ComparisonRouting; proc; check -assert; write_json $hierarchy_netlist; synth -top ComparisonRouting; check -assert; write_json $synthesized_netlist"
  python3 "$repo_root/morphhdl/scripts/check-yosys-instance-contract.py" \
    "$hierarchy_netlist" ComparisonRouting "$expected_instance" \
    --child-type "$expected_type" \
    --binding "$child_input:din:input:8" \
    --binding "$child_output:dout:output:8"
  python3 "$repo_root/morphhdl/scripts/check-yosys-port-widths.py" \
    "$synthesized_netlist" ComparisonRouting \
    --port "din:input:8" \
    --port "dout:output:8"
}

yosys_comparison_synthesize_and_check \
  default g_high.selected_inst HighRoute high_in high_out ""
yosys_comparison_synthesize_and_check \
  below-threshold g_low.selected_inst LowRoute low_in low_out \
  "chparam -set SELECT 3 -set THRESHOLD 5 ComparisonRouting;"
yosys_comparison_synthesize_and_check \
  equal-threshold g_high.selected_inst HighRoute high_in high_out \
  "chparam -set SELECT 5 -set THRESHOLD 5 ComparisonRouting;"

yosys_generate_synthesize_and_check() {
  local input_file="$1"
  local module_name="$2"
  local label="$3"
  local expected_lanes="$4"
  local expected_data_width="$5"
  local parameter_command="$6"
  local expected_flat_width=$((expected_lanes * expected_data_width))
  local hierarchy_netlist="$tmp_dir/${module_name}-${label}-generate.json"
  local synthesized_netlist="$tmp_dir/${module_name}-${label}-synthesized.json"

  yosys -q -p \
    "read_verilog -noautowire $input_file; $parameter_command hierarchy -check -top $module_name; proc; check -assert; write_json $hierarchy_netlist; synth -top $module_name; check -assert; write_json $synthesized_netlist"
  python3 "$repo_root/morphhdl/scripts/check-yosys-generate-contract.py" \
    "$hierarchy_netlist" "$module_name" \
    --lanes "$expected_lanes" \
    --data-width "$expected_data_width"
  python3 "$repo_root/morphhdl/scripts/check-yosys-port-widths.py" \
    "$synthesized_netlist" "$module_name" \
    --port "data_in:input:$expected_flat_width" \
    --port "data_out:output:$expected_flat_width"
}

yosys_generate_synthesize_and_check \
  "$yosys_lane_array_file" LaneArray default 4 8 ""
yosys_generate_synthesize_and_check \
  "$yosys_lane_array_file" LaneArray minimum 1 1 \
  "chparam -set DATA_WIDTH 1 -set LANES 1 LaneArray;"
yosys_generate_synthesize_and_check \
  "$yosys_lane_array_file" LaneArray awkward 3 5 \
  "chparam -set DATA_WIDTH 5 -set LANES 3 LaneArray;"
yosys_generate_synthesize_and_check \
  "$yosys_lane_array_file" LaneArray lanes-only 3 8 \
  "chparam -set LANES 3 LaneArray;"
yosys_generate_synthesize_and_check \
  "$yosys_lane_array_file" LaneArray data-width-only 4 5 \
  "chparam -set DATA_WIDTH 5 LaneArray;"

yosys_boolean_forwarding_synthesize_and_check() {
  local label="$1"
  local expected_branch="$2"
  local parameter_command="$3"
  local hierarchy_netlist="$tmp_dir/BooleanForwarding-${label}-hierarchy.json"
  local synthesized_netlist="$tmp_dir/BooleanForwarding-${label}-synthesized.json"

  yosys -q -p \
    "read_verilog -noautowire $yosys_boolean_forwarding_file; $parameter_command hierarchy -check -top BooleanForwarding; proc; check -assert; write_json $hierarchy_netlist; synth -top BooleanForwarding; check -assert; write_json $synthesized_netlist"
  python3 "$repo_root/morphhdl/scripts/check-yosys-boolean-forwarding-contract.py" \
    "$hierarchy_netlist" --branch "$expected_branch"
  python3 "$repo_root/morphhdl/scripts/check-yosys-port-widths.py" \
    "$synthesized_netlist" BooleanForwarding \
    --port "high_in:input:8" \
    --port "low_in:input:8" \
    --port "dout:output:8"
}

yosys_boolean_forwarding_synthesize_and_check \
  default high ""
yosys_boolean_forwarding_synthesize_and_check \
  disabled low "chparam -set ENABLE 0 BooleanForwarding;"
yosys_boolean_forwarding_synthesize_and_check \
  below-limit low \
  "chparam -set ENABLE 1 -set WIDTH 3 -set OFFSET 1 -set LIMIT 5 BooleanForwarding;"
yosys_boolean_forwarding_synthesize_and_check \
  equal-limit high \
  "chparam -set ENABLE 1 -set WIDTH 4 -set OFFSET 1 -set LIMIT 5 BooleanForwarding;"

yosys_boolean_locals_synthesize_and_check() {
  local label="$1"
  local expected_branch="$2"
  local parameter_command="$3"
  local hierarchy_netlist="$tmp_dir/BooleanLocals-${label}-hierarchy.json"
  local synthesized_netlist="$tmp_dir/BooleanLocals-${label}-synthesized.json"

  yosys -q -p \
    "read_verilog -noautowire $yosys_boolean_locals_file; $parameter_command hierarchy -check -top BooleanLocals; proc; check -assert; write_json $hierarchy_netlist; synth -top BooleanLocals; check -assert; write_json $synthesized_netlist"
  python3 "$repo_root/morphhdl/scripts/check-yosys-boolean-locals-contract.py" \
    "$hierarchy_netlist" --branch "$expected_branch"
  python3 "$repo_root/morphhdl/scripts/check-yosys-port-widths.py" \
    "$synthesized_netlist" BooleanLocals \
    --port "din:input:8" \
    --port "dout:output:8"
}

yosys_boolean_locals_synthesize_and_check \
  default high ""
yosys_boolean_locals_synthesize_and_check \
  disabled low "chparam -set ENABLE 0 BooleanLocals;"
yosys_boolean_locals_synthesize_and_check \
  below-limit low \
  "chparam -set ENABLE 1 -set WIDTH 7 -set LIMIT 8 BooleanLocals;"
yosys_boolean_locals_synthesize_and_check \
  equal-limit high \
  "chparam -set ENABLE 1 -set WIDTH 8 -set LIMIT 8 BooleanLocals;"

yosys_case_routing_synthesize_and_check() {
  local label="$1"
  local expected_branch="$2"
  local parameter_command="$3"
  local hierarchy_netlist="$tmp_dir/CaseRouting-${label}-hierarchy.json"
  local synthesized_netlist="$tmp_dir/CaseRouting-${label}-synthesized.json"

  yosys -q -p \
    "read_verilog -noautowire $yosys_case_routing_file; $parameter_command hierarchy -check -top CaseRouting; proc; check -assert; write_json $hierarchy_netlist; synth -top CaseRouting; check -assert; write_json $synthesized_netlist"
  python3 "$repo_root/morphhdl/scripts/check-yosys-case-routing-contract.py" \
    "$hierarchy_netlist" --branch "$expected_branch"
  python3 "$repo_root/morphhdl/scripts/check-yosys-port-widths.py" \
    "$synthesized_netlist" CaseRouting \
    --port "din:input:8" \
    --port "dout:output:8"
}

yosys_case_routing_synthesize_and_check \
  default zero ""
yosys_case_routing_synthesize_and_check \
  choice-one one "chparam -set MODE 1 CaseRouting;"
yosys_case_routing_synthesize_and_check \
  offset-choice-one one "chparam -set MODE 0 -set OFFSET 1 CaseRouting;"
yosys_case_routing_synthesize_and_check \
  unmatched default "chparam -set MODE 3 CaseRouting;"

yosys_runtime_mux_synthesize_and_check() {
  local label="$1"
  local expected_width="$2"
  local parameter_command="$3"
  local process_netlist="$tmp_dir/RuntimeMux-${label}-process.json"
  local synthesized_netlist="$tmp_dir/RuntimeMux-${label}-synthesized.json"

  yosys -q -p \
    "read_verilog -noautowire $yosys_runtime_mux_file; $parameter_command hierarchy -check -top RuntimeMux; proc; opt; check -assert; write_json $process_netlist; synth -top RuntimeMux; check -assert; write_json $synthesized_netlist"
  python3 "$repo_root/morphhdl/scripts/check-yosys-runtime-mux-contract.py" \
    "$process_netlist" --width "$expected_width"
  python3 "$repo_root/morphhdl/scripts/check-yosys-port-widths.py" \
    "$synthesized_netlist" RuntimeMux \
    --port "sel:input:1" \
    --port "data_false:input:$expected_width" \
    --port "data_true:input:$expected_width" \
    --port "result:output:$expected_width"
}

yosys_runtime_mux_synthesize_and_check \
  default 8 ""
yosys_runtime_mux_synthesize_and_check \
  width-five 5 "chparam -set WIDTH 5 RuntimeMux;"

yosys_synchronous_register_synthesize_and_check() {
  local label="$1"
  local expected_width="$2"
  local parameter_command="$3"
  local process_netlist="$tmp_dir/SynchronousRegister-${label}-process.json"
  local synthesized_netlist="$tmp_dir/SynchronousRegister-${label}-synthesized.json"

  yosys -q -p \
    "read_verilog -noautowire $yosys_synchronous_register_file; $parameter_command hierarchy -check -top SynchronousRegister; proc; opt; check -assert; write_json $process_netlist; synth -top SynchronousRegister; check -assert; write_json $synthesized_netlist"
  python3 "$repo_root/morphhdl/scripts/check-yosys-synchronous-register-contract.py" \
    "$process_netlist" --width "$expected_width"
  python3 "$repo_root/morphhdl/scripts/check-yosys-port-widths.py" \
    "$synthesized_netlist" SynchronousRegister \
    --port "clk:input:1" \
    --port "data_in:input:$expected_width" \
    --port "data_out:output:$expected_width" \
    --port "reset:input:1"
}

yosys_synchronous_register_synthesize_and_check \
  default 8 ""
yosys_synchronous_register_synthesize_and_check \
  width-five 5 "chparam -set WIDTH 5 SynchronousRegister;"

yosys_asynchronous_register_synthesize_and_check() {
  local label="$1"
  local expected_width="$2"
  local parameter_command="$3"
  local process_netlist="$tmp_dir/AsynchronousRegister-${label}-process.json"
  local synthesized_netlist="$tmp_dir/AsynchronousRegister-${label}-synthesized.json"

  yosys -q -p \
    "read_verilog -noautowire $yosys_asynchronous_register_file; $parameter_command hierarchy -check -top AsynchronousRegister; proc; opt; check -assert; write_json $process_netlist; synth -top AsynchronousRegister; check -assert; write_json $synthesized_netlist"
  python3 "$repo_root/morphhdl/scripts/check-yosys-asynchronous-register-contract.py" \
    "$process_netlist" --width "$expected_width"
  python3 "$repo_root/morphhdl/scripts/check-yosys-port-widths.py" \
    "$synthesized_netlist" AsynchronousRegister \
    --port "clk:input:1" \
    --port "data_in:input:$expected_width" \
    --port "data_out:output:$expected_width" \
    --port "reset:input:1"
}

yosys_asynchronous_register_synthesize_and_check \
  default 8 ""
yosys_asynchronous_register_synthesize_and_check \
  width-five 5 "chparam -set WIDTH 5 AsynchronousRegister;"

yosys_asynchronous_register_mutation_must_fail() {
  local label="$1"
  local sed_expression="$2"
  local mutated_file="$tmp_dir/asynchronous-register-${label}.v"
  local mutated_netlist="$tmp_dir/AsynchronousRegister-${label}-mutated.json"

  sed "$sed_expression" "$yosys_asynchronous_register_file" > "$mutated_file"
  if cmp -s "$yosys_asynchronous_register_file" "$mutated_file"; then
    echo "AsynchronousRegister mutation did not change the fixture: $label" >&2
    exit 1
  fi

  yosys -q -p \
    "read_verilog -noautowire $mutated_file; hierarchy -check -top AsynchronousRegister; proc; opt; check -assert; write_json $mutated_netlist"
  if python3 "$repo_root/morphhdl/scripts/check-yosys-asynchronous-register-contract.py" \
      "$mutated_netlist" --width 8; then
    echo "AsynchronousRegister Yosys checker accepted forbidden mutation: $label" >&2
    exit 1
  fi
  echo "Yosys AsynchronousRegister rejected forbidden mutation: $label"
}

yosys_asynchronous_register_mutation_must_fail \
  synchronous-reset 's/posedge clk or posedge reset/posedge clk/'
yosys_asynchronous_register_mutation_must_fail \
  falling-edge-clock 's/posedge clk or posedge reset/negedge clk or posedge reset/'
yosys_asynchronous_register_mutation_must_fail \
  reset-to-ones "s/{WIDTH{1'b0}}/{WIDTH{1'b1}}/"

yosys_synchronous_enabled_register_synthesize_and_check() {
  local label="$1"
  local expected_width="$2"
  local parameter_command="$3"
  local process_netlist="$tmp_dir/SynchronousEnabledRegister-${label}-process.json"
  local synthesized_netlist="$tmp_dir/SynchronousEnabledRegister-${label}-synthesized.json"

  yosys -q -p \
    "read_verilog -noautowire $yosys_synchronous_enabled_register_file; $parameter_command hierarchy -check -top SynchronousEnabledRegister; proc; opt_dff; opt_clean; check -assert; write_json $process_netlist; synth -top SynchronousEnabledRegister; check -assert; write_json $synthesized_netlist"
  python3 "$repo_root/morphhdl/scripts/check-yosys-synchronous-enabled-register-contract.py" \
    "$process_netlist" --width "$expected_width"
  python3 "$repo_root/morphhdl/scripts/check-yosys-port-widths.py" \
    "$synthesized_netlist" SynchronousEnabledRegister \
    --port "clk:input:1" \
    --port "data_in:input:$expected_width" \
    --port "data_out:output:$expected_width" \
    --port "enable:input:1" \
    --port "reset:input:1"
}

yosys_synchronous_enabled_register_synthesize_and_check \
  default 8 ""
yosys_synchronous_enabled_register_synthesize_and_check \
  width-five 5 "chparam -set WIDTH 5 SynchronousEnabledRegister;"

yosys_synchronous_enabled_register_mutation_must_fail() {
  local label="$1"
  local sed_expression="$2"
  local mutated_file="$tmp_dir/synchronous-enabled-register-${label}.v"
  local mutated_netlist="$tmp_dir/SynchronousEnabledRegister-${label}-mutated.json"

  sed "$sed_expression" "$yosys_synchronous_enabled_register_file" > "$mutated_file"
  if cmp -s "$yosys_synchronous_enabled_register_file" "$mutated_file"; then
    echo "SynchronousEnabledRegister mutation did not change the fixture: $label" >&2
    exit 1
  fi

  yosys -q -p \
    "read_verilog -noautowire $mutated_file; hierarchy -check -top SynchronousEnabledRegister; proc; opt_dff; opt_clean; check -assert; write_json $mutated_netlist"
  if python3 "$repo_root/morphhdl/scripts/check-yosys-synchronous-enabled-register-contract.py" \
      "$mutated_netlist" --width 8; then
    echo "SynchronousEnabledRegister Yosys checker accepted forbidden mutation: $label" >&2
    exit 1
  fi
  echo "Yosys SynchronousEnabledRegister rejected forbidden mutation: $label"
}

yosys_synchronous_enabled_register_mutation_must_fail \
  disabled-path-captures '/data_out <= data_in;/a\    end else begin\
      data_out <= data_in;'
yosys_synchronous_enabled_register_mutation_must_fail \
  enable-before-reset 's/reset/__morph_swap__/g;s/enable/reset/g;s/__morph_swap__/enable/g'
yosys_synchronous_enabled_register_mutation_must_fail \
  active-low-enable "s/enable == 1'b1/enable == 1'b0/"
yosys_synchronous_enabled_register_mutation_must_fail \
  falling-edge-clock 's/posedge clk/negedge clk/'
yosys_synchronous_enabled_register_mutation_must_fail \
  reset-to-ones "s/{WIDTH{1'b0}}/{WIDTH{1'b1}}/"

yosys_asynchronous_enabled_register_synthesize_and_check() {
  local label="$1"
  local expected_width="$2"
  local parameter_command="$3"
  local process_netlist="$tmp_dir/AsynchronousEnabledRegister-${label}-process.json"
  local synthesized_netlist="$tmp_dir/AsynchronousEnabledRegister-${label}-synthesized.json"

  yosys -q -p \
    "read_verilog -noautowire $yosys_asynchronous_enabled_register_file; $parameter_command hierarchy -check -top AsynchronousEnabledRegister; proc; opt_dff; opt_clean; check -assert; write_json $process_netlist; synth -top AsynchronousEnabledRegister; check -assert; write_json $synthesized_netlist"
  python3 "$repo_root/morphhdl/scripts/check-yosys-asynchronous-enabled-register-contract.py" \
    "$process_netlist" --width "$expected_width"
  python3 "$repo_root/morphhdl/scripts/check-yosys-port-widths.py" \
    "$synthesized_netlist" AsynchronousEnabledRegister \
    --port "clk:input:1" \
    --port "data_in:input:$expected_width" \
    --port "data_out:output:$expected_width" \
    --port "enable:input:1" \
    --port "reset:input:1"
}

yosys_asynchronous_enabled_register_synthesize_and_check \
  default 8 ""
yosys_asynchronous_enabled_register_synthesize_and_check \
  width-five 5 "chparam -set WIDTH 5 AsynchronousEnabledRegister;"

yosys_asynchronous_enabled_register_mutation_must_fail() {
  local label="$1"
  local sed_expression="$2"
  local mutated_file="$tmp_dir/asynchronous-enabled-register-${label}.v"
  local mutated_netlist="$tmp_dir/AsynchronousEnabledRegister-${label}-mutated.json"

  sed "$sed_expression" "$yosys_asynchronous_enabled_register_file" > "$mutated_file"
  if cmp -s "$yosys_asynchronous_enabled_register_file" "$mutated_file"; then
    echo "AsynchronousEnabledRegister mutation did not change the fixture: $label" >&2
    exit 1
  fi

  yosys -q -p \
    "read_verilog -noautowire $mutated_file; hierarchy -check -top AsynchronousEnabledRegister; proc; opt_dff; opt_clean; check -assert; write_json $mutated_netlist"
  if python3 "$repo_root/morphhdl/scripts/check-yosys-asynchronous-enabled-register-contract.py" \
      "$mutated_netlist" --width 8; then
    echo "AsynchronousEnabledRegister Yosys checker accepted forbidden mutation: $label" >&2
    exit 1
  fi
  echo "Yosys AsynchronousEnabledRegister rejected forbidden mutation: $label"
}

yosys_asynchronous_enabled_register_mutation_must_fail \
  disabled-path-captures '/data_out <= data_in;/a\    end else begin\
      data_out <= data_in;'
yosys_asynchronous_enabled_register_mutation_must_fail \
  enable-before-reset 's/reset/__morph_swap__/g;s/enable/reset/g;s/__morph_swap__/enable/g'
yosys_asynchronous_enabled_register_mutation_must_fail \
  active-low-enable "s/enable == 1'b1/enable == 1'b0/"
yosys_asynchronous_enabled_register_mutation_must_fail \
  falling-edge-clock 's/posedge clk/negedge clk/'
yosys_asynchronous_enabled_register_mutation_must_fail \
  synchronous-reset 's/ or posedge reset//'
yosys_asynchronous_enabled_register_mutation_must_fail \
  reset-to-ones "s/{WIDTH{1'b0}}/{WIDTH{1'b1}}/"

yosys_single_port_memory_synthesize_and_check() {
  local label="$1"
  local expected_width="$2"
  local expected_depth="$3"
  local parameter_command="$4"
  local expected_address_width=1
  local address_capacity=2
  local process_netlist="$tmp_dir/SinglePortMemory-${label}-process.json"
  local synthesized_netlist="$tmp_dir/SinglePortMemory-${label}-synthesized.json"

  while (( address_capacity < expected_depth )); do
    expected_address_width=$((expected_address_width + 1))
    address_capacity=$((address_capacity * 2))
  done

  yosys -q -p \
    "read_verilog -noautowire $yosys_single_port_memory_file; $parameter_command hierarchy -check -top SinglePortMemory; proc; opt_reduce; opt_expr -mux_undef; memory_dff; memory_collect; opt_clean; check -assert; write_json $process_netlist; synth -top SinglePortMemory; check -assert; write_json $synthesized_netlist"
  python3 "$repo_root/morphhdl/scripts/check-yosys-single-port-memory-contract.py" \
    "$process_netlist" --width "$expected_width" --depth "$expected_depth"
  python3 "$repo_root/morphhdl/scripts/check-yosys-port-widths.py" \
    "$synthesized_netlist" SinglePortMemory \
    --port "address:input:$expected_address_width" \
    --port "clk:input:1" \
    --port "read_data:output:$expected_width" \
    --port "read_enable:input:1" \
    --port "write_data:input:$expected_width" \
    --port "write_enable:input:1"
}

yosys_single_port_memory_synthesize_and_check \
  default 8 5 ""
yosys_single_port_memory_synthesize_and_check \
  awkward 5 3 "chparam -set DEPTH 3 -set WIDTH 5 SinglePortMemory;"
yosys_single_port_memory_synthesize_and_check \
  depth-two 4 2 "chparam -set DEPTH 2 -set WIDTH 4 SinglePortMemory;"
yosys_single_port_memory_synthesize_and_check \
  minimum 1 1 "chparam -set DEPTH 1 -set WIDTH 1 SinglePortMemory;"

yosys_single_port_memory_json_mutation_must_fail() {
  local label="$1"
  local parameter="$2"
  local canonical_netlist="$tmp_dir/SinglePortMemory-default-process.json"
  local mutated_netlist="$tmp_dir/SinglePortMemory-${label}-json-mutated.json"

  python3 - "$canonical_netlist" "$mutated_netlist" "$parameter" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
parameter = sys.argv[3]
netlist = json.loads(source.read_text(encoding="utf-8"))
top = netlist.get("modules", {}).get("SinglePortMemory")
if top is None:
    raise SystemExit("canonical JSON is missing SinglePortMemory")
memories = [
    cell for cell in top.get("cells", {}).values() if cell.get("type") == "$mem_v2"
]
if len(memories) != 1:
    raise SystemExit("canonical JSON does not contain exactly one $mem_v2")
parameters = memories[0].get("parameters", {})
if parameter not in parameters:
    raise SystemExit("canonical $mem_v2 is missing parameter " + parameter)
value = parameters[parameter]
if isinstance(value, int):
    if value != 0:
        raise SystemExit("canonical parameter is not zero: " + repr(value))
    parameters[parameter] = 1
elif isinstance(value, str) and value and set(value) <= {"0", "1"}:
    if int(value, 2) != 0:
        raise SystemExit("canonical parameter is not zero: " + repr(value))
    parameters[parameter] = value[:-1] + "1"
else:
    raise SystemExit("canonical parameter has unsupported encoding: " + repr(value))
if parameters[parameter] == value:
    raise SystemExit("JSON mutation did not change parameter " + parameter)
destination.write_text(json.dumps(netlist, indent=2) + "\n", encoding="utf-8")
PY

  if cmp -s "$canonical_netlist" "$mutated_netlist"; then
    echo "SinglePortMemory JSON mutation did not change the netlist: $label" >&2
    exit 1
  fi
  if python3 "$repo_root/morphhdl/scripts/check-yosys-single-port-memory-contract.py" \
      "$mutated_netlist" --width 8 --depth 5; then
    echo "SinglePortMemory checker accepted forbidden JSON mutation: $label" >&2
    exit 1
  fi
  echo "Yosys SinglePortMemory checker rejected forbidden JSON mutation: $label"
}

yosys_single_port_memory_json_mutation_must_fail \
  transparent-read RD_TRANSPARENCY_MASK
yosys_single_port_memory_json_mutation_must_fail \
  collision-x-read RD_COLLISION_X_MASK
yosys_single_port_memory_json_mutation_must_fail \
  synchronous-raw-read RD_CLK_ENABLE
yosys_single_port_memory_json_mutation_must_fail \
  falling-edge-raw-read RD_CLK_POLARITY
yosys_single_port_memory_json_mutation_must_fail \
  write-port-priority WR_PRIORITY_MASK
yosys_single_port_memory_json_mutation_must_fail \
  wide-read-continuation RD_WIDE_CONTINUATION
yosys_single_port_memory_json_mutation_must_fail \
  wide-write-continuation WR_WIDE_CONTINUATION

yosys_single_port_memory_init_json_mutation_must_fail() {
  local label="$1"
  local mutation="$2"
  local canonical_netlist="$tmp_dir/SinglePortMemory-default-process.json"
  local mutated_netlist="$tmp_dir/SinglePortMemory-${label}-json-mutated.json"

  python3 - "$canonical_netlist" "$mutated_netlist" "$mutation" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
mutation = sys.argv[3]
netlist = json.loads(source.read_text(encoding="utf-8"))
top = netlist.get("modules", {}).get("SinglePortMemory")
if top is None:
    raise SystemExit("canonical JSON is missing SinglePortMemory")
memories = [
    cell for cell in top.get("cells", {}).values() if cell.get("type") == "$mem_v2"
]
if len(memories) != 1:
    raise SystemExit("canonical JSON does not contain exactly one $mem_v2")
parameters = memories[0].get("parameters", {})
value = parameters.get("INIT")
if not isinstance(value, str) or len(value) != 40 or set(value.lower()) != {"x"}:
    raise SystemExit("canonical INIT is not exactly 40 unknown bits: " + repr(value))
if mutation == "empty":
    parameters["INIT"] = ""
elif mutation == "truncated":
    parameters["INIT"] = value[:-1]
else:
    raise SystemExit("unknown INIT mutation: " + mutation)
if parameters["INIT"] == value:
    raise SystemExit("JSON mutation did not change INIT")
destination.write_text(json.dumps(netlist, indent=2) + "\n", encoding="utf-8")
PY

  if cmp -s "$canonical_netlist" "$mutated_netlist"; then
    echo "SinglePortMemory INIT mutation did not change the netlist: $label" >&2
    exit 1
  fi
  if python3 "$repo_root/morphhdl/scripts/check-yosys-single-port-memory-contract.py" \
      "$mutated_netlist" --width 8 --depth 5; then
    echo "SinglePortMemory checker accepted forbidden INIT mutation: $label" >&2
    exit 1
  fi
  echo "Yosys SinglePortMemory checker rejected forbidden INIT mutation: $label"
}

yosys_single_port_memory_init_json_mutation_must_fail empty-init empty
yosys_single_port_memory_init_json_mutation_must_fail truncated-init truncated

yosys_single_port_memory_connection_shape_mutation_must_fail() {
  local label="$1"
  local connection="$2"
  local mutation="$3"
  local canonical_netlist="$tmp_dir/SinglePortMemory-default-process.json"
  local mutated_netlist="$tmp_dir/SinglePortMemory-${label}-json-mutated.json"

  python3 - "$canonical_netlist" "$mutated_netlist" "$connection" "$mutation" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
connection = sys.argv[3]
mutation = sys.argv[4]
netlist = json.loads(source.read_text(encoding="utf-8"))
top = netlist.get("modules", {}).get("SinglePortMemory")
if top is None:
    raise SystemExit("canonical JSON is missing SinglePortMemory")
memories = [
    cell for cell in top.get("cells", {}).values() if cell.get("type") == "$mem_v2"
]
if len(memories) != 1:
    raise SystemExit("canonical JSON does not contain exactly one $mem_v2")
connections = memories[0].get("connections", {})
value = connections.get(connection)
if not isinstance(value, list) or len(value) != 1:
    raise SystemExit("canonical connection is not exactly one bit: " + repr(value))
if mutation == "empty":
    connections[connection] = []
elif mutation == "widen-zero":
    if value[0] not in ("0", 0):
        raise SystemExit("canonical connection is not inactive: " + repr(value))
    connections[connection] = [value[0], value[0]]
else:
    raise SystemExit("unknown connection-shape mutation: " + mutation)
if connections[connection] == value:
    raise SystemExit("JSON mutation did not change connection " + connection)
destination.write_text(json.dumps(netlist, indent=2) + "\n", encoding="utf-8")
PY

  if cmp -s "$canonical_netlist" "$mutated_netlist"; then
    echo "SinglePortMemory connection-shape mutation did not change the netlist: $label" >&2
    exit 1
  fi
  if python3 "$repo_root/morphhdl/scripts/check-yosys-single-port-memory-contract.py" \
      "$mutated_netlist" --width 8 --depth 5; then
    echo "SinglePortMemory checker accepted forbidden connection-shape mutation: $label" >&2
    exit 1
  fi
  echo "Yosys SinglePortMemory checker rejected forbidden connection-shape mutation: $label"
}

yosys_single_port_memory_connection_shape_mutation_must_fail \
  empty-read-enable RD_EN empty
yosys_single_port_memory_connection_shape_mutation_must_fail \
  empty-asynchronous-read-clock RD_CLK empty
yosys_single_port_memory_connection_shape_mutation_must_fail \
  widened-asynchronous-read-reset RD_ARST widen-zero
yosys_single_port_memory_connection_shape_mutation_must_fail \
  widened-synchronous-read-reset RD_SRST widen-zero

yosys_single_port_memory_comparison_json_mutation_must_fail() {
  local label="$1"
  local mutation="$2"
  local canonical_netlist="$tmp_dir/SinglePortMemory-default-process.json"
  local mutated_netlist="$tmp_dir/SinglePortMemory-${label}-json-mutated.json"

  python3 - "$canonical_netlist" "$mutated_netlist" "$mutation" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
mutation = sys.argv[3]
netlist = json.loads(source.read_text(encoding="utf-8"))
top = netlist.get("modules", {}).get("SinglePortMemory")
if top is None:
    raise SystemExit("canonical JSON is missing SinglePortMemory")
comparisons = [
    cell for cell in top.get("cells", {}).values() if cell.get("type") == "$lt"
]
if len(comparisons) != 1:
    raise SystemExit("canonical JSON does not contain exactly one $lt")
comparison = comparisons[0]
if mutation == "short-b":
    value = comparison.get("connections", {}).get("B")
    if not isinstance(value, list) or len(value) != 32:
        raise SystemExit("canonical $lt.B is not exactly 32 bits: " + repr(value))
    comparison["connections"]["B"] = value[:-1]
elif mutation == "signed-b":
    parameters = comparison.get("parameters", {})
    value = parameters.get("B_SIGNED")
    if isinstance(value, int):
        if value != 0:
            raise SystemExit("canonical B_SIGNED is not zero: " + repr(value))
        parameters["B_SIGNED"] = 1
    elif isinstance(value, str) and value and set(value) <= {"0", "1"}:
        if int(value, 2) != 0:
            raise SystemExit("canonical B_SIGNED is not zero: " + repr(value))
        parameters["B_SIGNED"] = value[:-1] + "1"
    else:
        raise SystemExit("canonical B_SIGNED has unsupported encoding: " + repr(value))
else:
    raise SystemExit("unknown comparison mutation: " + mutation)
destination.write_text(json.dumps(netlist, indent=2) + "\n", encoding="utf-8")
PY

  if cmp -s "$canonical_netlist" "$mutated_netlist"; then
    echo "SinglePortMemory comparison mutation did not change the netlist: $label" >&2
    exit 1
  fi
  if python3 "$repo_root/morphhdl/scripts/check-yosys-single-port-memory-contract.py" \
      "$mutated_netlist" --width 8 --depth 5; then
    echo "SinglePortMemory checker accepted forbidden comparison mutation: $label" >&2
    exit 1
  fi
  echo "Yosys SinglePortMemory checker rejected forbidden comparison mutation: $label"
}

yosys_single_port_memory_comparison_json_mutation_must_fail \
  short-comparator-rhs short-b
yosys_single_port_memory_comparison_json_mutation_must_fail \
  signed-comparator-rhs signed-b

yosys_single_port_memory_full_domain_reset_mutation_must_fail() {
  local label="$1"
  local connection="$2"
  local canonical_netlist="$tmp_dir/SinglePortMemory-depth-two-process.json"
  local mutated_netlist="$tmp_dir/SinglePortMemory-${label}-json-mutated.json"

  python3 - "$canonical_netlist" "$mutated_netlist" "$connection" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
connection = sys.argv[3]
netlist = json.loads(source.read_text(encoding="utf-8"))
top = netlist.get("modules", {}).get("SinglePortMemory")
if top is None:
    raise SystemExit("depth-two JSON is missing SinglePortMemory")
memories = [
    cell for cell in top.get("cells", {}).values() if cell.get("type") == "$mem_v2"
]
if len(memories) != 1:
    raise SystemExit("depth-two JSON does not contain exactly one $mem_v2")
connections = memories[0].get("connections", {})
value = connections.get(connection)
if not value or any(bit not in ("0", 0) for bit in value):
    raise SystemExit("canonical connection is not permanently inactive: " + repr(value))
clock = top.get("ports", {}).get("clk", {}).get("bits", [])
if len(clock) != 1 or clock[0] in ("0", "1", "x", "z", 0, 1):
    raise SystemExit("canonical clk port is not one signal bit: " + repr(clock))
connections[connection] = list(clock)
if connections[connection] == value:
    raise SystemExit("JSON mutation did not activate connection " + connection)
destination.write_text(json.dumps(netlist, indent=2) + "\n", encoding="utf-8")
PY

  if cmp -s "$canonical_netlist" "$mutated_netlist"; then
    echo "SinglePortMemory full-domain reset mutation did not change the netlist: $label" >&2
    exit 1
  fi
  if python3 "$repo_root/morphhdl/scripts/check-yosys-single-port-memory-contract.py" \
      "$mutated_netlist" --width 4 --depth 2; then
    echo "SinglePortMemory checker accepted forbidden full-domain reset mutation: $label" >&2
    exit 1
  fi
  echo "Yosys SinglePortMemory checker rejected forbidden full-domain reset mutation: $label"
}

yosys_single_port_memory_full_domain_reset_mutation_must_fail \
  active-asynchronous-full-domain-read-reset RD_ARST
yosys_single_port_memory_full_domain_reset_mutation_must_fail \
  active-synchronous-full-domain-read-reset RD_SRST

yosys_single_port_memory_mutation_must_fail() {
  local label="$1"
  local sed_expression="$2"
  local mutated_file="$tmp_dir/single-port-memory-${label}.v"
  local mutated_netlist="$tmp_dir/SinglePortMemory-${label}-mutated.json"

  sed "$sed_expression" "$yosys_single_port_memory_file" > "$mutated_file"
  if cmp -s "$yosys_single_port_memory_file" "$mutated_file"; then
    echo "SinglePortMemory mutation did not change the fixture: $label" >&2
    exit 1
  fi

  if ! yosys -q -p \
      "read_verilog -noautowire $mutated_file; hierarchy -check -top SinglePortMemory; proc; opt_reduce; opt_expr -mux_undef; memory_dff; memory_collect; opt_clean; check -assert; write_json $mutated_netlist"; then
    echo "Yosys rejected forbidden SinglePortMemory mutation during synthesis: $label"
    return
  fi
  if python3 "$repo_root/morphhdl/scripts/check-yosys-single-port-memory-contract.py" \
      "$mutated_netlist" --width 8 --depth 5; then
    echo "SinglePortMemory Yosys checker accepted forbidden mutation: $label" >&2
    exit 1
  fi
  echo "Yosys SinglePortMemory rejected forbidden mutation: $label"
}

yosys_single_port_memory_mutation_must_fail \
  write-first-bypass "s/read_data <= memory\[address\];/read_data <= write_enable == 1'b1 ? write_data : memory[address];/"
yosys_single_port_memory_mutation_must_fail \
  branch-swapped-read "s/read_data <= memory\[address\];/read_data <= __morph_read_swap__;/;s/read_data <= {WIDTH{1'b0}};/read_data <= memory[address];/;s/read_data <= __morph_read_swap__;/read_data <= {WIDTH{1'b0}};/"
yosys_single_port_memory_mutation_must_fail \
  valid-disabled-read-captures "0,/read_enable == 1'b1/s//1'b1 == 1'b1/"
yosys_single_port_memory_mutation_must_fail \
  surplus-disabled-read-captures "s/end else if (read_enable == 1'b1) begin/end else begin/"
yosys_single_port_memory_mutation_must_fail \
  active-low-read-enable "s/read_enable == 1'b1/read_enable == 1'b0/g"
yosys_single_port_memory_mutation_must_fail \
  write-requires-read-enable "s/write_enable == 1'b1/read_enable == 1'b1 \&\& write_enable == 1'b1/"
yosys_single_port_memory_mutation_must_fail \
  falling-edge-clock 's/posedge clk/negedge clk/'
yosys_single_port_memory_mutation_must_fail \
  unconditional-write "s/write_enable == 1'b1/1'b1 == 1'b1/"
yosys_single_port_memory_mutation_must_fail \
  nonzero-surplus-read "s/{WIDTH{1'b0}}/write_data/"
yosys_single_port_memory_mutation_must_fail \
  inverted-address-guard 's/address < DEPTH/address >= DEPTH/'
yosys_single_port_memory_mutation_must_fail \
  signed-address-guard 's/address < DEPTH/$signed(address) < DEPTH/'
yosys_single_port_memory_mutation_must_fail \
  initialized-memory "/always @(posedge clk)/i\\  initial memory[0] = {WIDTH{1'b0}};"
yosys_single_port_memory_mutation_must_fail \
  extra-memory-word 's/\[0:DEPTH-1\]/[0:DEPTH]/'

fixed_address_file="$tmp_dir/single-port-memory-fixed-address.v"
fixed_address_netlist="$tmp_dir/SinglePortMemory-fixed-address-mutated.json"
sed 's/\[[^]]*-1:0\] address/[2:0] address/' \
  "$yosys_single_port_memory_file" > "$fixed_address_file"
if cmp -s "$yosys_single_port_memory_file" "$fixed_address_file"; then
  echo "SinglePortMemory fixed-address mutation did not change the fixture" >&2
  exit 1
fi
yosys -q -p \
  "read_verilog -noautowire $fixed_address_file; chparam -set DEPTH 3 SinglePortMemory; hierarchy -check -top SinglePortMemory; proc; opt_reduce; opt_expr -mux_undef; memory_dff; memory_collect; opt_clean; check -assert; write_json $fixed_address_netlist"
if python3 "$repo_root/morphhdl/scripts/check-yosys-single-port-memory-contract.py" \
    "$fixed_address_netlist" --width 8 --depth 3; then
  echo "SinglePortMemory checker accepted a fixed three-bit address bypass" >&2
  exit 1
fi
echo "Yosys SinglePortMemory rejected fixed three-bit address derivation bypass"

echo "Strict Verilog-2001 contract checks passed"
