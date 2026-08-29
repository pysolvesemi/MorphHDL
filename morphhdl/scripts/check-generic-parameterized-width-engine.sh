#!/usr/bin/env bash
set -euo pipefail
files=(
  morphruntime/src/main/scala/spinal/core/ExternalNativeIntShadowRegistry.scala
  morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedWidthDomainProof.scala
  morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala
  morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedAutoResize.scala
  morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala
)
for file in "${files[@]}"; do
  if grep -En 'StreamFifo(CC)?|BufferCC|pushToPopGray|popToPushGray' "$file"; then
    echo "component-specific recognition leaked into generic parameterization engine: $file" >&2
    exit 1
  fi
done
