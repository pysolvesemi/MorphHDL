#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

carrier=core/src/main/scala/spinal/core/ElabInt.scala
compiler_permit=core/src/main/scala/spinal/core/ExternalCompilerPermit.scala
exact_domain=core/src/main/scala/spinal/core/ElaborationExactDomain.scala
bits=core/src/main/scala/spinal/core/Bits.scala
data=core/src/main/scala/spinal/core/Data.scala
memory=core/src/main/scala/spinal/core/Mem.scala
memory_metadata=core/src/main/scala/spinal/core/ParameterizedMemory.scala
sint=core/src/main/scala/spinal/core/SInt.scala
uint=core/src/main/scala/spinal/core/UInt.scala
widths=core/src/main/scala/spinal/core/ParameterizedWidth.scala
counter=lib/src/main/scala/spinal/lib/Counter.scala
counter_registry=frontend/src/main/scala/spinal/lib/ExternalParameterizedCounterRegistry.scala
elab_control=morphruntime/src/main/scala/spinal/core/ElabControl.scala
formal_component=morphruntime/src/main/scala/spinal/core/ElabFormalComponent.scala
formal_registry=morphruntime/src/main/scala/spinal/core/ExternalFormalParameterRegistry.scala
native_formal_registry=morphruntime/src/main/scala/spinal/core/ExternalNativeIntFormalizationRegistry.scala
native_shadow_registry=morphruntime/src/main/scala/spinal/core/ExternalNativeIntShadowRegistry.scala
native_compiler_runtime=morphruntime/src/main/scala/spinal/core/ExternalNativeIntCompilerRuntime.scala
shadow_structural_publisher=morphruntime/src/main/scala/spinal/core/ExternalNativeIntStructuralPublisher.scala
parameter_token=frontend/src/main/scala/morphhdl/frontend/ParameterToken.scala
boolean_parameter_token=frontend/src/main/scala/morphhdl/frontend/BooleanParameterToken.scala
frontend_bridge=frontend/src/main/scala/morphhdl/frontend/StructuralExpressionBridge.scala
frontend_permit_issuer=frontend/src/main/scala/spinal/core/ExternalAnalyzedFrontendPermitIssuer.scala
native_formal_publisher=frontend/src/main/scala/spinal/core/ExternalAnalyzedNativeIntFormalizationPublisher.scala
structural_publisher=frontend/src/main/scala/spinal/core/ExternalAnalyzedStructuralPublisher.scala
native_structural_frontend=frontend/src/main/scala/morphhdl/frontend/NativeStructuralFrontend.scala
frontend_package=frontend/src/main/scala/morphhdl/frontend/package.scala
process_registry=frontend/src/main/scala/spinal/core/ParameterizedProcess.scala
hdl_int_tests=frontend/src/test/scala/morphhdl/frontend/HdlIntTests.scala
elab_value=morphruntime/src/main/scala/spinal/core/ElabValue.scala
value_registry=morphruntime/src/main/scala/spinal/core/ExternalParameterizedValueRegistry.scala
native_fallback=morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala
typed_bridge=morphplugin/src/main/scala/morphhdl/compiler/MorphHdlTypedElaborationControlComponent.scala
closure_tests=morphhdl/src/test/scala/spinal/core/TypedPrimitiveClosureTests.scala
exact_domain_tests=morphhdl/src/test/scala/spinal/core/TypedExactDomainSafetyTests.scala
finite_formal_tests=morphhdl/src/test/scala/spinal/core/FiniteFormalBoundaryTests.scala
finite_bits_tests=morphhdl/src/test/scala/spinal/core/FiniteBitsIndexTests.scala
finite_mem_tests=morphhdl/src/test/scala/spinal/core/FiniteMemIdentityAdversarialTests.scala
emitted_lineage_tests=morphhdl/src/test/scala/spinal/core/internals/ParameterizedVerilogTests.scala
counter_all_ones_tests=morphhdl/src/test/scala/morphhdl/TypedCounterAllOnesTests.scala
counter_parity_tests=morphhdl/src/test/scala/spinal/lib/CounterSingleAuthorityParityTests.scala
counter_atomicity_tests=morphhdl/src/test/scala/spinal/core/CounterRegistryAtomicityTests.scala
native_formal_atomicity_tests=morphhdl/src/test/scala/spinal/core/NativeIntFormalizationAtomicityTests.scala
shadow_structural_tests=morphhdl/src/test/scala/spinal/core/ShadowStructuralPredicateAuthorityTests.scala
native_formal_test_access=morphhdl/src/test/scala/spinal/core/ExternalNativeIntFormalizationTestAccess.scala
vec_formal=morphhdl/src/test/scala/morphhdl/TypedParameterizedVecFormalEquivalenceTests.scala
closure_formal=morphhdl/src/test/scala/morphhdl/TypedPrimitiveClosureFormalEquivalenceTests.scala
finite_range=morphruntime/src/main/scala/spinal/core/ElabFiniteRange.scala
structure=morphruntime/src/main/scala/spinal/core/ParameterizedStructure.scala
finite_backend=morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogFiniteFolds.scala
process_backend=morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogProcesses.scala
hierarchy_backend=morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogHierarchy.scala
publisher_backend=morphhdl/src/main/scala/spinal/core/internals/MorphHdlExternalParameterizedVerilog.scala
external_spinal=morphhdl/src/main/scala/morphhdl/integration/ExternalSpinalVerilog.scala
morph_verilog=morphhdl/src/main/scala/morphhdl/MorphVerilog.scala
stream_fifo=lib/src/main/scala/spinal/lib/Stream.scala
stream_fifo_tests=morphhdl/src/test/scala/morphhdl/ParameterizedStreamFifoDepthTests.scala
stream_fifo_compatibility_tests=morphhdl/src/test/scala/morphhdl/StreamFifoCompatibilityTests.scala
structural_identity_tests=morphhdl/src/test/scala/spinal/core/StructuralIdentityAdversarialTests.scala
procedural_identity_tests=morphhdl/src/test/scala/spinal/core/ProceduralIdentityAdversarialTests.scala
generic_process_tests=morphhdl/src/test/scala/morphhdl/GenericProcessLoweringTests.scala
structure_test_access=morphhdl/src/test/scala/spinal/core/ParameterizedStructureTestAccess.scala

