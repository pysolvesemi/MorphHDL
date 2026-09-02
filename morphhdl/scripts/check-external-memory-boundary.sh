#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

python3 morphhdl/scripts/check-production-retirement.py
mem=core/src/main/scala/spinal/core/Mem.scala
metadata=core/src/main/scala/spinal/core/ParameterizedMemory.scala
lowerer=morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala
identity_tests=morphhdl/src/test/scala/spinal/core/FiniteMemIdentityAdversarialTests.scala
frontend_package=frontend/src/main/scala/morphhdl/frontend/package.scala
frontend_library=frontend/src/main/scala/morphhdl/frontend/Library.scala
backend=morphhdl/src/main/scala/spinal/core/internals/MorphHdlExternalParameterizedVerilog.scala
retired_registry=morphruntime/src/main/scala/spinal/core/ExternalParameterizedMemoryRegistry.scala
retired_shape=frontend/src/main/scala/spinal/core/ExternalParameterizedHardTypeShape.scala
for required in "$mem" "$metadata" "$lowerer" "$identity_tests" \
  "$frontend_package" "$frontend_library" "$backend"; do
  test -f "$required"
done
test ! -e "$retired_registry"
test ! -e "$retired_shape"
grep -Fq 'def apply[T <: Data](wordType: HardType[T], wordCount: ElabInt)' "$mem"
grep -Fq 'ParameterizedMemory.attach[T](memory, depth)' "$mem"
grep -Fq 'private[core] def discover(component: Component)' "$metadata"
grep -Fq 'ElabInt.requireAuthoritativeIntegerDomain(' "$metadata"
grep -Fq 'ParameterizedWidth.HardType(dataType)' "$frontend_package"
grep -Fq 'apply(ParameterizedWidth.HardType(payloadType))' "$frontend_library"
grep -Fq 'components.foreach(ParameterizedMemory.discover)' "$backend"
grep -Fq 'ParameterizedVerilogMemories.rewrite' "$backend"
grep -Fq 'canonical memory discovery tags instantiated native HardType geometry once' \
  "$identity_tests"
if grep -R -n -E \
  'ExternalParameterizedMemoryRegistry|ExternalParameterizedHardTypeRegistry|ExternalParameterizedHardTypeShape' \
  core/src/main frontend/src/main morphruntime/src/main morphhdl/src/main; then
  printf 'obsolete external HardType or memory registry remains in production source\n' >&2
  exit 1
fi
printf 'Increment 54 canonical typed native-memory boundary passed.\n'
