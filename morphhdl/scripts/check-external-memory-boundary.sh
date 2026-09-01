#!/usr/bin/env bash
set -euo pipefail

root="$(git rev-parse --show-toplevel)"
cd "$root"
baseline=8c4241396cd718a36227dcd89a2e6a29d9077f11

mem=core/src/main/scala/spinal/core/Mem.scala
memory_metadata=core/src/main/scala/spinal/core/ParameterizedMemory.scala
phase=core/src/main/scala/spinal/core/internals/PhaseVerilog.scala
core_lowerer=core/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala
morph_lowerer=morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala
registry=morphruntime/src/main/scala/spinal/core/ExternalParameterizedMemoryRegistry.scala
compiler_runtime=morphruntime/src/main/scala/spinal/core/ExternalNativeIntCompilerRuntime.scala
shadow_registry=morphruntime/src/main/scala/spinal/core/ExternalNativeIntShadowRegistry.scala
adapter=frontend/src/main/scala/morphhdl/frontend/Memory.scala
auto_provenance=frontend/src/main/scala/morphhdl/frontend/NativeMemAutoProvenance.scala
frontend_package=frontend/src/main/scala/morphhdl/frontend/package.scala
external=morphhdl/src/main/scala/spinal/core/internals/MorphHdlExternalParameterizedVerilog.scala
manifest=morphhdl/contracts/native-source-preservation.json
typed_overlay=morphhdl/contracts/typed-native-source-overlay.json
identity_tests=morphhdl/src/test/scala/spinal/core/FiniteMemIdentityAdversarialTests.scala

test "$(git hash-object "$phase")" = "$(git rev-parse "${baseline}:${phase}")"
test ! -e "$core_lowerer"
test -f "$morph_lowerer"
test -f "$registry"
test -f "$compiler_runtime"
test -f "$shadow_registry"
test -f "$adapter"
test -f "$auto_provenance"
test -f "$frontend_package"
test -f "$identity_tests"
! grep -Fq 'ParameterizedMemoryDepth' "$mem"
grep -Fq 'def apply[T <: Data](wordType: HardType[T], wordCount: Int): Mem[T] =' "$mem"
grep -Fq 'new Mem[T](wordType, wordCount)' "$mem"
grep -Fq 'private[core] val wordTypeLeaves = wordType().flatten.toVector' "$mem"
grep -Fq 'def apply[T <: Data](wordType: HardType[T], wordCount: ElabInt)' "$mem"
grep -Fq 'val depth = ParameterizedMemory.depthOf(wordCount, "typed Mem depth")' "$mem"
python3 - "$mem" <<'PY'
import pathlib
import sys

source = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
start = source.index(
    "def apply[T <: Data](wordType: HardType[T], wordCount: ElabInt)"
)
end = source.index("\n  /** Create a RAM", start)
typed_factory = source[start:end]
required = (
    "if (wordCount.isConcrete)",
    "apply[T](wordType, wordCount.witness)",
    'val depth = ParameterizedMemory.depthOf(wordCount, "typed Mem depth")',
    "val memory: Mem[T] = apply[T](wordType, depth.value)",
    "ParameterizedMemory.attach[T](memory, depth)",
)
missing = [token for token in required if typed_factory.count(token) != 1]
if missing:
    raise SystemExit(
        "typed Mem factory lost exact ordinary-factory delegation: "
        + ", ".join(missing)
    )
if "new Mem(" in typed_factory:
    raise SystemExit(
        "typed Mem factory bypasses the authoritative ordinary Int factory"
    )
if "attachStatic" in typed_factory:
    raise SystemExit(
        "typed literal Mem factory attaches symbolic graph metadata"
    )
PY
test "$(grep -Fc 'wordCount: ElabInt' "$mem")" = 1
! grep -Fq 'attachStatic' "$memory_metadata"
for token in \
  'private def validateCompleteFiniteExpression(' \
  'ElabInt.requireAuthoritativeIntegerDomain(' \
  'requireExactExtrema = true' \
  'legacy native-Int shadow evidence are never sufficient' \
  'private def elementExpressionsOf(' \
  'memory.wordTypeLeaves.map' \
  'SPINAL-PARAMETERIZED-VERILOG-MEMORY-ELEMENT-METADATA-CONFLICT'; do
  grep -Fq "$token" "$registry"
done
grep -Fq 'ExternalParameterizedMemoryRegistry.attach(' "$compiler_runtime"
for forbidden in \
  'attachCompilerProven' \
  'allowCompilerEvidence' \
  'ExternalNativeIntShadowRegistry' \
  'certifyDefinitionExpression'; do
  ! grep -Fq "$forbidden" "$registry"
done
! grep -Fq 'attachCompilerProven' "$compiler_runtime"
! grep -Fq 'certifyDefinitionExpression' "$shadow_registry"
grep -Fq 'def checked(' "$memory_metadata"
grep -Fq 'depth.authoritativeProjectedExpression(' "$memory_metadata"
grep -Fq 'SPINAL-ELAB-INT-MEMORY-DEPTH-DOMAIN-INVALID' "$memory_metadata"
grep -Fq 'requireProjectedExactExtrema = true' "$memory_metadata"
for token in \
  'external memory registry rejects forged inexact public depth metadata' \
  'external memory registry accepts only canonical public literal depth metadata' \
  'external memory registry rejects foreign depth roots and inexact element geometry' \
  'external memory registry preserves literal and valid exact symbolic construction' \
  'public typed Mem rejects an equal-but-foreign raw exact schema' \
  'copied address-width algebra cannot replace same-root exhaustive capacity proof' \
  'UNDECLARED_MEMORY_DEPTH' \
  'generateIndex = Some("forged_generate_index")'; do
  grep -Fq "$token" "$identity_tests"
done
! grep -Fq 'compact(width.verilog) == compact(s"clog2(${depth.verilog}, 1)")' \
  "$morph_lowerer"
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
