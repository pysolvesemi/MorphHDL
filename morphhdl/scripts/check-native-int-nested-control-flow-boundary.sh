#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root_dir"

bash morphhdl/scripts/check-native-int-symbolic-conditional-boundary.sh

if git rev-parse --verify --quiet origin/parameterized-verilog >/dev/null; then
  baseline_ref="$(git merge-base HEAD origin/parameterized-verilog)"
else
  baseline_ref="$(git rev-parse HEAD^)"
fi

forbidden_native_changes="$({
  git diff --name-only "$baseline_ref"...HEAD -- \
    core/src/main \
    lib/src/main \
    idslplugin/src/main
} || true)"
if [[ -n "$forbidden_native_changes" ]]; then
  printf '%s\n' "Increment 52 modified upstream-owned native sources:" >&2
  printf '%s\n' "$forbidden_native_changes" >&2
  exit 1
fi

bridge_file="frontend/src/main/scala/morphhdl/frontend/NativeIntSymbolicConditional.scala"
plugin_file="morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeIntShadowExpressionComponent.scala"
test_file="morphhdl/src/test/scala/morphhdl/NativeIntNestedSymbolicControlFlowTests.scala"
doc_file="docs/morphhdl/increment-52-nested-symbolic-control-flow.md"

for required in "$bridge_file" "$plugin_file" "$test_file" "$doc_file"; do
  test -f "$required"
done

grep -Fq "MaximumCaptureDepth" "$bridge_file"
grep -Fq "guardAlternative" "$bridge_file"
grep -Fq "transformAlternative" "$plugin_file"
grep -Fq "MUTABLE-STATE-UNSUPPORTED" "$plugin_file"
grep -Fq "IO-UNSUPPORTED" "$plugin_file"
grep -Fq "REFLECTION-UNSUPPORTED" "$plugin_file"
grep -Fq "NONDETERMINISM-UNSUPPORTED" "$plugin_file"
grep -Fq "ARBITRARY-EFFECT-UNSUPPORTED" "$plugin_file"
grep -Fq "nested alternatives retain loops locals registers memory Areas ClockingAreas naming and assignments" "$test_file"
grep -Fq "nested symbolic control-flow replay is deterministic" "$test_file"

if grep -Fq "NESTED-DEFERRED" "$bridge_file"; then
  echo "Increment 52 must remove the Increment 51 nested-control deferral" >&2
  exit 1
fi

if grep -Eq 'Map\[[^]]*(Int|BigInt|Boolean)|HashMap\[[^]]*(Int|BigInt|Boolean)' \
  "$bridge_file"; then
  echo "Increment 52 must not key nested capture by a concrete witness" >&2
  exit 1
fi

if grep -Eq 'getName\(|definitionName|componentName|signalName' \
  "$bridge_file" "$plugin_file"; then
  echo "Increment 52 must not recognize emitted component or signal names" >&2
  exit 1
fi

printf '%s\n' "Increment 52 nested symbolic control-flow boundary checks passed."
