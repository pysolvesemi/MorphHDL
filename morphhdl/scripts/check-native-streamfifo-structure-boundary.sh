#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

native_fifo="lib/src/main/scala/spinal/lib/Stream.scala"
frontend_fifo="frontend/src/main/scala/morphhdl/frontend/Library.scala"
compiler_plugin="morphplugin/src/main/scala/morphhdl/compiler"
legacy_plugin="morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeIntShadowExpressionComponent.scala"
fifo_tests="morphhdl/src/test/scala/morphhdl/ParameterizedStreamFifoDepthTests.scala"
formal_helper_tests="morphhdl/src/test/scala/morphhdl/NativeStreamFifoFormalEquivalenceTests.scala"
finite_range="morphruntime/src/main/scala/spinal/core/ElabFiniteRange.scala"
structure="morphruntime/src/main/scala/spinal/core/ParameterizedStructure.scala"
finite_bits_tests="morphhdl/src/test/scala/spinal/core/FiniteBitsIndexTests.scala"

for required in \
  "$native_fifo" "$frontend_fifo" "$fifo_tests" "$formal_helper_tests" \
  "$finite_range" "$structure" "$finite_bits_tests"; do
  test -f "$required"
done

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
for token in \
  'formalOneStageOwner: ParameterizedStructuralOwner' \
  'formalStorageOwner: ParameterizedStructuralOwner' \
  'ParameterizedStructure.currentOwner' \
  'ParameterizedStructure.requireOwnerCoverage' \
  'ParameterizedStructure.captureInto' \
  'private sealed trait FormalHelperAdapter' \
  'private val concreteFormalHelperAdapter' \
  'private val capturedFormalHelperAdapter' \
  'private def formalCheckLastPushAlgorithm' \
  'private def formalCheckRamAlgorithm' \
  'private def formalContainsAlgorithm' \
  'private def formalCountAlgorithm' \
  'private def formalFullToEmptyAlgorithm(empty: Bool): Bool' \
  'private def publishCapturedFormalChecks(checks: Vec[Bool]): Vec[Bool]' \
  'ElabFiniteRange.foreach(elabDepth' \
  'ElabFiniteRange.reduceOr(checks.asBits, elabDepth)' \
  'ElabFiniteRange.countOne(checks.asBits, elabDepth)(CountOne(checks))' \
  'type Mask = Bits' \
  'index(target) := combine(index(mask), index(condition))' \
  'valid & predicate' \
  'condition.read(normalizedIndex(index, stableName))' \
  'val reachedEmpty = Bool().setName("formal_full_to_empty")' \
  'cover(reachedEmpty)' \
  'SPINAL-ELAB-STREAMFIFO-FORMAL-DEPTH-DOMAIN-NONPOSITIVE'; do
  grep -Fq "$token" "$native_fifo"
done
for forbidden in \
  'SPINAL-ELAB-STREAMFIFO-FORMAL-SYMBOLIC-DEPTH-UNSUPPORTED' \
  'requireConcreteFormalDepth(' \
  'symbolicFormalCheck' \
  'CapturedFormalMask' \
  'formal_ram_mask_view' \
  'outerMask' \
  'finiteRangeFromZero("StreamFifo formal RAM range")'; do
  ! grep -Fq "$forbidden" "$native_fifo"
done

python3 - "$native_fifo" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1]).read_text(encoding="utf-8")
algorithm_start = source.index("  private def formalStorageConditions[")
algorithm_end = source.index("  private def capturedFormalResult[", algorithm_start)
algorithms = source[algorithm_start:algorithm_end]

for label, token in {
    "storage predicate application": "cond(adapter.storagePayload(index))",
    "last-push selection": "val lastPushIndex = adapter.previousStorageIndex(",
    "ordered RAM predicate": "when(popIndex < pushIndex)",
    "wrapped RAM predicate": "elsewhen(popIndex > pushIndex)",
    "empty RAM predicate": "elsewhen(formalStorageEmpty)",
    "ordered RAM mask": "pushMask & popMask",
    "wrapped RAM mask": "pushMask | popMask",
    "RAM validity predicate": "valid & predicate",
    "contains reduction": "adapter.reduceOr(checks) || formalCheckOutputStage(cond)",
    "count reduction": "val count = storageCount + outputCount",
    "full-to-empty register": "val was_full = RegInit(False) setWhen (!io.push.ready)",
    "full-to-empty predicate": "was_full && empty",
}.items():
    count = source.count(token)
    if count != 1 or algorithms.count(token) != 1:
        raise SystemExit(
            f"native StreamFifo {label} must have one shared source; found source={count}, body={algorithms.count(token)}"
        )

if source.count("formalFullToEmptyAlgorithm(") != 4:
    raise SystemExit(
        "native StreamFifo full-to-empty shared algorithm must have one definition and exactly three owner-mechanics calls"
    )
