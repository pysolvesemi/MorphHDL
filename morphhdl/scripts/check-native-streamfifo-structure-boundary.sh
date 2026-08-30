#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

native_fifo="lib/src/main/scala/spinal/lib/Stream.scala"
frontend_fifo="frontend/src/main/scala/morphhdl/frontend/Library.scala"
compiler_plugin="morphplugin/src/main/scala/morphhdl/compiler"
legacy_plugin="morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeIntShadowExpressionComponent.scala"
fifo_tests="morphhdl/src/test/scala/morphhdl/ParameterizedStreamFifoDepthTests.scala"

# No MorphHDL-authored FIFO or emitted-name recovery may replace the native body.
[[ ! -e lib/src/main/scala/spinal/lib/ParameterizedStreamFifoDepth.scala ]]
[[ ! -e frontend/src/main/scala/spinal/lib/ExternalParameterizedStreamFifoDepthRegistry.scala ]]
! grep -R --line-number --fixed-strings 'rewriteParameterizedStreamFifoDepth' \
  morphhdl/src/main/scala frontend/src/main/scala morphruntime/src/main/scala lib/src/main/scala
! grep -R --line-number --fixed-strings 'fromParameterizedMemoryDepth' \
  frontend/src/main/scala
! grep -R --line-number -E \
  'io_push_(valid|ready|payload)|io_pop_(valid|ready|payload)|io_occupancy|io_availability' \
  morphhdl/src/main/scala/spinal/core/internals
! grep -q -E 'NativeHardwareNames|looksNativeHardware' \
  "$legacy_plugin"
! grep -q -E '"(push|pop|popOnIo|occupancy|availability|full|empty|addressGen|readArbitration|readPort|io)"' \
  "$legacy_plugin"

# Increment 53e must not revive the superseded native-Int FIFO boundary.
for fifo_source in "$native_fifo" "$frontend_fifo" "$fifo_tests"; do
  ! grep -q -E \
    'NativeIntShadow|ExternalNativeIntFormalComponent|ParameterizedMemoryDepth' \
    "$fifo_source"
done
! grep -R -q -E 'StreamFifo|Stream\.scala' "$compiler_plugin"

# The public entry and authoritative native body carry one exact typed depth.
grep -q 'depth: ElabInt' "$native_fifo"
grep -q 'ElabFormalComponent.parameter' "$native_fifo"
grep -q 'actual = depth' "$native_fifo"
grep -q 'name = "DEPTH"' "$native_fifo"
grep -q 'minimum = BigInt(1)' "$native_fifo"
grep -q 'maximum = depth.maximum' "$native_fifo"
grep -q 'private\[lib\] val elabDepth: ElabInt' "$native_fifo"
grep -q 'val depth: Int = elabDepth.witness' "$native_fifo"
grep -q 'ElabInt.literal(depth)' "$native_fifo"
grep -q 'require(elabDepth >= 0)' "$native_fifo"
grep -q 'val elabWithExtraMsb: ElabBool = elabDepth.isPow2 && allowExtraMsb' "$native_fifo"
grep -q 'val withExtraMsb: Boolean = elabWithExtraMsb.witness' "$native_fifo"
grep -q 'val depthIsOne: ElabBool = elabDepth == 1' "$native_fifo"
grep -q 'val depthHasStorage: ElabBool = elabDepth > 1' "$native_fifo"
grep -q 'val oneStage = depthIsOne generate new Area' "$native_fifo"
grep -q 'val logic = depthHasStorage generate new Area' "$native_fifo"
grep -q 'Mem(dataType, elabDepth)' "$native_fifo"
grep -q 'finiteRangeFromZero("StreamFifo formal RAM range")' "$native_fifo"
grep -q 'SPINAL-ELAB-STREAMFIFO-FORMAL-SYMBOLIC-DEPTH-UNSUPPORTED' "$native_fifo"
grep -q 'requireConcreteFormalDepth("StreamFifo.formalFullToEmpty")' "$native_fifo"
grep -q 'spinal.lib.StreamFifo(dataType, depth.asElabInt)' "$frontend_fifo"

# Preserve the reviewed native pointer algorithm and direct native test target.
grep -q 'push := (push + 1).resized' "$native_fifo"
grep -q 'pop := (pop + 1).resized' "$native_fifo"
grep -q 'push := U(0).resized' "$native_fifo"
grep -q 'pop := U(0).resized' "$native_fifo"
grep -q 'val fifo = spinal.lib.StreamFifo' "$fifo_tests"
grep -q 'depth.asElabInt' "$fifo_tests"
! grep -q 'MorphStreamFifo' "$fifo_tests"

python3 morphhdl/scripts/check-native-source-preservation.py \
  --manifest morphhdl/contracts/native-source-preservation.json

printf 'Increment 53e typed native StreamFifo source boundary passed.\n'
