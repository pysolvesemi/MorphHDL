#!/usr/bin/env bash
set -eEuo pipefail
trap 'status=$?; printf "WA09-REGRESSION-FAILED: %s:%s: %s (exit %s)\n" "${BASH_SOURCE[0]:-bash}" "$LINENO" "$BASH_COMMAND" "$status" >&2; exit "$status"' ERR

repo_root="${GITHUB_WORKSPACE:-$(git rev-parse --show-toplevel)}"
cd "${repo_root}"
case "${1:-}" in
  '') bash morphhdl-passes/scripts/run-wa07b-regression.sh ;;
  --after-wa07b) ;; # The workflow has just executed every frozen historical leg.
  *) echo 'usage: run-wa09-regression.sh [--after-wa07b]' >&2; exit 2 ;;
esac

root="${repo_root}/morphhdl-passes/build"
out="${root}/pass-outputs"
repeat="${root}/repeated-pass-outputs"
common_reference="${root}/formal/wire_assignment_ir/generated/parameterized_stream_fifo.v"
reference="${root}/formal/wire_assignment_ir/wa09-reference"
reference_repeat="${root}/formal/wire_assignment_ir/wa09-reference-repeat"
native="${root}/wa09-native"
native_repeat="${root}/wa09-native-repeat"
preference="${root}/wa09-preference-native"
preference_repeat="${root}/wa09-preference-native-repeat"
checks="${root}/wa09-checks"
mkdir -p "${out}" "${repeat}" "${reference}" "${reference_repeat}" \
  "${native}" "${native_repeat}" "${preference}" \
  "${preference_repeat}" "${checks}"

# The WA-09 named-expression reference deliberately enables its source-level
# witness and may therefore differ textually from the frozen common reference.
# The six-stage candidate uses the normal production FIFO source.  The formal
# manifest compares both functionally equivalent candidates with the common
# normal-source reference; no byte-equality assumption bridges those sources.
test -s "${common_reference}"
sbt -batch '++2.12.18' \
  'set morph / Test / unmanagedSourceDirectories ++= Seq(file("morphhdl-passes/src/main/scala"), file("morphhdl-passes/examples"))' \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoNamedExpressionPassWitness reference ${reference} wire-expression-reference.v ${reference}/report.json" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoNamedExpressionPassWitness named-expression ${out} wire-expression-named.v ${out}/wire-expression-named-report.json" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoNamedExpressionPassWitness all ${out} wire-assignment-six-pass.v ${out}/wire-assignment-six-pass-report.json" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoNamedExpressionPassWitness reference ${reference_repeat} wire-expression-reference.v ${reference_repeat}/report.json" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoNamedExpressionPassWitness named-expression ${repeat} wire-expression-named.v ${repeat}/wire-expression-named-report.json" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoNamedExpressionPassWitness all ${repeat} wire-assignment-six-pass.v ${repeat}/wire-assignment-six-pass-report.json" \
  "morph / Test / runMain morphhdl.examples.NamedWireExpressionGenericNativeWitness reference ${native} ${native}/reference-report.json" \
  "morph / Test / runMain morphhdl.examples.NamedWireExpressionGenericNativeWitness named-expression ${native} ${native}/named-expression-report.json" \
  "morph / Test / runMain morphhdl.examples.NamedWireExpressionGenericNativeWitness reference ${native_repeat} ${native_repeat}/reference-report.json" \
  "morph / Test / runMain morphhdl.examples.NamedWireExpressionGenericNativeWitness named-expression ${native_repeat} ${native_repeat}/named-expression-report.json" \
  "morph / Test / runMain morphhdl.examples.NamedWirePreferenceNativeWitness reference ${preference} ${preference}/reference-report.json" \
  "morph / Test / runMain morphhdl.examples.NamedWirePreferenceNativeWitness candidate ${preference} ${preference}/candidate-report.json" \
  "morph / Test / runMain morphhdl.examples.NamedWirePreferenceNativeWitness reference ${preference_repeat} ${preference_repeat}/reference-report.json" \
  "morph / Test / runMain morphhdl.examples.NamedWirePreferenceNativeWitness candidate ${preference_repeat} ${preference_repeat}/candidate-report.json"

