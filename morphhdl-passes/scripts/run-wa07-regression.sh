#!/usr/bin/env bash
set -euo pipefail

repo_root="${GITHUB_WORKSPACE:-$(git rev-parse --show-toplevel)}"
cd "${repo_root}"

formal_root="${repo_root}/morphhdl-passes/build/formal/wire_assignment_ir"
reference_directory="${formal_root}/generated"
named_reference_directory="${formal_root}/named-reference-check"
combined_reference_directory="${formal_root}/combined-reference-check"
expression_reference_directory="${formal_root}/expression-reference-check"
all_reference_directory="${formal_root}/all-reference-check"
pass_output_directory="${repo_root}/morphhdl-passes/build/pass-outputs"
repeat_directory="${repo_root}/morphhdl-passes/build/repeated-pass-outputs"

reference="${reference_directory}/parameterized_stream_fifo.v"
reference_report="${reference_directory}/common-pre-pass-report.json"
named_reference="${named_reference_directory}/parameterized_stream_fifo.v"
named_reference_report="${named_reference_directory}/common-pre-pass-report.json"
combined_reference="${combined_reference_directory}/parameterized_stream_fifo.v"
combined_reference_report="${combined_reference_directory}/common-pre-pass-report.json"
expression_reference="${expression_reference_directory}/parameterized_stream_fifo.v"
expression_reference_report="${expression_reference_directory}/common-pre-pass-report.json"
all_reference="${all_reference_directory}/parameterized_stream_fifo.v"
all_reference_report="${all_reference_directory}/common-pre-pass-report.json"

unnamed_candidate="${pass_output_directory}/wire-alias-unnamed.v"
unnamed_report="${pass_output_directory}/wire-alias-unnamed-report.json"
named_candidate="${pass_output_directory}/wire-alias-named.v"
named_report="${pass_output_directory}/wire-alias-named-report.json"
combined_candidate="${pass_output_directory}/wire-alias-combined.v"
combined_report="${pass_output_directory}/wire-alias-combined-report.json"
expression_candidate="${pass_output_directory}/wire-expression-unnamed.v"
expression_report="${pass_output_directory}/wire-expression-unnamed-report.json"
all_candidate="${pass_output_directory}/wire-assignment-all.v"
all_report="${pass_output_directory}/wire-assignment-all-report.json"

unnamed_repeat="${repeat_directory}/wire-alias-unnamed.v"
unnamed_repeat_report="${repeat_directory}/wire-alias-unnamed-report.json"
named_repeat="${repeat_directory}/wire-alias-named.v"
named_repeat_report="${repeat_directory}/wire-alias-named-report.json"
combined_repeat="${repeat_directory}/wire-alias-combined.v"
combined_repeat_report="${repeat_directory}/wire-alias-combined-report.json"
expression_repeat="${repeat_directory}/wire-expression-unnamed.v"
expression_repeat_report="${repeat_directory}/wire-expression-unnamed-report.json"
all_repeat="${repeat_directory}/wire-assignment-all.v"
all_repeat_report="${repeat_directory}/wire-assignment-all-report.json"

rm -rf \
  "${reference_directory}" \
  "${named_reference_directory}" \
  "${combined_reference_directory}" \
  "${expression_reference_directory}" \
  "${all_reference_directory}" \
  "${pass_output_directory}" \
  "${repeat_directory}" \
  "${repo_root}/morphhdl-passes/build/wire-alias-unnamed-checks" \
  "${repo_root}/morphhdl-passes/build/wire-alias-named-checks" \
  "${repo_root}/morphhdl-passes/build/wire-alias-combined-checks" \
  "${repo_root}/morphhdl-passes/build/wire-expression-unnamed-checks" \
  "${repo_root}/morphhdl-passes/build/wire-assignment-all-checks"
mkdir -p \
  "${reference_directory}" \
  "${named_reference_directory}" \
  "${combined_reference_directory}" \
  "${expression_reference_directory}" \
  "${all_reference_directory}" \
  "${pass_output_directory}" \
  "${repeat_directory}"

