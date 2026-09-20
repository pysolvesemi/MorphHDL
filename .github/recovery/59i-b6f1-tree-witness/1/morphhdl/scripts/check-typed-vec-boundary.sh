#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

native_vec=core/src/main/scala/spinal/core/Vec.scala
typed_vec=core/src/main/scala/spinal/core/ParameterizedVec.scala
widths=core/src/main/scala/spinal/core/ParameterizedWidth.scala
lowerer=morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogVecs.scala
publisher=morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala
topology=morphhdl/src/main/scala/spinal/core/internals/MorphHdlExternalParameterizedVerilog.scala
shape_tests=morphhdl/src/test/scala/spinal/core/TypedVecShapeTests.scala
rtl_tests=morphhdl/src/test/scala/morphhdl/TypedParameterizedVecTests.scala
structural_identity_tests=morphhdl/src/test/scala/spinal/core/StructuralIdentityAdversarialTests.scala
packed_identity_tests=morphhdl/src/test/scala/spinal/core/PackedVecIdentityAdversarialTests.scala
emitted_identity_tests=morphhdl/src/test/scala/spinal/core/VecEmittedIdentityAdversarialTests.scala
workflow=.github/workflows/increment-53f-typed-primitives.yml
closure_doc=docs/morphhdl/increment-53f-typed-primitive-closure.md

for required in \
  "$native_vec" "$typed_vec" "$widths" "$lowerer" "$publisher" \
  "$topology" "$shape_tests" "$rtl_tests" "$structural_identity_tests" \
  "$packed_identity_tests" "$emitted_identity_tests" "$workflow" \
  "$closure_doc"; do
  test -f "$required"
done

# The native Vec remains the only logical collection implementation. Typed
# construction adds metadata to that object; it does not introduce a Component
# or a separately authored parameterized Vec algorithm.
grep -Fq 'def Vec[T <: Data](gen: => T, size: Int)' "$native_vec"
grep -Fq 'def Vec[T <: Data](gen: => T, size: ElabInt)' "$native_vec"
grep -Fq 'def fill[T <: Data](size: Int)' "$native_vec"
grep -Fq 'def fill[T <: Data](size: ElabInt)' "$native_vec"
grep -Fq 'ParameterizedVec.create(size, "typed Vec size")' "$native_vec"
grep -Fq 'class Vec[T <: Data]' "$native_vec"
test "$(grep -Fc 'class Vec[T <: Data]' "$native_vec")" = 1
! grep -Eq 'extends[[:space:]]+Component|new[[:space:]]+Component' "$typed_vec" "$lowerer"

# Shape, capacity and operations are retained by exact native object identity.
# The finite carrier is explicitly not the public symbolic depth.
for token in \
  'final case class ParameterizedVecShape' \
  'depth: ElaborationIntegerExpression' \
  'witnessDepth: Int' \
  'carrierCapacity: Int' \
  'elementLeaves: Vector[ParameterizedVecLeafShape]' \
  'def totalPackedWidthVerilog' \
  'WeakReference[Vec[_]]' \
  'System.identityHashCode(value)' \
  'private[spinal] def shapeOf' \
  'private[spinal] def operationsOf' \
  'private[spinal] def vectorsOf' \
  'private[spinal] def copyShape' \
  'private[spinal] def validateStaticIndex' \
  'private[spinal] def validateDynamicAddress' \
  'private[spinal] def requireCompatible'; do
  grep -Fq "$token" "$typed_vec"
done
grep -Fq 'val capacity = depth.maximum.toInt' "$typed_vec"
grep -Fq 'publication must use `depth`, never this capacity' "$typed_vec"
grep -Fq 'size.authoritativeProjectedExpression(' "$typed_vec"
grep -Fq 'SPINAL-ELAB-INT-VEC-DEPTH-DOMAIN-INVALID' "$typed_vec"
grep -Fq 'requireProjectedExactExtrema = true' "$typed_vec"

# The authoritative Vec methods record their already-selected native
# operations. Publication may consume those identities, but it may not infer a
# Vec from an emitted suffix or a user component/signal name.
for token in \
  'ParameterizedVec.validateStaticIndex(this, idx)' \
  'ParameterizedVec.validateDynamicAddress(this, address)' \
  'ParameterizedVec.recordDynamicAccess' \
  'ParameterizedVec.recordDynamicWrite' \
  'ParameterizedVec.recordWholeAssignment' \
  'ParameterizedVec.recordPackedRead' \
  'ParameterizedVec.recordPackedAssignment' \
  'ParameterizedVec.recordAutoConnect' \
  'ParameterizedVec.copyShape(this, cloned)'; do
  grep -Fq "$token" "$native_vec"
