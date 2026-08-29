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
doc_file="docs/morphhdl/increment-53d-native-streamwidth-adapter.md"

for required in "$plugin" "$runtime" "$registry" "$resize_registry" "$test_file" "$doc_file"; do
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
grep -Fq 'val upstreamSpinalComponentSource' "$plugin"
grep -Fq '/core/src/main/scala/spinal/' "$plugin"
grep -Fq '/lib/src/main/scala/spinal/' "$plugin"
grep -Fq 'private def inNativeRewriteContext' "$plugin"
grep -Fq 'private def withoutEnclosingNativeRuntimeContext' "$plugin"
grep -Fq 'transformIndependentNativeDefinition(definition)' "$plugin"
grep -Fq 'private def nativeWidthRootAvailableAtEntry' "$plugin"
grep -Fq 'case Bind(name: TermName, body) =>' "$plugin"
grep -Fq 'val (rootSequence, transformedRhs) = withScope' "$plugin"
grep -Fq 'roots.map(root => super.transform(root).duplicate).toList' "$plugin"
grep -Fq 'val transformedBody = super.transform(definition.rhs)' "$plugin"
grep -Fq 'withScope(super.transform(definition))' "$plugin"
grep -Fq 'List(transformedRhs)' "$plugin"
if grep -Fq 'super.transform(definition)).asInstanceOf[DefDef]' "$plugin"; then
  echo "Native method transformation must preserve original DefDef parameter symbols" >&2
  exit 1
fi

if grep -Fq 'withoutNativeStreamFifoContext' "$plugin"; then
  echo "Named-definition isolation must not use a StreamFifo-only helper" >&2
  exit 1
fi
if grep -Fq 'if inNativeStreamFifo && decoded(definition.name)' "$plugin"; then
  echo "Named-definition isolation must be component-independent" >&2
  exit 1
fi
if grep -Fq 'normalizedPath.endsWith("/lib/src/main/scala/spinal/lib/Stream.scala")' "$plugin"; then
  echo "Native instrumentation eligibility must not be limited to Stream.scala" >&2
  exit 1
fi
if grep -Fq 'MORPHDL-NATIVE-WIDTH-FUNCTION-ROOT-UNSTABLE' "$plugin"; then
  echo "Unrelated concrete widthOf calls must not fail during candidate discovery" >&2
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