for required in \
  "$carrier" "$compiler_permit" "$exact_domain" "$bits" "$data" "$memory" "$memory_metadata" "$sint" "$uint" \
  "$widths" "$counter" "$counter_registry" \
  "$elab_control" "$formal_component" "$formal_registry" "$native_formal_registry" "$native_shadow_registry" \
  "$native_compiler_runtime" "$shadow_structural_publisher" \
  "$parameter_token" "$boolean_parameter_token" \
  "$frontend_bridge" "$frontend_permit_issuer" "$native_formal_publisher" "$structural_publisher" "$native_structural_frontend" \
  "$frontend_package" "$process_registry" "$hdl_int_tests" "$elab_value" "$value_registry" "$native_fallback" \
  "$typed_bridge" "$closure_tests" "$exact_domain_tests" "$finite_formal_tests" "$finite_bits_tests" "$finite_mem_tests" \
  "$emitted_lineage_tests" \
  "$counter_all_ones_tests" "$counter_parity_tests" "$counter_atomicity_tests" \
  "$native_formal_atomicity_tests" "$shadow_structural_tests" "$native_formal_test_access" "$vec_formal" "$closure_formal" \
  "$finite_range" "$structure" "$finite_backend" "$process_backend" "$hierarchy_backend" \
  "$publisher_backend" "$external_spinal" "$morph_verilog" "$stream_fifo" \
  "$stream_fifo_tests" "$stream_fifo_compatibility_tests" "$structural_identity_tests" "$procedural_identity_tests" \
  "$generic_process_tests" "$structure_test_access"; do
  test -f "$required"
done
for token in \
  'private object GeneratedIfOrdinalStorageKey' \
  'private def nextGeneratedIfBase(' \
  'val ordinal = ordinals.byBase.getOrElse(base, 0) + 1' \
  'if (ordinal == 1) base else s"${base}_$ordinal"'; do
  grep -Fq "$token" "$elab_control"
done

grep -Fq 'private[spinal] def hasCompleteCoverage: Boolean' "$exact_domain"
grep -Fq 'private[spinal] def requireAuthoritativeIntegerDomain(' "$carrier"
grep -Fq 'private[spinal] def authoritativeProjectedExpression(' "$carrier"
grep -Fq 'private[spinal] def requireAuthoritativeBooleanDomain(' "$carrier"
grep -Fq 'private[core] def authorizeDerivedProjection(' "$carrier"
grep -Fq 'SPINAL-ELAB-DOMAIN-PROJECTION-IDENTITY-MISSING' "$carrier"
grep -Fq 'SPINAL-ELAB-BOOL-EXACT-DOMAIN-REQUIRED' "$carrier"
grep -Fq 'root.bindAuthoritativeSchema(parameter, role, sourceLocation)' "$exact_domain"
grep -Fq 'private[spinal] def isAuthoritativeSchema(' "$widths"
grep -Fq 'SPINAL-ELAB-DOMAIN-ROOT-SCHEMA-IDENTITY-CONFLICT' "$widths"
grep -Fq 'final class ExternalCompilerPermit private[core]' "$compiler_permit"
grep -Fq 'private[core] case object AnalyzedSingleRoot extends Kind' "$compiler_permit"
grep -Fq 'def claimSingleRoot(' "$compiler_permit"
grep -Fq 'private object AnalyzerSeal' "$frontend_bridge"
grep -Fq 'def claimSingleRoot()' "$frontend_bridge"
grep -Fq 'ExternalCompilerPermit.analyzedSingleRoot(' "$frontend_permit_issuer"
grep -Fq 'SPINAL-ELAB-INT-ANALYZED-SOURCE-AUTHORIZATION-REQUIRED' "$carrier"
grep -Fq 'private[spinal] def fromSingleRootExpressionTrusted(' "$carrier"
grep -Fq 'private[spinal] def attachExactAuthority(' "$widths"
grep -Fq 'private[spinal] def hasExactAuthority: Boolean' "$widths"
grep -Fq 'SPINAL-ELAB-DOMAIN-EXACT-AUTHORITY-MISSING' "$carrier"
test "$(grep -RFl 'ExternalCompilerPermit.analyzedSingleRoot(' \
  core/src/main/scala frontend/src/main/scala morphruntime/src/main/scala | wc -l)" = 1
test "$(grep -RFl '.attachExactAuthority(' \
  core/src/main/scala frontend/src/main/scala morphruntime/src/main/scala | sort)" = \
  $'core/src/main/scala/spinal/core/ElabInt.scala\ncore/src/main/scala/spinal/core/ParameterizedWidth.scala\nmorphruntime/src/main/scala/spinal/core/ParameterizedStructure.scala'
grep -Fq 'public raw exact-domain mint and copied rendered metadata fail closed' \
  "$exact_domain_tests"
grep -Fq 'analyzed integer wrapper issues one permit only' "$hdl_int_tests"
grep -Fq 'single-root permit binds exact metadata identities and consumes on success' \
  "$hdl_int_tests"
grep -Fq 'final class ExternalNativeIntFormalizationToken private[core]' \
  "$native_formal_registry"
for token in \
  'private[core] case object Region extends Kind' \
  'private[core] case object ComponentGeometry extends Kind' \
  'private[core] case object ComponentParameter extends Kind' \
  'private[core] case object NativeWidth extends Kind' \
  'private[spinal] def attachRegionAtomically' \
  'private[spinal] def attachComponentAtomically' \
  'private[spinal] def attachComponentParameterAtomically' \
  'private def preflightRegionTransaction' \
  'private def preflightComponentTransaction' \
  'private def preflightComponentParameterTransaction' \
  'capture.token.claimFinal(' \
  'private def releaseIdentityAuthority(): Unit' \
  'retainedOwnerIdentity = null' \
  'retainedActualExpressionIdentity = null' \
  'retainedDefinitionExpressionIdentity = null' \
  'capturedResult = null'; do
  grep -Fq "$token" "$native_formal_registry"
done
for forbidden in \
  'finalTarget' \
  'finalFormal' \
  'finalGeometry'; do
  ! grep -Fq "$forbidden" "$native_formal_registry"
done
for token in \
  'private[spinal] def capture[A](' \
  'private[spinal] def captureWithDefinition[A](' \
  'private[spinal] def attachComponent[C <: Component](' \
  'private[spinal] def attachRegion[T <: Data](' \
  'private[core] def preflightComponent[C <: Component](' \
  'private[core] def preflightRegion[T <: Data]('; do
  grep -Fq "$token" "$native_shadow_registry"
done
for token in \
  'final class ExternalNativeIntStructuralPredicateReceipt private[core]' \
  'private[core] def definitionPredicateReceiptTracked(' \
  'MORPH-FRONTEND-NATIVE-INT-STRUCTURAL-PREDICATE-CONDITION-MISMATCH' \
  'MORPH-FRONTEND-NATIVE-INT-STRUCTURAL-PREDICATE-OWNER-MISMATCH' \
  'MORPH-FRONTEND-NATIVE-INT-STRUCTURAL-PREDICATE-TARGET-MISMATCH' \
  'MORPH-FRONTEND-NATIVE-INT-STRUCTURAL-PREDICATE-REPLAY' \
  'body' \
  'consumed = true'; do
  grep -Fq "$token" "$native_shadow_registry"
done
for token in \
  'object ExternalNativeIntStructuralPublisher' \
  'def definitionPredicateTracked(' \
  'ExternalNativeIntShadowRegistry.definitionPredicateReceiptTracked(' \
  'def registerIf(' \
  'receipt.publish(' \
  'receipt.predicateDomain'; do
  grep -Fq "$token" "$shadow_structural_publisher"
