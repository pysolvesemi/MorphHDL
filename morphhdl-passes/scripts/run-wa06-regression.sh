#!/usr/bin/env bash
set -euo pipefail

repo_root="${GITHUB_WORKSPACE:-$(git rev-parse --show-toplevel)}"
cd "${repo_root}"

reference_directory="${repo_root}/morphhdl-passes/build/formal/wire_assignment_ir/generated"
named_reference_directory="${repo_root}/morphhdl-passes/build/formal/wire_assignment_ir/named-reference-check"
combined_reference_directory="${repo_root}/morphhdl-passes/build/formal/wire_assignment_ir/combined-reference-check"
pass_output_directory="${repo_root}/morphhdl-passes/build/pass-outputs"
repeat_directory="${repo_root}/morphhdl-passes/build/repeated-pass-outputs"

reference="${reference_directory}/parameterized_stream_fifo.v"
reference_report="${reference_directory}/common-pre-pass-report.json"
named_reference="${named_reference_directory}/parameterized_stream_fifo.v"
named_reference_report="${named_reference_directory}/common-pre-pass-report.json"
combined_reference="${combined_reference_directory}/parameterized_stream_fifo.v"
combined_reference_report="${combined_reference_directory}/common-pre-pass-report.json"

unnamed_candidate="${pass_output_directory}/wire-alias-unnamed.v"
unnamed_report="${pass_output_directory}/wire-alias-unnamed-report.json"
named_candidate="${pass_output_directory}/wire-alias-named.v"
named_report="${pass_output_directory}/wire-alias-named-report.json"
combined_candidate="${pass_output_directory}/wire-alias-combined.v"
combined_report="${pass_output_directory}/wire-alias-combined-report.json"

unnamed_repeat="${repeat_directory}/wire-alias-unnamed.v"
unnamed_repeat_report="${repeat_directory}/wire-alias-unnamed-report.json"
named_repeat="${repeat_directory}/wire-alias-named.v"
named_repeat_report="${repeat_directory}/wire-alias-named-report.json"
combined_repeat="${repeat_directory}/wire-alias-combined.v"
combined_repeat_report="${repeat_directory}/wire-alias-combined-report.json"

rm -rf \
  "${reference_directory}" \
  "${named_reference_directory}" \
  "${combined_reference_directory}" \
  "${pass_output_directory}" \
  "${repeat_directory}" \
  "${repo_root}/morphhdl-passes/build/wire-alias-unnamed-checks" \
  "${repo_root}/morphhdl-passes/build/wire-alias-named-checks" \
  "${repo_root}/morphhdl-passes/build/wire-alias-combined-checks"
mkdir -p \
  "${reference_directory}" \
  "${named_reference_directory}" \
  "${combined_reference_directory}" \
  "${pass_output_directory}" \
  "${repeat_directory}"

sbt -batch \
  '++2.12.18' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/src/main/scala/morphhdl/passes/api/PassContracts.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/src/main/scala/morphhdl/passes/adapter/CanonicalIrPassAdapter.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/src/main/scala/morphhdl/passes/safety/WireAliasSafetyGate.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/src/main/scala/morphhdl/passes/transform/UnnamedWireAliasEliminationPass.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/src/main/scala/morphhdl/passes/transform/NamedWireAliasEliminationPass.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/src/main/scala/morphhdl/passes/pipeline/WireAliasPassPipeline.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/examples/ParameterizedStreamFifo.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/examples/UnnamedWireAliasNativeBridge.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/examples/NamedWireAliasNativeBridge.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/examples/OrderedWireAliasNativeBridge.scala")' \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoUnnamedPassWitness reference ${reference_directory} parameterized_stream_fifo.v ${reference_report}" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoNamedPassWitness reference ${named_reference_directory} parameterized_stream_fifo.v ${named_reference_report}" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoCombinedPassWitness reference ${combined_reference_directory} parameterized_stream_fifo.v ${combined_reference_report}" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoUnnamedPassWitness candidate ${pass_output_directory} wire-alias-unnamed.v ${unnamed_report}" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoNamedPassWitness candidate ${pass_output_directory} wire-alias-named.v ${named_report}" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoCombinedPassWitness candidate ${pass_output_directory} wire-alias-combined.v ${combined_report}" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoUnnamedPassWitness candidate ${repeat_directory} wire-alias-unnamed.v ${unnamed_repeat_report}" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoNamedPassWitness candidate ${repeat_directory} wire-alias-named.v ${named_repeat_report}" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoCombinedPassWitness candidate ${repeat_directory} wire-alias-combined.v ${combined_repeat_report}"

for path in \
  "${reference}" \
  "${reference_report}" \
  "${named_reference}" \
  "${named_reference_report}" \
  "${combined_reference}" \
  "${combined_reference_report}" \
  "${unnamed_candidate}" \
  "${unnamed_report}" \
  "${named_candidate}" \
  "${named_report}" \
  "${combined_candidate}" \
  "${combined_report}" \
  "${unnamed_repeat}" \
  "${unnamed_repeat_report}" \
  "${named_repeat}" \
  "${named_repeat_report}" \
  "${combined_repeat}" \
  "${combined_repeat_report}"; do
  test -s "${path}"
done

cmp -s "${reference}" "${named_reference}" || {
  echo 'WA-05 reference leg is not the unchanged common pre-pass reference.' >&2
  exit 1
}
cmp -s "${reference}" "${combined_reference}" || {
  echo 'WA-06 reference leg is not the unchanged common pre-pass reference.' >&2
  exit 1
}