sbt -batch \
  '++2.12.18' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/src/main/scala/morphhdl/passes/api/PassContracts.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/src/main/scala/morphhdl/passes/adapter/CanonicalIrPassAdapter.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/src/main/scala/morphhdl/passes/safety/WireAliasSafetyGate.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/src/main/scala/morphhdl/passes/transform/UnnamedWireAliasEliminationPass.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/src/main/scala/morphhdl/passes/transform/NamedWireAliasEliminationPass.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/src/main/scala/morphhdl/passes/transform/UnnamedWireExpressionEliminationPass.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/src/main/scala/morphhdl/passes/transform/ConstantOperandSimplificationPass.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/src/main/scala/morphhdl/passes/pipeline/WireAliasPassPipeline.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/examples/ParameterizedStreamFifo.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/examples/UnnamedWireAliasNativeBridge.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/examples/NamedWireAliasNativeBridge.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/examples/OrderedWireAliasNativeBridge.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/examples/UnnamedWireExpressionNativeBridge.scala")' \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/examples/AllWireAssignmentNativeBridge.scala")' \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoUnnamedPassWitness reference ${reference_directory} parameterized_stream_fifo.v ${reference_report}" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoNamedPassWitness reference ${named_reference_directory} parameterized_stream_fifo.v ${named_reference_report}" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoCombinedPassWitness reference ${combined_reference_directory} parameterized_stream_fifo.v ${combined_reference_report}" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoExpressionPassWitness reference ${expression_reference_directory} parameterized_stream_fifo.v ${expression_reference_report}" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoAllPassWitness reference ${all_reference_directory} parameterized_stream_fifo.v ${all_reference_report}" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoUnnamedPassWitness candidate ${pass_output_directory} wire-alias-unnamed.v ${unnamed_report}" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoNamedPassWitness candidate ${pass_output_directory} wire-alias-named.v ${named_report}" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoCombinedPassWitness candidate ${pass_output_directory} wire-alias-combined.v ${combined_report}" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoExpressionPassWitness candidate ${pass_output_directory} wire-expression-unnamed.v ${expression_report}" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoAllPassWitness candidate ${pass_output_directory} wire-assignment-all.v ${all_report}" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoUnnamedPassWitness candidate ${repeat_directory} wire-alias-unnamed.v ${unnamed_repeat_report}" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoNamedPassWitness candidate ${repeat_directory} wire-alias-named.v ${named_repeat_report}" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoCombinedPassWitness candidate ${repeat_directory} wire-alias-combined.v ${combined_repeat_report}" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoExpressionPassWitness candidate ${repeat_directory} wire-expression-unnamed.v ${expression_repeat_report}" \
  "morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoAllPassWitness candidate ${repeat_directory} wire-assignment-all.v ${all_repeat_report}"

for path in \
  "${reference}" "${reference_report}" \
  "${named_reference}" "${named_reference_report}" \
  "${combined_reference}" "${combined_reference_report}" \
  "${expression_reference}" "${expression_reference_report}" \
  "${all_reference}" "${all_reference_report}" \
  "${unnamed_candidate}" "${unnamed_report}" \
  "${named_candidate}" "${named_report}" \
  "${combined_candidate}" "${combined_report}" \
  "${expression_candidate}" "${expression_report}" \
  "${all_candidate}" "${all_report}" \
  "${unnamed_repeat}" "${unnamed_repeat_report}" \
  "${named_repeat}" "${named_repeat_report}" \
  "${combined_repeat}" "${combined_repeat_report}" \
  "${expression_repeat}" "${expression_repeat_report}" \
  "${all_repeat}" "${all_repeat_report}"; do
  test -s "${path}"
done

for alternate in \
  "${named_reference}" \
  "${combined_reference}" \
  "${expression_reference}" \
  "${all_reference}"; do
  cmp -s "${reference}" "${alternate}" || {
    echo "$(basename "$(dirname "${alternate}")") is not the unchanged common pre-pass reference." >&2
    exit 1
  }
done

for candidate in \
  "${unnamed_candidate}" \
  "${named_candidate}" \
  "${combined_candidate}" \
  "${expression_candidate}" \
  "${all_candidate}"; do
  if cmp -s "${reference}" "${candidate}"; then
    echo "$(basename "${candidate}") is byte-identical to the common pre-pass reference." >&2
    exit 1
  fi
  grep -q 'parameter integer WIDTH' "${candidate}"
  grep -q 'parameter integer DEPTH' "${candidate}"
done

