#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

python3 morphhdl/scripts/check-production-retirement.py
mem=core/src/main/scala/spinal/core/Mem.scala
metadata=core/src/main/scala/spinal/core/ParameterizedMemory.scala
registry=morphruntime/src/main/scala/spinal/core/ExternalParameterizedMemoryRegistry.scala
lowerer=morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala
identity_tests=morphhdl/src/test/scala/spinal/core/FiniteMemIdentityAdversarialTests.scala
for required in "$mem" "$metadata" "$registry" "$lowerer" "$identity_tests"; do
  test -f "$required"
done
grep -Fq 'def apply[T <: Data](wordType: HardType[T], wordCount: ElabInt)' "$mem"
grep -Fq 'ParameterizedMemory.attach[T](memory, depth)' "$mem"
grep -Fq 'ElabInt.requireAuthoritativeIntegerDomain(' "$registry"
grep -Fq 'ExternalParameterizedMemoryRegistry.discover'   morphhdl/src/main/scala/spinal/core/internals/MorphHdlExternalParameterizedVerilog.scala
grep -Fq 'ParameterizedVerilogMemories.rewrite'   morphhdl/src/main/scala/spinal/core/internals/MorphHdlExternalParameterizedVerilog.scala
grep -Fq 'external memory registry rejects forged inexact public depth metadata'   "$identity_tests"
printf 'Increment 53e typed native-memory retirement boundary passed.\n'
