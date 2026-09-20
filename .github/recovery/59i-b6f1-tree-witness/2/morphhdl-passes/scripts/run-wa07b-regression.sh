#!/usr/bin/env bash
set -eEuo pipefail
trap 'status=$?; printf "WA07B-REGRESSION-FAILED: %s:%s: %s (exit %s)\n" "${BASH_SOURCE[0]:-bash}" "$LINENO" "$BASH_COMMAND" "$status" >&2; exit "$status"' ERR

repo_root="${GITHUB_WORKSPACE:-$(git rev-parse --show-toplevel)}"
cd "${repo_root}"
case "${1:-}" in
  '') bash morphhdl-passes/scripts/run-wa07a-regression.sh ;;
  --after-wa07a) ;; # The workflow has just executed the unchanged historical leg.
  *) echo 'usage: run-wa07b-regression.sh [--after-wa07a]' >&2; exit 2 ;;
esac

root="${repo_root}/morphhdl-passes/build"
out="${root}/pass-outputs"
repeat="${root}/repeated-pass-outputs"
reference="${root}/formal/wire_assignment_ir/generated/parameterized_stream_fifo.v"
new_reference="${root}/formal/wire_assignment_ir/ternary-reference-check"
native="${root}/wa07b-native"
native_repeat="${root}/wa07b-native-repeat"
mkdir -p "${new_reference}" "${native}" "${native_repeat}" "${out}" "${repeat}"

test -s "${reference}"
sbt -batch '++2.12.18' \
  'set morph / Test / unmanagedSourceDirectories ++= Seq(file("morphhdl-passes/src/main/scala"), file("morphhdl-passes/examples"))' \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoBooleanTernaryWitness reference ${new_reference} parameterized_stream_fifo.v ${new_reference}/report.json" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoBooleanTernaryWitness ternary ${out} boolean-ternary-simplification.v ${out}/boolean-ternary-simplification-report.json" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoBooleanTernaryWitness all ${out} wire-assignment-five-pass.v ${out}/wire-assignment-five-pass-report.json" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoBooleanTernaryWitness ternary ${repeat} boolean-ternary-simplification.v ${repeat}/boolean-ternary-simplification-report.json" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoBooleanTernaryWitness all ${repeat} wire-assignment-five-pass.v ${repeat}/wire-assignment-five-pass-report.json" \
  "morph / Test / runMain morphhdl.examples.BooleanTernaryGenericNativeWitness reference ${native} ${native}/reference-report.json" \
  "morph / Test / runMain morphhdl.examples.BooleanTernaryGenericNativeWitness ternary ${native} ${native}/ternary-report.json" \
  "morph / Test / runMain morphhdl.examples.BooleanTernaryGenericNativeWitness all ${native} ${native}/all-report.json" \
  "morph / Test / runMain morphhdl.examples.BooleanTernaryGenericNativeWitness reference ${native_repeat} ${native_repeat}/reference-report.json" \
  "morph / Test / runMain morphhdl.examples.BooleanTernaryGenericNativeWitness ternary ${native_repeat} ${native_repeat}/ternary-report.json" \
  "morph / Test / runMain morphhdl.examples.BooleanTernaryGenericNativeWitness all ${native_repeat} ${native_repeat}/all-report.json"

test -s "${new_reference}/parameterized_stream_fifo.v"
cmp -s "${reference}" "${new_reference}/parameterized_stream_fifo.v" || {
  echo 'WA-07b reference is not the unchanged snapshot before ALL passes.' >&2
  exit 1
}
for stem in boolean-ternary-simplification wire-assignment-five-pass; do
  test -s "${out}/${stem}.v"
  test -s "${out}/${stem}-report.json"
  cmp -s "${out}/${stem}.v" "${repeat}/${stem}.v"
  cmp -s "${out}/${stem}-report.json" "${repeat}/${stem}-report.json"
  grep -q 'parameter integer WIDTH' "${out}/${stem}.v"
  grep -q 'parameter integer DEPTH' "${out}/${stem}.v"
done
for mode in reference ternary all; do
  cmp -s "${native}/native-${mode}.v" "${native_repeat}/native-${mode}.v"
  cmp -s "${native}/${mode}-report.json" "${native_repeat}/${mode}-report.json"
done

