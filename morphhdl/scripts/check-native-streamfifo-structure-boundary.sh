#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

[[ ! -e lib/src/main/scala/spinal/lib/ParameterizedStreamFifoDepth.scala ]]
! grep -R --line-number --fixed-strings 'rewriteParameterizedStreamFifoDepth' \
  morphhdl/src/main/scala frontend/src/main/scala morphruntime/src/main/scala lib/src/main/scala
! grep -R --line-number --fixed-strings 'io_push_valid") &&' \
  morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala

grep -q 'ExternalNativeIntCompilerRuntime' \
  morphruntime/src/main/scala/spinal/core/ExternalNativeIntCompilerRuntime.scala
grep -q 'ExternalParameterizedValueRegistry' \
  morphruntime/src/main/scala/spinal/core/ExternalParameterizedValueRegistry.scala
grep -q '/lib/src/main/scala/spinal/lib/Stream.scala' \
  morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeIntShadowExpressionComponent.scala
grep -q 'formalComponent' \
  frontend/src/main/scala/spinal/lib/ExternalParameterizedStreamFifoDepthRegistry.scala

base_ref="$(sed -n 's/^ref=//p' morphhdl/upstream-base.conf)"
git diff --exit-code "$base_ref" -- lib/src/main/scala/spinal/lib/Stream.scala
bash morphhdl/scripts/check-native-source-guard.sh

printf 'Increment 53 native StreamFifo source boundary passed.\n'