for candidate in "${unnamed_candidate}" "${named_candidate}" "${combined_candidate}"; do
  if cmp -s "${reference}" "${candidate}"; then
    echo "$(basename "${candidate}") is byte-identical to the common pre-pass reference." >&2
    exit 1
  fi
  grep -q 'parameter integer WIDTH' "${candidate}"
  grep -q 'parameter integer DEPTH' "${candidate}"
done

cmp -s "${unnamed_candidate}" "${unnamed_repeat}"
cmp -s "${unnamed_report}" "${unnamed_repeat_report}"
cmp -s "${named_candidate}" "${named_repeat}"
cmp -s "${named_report}" "${named_repeat_report}"
cmp -s "${combined_candidate}" "${combined_repeat}"
cmp -s "${combined_report}" "${combined_repeat_report}"

python3 - \
  "${unnamed_report}" \
  "${named_report}" \
  "${combined_report}" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    unnamed = json.load(handle)
with open(sys.argv[2], encoding="utf-8") as handle:
    named = json.load(handle)
with open(sys.argv[3], encoding="utf-8") as handle:
    combined = json.load(handle)

for report, pass_id, label in (
    (unnamed, "wire-alias-unnamed", "WA-04"),
    (named, "wire-alias-named", "WA-05"),
):
    if report.get("pass_id") != pass_id:
        raise SystemExit(f"{label} witness report has the wrong pass id")
    if report.get("executed_before_name_allocation") is not True:
        raise SystemExit(f"{label} witness did not execute before native name allocation")
    if report.get("eliminated_count", 0) < 1:
        raise SystemExit(f"{label} witness eliminated no alias")
    if report.get("rewritten_reference_count", 0) < 1:
        raise SystemExit(f"{label} witness rewrote no exact native reference")

names = named.get("eliminated_names")
if not isinstance(names, list) or not names or any(not value for value in names):
    raise SystemExit("WA-05 witness did not report every removed explicit name")
if names != sorted(names):
    raise SystemExit("WA-05 removed-name report is not deterministic")

if combined.get("pass_id") != "wire-alias-unnamed+wire-alias-named":
    raise SystemExit("WA-06 combined witness report has the wrong pass id")
if combined.get("pipeline_status") != "changed":
    raise SystemExit("WA-06 combined witness did not report a changed pipeline")
if combined.get("executed_before_name_allocation") is not True:
    raise SystemExit("WA-06 combined witness did not execute before native name allocation")
if combined.get("executed_passes") != [
    "wire-alias-unnamed",
    "wire-alias-named",
]:
    raise SystemExit("WA-06 combined witness pass order changed")
if combined.get("unnamed_eliminated_count", 0) < 1:
    raise SystemExit("WA-06 combined witness eliminated no unnamed alias")
if combined.get("named_eliminated_count", 0) < 1:
    raise SystemExit("WA-06 combined witness eliminated no named alias")
if combined.get("rewritten_reference_count", 0) < 2:
    raise SystemExit("WA-06 combined witness did not rewrite both stages")
combined_names = combined.get("eliminated_names")
if not isinstance(combined_names, list) or not combined_names:
    raise SystemExit("WA-06 combined witness reported no removed named alias")
if combined_names != sorted(combined_names):
    raise SystemExit("WA-06 combined removed-name report is not deterministic")
PY

(
  cd morphhdl-passes
  sbt -batch ++2.12.18 \
    'testOnly morphhdl.passes.transform.UnnamedWireAliasEliminationPassSpec -- -z "shared parameterized witness proof contract"' \
    'testOnly morphhdl.passes.transform.NamedWireAliasEliminationPassSpec -- -z "shared parameterized witness proof contract"' \
    'testOnly morphhdl.passes.pipeline.WireAliasPassPipelineSpec -- -z "shared parameterized witness proof contract"'
)

for candidate in \
  "${unnamed_candidate}" \
  "${named_candidate}" \
  "${combined_candidate}"; do
  label="$(basename "${candidate}" .v)"
  check_directory="${repo_root}/morphhdl-passes/build/${label}-checks"
  mkdir -p "${check_directory}"

  iverilog -g2001 -s ParameterizedStreamFifo \
    -o "${check_directory}/strict-candidate.vvp" "${candidate}"
  verilator --lint-only --language 1364-2001 -Wno-fatal \
    -Wno-DECLFILENAME --top-module ParameterizedStreamFifo "${candidate}"
  yosys -Q -p "read_verilog -defer ${candidate}; hierarchy -check -top ParameterizedStreamFifo; proc; opt_clean; memory_dff; memory_collect; opt_clean; check -assert; synth -top ParameterizedStreamFifo; check -assert; stat"

  for binding in WIDTH-1__DEPTH-1 WIDTH-8__DEPTH-3 WIDTH-64__DEPTH-8; do
    width="${binding#WIDTH-}"
    width="${width%%__*}"
    depth="${binding##*DEPTH-}"
    simulation_directory="${check_directory}/simulation/${binding}"
    mkdir -p "${simulation_directory}"
    iverilog -g2001 -s Wa03ParameterizedWitnessTb \
      -PWa03ParameterizedWitnessTb.WIDTH="${width}" \
      -PWa03ParameterizedWitnessTb.DEPTH="${depth}" \
      -o "${simulation_directory}/simulation.vvp" \
      "${candidate}" \
      morphhdl-passes/tests/formal/wire_assignment_ir/parameterized_witness_tb.v
    vvp "${simulation_directory}/simulation.vvp" | tee "${simulation_directory}/run.log"
    grep -q 'WA03_SIM_PASS' "${simulation_directory}/run.log"
  done
done

printf 'WA-06 ordered pipeline generation, legality, synthesis, simulation and repeated-emission gates passed.\n'