for path in \
  "${reference}/wire-expression-reference.v" "${reference}/report.json" \
  "${out}/wire-expression-named.v" "${out}/wire-expression-named-report.json" \
  "${out}/wire-assignment-six-pass.v" "${out}/wire-assignment-six-pass-report.json" \
  "${native}/native-reference.v" "${native}/reference-report.json" \
  "${native}/native-named-expression.v" "${native}/named-expression-report.json" \
  "${preference}/native-preference-reference.v" "${preference}/reference-report.json" \
  "${preference}/native-preference-candidate.v" "${preference}/candidate-report.json"; do
  test -s "${path}"
done

cmp -s "${reference}/wire-expression-reference.v" "${reference_repeat}/wire-expression-reference.v"
cmp -s "${reference}/report.json" "${reference_repeat}/report.json"
for stem in wire-expression-named wire-assignment-six-pass; do
  cmp -s "${out}/${stem}.v" "${repeat}/${stem}.v"
  cmp -s "${out}/${stem}-report.json" "${repeat}/${stem}-report.json"
  grep -q 'parameter integer WIDTH' "${out}/${stem}.v"
  grep -q 'parameter integer DEPTH' "${out}/${stem}.v"
  if [[ "${stem}" == wire-expression-named ]] && \
      cmp -s "${reference}/wire-expression-reference.v" "${out}/${stem}.v"; then
    echo "WA-09 ${stem} performed no observable native rewrite." >&2
    exit 1
  fi
done
for mode in reference named-expression; do
  cmp -s "${native}/native-${mode}.v" "${native_repeat}/native-${mode}.v"
  cmp -s "${native}/${mode}-report.json" "${native_repeat}/${mode}-report.json"
done
for mode in reference candidate; do
  cmp -s "${preference}/native-preference-${mode}.v" \
    "${preference_repeat}/native-preference-${mode}.v"
  cmp -s "${preference}/${mode}-report.json" \
    "${preference_repeat}/${mode}-report.json"
done

python3 - "${out}" "${reference}" "${native}" "${preference}" <<'PY'
import json
import re
import sys
from pathlib import Path

out, reference, native, preference = map(Path, sys.argv[1:])
historical = ["wire-alias-unnamed", "wire-alias-named", "wire-expression-unnamed"]
named_id = "wire-expression-named"
named_ids = historical + [named_id]
all_ids = named_ids + ["constant-operand-simplification", "boolean-ternary-simplification"]

reference_report = json.loads((reference / "report.json").read_text())
assert reference_report == {"schema_version": 1, "mode": "common-pre-pass-reference"}, reference_report

def check_fifo(stem, ids, common_flag, require_named_change):
    report = json.loads((out / f"{stem}-report.json").read_text())
    assert report["pass_id"] == "+".join(ids), report
    assert report["executed_passes"] == ids, report
    assert report["executed_rounds"] and all(row == ids for row in report["executed_rounds"]), report
    assert report["rounds"] == len(report["executed_rounds"]), report
    assert report["common_flag_enabled"] is common_flag, report
    assert report["executed_before_name_allocation"] is True, report
    assert report["actual_rhs_capture_writeback"] is True, report
    assert report["receiver_shape"] == "whole-rhs-only", report
    named_changes = report["named_expression_eliminated_count"]
    assert isinstance(named_changes, int) and named_changes >= 0, report
    if require_named_change:
        assert named_changes > 0, report
    # The selection executes all three frozen wire prerequisites.  A stage may
    # legitimately be a no-op on this successor-specific source; each stage's
    # independent historical runner above still requires a real elimination.
    for key in ("unnamed_alias_eliminated_count", "named_alias_eliminated_count",
                "unnamed_expression_eliminated_count"):
        assert isinstance(report[key], int) and report[key] >= 0, (key, report)

check_fifo("wire-expression-named", named_ids, False, True)
# The all-six artifact must remain the exact normal production topology.  It
# may legitimately converge after an earlier stage on a future equivalent
# source; the independent named slot above and production artifact suite both
# require a real WA-09 expression rewrite.
check_fifo("wire-assignment-six-pass", all_ids, True, False)

