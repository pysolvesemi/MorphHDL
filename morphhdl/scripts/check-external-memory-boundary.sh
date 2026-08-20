#!/usr/bin/env bash
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cd "$root"
baseline=8c4241396cd718a36227dcd89a2e6a29d9077f11

mem=core/src/main/scala/spinal/core/Mem.scala
phase=core/src/main/scala/spinal/core/internals/PhaseVerilog.scala
core_lowerer=core/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala
morph_lowerer=morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala
registry=frontend/src/main/scala/spinal/core/ExternalParameterizedMemoryRegistry.scala
adapter=frontend/src/main/scala/morphhdl/frontend/Memory.scala
external=morphhdl/src/main/scala/spinal/core/internals/MorphHdlExternalParameterizedVerilog.scala
manifest=morphhdl/contracts/native-source-preservation.json

test "$(git hash-object "$mem")" = "$(git rev-parse "${baseline}:${mem}")"
test "$(git hash-object "$phase")" = "$(git rev-parse "${baseline}:${phase}")"
test ! -e "$core_lowerer"
test -f "$morph_lowerer"
test -f "$registry"
test -f "$adapter"
! grep -Fq 'ParameterizedMemoryDepth' "$mem"
! grep -Fq 'ParameterizedMemory.attach' "$mem"
! grep -Fq 'ParameterizedVerilogMemories' "$phase"
grep -Fq 'ExternalParameterizedMemoryRegistry.discover' "$external"
grep -Fq 'ParameterizedVerilogMemories.rewrite' "$external"
grep -Fq 'ExternalParameterizedMemoryRegistry.create' "$adapter"

python3 - <<'PY'
import json
from pathlib import Path

external = Path("morphhdl/src/main/scala/spinal/core/internals/MorphHdlExternalParameterizedVerilog.scala").read_text()
order = [
    external.index("ExternalParameterizedMemoryRegistry.discover"),
    external.index("ParameterizedVerilogMemories.rewrite"),
    external.index("ParameterizedVerilogProcesses.rewrite"),
    external.index("ParameterizedVerilogStructural.rewrite"),
    external.index("ExternalParameterizedVerilogNativeFallback.rewrite"),
]
if order != sorted(order):
    raise SystemExit("external memory/process/structure/expression publication order is invalid")

manifest = json.loads(Path("morphhdl/contracts/native-source-preservation.json").read_text())
paths = {entry["path"] for entry in manifest["entries"]}
for path in (
    "core/src/main/scala/spinal/core/Mem.scala",
    "core/src/main/scala/spinal/core/internals/PhaseVerilog.scala",
    "core/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala",
):
    if path in paths:
        raise SystemExit(f"restored/relocated native path remains in manifest: {path}")
PY

python3 morphhdl/scripts/check-native-source-preservation.py

echo "External native-memory ownership boundary is valid"
