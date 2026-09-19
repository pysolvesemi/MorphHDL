#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

python3 morphhdl/scripts/check-production-retirement.py
bash morphhdl/scripts/check-typed-vec-boundary.sh
for required in \
  core/src/main/scala/spinal/core/ElabInt.scala \
  core/src/main/scala/spinal/core/ElaborationExactDomain.scala \
  morphruntime/src/main/scala/spinal/core/ElabControl.scala \
  morphruntime/src/main/scala/spinal/core/ElabFiniteRange.scala \
  morphruntime/src/main/scala/spinal/core/ElabFormalComponent.scala \
  morphruntime/src/main/scala/spinal/core/ElabValue.scala \
  morphruntime/src/main/scala/spinal/core/ParameterizedStructure.scala \
  morphplugin/src/main/scala/morphhdl/compiler/MorphHdlTypedElaborationControlComponent.scala; do
  test -f "$required"
done
grep -Fq 'final class ElabInt' core/src/main/scala/spinal/core/ElabInt.scala
grep -Fq 'final class ElabBool' core/src/main/scala/spinal/core/ElabInt.scala
grep -Fq 'object ElabFiniteRange' morphruntime/src/main/scala/spinal/core/ElabFiniteRange.scala
grep -Fq 'object ElabFormalComponent' morphruntime/src/main/scala/spinal/core/ElabFormalComponent.scala
printf 'Increment 53f typed primitive retirement boundary passed.\n'