done
grep -Fq 'ParameterizedVec.vectorsOf(component)' "$lowerer"
grep -Fq 'ParameterizedVec.retainedVectorsOf(component)' "$lowerer"
grep -Fq 'ParameterizedVec.shapeOf(vector)' "$lowerer"
grep -Fq 'ParameterizedVec.operationsOf(plan.vector)' "$lowerer"
grep -Fq 'new IdentityHashMap[Vec[_], VecPlan]()' "$lowerer"
grep -Fq 'never discovers a Vec from emitted-name' "$lowerer"
grep -Fq 'final case class ParameterizedVecDynamicWriteGuard' "$typed_vec"
grep -Fq 'carrierAddress: UInt' "$typed_vec"
grep -Fq 'decoder: UInt' "$typed_vec"
grep -Fq 'validateDynamicWriteGuardLineage' "$lowerer"
grep -Fq '(guard.whenStatement.cond eq guard.enable)' "$lowerer"
grep -Fq '(access.source eq write.decoder)' "$lowerer"
grep -Fq 'val decoderAddressWidth = write.carrierAddress.getBitsWidth' "$lowerer"
grep -Fq 'val decoderWidth = 1 << decoderAddressWidth' "$lowerer"
grep -Fq 'ParameterizedVerilogVecs.rewrite' "$publisher"
grep -Fq 'ParameterizedVerilogVecs.logicalSchema' "$topology"
grep -Fq 'ParameterizedVerilogVecs.isExactStructuralOutputSurface' "$publisher"
grep -Fq '!hasParameterizedHierarchy &&' "$publisher"
grep -Fq 'private[internals] def isExactStructuralOutputSurface' "$lowerer"
grep -Fq 'component == null || ports.isEmpty' "$lowerer"
grep -Fq 'vectorLeaves(vector).exists(_ eq port)' "$lowerer"
grep -Fq 'matches.size != 1' "$lowerer"
grep -Fq 'exactCoverage && owners.nonEmpty' "$lowerer"
grep -Fq 'selections.exists(_.vector eq owner)' "$lowerer"
grep -Fq 'port.isInput && !port.isOutput' "$publisher"
grep -Fq 'port.isOutput && !port.isInput' "$publisher"
grep -Fq '!port.isInOut' "$publisher"

! grep -Eq 'NativeIntShadow|ExternalNativeInt|source[- ]position|SourcePosition' \
  "$native_vec" "$typed_vec" "$lowerer"
! grep -Fq 'ParameterizedMemory' "$typed_vec" "$lowerer"
! grep -Eq 'definitionName[[:space:]]*(==|!=|match)|getDefinitionName.*(contains|startsWith|endsWith|matches)' \
  "$typed_vec" "$lowerer"
! grep -Eq '[_][0-9]+.*(discover|identify|recognize)|(discover|identify|recognize).*[_][0-9]+' \
  "$lowerer"
grep -Fq 'SPINAL-ELAB-VEC-OPERATION-UNSUPPORTED' "$typed_vec"
grep -Fq 'typeObject: AnyRef' "$typed_vec"
grep -Fq 'shape.typeObject eq TypeSInt' "$lowerer"
! grep -Fq 'dataClassName' "$typed_vec" "$lowerer"
! grep -Fq 'endsWith(".SInt")' "$typed_vec" "$lowerer"

# Typed convenience factories must delegate to the same native registry.
grep -Fq 'def Vec[T <: Data](dataType: => T, size: Int)' "$widths"
grep -Fq 'def Vec[T <: Data](dataType: => T, size: ElabInt)' "$widths"
grep -Fq 'ParameterizedVec.create(size, "typed Vec size")' "$widths"