generic_report = json.loads((native / "named-expression-report.json").read_text())
assert generic_report["pass_id"] == named_id, generic_report
assert generic_report["executed_passes"] == [named_id], generic_report
assert generic_report["common_flag_enabled"] is False, generic_report
assert generic_report["executed_before_name_allocation"] is True, generic_report
assert generic_report["actual_rhs_capture_writeback"] is True, generic_report
assert generic_report["receiver_shape"] == "whole-rhs-only", generic_report
assert generic_report["eliminated_count"] == 2, generic_report
assert generic_report["rewritten_reference_count"] == 2, generic_report
assert sorted(generic_report["eliminated_names"]) == ["expressionSignal", "flagExpression"], generic_report
assert generic_report["eliminated_origins"] == ["explicit", "explicit"], generic_report
assert len(generic_report["expression_operators"]) == 2, generic_report

before = (native / "native-reference.v").read_text()
after = (native / "native-named-expression.v").read_text()
assert "module NamedWireExpressionNativeReference" in before, before
assert "module NamedWireExpressionNativeCandidate" in after, after
assert "expressionSignal" in before and "flagExpression" in before, before
assert "expressionSignal" not in after and "flagExpression" not in after, after
lines = {line.strip() for line in after.splitlines()}
assert "assign result = (a ^ b);" in lines, after
assert "assign flagResult = (c ^ d);" in lines, after

preference_reference_report = json.loads((preference / "reference-report.json").read_text())
assert preference_reference_report == {
    "schema_version": 1,
    "mode": "common-pre-pass-reference",
}, preference_reference_report
preference_report = json.loads((preference / "candidate-report.json").read_text())
assert preference_report["pass_id"] == "wire-alias-named", preference_report
assert preference_report["executed_passes"] == ["wire-alias-named"], preference_report
assert preference_report["executed_before_name_allocation"] is True, preference_report
assert preference_report["actual_native_identity_writeback"] is True, preference_report
assert preference_report["receiver_shape"] == "whole-rhs-only", preference_report
assert preference_report["eliminated_count"] == 4, preference_report
assert preference_report["rewritten_reference_count"] == 4, preference_report
assert preference_report["eliminated_names"] == [
    "substantiallyLongerMeaningfulSource",
    "substantiallyLongerMeaningfulAlias",
    "bbb",
    "p",
], preference_report
assert preference_report["deferred_source_origins"] == [
    "generated",
    "generated",
    "unnamed",
], preference_report

preference_before = (preference / "native-preference-reference.v").read_text()
preference_after = (preference / "native-preference-candidate.v").read_text()
before_identifiers = set(re.findall(r"[A-Za-z_$][A-Za-z0-9_$]*", preference_before))
after_identifiers = set(re.findall(r"[A-Za-z_$][A-Za-z0-9_$]*", preference_after))
for removed in preference_report["eliminated_names"]:
    assert removed in before_identifiers, (removed, preference_before)
    assert removed not in after_identifiers, (removed, preference_after)
for survivor in ("q", "abc", "aaa", "unnamedMeaningfulSurvivor",
                 "extraordinarilyLongProtectedName"):
    assert survivor in after_identifiers, (survivor, preference_after)
assert any(
    "(* keep *)" in line and "extraordinarilyLongProtectedName" in line
    for line in preference_after.splitlines()
), preference_after
preference_lines = {line.strip() for line in preference_after.splitlines()}
for expected in (
    "assign reverseResult = q;",
    "assign forwardResult = abc;",
    "assign lexicalResult = aaa;",
    "assign unnamedResult = unnamedMeaningfulSurvivor;",
    "assign protectedResult = extraordinarilyLongProtectedName;",
):
    assert expected in preference_lines, (expected, preference_after)
PY

# Strict Verilog-2001 parsing and a four-state native comparison.  The miter
# uses distinct generated module names, so no textual rewriting is involved.
iverilog -g2001 -s NamedWireExpressionNativeTb -o "${native}/native.vvp" \
  "${native}/native-reference.v" "${native}/native-named-expression.v" \
  morphhdl-passes/tests/formal/wire_assignment_ir/named_expression_native_tb.v
vvp "${native}/native.vvp" | tee "${native}/simulation.log"
grep -q 'WA09_NATIVE_PASS patterns=256 outputs=9 candidate=1' "${native}/simulation.log"
! grep -q 'WA09_NATIVE_FAIL' "${native}/simulation.log"

yosys -Q -p "read_verilog -D FORMAL ${native}/native-reference.v ${native}/native-named-expression.v morphhdl-passes/tests/formal/wire_assignment_ir/named_expression_native_tb.v; prep -top NamedWireExpressionNativeMiter; flatten; opt; sat -verify -prove ok 1 -show-inputs" \
  >"${native}/formal.log" 2>&1