done
test "$(grep -Fc 'ExternalNativeIntStructuralPublisher.definitionPredicateTracked(' \
  "$native_compiler_runtime")" = 3
test "$(grep -Fc 'ExternalNativeIntStructuralPublisher.registerIf(' \
  "$native_compiler_runtime")" = 3
test "$(grep -Fc 'ExternalNativeIntStructuralPublisher.definitionPredicateTracked(' \
  "$native_structural_frontend")" = 1
test "$(grep -Fc 'ExternalNativeIntStructuralPublisher.registerIf(' \
  "$native_structural_frontend")" = 1
for token in \
  'final class ExternalAnalyzedNativeIntFormalCapture[A] private[core]' \
  'ExternalAnalyzedFrontendPermitIssuer.singleRoot(analyzed)' \
  'ExternalNativeIntFormalizationToken.region(' \
  'ExternalNativeIntFormalizationRegistry.attachRegionAtomically(' \
  'ExternalNativeIntFormalizationRegistry.attachComponentAtomically(' \
  'ExternalNativeIntFormalizationRegistry.attachComponentParameterAtomically('; do
  grep -Fq "$token" "$native_formal_publisher"
done
! grep -Eq '^\s*(def|val)\s+apply\b' "$native_formal_registry"
grep -Fq 'shadow-side preflight failure leaves both registries clean and token retryable' \
  "$native_formal_atomicity_tests"
grep -Fq 'successful formalization token retained its weak-key graph identities' \
  "$native_formal_atomicity_tests"
grep -Fq 'probe.replayRegistriesStable' "$native_formal_atomicity_tests"
for token in \
  'final class AnalyzedStructuralInteger private[frontend]' \
  'final class AnalyzedStructuralBoolean private[frontend]' \
  'case object ProcessRangeCount' \
  'case object StructuralIfCondition' \
  'def analyzedStructuralInteger(' \
  'def analyzedStructuralBoolean('; do
  grep -Fq "$token" "$frontend_bridge"
done
for token in \
  'object ExternalAnalyzedStructuralPublisher' \
  'def captureProcessRange(' \
  'def registerStructuralIf(' \
  'def registerStructuralCase(' \
  'def recordProcessSlice(' \
  'def recordStructuralSlice(' \
  'def recordProcessVecIndex[T <: Data](' \
  'def recordStructuralVecIndex[T <: Data]('; do
  grep -Fq "$token" "$structural_publisher"
done
for token in \
  'private[spinal] def captureAnalyzedFrontendRange(' \
  'private[spinal] def recordSlice(' \
  'private[spinal] def recordVecIndex[T <: Data]('; do
  grep -Fq "$token" "$process_registry"
done
for token in \
  'private[spinal] def registerIf(' \
  'private[spinal] def registerFor(' \
  'private[spinal] def registerCase(' \
  'private[spinal] def recordSlice(' \
  'private[spinal] def recordVecIndex[T <: Data]('; do
  grep -Fq "$token" "$structure"
done
grep -Fq 'ParameterizedStructure.registerIf(' "$structure_test_access"
for token in \
  'analyzed structural integer publication is kind target and replay bound' \
  'analyzed structural Boolean publication consumes its exact target once' \
  'analyzed structural case retains its active generate-index context' \
  'forged structural metadata cannot enter analyzed publication APIs'; do
  grep -Fq "$token" "$hdl_int_tests"
done
grep -Fq 'ParameterizedStructure.registerIf(' "$hdl_int_tests"
grep -Fq \
  'shadow structural predicate receipts reject copied foreign and replayed targets without mutation' \
  "$shadow_structural_tests"
test "$(grep -RFl 'ParameterizedProcess.captureAnalyzedFrontendRange(' \
  frontend/src/main/scala | sort)" = "$structural_publisher"
test "$(grep -RFl 'ParameterizedProcess.recordSlice(' \
  frontend/src/main/scala | sort)" = "$structural_publisher"
test "$(grep -RFl 'ParameterizedProcess.recordVecIndex(' \
  frontend/src/main/scala | sort)" = "$structural_publisher"
test "$(grep -RFl 'ParameterizedStructure.registerFor(' \
  frontend/src/main/scala morphruntime/src/main/scala | sort)" = "$process_registry"
test "$(grep -RFl 'ParameterizedStructure.registerIf(' \
  frontend/src/main/scala morphruntime/src/main/scala | sort)" = \
  $'frontend/src/main/scala/spinal/core/ExternalAnalyzedStructuralPublisher.scala\nmorphruntime/src/main/scala/spinal/core/ElabControl.scala\nmorphruntime/src/main/scala/spinal/core/ExternalNativeIntStructuralPublisher.scala'
test "$(grep -RFl 'ParameterizedStructure.registerCase(' \
  frontend/src/main/scala morphruntime/src/main/scala | sort)" = "$structural_publisher"
test "$(grep -RFl 'ParameterizedStructure.recordSlice(' \
  frontend/src/main/scala morphruntime/src/main/scala | sort)" = "$structural_publisher"
test "$(grep -RFl 'ParameterizedStructure.recordVecIndex(' \
  frontend/src/main/scala morphruntime/src/main/scala | sort)" = \
  $'frontend/src/main/scala/spinal/core/ExternalAnalyzedStructuralPublisher.scala\nmorphruntime/src/main/scala/spinal/core/ElabFiniteRange.scala'
grep -Fq 'def canonicalSchema(' "$parameter_token"
grep -Fq 'lazy val canonicalSchema:' "$boolean_parameter_token"
grep -Fq 'token.canonicalSchema(minimum, maximum)' "$frontend_bridge"
grep -Fq 'value.authoritativeProjectedExpression(' "$elab_value"
grep -Fq 'SPINAL-PARAMETERIZED-VERILOG-VALUE-EXACT-DOMAIN-REQUIRED' \
  "$elab_value"
grep -Fq 'requireProjectedExactExtrema = true' "$elab_value"

# Finite native algorithms retain exact typed counts, branch owners and
# identity-anchored Mem/fold publication. Symbolic counts never fall back to a
# witness-unrolled Scala loop outside an active structural capture.
for token in \
  'final class ElabFiniteIndex' \
  'object ElabFiniteRange' \
  'def foreach(' \
  'def reduceOr(source: Bits, count: ElabInt)' \
  'def countOne(source: Bits, count: ElabInt)(' \
  'SPINAL-ELAB-FINITE-RANGE-SYMBOLIC-CAPTURE-REQUIRED' \
  'SPINAL-ELAB-FINITE-RANGE-VEC-DEPTH-MISMATCH' \
  'SPINAL-ELAB-FINITE-RANGE-MEM-DEPTH-MISMATCH' \
  'def apply(source: Bits): Bool' \
  'ParameterizedStructure.recordFiniteIndexSlice(' \
  'SPINAL-ELAB-FINITE-FOLD-WIDTH-MISMATCH'; do
  grep -Fq "$token" "$finite_range"
