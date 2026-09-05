#!/usr/bin/env bash
set -eEuo pipefail
# Preserve the failing command and status in CI instead of stopping silently at
# the boundary between the historical and new native proof legs.
trap 'status=$?; printf "WA07A-REGRESSION-FAILED: %s:%s: %s (exit %s)\n" "${BASH_SOURCE[0]:-bash}" "$LINENO" "$BASH_COMMAND" "$status" >&2; exit "$status"' ERR

repo_root="${GITHUB_WORKSPACE:-$(git rev-parse --show-toplevel)}"
cd "${repo_root}"
python3 morphhdl-passes/scripts/test_wire_assignment_clock_model.py
# Retain every historical independent proof leg and its common reference.
bash morphhdl-passes/scripts/run-wa07-regression.sh

# SBT forks morph/Test runMain from its subproject working directory.
# Pass absolute artifact paths so the producer and proof consumer agree.
root="${repo_root}/morphhdl-passes/build"
out="${root}/pass-outputs"
repeat="${root}/repeated-pass-outputs"
reference="${root}/formal/wire_assignment_ir/generated/parameterized_stream_fifo.v"
new_reference="${root}/formal/wire_assignment_ir/constant-reference-check"
native="${root}/wa07a-native"
mkdir -p "${new_reference}" "${native}" "${out}" "${repeat}"

sbt -batch '++2.12.18' \
  'set morph / Test / unmanagedSourceDirectories ++= Seq(file("morphhdl-passes/src/main/scala"), file("morphhdl-passes/examples"))' \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoConstantPassWitness reference ${new_reference} parameterized_stream_fifo.v ${new_reference}/report.json" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoConstantPassWitness constant ${out} constant-operand-simplification.v ${out}/constant-operand-simplification-report.json" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoConstantPassWitness all ${out} wire-assignment-four-pass.v ${out}/wire-assignment-four-pass-report.json" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoConstantPassWitness constant ${repeat} constant-operand-simplification.v ${repeat}/constant-operand-simplification-report.json" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoConstantPassWitness all ${repeat} wire-assignment-four-pass.v ${repeat}/wire-assignment-four-pass-report.json" \
  "morph / Test / runMain morphhdl.examples.ConstantOperandGenericNativeWitness reference ${native} ${native}/reference-report.json" \
  "morph / Test / runMain morphhdl.examples.ConstantOperandGenericNativeWitness constant ${native} ${native}/candidate-report.json"

test -s "${reference}"
test -s "${new_reference}/parameterized_stream_fifo.v"
cmp -s "${reference}" "${new_reference}/parameterized_stream_fifo.v" || {
  echo 'WA-07a reference is not the unchanged snapshot before ALL passes.' >&2
  exit 1
}

for stem in constant-operand-simplification wire-assignment-four-pass; do
  test -s "${out}/${stem}.v"
  test -s "${out}/${stem}-report.json"
  if cmp -s "${reference}" "${out}/${stem}.v"; then
    echo "WA-07a ${stem} performed no observable expression rewrite." >&2
    exit 1
  fi
  cmp -s "${out}/${stem}.v" "${repeat}/${stem}.v"
  cmp -s "${out}/${stem}-report.json" "${repeat}/${stem}-report.json"
  grep -q 'parameter integer WIDTH' "${out}/${stem}.v"
  grep -q 'parameter integer DEPTH' "${out}/${stem}.v"
done

python3 - "${out}" "${native}" <<'PY'
import json
import sys
from pathlib import Path
out, native = map(Path, sys.argv[1:])
constant_id = "constant-operand-simplification"
all_ids = ["wire-alias-unnamed", "wire-alias-named", "wire-expression-unnamed", constant_id]
for stem, ids in ((constant_id, [constant_id]), ("wire-assignment-four-pass", all_ids)):
    report = json.loads((out / f"{stem}-report.json").read_text())
    assert report["pass_id"] == "+".join(ids), report
    assert report["executed_passes"] == ids, report
    assert report["actual_rhs_capture_writeback"] is True, report
    assert report["executed_before_name_allocation"] is True, report
    assert report["procedural_receiver_rewrites"] == 0, report
    assert report["simplified_assignment_count"] > 0 and report["rounds"] >= 2, report
    assert report["rules"], report
    assert report["common_flag_enabled"] is (len(ids) == 4), report
    if len(ids) == 4:
        for key in ("unnamed_alias_eliminated_count", "named_alias_eliminated_count", "unnamed_expression_eliminated_count"):
            assert report[key] > 0, (key, report)
report = json.loads((native / "candidate-report.json").read_text())
assert report["simplified_assignment_count"] >= 6, report
assert report["actual_rhs_capture_writeback"] is True, report
PY

# Four-state validation of ACTUAL native capture/writeback and backend emission,
# independent of the canonical test renderer. No unknown input is constrained.
iverilog -g2001 -s ConstantOperandNativeTb -o "${native}/native.vvp" \
  "${native}/native-reference.v" "${native}/native-candidate.v" \
  morphhdl-passes/tests/formal/wire_assignment_ir/constant_native_tb.v
vvp "${native}/native.vvp" | tee "${native}/simulation.log"
grep -q 'WA07A_NATIVE_PASS patterns=16 outputs=8' "${native}/simulation.log"
! grep -q 'WA07A_NATIVE_FAIL' "${native}/simulation.log"

for stem in constant-operand-simplification wire-assignment-four-pass; do
  candidate="${out}/${stem}.v"
  for binding in 1:1 1:8 3:3 8:5 16:5 64:8; do
    width="${binding%:*}"
    depth="${binding#*:}"
    dir="${root}/wa07a-checks/${stem}/WIDTH-${width}__DEPTH-${depth}"
    mkdir -p "${dir}"
    iverilog -g2001 -s ParameterizedStreamFifo \
      -PParameterizedStreamFifo.WIDTH="${width}" -PParameterizedStreamFifo.DEPTH="${depth}" \
      -o "${dir}/compile.vvp" "${candidate}" >"${dir}/compile.log" 2>&1
    verilator --lint-only --language 1364-2001 --top-module ParameterizedStreamFifo \
      -GWIDTH="${width}" -GDEPTH="${depth}" -Wno-fatal "${candidate}" >"${dir}/lint.log" 2>&1
    yosys -Q -p "read_verilog ${candidate}; chparam -set WIDTH ${width} -set DEPTH ${depth} ParameterizedStreamFifo; hierarchy -check -top ParameterizedStreamFifo; synth -top ParameterizedStreamFifo; check -assert" \
      >"${dir}/synthesis.log" 2>&1
    iverilog -g2001 -s Wa03ParameterizedWitnessTb \
      -PWa03ParameterizedWitnessTb.WIDTH="${width}" -PWa03ParameterizedWitnessTb.DEPTH="${depth}" \
      -o "${dir}/simulation.vvp" "${candidate}" \
      morphhdl-passes/tests/formal/wire_assignment_ir/parameterized_witness_tb.v \
      >"${dir}/simulation-compile.log" 2>&1
    vvp "${dir}/simulation.vvp" >"${dir}/simulation.log" 2>&1
    grep -q 'WA03_SIM_PASS' "${dir}/simulation.log"
    ! grep -q 'WA03_SIM_FAIL' "${dir}/simulation.log"
  done
done
printf 'WA-07a native capture/writeback, four-state, legality and determinism gates passed.\n'