grep -q 'SUCCESS' "${native}/formal.log"
for pair in reference:Reference named-expression:Candidate; do
  mode="${pair%:*}"
  top="NamedWireExpressionNative${pair#*:}"
  iverilog -g2001 -s "${top}" -o "${native}/${mode}-compile.vvp" \
    "${native}/native-${mode}.v" >"${native}/${mode}-compile.log" 2>&1
  verilator --lint-only --language 1364-2001 --top-module "${top}" -Wno-fatal \
    "${native}/native-${mode}.v" >"${native}/${mode}-lint.log" 2>&1
  yosys -Q -p "read_verilog ${native}/native-${mode}.v; hierarchy -check -top ${top}; synth -top ${top}; check -assert" \
    >"${native}/${mode}-synthesis.log" 2>&1
done

iverilog -g2001 -s NamedWirePreferenceNativeTb -o "${preference}/native.vvp" \
  "${preference}/native-preference-reference.v" \
  "${preference}/native-preference-candidate.v" \
  morphhdl-passes/tests/formal/wire_assignment_ir/named_preference_native_tb.v
vvp "${preference}/native.vvp" | tee "${preference}/simulation.log"
grep -q 'WA09_PREFERENCE_NATIVE_PASS patterns=16 outputs=5 candidate=1' \
  "${preference}/simulation.log"
! grep -q 'WA09_PREFERENCE_NATIVE_FAIL' "${preference}/simulation.log"

yosys -Q -p "read_verilog -D FORMAL ${preference}/native-preference-reference.v ${preference}/native-preference-candidate.v morphhdl-passes/tests/formal/wire_assignment_ir/named_preference_native_tb.v; prep -top NamedWirePreferenceNativeMiter; flatten; opt; sat -verify -prove ok 1 -show-inputs" \
  >"${preference}/formal.log" 2>&1
grep -q 'SUCCESS' "${preference}/formal.log"
for pair in reference:Reference candidate:Candidate; do
  mode="${pair%:*}"
  top="NamedWirePreferenceNative${pair#*:}"
  iverilog -g2001 -s "${top}" -o "${preference}/${mode}-compile.vvp" \
    "${preference}/native-preference-${mode}.v" \
    >"${preference}/${mode}-compile.log" 2>&1
  verilator --lint-only --language 1364-2001 --top-module "${top}" -Wno-fatal \
    "${preference}/native-preference-${mode}.v" \
    >"${preference}/${mode}-lint.log" 2>&1
  yosys -Q -p "read_verilog ${preference}/native-preference-${mode}.v; hierarchy -check -top ${top}; synth -top ${top}; check -assert" \
    >"${preference}/${mode}-synthesis.log" 2>&1
done

# Representative compilation, lint, synthesis and simulation are quick local
# gates.  The workflow's 16 proof shards retain the complete 512-binding domain
# (WIDTH=1..64, DEPTH=1..8), both repetitions, for both WA-09 manifest slots.
for stem in wire-expression-named wire-assignment-six-pass; do
  candidate="${out}/${stem}.v"
  for binding in 1:1 1:8 3:3 8:5 16:5 64:8; do
    width="${binding%:*}"
    depth="${binding#*:}"
    dir="${checks}/${stem}/WIDTH-${width}__DEPTH-${depth}"
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

printf '\nWA09_ACTUAL_NATIVE_BEFORE_BEGIN\n'
cat "${native}/native-reference.v"
printf 'WA09_ACTUAL_NATIVE_BEFORE_END\n'
printf '\nWA09_ACTUAL_NATIVE_AFTER_BEGIN\n'
cat "${native}/native-named-expression.v"
printf 'WA09_ACTUAL_NATIVE_AFTER_END\n'
printf '\nWA09_ACTUAL_PREFERENCE_BEFORE_BEGIN\n'
cat "${preference}/native-preference-reference.v"
printf 'WA09_ACTUAL_PREFERENCE_BEFORE_END\n'
printf '\nWA09_ACTUAL_PREFERENCE_AFTER_BEGIN\n'
cat "${preference}/native-preference-candidate.v"
printf 'WA09_ACTUAL_PREFERENCE_AFTER_END\n'
printf 'WA-09 native named-expression/preference, direct-structure, four-state, formal, legality and determinism gates passed.\n'
