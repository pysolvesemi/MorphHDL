#!/usr/bin/env python3
"""WA-10 current-source safety contract; executable equivalence remains required."""
from __future__ import annotations

import argparse
import importlib.util
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
EMITTER = "core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala"
POLICY = "core/src/main/scala/spinal/core/internals/VerilogEmitterExpressionInlining.scala"
COPY = "core/src/main/scala/spinal/core/internals/NativePureExpressionCopy.scala"
CODEC = "morphhdl-passes/examples/NativeWireExpressionCodec.scala"
NATIVE = "morphhdl-passes/examples/NamedWireExpressionNativeBridge.scala"
SHARED = "morphhdl-passes/examples/NamedWireAliasNativeBridge.scala"
CANONICAL = "morphhdl-passes/src/main/scala/morphhdl/passes/transform/UnnamedWireExpressionEliminationPass.scala"
WORKFLOW = ".github/workflows/morphhdl-passes.yml"
PRODUCTION_CHECKER = "morphhdl/scripts/check-wa08-production-artifacts.py"

# These are safety contracts, not semantic proofs. The source seal covers every
# byte, and current compiled tests and enabled/disabled RTL equivalence cover
# behavior. WA-09's old capability exclusions remain in historical evidence.
MARKERS = {
    EMITTER: (
        "VerilogEmitterExpressionInlining.redundantWrappers",
        "private lazy val wrappersProvenRedundant",
        "val requiredBeforeDepthCut = new java.util.IdentityHashMap",
        "requiredBeforeDepthCut.containsKey(literal) || !wrappersProvenRedundant.containsKey(literal)",
        "case _ => true",
    ),
    POLICY: (
        "def redundantWrappers", "isEnabled", "IdentityHashMap",
        "ParameterizedWidth.expressionOf", "ParameterizedWidth.resizeExpressionOf",
        "isUnannotated", "referenceRequired", "count.intValue == 1",
        "target.isEmptyOfTag", "dataAssignments == 1", "visiting", "budget = 256",
        "def independentBoolean", "wrappers.trimEnd(wrappers.size - checkpoint)",
        "def eligibleSelection", "lo <= otherHi && otherLo <= hi",
        "case _ => valid = false", "if (collect(root, contextWidth)) publish(wrappers)",
    ),
    CODEC: (
        "IdentityHashMap", "active.remove(value)", "RtlExpr.Resize",
        "Operator.BitVector.Sub", "literal.getWidth", "literal.hasPoison()",
        "def fixedWidthTree", "def fenced", "captureInScope",
    ),
    COPY: (
        "var remaining = 256", "case reference: BaseType => return reference",
        "tagged.isEmptyOfTag", "active.put(source", "result.getClass == source.getClass",
        "ParameterizedWidth.resizeExpressionOf(from).isEmpty",
        "result.setScalaLocated(source)", "to.inferredWidth = from.inferredWidth",
        "to.widthWhenNotInferred = from.widthWhenNotInferred", "active.remove(source)",
    ),
    NATIVE: (
        "rewriteNativeIdentity", "sameRetainedWidth", "captureScope",
        "candidate.receiverOccurrenceCount > 32",
        "expressionNodeCount(candidate.sourceExpression) > 64",
        "candidate.receiverOccurrenceCount * expressionNodeCount(candidate.sourceExpression) > 256",
        "allowRegisterRhs = true", "NativeWireExpressionCodec.fenced",
        "DriverKind.Procedural else DriverKind.Continuous",
        "runWithNativeNonblockingReceivers", "snapshot.nonblockingReceivers",
        "val receiverSuppliesFence = (copiedSource eq candidate.alias) &&",
        "samePackedBoundary(assignment.finalTarget, candidate.alias)",
        "if (!receiverSuppliesFence && ParameterizedWidth.expressionOf(candidate.alias).isEmpty &&",
    ),
    SHARED: (
        "allowRegisterRhs: Boolean = false", "allowedRegisterRhs",
        "hasClockDomainUse", "hasReferencedMetadata", "preservationMetadataAllows",
    ),
    CANONICAL: (
        "DriverKind.Continuous", "DriverCoverage.FullObject",
        "receiverSelectionViolations", "scopeIsAncestor", "createsCombinationalCycle",
        "cloneForReceiver", "filterNot(_.id == aliasDriver.id)",
        "declarations.filterNot(_.id == aliasSymbol)",
        "case DriverKind.Procedural if nonblockingReceivers.contains(module.id -> receiver.id)",
        ".exists(_.kind == DeclarationKind.Register)", "fencedReplacement",
        "private[morphhdl] def runWithNativeNonblockingReceivers",
        "require(module.drivers.exists(_ eq driver)",
        "nonblockingReceivers: Set[(ModuleId, DriverId)] = Set.empty",
    ),
    WORKFLOW: (
        "check-wa10-general-expression.py --self-test",
        "check-wa10-general-expression.py", "run-wa10-regression.sh",
        "wa10-production-${{ matrix.scala }}",
        "core/testOnly *NativePureExpressionCopyTests *VerilogEmitterExpressionInliningTests",
        "morph/testOnly *NativeWireExpressionCodecTests *MorphVerilogExpressionInliningTests",
        "NativeExpressionDiagnosticsWriter timing", "NativeExpressionDiagnosticsWriter general",
        "WA10_DISABLED_LEGACY_BYTES_PASS files=2",
    ),
    PRODUCTION_CHECKER: (
        'assert declares_wire(reference, "sampledAlias")',
        'assert re.search(r"\\bsampledAlias\\b", enabled) is None',
        'kind, "sampled register or symbolic width was not preserved"',
        'assert "assign registeredResult = sampled;" in source',
        'assert "sampled <= sampledAlias;" in reference',
        'assert "sampled <= (a ^ b);" in enabled',
        'for name in protected_aliases + ineligible_direct_port_aliases:',
        'assert declares_wire(reference, name) and declares_wire(enabled, name)',
        'for width in range(1, 17):',
        '"sat -seq 4 -set-init-zero -verify -prove ok 1 -show-inputs"',
        'assert "WA08 NAMED FOUR STATE PASS cases=64" in simulation and "FAIL" not in simulation',
        'failed = run("mutation", ["yosys", "-Q", "-p", proof_command(8)], success=False)',
    ),
}


