#!/usr/bin/env bash

set -euo pipefail

require_tools=0
if [[ "${1:-}" == "--require-tools" ]]; then
  require_tools=1
elif [[ -n "${1:-}" ]]; then
  echo "Usage: $0 [--require-tools]" >&2
  exit 2
fi

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(git -C "$script_dir" rev-parse --show-toplevel)"
contract_file="$repo_root/morphhdl/contracts/verilog-2001.properties"
examples_dir="$repo_root/morphhdl/examples/contracts"

design_files=(
  "$examples_dir/parameterized_wire.v"
  "$examples_dir/lane_array.v"
)

all_verilog_files=(
  "${design_files[@]}"
  "$examples_dir/parameterized_wire_tb.v"
  "$examples_dir/lane_array_tb.v"
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
require_property port.conditional_presence false

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
  PixelLane
  LaneArray
  LaneArrayTb
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

if ! grep -Eq 'for[[:space:]]*\(' "${design_files[1]}" ||
   ! grep -Eq '\+:[[:space:]]*DATA_WIDTH' "${design_files[1]}"; then
  echo "LaneArray does not exercise generate-for and indexed part-select" >&2
  exit 1
fi

missing_tools=()
for tool in iverilog vvp yosys; do
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

iverilog -g2001 -Wall -s ParameterizedWireTb \
  -o "$tmp_dir/parameterized_wire.vvp" \
  "$examples_dir/parameterized_wire.v" \
  "$examples_dir/parameterized_wire_tb.v"
wire_output="$(vvp "$tmp_dir/parameterized_wire.vvp")"
echo "$wire_output"
if ! printf '%s\n' "$wire_output" | grep -q 'PASS: ParameterizedWire'; then
  echo "ParameterizedWire simulation did not report PASS" >&2
  exit 1
fi

iverilog -g2001 -Wall -s LaneArrayTb \
  -o "$tmp_dir/lane_array.vvp" \
  "$examples_dir/lane_array.v" \
  "$examples_dir/lane_array_tb.v"
lane_output="$(vvp "$tmp_dir/lane_array.vvp")"
echo "$lane_output"
if ! printf '%s\n' "$lane_output" | grep -q 'PASS: LaneArray'; then
  echo "LaneArray simulation did not report PASS" >&2
  exit 1
fi

(
  cd "$examples_dir"
  yosys -q -p 'read_verilog parameterized_wire.v; hierarchy -check -top ParameterizedWire; proc; check'
  yosys -q -p 'read_verilog lane_array.v; hierarchy -check -top LaneArray; proc; check'
)

echo "Strict Verilog-2001 contract checks passed"
