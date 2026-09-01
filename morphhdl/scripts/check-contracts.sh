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
  symbolic_data_shapes.v
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
  parameterized_counter.v
  simple_dual_port_memory.v
  synchronous_stream_fifo.v
  synchronous_stream_m2s_pipe.v
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
symbolic_data_shapes_file="$generated_dir/symbolic_data_shapes.v"
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
parameterized_counter_file="$generated_dir/parameterized_counter.v"
simple_dual_port_memory_file="$generated_dir/simple_dual_port_memory.v"
synchronous_stream_fifo_file="$generated_dir/synchronous_stream_fifo.v"
synchronous_stream_m2s_pipe_file="$generated_dir/synchronous_stream_m2s_pipe.v"

parity_args=("$parity_file")
for live_phase_id_file in "${live_phase_id_files[@]}"; do
  parity_args+=(--live-phase-ids "$live_phase_id_file")
done
python3 "$repo_root/morphhdl/scripts/check-validation-parity.py" "${parity_args[@]}"
python3 "$repo_root/morphhdl/scripts/check-parameter-operators.py" "$operator_file"

design_files=(
  "$parameterized_wire_file"
  "$symbolic_data_shapes_file"
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
  "$parameterized_counter_file"
  "$simple_dual_port_memory_file"
  "$synchronous_stream_fifo_file"
  "$synchronous_stream_m2s_pipe_file"
)

all_verilog_files=(
  "${design_files[@]}"
  "$examples_dir/parameterized_wire_tb.v"
  "$examples_dir/symbolic_data_shapes_tb.v"
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
  "$examples_dir/parameterized_counter_tb.v"
  "$examples_dir/simple_dual_port_memory_tb.v"
  "$examples_dir/synchronous_stream_fifo_tb.v"
  "$examples_dir/synchronous_stream_m2s_pipe_tb.v"
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
require_property backend.single_source_emitter native-spinal-verilog
require_property frontend.single_source_component true
require_property frontend.symbolic_width positive-direct-public-hdlint
require_property frontend.symbolic_width_domain positive-finite-within-bit-vector-limit
require_property frontend.symbolic_width_types bits-uint-sint
require_property frontend.symbolic_width_locations port-internal-register
require_property frontend.symbolic_width_propagation clone-hardtype-bundle-vec-stream-flow
require_property frontend.symbolic_width_logic ordinary-assignment-mux-arithmetic-concat-fixed-slice-domain-invariant-narrowing-resize
require_property frontend.concrete_bool_controls true
require_property frontend.symbolic_vec_length positive-finite-elabint
require_property frontend.literal_width concrete-bits-uint-sint-no-symbolic-tag
require_property frontend.single_source_literal_bitvector_ports reject-when-no-symbolic-schema
require_property frontend.legacy_spinalverilog concrete-witness
require_property implementation.single_source_symbolic_width true
require_property implementation.single_source_symbolic_data_shapes true
require_property implementation.single_source_generic_expressions true
require_property implementation.single_source_native_stream_m2s_pipe true
require_property frontend.single_source_hierarchy ordinary-component-direct-packed-boundary
require_property frontend.single_source_parameter_binding connection-inferred-named-width-parameter-or-literal
require_property implementation.single_source_hierarchy true
require_property implementation.single_source_parameter_binding true
require_property aggregate.vec.logical_shape typed-depth-and-element-layout
require_property aggregate.vec.verilog2001_storage single-packed-vector
require_property memory.verilog2001_storage unpacked-array
require_property parameter.boolean_encoding integer
require_property parameter.integer_comparison true
require_property parameter.integer_conditional true
require_property parameter.integer_minimum true
require_property parameter.integer_maximum true
require_property parameter.min_max_expansion_limit 4096
require_property parameter.ceil_log2 module-local-constant-function-minimum-zero
require_property parameter.address_width module-local-constant-function-minimum-one
require_property parameter.log2_helper 'clog2'
require_property parameter.log2_helper_collision deterministic-numeric-suffix
require_property parameter.log2_operand_domain positive-signed-32
require_property parameter.log2_helper_runtime_hardware false
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
require_property implementation.synchronous_counter true
require_property counter.limit positive-direct-public-parameter
require_property counter.width address-width-of-limit
require_property counter.reset active-high-synchronous-zero
require_property counter.enable active-high-hold
require_property counter.rollover limit-minus-one-to-zero
require_property counter.direction up
require_property counter.out_of_range forbidden
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
require_property memory.simple_dual_port single-clock-1r1w
require_property memory.simple_dual_port_addresses independent-type-equivalent-capacity-proven
require_property memory.simultaneous_read_write true
require_property implementation.synchronous_read_first_simple_dual_port_memory true
require_property fifo.synchronous_stream single-clock-ready-valid
require_property fifo.capacity public-depth-includes-registered-pop-stage
require_property fifo.storage synchronous-read-no-bypass
require_property fifo.reset active-high-synchronous-control-only
require_property fifo.full_push reject-even-with-pop-ready
require_property fifo.empty_pop reject-even-with-push-valid
require_property fifo.middle_simultaneous_push_pop true
require_property fifo.occupancy_one_refill one-invalid-cycle
require_property fifo.stalled_pop valid-and-data-hold
require_property fifo.memory_initialization false
require_property fifo.pop_data_when_invalid unspecified
require_property implementation.synchronous_stream_fifo true
require_property stream.m2s_pipe single-clock-one-entry-ready-valid
require_property stream.m2s_pipe.latency one-edge
require_property stream.m2s_pipe.ready pop-ready-or-empty
require_property stream.m2s_pipe.full_replacement bubble-free
require_property stream.m2s_pipe.stalled_pop valid-and-data-hold
require_property stream.m2s_pipe.payload_capture whenever-push-ready
require_property stream.m2s_pipe.reset active-high-synchronous-valid-only
require_property stream.m2s_pipe.pop_data_when_invalid unspecified
require_property implementation.synchronous_stream_m2s_pipe true

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
  echo "Sizing helper outside the strict Verilog-2001 baseline found in fixture" >&2
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
  SymbolicDataShapes
  SymbolicDataShapesCase
  SymbolicDataShapesTb
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
  ParameterizedCounter
  ParameterizedCounterTb
  SimpleDualPortMemory
  SimpleDualPortMemoryTb
  SynchronousStreamFifo
  SynchronousStreamFifoTb
  SynchronousStreamM2sPipe
  SynchronousStreamM2sPipeTb
)

for module_name in "${expected_modules[@]}"; do
  if ! printf '%s\n' "$module_names" | grep -qx "$module_name"; then
    echo "Expected contract module is missing: $module_name" >&2
    exit 1
  fi
done

if ! python3 - "${design_files[@]}" <<'PY'
import pathlib
import sys

helper = """  function integer clog2;
    input integer value;
    input integer minimum_result;
    integer remaining;
    begin
      clog2 = 0;
      for (remaining = value - 1; remaining > 0; remaining = remaining >> 1) begin
        clog2 = clog2 + 1;
      end
      if (clog2 < minimum_result) begin
        clog2 = minimum_result;
      end
    end
  endfunction"""

expected_helpers = {
    "derived_width.v",
    "single_port_memory.v",
    "parameterized_counter.v",
    "simple_dual_port_memory.v",
    "synchronous_stream_fifo.v",
}
sources = {}
for raw_path in sys.argv[1:]:
    path = pathlib.Path(raw_path)
    source = path.read_text(encoding="utf-8")
    sources[path.name] = source
    if path.name in expected_helpers:
        if source.count(helper) != 1:
            raise SystemExit("missing or duplicate canonical log helper: {}".format(path))
    elif "clog2" in source:
        raise SystemExit("unexpected log helper in unrelated module: {}".format(path))
    if "1073741824" in source:
        raise SystemExit("superseded logarithm threshold chain remains: {}".format(path))

derived = sources["derived_width.v"]
memory = sources["single_port_memory.v"]
counter = sources["parameterized_counter.v"]
simple_dual_port_memory = sources["simple_dual_port_memory.v"]
synchronous_stream_fifo = sources["synchronous_stream_fifo.v"]
if derived.count("clog2(LANES, 0)") != 1:
    raise SystemExit("DerivedWidth does not call mathematical ceiling-log2 exactly once")
if memory.count("clog2(DEPTH, 1)") != 1:
    raise SystemExit("SinglePortMemory does not call address width exactly once")
if counter.count("clog2(LIMIT, 1)") != 3:
    raise SystemExit("ParameterizedCounter does not use its derived width exactly three times")
if simple_dual_port_memory.count("clog2(DEPTH, 1)") != 2:
    raise SystemExit("SimpleDualPortMemory does not derive both address widths exactly once")
if synchronous_stream_fifo.count("clog2(DEPTH, 1)") != 1:
    raise SystemExit("SynchronousStreamFifo does not derive pointer width exactly once")
if synchronous_stream_fifo.count("clog2(DEPTH + 1, 1)") != 1:
    raise SystemExit("SynchronousStreamFifo does not derive occupancy width exactly once")
PY
then
  echo "Constant-function logarithm lowering contract failed" >&2
  exit 1
fi

if ! python3 - "$parameterized_wire_file" <<'PY'
import pathlib
import re
import sys

source = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
required = (
    "  parameter integer WIDTH = 8",
    "  input  wire [WIDTH-1:0] din,",
    "  output wire [WIDTH-1:0] dout",
    "  assign dout = din;",
)
if any(source.count(fragment) != 1 for fragment in required):
    raise SystemExit("missing or duplicate native symbolic-width construct")
if len(re.findall(r"\bparameter\s+integer\b", source)) != 1:
    raise SystemExit("expected exactly one public integer parameter")
if len(re.findall(r"\bmodule\s+ParameterizedWire\b", source)) != 1:
    raise SystemExit("expected exactly one logical module definition")
if re.search(r"\b(localparam|function|always|reg|generate|genvar)\b", source):
    raise SystemExit("single-source direct-wire contract contains deferred RTL")
PY
then
  echo "ParameterizedWire does not retain the bounded native symbolic-width bridge" >&2
  exit 1
fi

if ! grep -Fqx '  localparam integer MINIMUM_WIDTH = 1;' "$examples_dir/parameterized_wire_tb.v" ||
   ! grep -Fqx '  localparam integer AWKWARD_WIDTH = 13;' "$examples_dir/parameterized_wire_tb.v" ||
   ! grep -Fqx '  localparam integer MAXIMUM_WIDTH = 64;' "$examples_dir/parameterized_wire_tb.v" ||
   [[ "$(grep -Ec '^[[:space:]]*ParameterizedWire([[:space:]]|[[:space:]]*#)' "$examples_dir/parameterized_wire_tb.v")" != "4" ]]; then
  echo "ParameterizedWire testbench does not retain default, minimum, awkward and maximum instances" >&2
  exit 1
fi

if ! python3 - "$symbolic_data_shapes_file" <<'PY'
import pathlib
import re
import sys

source = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
symbolic_ports = {
    "bits_in", "bundle_in_bits", "bundle_in_sint", "bundle_in_uint",
    "flow_in_payload_bits", "flow_in_payload_sint", "flow_in_payload_uint",
    "sint_in", "stream_in_payload_bits", "stream_in_payload_sint",
    "stream_in_payload_uint", "uint_in",
    "bits_out", "bundle_out_bits", "bundle_out_sint", "bundle_out_uint",
    "flow_out_payload_bits", "flow_out_payload_sint", "flow_out_payload_uint",
    "register_out_bits", "register_out_sint", "register_out_uint", "sint_out",
    "stream_out_payload_bits", "stream_out_payload_sint",
    "stream_out_payload_uint", "uint_out",
}
vec_ports = {"vec_in", "vec_out"}
bool_ports = {
    "clk", "flow_in_valid", "stream_in_valid", "stream_out_ready",
    "flow_out_valid", "stream_in_ready", "stream_out_valid",
}
port_pattern = re.compile(
    r"^  (input|output)\s+wire\s+(?:(\[[^\]]+\])\s+)?([A-Za-z0-9_]+),?$",
    re.MULTILINE,
)
ports = {name: (direction, packed) for direction, packed, name in port_pattern.findall(source)}
if set(ports) != symbolic_ports | vec_ports | bool_ports:
    raise SystemExit("native symbolic-data-shape port inventory changed")
if any(ports[name][1] != "[WIDTH-1:0]" for name in symbolic_ports):
    raise SystemExit("a symbolic data-shape port lost WIDTH")
if any(ports[name][1] for name in bool_ports):
    raise SystemExit("a concrete Bool control gained a packed symbolic range")
if ports["vec_in"][0] != "input" or ports["vec_out"][0] != "output":
    raise SystemExit("packed Vec port directions changed")
if ports["vec_in"][1] != ports["vec_out"][1]:
    raise SystemExit("packed Vec input and output ranges differ")
vec_range = ports["vec_in"][1]
if vec_range is None:
    raise SystemExit("Vec ports lost their packed range")
compact_vec_range = re.sub(r"\s+", "", vec_range)
if (
    "WIDTH" not in compact_vec_range
    or "*" not in compact_vec_range
    or not re.fullmatch(r"\[[()WIDTH+*0-9-]+:0\]", compact_vec_range)
):
    raise SystemExit("Vec packed range lost its typed width/depth factors")
for width in range(1, 65):
    high = compact_vec_range[1:-3].replace("WIDTH", str(width))
    if eval(high, {"__builtins__": {}}, {}) != 6 * width - 1:
        raise SystemExit("Vec packed range is not six WIDTH bits")
if re.search(r"\bvec_(in|out)_[0-9]+", source):
    raise SystemExit("Vec escaped as exploded element ports")
if source.count("[WIDTH-1:0]") != 33:
    raise SystemExit("expected exactly 27 ordinary symbolic ports and six symbolic internals")
if len(re.findall(r"\bparameter\s+integer\s+WIDTH\s*=\s*8\b", source)) != 1:
    raise SystemExit("expected exactly one WIDTH public parameter")
if len(re.findall(r"^  wire\s+\[WIDTH-1:0\]\s+internal_payload_", source, re.MULTILINE)) != 3:
    raise SystemExit("expected three symbolic internal Bundle leaves")
if len(re.findall(r"^  reg\s+\[WIDTH-1:0\]\s+payload_register_", source, re.MULTILINE)) != 3:
    raise SystemExit("expected three symbolic register Bundle leaves")
if len(re.findall(r"^  assign\s+", source, re.MULTILINE)) != 22:
    raise SystemExit("expected the exact direct equal-shape assignment inventory")
if source.count("  assign vec_out = vec_in;") != 1:
    raise SystemExit("packed Vec is not one direct structural assignment")
if len(re.findall(r"^  always @\(posedge clk\) begin$", source, re.MULTILINE)) != 1:
    raise SystemExit("expected one bounded unconditional register process")
if len(re.findall(r"^    payload_register_(bits|uint|sint) <= bundle_in_\1;$", source, re.MULTILINE)) != 3:
    raise SystemExit("register leaves do not capture their same-type Bundle inputs")