def source_scope(root: Path):
    spec = importlib.util.spec_from_file_location(
        "wa10_current_source_scope", root / "morphhdl/scripts/check-wa10-source-scope.py")
    if spec is None or spec.loader is None:
        raise RuntimeError("WA10-CONTRACT: cannot load successor source scope")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def failures(path: str, source: str) -> list[str]:
    errors = ["WA10-SAFETY-MARKER: " + path + ": " + marker
              for marker in MARKERS.get(path, ()) if marker not in source]
    if path in (EMITTER, POLICY, COPY, CODEC, NATIVE, CANONICAL):
        # Native fixture writers share source files with phases. Only the real
        # phase/codec/policy belongs to the generic compiler surface.
        source = source.split("object ParameterizedStreamFifoNamedExpressionPassWitness", 1)[0]
        source = "\n".join(line for line in source.splitlines()
                           if not line.strip().startswith("import "))
        source = re.sub(r"/\*.*?\*/|//[^\n]*", "", source, flags=re.S)
        for code, pattern in (
            ("EMITTED-NAME-RECOGNITION", r'"_zz_[^"]*"'),
            ("APPLICATION-SPECIAL-CASE", r'"(?:TimingExpressionExample|timing_[^"]*)"'),
            ("GENERATED-TEXT-REWRITE", r"\b(?:readAllBytes|readString|fromFile|parseVerilog)\b"),
            ("REGEX-CANDIDATE-DISCOVERY", r"\b(?:Pattern\.compile|scala\.util\.matching\.Regex)\b"),
        ):
            if re.search(pattern, source):
                errors.append("WA10-" + code + ": " + path)
    return errors


def check(root: Path) -> dict[str, str]:
    source_scope(root).verify(root)
    sources = {path: (root / path).read_text() for path in set(MARKERS) | {NATIVE}}
    errors = [error for path, source in sources.items() for error in failures(path, source)]
    if errors:
        raise RuntimeError("\n".join(errors))
    return sources


def self_test(root: Path) -> None:
    sources = check(root)
    controls = 0
    for path, markers in MARKERS.items():
        for marker in markers:
            assert failures(path, sources[path].replace(marker, "WA10_REMOVED")), (path, marker)
            controls += 1
    for path in (EMITTER, POLICY, COPY, CODEC, CANONICAL):
        for attack in ('val candidate = "_zz_7"', 'val component = "TimingExpressionExample"',
                       'val text = readString(path)', 'val matcher = Pattern.compile(name)'):
            assert failures(path, sources[path] + "\n" + attack), (path, attack)
            controls += 1
    # A valid predecessor projection is exact, and an unsealed current byte
    # cannot reach the historical marker check: the outer overlay mutation
    # suite exercises this boundary with committed, staged and dirty sources.
    historical = source_scope(root).historical_sources(root, (EMITTER, CODEC, PRODUCTION_CHECKER))
    assert "def redundantUnsignedAddWrappers()" in historical[EMITTER]
    assert "case _: Resize => None" in historical[CODEC]
    assert 'protected_aliases = ("keptAlias", "guardedAlias", "sampledAlias", "conditionalAlias",' in (
        historical[PRODUCTION_CHECKER])
    print("WA10_CURRENT_CONTRACT_MUTATIONS_PASS controls=" + str(controls))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo-root", type=Path, default=ROOT)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        self_test(args.repo_root)
    else:
        check(args.repo_root)
        print("WA10_CURRENT_CONTRACT_PASS")


if __name__ == "__main__":
    main()