done
for token in \
  'final class ParameterizedStructuralOwner' \
  'val ownerRoot: Option[ElaborationIntegerParameterRoot]' \
  'def currentOwner(' \
  'private[spinal] def captureExactBlock(' \
  'def captureInto[T](' \
  'def requireOwnerCoverage(' \
  'private[core] def recordFiniteIndexSlice(' \
  'SPINAL-ELAB-FINITE-INDEX-BITS-SOURCE-WIDTH-MISSING' \
  'SPINAL-ELAB-FINITE-INDEX-BITS-EXACT-DOMAIN-REQUIRED' \
  'SPINAL-ELAB-FINITE-INDEX-BITS-WIDTH-MISMATCH' \
  'ElabFiniteRange.equivalentLogicalCount(' \
  'ElaborationDomainContext.constrains(exact.root)' \
  'state.ownerRoot.exists(_ eq exact.root)' \
  'private[spinal] def recordMemoryIndex(' \
  'SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-ACTIVE-ROOT-MISMATCH' \
  'SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-OWNER-COVERAGE-OVERLAP'; do
  grep -Fq "$token" "$structure"
done
grep -Fq 'nested exact roots cannot lend the inner capture id to the outer owner' "$closure_tests"
grep -Fq 'nested owner-root borrowing published partial RTL' "$closure_tests"
for token in \
  'object ParameterizedVerilogFiniteFolds' \
  'SPINAL-PARAMETERIZED-VERILOG-FINITE-FOLD-ANCHOR-CARDINALITY-MISMATCH' \
  'always @(*) begin' \
  'fold.count.verilog'; do
  grep -Fq "$token" "$finite_backend"
done
! grep -Fq 'finiteRangeFromZero("StreamFifo formal RAM range")' "$stream_fifo"
for token in \
  'private sealed trait FormalHelperAdapter' \
  'private val concreteFormalHelperAdapter' \
  'private val capturedFormalHelperAdapter' \
  'private def formalCheckLastPushAlgorithm' \
  'private def formalCheckRamAlgorithm' \
  'private def formalContainsAlgorithm' \
  'private def formalCountAlgorithm' \
  'private def formalFullToEmptyAlgorithm(empty: Bool): Bool' \
  'private def publishCapturedFormalChecks(checks: Vec[Bool]): Vec[Bool]' \
  'private def normalizedIndex' \
  'typed_storage_pop_index' \
  'typed_formal_ram_mask_one' \
  'type Mask = Bits' \
  'index(target) := combine(index(mask), index(condition))' \
  'valid & predicate' \
  'condition.read(normalizedIndex(index, stableName))' \
  'adapter.shiftMaskOne(one, index, s"${stableName}_shifted_one") - one' \
  'copyShape(one, one |<< index)' \
  '.uintAllOnes(elabDepth, s"${allOnesName}_zero")' \
  '.resize((elabDepth + 1).addressWidth + 1)'; do
  grep -Fq "$token" "$stream_fifo"
done
! grep -Fq 'symbolicFormalCheck' "$stream_fifo"
for forbidden in \
  'CapturedFormalMask' \
  'formal_ram_mask_view' \
  'outerMask'; do
  ! grep -Fq "$forbidden" "$stream_fifo"
done

# StreamFifo formal helpers have one semantic source. Concrete and captured
# adapters may select owners, enumerate exact storage and normalize geometry,
# but they must not fork predecessor selection, predicates, mask decisions or
# reductions.
python3 - "$stream_fifo" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1]).read_text(encoding="utf-8")
algorithm_start = source.index("  private def formalStorageConditions[")
algorithm_end = source.index("  private def capturedFormalResult[", algorithm_start)
algorithms = source[algorithm_start:algorithm_end]

unique_tokens = {
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
}
for label, token in unique_tokens.items():
    count = source.count(token)
    if count != 1 or algorithms.count(token) != 1:
        raise SystemExit(
            f"StreamFifo {label} must occur exactly once in the shared algorithm body; found source={count}, body={algorithms.count(token)}"
        )

if source.count("formalFullToEmptyAlgorithm(") != 4:
    raise SystemExit(
        "StreamFifo full-to-empty shared algorithm must have one definition and exactly three owner-mechanics calls"
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
    "override def checkedRam(",
    "override def newMask(",
    "override def growCountOperand(",
    "override def countResult(",
):
    count = adapters.count(token)
    if count != 2:
        raise SystemExit(
            f"StreamFifo count mechanics seam must have exactly two adapter implementations for {token}; found {count}"
        )

for forbidden in (
    "symbolicFormalCheckLastPush",
    "symbolicFormalCheckRam",
    "if (adapter eq concreteFormalHelperAdapter)",
    "if (adapter eq capturedFormalHelperAdapter)",
):
    if forbidden in source:
        raise SystemExit(
            f"StreamFifo formal-helper semantic fork is forbidden: {forbidden}"
        )

typed_apply_start = source.index("  /** Typed depth entry point")
typed_apply_end = source.index("\n  }\n\n}", typed_apply_start)
typed_apply = source[typed_apply_start:typed_apply_end]
full_apply_marker = "  /** Full typed-depth entry point"
default_apply_end = typed_apply.index(full_apply_marker)
default_apply = typed_apply[:default_apply_end]
full_apply = typed_apply[default_apply_end:]
default_delegate = """apply(
      dataType,
      depth,
      withAsyncRead = false,
      withBypass = false,
      allowExtraMsb = true,
      forFMax = false,
      useVec = false,
      initPayload = None
    )"""
if default_apply.count(default_delegate) != 1 or "new StreamFifo(" in default_apply:
    raise SystemExit(
        "typed default StreamFifo depth must delegate exactly once to the full typed factory"
    )
literal_constructor = """if (depth.isConcrete)
      new StreamFifo(
        dataType,
        depth.witness,
        withAsyncRead,
        withBypass,
        allowExtraMsb,
        forFMax,
        useVec,
        initPayload
      )"""
if full_apply.count(literal_constructor) != 1:
    raise SystemExit(
        "typed literal StreamFifo depth must invoke exactly one authoritative native Int constructor with the full configuration"
    )
PY

for token in \
  'finite Bits indexing publishes one exact correlated structural slice' \
  'finite Bits indexing rejects a same-witness different width function' \
  'finite Bits indexing rejects an independently rooted width' \
  'finite Bits indexing rejects a native same-witness width carrier'; do
  grep -Fq "$token" "$finite_bits_tests"
done

bash morphhdl/scripts/check-typed-vec-boundary.sh

# Native hierarchy pulling keeps its traversal and assignment algorithm while
# cloning exact typed geometry through the shared metadata-preserving helper.
test "$(grep -Fc 'ParameterizedWidth.cloneOf(srcData)' "$data")" = 2

# ElabInt is the single typed carrier. It stays non-convertible and supplies
# the generic geometry helpers used by native primitives.
for token in \
  'final class ElabInt' \
  'final class ElabBool' \
  'def addressWidth: ElabInt' \
  'def log2Up: ElabInt' \
  'def slices: SlicesCount' \
  'def finiteRangeFromZero' \
  'def packedWidthOf(data: Data): ElabInt' \
  'def requireSingleSymbolicRoot'; do
  grep -Fq "$token" "$carrier"