python3 - "${out}" "${native}" <<'PY'
import json
import sys
from pathlib import Path
out, native = map(Path, sys.argv[1:])
ternary_id = 'boolean-ternary-simplification'
all_ids = ['wire-alias-unnamed', 'wire-alias-named', 'wire-expression-unnamed',
           'constant-operand-simplification', ternary_id]

def check(report, ids):
    assert report['pass_id'] == '+'.join(ids), report
    assert report['executed_passes'] == ids, report
    assert report['executed_rounds'] and all(x == ids for x in report['executed_rounds']), report
    assert report['rounds'] == len(report['executed_rounds']), report
    assert report['actual_rhs_capture_writeback'] is True, report
    assert report['executed_before_name_allocation'] is True, report
    assert report['procedural_receiver_rewrites'] == 0, report
    assert report['common_flag_enabled'] is (len(ids) == 5), report
    assert report['ternary_captured_assignment_count'] > 0, report
    changed = report['ternary_simplified_assignment_count']
    assert report['ternary_no_op'] is (changed == 0), report
    if changed:
        assert report['rounds'] >= 2 and report['rules'], report

# A legitimate no-op on this one shared source is recorded, not disguised as
# coverage. Independent real native fixtures below MUST trigger both rules.
for stem, ids in ((ternary_id, [ternary_id]), ('wire-assignment-five-pass', all_ids)):
    report = json.loads((out / f'{stem}-report.json').read_text())
    check(report, ids)
    if len(ids) == 5:
        for key in ('unnamed_alias_eliminated_count', 'named_alias_eliminated_count',
                    'unnamed_expression_eliminated_count', 'constant_simplified_assignment_count'):
            assert report[key] > 0, (key, report)
for mode, ids in (('ternary', [ternary_id]), ('all', all_ids)):
    report = json.loads((native / f'{mode}-report.json').read_text())
    check(report, ids)
    assert report['ternary_simplified_assignment_count'] > 0, report
    for rule in ('boolean-ternary-positive', 'boolean-ternary-inverse'):
        assert report['rules'].get(rule, 0) > 0, (rule, report)
PY

# Real native backend output, independent of the structured canonical rule oracle.
iverilog -g2001 -s BooleanTernaryNativeTb -o "${native}/native.vvp" \
  "${native}/native-reference.v" "${native}/native-ternary.v" "${native}/native-all.v" \
  morphhdl-passes/tests/formal/wire_assignment_ir/boolean_ternary_native_tb.v
vvp "${native}/native.vvp" | tee "${native}/simulation.log"
grep -q 'WA07B_NATIVE_PASS patterns=16 outputs=8 candidates=2' "${native}/simulation.log"
! grep -q 'WA07B_NATIVE_FAIL' "${native}/simulation.log"

yosys -Q -p "read_verilog -D FORMAL ${native}/native-reference.v ${native}/native-ternary.v ${native}/native-all.v morphhdl-passes/tests/formal/wire_assignment_ir/boolean_ternary_native_tb.v; prep -top BooleanTernaryNativeMiter; flatten; opt; sat -verify -prove ok 1 -show-inputs" \
  >"${native}/formal.log" 2>&1
grep -q 'SUCCESS' "${native}/formal.log"
for pair in ternary:Candidate all:All; do
  mode="${pair%:*}"
  top="BooleanTernaryNative${pair#*:}"
  verilator --lint-only --language 1364-2001 --top-module "${top}" -Wno-fatal \
    "${native}/native-${mode}.v" >"${native}/${mode}-lint.log" 2>&1
  yosys -Q -p "read_verilog ${native}/native-${mode}.v; hierarchy -check -top ${top}; synth -top ${top}; check -assert" \
    >"${native}/${mode}-synthesis.log" 2>&1
done

for stem in boolean-ternary-simplification wire-assignment-five-pass; do
  candidate="${out}/${stem}.v"
  for binding in 1:1 1:8 3:3 8:5 16:5 64:8; do
    width="${binding%:*}"
    depth="${binding#*:}"
    dir="${root}/wa07b-checks/${stem}/WIDTH-${width}__DEPTH-${depth}"
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

# Retain a human-readable demonstration in the log as well as the artifacts.
for mode in reference ternary all; do
  printf '\nWA07B_ACTUAL_NATIVE_%s_BEGIN\n' "${mode}"
  cat "${native}/native-${mode}.v"
  printf 'WA07B_ACTUAL_NATIVE_%s_END\n' "${mode}"
done
printf 'WA-07b native capture/writeback, four-state, legality and determinism gates passed.\n'
