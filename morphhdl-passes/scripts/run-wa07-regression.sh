#!/usr/bin/env bash
set -euo pipefail

repo_root="${GITHUB_WORKSPACE:-$(git rev-parse --show-toplevel)}"
cd "${repo_root}"

bash morphhdl-passes/scripts/run-wa06-regression.sh

common="${repo_root}/morphhdl-passes/build/formal/wire_assignment_ir/generated/parameterized_stream_fifo.v"
reference_dir="${repo_root}/morphhdl-passes/build/formal/wire_assignment_ir/wa07-reference-check"
output_dir="${repo_root}/morphhdl-passes/build/pass-outputs"
repeat_dir="${repo_root}/morphhdl-passes/build/repeated-pass-outputs"
expression="${output_dir}/wire-expression-unnamed.v"
expression_report="${output_dir}/wire-expression-unnamed-report.json"
all_pass="${output_dir}/wire-passes-all.v"
all_report="${output_dir}/wire-passes-all-report.json"
expression_repeat="${repeat_dir}/wire-expression-unnamed.v"
expression_repeat_report="${repeat_dir}/wire-expression-unnamed-report.json"
all_repeat="${repeat_dir}/wire-passes-all.v"
all_repeat_report="${repeat_dir}/wire-passes-all-report.json"

rm -rf "${reference_dir}"
mkdir -p "${reference_dir}" "${output_dir}" "${repeat_dir}"

sbt -batch \
  '++2.12.18' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/src/main/scala/morphhdl/passes/api/PassContracts.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/src/main/scala/morphhdl/passes/adapter/CanonicalIrPassAdapter.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/src/main/scala/morphhdl/passes/safety/WireAliasSafetyGate.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/src/main/scala/morphhdl/passes/transform/UnnamedWireAliasEliminationPass.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/src/main/scala/morphhdl/passes/transform/NamedWireAliasEliminationPass.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/src/main/scala/morphhdl/passes/transform/UnnamedWireExpressionEliminationPass.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/src/main/scala/morphhdl/passes/pipeline/WireAliasPassPipeline.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/examples/ParameterizedStreamFifo.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/examples/UnnamedWireAliasNativeBridge.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/examples/NamedWireAliasNativeBridge.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/examples/UnnamedWireExpressionNativeBridge.scala")' \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoExpressionPassWitness reference ${reference_dir} parameterized_stream_fifo.v ${reference_dir}/reference-report.json" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoExpressionPassWitness candidate ${output_dir} wire-expression-unnamed.v ${expression_report}" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoAllPassWitness candidate ${output_dir} wire-passes-all.v ${all_report}" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoExpressionPassWitness candidate ${repeat_dir} wire-expression-unnamed.v ${expression_repeat_report}" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoAllPassWitness candidate ${repeat_dir} wire-passes-all.v ${all_repeat_report}"

reference="${reference_dir}/parameterized_stream_fifo.v"
cmp -s "${common}" "${reference}" || {
  echo 'WA-07 reference is not the unchanged common pre-pass reference.' >&2
  exit 1
}

for path in "${expression}" "${expression_report}" "${all_pass}" "${all_report}"; do
  test -s "${path}"
done
for candidate in "${expression}" "${all_pass}"; do
  if cmp -s "${common}" "${candidate}"; then
    echo "$(basename "${candidate}") made no transformation." >&2
    exit 1
  fi
  grep -q 'parameter integer WIDTH' "${candidate}"
  grep -q 'parameter integer DEPTH' "${candidate}"
done
cmp -s "${expression}" "${expression_repeat}"
cmp -s "${expression_report}" "${expression_repeat_report}"
cmp -s "${all_pass}" "${all_repeat}"
cmp -s "${all_report}" "${all_repeat_report}"

python3 - "${expression_report}" "${all_report}" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as handle:
    expression = json.load(handle)
with open(sys.argv[2], encoding="utf-8") as handle:
    all_pass = json.load(handle)
if expression.get("pass_id") != "wire-expression-unnamed":
    raise SystemExit("WA-07 expression report has the wrong pass id")
if all_pass.get("pass_id") != "wire-alias-unnamed+wire-alias-named+wire-expression-unnamed":
    raise SystemExit("WA-07 all-pass report has the wrong pass id")
for label, report in (("expression", expression), ("all", all_pass)):
    if report.get("executed_before_name_allocation") is not True:
        raise SystemExit(f"WA-07 {label} candidate ran after name allocation")
    if report.get("eliminated_count", 0) < 1:
        raise SystemExit(f"WA-07 {label} candidate eliminated no wire")
    if report.get("rewritten_reference_count", 0) < 1:
        raise SystemExit(f"WA-07 {label} candidate rewrote no receiver")
PY

(
  cd morphhdl-passes
  sbt -batch ++2.12.18 \
    'testOnly morphhdl.passes.transform.UnnamedWireExpressionEliminationPassSpec' \
    'testOnly morphhdl.passes.pipeline.WireAliasPassPipelineSpec -- -z "shared parameterized witness proof contract"'
)

for candidate in "${expression}" "${all_pass}"; do
  label="$(basename "${candidate}" .v)"
  check_dir="${repo_root}/morphhdl-passes/build/${label}-checks"
  mkdir -p "${check_dir}"
  iverilog -g2001 -s ParameterizedStreamFifo -o "${check_dir}/strict.vvp" "${candidate}"
  verilator --lint-only --language 1364-2001 -Wno-fatal -Wno-DECLFILENAME \
    --top-module ParameterizedStreamFifo "${candidate}"
  yosys -Q -p "read_verilog -defer ${candidate}; hierarchy -check -top ParameterizedStreamFifo; proc; opt_clean; memory_dff; memory_collect; opt_clean; check -assert; synth -top ParameterizedStreamFifo; check -assert; stat"
  for binding in WIDTH-1__DEPTH-1 WIDTH-8__DEPTH-3 WIDTH-64__DEPTH-8; do
    width="${binding#WIDTH-}"; width="${width%%__*}"
    depth="${binding##*DEPTH-}"
    sim_dir="${check_dir}/simulation/${binding}"
    mkdir -p "${sim_dir}"
    iverilog -g2001 -s Wa03ParameterizedWitnessTb \
      -PWa03ParameterizedWitnessTb.WIDTH="${width}" \
      -PWa03ParameterizedWitnessTb.DEPTH="${depth}" \
      -o "${sim_dir}/simulation.vvp" \
      "${candidate}" morphhdl-passes/tests/formal/wire_assignment_ir/parameterized_witness_tb.v
    vvp "${sim_dir}/simulation.vvp" | tee "${sim_dir}/run.log"
    grep -q 'WA03_SIM_PASS' "${sim_dir}/run.log"
  done
done

printf 'WA-07 unified control and unnamed expression regression passed.\n'