done
for forbidden in \
  'implicit def elabIntToInt' 'implicit def toInt' \
  'implicit def elabBoolToBoolean' 'implicit def toBoolean'; do
  ! grep -Fq "$forbidden" "$carrier"
done

# Binary Counter retains its BigInt constructor while one adapter seam supplies
# concrete or typed geometry to one authoritative update algorithm.
for token in \
  'def apply(stateCount: BigInt): Counter' \
  'def apply(stateCount: ElabInt): Counter' \
  'def apply(start: ElabInt, end: ElabInt): Counter' \
  'def apply(stateCount: ElabInt, inc: Bool): Counter' \
  'def apply(start: ElabInt, end: ElabInt, inc: Bool): Counter' \
  'def down(stateCount: ElabInt): Counter' \
  'def both(stateCount: ElabInt): Counter' \
  'sealed trait Bounds' \
  'final case class ConcreteBounds' \
  'final case class TypedBounds' \
  'start: BigInt' \
  'end: BigInt' \
  'ElabValue.uintLike' \
  'ElabValue.uintAllOnes' \
  'private sealed trait AlgorithmAdapter' \
  'private final class ConcreteAlgorithmAdapter' \
  'private final class TypedAlgorithmAdapter(' \
  'private def applyOneStep(' \
  'private def naturalWrapControl(' \
  'private def stepTrickControl(' \
  'algorithm.generateWhen(algorithm.not(naturalWrapControl(policy)))' \
  'algorithm.select(stepTrickControl(bothWrap, handleOverflow))' \
  'value.witness > 1' \
  'value.exact > 1' \
  'val bothIncOnly = incOnly' \
  'val bothDecOnly = decOnly' \
  'algorithm.withBothTarget { target =>' \
  'emitStepTrick(bothIncOnly, bothDecOnly, target)' \
  'emitComparedSteps(' \
  'value.requireAuthoritativeIntegerDomain(' \
  'SPINAL-ELAB-COUNTER-ORDINAL-NEGATIVE' \
  'SPINAL-ELAB-COUNTER-ORDINAL-DOMAIN-OUT-OF-RANGE' \
  'SPINAL-ELAB-COUNTER-ORDINAL-WIDTH-INSUFFICIENT' \
  'SPINAL-ELAB-COUNTER-INIT-NEGATIVE' \
  'SPINAL-ELAB-COUNTER-INIT-WIDTH-INSUFFICIENT' \
  'SPINAL-ELAB-COUNTER-INIT-DOMAIN-OUT-OF-RANGE'; do
  grep -Fq "$token" "$counter"
done
for token in \
  'def uintAllOnes(width: ElabInt, stableName: String): UInt' \
  'ParameterizedWidth.copyShape(zero, ones)' \
  'SPINAL-PARAMETERIZED-VERILOG-VALUE-WIDTH-INSUFFICIENT' \
  'ExternalParameterizedValueRegistry.validateCarrierDomain(' \
  'result.assignFrom(' \
  'spinal.core.internals.UIntLiteral(witness, null, width)'; do
  grep -Fq "$token" "$elab_value"
done
for token in \
  'private[core] def validateCarrierDomain(' \
  'ElabInt.requireAuthoritativeIntegerDomain(' \
  'role = "retained UInt expression"' \
  'requireExactExtrema = true' \
  'SPINAL-PARAMETERIZED-VERILOG-VALUE-EXACT-DOMAIN-REQUIRED' \
  'values.root eq widths.root' \
  'values.parameter eq widths.parameter' \
  'values.evidenceValues == widths.evidenceValues' \
  'outside carrier minimum width'; do
  grep -Fq "$token" "$value_registry"
done
for token in \
  'UNDECLARED_RETAINED_VALUE' \
  'FOREIGN_EXACT_SCHEMA_VALUE' \
  'LOOSE_EXACT_EXTREMA_VALUE' \
  'MISMATCHED_EXACT_DEFAULT_VALUE'; do
  grep -Fq "$token" "$closure_tests"
done
grep -Fq \
  'external memory registry accepts only canonical public literal depth metadata' \
  "$finite_mem_tests"
grep -Fq 'UNDECLARED_MEMORY_DEPTH' "$finite_mem_tests"
for token in \
  'record.expression.exactDomain, width.exactDomain' \
  'valueDomain.root eq widthDomain.root' \
  'valueDomain.evidenceValues == widthDomain.evidenceValues' \
  'SPINAL-PARAMETERIZED-VERILOG-VALUE-EMITTED-LINEAGE-MISMATCH' \
  'SPINAL-PARAMETERIZED-VERILOG-RESIZE-DOMAIN-CROSSING-UNSUPPORTED' \
  'SPINAL-PARAMETERIZED-VERILOG-SIGNED-RESIZE-GROW-DOMAIN-UNSUPPORTED' \
  'emittedRetainedWitness'; do
  grep -Fq "$token" "$native_fallback"
done
grep -Fq \
  'retained value rewrite rejects a stale same-name emitted right-hand side' \
  "$emitted_lineage_tests"
grep -Fq \
  'retained resize rewrite rejects an additional same-target assignment' \
  "$emitted_lineage_tests"
python3 - "$counter" "$elab_value" "$closure_tests" <<'PY'
import pathlib
import re
import sys

source = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8")
counter_start = source.index("class Counter private[lib]")
counter_end = source.index("/** Binary up/down counter", counter_start)
counter = source[counter_start:counter_end]
adapters_start = counter.index("private sealed trait AlgorithmAdapter")
algorithm_start = counter.index("private val algorithm: AlgorithmAdapter")
adapters = counter[adapters_start:algorithm_start]
algorithm_end = counter.index("enableStandardPruning()", algorithm_start)
algorithm = counter[algorithm_start:algorithm_end]

unique_tokens = {
    "adapter selection": "private val algorithm: AlgorithmAdapter",
    "natural-wrap control": "private def naturalWrapControl(",
    "step-trick control": "private def stepTrickControl(",
    "single-step body": "private def applyOneStep(",
    "boundary control": "algorithm.generateWhen(algorithm.not(naturalWrapControl(policy)))",
    "direction topology": "direction match",
    "Both target capture": "algorithm.withBothTarget { target =>",
    "step-trick selection": "algorithm.select(stepTrickControl(bothWrap, handleOverflow))",
    "step-trick body": "private def emitStepTrick(",
    "compared-step body": "private def emitComparedSteps(",
    "boundary assignment": "when(boundary)",
}
for label, token in unique_tokens.items():
    count = counter.count(token)
    body_count = algorithm.count(token)
    if count != 1 or body_count != 1:
        raise SystemExit(
            f"Counter {label} must occur exactly once in the shared algorithm; "
            f"found source={count}, body={body_count}"
        )

