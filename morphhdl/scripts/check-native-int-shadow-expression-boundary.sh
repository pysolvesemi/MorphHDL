#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root_dir"

bash morphhdl/scripts/check-native-int-shadow-provenance-boundary.sh

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
  printf '%s\n' "Increment 50 modified upstream-owned native sources:" >&2
  printf '%s\n' "$forbidden_native_changes" >&2
  exit 1
fi

expression_file="frontend/src/main/scala/spinal/core/ExternalNativeIntShadowExpression.scala"
registry_file="frontend/src/main/scala/spinal/core/ExternalNativeIntShadowRegistry.scala"
frontend_file="frontend/src/main/scala/morphhdl/frontend/NativeIntShadow.scala"
plugin_file="morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeIntShadowExpressionComponent.scala"
test_file="morphhdl/src/test/scala/morphhdl/ExternalNativeIntShadowExpressionTests.scala"
doc_file="docs/morphhdl/increment-50-native-int-expressions-and-predicates.md"

for required in \
  "$expression_file" \
  "$registry_file" \
  "$frontend_file" \
  "$plugin_file" \
  "$test_file" \
  "$doc_file"; do
  test -f "$required"
done

grep -Fq "ExternalNativeIntShadowExpression" "$expression_file"
grep -Fq "ExternalNativeIntShadowPredicate" "$expression_file"
grep -Fq "complete domain" "$expression_file"
grep -Fq "compilerBinary" "$frontend_file"
grep -Fq "compilerComparison" "$frontend_file"
grep -Fq "compilerPowerOfTwo" "$frontend_file"
grep -Fq "morphhdl-native-int-shadow-expressions" "$plugin_file"
grep -Fq "MORPH-FRONTEND-NATIVE-INT-EXPRESSION-DOMAIN-OVERFLOW" "$registry_file"
grep -Fq "MORPH-FRONTEND-NATIVE-INT-EXPRESSION-DIVISOR-ZERO-DOMAIN" "$registry_file"
grep -Fq "MORPH-FRONTEND-NATIVE-INT-EXPRESSION-BOXING-UNSUPPORTED" "$registry_file"
grep -Fq "MORPH-FRONTEND-NATIVE-INT-EXPRESSION-MUTABLE-ESCAPE" "$registry_file"
grep -Fq "ordinary SpinalVerilog preserves concrete native Int execution" "$test_file"
grep -Fq "expression and predicate replay is deterministic" "$test_file"

if grep -Eq 'Map\[[^]]*(Int|BigInt)|HashMap\[[^]]*(Int|BigInt)' \
  "$expression_file" "$registry_file"; then
  echo "Increment 50 must not key provenance by a concrete integer value" >&2
  exit 1
fi

if grep -Eq 'getName\(|definitionName|componentName|signalName' \
  "$expression_file" "$registry_file" "$plugin_file"; then
  echo "Increment 50 must not recognize emitted component or signal names" >&2
  exit 1
fi

printf '%s\n' "Increment 50 native Int expression boundary checks passed."
