#!/usr/bin/env bash
set -euo pipefail
file=morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeIntShadowExpressionComponent.scala
if grep -En 'StreamFifo(CC)?|BufferCC|Stream\.scala|CrossClock\.scala' "$file"; then
  echo "component/file-specific native compiler selection remains" >&2
  exit 1
fi
for required in   nativeSpinalProductionSource   hasSingleNativeIntegerConstructorParameter   hasNativeShapeConstructorParameter   nativeIntegerConstructorParameter
 do
  grep -q "$required" "$file"
 done