if re.search(r"\b(localparam|function|generate|genvar)\b|\$signed", source):
    raise SystemExit("symbolic shape fixture contains a deferred construct")
PY
then
  echo "SymbolicDataShapes does not retain the bounded native shape contract" >&2
  exit 1
fi

for memory_contract in "$single_port_memory_file" "$simple_dual_port_memory_file"; do
  if ! grep -Eq \
      '^[[:space:]]*reg[[:space:]]+\[WIDTH-1:0\][[:space:]]+memory[[:space:]]+\[0:DEPTH-1\];$' \
      "$memory_contract"; then
    echo "Vec/Memory storage distinction changed: Mem must remain an unpacked array" >&2
    exit 1
  fi
done

if [[ "$(grep -Ec '^[[:space:]]*SymbolicDataShapesCase[[:space:]]*#' "$examples_dir/symbolic_data_shapes_tb.v")" != "4" ]] ||
   [[ "$(grep -Fc '.WIDTH(1),' "$examples_dir/symbolic_data_shapes_tb.v")" != "1" ]] ||
   [[ "$(grep -Fc '.WIDTH(8),' "$examples_dir/symbolic_data_shapes_tb.v")" != "1" ]] ||
   [[ "$(grep -Fc '.WIDTH(13),' "$examples_dir/symbolic_data_shapes_tb.v")" != "1" ]] ||
   [[ "$(grep -Fc '.WIDTH(64),' "$examples_dir/symbolic_data_shapes_tb.v")" != "1" ]] ||
   [[ "$(grep -Fc '.USE_DEFAULT(1)' "$examples_dir/symbolic_data_shapes_tb.v")" != "1" ]] ||
   [[ "$(grep -Fc 'localparam integer VEC_WIDTH = 6 * WIDTH;' "$examples_dir/symbolic_data_shapes_tb.v")" != "1" ]] ||
   [[ "$(grep -Ec '^[[:space:]]*\.vec_(in|out)[[:space:]]*\(' "$examples_dir/symbolic_data_shapes_tb.v")" != "4" ]] ||
   grep -Eq '\bvec_(in|out)_[0-9]+' "$examples_dir/symbolic_data_shapes_tb.v"; then
  echo "SymbolicDataShapes testbench lost its four width cases or packed Vec ABI" >&2
  exit 1
fi

if ! grep -Eq 'localparam[[:space:]]+integer[[:space:]]+TOTAL_WIDTH[[:space:]]*=[[:space:]]*LANES[[:space:]]*\*[[:space:]]*DATA_WIDTH' "$derived_width_file" ||
   ! grep -Eq 'localparam[[:space:]]+integer[[:space:]]+CLAMPED_PADDING[[:space:]]*=.*DATA_WIDTH[[:space:]]*<[[:space:]]*3.*\?.*DATA_WIDTH.*:.*3' "$derived_width_file" ||
   ! grep -Eq 'localparam[[:space:]]+integer[[:space:]]+LANE_INDEX_WIDTH[[:space:]]*=[[:space:]]*clog2\([[:space:]]*LANES,[[:space:]]*0[[:space:]]*\)' "$derived_width_file" ||
   ! grep -Eq 'localparam[[:space:]]+integer[[:space:]]+PADDED_WIDTH[[:space:]]*=.*TOTAL_WIDTH[[:space:]]*\+[[:space:]]*CLAMPED_PADDING[[:space:]]*>[[:space:]]*4.*\?.*TOTAL_WIDTH[[:space:]]*\+[[:space:]]*CLAMPED_PADDING.*:.*4.*\+[[:space:]]*LANE_INDEX_WIDTH' "$derived_width_file"; then
  echo "DerivedWidth does not retain its symbolic Min/Max/CeilLog2 local-parameter expressions" >&2
  exit 1
fi

if ! python3 - "$derived_width_file" <<'PY'
import pathlib
import sys

source = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
expected = (
    "  localparam integer CLAMPED_PADDING = "
    "(DATA_WIDTH < 3) ? DATA_WIDTH : 3;",
    "  localparam integer PADDED_WIDTH = "
    "((TOTAL_WIDTH + CLAMPED_PADDING > 4) ? "
    "TOTAL_WIDTH + CLAMPED_PADDING : 4) + LANE_INDEX_WIDTH;",
)
if any(source.count(line) != 1 for line in expected):
    raise SystemExit("missing exact canonical Min/Max local-parameter lowering")
PY
then
  echo "DerivedWidth Min/Max comparator or branch order is not canonical" >&2
  exit 1
fi

if ! grep -Eq 'localparam[[:space:]]+integer[[:space:]]+TOTAL_WIDTH[[:space:]]*=[[:space:]]*LANES[[:space:]]*\*[[:space:]]*DATA_WIDTH' "$parameter_forwarding_file" ||
   ! grep -Eq '\.WIDTH[[:space:]]*\([[:space:]]*TOTAL_WIDTH[[:space:]]*\)' "$parameter_forwarding_file" ||
   ! grep -Eq '\)[[:space:]]+forwarded_inst[[:space:]]*\(' "$parameter_forwarding_file" ||
   ! grep -Eq '\.din[[:space:]]*\([[:space:]]*din[[:space:]]*\)' "$parameter_forwarding_file" ||
   ! grep -Eq '\.dout[[:space:]]*\([[:space:]]*dout[[:space:]]*\)' "$parameter_forwarding_file"; then
  echo "ParameterForwarding does not retain its named symbolic child bindings" >&2
  exit 1
fi

if ! grep -Eq 'genvar[[:space:]]+lane[[:space:]]*;' "$lane_array_file" ||
   ! grep -Eq 'for[[:space:]]*\([[:space:]]*lane[[:space:]]*=[[:space:]]*0[[:space:]]*;[[:space:]]*lane[[:space:]]*<[[:space:]]*LANES[[:space:]]*;' "$lane_array_file" ||
   ! grep -Eq 'begin[[:space:]]*:[[:space:]]*g_lane' "$lane_array_file" ||
   ! grep -Eq 'lane[[:space:]]*\*[[:space:]]*DATA_WIDTH[[:space:]]*\+:[[:space:]]*DATA_WIDTH' "$lane_array_file" ||
   [[ "$(grep -Ec '\)[[:space:]]+lane_inst[[:space:]]*\(' "$lane_array_file")" != "1" ]]; then
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

expected_address_port='  input  wire [(clog2(DEPTH, 1))-1:0] address,'

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

expected_counter_port='  output reg [(clog2(LIMIT, 1))-1:0] count,'

if ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+LIMIT[[:space:]]*=[[:space:]]*5' "$parameterized_counter_file" ||
   ! grep -Fqx "$expected_counter_port" "$parameterized_counter_file" ||
   ! grep -Eq 'input[[:space:]]+wire[[:space:]]+\[0:0\][[:space:]]+clk' "$parameterized_counter_file" ||
   ! grep -Eq 'input[[:space:]]+wire[[:space:]]+\[0:0\][[:space:]]+reset' "$parameterized_counter_file" ||
   ! grep -Eq 'input[[:space:]]+wire[[:space:]]+\[0:0\][[:space:]]+enable' "$parameterized_counter_file" ||
   ! grep -Eq 'always[[:space:]]+@\([[:space:]]*posedge[[:space:]]+clk[[:space:]]*\)[[:space:]]+begin[[:space:]]*:[[:space:]]*p_counter' "$parameterized_counter_file" ||
   ! grep -Eq "if[[:space:]]*\\([[:space:]]*reset[[:space:]]*==[[:space:]]*1'b1[[:space:]]*\\)[[:space:]]*begin" "$parameterized_counter_file" ||
   ! grep -Eq "end[[:space:]]+else[[:space:]]+if[[:space:]]*\\([[:space:]]*enable[[:space:]]*==[[:space:]]*1'b1[[:space:]]*\\)[[:space:]]*begin" "$parameterized_counter_file" ||
   ! grep -Eq 'if[[:space:]]*\([[:space:]]*count[[:space:]]*==[[:space:]]*LIMIT[[:space:]]*-[[:space:]]*1[[:space:]]*\)[[:space:]]*begin' "$parameterized_counter_file" ||
   [[ "$(grep -Ec "count[[:space:]]*<=[[:space:]]*\\{clog2\\(LIMIT,[[:space:]]*1\\)\\{1'b0\\}\\}[[:space:]]*;" "$parameterized_counter_file")" != "2" ]] ||
   ! grep -Eq "count[[:space:]]*<=[[:space:]]*count[[:space:]]*\\+[[:space:]]*1'b1[[:space:]]*;" "$parameterized_counter_file" ||
   [[ "$(grep -Ec 'always[[:space:]]+@\(' "$parameterized_counter_file")" != "1" ]] ||
   [[ "$(grep -Ec 'count[[:space:]]*<=' "$parameterized_counter_file")" != "3" ]] ||
   grep -Eq 'always_comb|always_ff|always_latch|always[[:space:]]+@\*|initial[[:space:]]+begin|count[[:space:]]*=[^=]' "$parameterized_counter_file"; then
  echo "ParameterizedCounter does not retain reset-priority enabled modulo-up-count semantics" >&2
  exit 1
fi

if ! python3 - "$parameterized_counter_file" <<'PY'
import pathlib
import sys

source = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
canonical_process = """  always @(posedge clk) begin : p_counter
    if (reset == 1'b1) begin
      count <= {clog2(LIMIT, 1){1'b0}};
    end else if (enable == 1'b1) begin
      if (count == LIMIT - 1) begin
        count <= {clog2(LIMIT, 1){1'b0}};
      end else begin
        count <= count + 1'b1;
      end
    end
  end"""
if source.count(canonical_process) != 1:
    raise SystemExit("missing exact synchronous counter process")
PY
then
  echo "ParameterizedCounter process is not canonical" >&2
  exit 1
fi

expected_read_address_port='  input  wire [(clog2(DEPTH, 1))-1:0] read_address,'
expected_write_address_port='  input  wire [(clog2(DEPTH, 1))-1:0] write_address,'

if ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+DEPTH[[:space:]]*=[[:space:]]*5' "$simple_dual_port_memory_file" ||
   ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+WIDTH[[:space:]]*=[[:space:]]*8' "$simple_dual_port_memory_file" ||
   ! grep -Fqx "$expected_read_address_port" "$simple_dual_port_memory_file" ||
   ! grep -Fqx "$expected_write_address_port" "$simple_dual_port_memory_file" ||
   ! grep -Eq 'input[[:space:]]+wire[[:space:]]+\[0:0\][[:space:]]+clk' "$simple_dual_port_memory_file" ||
   ! grep -Eq 'input[[:space:]]+wire[[:space:]]+\[0:0\][[:space:]]+read_enable' "$simple_dual_port_memory_file" ||
   ! grep -Eq 'input[[:space:]]+wire[[:space:]]+\[0:0\][[:space:]]+write_enable' "$simple_dual_port_memory_file" ||
   ! grep -Eq 'input[[:space:]]+wire[[:space:]]+\[WIDTH-1:0\][[:space:]]+write_data' "$simple_dual_port_memory_file" ||
   ! grep -Eq 'output[[:space:]]+reg[[:space:]]+\[WIDTH-1:0\][[:space:]]+read_data' "$simple_dual_port_memory_file" ||
   ! grep -Eq 'reg[[:space:]]+\[WIDTH-1:0\][[:space:]]+memory[[:space:]]+\[0:DEPTH-1\][[:space:]]*;' "$simple_dual_port_memory_file" ||
   ! grep -Eq 'always[[:space:]]+@\([[:space:]]*posedge[[:space:]]+clk[[:space:]]*\)[[:space:]]+begin[[:space:]]*:[[:space:]]*p_memory' "$simple_dual_port_memory_file" ||
   ! grep -Eq 'if[[:space:]]*\([[:space:]]*read_address[[:space:]]*<[[:space:]]*DEPTH[[:space:]]*\)[[:space:]]*begin' "$simple_dual_port_memory_file" ||
   ! grep -Eq 'if[[:space:]]*\([[:space:]]*write_address[[:space:]]*<[[:space:]]*DEPTH[[:space:]]*\)[[:space:]]*begin' "$simple_dual_port_memory_file" ||
   [[ "$(grep -Ec "read_enable[[:space:]]*==[[:space:]]*1'b1" "$simple_dual_port_memory_file")" != "2" ]] ||
   [[ "$(grep -Ec "write_enable[[:space:]]*==[[:space:]]*1'b1" "$simple_dual_port_memory_file")" != "1" ]] ||
   ! grep -Eq 'read_data[[:space:]]*<=[[:space:]]*memory\[read_address\][[:space:]]*;' "$simple_dual_port_memory_file" ||
   ! grep -Eq 'memory\[write_address\][[:space:]]*<=[[:space:]]*write_data[[:space:]]*;' "$simple_dual_port_memory_file" ||
   ! grep -Eq "read_data[[:space:]]*<=[[:space:]]*\{WIDTH\{1'b0\}\}[[:space:]]*;" "$simple_dual_port_memory_file" ||
   [[ "$(grep -Ec 'always[[:space:]]+@\(' "$simple_dual_port_memory_file")" != "1" ]] ||
   [[ "$(grep -Ec 'memory\[write_address\][[:space:]]*<=' "$simple_dual_port_memory_file")" != "1" ]] ||
   [[ "$(grep -Ec 'read_data[[:space:]]*<=' "$simple_dual_port_memory_file")" != "2" ]] ||
   grep -Eq 'always_comb|always_ff|always_latch|always[[:space:]]+@\*|initial[[:space:]]+begin|read_data[[:space:]]*=[^=]|memory\[write_address\][[:space:]]*=[^=]' "$simple_dual_port_memory_file"; then
  echo "SimpleDualPortMemory does not retain one independent-address 1R1W synchronous read-first memory" >&2
  exit 1
fi

if ! python3 - "$simple_dual_port_memory_file" <<'PY'
import pathlib
import sys

source = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
canonical_process = """  always @(posedge clk) begin : p_memory
    if (read_address < DEPTH) begin
      if (read_enable == 1'b1) begin
        read_data <= memory[read_address];
      end
    end else if (read_enable == 1'b1) begin
      read_data <= {WIDTH{1'b0}};
    end
    if (write_address < DEPTH) begin
      if (write_enable == 1'b1) begin
        memory[write_address] <= write_data;
      end
    end
  end"""
if source.count(canonical_process) != 1:
    raise SystemExit("missing exact independent-address read-first 1R1W process")
PY
then
  echo "SimpleDualPortMemory process is not canonical" >&2
  exit 1
fi

if ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+DEPTH[[:space:]]*=[[:space:]]*5' "$synchronous_stream_fifo_file" ||
   ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+WIDTH[[:space:]]*=[[:space:]]*8' "$synchronous_stream_fifo_file" ||
   ! grep -Fqx '  localparam integer POINTER_WIDTH = clog2(DEPTH, 1);' "$synchronous_stream_fifo_file" ||
   ! grep -Fqx '  localparam integer OCCUPANCY_WIDTH = clog2(DEPTH + 1, 1);' "$synchronous_stream_fifo_file" ||
   ! grep -Fqx '  reg [WIDTH-1:0] memory [0:DEPTH-1];' "$synchronous_stream_fifo_file" ||
   ! grep -Fqx '  reg [POINTER_WIDTH-1:0] read_pointer;' "$synchronous_stream_fifo_file" ||
   ! grep -Fqx '  reg [POINTER_WIDTH-1:0] write_pointer;' "$synchronous_stream_fifo_file" ||
   ! grep -Fqx '  reg [OCCUPANCY_WIDTH-1:0] occupancy;' "$synchronous_stream_fifo_file" ||
   ! grep -Fqx '  assign push_ready = occupancy < DEPTH;' "$synchronous_stream_fifo_file" ||
   ! grep -Fqx '  assign push_fire = push_valid && push_ready;' "$synchronous_stream_fifo_file" ||
   ! grep -Fqx '  assign pop_fire = pop_valid && pop_ready;' "$synchronous_stream_fifo_file" ||
   [[ "$(grep -Ec 'always[[:space:]]+@\(' "$synchronous_stream_fifo_file")" != "1" ]] ||
   grep -Eq 'always_comb|always_ff|always_latch|always[[:space:]]+@\*|negedge|initial[[:space:]]+begin' "$synchronous_stream_fifo_file"; then
  echo "SynchronousStreamFifo does not retain its exact bounded synchronous ready/valid state" >&2
  exit 1
fi

if ! python3 - "$synchronous_stream_fifo_file" <<'PY'
import pathlib
import sys

source = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
canonical_process = """  always @(posedge clk) begin : p_fifo
    if (reset == 1'b1) begin
      read_pointer <= {POINTER_WIDTH{1'b0}};
      write_pointer <= {POINTER_WIDTH{1'b0}};
      occupancy <= {OCCUPANCY_WIDTH{1'b0}};
      pop_valid <= 1'b0;
    end else begin
      if ((pop_valid == 1'b0 && occupancy > 0) || (pop_fire == 1'b1 && occupancy > 1)) begin
        pop_data <= memory[read_pointer];
        pop_valid <= 1'b1;
        if (read_pointer == DEPTH - 1) begin
          read_pointer <= {POINTER_WIDTH{1'b0}};
        end else begin
          read_pointer <= read_pointer + 1'b1;
        end
      end else if (pop_fire == 1'b1) begin
        pop_valid <= 1'b0;
      end
      if (push_fire == 1'b1) begin
        memory[write_pointer] <= push_data;
        if (write_pointer == DEPTH - 1) begin
          write_pointer <= {POINTER_WIDTH{1'b0}};
        end else begin
          write_pointer <= write_pointer + 1'b1;
        end
      end
      if (push_fire != pop_fire) begin
        if (push_fire == 1'b1) begin
          occupancy <= occupancy + 1'b1;
        end else begin
          occupancy <= occupancy - 1'b1;
        end
      end
    end
  end"""
if source.count(canonical_process) != 1:
    raise SystemExit("missing exact synchronous Stream FIFO process")
reset_body = source.split("if (reset == 1'b1) begin", 1)[1].split("end else begin", 1)[0]
if "pop_data" in reset_body or "memory" in reset_body:
    raise SystemExit("FIFO reset illegally initializes payload or memory")
PY
then
  echo "SynchronousStreamFifo process is not canonical" >&2
  exit 1
fi

if ! grep -Eq 'parameter[[:space:]]+integer[[:space:]]+WIDTH[[:space:]]*=[[:space:]]*8' "$synchronous_stream_m2s_pipe_file" ||
   ! grep -Fqx '  assign push_ready = pop_ready || !pop_valid;' "$synchronous_stream_m2s_pipe_file" ||
   ! grep -Eq 'always[[:space:]]+@\([[:space:]]*posedge[[:space:]]+clk[[:space:]]*\)[[:space:]]+begin[[:space:]]*:[[:space:]]*p_m2s_pipe' "$synchronous_stream_m2s_pipe_file" ||
   [[ "$(grep -Ec 'always[[:space:]]+@\(' "$synchronous_stream_m2s_pipe_file")" != "1" ]] ||
   [[ "$(grep -Ec 'pop_valid[[:space:]]*<=' "$synchronous_stream_m2s_pipe_file")" != "2" ]] ||
   [[ "$(grep -Ec 'pop_data[[:space:]]*<=' "$synchronous_stream_m2s_pipe_file")" != "1" ]] ||
   grep -Eq 'always_comb|always_ff|always_latch|always[[:space:]]+@\*|negedge|initial[[:space:]]+begin|localparam|function|memory|occupancy|pointer|flush' "$synchronous_stream_m2s_pipe_file"; then
  echo "SynchronousStreamM2sPipe does not retain one exact ready/valid stage" >&2
  exit 1
fi

if ! python3 - "$synchronous_stream_m2s_pipe_file" <<'PY'
import pathlib
import sys

source = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
canonical_process = """  always @(posedge clk) begin : p_m2s_pipe
    if (reset == 1'b1) begin
      pop_valid <= 1'b0;
    end else if (push_ready == 1'b1) begin
      pop_valid <= push_valid;
    end
    if (push_ready == 1'b1) begin
      pop_data <= push_data;
    end
  end"""
if source.count(canonical_process) != 1:
    raise SystemExit("missing exact synchronous Stream m2s pipe process")
reset_body = source.split("if (reset == 1'b1) begin", 1)[1].split("end else if", 1)[0]
if "pop_data" in reset_body:
    raise SystemExit("m2s pipe reset illegally initializes payload")
PY
then
  echo "SynchronousStreamM2sPipe process is not canonical" >&2
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

# Verilator 5 split the legacy UNUSED and WIDTH warning families into the
# narrower UNUSEDSIGNAL, WIDTHEXPAND and WIDTHTRUNC names used below. The
# canonical MorphHDL container intentionally retains Verilator 4.228, so map
# only those reviewed suppressions back to their historical family names.
verilator_executable="$(command -v verilator)"
verilator_version="$($verilator_executable --version)"
verilator_major="$(
  sed -nE 's/^Verilator ([0-9]+)(\..*)?$/\1/p' <<<"$verilator_version"
)"
if [[ -z "$verilator_major" ]]; then
  echo "Cannot parse Verilator version: $verilator_version" >&2
  exit 1
fi

verilator() {
  local argument
  local -a translated=()
  for argument in "$@"; do
    if (( verilator_major < 5 )); then
      case "$argument" in
        -Wno-UNUSEDSIGNAL)
          argument=-Wno-UNUSED
          ;;
        -Wno-WIDTHEXPAND|-Wno-WIDTHTRUNC)
          argument=-Wno-WIDTH
          ;;
      esac
    fi
    translated+=("$argument")
  done
  "$verilator_executable" "${translated[@]}"
}

tmp_dir="$(mktemp -d /tmp/morphhdl-contracts.XXXXXX)"
cleanup() {
  rm -rf -- "$tmp_dir"
}
trap cleanup EXIT

# Yosys 0.33 and older do not consistently accept quoted filenames in `-p`
# command strings. Stable temporary names avoid depending on user/repository
# path spelling while preserving the exact artifact bytes.
cp "$parameterized_wire_file" "$tmp_dir/parameterized_wire.v"
cp "$symbolic_data_shapes_file" "$tmp_dir/symbolic_data_shapes.v"
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
cp "$parameterized_counter_file" "$tmp_dir/parameterized_counter.v"
cp "$simple_dual_port_memory_file" "$tmp_dir/simple_dual_port_memory.v"
cp "$synchronous_stream_fifo_file" "$tmp_dir/synchronous_stream_fifo.v"
cp "$synchronous_stream_m2s_pipe_file" "$tmp_dir/synchronous_stream_m2s_pipe.v"
yosys_parameterized_wire_file="$tmp_dir/parameterized_wire.v"
yosys_symbolic_data_shapes_file="$tmp_dir/symbolic_data_shapes.v"
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
yosys_parameterized_counter_file="$tmp_dir/parameterized_counter.v"
yosys_simple_dual_port_memory_file="$tmp_dir/simple_dual_port_memory.v"
yosys_synchronous_stream_fifo_file="$tmp_dir/synchronous_stream_fifo.v"
yosys_synchronous_stream_m2s_pipe_file="$tmp_dir/synchronous_stream_m2s_pipe.v"

echo "Verilator: $(verilator --version)"
echo "Icarus: $(iverilog -V 2>/dev/null | head -n 1)"
echo "Yosys: $(yosys -V)"

for helper_case in \
  "$yosys_derived_width_file:DerivedWidth" \
  "$yosys_single_port_memory_file:SinglePortMemory" \
  "$yosys_simple_dual_port_memory_file:SimpleDualPortMemory"
do
  helper_file="${helper_case%%:*}"
  helper_top="${helper_case##*:}"
  yosys -q -p \
    "read_verilog -noautowire $helper_file; hierarchy -check -top $helper_top; select -assert-none t:\$shr t:\$sshr t:\$shift t:\$shiftx t:\$add"
done
yosys -q -p \
  "read_verilog -noautowire $yosys_synchronous_stream_fifo_file; hierarchy -check -top SynchronousStreamFifo; proc; opt; select -assert-none t:\$shr t:\$sshr t:\$shift t:\$shiftx"
yosys -q -p \
  "read_verilog -noautowire $yosys_parameterized_counter_file; hierarchy -check -top ParameterizedCounter; proc; opt; select -assert-none t:\$shr t:\$sshr t:\$shift t:\$shiftx"
echo "Yosys constant-function logarithm helpers create no runtime shift cells"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  --top-module ParameterizedWire \
  "$parameterized_wire_file"

for wire_width in 1 13 64; do
  verilator --lint-only --language 1364-2001 -Wall \
    -Wno-DECLFILENAME \
    --top-module ParameterizedWire \
    -GWIDTH="$wire_width" \
    "$parameterized_wire_file"
done

for shape_width in 8 1 13 64; do
  shape_arguments=()
  if [[ "$shape_width" != "8" ]]; then
    shape_arguments=(-GWIDTH="$shape_width")
  fi
  verilator --lint-only --language 1364-2001 -Wall \
    -Wno-DECLFILENAME \
    --top-module SymbolicDataShapes \
    "${shape_arguments[@]}" \
    "$symbolic_data_shapes_file"
done

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
  --top-module DerivedWidth \
  -GDATA_WIDTH=2 -GLANES=2 \
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

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  -Wno-WIDTHEXPAND \
  --top-module SimpleDualPortMemory \
  "$simple_dual_port_memory_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  -Wno-WIDTHEXPAND \
  --top-module SimpleDualPortMemory \
  -GDEPTH=3 -GWIDTH=5 \
  "$simple_dual_port_memory_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  -Wno-WIDTHEXPAND \
  --top-module SimpleDualPortMemory \
  -GDEPTH=1 -GWIDTH=1 \
  "$simple_dual_port_memory_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  -Wno-WIDTHEXPAND \
  --top-module SimpleDualPortMemory \
  -GDEPTH=2 -GWIDTH=4 \
  "$simple_dual_port_memory_file"

verilator --lint-only --language 1364-2001 -Wall \
  -Wno-DECLFILENAME \
  -Wno-WIDTHEXPAND \
  --top-module SimpleDualPortMemory \
  -GDEPTH=8 -GWIDTH=4 \
  "$simple_dual_port_memory_file"

for fifo_shape in "5 8" "3 5" "1 1" "8 4"; do
  fifo_depth="${fifo_shape%% *}"
  fifo_width="${fifo_shape##* }"
  fifo_lint_extra=()
  if [[ "$fifo_depth" == "1" ]]; then
    fifo_lint_extra=(-Wno-CMPCONST)
  fi
  verilator --lint-only --language 1364-2001 -Wall \
    -Wno-DECLFILENAME \
    -Wno-WIDTHEXPAND \
    -Wno-WIDTHTRUNC \
    "${fifo_lint_extra[@]}" \
    --top-module SynchronousStreamFifo \
    -GDEPTH="$fifo_depth" -GWIDTH="$fifo_width" \
    "$synchronous_stream_fifo_file"
done

for pipe_width in 1 5 8 32; do
  verilator --lint-only --language 1364-2001 -Wall \
    -Wno-DECLFILENAME \
    --top-module SynchronousStreamM2sPipe \
    -GWIDTH="$pipe_width" \
    "$synchronous_stream_m2s_pipe_file"
done

for counter_limit in 1 2 3 5 8; do
  verilator --lint-only --language 1364-2001 -Wall \
    -Wno-DECLFILENAME \
    -Wno-WIDTHEXPAND \
    -Wno-WIDTHTRUNC \
    --top-module ParameterizedCounter \
    -GLIMIT="$counter_limit" \
    "$parameterized_counter_file"
done

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

iverilog -g2001 -Wall -s SymbolicDataShapesTb \
  -o "$tmp_dir/symbolic_data_shapes.vvp" \
  "$symbolic_data_shapes_file" \
  "$examples_dir/symbolic_data_shapes_tb.v"
symbolic_data_shapes_output="$(vvp "$tmp_dir/symbolic_data_shapes.vvp")"
echo "$symbolic_data_shapes_output"
if ! printf '%s\n' "$symbolic_data_shapes_output" | grep -q 'PASS: SymbolicDataShapes'; then
  echo "SymbolicDataShapes simulation did not report PASS" >&2
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

iverilog -g2001 -Wall -s ParameterizedCounterTb \
  -o "$tmp_dir/parameterized_counter.vvp" \
  "$parameterized_counter_file" \
  "$examples_dir/parameterized_counter_tb.v"
parameterized_counter_output="$(vvp "$tmp_dir/parameterized_counter.vvp")"
echo "$parameterized_counter_output"
if ! printf '%s\n' "$parameterized_counter_output" | grep -q 'PASS: ParameterizedCounter'; then
  echo "ParameterizedCounter simulation did not report PASS" >&2
  exit 1
fi

iverilog -g2001 -Wall -s SimpleDualPortMemoryTb \
  -o "$tmp_dir/simple_dual_port_memory.vvp" \
  "$simple_dual_port_memory_file" \
  "$examples_dir/simple_dual_port_memory_tb.v"
simple_dual_port_memory_output="$(vvp "$tmp_dir/simple_dual_port_memory.vvp")"
echo "$simple_dual_port_memory_output"
if ! printf '%s\n' "$simple_dual_port_memory_output" | grep -q 'PASS: SimpleDualPortMemory'; then
  echo "SimpleDualPortMemory simulation did not report PASS" >&2
  exit 1
fi

iverilog -g2001 -Wall -s SynchronousStreamFifoTb \
  -o "$tmp_dir/synchronous_stream_fifo.vvp" \
  "$synchronous_stream_fifo_file" \
  "$examples_dir/synchronous_stream_fifo_tb.v"
synchronous_stream_fifo_output="$(vvp "$tmp_dir/synchronous_stream_fifo.vvp")"
echo "$synchronous_stream_fifo_output"
if ! printf '%s\n' "$synchronous_stream_fifo_output" | grep -q 'PASS: SynchronousStreamFifo'; then
  echo "SynchronousStreamFifo simulation did not report PASS" >&2
  exit 1
fi

iverilog -g2001 -Wall -s SynchronousStreamM2sPipeTb \
  -o "$tmp_dir/synchronous_stream_m2s_pipe.vvp" \
  "$synchronous_stream_m2s_pipe_file" \
  "$examples_dir/synchronous_stream_m2s_pipe_tb.v"
synchronous_stream_m2s_pipe_output="$(vvp "$tmp_dir/synchronous_stream_m2s_pipe.vvp")"
echo "$synchronous_stream_m2s_pipe_output"
if ! printf '%s\n' "$synchronous_stream_m2s_pipe_output" | grep -q 'PASS: SynchronousStreamM2sPipe'; then
  echo "SynchronousStreamM2sPipe simulation did not report PASS" >&2
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
  "$yosys_parameterized_wire_file" ParameterizedWire minimum 1 \
  "chparam -set WIDTH 1 ParameterizedWire;"
yosys_synthesize_and_check \
  "$yosys_parameterized_wire_file" ParameterizedWire awkward 13 \
  "chparam -set WIDTH 13 ParameterizedWire;"
yosys_synthesize_and_check \
  "$yosys_parameterized_wire_file" ParameterizedWire maximum 64 \
  "chparam -set WIDTH 64 ParameterizedWire;"

yosys_parameterized_wire_fixed_port_mutation_must_fail() {
  local label="$1"
  local original="$2"
  local replacement="$3"
  local mutated_file="$tmp_dir/parameterized-wire-${label}.v"
  local netlist="$tmp_dir/ParameterizedWire-${label}.json"

  python3 - "$yosys_parameterized_wire_file" "$mutated_file" "$original" "$replacement" <<'PY'
import pathlib
import sys

source_path, output_path, original, replacement = sys.argv[1:]
source = pathlib.Path(source_path).read_text(encoding="utf-8")
if source.count(original) != 1:
    raise SystemExit("ParameterizedWire mutation source did not match exactly once")
pathlib.Path(output_path).write_text(source.replace(original, replacement), encoding="utf-8")
PY

  if ! yosys -q -p \
    "read_verilog -noautowire $mutated_file; chparam -set WIDTH 64 ParameterizedWire; hierarchy -check -top ParameterizedWire; proc; check -assert; synth -top ParameterizedWire; check -assert; write_json $netlist"; then
    echo "ParameterizedWire $label mutation did not reach the width-64 ABI checker" >&2
    exit 1
  fi
  if python3 "$repo_root/morphhdl/scripts/check-yosys-port-widths.py" \
    "$netlist" ParameterizedWire \
    --port "din:input:64" \
    --port "dout:output:64"; then
    echo "ParameterizedWire width-64 ABI gate accepted $label mutation" >&2
    exit 1
  fi
}

yosys_parameterized_wire_fixed_port_mutation_must_fail \
  fixed-input \
  'input  wire [WIDTH-1:0] din' \
  'input  wire [7:0] din'
yosys_parameterized_wire_fixed_port_mutation_must_fail \
  fixed-output \
  'output wire [WIDTH-1:0] dout' \
  'output wire [7:0] dout'

yosys_symbolic_data_shapes_synthesize_and_check() {
  local label="$1"
  local expected_width="$2"
  local parameter_command="$3"
  local process_netlist="$tmp_dir/SymbolicDataShapes-${label}-process.json"
  local synth_netlist="$tmp_dir/SymbolicDataShapes-${label}-synth.json"
  local port_args=()
  local name

  yosys -q -p \
    "read_verilog -noautowire $yosys_symbolic_data_shapes_file; $parameter_command hierarchy -check -top SymbolicDataShapes; proc; check -assert; write_json $process_netlist"
  python3 "$repo_root/morphhdl/scripts/check-yosys-symbolic-data-shapes-contract.py" \
    "$process_netlist" --width "$expected_width"

  yosys -q -p \
    "read_verilog -noautowire $yosys_symbolic_data_shapes_file; $parameter_command hierarchy -check -top SymbolicDataShapes; proc; check -assert; synth -top SymbolicDataShapes; check -assert; write_json $synth_netlist"
  for name in \
    bits_in \
    bundle_in_bits bundle_in_sint bundle_in_uint \
    flow_in_payload_bits flow_in_payload_sint flow_in_payload_uint \
    sint_in \
    stream_in_payload_bits stream_in_payload_sint stream_in_payload_uint \
    uint_in
  do
    port_args+=(--port "$name:input:$expected_width")
  done
  port_args+=(--port "vec_in:input:$((6 * expected_width))")
  for name in \
    bits_out \
    bundle_out_bits bundle_out_sint bundle_out_uint \
    flow_out_payload_bits flow_out_payload_sint flow_out_payload_uint \
    register_out_bits register_out_sint register_out_uint \
    sint_out \
    stream_out_payload_bits stream_out_payload_sint stream_out_payload_uint \
    uint_out
  do
    port_args+=(--port "$name:output:$expected_width")
  done
  port_args+=(--port "vec_out:output:$((6 * expected_width))")
  for name in clk flow_in_valid stream_in_valid stream_out_ready; do
    port_args+=(--port "$name:input:1")
  done
  for name in flow_out_valid stream_in_ready stream_out_valid; do
    port_args+=(--port "$name:output:1")
  done
  python3 "$repo_root/morphhdl/scripts/check-yosys-port-widths.py" \
    "$synth_netlist" SymbolicDataShapes "${port_args[@]}"
}

yosys_symbolic_data_shapes_synthesize_and_check default 8 ""
yosys_symbolic_data_shapes_synthesize_and_check minimum 1 \
  "chparam -set WIDTH 1 SymbolicDataShapes;"
yosys_symbolic_data_shapes_synthesize_and_check awkward 13 \
  "chparam -set WIDTH 13 SymbolicDataShapes;"
yosys_symbolic_data_shapes_synthesize_and_check maximum 64 \
  "chparam -set WIDTH 64 SymbolicDataShapes;"

yosys_symbolic_data_shapes_width_mutation_must_fail() {
  local label="$1"
  local original="$2"
  local replacement="$3"
  local mutated_file="$tmp_dir/symbolic-data-shapes-${label}.v"
  local netlist="$tmp_dir/SymbolicDataShapes-${label}.json"

  python3 - "$yosys_symbolic_data_shapes_file" "$mutated_file" "$original" "$replacement" <<'PY'
import pathlib
import sys

source_path, output_path, original, replacement = sys.argv[1:]
source = pathlib.Path(source_path).read_text(encoding="utf-8")
if source.count(original) != 1:
    raise SystemExit("SymbolicDataShapes mutation source did not match exactly once")
pathlib.Path(output_path).write_text(source.replace(original, replacement), encoding="utf-8")
PY

  if ! yosys -q -p \
    "read_verilog -noautowire $mutated_file; chparam -set WIDTH 64 SymbolicDataShapes; hierarchy -check -top SymbolicDataShapes; proc; write_json $netlist"; then
    echo "SymbolicDataShapes $label mutation did not reach the width-64 structural checker" >&2
    exit 1
  fi
  if python3 "$repo_root/morphhdl/scripts/check-yosys-symbolic-data-shapes-contract.py" \
      "$netlist" --width 64; then
    echo "SymbolicDataShapes width-64 gate accepted $label mutation" >&2
    exit 1
  fi
  echo "Yosys SymbolicDataShapes checker rejected forbidden mutation: $label"
}

yosys_symbolic_data_shapes_width_mutation_must_fail \
  fixed-packed-input \
  'input  wire [WIDTH-1:0] bits_in,' \
  'input  wire [7:0] bits_in,'
yosys_symbolic_data_shapes_width_mutation_must_fail \
  fixed-packed-vec-input \
  'input wire [((WIDTH + WIDTH + WIDTH) * 2)-1:0] vec_in,' \
  'input wire [47:0] vec_in,'
yosys_symbolic_data_shapes_width_mutation_must_fail \
  fixed-register-leaf \
  'reg        [WIDTH-1:0] payload_register_sint;' \
  'reg        [7:0] payload_register_sint;'
yosys_symbolic_data_shapes_width_mutation_must_fail \
  falling-register-clock \
  'always @(posedge clk) begin' \
  'always @(negedge clk) begin'

yosys_symbolic_data_shapes_extra_cell_mutation_must_fail() {
  local canonical_netlist="$tmp_dir/SymbolicDataShapes-default-process.json"
  local mutated_netlist="$tmp_dir/SymbolicDataShapes-extra-cell.json"

  python3 - "$canonical_netlist" "$mutated_netlist" <<'PY'
import copy
import json
import pathlib
import sys

source_path = pathlib.Path(sys.argv[1])
target_path = pathlib.Path(sys.argv[2])
netlist = json.loads(source_path.read_text(encoding="utf-8"))
module = netlist.get("modules", {}).get("SymbolicDataShapes")
if module is None:
    raise SystemExit("canonical SymbolicDataShapes netlist is missing")
cells = module.get("cells", {})
if len(cells) != 3:
    raise SystemExit("canonical SymbolicDataShapes netlist does not have three cells")
extra_name = "$morphhdl_extra_dff"
if extra_name in cells:
    raise SystemExit("extra-cell mutation name already exists")
cells[extra_name] = copy.deepcopy(next(iter(cells.values())))
target_path.write_text(json.dumps(netlist, sort_keys=True), encoding="utf-8")
PY

  if python3 "$repo_root/morphhdl/scripts/check-yosys-symbolic-data-shapes-contract.py" \
      "$mutated_netlist" --width 8; then
    echo "SymbolicDataShapes checker accepted a surplus storage cell" >&2
    exit 1
  fi
  echo "Yosys SymbolicDataShapes checker rejected forbidden mutation: extra-cell"
}

yosys_symbolic_data_shapes_extra_cell_mutation_must_fail

yosys_synthesize_and_check \
  "$yosys_derived_width_file" DerivedWidth default 37 ""
yosys_synthesize_and_check \
  "$yosys_derived_width_file" DerivedWidth minimum 4 \
  "chparam -set DATA_WIDTH 1 -set LANES 1 DerivedWidth;"
yosys_synthesize_and_check \
  "$yosys_derived_width_file" DerivedWidth awkward 20 \
  "chparam -set DATA_WIDTH 5 -set LANES 3 DerivedWidth;"
yosys_synthesize_and_check \
  "$yosys_derived_width_file" DerivedWidth lanes-only 29 \
  "chparam -set LANES 3 DerivedWidth;"
yosys_synthesize_and_check \
  "$yosys_derived_width_file" DerivedWidth data-width-only 25 \
  "chparam -set DATA_WIDTH 5 DerivedWidth;"
yosys_synthesize_and_check \
  "$yosys_derived_width_file" DerivedWidth dynamic-minimum 7 \
  "chparam -set DATA_WIDTH 2 -set LANES 2 DerivedWidth;"

expect_derived_width_mutation_rejected() {
  local label="$1"
  local expected_width="$2"
  local parameter_command="$3"
  local original="$4"
  local replacement="$5"
  local mutated_source="$tmp_dir/derived-width-${label}.v"
  local mutated_netlist="$tmp_dir/derived-width-${label}.json"

  python3 - "$yosys_derived_width_file" "$mutated_source" "$original" "$replacement" <<'PY'
import pathlib
import sys

source_path, target_path, original, replacement = sys.argv[1:]
source = pathlib.Path(source_path).read_text(encoding="utf-8")
if source.count(original) != 1:
    raise SystemExit("mutation source is not unique: {}".format(original))
pathlib.Path(target_path).write_text(source.replace(original, replacement), encoding="utf-8")
PY

  if {
    yosys -q -p \
      "read_verilog -noautowire $mutated_source; $parameter_command hierarchy -check -top DerivedWidth; proc; check -assert; synth -top DerivedWidth; check -assert; write_json $mutated_netlist" &&
    python3 "$repo_root/morphhdl/scripts/check-yosys-port-widths.py" \
      "$mutated_netlist" DerivedWidth \
      --port "din:input:$expected_width" \
      --port "dout:output:$expected_width"
  } >/dev/null 2>&1; then
    echo "DerivedWidth mutation unexpectedly retained the contract: $label" >&2
    exit 1
  fi
}

min_local='  localparam integer CLAMPED_PADDING = (DATA_WIDTH < 3) ? DATA_WIDTH : 3;'
max_local='  localparam integer PADDED_WIDTH = ((TOTAL_WIDTH + CLAMPED_PADDING > 4) ? TOTAL_WIDTH + CLAMPED_PADDING : 4) + LANE_INDEX_WIDTH;'

expect_derived_width_mutation_rejected min-comparator 37 "" \
  "$min_local" \
  '  localparam integer CLAMPED_PADDING = (DATA_WIDTH > 3) ? DATA_WIDTH : 3;'
expect_derived_width_mutation_rejected min-branches 37 "" \
  "$min_local" \
  '  localparam integer CLAMPED_PADDING = (DATA_WIDTH < 3) ? 3 : DATA_WIDTH;'
expect_derived_width_mutation_rejected min-specialized 7 \
  "chparam -set DATA_WIDTH 2 -set LANES 2 DerivedWidth;" \
  "$min_local" \
  '  localparam integer CLAMPED_PADDING = 3;'
expect_derived_width_mutation_rejected max-comparator 37 "" \
  "$max_local" \
  '  localparam integer PADDED_WIDTH = ((TOTAL_WIDTH + CLAMPED_PADDING < 4) ? TOTAL_WIDTH + CLAMPED_PADDING : 4) + LANE_INDEX_WIDTH;'
expect_derived_width_mutation_rejected max-branches 37 "" \
  "$max_local" \
  '  localparam integer PADDED_WIDTH = ((TOTAL_WIDTH + CLAMPED_PADDING > 4) ? 4 : TOTAL_WIDTH + CLAMPED_PADDING) + LANE_INDEX_WIDTH;'
expect_derived_width_mutation_rejected max-specialized 4 \
  "chparam -set DATA_WIDTH 1 -set LANES 1 DerivedWidth;" \
  "$max_local" \
  '  localparam integer PADDED_WIDTH = 37;'

expect_derived_width_mutation_rejected ceil-helper-initialization 37 "" \
  '      clog2 = 0;' \
  '      clog2 = 1;'
expect_derived_width_mutation_rejected ceil-helper-input-declaration 37 "" \
  '    input integer value;' \
  '    input [0:0] value;'
expect_derived_width_mutation_rejected ceil-helper-loop-declaration 37 "" \
  '    integer remaining;' \
  '    reg [0:0] remaining;'
expect_derived_width_mutation_rejected ceil-helper-decrement 37 "" \
  '      for (remaining = value - 1; remaining > 0; remaining = remaining >> 1) begin' \
  '      for (remaining = value; remaining > 0; remaining = remaining >> 1) begin'
expect_derived_width_mutation_rejected ceil-helper-shift 37 "" \
  '      for (remaining = value - 1; remaining > 0; remaining = remaining >> 1) begin' \
  '      for (remaining = value - 1; remaining > 0; remaining = remaining >> 2) begin'
expect_derived_width_mutation_rejected ceil-helper-increment 37 "" \
  '        clog2 = clog2 + 1;' \
  '        clog2 = clog2 + 2;'
expect_derived_width_mutation_rejected ceil-helper-clamp-comparator 37 "" \
  '      if (clog2 < minimum_result) begin' \
  '      if (clog2 > minimum_result) begin'
expect_derived_width_mutation_rejected ceil-zero-boundary 4 \
  "chparam -set DATA_WIDTH 1 -set LANES 1 DerivedWidth;" \
  'clog2(LANES, 0)' \
  'clog2(LANES, 1)'
expect_derived_width_mutation_rejected ceil-default-specialization 7 \
  "chparam -set DATA_WIDTH 2 -set LANES 2 DerivedWidth;" \
  'clog2(LANES, 0)' \
  '2'

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

yosys_parameterized_counter_synthesize_and_check() {
  local label="$1"
  local expected_limit="$2"
  local parameter_command="$3"
  local expected_width=1
  local capacity=2
  local process_netlist="$tmp_dir/ParameterizedCounter-${label}-process.json"
  local synthesized_netlist="$tmp_dir/ParameterizedCounter-${label}-synthesized.json"

  while (( capacity < expected_limit )); do
    expected_width=$((expected_width + 1))
    capacity=$((capacity * 2))
  done

  yosys -q -p \
    "read_verilog -noautowire $yosys_parameterized_counter_file; $parameter_command hierarchy -check -top ParameterizedCounter; proc; opt_dff; opt_clean; check -assert; write_json $process_netlist; synth -top ParameterizedCounter; check -assert; write_json $synthesized_netlist"
  python3 "$repo_root/morphhdl/scripts/check-yosys-parameterized-counter-contract.py" \
    "$process_netlist" --limit "$expected_limit"
  python3 "$repo_root/morphhdl/scripts/check-yosys-port-widths.py" \
    "$synthesized_netlist" ParameterizedCounter \
    --port "clk:input:1" \
    --port "count:output:$expected_width" \
    --port "enable:input:1" \
    --port "reset:input:1"
}

yosys_parameterized_counter_synthesize_and_check \
  default 5 ""
yosys_parameterized_counter_synthesize_and_check \
  limit-three 3 "chparam -set LIMIT 3 ParameterizedCounter;"
yosys_parameterized_counter_synthesize_and_check \
  limit-eight 8 "chparam -set LIMIT 8 ParameterizedCounter;"

yosys_parameterized_counter_mutation_must_fail() {
  local label="$1"
  local sed_expression="$2"
  local mutated_file="$tmp_dir/parameterized-counter-${label}.v"
  local mutated_netlist="$tmp_dir/ParameterizedCounter-${label}-mutated.json"

  sed "$sed_expression" "$yosys_parameterized_counter_file" > "$mutated_file"
  if cmp -s "$yosys_parameterized_counter_file" "$mutated_file"; then
    echo "ParameterizedCounter mutation did not change the fixture: $label" >&2
    exit 1
  fi

  if ! yosys -q -p \
      "read_verilog -noautowire $mutated_file; hierarchy -check -top ParameterizedCounter; proc; opt_dff; opt_clean; check -assert; write_json $mutated_netlist"; then
    echo "Yosys rejected forbidden ParameterizedCounter mutation during synthesis: $label"
    return
  fi
  if python3 "$repo_root/morphhdl/scripts/check-yosys-parameterized-counter-contract.py" \
      "$mutated_netlist" --limit 5; then
    echo "ParameterizedCounter checker accepted forbidden mutation: $label" >&2
    exit 1
  fi
  echo "Yosys ParameterizedCounter rejected forbidden mutation: $label"
}

yosys_parameterized_counter_mutation_must_fail \
  off-by-one-terminal 's/count == LIMIT - 1/count == LIMIT/'
yosys_parameterized_counter_mutation_must_fail \
  decrement "s/count + 1'b1/count - 1'b1/"
yosys_parameterized_counter_mutation_must_fail \
  active-low-enable "s/enable == 1'b1/enable == 1'b0/"
yosys_parameterized_counter_mutation_must_fail \
  enable-before-reset 's/reset/__morph_swap__/g;s/enable/reset/g;s/__morph_swap__/enable/g'
yosys_parameterized_counter_mutation_must_fail \
  falling-edge-clock 's/posedge clk/negedge clk/'
yosys_parameterized_counter_mutation_must_fail \
  reset-to-ones "s/{clog2(LIMIT, 1){1'b0}}/{clog2(LIMIT, 1){1'b1}}/"

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

expect_single_port_memory_address_mutation_rejected() {
  local label="$1"
  local depth="$2"
  local expected_width="$3"
  local original="$4"
  local replacement="$5"
  local mutated_file="$tmp_dir/single-port-memory-address-${label}.v"
  local mutated_netlist="$tmp_dir/SinglePortMemory-address-${label}.json"

  python3 - "$yosys_single_port_memory_file" "$mutated_file" "$original" "$replacement" <<'PY'
import pathlib
import sys

source_path, target_path, original, replacement = sys.argv[1:]
source = pathlib.Path(source_path).read_text(encoding="utf-8")
if source.count(original) != 1:
    raise SystemExit("address mutation source is not unique: {}".format(original))
pathlib.Path(target_path).write_text(source.replace(original, replacement), encoding="utf-8")
PY

  if {
    yosys -q -p \
      "read_verilog -noautowire $mutated_file; chparam -set DEPTH $depth SinglePortMemory; hierarchy -check -top SinglePortMemory; proc; check -assert; synth -top SinglePortMemory; check -assert; write_json $mutated_netlist" &&
    python3 "$repo_root/morphhdl/scripts/check-yosys-port-widths.py" \
      "$mutated_netlist" SinglePortMemory \
      --port "address:input:$expected_width"
  } >/dev/null 2>&1; then
    echo "SinglePortMemory address mutation unexpectedly retained the contract: $label" >&2
    exit 1
  fi
}

expect_single_port_memory_address_mutation_rejected address-minimum-zero 1 1 \
  'clog2(DEPTH, 1)' \
  'clog2(DEPTH, 0)'
expect_single_port_memory_address_mutation_rejected address-minimum-two 1 1 \
  'clog2(DEPTH, 1)' \
  'clog2(DEPTH, 2)'
expect_single_port_memory_address_mutation_rejected address-specialized-default 3 2 \
  'clog2(DEPTH, 1)' \
  '3'
expect_single_port_memory_address_mutation_rejected address-clamp-assignment 1 1 \
  '        clog2 = minimum_result;' \
  '        clog2 = 0;'

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

yosys_simple_dual_port_memory_synthesize_and_check() {
  local label="$1"
  local expected_width="$2"
  local expected_depth="$3"
  local parameter_command="$4"
  local expected_address_width=1
  local address_capacity=2
  local process_netlist="$tmp_dir/SimpleDualPortMemory-${label}-process.json"
  local synthesized_netlist="$tmp_dir/SimpleDualPortMemory-${label}-synthesized.json"

  while (( address_capacity < expected_depth )); do
    expected_address_width=$((expected_address_width + 1))
    address_capacity=$((address_capacity * 2))
  done

  yosys -q -p \
    "read_verilog -noautowire $yosys_simple_dual_port_memory_file; $parameter_command hierarchy -check -top SimpleDualPortMemory; proc; opt_reduce; opt_expr -mux_undef; memory_dff; memory_collect; opt_clean; check -assert; write_json $process_netlist; synth -top SimpleDualPortMemory; check -assert; write_json $synthesized_netlist"
  python3 "$repo_root/morphhdl/scripts/check-yosys-simple-dual-port-memory-contract.py" \
    "$process_netlist" --width "$expected_width" --depth "$expected_depth"
  python3 "$repo_root/morphhdl/scripts/check-yosys-port-widths.py" \
    "$synthesized_netlist" SimpleDualPortMemory \
    --port "clk:input:1" \
    --port "read_address:input:$expected_address_width" \
    --port "read_data:output:$expected_width" \
    --port "read_enable:input:1" \
    --port "write_address:input:$expected_address_width" \
    --port "write_data:input:$expected_width" \
    --port "write_enable:input:1"
}

yosys_simple_dual_port_memory_synthesize_and_check \
  default 8 5 ""
yosys_simple_dual_port_memory_synthesize_and_check \
  awkward 5 3 "chparam -set DEPTH 3 -set WIDTH 5 SimpleDualPortMemory;"
yosys_simple_dual_port_memory_synthesize_and_check \
  minimum 1 1 "chparam -set DEPTH 1 -set WIDTH 1 SimpleDualPortMemory;"
yosys_simple_dual_port_memory_synthesize_and_check \
  depth-two 4 2 "chparam -set DEPTH 2 -set WIDTH 4 SimpleDualPortMemory;"
yosys_simple_dual_port_memory_synthesize_and_check \
  power-eight 4 8 "chparam -set DEPTH 8 -set WIDTH 4 SimpleDualPortMemory;"

yosys_simple_dual_port_memory_mutation_must_fail() {
  local label="$1"
  local original="$2"
  local replacement="$3"
  local expected_count="$4"
  local mutated_file="$tmp_dir/simple-dual-port-memory-${label}.v"
  local mutated_netlist="$tmp_dir/SimpleDualPortMemory-${label}-mutated.json"

  python3 - "$yosys_simple_dual_port_memory_file" "$mutated_file" \
      "$original" "$replacement" "$expected_count" <<'PY'
import pathlib
import sys

source_path, target_path, original, replacement, expected_count = sys.argv[1:]
source = pathlib.Path(source_path).read_text(encoding="utf-8")
expected = int(expected_count)
if source.count(original) != expected:
    raise SystemExit(
        "mutation source count for {!r} is {}, expected {}".format(
            original, source.count(original), expected
        )
    )
pathlib.Path(target_path).write_text(source.replace(original, replacement), encoding="utf-8")
PY

  if cmp -s "$yosys_simple_dual_port_memory_file" "$mutated_file"; then
    echo "SimpleDualPortMemory mutation did not change the fixture: $label" >&2
    exit 1
  fi
  if ! yosys -q -p \
      "read_verilog -noautowire $mutated_file; hierarchy -check -top SimpleDualPortMemory; proc; opt_reduce; opt_expr -mux_undef; memory_dff; memory_collect; opt_clean; check -assert; write_json $mutated_netlist"; then
    echo "Yosys rejected forbidden SimpleDualPortMemory mutation during synthesis: $label"
    return
  fi
  if python3 "$repo_root/morphhdl/scripts/check-yosys-simple-dual-port-memory-contract.py" \
      "$mutated_netlist" --width 8 --depth 5; then
    echo "SimpleDualPortMemory checker accepted forbidden mutation: $label" >&2
    exit 1
  fi
  echo "Yosys SimpleDualPortMemory rejected forbidden mutation: $label"
}

yosys_simple_dual_port_memory_mutation_must_fail \
  write-first-bypass \
  'read_data <= memory[read_address];' \
  "read_data <= write_enable == 1'b1 && write_address == read_address ? write_data : memory[read_address];" \
  1
yosys_simple_dual_port_memory_mutation_must_fail \
  read-from-write-address \
  'read_data <= memory[read_address];' \
  'read_data <= memory[write_address];' \
  1
yosys_simple_dual_port_memory_mutation_must_fail \
  write-to-read-address \
  'memory[write_address] <= write_data;' \
  'memory[read_address] <= write_data;' \
  1
yosys_simple_dual_port_memory_mutation_must_fail \
  read-requires-write-enable \
  "read_enable == 1'b1" \
  "read_enable == 1'b1 && write_enable == 1'b1" \
  2
yosys_simple_dual_port_memory_mutation_must_fail \
  write-requires-read-enable \
  "write_enable == 1'b1" \
  "read_enable == 1'b1 && write_enable == 1'b1" \
  1
yosys_simple_dual_port_memory_mutation_must_fail \
  active-low-read-enable \
  "read_enable == 1'b1" \
  "read_enable == 1'b0" \
  2
yosys_simple_dual_port_memory_mutation_must_fail \
  active-low-write-enable \
  "write_enable == 1'b1" \
  "write_enable == 1'b0" \
  1
yosys_simple_dual_port_memory_mutation_must_fail \
  unconditional-read \
  "read_enable == 1'b1" \
  "1'b1 == 1'b1" \
  2
yosys_simple_dual_port_memory_mutation_must_fail \
  unconditional-write \
  "write_enable == 1'b1" \
  "1'b1 == 1'b1" \
  1
yosys_simple_dual_port_memory_mutation_must_fail \
  falling-edge-clock \
  'posedge clk' \
  'negedge clk' \
  1
yosys_simple_dual_port_memory_mutation_must_fail \
  nonzero-surplus-read \
  "read_data <= {WIDTH{1'b0}};" \
  'read_data <= write_data;' \
  1
yosys_simple_dual_port_memory_mutation_must_fail \
  inverted-read-guard \
  'read_address < DEPTH' \
  'read_address >= DEPTH' \
  1
yosys_simple_dual_port_memory_mutation_must_fail \
  inverted-write-guard \
  'write_address < DEPTH' \
  'write_address >= DEPTH' \
  1
yosys_simple_dual_port_memory_mutation_must_fail \
  off-by-one-read-guard \
  'read_address < DEPTH' \
  'read_address <= DEPTH' \
  1
yosys_simple_dual_port_memory_mutation_must_fail \
  off-by-one-write-guard \
  'write_address < DEPTH' \
  'write_address <= DEPTH' \
  1
yosys_simple_dual_port_memory_mutation_must_fail \
  signed-read-guard \
  'read_address < DEPTH' \
  '$signed(read_address) < DEPTH' \
  1
yosys_simple_dual_port_memory_mutation_must_fail \
  signed-write-guard \
  'write_address < DEPTH' \
  '$signed(write_address) < DEPTH' \
  1
yosys_simple_dual_port_memory_mutation_must_fail \
  initialized-memory \
  '  always @(posedge clk) begin : p_memory' \
  $'  initial memory[0] = {WIDTH{1\x27b0}};\n\n  always @(posedge clk) begin : p_memory' \
  1
yosys_simple_dual_port_memory_mutation_must_fail \
  extra-memory-word \
  '[0:DEPTH-1]' \
  '[0:DEPTH]' \
  1

yosys_simple_dual_port_memory_json_mutation_must_fail() {
  local label="$1"
  local mutation="$2"
  local canonical_netlist="$tmp_dir/SimpleDualPortMemory-default-process.json"
  local mutated_netlist="$tmp_dir/SimpleDualPortMemory-${label}-json-mutated.json"

  python3 - "$canonical_netlist" "$mutated_netlist" "$mutation" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
mutation = sys.argv[3]
netlist = json.loads(source.read_text(encoding="utf-8"))
top = netlist.get("modules", {}).get("SimpleDualPortMemory")
if top is None:
    raise SystemExit("canonical JSON is missing SimpleDualPortMemory")
ports = top.get("ports", {})
memories = [
    cell for cell in top.get("cells", {}).values() if cell.get("type") == "$mem_v2"
]
if len(memories) != 1:
    raise SystemExit("canonical JSON does not contain exactly one $mem_v2")
memory = memories[0]
parameters = memory.get("parameters", {})
connections = memory.get("connections", {})

def set_nonzero_parameter(name):
    if name not in parameters:
        raise SystemExit("canonical memory is missing parameter " + name)
    value = parameters[name]
    if isinstance(value, int):
        if value != 0:
            raise SystemExit("canonical parameter is not zero: " + repr(value))
        parameters[name] = 1
    elif isinstance(value, str) and value and set(value) <= {"0", "1"}:
        if int(value, 2) != 0:
            raise SystemExit("canonical parameter is not zero: " + repr(value))
        parameters[name] = value[:-1] + "1"
    else:
        raise SystemExit("unsupported zero parameter encoding: " + repr(value))

def set_one_to_two_parameter(name):
    if name not in parameters:
        raise SystemExit("canonical memory is missing parameter " + name)
    value = parameters[name]
    if isinstance(value, int):
        if value != 1:
            raise SystemExit("canonical parameter is not one: " + repr(value))
        parameters[name] = 2
    elif isinstance(value, str) and value and set(value) <= {"0", "1"}:
        if int(value, 2) != 1:
            raise SystemExit("canonical parameter is not one: " + repr(value))
        replacement = format(2, "0" + str(len(value)) + "b")
        if len(replacement) != len(value):
            raise SystemExit("canonical parameter is too narrow for two: " + repr(value))
        parameters[name] = replacement
    else:
        raise SystemExit("unsupported one parameter encoding: " + repr(value))

def comparison_for(port_name):
    bits = ports.get(port_name, {}).get("bits", [])
    matches = [
        cell
        for cell in top.get("cells", {}).values()
        if cell.get("type") == "$lt"
        and cell.get("connections", {}).get("A") == bits
    ]
    if len(matches) != 1:
        raise SystemExit("canonical JSON lacks one comparator for " + port_name)
    return matches[0]

if mutation.startswith("parameter:"):
    set_nonzero_parameter(mutation.split(":", 1)[1])
elif mutation.startswith("port-count:"):
    set_one_to_two_parameter(mutation.split(":", 1)[1])
elif mutation == "empty-init":
    parameters["INIT"] = ""
elif mutation == "truncated-init":
    value = parameters.get("INIT")
    if not isinstance(value, str) or not value:
        raise SystemExit("canonical INIT is missing")
    parameters["INIT"] = value[:-1]
elif mutation == "initialized-memory":
    value = parameters.get("INIT")
    if not isinstance(value, str) or not value:
        raise SystemExit("canonical INIT is missing")
    parameters["INIT"] = "0" + value[1:]
elif mutation == "collapse-read-address":
    connections["RD_ADDR"] = list(connections["WR_ADDR"])
elif mutation == "collapse-write-address":
    connections["WR_ADDR"] = list(connections["RD_ADDR"])
elif mutation == "swap-addresses":
    connections["RD_ADDR"], connections["WR_ADDR"] = (
        list(connections["WR_ADDR"]),
        list(connections["RD_ADDR"]),
    )
elif mutation == "write-enable-from-read":
    read_enable = ports.get("read_enable", {}).get("bits", [])
    if len(read_enable) != 1:
        raise SystemExit("canonical read_enable is not one bit")
    connections["WR_EN"] = read_enable * 8
elif mutation == "partial-write-enable":
    write_enable = connections.get("WR_EN", [])
    if len(write_enable) != 8 or len(set(write_enable)) != 1:
        raise SystemExit("canonical WR_EN is not one replicated whole-word enable")
    connections["WR_EN"] = ["0"] + list(write_enable[1:])
elif mutation == "empty-read-enable":
    connections["RD_EN"] = []
elif mutation == "active-read-reset":
    connections["RD_ARST"] = list(ports.get("clk", {}).get("bits", []))
elif mutation in {
    "short-read-comparator",
    "short-write-comparator",
    "signed-read-comparator",
    "signed-write-comparator",
}:
    role = "read_address" if "read" in mutation else "write_address"
    comparison = comparison_for(role)
    if mutation.startswith("short-"):
        value = comparison.get("connections", {}).get("B", [])
        if len(value) != 32:
            raise SystemExit("canonical comparator B is not 32 bits")
        comparison["connections"]["B"] = value[:-1]
    else:
        value = comparison.get("parameters", {}).get("B_SIGNED")
        if isinstance(value, int):
            if value != 0:
                raise SystemExit("canonical comparator B_SIGNED is not zero")
            comparison["parameters"]["B_SIGNED"] = 1
        elif isinstance(value, str) and value and set(value) <= {"0", "1"}:
            if int(value, 2) != 0:
                raise SystemExit("canonical comparator B_SIGNED is not zero")
            comparison["parameters"]["B_SIGNED"] = value[:-1] + "1"
        else:
            raise SystemExit("unsupported B_SIGNED encoding: " + repr(value))
else:
    raise SystemExit("unknown JSON mutation: " + mutation)

destination.write_text(json.dumps(netlist, indent=2) + "\n", encoding="utf-8")
PY

  if cmp -s "$canonical_netlist" "$mutated_netlist"; then
    echo "SimpleDualPortMemory JSON mutation did not change the netlist: $label" >&2
    exit 1
  fi
  if python3 "$repo_root/morphhdl/scripts/check-yosys-simple-dual-port-memory-contract.py" \
      "$mutated_netlist" --width 8 --depth 5; then
    echo "SimpleDualPortMemory checker accepted forbidden JSON mutation: $label" >&2
    exit 1
  fi
  echo "Yosys SimpleDualPortMemory checker rejected forbidden JSON mutation: $label"
}

yosys_simple_dual_port_memory_json_mutation_must_fail \
  transparent-read parameter:RD_TRANSPARENCY_MASK
yosys_simple_dual_port_memory_json_mutation_must_fail \
  collision-x-read parameter:RD_COLLISION_X_MASK
yosys_simple_dual_port_memory_json_mutation_must_fail \
  write-port-priority parameter:WR_PRIORITY_MASK
yosys_simple_dual_port_memory_json_mutation_must_fail \
  synchronous-raw-read parameter:RD_CLK_ENABLE
yosys_simple_dual_port_memory_json_mutation_must_fail \
  wide-read-continuation parameter:RD_WIDE_CONTINUATION
yosys_simple_dual_port_memory_json_mutation_must_fail \
  wide-write-continuation parameter:WR_WIDE_CONTINUATION
yosys_simple_dual_port_memory_json_mutation_must_fail \
  extra-read-port port-count:RD_PORTS
yosys_simple_dual_port_memory_json_mutation_must_fail \
  extra-write-port port-count:WR_PORTS
yosys_simple_dual_port_memory_json_mutation_must_fail empty-init empty-init
yosys_simple_dual_port_memory_json_mutation_must_fail truncated-init truncated-init
yosys_simple_dual_port_memory_json_mutation_must_fail initialized-json initialized-memory
yosys_simple_dual_port_memory_json_mutation_must_fail \
  collapsed-read-address collapse-read-address
yosys_simple_dual_port_memory_json_mutation_must_fail \
  collapsed-write-address collapse-write-address
yosys_simple_dual_port_memory_json_mutation_must_fail swapped-addresses swap-addresses
yosys_simple_dual_port_memory_json_mutation_must_fail \
  write-enable-contaminated write-enable-from-read
yosys_simple_dual_port_memory_json_mutation_must_fail \
  partial-write-enable partial-write-enable
yosys_simple_dual_port_memory_json_mutation_must_fail empty-read-enable empty-read-enable
yosys_simple_dual_port_memory_json_mutation_must_fail active-read-reset active-read-reset
yosys_simple_dual_port_memory_json_mutation_must_fail \
  short-read-comparator short-read-comparator
yosys_simple_dual_port_memory_json_mutation_must_fail \
  short-write-comparator short-write-comparator
yosys_simple_dual_port_memory_json_mutation_must_fail \
  signed-read-comparator signed-read-comparator
yosys_simple_dual_port_memory_json_mutation_must_fail \
  signed-write-comparator signed-write-comparator

yosys_simple_dual_port_memory_full_domain_json_mutation_must_fail() {
  local label="$1"
  local mutation="$2"
  local canonical_netlist="$tmp_dir/SimpleDualPortMemory-power-eight-process.json"
  local mutated_netlist="$tmp_dir/SimpleDualPortMemory-full-domain-${label}-json-mutated.json"

  python3 - "$canonical_netlist" "$mutated_netlist" "$mutation" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
mutation = sys.argv[3]
netlist = json.loads(source.read_text(encoding="utf-8"))
top = netlist.get("modules", {}).get("SimpleDualPortMemory")
if top is None:
    raise SystemExit("full-domain canonical JSON is missing SimpleDualPortMemory")
ports = top.get("ports", {})
cells = top.get("cells", {})
memories = [cell for cell in cells.values() if cell.get("type") == "$mem_v2"]
if len(memories) != 1:
    raise SystemExit("full-domain canonical JSON does not contain exactly one $mem_v2")
memory = memories[0]
parameters = memory.get("parameters", {})
connections = memory.get("connections", {})

def integer(value):
    if isinstance(value, int):
        return value
    if isinstance(value, str) and value and set(value) <= {"0", "1"}:
        return int(value, 2)
    raise SystemExit("unsupported integer parameter encoding: " + repr(value))

def set_one_to_zero(container, name):
    if name not in container:
        raise SystemExit("canonical cell is missing parameter " + name)
    value = container[name]
    if integer(value) != 1:
        raise SystemExit("canonical parameter is not one: " + name + "=" + repr(value))
    if isinstance(value, int):
        container[name] = 0
    else:
        container[name] = "0" * len(value)

def sole_output_register():
    read_data = ports.get("read_data", {}).get("bits", [])
    matches = [
        cell
        for cell in cells.values()
        if "dff" in cell.get("type", "").lower()
        and cell.get("connections", {}).get("Q") == read_data
    ]
    if len(matches) != 1:
        raise SystemExit("external full-domain form lacks one read-output register")
    return matches[0]

clock = ports.get("clk", {}).get("bits", [])
read_enable = ports.get("read_enable", {}).get("bits", [])
write_enable = ports.get("write_enable", {}).get("bits", [])
if len(clock) != 1 or len(read_enable) != 1 or len(write_enable) != 1:
    raise SystemExit("full-domain canonical control ports are not one bit")
absorbed = integer(parameters.get("RD_CLK_ENABLE")) == 1

if mutation == "active-asynchronous-read-reset":
    connections["RD_ARST"] = list(clock)
elif mutation == "active-synchronous-read-reset":
    connections["RD_SRST"] = list(clock)
elif mutation == "contaminated-read-enable":
    if absorbed:
        connections["RD_EN"] = list(write_enable)
    else:
        raw_read_data = connections.get("RD_DATA", [])
        matches = [
            cell
            for cell in cells.values()
            if cell.get("type") == "$mux"
            and cell.get("connections", {}).get("B") == raw_read_data
            and cell.get("connections", {}).get("S") == read_enable
        ]
        if len(matches) != 1:
            raise SystemExit("external full-domain form lacks one read-enable hold mux")
        matches[0]["connections"]["S"] = list(write_enable)
elif mutation == "contaminated-read-clock":
    if absorbed:
        connections["RD_CLK"] = list(write_enable)
    else:
        sole_output_register()["connections"]["CLK"] = list(write_enable)
elif mutation == "falling-read-clock":
    if absorbed:
        set_one_to_zero(parameters, "RD_CLK_POLARITY")
    else:
        set_one_to_zero(sole_output_register().get("parameters", {}), "CLK_POLARITY")
else:
    raise SystemExit("unknown full-domain JSON mutation: " + mutation)

destination.write_text(json.dumps(netlist, indent=2) + "\n", encoding="utf-8")
PY

  if cmp -s "$canonical_netlist" "$mutated_netlist"; then
    echo "SimpleDualPortMemory full-domain JSON mutation did not change the netlist: $label" >&2
    exit 1
  fi
  if python3 "$repo_root/morphhdl/scripts/check-yosys-simple-dual-port-memory-contract.py" \
      "$mutated_netlist" --width 4 --depth 8; then
    echo "SimpleDualPortMemory checker accepted forbidden full-domain JSON mutation: $label" >&2
    exit 1
  fi
  echo "Yosys SimpleDualPortMemory checker rejected forbidden full-domain JSON mutation: $label"
}

yosys_simple_dual_port_memory_full_domain_json_mutation_must_fail \
  active-asynchronous-read-reset active-asynchronous-read-reset
yosys_simple_dual_port_memory_full_domain_json_mutation_must_fail \
  active-synchronous-read-reset active-synchronous-read-reset
yosys_simple_dual_port_memory_full_domain_json_mutation_must_fail \
  contaminated-read-enable contaminated-read-enable
yosys_simple_dual_port_memory_full_domain_json_mutation_must_fail \
  contaminated-read-clock contaminated-read-clock
yosys_simple_dual_port_memory_full_domain_json_mutation_must_fail \
  falling-read-clock falling-read-clock

simple_dual_port_memory_fixed_address_mutation_must_fail() {
  local role="$1"
  local mutated_file="$tmp_dir/simple-dual-port-memory-fixed-${role}.v"
  local mutated_netlist="$tmp_dir/SimpleDualPortMemory-fixed-${role}.json"
  local original="[(clog2(DEPTH, 1))-1:0] ${role}_address"
  local replacement="[2:0] ${role}_address"

  python3 - "$yosys_simple_dual_port_memory_file" "$mutated_file" \
      "$original" "$replacement" <<'PY'
import pathlib
import sys

source_path, target_path, original, replacement = sys.argv[1:]
source = pathlib.Path(source_path).read_text(encoding="utf-8")
if source.count(original) != 1:
    raise SystemExit("fixed-address mutation source is not unique: " + original)
pathlib.Path(target_path).write_text(source.replace(original, replacement), encoding="utf-8")
PY
  yosys -q -p \
    "read_verilog -noautowire $mutated_file; chparam -set DEPTH 3 SimpleDualPortMemory; hierarchy -check -top SimpleDualPortMemory; proc; opt_reduce; opt_expr -mux_undef; memory_dff; memory_collect; opt_clean; check -assert; write_json $mutated_netlist"
  if python3 "$repo_root/morphhdl/scripts/check-yosys-simple-dual-port-memory-contract.py" \
      "$mutated_netlist" --width 8 --depth 3; then
    echo "SimpleDualPortMemory checker accepted fixed ${role}-address width" >&2
    exit 1
  fi
  echo "Yosys SimpleDualPortMemory rejected fixed ${role}-address width"
}

simple_dual_port_memory_fixed_address_mutation_must_fail read
simple_dual_port_memory_fixed_address_mutation_must_fail write

yosys_synchronous_stream_fifo_synthesize_and_check() {
  local label="$1"
  local expected_width="$2"
  local expected_depth="$3"
  local parameter_command="$4"
  local process_netlist="$tmp_dir/SynchronousStreamFifo-${label}-process.json"
  local synthesized_netlist="$tmp_dir/SynchronousStreamFifo-${label}-synthesized.json"

  yosys -q -p \
    "read_verilog -noautowire $yosys_synchronous_stream_fifo_file; $parameter_command hierarchy -check -top SynchronousStreamFifo; proc; opt -mux_undef; memory_dff; opt_merge; memory_collect; opt_clean; check -assert; write_json $process_netlist; synth -top SynchronousStreamFifo; check -assert; write_json $synthesized_netlist"
  python3 "$repo_root/morphhdl/scripts/check-yosys-synchronous-stream-fifo-contract.py" \
    "$process_netlist" --source "$yosys_synchronous_stream_fifo_file" \
    --width "$expected_width" --depth "$expected_depth"
  python3 "$repo_root/morphhdl/scripts/check-yosys-port-widths.py" \
    "$synthesized_netlist" SynchronousStreamFifo \
    --port "clk:input:1" \
    --port "pop_data:output:$expected_width" \
    --port "pop_ready:input:1" \
    --port "pop_valid:output:1" \
    --port "push_data:input:$expected_width" \
    --port "push_ready:output:1" \
    --port "push_valid:input:1" \
    --port "reset:input:1"
}

yosys_synchronous_stream_fifo_synthesize_and_check default 8 5 ""
yosys_synchronous_stream_fifo_synthesize_and_check \
  awkward 5 3 "chparam -set DEPTH 3 -set WIDTH 5 SynchronousStreamFifo;"
yosys_synchronous_stream_fifo_synthesize_and_check \
  minimum 1 1 "chparam -set DEPTH 1 -set WIDTH 1 SynchronousStreamFifo;"
yosys_synchronous_stream_fifo_synthesize_and_check \
  power-eight 4 8 "chparam -set DEPTH 8 -set WIDTH 4 SynchronousStreamFifo;"

yosys_synchronous_stream_fifo_mutation_must_fail() {
  local label="$1"
  local original="$2"
  local replacement="$3"
  local expected_count="$4"
  local mutated_file="$tmp_dir/synchronous-stream-fifo-${label}.v"
  local mutated_netlist="$tmp_dir/SynchronousStreamFifo-${label}-mutated.json"

  python3 - "$yosys_synchronous_stream_fifo_file" "$mutated_file" \
      "$original" "$replacement" "$expected_count" <<'PY'
import pathlib
import sys

source_path, target_path, original, replacement, expected_count = sys.argv[1:]
source = pathlib.Path(source_path).read_text(encoding="utf-8")
expected = int(expected_count)
actual = source.count(original)
if actual != expected:
    raise SystemExit(
        "mutation source count for {!r} is {}, expected {}".format(
            original, actual, expected
        )
    )
pathlib.Path(target_path).write_text(
    source.replace(original, replacement), encoding="utf-8"
)
PY

  if cmp -s "$yosys_synchronous_stream_fifo_file" "$mutated_file"; then
    echo "SynchronousStreamFifo mutation did not change the fixture: $label" >&2
    exit 1
  fi
  if ! yosys -q -p \
      "read_verilog -noautowire $mutated_file; hierarchy -check -top SynchronousStreamFifo; proc; opt -mux_undef; memory_dff; opt_merge; memory_collect; opt_clean; check -assert; write_json $mutated_netlist"; then
    echo "Yosys rejected forbidden SynchronousStreamFifo mutation during synthesis: $label"
    return
  fi
  if python3 "$repo_root/morphhdl/scripts/check-yosys-synchronous-stream-fifo-contract.py" \
      "$mutated_netlist" --source "$mutated_file" --width 8 --depth 5; then
    echo "SynchronousStreamFifo checker accepted forbidden mutation: $label" >&2
    exit 1
  fi
  echo "Yosys SynchronousStreamFifo rejected forbidden mutation: $label"
}

yosys_synchronous_stream_fifo_mutation_must_fail \
  wrong-capacity-ready \
  'assign push_ready = occupancy < DEPTH;' \
  'assign push_ready = occupancy <= DEPTH;' 1
yosys_synchronous_stream_fifo_mutation_must_fail \
  full-pop-replacement \
  'assign push_ready = occupancy < DEPTH;' \
  'assign push_ready = occupancy < DEPTH || pop_fire;' 1
yosys_synchronous_stream_fifo_mutation_must_fail \
  empty-fall-through \
  "pop_valid == 1'b0 && occupancy > 0" \
  "pop_valid == 1'b0 && occupancy >= 0" 1
yosys_synchronous_stream_fifo_mutation_must_fail \
  occupancy-one-no-bubble \
  "pop_fire == 1'b1 && occupancy > 1" \
  "pop_fire == 1'b1 && occupancy > 0" 1
yosys_synchronous_stream_fifo_mutation_must_fail \
  falling-edge-clock \
  'always @(posedge clk) begin : p_fifo' \
  'always @(negedge clk) begin : p_fifo' 1
yosys_synchronous_stream_fifo_mutation_must_fail \
  active-low-reset \
  "if (reset == 1'b1) begin" \
  "if (reset == 1'b0) begin" 1
yosys_synchronous_stream_fifo_mutation_must_fail \
  partial-write \
  'memory[write_pointer] <= push_data;' \
  'memory[write_pointer][0] <= push_data[0];' 1
yosys_synchronous_stream_fifo_mutation_must_fail \
  wrong-pointer-wrap \
  'if (write_pointer == DEPTH - 1) begin' \
  'if (write_pointer == DEPTH) begin' 1
yosys_synchronous_stream_fifo_mutation_must_fail \
  decrement-on-push \
  "occupancy <= occupancy + 1'b1;" \
  "occupancy <= occupancy - 1'b1;" 1
yosys_synchronous_stream_fifo_mutation_must_fail \
  payload-reset \
  $'      occupancy <= {OCCUPANCY_WIDTH{1\'b0}};\n      pop_valid <= 1\'b0;' \
  $'      occupancy <= {OCCUPANCY_WIDTH{1\'b0}};\n      pop_valid <= 1\'b0;\n      pop_data <= {WIDTH{1\'b0}};' 1
yosys_synchronous_stream_fifo_mutation_must_fail \
  fixed-pointer-width \
  'localparam integer POINTER_WIDTH = clog2(DEPTH, 1);' \
  'localparam integer POINTER_WIDTH = 3;' 1
yosys_synchronous_stream_fifo_mutation_must_fail \
  extra-storage-slot \
  'reg [WIDTH-1:0] memory [0:DEPTH-1];' \
  'reg [WIDTH-1:0] memory [0:DEPTH];' 1

yosys_synchronous_stream_fifo_json_mutation_must_fail() {
  local label="$1"
  local mutation="$2"
  local canonical_netlist="$tmp_dir/SynchronousStreamFifo-default-process.json"
  local mutated_netlist="$tmp_dir/SynchronousStreamFifo-${label}-json-mutated.json"

  python3 - "$canonical_netlist" "$mutated_netlist" "$mutation" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
mutation = sys.argv[3]
netlist = json.loads(source.read_text(encoding="utf-8"))
top = netlist.get("modules", {}).get("SynchronousStreamFifo")
if top is None:
    raise SystemExit("canonical JSON is missing SynchronousStreamFifo")
memories = [
    cell for cell in top.get("cells", {}).values() if cell.get("type") == "$mem_v2"
]
if len(memories) != 1:
    raise SystemExit("canonical JSON does not contain exactly one $mem_v2")
memory = memories[0]
parameters = memory.get("parameters", {})
connections = memory.get("connections", {})

def set_encoded(container, name, expected, replacement):
    value = container.get(name)
    if isinstance(value, int):
        actual = value
        encoded = replacement
    elif isinstance(value, str) and value and set(value) <= {"0", "1"}:
        actual = int(value, 2)
        encoded = format(replacement, "0{}b".format(len(value)))
    else:
        raise SystemExit("unsupported parameter encoding for " + name + ": " + repr(value))
    if actual != expected:
        raise SystemExit("canonical parameter {} is {}, expected {}".format(name, actual, expected))
    container[name] = encoded

def set_integer(name, expected, replacement):
    set_encoded(parameters, name, expected, replacement)

def exact_state_driver(state_name):
    state = top.get("netnames", {}).get(state_name, {}).get("bits", [])
    matches = [
        cell
        for cell in top.get("cells", {}).values()
        if cell.get("connections", {}).get("Q") == state
    ]
    if len(matches) != 1:
        raise SystemExit(
            "canonical JSON lacks one {} state driver".format(state_name)
        )
    return matches[0]

def reset_inverter_output():
    reset = top.get("ports", {}).get("reset", {}).get("bits", [])
    matches = [
        cell.get("connections", {}).get("Y", [])
        for cell in top.get("cells", {}).values()
        if cell.get("type") == "$not"
        and cell.get("connections", {}).get("A") == reset
        and len(cell.get("connections", {}).get("Y", [])) == 1
    ]
    if len(matches) != 1:
        raise SystemExit("canonical JSON lacks one reset inverter")
    return matches[0]

if mutation == "initialized-memory":
    value = parameters.get("INIT")
    if not isinstance(value, str) or set(value.lower()) != {"x"}:
        raise SystemExit("canonical INIT is not wholly uninitialized")
    parameters["INIT"] = "0" * len(value)
elif mutation == "extra-storage-slot":
    set_integer("SIZE", 5, 6)
elif mutation == "falling-write-clock":
    set_integer("WR_CLK_POLARITY", 1, 0)
elif mutation == "payload-width-drift":
    set_integer("WIDTH", 8, 7)
elif mutation == "reset-memory":
    reset = top.get("ports", {}).get("reset", {}).get("bits", [])
    if len(reset) != 1:
        raise SystemExit("canonical reset port is not one bit")
    connections["RD_SRST"] = list(reset)
elif mutation == "asynchronous-read":
    set_integer("RD_CLK_ENABLE", 1, 0)
elif mutation == "falling-read-clock":
    set_integer("RD_CLK_POLARITY", 1, 0)
elif mutation == "contaminated-read-clock":
    push_valid = top.get("ports", {}).get("push_valid", {}).get("bits", [])
    if len(push_valid) != 1:
        raise SystemExit("canonical push_valid port is not one bit")
    connections["RD_CLK"] = list(push_valid)
elif mutation == "disconnected-read-data":
    connections["RD_DATA"] = ["0"] * len(connections.get("RD_DATA", []))
elif mutation == "constant-read-enable":
    connections["RD_EN"] = ["0"]
elif mutation == "contaminated-read-enable":
    push_valid = top.get("ports", {}).get("push_valid", {}).get("bits", [])
    if len(push_valid) != 1:
        raise SystemExit("canonical push_valid port is not one bit")
    connections["RD_EN"] = list(push_valid)
elif mutation == "contaminated-write-enable":
    pop_fire = top.get("netnames", {}).get("pop_fire", {}).get("bits", [])
    if len(pop_fire) != 1:
        raise SystemExit("canonical pop_fire net is not one bit")
    connections["WR_EN"] = pop_fire * len(connections.get("WR_EN", []))
elif mutation == "ungated-write-enable":
    push_fire = top.get("netnames", {}).get("push_fire", {}).get("bits", [])
    if len(push_fire) != 1:
        raise SystemExit("canonical push_fire net is not one bit")
    connections["WR_EN"] = push_fire * len(connections.get("WR_EN", []))
elif mutation == "inverted-write-reset":
    write_enable = connections.get("WR_EN", [])
    if not write_enable or any(bit != write_enable[0] for bit in write_enable):
        raise SystemExit("canonical WR_EN is not one replicated signal")
    drivers = [
        cell
        for cell in top.get("cells", {}).values()
        if cell.get("connections", {}).get("Y") == [write_enable[0]]
    ]
    if len(drivers) != 1 or drivers[0].get("type") != "$mux":
        raise SystemExit("canonical WR_EN lacks one reset guard mux")
    drivers[0]["connections"]["S"] = reset_inverter_output()
elif mutation == "inverted-read-reset":
    read_enable = connections.get("RD_EN", [])
    drivers = [
        cell
        for cell in top.get("cells", {}).values()
        if cell.get("connections", {}).get("Y") == read_enable
    ]
    if len(drivers) != 1 or drivers[0].get("type") != "$reduce_and":
        raise SystemExit("canonical RD_EN lacks one fetch/reset conjunction")
    inactive_reset = reset_inverter_output()[0]
    active_reset = top.get("ports", {}).get("reset", {}).get("bits", [])
    inputs = drivers[0].get("connections", {}).get("A", [])
    if inputs.count(inactive_reset) != 1 or len(active_reset) != 1:
        raise SystemExit("canonical RD_EN does not contain one inverted reset")
    drivers[0]["connections"]["A"] = [
        active_reset[0] if bit == inactive_reset else bit for bit in inputs
    ]
elif mutation in {
    "state-reset-connection",
    "state-reset-polarity",
    "state-reset-value",
    "state-enable-over-reset",
}:
    state = exact_state_driver("occupancy")
    state_parameters = state.get("parameters", {})
    state_connections = state.get("connections", {})
    if mutation == "state-reset-connection":
        push_valid = top.get("ports", {}).get("push_valid", {}).get("bits", [])
        if len(push_valid) != 1:
            raise SystemExit("canonical push_valid port is not one bit")
        state_connections["SRST"] = list(push_valid)
    elif mutation == "state-reset-polarity":
        set_encoded(state_parameters, "SRST_POLARITY", 1, 0)
    elif mutation == "state-reset-value":
        value = state_parameters.get("SRST_VALUE")
        if not isinstance(value, str) or not value or set(value) != {"0"}:
            raise SystemExit("canonical occupancy reset value is not all zero")
        state_parameters["SRST_VALUE"] = "1" + value[1:]
    else:
        if state.get("type") != "$sdffe":
            raise SystemExit("canonical occupancy state is not $sdffe")
        state["type"] = "$sdffce"
elif mutation in {"signed-ready-comparator", "short-ready-comparator"}:
    ready = top.get("ports", {}).get("push_ready", {}).get("bits", [])
    comparators = [
        cell
        for cell in top.get("cells", {}).values()
        if cell.get("type") == "$lt"
        and cell.get("connections", {}).get("Y") == ready
    ]
    if len(comparators) != 1:
        raise SystemExit("canonical JSON lacks one ready comparator")
    comparator_parameters = comparators[0].get("parameters", {})
    if mutation == "signed-ready-comparator":
        set_encoded(comparator_parameters, "A_SIGNED", 0, 1)
    else:
        set_encoded(comparator_parameters, "A_WIDTH", 3, 2)
else:
    raise SystemExit("unknown FIFO JSON mutation: " + mutation)

destination.write_text(json.dumps(netlist, indent=2) + "\n", encoding="utf-8")
PY

  if cmp -s "$canonical_netlist" "$mutated_netlist"; then
    echo "SynchronousStreamFifo JSON mutation did not change the netlist: $label" >&2
    exit 1
  fi
  if python3 "$repo_root/morphhdl/scripts/check-yosys-synchronous-stream-fifo-contract.py" \
      "$mutated_netlist" --source "$yosys_synchronous_stream_fifo_file" \
      --width 8 --depth 5; then
    echo "SynchronousStreamFifo checker accepted forbidden JSON mutation: $label" >&2
    exit 1
  fi
  echo "Yosys SynchronousStreamFifo checker rejected forbidden JSON mutation: $label"
}

yosys_synchronous_stream_fifo_json_mutation_must_fail \
  initialized-memory initialized-memory
yosys_synchronous_stream_fifo_json_mutation_must_fail \
  extra-storage-slot extra-storage-slot
yosys_synchronous_stream_fifo_json_mutation_must_fail \
  falling-write-clock falling-write-clock
yosys_synchronous_stream_fifo_json_mutation_must_fail \
  payload-width-drift payload-width-drift
yosys_synchronous_stream_fifo_json_mutation_must_fail \
  reset-memory reset-memory
yosys_synchronous_stream_fifo_json_mutation_must_fail \
  asynchronous-read asynchronous-read
yosys_synchronous_stream_fifo_json_mutation_must_fail \
  falling-read-clock falling-read-clock
yosys_synchronous_stream_fifo_json_mutation_must_fail \
  contaminated-read-clock contaminated-read-clock
yosys_synchronous_stream_fifo_json_mutation_must_fail \
  disconnected-read-data disconnected-read-data
yosys_synchronous_stream_fifo_json_mutation_must_fail \
  constant-read-enable constant-read-enable
yosys_synchronous_stream_fifo_json_mutation_must_fail \
  contaminated-read-enable contaminated-read-enable
yosys_synchronous_stream_fifo_json_mutation_must_fail \
  contaminated-write-enable contaminated-write-enable
yosys_synchronous_stream_fifo_json_mutation_must_fail \
  ungated-write-enable ungated-write-enable
yosys_synchronous_stream_fifo_json_mutation_must_fail \
  inverted-write-reset inverted-write-reset
yosys_synchronous_stream_fifo_json_mutation_must_fail \
  inverted-read-reset inverted-read-reset
yosys_synchronous_stream_fifo_json_mutation_must_fail \
  state-reset-connection state-reset-connection
yosys_synchronous_stream_fifo_json_mutation_must_fail \
  state-reset-polarity state-reset-polarity
yosys_synchronous_stream_fifo_json_mutation_must_fail \
  state-reset-value state-reset-value
yosys_synchronous_stream_fifo_json_mutation_must_fail \
  state-enable-over-reset state-enable-over-reset
yosys_synchronous_stream_fifo_json_mutation_must_fail \
  signed-ready-comparator signed-ready-comparator
yosys_synchronous_stream_fifo_json_mutation_must_fail \
  short-ready-comparator short-ready-comparator

yosys_synchronous_stream_m2s_pipe_synthesize_and_check() {
  local label="$1"
  local expected_width="$2"
  local parameter_command="$3"
  local process_netlist="$tmp_dir/SynchronousStreamM2sPipe-${label}-process.json"
  local synthesized_netlist="$tmp_dir/SynchronousStreamM2sPipe-${label}-synthesized.json"

  yosys -q -p \
    "read_verilog -noautowire $yosys_synchronous_stream_m2s_pipe_file; $parameter_command hierarchy -check -top SynchronousStreamM2sPipe; proc; opt_dff; opt_clean; check -assert; write_json $process_netlist; synth -top SynchronousStreamM2sPipe; check -assert; write_json $synthesized_netlist"
  python3 "$repo_root/morphhdl/scripts/check-yosys-synchronous-stream-m2s-pipe-contract.py" \
    "$process_netlist" --source "$yosys_synchronous_stream_m2s_pipe_file" \
    --width "$expected_width"
  python3 "$repo_root/morphhdl/scripts/check-yosys-port-widths.py" \
    "$synthesized_netlist" SynchronousStreamM2sPipe \
    --port "clk:input:1" \
    --port "pop_data:output:$expected_width" \
    --port "pop_ready:input:1" \
    --port "pop_valid:output:1" \
    --port "push_data:input:$expected_width" \
    --port "push_ready:output:1" \
    --port "push_valid:input:1" \
    --port "reset:input:1"
}

yosys_synchronous_stream_m2s_pipe_synthesize_and_check default 8 ""
yosys_synchronous_stream_m2s_pipe_synthesize_and_check \
  minimum 1 "chparam -set WIDTH 1 SynchronousStreamM2sPipe;"
yosys_synchronous_stream_m2s_pipe_synthesize_and_check \
  awkward 5 "chparam -set WIDTH 5 SynchronousStreamM2sPipe;"
yosys_synchronous_stream_m2s_pipe_synthesize_and_check \
  maximum 32 "chparam -set WIDTH 32 SynchronousStreamM2sPipe;"

yosys_synchronous_stream_m2s_pipe_mutation_must_fail() {
  local label="$1"
  local original="$2"
  local replacement="$3"
  local expected_count="$4"
  local mutated_file="$tmp_dir/synchronous-stream-m2s-pipe-${label}.v"
  local mutated_netlist="$tmp_dir/SynchronousStreamM2sPipe-${label}-mutated.json"

  python3 - "$yosys_synchronous_stream_m2s_pipe_file" "$mutated_file" \
      "$original" "$replacement" "$expected_count" <<'PY'
import pathlib
import sys

source_path, target_path, original, replacement, expected_count = sys.argv[1:]
source = pathlib.Path(source_path).read_text(encoding="utf-8")
expected = int(expected_count)
actual = source.count(original)
if actual != expected:
    raise SystemExit(
        "mutation source count for {!r} is {}, expected {}".format(
            original, actual, expected
        )
    )
pathlib.Path(target_path).write_text(
    source.replace(original, replacement), encoding="utf-8"
)
PY

  if cmp -s "$yosys_synchronous_stream_m2s_pipe_file" "$mutated_file"; then
    echo "SynchronousStreamM2sPipe mutation did not change the fixture: $label" >&2
    exit 1
  fi
  if ! yosys -q -p \
      "read_verilog -noautowire $mutated_file; hierarchy -check -top SynchronousStreamM2sPipe; proc; opt_dff; opt_clean; check -assert; write_json $mutated_netlist"; then
    echo "Yosys rejected forbidden SynchronousStreamM2sPipe mutation during synthesis: $label"
    return
  fi
  if python3 "$repo_root/morphhdl/scripts/check-yosys-synchronous-stream-m2s-pipe-contract.py" \
      "$mutated_netlist" --source "$mutated_file" --width 8; then
    echo "SynchronousStreamM2sPipe checker accepted forbidden mutation: $label" >&2
    exit 1
  fi
  echo "Yosys SynchronousStreamM2sPipe rejected forbidden mutation: $label"
}

yosys_synchronous_stream_m2s_pipe_mutation_must_fail \
  missing-full-replacement \
  'assign push_ready = pop_ready || !pop_valid;' \
  'assign push_ready = !pop_valid;' 1
yosys_synchronous_stream_m2s_pipe_mutation_must_fail \
  wrong-ready-conjunction \
  'assign push_ready = pop_ready || !pop_valid;' \
  'assign push_ready = pop_ready && !pop_valid;' 1
yosys_synchronous_stream_m2s_pipe_mutation_must_fail \
  falling-edge-clock \
  'always @(posedge clk) begin : p_m2s_pipe' \
  'always @(negedge clk) begin : p_m2s_pipe' 1
yosys_synchronous_stream_m2s_pipe_mutation_must_fail \
  asynchronous-reset \
  'always @(posedge clk) begin : p_m2s_pipe' \
  'always @(posedge clk or posedge reset) begin : p_m2s_pipe' 1
yosys_synchronous_stream_m2s_pipe_mutation_must_fail \
  active-low-reset \
  "if (reset == 1'b1) begin" \
  "if (reset == 1'b0) begin" 1
yosys_synchronous_stream_m2s_pipe_mutation_must_fail \
  reset-valid-high \
  "pop_valid <= 1'b0;" \
  "pop_valid <= 1'b1;" 1
yosys_synchronous_stream_m2s_pipe_mutation_must_fail \
  wrong-valid-enable \
  $'    end else if (push_ready == 1\'b1) begin\n      pop_valid <= push_valid;' \
  $'    end else if (push_valid == 1\'b1) begin\n      pop_valid <= push_valid;' 1
yosys_synchronous_stream_m2s_pipe_mutation_must_fail \
  payload-gated-by-valid \
  $'    if (push_ready == 1\'b1) begin\n      pop_data <= push_data;' \
  $'    if (push_ready == 1\'b1 && push_valid == 1\'b1) begin\n      pop_data <= push_data;' 1
yosys_synchronous_stream_m2s_pipe_mutation_must_fail \
  payload-reset \
  $'      pop_valid <= 1\'b0;' \
  $'      pop_valid <= 1\'b0;\n      pop_data <= {WIDTH{1\'b0}};' 1

yosys_synchronous_stream_m2s_pipe_json_mutation_must_fail() {
  local label="$1"
  local mutation="$2"
  local canonical_netlist="$tmp_dir/SynchronousStreamM2sPipe-default-process.json"
  local mutated_netlist="$tmp_dir/SynchronousStreamM2sPipe-${label}-json-mutated.json"

  python3 - "$canonical_netlist" "$mutated_netlist" "$mutation" <<'PY'
import json
import pathlib
import sys

source = pathlib.Path(sys.argv[1])
destination = pathlib.Path(sys.argv[2])
mutation = sys.argv[3]
netlist = json.loads(source.read_text(encoding="utf-8"))
top = netlist.get("modules", {}).get("SynchronousStreamM2sPipe")
if top is None:
    raise SystemExit("canonical JSON is missing SynchronousStreamM2sPipe")
ports = top.get("ports", {})
cells = top.get("cells", {})

def unique(cell_type):
    matches = [cell for cell in cells.values() if cell.get("type") == cell_type]
    if len(matches) != 1:
        raise SystemExit("canonical JSON does not contain one " + cell_type)
    return matches[0]

def set_encoded(container, name, expected, replacement):
    value = container.get(name)
    if isinstance(value, int):
        actual = value
        encoded = replacement
    elif isinstance(value, str) and value and set(value) <= {"0", "1"}:
        actual = int(value, 2)
        encoded = format(replacement, "0{}b".format(len(value)))
    else:
        raise SystemExit("unsupported parameter encoding for " + name + ": " + repr(value))
    if actual != expected:
        raise SystemExit("canonical parameter {} is {}, expected {}".format(name, actual, expected))
    container[name] = encoded

valid = unique("$sdffe")
payload = unique("$dffe")
ready_not = unique("$logic_not")
ready_or = unique("$logic_or")

if mutation == "valid-clock":
    valid["connections"]["CLK"] = list(ports["push_valid"]["bits"])
elif mutation == "valid-data":
    valid["connections"]["D"] = list(ports["pop_ready"]["bits"])
elif mutation == "valid-enable":
    valid["connections"]["EN"] = list(ports["push_valid"]["bits"])
elif mutation == "valid-reset-connection":
    valid["connections"]["SRST"] = list(ports["pop_ready"]["bits"])
elif mutation == "valid-reset-polarity":
    set_encoded(valid["parameters"], "SRST_POLARITY", 1, 0)
elif mutation == "valid-reset-value":
    set_encoded(valid["parameters"], "SRST_VALUE", 0, 1)
elif mutation == "falling-valid-clock":
    set_encoded(valid["parameters"], "CLK_POLARITY", 1, 0)
elif mutation == "payload-clock":
    payload["connections"]["CLK"] = list(ports["reset"]["bits"])
elif mutation == "payload-data":
    payload["connections"]["D"] = ["0"] * len(ports["push_data"]["bits"])
elif mutation == "payload-enable":
    payload["connections"]["EN"] = list(ports["push_valid"]["bits"])
elif mutation == "payload-reset":
    payload["type"] = "$sdffe"
    payload["connections"]["SRST"] = list(ports["reset"]["bits"])
    payload["parameters"]["SRST_POLARITY"] = payload["parameters"]["EN_POLARITY"]
    payload["parameters"]["SRST_VALUE"] = "0" * 8
elif mutation == "ready-not-input":
    ready_not["connections"]["A"] = list(ports["push_valid"]["bits"])
elif mutation == "ready-and":
    ready_or["type"] = "$logic_and"
elif mutation == "ready-or-input":
    ready_or["connections"]["A"] = list(ports["push_valid"]["bits"])
else:
    raise SystemExit("unknown m2s pipe JSON mutation: " + mutation)

destination.write_text(json.dumps(netlist, indent=2) + "\n", encoding="utf-8")
PY

  if cmp -s "$canonical_netlist" "$mutated_netlist"; then
    echo "SynchronousStreamM2sPipe JSON mutation did not change the netlist: $label" >&2
    exit 1
  fi
  if python3 "$repo_root/morphhdl/scripts/check-yosys-synchronous-stream-m2s-pipe-contract.py" \
      "$mutated_netlist" --source "$yosys_synchronous_stream_m2s_pipe_file" \
      --width 8; then
    echo "SynchronousStreamM2sPipe checker accepted forbidden JSON mutation: $label" >&2
    exit 1
  fi
  echo "Yosys SynchronousStreamM2sPipe checker rejected forbidden JSON mutation: $label"
}

for m2s_pipe_json_mutation in \
  valid-clock \
  valid-data \
  valid-enable \
  valid-reset-connection \
  valid-reset-polarity \
  valid-reset-value \
  falling-valid-clock \
  payload-clock \
  payload-data \
  payload-enable \
  payload-reset \
  ready-not-input \
  ready-and \
  ready-or-input
do
  yosys_synchronous_stream_m2s_pipe_json_mutation_must_fail \
    "$m2s_pipe_json_mutation" "$m2s_pipe_json_mutation"
done

echo "Strict Verilog-2001 contract checks passed"
