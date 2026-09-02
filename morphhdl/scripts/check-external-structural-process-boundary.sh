#!/usr/bin/env bash
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cd "$root"

for path in \
  core/src/main/scala/spinal/core/ParameterizedStructure.scala \
  core/src/main/scala/spinal/core/ParameterizedProcess.scala \
  core/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala \
  core/src/main/scala/spinal/core/internals/ParameterizedVerilogProcesses.scala
do
  test ! -e "$path"
done

for path in \
  morphruntime/src/main/scala/spinal/core/ParameterizedStructure.scala \
  morphruntime/src/main/scala/spinal/core/ParameterizedProcess.scala \
  morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala \
  morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogProcesses.scala \
  morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala
do
  test -f "$path"
done

test ! -e frontend/src/main/scala/spinal/core/ParameterizedProcess.scala

width=core/src/main/scala/spinal/core/ParameterizedWidth.scala
structure=morphruntime/src/main/scala/spinal/core/ParameterizedStructure.scala
phase=core/src/main/scala/spinal/core/internals/PhaseVerilog.scala
external=morphhdl/src/main/scala/spinal/core/internals/MorphHdlExternalParameterizedVerilog.scala

test "$(grep -Fc 'final case class ElaborationIntegerExpression(' "$width")" = 1
test "$(grep -Fc 'final case class ElaborationBooleanExpression(' "$width")" = 1
! grep -Fq 'final case class ElaborationIntegerExpression(' "$structure"
! grep -Fq 'ParameterizedVerilogProcesses' "$phase"
! grep -Fq 'ParameterizedVerilogStructural' "$phase"
! grep -Fq 'ParameterizedVerilogMemories' "$phase"
grep -Fq 'ParameterizedVerilogMemories.rewrite' "$external"
grep -Fq 'ParameterizedVerilogProcesses.rewrite' "$external"
grep -Fq 'ParameterizedVerilogStructural.rewrite' "$external"
grep -Fq 'requiresPublicationRewrite' "$external"

python3 - <<'PY2'
import json
from pathlib import Path

manifest = json.loads(Path("morphhdl/contracts/native-source-preservation.json").read_text())
paths = {entry["path"] for entry in manifest["entries"]}
removed = {
    "core/src/main/scala/spinal/core/ParameterizedStructure.scala",
    "core/src/main/scala/spinal/core/ParameterizedProcess.scala",
    "core/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala",
    "core/src/main/scala/spinal/core/internals/ParameterizedVerilogProcesses.scala",
    "core/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala",
}
remaining = sorted(paths.intersection(removed))
if remaining:
    raise SystemExit("relocated native-tree entries remain in manifest: " + ", ".join(remaining))
PY2

echo "External structural/process ownership boundary is valid"