for stem in \
  wire-alias-unnamed \
  wire-alias-named \
  wire-alias-combined \
  wire-expression-unnamed \
  wire-assignment-all; do
  cmp -s "${pass_output_directory}/${stem}.v" "${repeat_directory}/${stem}.v"
  cmp -s "${pass_output_directory}/${stem}-report.json" "${repeat_directory}/${stem}-report.json"
done

python3 - \
  "${unnamed_report}" \
  "${named_report}" \
  "${combined_report}" \
  "${expression_report}" \
  "${all_report}" <<'PY'
import json
import sys

reports = []
for path in sys.argv[1:]:
    with open(path, encoding="utf-8") as handle:
        reports.append(json.load(handle))
unnamed, named, combined, expression, all_passes = reports

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
if not isinstance(names, list) or not names or names != sorted(names):
    raise SystemExit("WA-05 removed-name report is empty or nondeterministic")

if combined.get("pass_id") != "wire-alias-unnamed+wire-alias-named":
    raise SystemExit("WA-06 combined witness report has the wrong pass id")
if combined.get("executed_passes") != ["wire-alias-unnamed", "wire-alias-named"]:
    raise SystemExit("WA-06 historical combined pass order changed")
if combined.get("unnamed_eliminated_count", 0) < 1:
    raise SystemExit("WA-06 combined witness eliminated no unnamed alias")
if combined.get("named_eliminated_count", 0) < 1:
    raise SystemExit("WA-06 combined witness eliminated no named alias")

if expression.get("pass_id") != "wire-expression-unnamed":
    raise SystemExit("WA-07 expression witness report has the wrong pass id")
if expression.get("pipeline_status") != "changed":
    raise SystemExit("WA-07 expression witness did not report a change")
if expression.get("executed_before_name_allocation") is not True:
    raise SystemExit("WA-07 expression witness executed after name allocation")
if expression.get("procedural_receiver_rewrites") != 0:
    raise SystemExit("WA-07 expression witness rewrote an always-block receiver")
if expression.get("eliminated_count", 0) < 1:
    raise SystemExit("WA-07 expression witness eliminated no temporary")
if expression.get("rewritten_reference_count", 0) < 1:
    raise SystemExit("WA-07 expression witness inlined no receiver")
operators = expression.get("expression_operators")
if not isinstance(operators, list) or not operators:
    raise SystemExit("WA-07 expression witness reported no RHS expression")

expected_all = [
    "wire-alias-unnamed",
    "wire-alias-named",
    "wire-expression-unnamed",
]
if all_passes.get("pass_id") != "+".join(expected_all):
    raise SystemExit("WA-07 all-pass report has the wrong pass id")
if all_passes.get("common_flag_enabled") is not False or all_passes.get("historical_regression_selection") is not True:
    raise SystemExit("WA-07 historical candidate must not claim execution of the current four-pass flag")
if all_passes.get("executed_passes") != expected_all:
    raise SystemExit("WA-07 all-pass order changed")
if all_passes.get("procedural_receiver_rewrites") != 0:
    raise SystemExit("WA-07 all-pass candidate rewrote an always-block receiver")
for key in (
    "unnamed_alias_eliminated_count",
    "named_alias_eliminated_count",
    "unnamed_expression_eliminated_count",
):
    if all_passes.get(key, 0) < 1:
        raise SystemExit(f"WA-07 all-pass candidate has no {key}")
PY

(
  cd morphhdl-passes
  sbt -batch ++2.12.18 \
    'testOnly morphhdl.passes.api.AllPassConfigurationSpec' \
    'testOnly morphhdl.passes.transform.UnnamedWireExpressionEliminationPassSpec' \
    'testOnly morphhdl.passes.pipeline.WireAssignmentAllPassPipelineSpec' \
    'testOnly morphhdl.passes.transform.UnnamedWireAliasEliminationPassSpec -- -z "shared parameterized witness proof contract"' \
    'testOnly morphhdl.passes.transform.NamedWireAliasEliminationPassSpec -- -z "shared parameterized witness proof contract"' \
    'testOnly morphhdl.passes.pipeline.WireAliasPassPipelineSpec -- -z "shared parameterized witness proof contract"'
)

for candidate in \
  "${unnamed_candidate}" \
  "${named_candidate}" \
  "${combined_candidate}" \
  "${expression_candidate}" \
  "${all_candidate}"; do
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

printf 'WA-07 common flag, expression inlining, legality, synthesis, simulation and repeated-emission gates passed.\n'