if algorithm.count("typedBounds == null") != 1:
    raise SystemExit(
        "Counter shared algorithm must contain one concrete-vs-typed seam and no semantic fork"
    )
for forbidden in (
    "BoundaryPolicy.Wrap",
    "bothWrap",
    "handleOverflow",
    "useNaturalOverflow",
):
    if forbidden in adapters:
        raise SystemExit(
            f"Counter adapters must not own algorithm policy or topology: {forbidden}"
        )
for forbidden in (
    "if (algorithm eq",
    "isInstanceOf[ConcreteAlgorithmAdapter]",
    "isInstanceOf[TypedAlgorithmAdapter]",
):
    if forbidden in counter:
        raise SystemExit(
            f"Counter duplicates concrete-vs-typed algorithm authority: {forbidden}"
        )
if counter.count("private final class ConcreteAlgorithmAdapter") != 1:
    raise SystemExit("Counter must retain exactly one concrete algorithm adapter")
if counter.count("private final class TypedAlgorithmAdapter") != 1:
    raise SystemExit("Counter must retain exactly one typed algorithm adapter")
if counter.count("def stepOne(") != 1:
    raise SystemExit("Counter must retain exactly one public stepOne definition")
step_one_definitions = re.findall(r"\bdef\s+(stepOne\w*)\s*\(", counter)
if step_one_definitions != ["stepOne"]:
    raise SystemExit(
        "Counter must not retain specialized or selected stepOne emitters; found "
        + repr(step_one_definitions)
    )

value_source = pathlib.Path(sys.argv[2]).read_text(encoding="utf-8")
if value_source.count("result.assignFrom(") != 1:
    raise SystemExit(
        "typed UInt value retention must have one exact direct target assignment"
    )
if "result := U(witness" in value_source:
    raise SystemExit(
        "typed UInt value retention must not interpose a native UInt type node"
    )

closure_source = pathlib.Path(sys.argv[3]).read_text(encoding="utf-8")
closure_compact = "".join(closure_source.split())
if (
    "spinal.lib.ExternalParameterizedCounterRegistry"
    ".metadataOf(dut.counter).isEmpty"
    not in closure_compact
):
    raise SystemExit(
        "typed Counter closure must prove it does not populate the legacy registry"
    )
PY
! grep -Eq 'NativeIntShadow|ExternalNativeInt|ExternalParameterizedCounterRegistry' "$counter"
! grep -Fq 'typed_counter_boundary' "$counter"

for token in \
  'class TypedCounterAllOnesTests' \
  'typed bidirectional Counter decrement remains all ones at every power-of-two width' \
  'Depths = Vector(1, 2, 4, 8)' \
  'iverilog' \
  'PASS typed Counter all ones DEPTH='; do
  grep -Fq "$token" "$counter_all_ones_tests"
done
for token in \
  'class CounterSingleAuthorityParityTests' \
  'typed Counter specializations match independent concrete policy matrices' \
  'CounterDirection.Up' \
  'CounterDirection.Down' \
  'CounterDirection.Both' \
  'BoundaryPolicy.Wrap' \
  'BoundaryPolicy.Saturate' \
  'BoundaryPolicy.Freeze' \
  'start <- Vector(0, 3)' \
  'handleOverflow = false' \
  'StateCounts = Vector(1, 4, 5)' \
  'PASS Counter single authority STATE_COUNT='; do
  grep -Fq "$token" "$counter_parity_tests"
done
for token in \
  'private[spinal] def attachExistingAll(' \
  'val checkedExpression = ParameterizedWidth.attachExistingAll(' \
  'rejected Counter width cannot leave partial boundary authority' \
  'ExternalParameterizedCounterRegistry.metadataOf(counter).isEmpty' \
  'rejected Counter width published partial RTL'; do
  grep -Fq "$token" "$widths" "$counter_registry" "$counter_atomicity_tests"
done
python3 - "$counter_registry" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1]).read_text(encoding="utf-8")
start = source.index("  def attach(counter: Counter, width: ParameterizedBitCount)")
end = source.index("\n  def metadataOf", start)
attach = source[start:end]
preflight = attach.index("ParameterizedWidth.attachExistingAll(")
commit = attach.index("retained.update(")
if preflight >= commit:
    raise SystemExit(
        "native Counter metadata must be committed only after atomic width preflight"
    )
for forbidden in (
    "ParameterizedWidth.attach(counter.valueNext, width)",
    "ParameterizedWidth.attach(counter.value, width)",
):
    if forbidden in attach:
        raise SystemExit(
            "native Counter restored a partial per-leaf width commit: " + forbidden
        )
PY

# Mem remains an unpacked native memory. Only its typed depth/address geometry
# crosses the new API, with one audited concrete witness for native port
# normalization.
grep -Fq 'def apply[T <: Data](wordType: HardType[T], wordCount: Int)' "$memory"
grep -Fq 'def apply[T <: Data](wordType: HardType[T], wordCount: ElabInt)' "$memory"
grep -Fq 'def addressWidthExpr: ElabInt' "$memory"
grep -Fq 'private[core] def nativePortAddressWidth: Int' "$memory"
grep -Fq 'private[core] def depthExpressionOf' "$memory_metadata"
grep -Fq 'private[core] def addressWidthOf' "$memory_metadata"
grep -Fq 'private[core] def nativePortAddressWidthOf' "$memory_metadata"
! grep -Eq 'ParameterizedVec|totalPackedWidthVerilog' "$memory" "$memory_metadata"

# Generic typed width/resize/clone helpers remain shared; the compiler bridge
# must not recognize individual library primitives.
grep -Fq 'def attachResize[T <: BitVector](data: T, width: ElabInt)' "$widths"
grep -Fq 'def copyShape' "$widths"
grep -Fq 'SPINAL-PARAMETERIZED-VERILOG-WIDTH-EXACT-DOMAIN-REQUIRED' "$widths"
for bit_vector in "$bits" "$sint" "$uint"; do
  grep -Fq 'val witness = ParameterizedWidth.validatedResizeWitness(width)' \
    "$bit_vector"
  grep -Fq 'if (width.isConcrete) resize(witness)' "$bit_vector"
done
for forbidden in '"Vec"' '"Counter"' '"Mem"' '"StreamFifo"'; do
  ! grep -Fq "$forbidden" "$typed_bridge"
done

for source in \
  "$carrier" "$data" "$memory" "$memory_metadata" "$widths" "$counter" \
  core/src/main/scala/spinal/core/ParameterizedVec.scala \
  core/src/main/scala/spinal/core/Vec.scala; do
  ! grep -Eq 'NativeIntShadow|ExternalNativeIntShadow|component-name recognizer|signal-name recognizer' "$source"
done

grep -Fq \
  'derived projections retain authority while copied partial evidence cannot reacquire it' \
  "$exact_domain_tests"
grep -Fq \
  'authoritative Boolean domains reject forged summaries keys and schemas' \
  "$exact_domain_tests"
