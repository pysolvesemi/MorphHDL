#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

python3 morphhdl/scripts/check-production-retirement.py
stream=lib/src/main/scala/spinal/lib/Stream.scala
test_file=morphhdl/src/test/scala/morphhdl/ParameterizedStreamWidthAdapterTests.scala
test -f core/src/main/scala/spinal/core/ElabInt.scala
test -f "$stream"
test -f "$test_file"
grep -Fq 'val inputWidth: ElabInt = widthOfExpr(input.payload)' "$stream"
grep -Fq 'val outputWidth: ElabInt = widthOfExpr(output.payload)' "$stream"
grep -Fq 'ElabInt.requireSingleSymbolicRoot' "$stream"
grep -Fq 'class ParameterizedStreamWidthAdapterTests' "$test_file"
if find frontend morphruntime morphplugin morphhdl/src/main   -type f \( -iname '*StreamWidthAdapter*' -o -iname '*WidthAdapterReplacement*' \)   | grep -q .; then
  echo "MorphHDL must not contain a replacement StreamWidthAdapter component" >&2
  exit 1
fi
printf 'Increment 53d typed StreamWidthAdapter retirement boundary passed.\n'
