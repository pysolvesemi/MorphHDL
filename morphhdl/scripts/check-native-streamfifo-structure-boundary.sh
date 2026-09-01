#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

python3 morphhdl/scripts/check-production-retirement.py
native_fifo=lib/src/main/scala/spinal/lib/Stream.scala
fifo_tests=morphhdl/src/test/scala/morphhdl/ParameterizedStreamFifoDepthTests.scala
formal_tests=morphhdl/src/test/scala/morphhdl/NativeStreamFifoFormalEquivalenceTests.scala
for required in "$native_fifo" "$fifo_tests" "$formal_tests"; do
  test -f "$required"
done
grep -Fq 'depth: ElabInt' "$native_fifo"
grep -Fq 'private[lib] val elabDepth: ElabInt' "$native_fifo"
grep -Fq 'Mem(dataType, elabDepth)' "$native_fifo"
grep -Fq 'push := (push + 1).resized' "$native_fifo"
grep -Fq 'pop := (pop + 1).resized' "$native_fifo"
grep -Fq 'class ParameterizedStreamFifoDepthTests' "$fifo_tests"
grep -Fq 'class NativeStreamFifoFormalEquivalenceTests' "$formal_tests"
! grep -R -q -E 'StreamFifo|Stream\.scala'   morphplugin/src/main/scala/morphhdl/compiler
printf 'Increment 53e typed StreamFifo retirement boundary passed.\n'