grep -Fq \
  'integer derivations reject malformed raw evidence before projection' \
  "$exact_domain_tests"

for token in \
  'removed finite Mem port identity cannot be replaced by same-name text' \
  'SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-FOREIGN-MEMORY-PORT-UNSUPPORTED'; do
  grep -Fq "$token" "$structural_identity_tests"
done

for token in \
  'class TypedPrimitiveClosureTests' \
  'typed addressWidth is exact and positive at depths 1, 3, 5 and 8' \
  'direct typed Mem keeps an unpacked symbolic memory and portable address geometry' \
  'typed Counter retains state-count and inclusive-limit expressions' \
  'typed literal Mem and Counter preserve ordinary concrete RTL' \
  'typed literal Mem preserves native HardType evaluation and Int depth acceptance' \
  'typed literal Mem with symbolic elements preserves native graph and RTL' \
  'ordinary Mem keeps eager addressType ordering and concrete RTL parity' \
  'Mem port geometry preserves concrete formulas and typed products' \
  'typed literal Stream and Flow pipes preserve byte-identical concrete RTL' \
  'fixed slices, typed resize, Stream and Flow retain their native shapes' \
  'public symbolic widths and typed resize reject missing exact evidence before attachment' \
  'Counter retains the public five-argument stepOne ABI' \
  'one direct typed child formal binds its parent expression' \
  'finite structural and procedural ranges keep distinct Verilog forms' \
  'typed finite Mem selection rewrites only its exact retained read port' \
  'typed finite composite Mem retains one complete packed read lineage' \
  'symbolic finite ranges fail closed outside structural capture' \
  'finite Vec Mem and fold geometry mismatches fail by exact expression' \
  'finite population count rejects an overridden retained anchor' \
  'structural owners reject wrong roots overlap incomplete domains and siblings' \
  'structural owner cannot borrow an active capture id from a foreign root' \
  'non-positive address, Mem and Counter domains fail before construction' \
  'typed Counter inclusive bounds reject negative and independent domains' \
  'duplicateMissingEvidence' \
  'retained UInt attach rejects malformed summaries before registration' \
  'uintLike rejects negative and minimum-width-insufficient typed constants' \
  'uintLike rejects malformed raw exact evidence before projection normalization'; do
  grep -Fq "$token" "$closure_tests"
done

for token in \
  'external memory registry rejects forged inexact public depth metadata' \
  'external memory registry accepts only canonical public literal depth metadata' \
  'public typed Mem rejects bounded symbolic depth without exact evidence' \
  'finite Mem rejects a witness-identical replacement address assignment' \
  'SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MEM-ADDRESS-LINEAGE-MISMATCH' \
  'finite Mem accepts exact native literal-address normalization' \
  'finite Mem partitions exact structural reads from one native 1R1W pair' \
  'finite Mem mixed mode rejects an uncaptured asynchronous port' \
  'copied address-width algebra cannot replace same-root exhaustive capacity proof' \
  'SPINAL-PARAMETERIZED-VERILOG-MEMORY-PORT-SHAPE-UNSUPPORTED'; do
  grep -Fq "$token" "$finite_mem_tests"
done

grep -Fq \
  'symbolic slice counts fail closed while literal slices stay concrete' \
  "$closure_tests"
grep -Fq \
  'a witness-valid procedural slice rejects a narrower retained source domain' \
  "$generic_process_tests"
grep -Fq 'SPINAL-PARAMETERIZED-VERILOG-PROCESS-SLICE-DOMAIN-UNSUPPORTED' \
  "$generic_process_tests"
for token in \
  'arbitrary AssertStatement remains forbidden inside structural capture' \
  'structural slice rejects a witness-valid range that escapes its complete domain' \
  'removed structural slice assignment cannot be replaced by same text' \
  'SPINAL-PARAMETERIZED-VERILOG-PROCESS-SLICE-DOMAIN-UNSUPPORTED' \
  'SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SLICE-ANCHOR-MISMATCH'; do
  grep -Fq "$token" "$structural_identity_tests"
done

grep -Fq \
  'literal typed resize delegates to byte-authoritative native behavior' \
  morphhdl/src/test/scala/spinal/core/TypedElaborationPrimitiveTests.scala
for token in \
  'typed log2Up retains exact numeric semantics including zero-width results' \
  'typed log2Up projects exact values in each active depth branch'; do
  grep -Fq "$token" \
    morphhdl/src/test/scala/spinal/core/TypedElaborationPrimitiveTests.scala
done
for token in \
  'a named retained resize cannot bypass complete crossing-domain validation' \
  'a named unsigned grow rewrites the exact native witness prefix' \
  'a symbolic signed grow fails before freezing its witness sign index'; do
  grep -Fq "$token" \
    morphhdl/src/test/scala/morphhdl/CapturedDomainWidthEquivalenceTests.scala
done

for token in \
  'SPINAL-PARAMETERIZED-VERILOG-PROCESS-ASSIGNMENT-EVIDENCE-STALE' \
  'SPINAL-PARAMETERIZED-VERILOG-PROCESS-SLICE-EMITTED-CARDINALITY-MISMATCH'; do
  grep -Fq "$token" "$process_backend"
done
for token in \
  'a live retained procedural assignment rewrites only its exact slices' \
  'a removed procedural assignment cannot be replaced by copied marker text' \
  'coincident fixed and retained procedural slices remain distinct or fail closed'; do
  grep -Fq "$token" "$procedural_identity_tests"
done

python3 - .github/workflows/increment-53f-typed-primitives.yml <<'PY'
from pathlib import Path
import sys

workflow = Path(sys.argv[1]).read_text(encoding="utf-8")
typed_start = workflow.index("  typed-regressions:")
typed_end = workflow.index("  primitive-v2001:", typed_start)
typed_regressions = workflow[typed_start:typed_end]
start = workflow.index("  no-shadow:")
end = workflow.index("  closure:", start)
no_shadow = workflow[start:end]
required_suites = (
    "morphhdl.frontend.HdlIntTests",
    "spinal.core.TypedExactDomainSafetyTests",
    "spinal.core.CentralTypedAuthorityAdversarialTests",
    "spinal.core.CounterRegistryAtomicityTests",
    "spinal.core.NativeIntFormalizationAtomicityTests",
    "spinal.core.ShadowStructuralPredicateAuthorityTests",
    "spinal.core.FiniteMemIdentityAdversarialTests",
    "spinal.core.FiniteFormalBoundaryTests",
    "spinal.core.FiniteBitsIndexTests",
    "spinal.core.ScalarStructuralIdentityAdversarialTests",
    "spinal.core.ProceduralIdentityAdversarialTests",
    "spinal.core.internals.RetainedWidthExpressionEquivalenceTests",
    "spinal.core.internals.ParameterizedVerilogTests",
    "morphhdl.CounterSingleAuthorityParityTests",
    "morphhdl.GenericProcessLoweringTests",
    "morphhdl.ParameterizedStreamFifoDepthTests",
    "morphhdl.StreamFifoCompatibilityTests",
)
for job, section in (
    ("typed-regressions", typed_regressions),
    ("no-shadow", no_shadow),
):
    for suite in required_suites:
        if suite not in section:
            raise SystemExit(
                f"{job} closure job does not exercise required suite {suite}"
            )
    command = '"frontend/testOnly morphhdl.frontend.HdlIntTests"'
    if section.count(command) != 1:
        raise SystemExit(
            f"{job} closure job must run the exact frontend authorization suite once"
        )