# Required regressions cover the stable behavior rather than implementation
# signal names. Strict output is IEEE-1364-2001, and Mem remains unpacked.
for token in \
  'class TypedVecShapeTests' \
  'symbolic width and depth remain factorized on an ordinary native Vec' \
  'public typed Vec rejects an equal-but-foreign raw exact schema' \
  'public typed Vec rejects a forged symbolic leaf width before RTL publication' \
  'varying typed Vec geometry never exposes finite carrier capacity' \
  'expectUnsupported(vector.length)' \
  'expectUnsupported(vector.size)' \
  'expectUnsupported(vector.range)' \
  'expectUnsupported(vector.indices)' \
  'expectUnsupported(vector.getBitsWidth)' \
  'clone HardType Reg Stream Flow and enclosing Bundle retain Vec shape roots' \
  'constant and dynamic indexing record native Vec operations without erasing shape' \
  'incompatible symbolic Vec assignments fail even when witnesses match' \
  'deferred symbolic-depth Vec APIs fail closed before native carrier use' \
  'expectSymbolicOperationUnsupported("fixed range selection")' \
  'expectSymbolicOperationUnsupported("one-hot access")' \
  'expectSymbolicOperationUnsupported("equality")' \
  'expectSymbolicOperationUnsupported("inequality")' \
  'expectSymbolicOperationUnsupported("four-state equality")' \
  'expectSymbolicOperationUnsupported("zero construction")' \
  'expectSymbolicOperationUnsupported("bitwise or")' \
  'expectSymbolicOperationUnsupported("bitwise and")' \
  'expectSymbolicOperationUnsupported("bitwise xor")' \
  'expectSymbolicOperationUnsupported("bitwise inversion")' \
  'expectSymbolicOperationUnsupported("ranged packed assignment")' \
  'ordinary concrete Vec APIs remain on their native surface' \
  'symbolic Vec depths that reach zero or negative values fail closed' \
  'ElabInt literals delegate to the ordinary concrete Vec path'; do
  grep -Fq "$token" "$shape_tests"
done
for token in \
  'private def expectSymbolicOperationUnsupported(' \
  'val vector = Vec(Bits(8 bits), depth)' \
  'exerciseConcreteOperation(vector => vector(0 until 1))' \
  'exerciseConcreteOperation(vector => vector.oneHotAccess(B(1, 3 bits)))' \
  'exerciseConcreteOperation(vector => vector === Vec(Bits(8 bits), 3))' \
  'exerciseConcreteOperation(vector => vector =/= Vec(Bits(8 bits), 3))' \
  'exerciseConcreteOperation(vector => vector =::= Vec(Bits(8 bits), 3))' \
  'exerciseConcreteOperation(_.getZero)' \
  'exerciseConcreteOperation(vector => vector | Vec(Bits(8 bits), 3))' \
  'exerciseConcreteOperation(vector => vector & Vec(Bits(8 bits), 3))' \
  'exerciseConcreteOperation(vector => vector ^ Vec(Bits(8 bits), 3))' \
  'exerciseConcreteOperation(vector => ~vector)' \
  '_.assignFromBits(B(0, 8 bits), hi = 7, lo = 0)'; do
  grep -Fq "$token" "$shape_tests"
done
for token in \
  'class TypedParameterizedVecTests' \
  'symbolic width with concrete depth emits one packed Vec port' \
  'concrete width with symbolic depth emits one packed Vec port' \
  'independent symbolic width and depth remain in the total packed width' \
  'constant indices publish the canonical zero-based packed slices' \
  'Vec inside Bundle retains one packed Vec subtree' \
  'Vec of Bundle uses DEPTH times the logical twenty-four-bit element' \
  'ordinary Int and ElabInt literal Vec construction have byte-identical RTL' \
  'mixed-direction Vec auto-connect fails closed' \
  'SPINAL-PARAMETERIZED-VERILOG-VEC-ASSIGNMENT-LINEAGE-UNSUPPORTED' \
  'one parameterized Vec module passes strict V2001 at depths 1 3 5 and 8' \
  'Vec is packed while Mem remains an unpacked Verilog memory' \
  'a downstream Bits subclass named SInt cannot acquire signed Vec lowering' \
  'assert(!verilog.contains("$signed("), verilog)' \
  '"--language"' \
  '"1364-2001"' \
  '"-g2001"' \
  'read_verilog -defer -noautowire'; do
  grep -Fq "$token" "$rtl_tests"
done

