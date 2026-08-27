#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

[[ ! -e lib/src/main/scala/spinal/lib/ParameterizedStreamFifoDepth.scala ]]
[[ ! -e frontend/src/main/scala/spinal/lib/ExternalParameterizedStreamFifoDepthRegistry.scala ]]
! grep -R --line-number --fixed-strings 'rewriteParameterizedStreamFifoDepth'   morphhdl/src/main/scala frontend/src/main/scala morphruntime/src/main/scala lib/src/main/scala
! grep -R --line-number --fixed-strings 'fromParameterizedMemoryDepth'   frontend/src/main/scala
! grep -R --line-number -E   'io_push_(valid|ready|payload)|io_pop_(valid|ready|payload)|io_occupancy|io_availability'   morphhdl/src/main/scala/spinal/core/internals

grep -q 'depth: ParameterizedMemoryDepth'   lib/src/main/scala/spinal/lib/Stream.scala
grep -q 'ExternalNativeIntFormalComponent.parameter'   lib/src/main/scala/spinal/lib/Stream.scala
grep -q 'minimum = depth.expression.minimum'   lib/src/main/scala/spinal/lib/Stream.scala
grep -q 'maximum = depth.expression.maximum'   lib/src/main/scala/spinal/lib/Stream.scala
! grep -q 'maximum = BigInt(4096)'   lib/src/main/scala/spinal/lib/Stream.scala
grep -q 'push := (push + 1).resized'   lib/src/main/scala/spinal/lib/Stream.scala
grep -q 'pop := (pop + 1).resized'   lib/src/main/scala/spinal/lib/Stream.scala
grep -q 'push := U(0).resized'   lib/src/main/scala/spinal/lib/Stream.scala
grep -q 'pop := U(0).resized'   lib/src/main/scala/spinal/lib/Stream.scala
grep -q 'object ExternalNativeIntFormalComponent'   morphruntime/src/main/scala/spinal/core/ExternalNativeIntFormalComponent.scala
grep -q 'val fifo = spinal.lib.StreamFifo'   morphhdl/src/test/scala/morphhdl/ParameterizedStreamFifoDepthTests.scala
! grep -q 'MorphStreamFifo'   morphhdl/src/test/scala/morphhdl/ParameterizedStreamFifoDepthTests.scala

python3 morphhdl/scripts/check-native-source-preservation.py   --manifest morphhdl/contracts/native-source-preservation.json

printf 'Increment 53 native StreamFifo source boundary passed.\n'