if "condition.asBits(normalizedIndex(index, stableName))" in source:
    raise SystemExit(
        "captured last-push selection must remain one owner-local typed Vec read"
    )

publication_start = source.index(
    "  private def publishCapturedFormalChecks(checks: Vec[Bool]): Vec[Bool]"
)
publication_end = source.index("\n  }", publication_start) + len("\n  }")
publication = source[publication_start:publication_end]
for token in (
    "val callerDepth = ElabFormalComponent",
    ".parentActualAndRefreshVecFormals(this)",
    ".getOrElse(elabDepth)",
    "val result = Vec(Bool(), callerDepth)",
    "result := checks",
):
    if source.count(token) != 1 or publication.count(token) != 1:
        raise SystemExit(
            f"captured formal RAM publication must retain one exact caller-actual typed Vec bridge: {token}"
        )
if "cloneOf(checks)" in publication:
    raise SystemExit(
        "captured formal RAM publication must not clone the child-owned typed Vec root"
    )
if source.count("publishCapturedFormalChecks(pulled)") != 1:
    raise SystemExit(
        "captured formal RAM publication bridge must be used exactly once on the symbolic path"
    )
if any(token in publication for token in ("valid & predicate", "when(popIndex", "formalStorageEmpty")):
    raise SystemExit(
        "captured formal RAM publication mechanics must not duplicate predicates or mask decisions"
    )

adapter_start = source.index("  private sealed trait FormalHelperAdapter")
adapter_end = source.index("  private def formalStorageConditions[", adapter_start)
adapters = source[adapter_start:adapter_end]
for token in (
    "override def previousStorageIndex(",
    "override def conditions(",
    "override def boolBranches(",
    "override def ramBranches(",
    "override def checkedRam(",
    "override def newMask(",
    "override def reduceOr(",
    "override def countOne(",
    "override def growCountOperand(",
    "override def countResult(",
):
    if adapters.count(token) != 2:
        raise SystemExit(
            f"StreamFifo formal mechanics seam must have exactly two adapter implementations for {token}; found {adapters.count(token)}"
        )
PY

for token in \
  'def apply(source: Bits): Bool' \
  'ParameterizedStructure.recordFiniteIndexSlice(' \
  'private[core] def recordFiniteIndexSlice(' \
  'SPINAL-ELAB-FINITE-INDEX-BITS-SOURCE-WIDTH-MISSING' \
  'SPINAL-ELAB-FINITE-INDEX-BITS-EXACT-DOMAIN-REQUIRED' \
  'SPINAL-ELAB-FINITE-INDEX-BITS-WIDTH-MISMATCH' \
  'ElabFiniteRange.equivalentLogicalCount('; do
  grep -Fq "$token" "$finite_range" "$structure"
done
for token in \
  'finite Bits indexing publishes one exact correlated structural slice' \
  'finite Bits indexing rejects a same-witness different width function' \
  'finite Bits indexing rejects an independently rooted width' \
  'finite Bits indexing rejects a native same-witness width carrier'; do
  grep -Fq "$token" "$finite_bits_tests"
done
for token in \
  'formal_ram_mask_view' \
  'stream_fifo_formal_ram_mask_index_' \
  'typed-specialized formal helpers match native-Int Mem and Vec witnesses at depths 1 3 5 and 8'; do
  grep -Fq "$token" "$formal_helper_tests"
done
grep -q 'spinal.lib.StreamFifo(dataType, depth.asElabInt)' "$frontend_fifo"

# Preserve the reviewed native pointer algorithm and direct native test target.
grep -q 'push := (push + 1).resized' "$native_fifo"
grep -q 'pop := (pop + 1).resized' "$native_fifo"
grep -q 'push := U(0).resized' "$native_fifo"
grep -q 'pop := U(0).resized' "$native_fifo"
grep -Fq 'addressGen.payload := ptr.pop.resize(log2Up(elabDepth))' "$native_fifo"
grep -Fq '.setName("typed_storage_pop_index", weak = true)' "$native_fifo"
grep -Fq 'storagePopIndex := ptr.pop.resized' "$native_fifo"
grep -Fq 'result := index.resized' "$native_fifo"
grep -q 'val fifo = spinal.lib.StreamFifo' "$fifo_tests"
grep -q 'depth.asElabInt' "$fifo_tests"
grep -q 'symbolic formal helpers remain live at depths 1, 3, 5 and 8' "$fifo_tests"
grep -q 'symbolic formal helpers support one-stage storage and Vec-storage domains' "$fifo_tests"
grep -q 'typed literal formal helpers preserve ordinary concrete RTL' "$fifo_tests"
grep -q 'symbolic formal helpers reject a nonpositive depth domain' "$fifo_tests"
! grep -q 'MorphStreamFifo' "$fifo_tests"

python3 morphhdl/scripts/check-native-source-preservation.py \
  --manifest morphhdl/contracts/native-source-preservation.json

printf 'Increment 53e typed native StreamFifo source boundary passed.\n'
