#!/usr/bin/env bash
set -euo pipefail

root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root_dir"

bash morphhdl/scripts/check-native-int-shadow-expression-boundary.sh

# Corrective increments may restore previously modified upstream files. The
# chained exact native-source manifest check remains authoritative.

registry_file="morphruntime/src/main/scala/spinal/core/ExternalNativeIntShadowRegistry.scala"
formal_file="frontend/src/main/scala/morphhdl/frontend/formalComponent.scala"
formal_publisher_file="frontend/src/main/scala/spinal/core/ExternalAnalyzedNativeIntFormalizationPublisher.scala"
structural_file="frontend/src/main/scala/morphhdl/frontend/NativeStructuralFrontend.scala"
bridge_file="frontend/src/main/scala/morphhdl/frontend/NativeIntSymbolicConditional.scala"
plugin_file="morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeIntShadowExpressionComponent.scala"
test_file="morphhdl/src/test/scala/morphhdl/NativeIntSymbolicConditionalTests.scala"
doc_file="docs/morphhdl/increment-51-native-int-symbolic-conditionals.md"

for required in \
  "$registry_file" \
  "$formal_file" \
  "$formal_publisher_file" \
  "$structural_file" \
  "$bridge_file" \
  "$plugin_file" \
  "$test_file" \
  "$doc_file"; do
  test -f "$required"
done

grep -Fq "ExternalAnalyzedNativeIntFormalizationPublisher.captureComponent" "$formal_file"
grep -Fq "ExternalNativeIntShadowRegistry.captureWithDefinition" "$formal_publisher_file"
grep -Fq "definitionPredicateTracked" "$registry_file"
grep -Fq "startGenerateIfExpression" "$structural_file"
grep -Fq "selectSymbolicChain" "$bridge_file"
grep -Fq "NativeIntSymbolicConditional" "$plugin_file"
if ! grep -Fq \
  "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-NESTED-DEFERRED" \
  "$bridge_file"; then
  grep -Fq "MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-DEPTH-EXCEEDED" "$bridge_file"
fi
grep -Fq "ordinary Scala Boolean conditional remains concrete" "$test_file"
grep -Fq "native symbolic branch replay is deterministic" "$test_file"

if grep -Eq 'Map\[[^]]*(Int|BigInt|Boolean)|HashMap\[[^]]*(Int|BigInt|Boolean)' \
  "$registry_file" "$bridge_file"; then
  echo "Increment 51 must not key branch provenance by a concrete witness" >&2
  exit 1
fi

if grep -Eq 'getName\(|definitionName|componentName|signalName' \
  "$registry_file" "$bridge_file" "$plugin_file"; then
  echo "Increment 51 must not recognize emitted component or signal names" >&2
  exit 1
fi

printf '%s\n' "Increment 51 native Int symbolic conditional boundary checks passed."
