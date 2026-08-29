#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

if git rev-parse --verify origin/parameterized-verilog >/dev/null 2>&1; then
  base="$(git merge-base HEAD origin/parameterized-verilog)"
elif git rev-parse --verify HEAD^ >/dev/null 2>&1; then
  base="$(git rev-parse HEAD^)"
else
  base="$(git rev-parse HEAD)"
fi
if ! git diff --quiet "$base" HEAD -- core lib idslplugin; then
  echo "Increment 53d must not modify upstream-owned core, lib, or idslplugin sources" >&2
  git diff --name-status "$base" HEAD -- core lib idslplugin >&2
  exit 1
fi

plugin="morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeIntShadowExpressionComponent.scala"
runtime="morphruntime/src/main/scala/spinal/core/ExternalNativeIntCompilerRuntime.scala"
registry="morphruntime/src/main/scala/spinal/core/ExternalNativeIntShadowRegistry.scala"
resize_registry="morphruntime/src/main/scala/spinal/core/ExternalParameterizedResizeRegistry.scala"
test_file="morphhdl/src/test/scala/morphhdl/ParameterizedStreamWidthAdapterTests.scala"
generic_test_file="morphhdl/src/test/scala/morphhdl/GenericNativeDefinitionBoundaryTests.scala"
doc_file="docs/morphhdl/increment-53d-native-streamwidth-adapter.md"

for required in "$plugin" "$runtime" "$registry" "$resize_registry" "$test_file" "$generic_test_file" "$doc_file"; do
  test -f "$required"
done

grep -Fq 'object StreamWidthAdapter' lib/src/main/scala/spinal/lib/Stream.scala
grep -Fq 'spinal.lib.StreamWidthAdapter(' "$test_file"
grep -Fq 'withWidthFunctionBoundary' "$runtime"
grep -Fq 'compilerWidthOf' "$runtime"
grep -Fq 'widthQueryTracked' "$registry"
grep -Fq 'ExternalParameterizedResizeRegistry.attach' "$runtime"
grep -Fq 'WeakReference[Resize]' "$resize_registry"
grep -Fq 'nativeWidthRoots' "$plugin"
grep -Fq 'terminalName(fun) == "widthOf"' "$plugin"
grep -Fq 'private def withoutEnclosingNativeRuntimeContext' "$plugin"
grep -Fq 'if (inNativeRuntimeContext)' "$plugin"
grep -Fq 'transformIndependentNativeDefinition(definition)' "$plugin"
grep -Fq 'class GenericNativeDefinitionBoundaryTests' "$generic_test_file"

if grep -Fq 'withoutNativeStreamFifoContext' "$plugin"; then
  echo "Named-definition isolation must not use a StreamFifo-only helper" >&2
  exit 1
fi
if grep -Fq 'if inNativeStreamFifo && decoded(definition.name)' "$plugin"; then
  echo "Named-definition isolation must be generic across native contexts" >&2
  exit 1
fi

if grep -Eq 'StreamWidthAdapter|io_(down|up)(Input|Output)|widthAdapter' \
  "$plugin" "$runtime" "$registry" "$resize_registry"; then
  echo "MorphHDL native-width support must not recognize StreamWidthAdapter or emitted hardware names" >&2
  exit 1
fi

if find frontend morphruntime morphplugin morphhdl/src/main \
  -type f \( -iname '*StreamWidthAdapter*' -o -iname '*WidthAdapterReplacement*' \) \
  | grep -q .; then
  echo "MorphHDL must not contain a replacement parameterized StreamWidthAdapter component" >&2
  exit 1
fi

python3 morphhdl/scripts/check-native-source-preservation.py \
  --manifest morphhdl/contracts/native-source-preservation.json

printf 'Increment 53d native StreamWidthAdapter source boundary passed.\n'