PY

python3 - "$formal_component" "$formal_registry" "$hierarchy_backend" "$publisher_backend" "$external_spinal" "$morph_verilog" <<'PY'
from pathlib import Path
import sys

formal_component, registry, hierarchy, publisher, external_spinal, morph_verilog = [
    Path(value).read_text(encoding="utf-8") for value in sys.argv[1:]
]

for forbidden in ("declarationKey", "ownerClassName"):
    if forbidden in formal_component:
        raise SystemExit(
            f"typed ElabFormalComponent must not construct or inspect legacy {forbidden} authority"
        )

def section(source: str, signature: str) -> str:
    start = source.index(signature)
    candidates = [
        value for value in (
            source.find("\n  private def ", start + len(signature)),
            source.find("\n  private[", start + len(signature)),
            source.find("\n  def ", start + len(signature)),
        ) if value >= 0
    ]
    return source[start:min(candidates) if candidates else len(source)]

typed_registry = section(registry, "private[spinal] def retainTypedComponent(")
for forbidden in (
    "validateDeclarationForDesign(",
    "validateInstanceBinding(",
    "retainDeclaration(",
    "retainInstanceBinding(",
    "validateBinding(",
    "binding.declarationKey",
    "binding.ownerClassName",
):
    if forbidden in typed_registry:
        raise SystemExit(
            f"opaque typed formal registration reaches legacy authority through {forbidden}"
        )

typed_payload = section(registry, "private def validateTypedBindingPayload(")
for forbidden in ("declarationKey", "ownerClassName", "getClass.getName"):
    if forbidden in typed_payload:
        raise SystemExit(
            f"opaque typed formal payload validation consults legacy authority through {forbidden}"
        )

for signature in (
    "private def validateTypedMappedFormalIdentity(",
    "private def validateTypedOneSideFormalIdentity(",
):
    typed_hierarchy = section(hierarchy, signature)
    for forbidden in ("declarationKey", "ownerClassName", "getClass.getName"):
        if forbidden in typed_hierarchy:
            raise SystemExit(
                f"typed hierarchy authority helper {signature} consults {forbidden}"
            )

for signature in (
    "private def validateTypedCanonicalSlots(",
    "private def validateTypedComponentFormalSlot(",
):
    typed_publisher = section(publisher, signature)
    for forbidden in ("declarationKey", "ownerClassName", "getClass.getName"):
        if forbidden in typed_publisher:
            raise SystemExit(
                f"typed publication authority helper {signature} consults {forbidden}"
            )

if "components.groupBy(componentName)" in publisher:
    raise SystemExit(
        "external publication must not reconstruct canonical component groups from names"
    )
for required in (
    "emittedCanonicalOf: Component => Component",
    "val canonicalByIdentity = new IdentityHashMap[Component, Component]()",
    "if (terminal ne canonical)",
    "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-CANONICAL-MAP-MISSING",
    "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-CANONICAL-IDENTITY-MISSING",
    "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-CANONICAL-IDENTITY-FOREIGN",
    "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-CANONICAL-IDENTITY-INCONSISTENT",
):
    if required not in publisher:
        raise SystemExit(
            f"external publication lacks exact canonical-identity guard: {required}"
        )

for required in (
    "def transformWithCanonicalIdentity[",
    "phases.collect { case phase: PhaseVerilog => phase }",
    "if (emitters.size != 1)",
    "emitter.emitedComponentRef.get(value)",
):
    if required not in external_spinal:
        raise SystemExit(
            f"external Spinal boundary lacks exact emitter identity capture: {required}"
        )

for required in (
    "ExternalSpinalVerilog.transformWithCanonicalIdentity",
    "MorphHdlExternalParameterizedVerilog.rewrite(pc, canonicalOf)",
):
    if required not in morph_verilog:
        raise SystemExit(
            f"single-source publication does not carry exact emitter identity: {required}"
        )
PY

for token in \
  'typed literal child formal matches the established literal formal graph and RTL' \
  'typed literal child formals reject non-literals and values outside finite bounds' \
  'correlated exact child actual is projected before exact-extrema validation' \
  'correlated exact foreach and countOne retain their four-wide function' \
  'same-class typed child instances retain distinct opaque formal tokens' \
  'different child classes with one exact layout share canonical typed RTL' \
  'different child classes with different layouts fail closed before typed publication' \
  'duplicate and typed-to-legacy claims are atomic despite matching diagnostic text' \
  'a matching constructor legacy claim blocks typed registration before token or RTL publication' \
  'same-name distinct emitted canonical terminals fail without textual regrouping or RTL' \
  'typed and established finite ranges admit zero defaults independently' \
  'inexact symbolic foreach and countOne fail before publishing RTL'; do
  grep -Fq "$token" "$finite_formal_tests"
done
grep -Fq 'SPINAL-ELAB-FORMAL-ACTUAL-LITERAL-INVALID' "$formal_component"
grep -Fq 'SPINAL-ELAB-FORMAL-DOMAIN-INVALID' "$formal_component"

for token in \
  'symbolic formal helpers remain live at depths 1, 3, 5 and 8' \
  'symbolic formal helpers support one-stage storage and Vec-storage domains' \
  '("vec_storage", 1, 1, 8, Vector(1, 3, 4, 5, 8), true)' \
  'Vector("DEPTH" -> BigInt(1))' \
  'typed literal formal helpers preserve ordinary concrete RTL' \
  'symbolic formal helpers reject a nonpositive depth domain' \
  'PASS formal helpers DEPTH='; do
  grep -Fq "$token" "$stream_fifo_tests"
done
grep -Fq 'full-config typed literal Vec storage is byte-identical to native Int construction' \
  "$stream_fifo_compatibility_tests"

grep -Fq 'class TypedParameterizedVecFormalEquivalenceTests' "$vec_formal"
grep -Fq 'class TypedPrimitiveClosureFormalEquivalenceTests' "$closure_formal"
for formal in "$vec_formal" "$closure_formal"; do
  grep -Fq 'expectedStatus = "PASS"' "$formal"
  grep -Fq 'expectedStatus = "FAIL"' "$formal"
done

bash morphhdl/scripts/check-external-memory-boundary.sh
bash morphhdl/scripts/check-native-streamfifo-structure-boundary.sh
python3 morphhdl/scripts/check-native-source-preservation.py \
  --manifest morphhdl/contracts/native-source-preservation.json
python3 morphhdl/scripts/check-typed-native-source-overlay.py

printf 'Increment 53f typed primitive-closure architecture boundary passed.\n'
