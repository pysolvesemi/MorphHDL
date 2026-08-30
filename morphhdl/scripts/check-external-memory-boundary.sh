#!/usr/bin/env bash
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cd "$root"
baseline=8c4241396cd718a36227dcd89a2e6a29d9077f11

mem=core/src/main/scala/spinal/core/Mem.scala
phase=core/src/main/scala/spinal/core/internals/PhaseVerilog.scala
core_lowerer=core/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala
morph_lowerer=morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala
registry=morphruntime/src/main/scala/spinal/core/ExternalParameterizedMemoryRegistry.scala
adapter=frontend/src/main/scala/morphhdl/frontend/Memory.scala
auto_provenance=frontend/src/main/scala/morphhdl/frontend/NativeMemAutoProvenance.scala
frontend_package=frontend/src/main/scala/morphhdl/frontend/package.scala
external=morphhdl/src/main/scala/spinal/core/internals/MorphHdlExternalParameterizedVerilog.scala
manifest=morphhdl/contracts/native-source-preservation.json
typed_overlay=morphhdl/contracts/typed-native-source-overlay.json

test "$(git hash-object "$phase")" = "$(git rev-parse "${baseline}:${phase}")"
test ! -e "$core_lowerer"
test -f "$morph_lowerer"
test -f "$registry"
test -f "$adapter"
test -f "$auto_provenance"
test -f "$frontend_package"
! grep -Fq 'ParameterizedMemoryDepth' "$mem"
grep -Fq 'def apply[T <: Data](wordType: HardType[T], wordCount: Int) = new Mem(wordType, wordCount)' "$mem"
grep -Fq 'def apply[T <: Data](wordType: HardType[T], wordCount: ElabInt)' "$mem"
grep -Fq 'val depth = ParameterizedMemory.depthOf(wordCount, "typed Mem depth")' "$mem"
grep -Fq 'ParameterizedMemory.attach(new Mem(wordType, depth.value), depth)' "$mem"
test "$(grep -Fc 'wordCount: ElabInt' "$mem")" = 1
! grep -Fq 'ParameterizedVerilogMemories' "$phase"
grep -Fq 'ExternalParameterizedMemoryRegistry.discover' "$external"
grep -Fq 'ParameterizedVerilogMemories.rewrite' "$external"
grep -Fq 'ExternalParameterizedMemoryRegistry.create' "$adapter"
grep -Fq 'implicit final class NativeMemFactoryOps' "$frontend_package"
grep -Fq 'NativeMemAutoProvenance.create' "$frontend_package"
grep -Fq 'ExternalParameterizedMemoryRegistry.attach' "$auto_provenance"
grep -Fq 'System.identityHashCode' "$auto_provenance"
grep -Fq 'NativeMemIdentityReference' "$auto_provenance"

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

auto = Path("frontend/src/main/scala/morphhdl/frontend/NativeMemAutoProvenance.scala").read_text()
for forbidden in (
    "HashMap.empty[Int",
    "HashMap.empty[BigInt",
    "Map[Int,",
    "Map[BigInt,",
    "groupBy(_.value)",
    "find(_.value",
    "find(_._2.value",
):
    if forbidden in auto:
        raise SystemExit(
            f"automatic native-memory provenance contains concrete-value lookup: {forbidden}"
        )
if "the concrete witness is deliberately not part of the token" not in auto.lower():
    raise SystemExit("automatic native-memory token must explicitly exclude the witness")

manifest = json.loads(Path("morphhdl/contracts/native-source-preservation.json").read_text())
paths = {entry["path"] for entry in manifest["entries"]}
for path in (
    "core/src/main/scala/spinal/core/Mem.scala",
    "core/src/main/scala/spinal/core/internals/PhaseVerilog.scala",
    "core/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala",
):
    if path in paths:
        raise SystemExit(f"restored/relocated native path remains in manifest: {path}")

overlay = json.loads(Path("morphhdl/contracts/typed-native-source-overlay.json").read_text())
entries = {entry["path"]: entry for entry in overlay["entries"]}
mem = entries.get("core/src/main/scala/spinal/core/Mem.scala")
if mem is None:
    raise SystemExit("typed native overlay does not approve Mem.scala")
if mem["change"] != "modified" or mem["classification"] != "typed-formal-or-overload":
    raise SystemExit("typed native overlay gives Mem.scala the wrong reviewed classification")
PY

python3 morphhdl/scripts/check-native-source-preservation.py

echo "External native-memory ownership boundary is valid"