# Exact-identity adversarial suites are permanent closure evidence. They make
# stale, coincident and reordered native graphs fail closed instead of letting
# a textual or same-shape match authorize Vec publication.
for token in \
  'class StructuralIdentityAdversarialTests' \
  'coincident constant Vec reference remains fixed beside a finite index' \
  'an unused finite Vec selection cannot promote a raw witness carrier' \
  'one finite Vec wrapper may be reused without losing its exact selection' \
  'finite selection keeps an internal typed Vec alive and packed' \
  'finite Vec alias cloned from a port never becomes another port' \
  'finite Vec alias is directionless and supports whole-leaf LHS use' \
  'distinct same-index Vec calls keep independent aliases' \
  'removed dynamic Vec read evidence cannot rewrite a replacement target' \
  'mutated standalone dynamic-write guards cannot launder exact assignments' \
  'mutated consolidated dynamic-write guards cannot launder exact assignments' \
  'dynamic writes retain non-power-of-two and singleton carrier guards' \
  'removed hierarchy Vec evidence cannot authorize leafwise replacement wiring' \
  'removed finite Mem port identity cannot be replaced by same-name text' \
  'output-only finite structural typed Vec is admitted by exact identity' \
  'output-only scalar and mixed Vec scalar surfaces remain rejected' \
  'input-only and inout-only symbolic surfaces remain rejected' \
  'a fully pruned unused Vec is harmlessly omitted by exact ownership' \
  'a fully pruned Vec with an exact hierarchy binding fails closed'; do
  grep -Fq "$token" "$structural_identity_tests"
done
for token in \
  'class PackedVecIdentityAdversarialTests' \
  'packed Vec read rejects a live Resize whose input identity changed' \
  'packed Vec assignment rejects a live leaf with the wrong slice' \
  'packed Vec read rejects a live Cat with reordered leaves'; do
  grep -Fq "$token" "$packed_identity_tests"
done
for token in \
  'class VecEmittedIdentityAdversarialTests' \
  'constant Vec carrier replacement preserves coincident child formal labels' \
  'constant Vec carrier replacement preserves coincident child module types' \
  'constant Vec carrier replacement preserves comments and string literals' \
  'packed Vec declaration replacement preserves same-name attribute strings' \
  'constant Vec carrier replacement preserves module and attribute syntax' \
  'exact structural names cannot collide with retained Vec carriers'; do
  grep -Fq "$token" "$emitted_identity_tests"
done

# The focused dual-lane job must name each closure suite explicitly. A passing
# broad module test is additional coverage, but cannot silently replace these
# exact ownership, publisher and native-compatibility regressions.
for suite in \
  'spinal.core.FiniteMemIdentityAdversarialTests' \
  'spinal.core.FiniteFormalBoundaryTests' \
  'spinal.core.ProceduralIdentityAdversarialTests' \
  'spinal.core.internals.RetainedWidthExpressionEquivalenceTests' \
  'spinal.core.internals.ParameterizedVerilogTests' \
  'morphhdl.GenericProcessLoweringTests' \
  'morphhdl.ParameterizedStreamFifoDepthTests' \
  'morphhdl.StreamFifoCompatibilityTests'; do
  grep -Fq "$suite" "$workflow"
done
python3 - "$workflow" <<'PY'
from pathlib import Path
import sys

workflow = Path(sys.argv[1]).read_text(encoding="utf-8")
start = workflow.index("  typed-regressions:")
end = workflow.index("  primitive-v2001:", start)
focused = workflow[start:end]
for suite in (
    "spinal.core.FiniteMemIdentityAdversarialTests",
    "spinal.core.FiniteFormalBoundaryTests",
    "spinal.core.ProceduralIdentityAdversarialTests",
    "spinal.core.internals.RetainedWidthExpressionEquivalenceTests",
    "spinal.core.internals.ParameterizedVerilogTests",
    "morphhdl.GenericProcessLoweringTests",
    "morphhdl.ParameterizedStreamFifoDepthTests",
    "morphhdl.StreamFifoCompatibilityTests",
):
    if suite not in focused:
        raise SystemExit(
            f"typed-regressions job does not exercise required suite {suite}"
        )
PY

for token in \
  'one narrower output-only exception' \
  'output surface mixing typed-Vec and scalar leaves' \
  'same-name text, and accepts one composite word' \
  'Increment 53f makes no broader'; do
  grep -Fq "$token" "$closure_doc"
done

printf 'Increment 53f typed Vec architecture boundary passed.\n'
