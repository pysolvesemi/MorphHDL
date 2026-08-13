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
)

all_verilog_files=(
  "${design_files[@]}"
  "$examples_dir/parameterized_wire_tb.v"
  "$examples_dir/derived_width_tb.v"
  "$examples_dir/parameter_forwarding_tb.v"
  "$examples_dir/lane_array_tb.v"
  "$examples_dir/conditional_forwarding_tb.v"
  "$examples_dir/comparison_routing_tb.v"
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
require_property port.conditional_presence false
require_property structure.module_instance true
require_property structure.named_parameter_binding true
require_property structure.named_port_binding true
require_property structure.generate_for true
require_property structure.generate_if true
require_property implementation.generate_if true
require_property implementation.generate_case false

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
yosys_parameterized_wire_file="$tmp_dir/parameterized_wire.v"
yosys_derived_width_file="$tmp_dir/derived_width.v"
yosys_parameter_forwarding_file="$tmp_dir/parameter_forwarding.v"
yosys_lane_array_file="$tmp_dir/lane_array.v"
yosys_conditional_forwarding_file="$tmp_dir/conditional_forwarding.v"
yosys_comparison_routing_file="$tmp_dir/comparison_routing.v"

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

echo "Strict Verilog-2001 contract checks passed"
