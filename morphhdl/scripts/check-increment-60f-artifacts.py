#!/usr/bin/env python3
"""Fail-closed inventory, regeneration and cross-Scala evidence gates for 60f."""
from __future__ import annotations

import argparse
import contextlib
import functools
import importlib.util
import io
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET


COUNTS = {"boundaries": 70, "pure": 29, "compatibility": 114}
REGRESSIONS = {
    "paramrtl": (234, 23),
    "frontend": (257, 22),
    "backends/verilog": (148, 21),
    "morphhdl": (819, 78),
    "morphir": (32, 2),
    "morphplugin": (16, 2),
    "core": (5, 1),
    "morphhdl-passes": (99, 11),
}


# Exact suite identities at the 60f qualification boundary. These are frozen
# data, not runtime discovery or a count-only substitute for inherited suites.
EXPECTED_SUITES = {
    "paramrtl": frozenset("""
        morphhdl.paramrtl.AddressWidthExpressionTests
        morphhdl.paramrtl.AsynchronousEnabledRegisterValidatorTests
        morphhdl.paramrtl.AsynchronousRegisterValidatorTests
        morphhdl.paramrtl.BoolExpressionAnalysisTests
        morphhdl.paramrtl.BooleanLocalParameterTests
        morphhdl.paramrtl.BooleanParameterBindingTests
        morphhdl.paramrtl.CeilLog2ExpressionTests
        morphhdl.paramrtl.CombinationalIfValidatorTests
        morphhdl.paramrtl.GenerateCaseValidatorTests
        morphhdl.paramrtl.GenerateForValidatorTests
        morphhdl.paramrtl.GenerateIfValidatorTests
        morphhdl.paramrtl.HierarchyValidatorTests
        morphhdl.paramrtl.IntExpressionAnalysisTests
        morphhdl.paramrtl.MinMaxExpressionTests
        morphhdl.paramrtl.ParamExpressionValidatorTests
        morphhdl.paramrtl.ParamRtlValidatorTests
        morphhdl.paramrtl.SynchronousCounterValidatorTests
        morphhdl.paramrtl.SynchronousEnabledRegisterValidatorTests
        morphhdl.paramrtl.SynchronousReadFirstSimpleDualPortMemoryValidatorTests
        morphhdl.paramrtl.SynchronousReadFirstSinglePortMemoryValidatorTests
        morphhdl.paramrtl.SynchronousRegisterValidatorTests
        morphhdl.paramrtl.SynchronousStreamFifoValidatorTests
        morphhdl.paramrtl.SynchronousStreamM2sPipeValidatorTests
    """.split()),
    "frontend": frozenset("""
        morphhdl.frontend.AddressWidthFrontendTests
        morphhdl.frontend.AsynchronousEnabledRegisterFrontendTests
        morphhdl.frontend.AsynchronousRegisterFrontendTests
        morphhdl.frontend.BooleanLocalParameterFrontendTests
        morphhdl.frontend.BooleanParameterBindingFrontendTests
        morphhdl.frontend.CeilLog2FrontendTests
        morphhdl.frontend.CombinationalIfFrontendTests
        morphhdl.frontend.FrontendProvenanceTests
        morphhdl.frontend.GenerateCaseFrontendTests
        morphhdl.frontend.GenerateForFrontendTests
        morphhdl.frontend.GenerateIfFrontendTests
        morphhdl.frontend.HdlBoolTests
        morphhdl.frontend.HdlIntTests
        morphhdl.frontend.LocalParameterFrontendTests
        morphhdl.frontend.SynchronousCounterFrontendTests
        morphhdl.frontend.SynchronousEnabledRegisterFrontendTests
        morphhdl.frontend.SynchronousReadFirstSimpleDualPortMemoryFrontendTests
        morphhdl.frontend.SynchronousReadFirstSinglePortMemoryFrontendTests
        morphhdl.frontend.SynchronousRegisterFrontendTests
        morphhdl.frontend.SynchronousStreamFifoFrontendTests
        morphhdl.frontend.SynchronousStreamM2sPipeFrontendTests
        morphhdl.frontend.compatibility.SpinalImportCompatibilityTests
    """.split()),
    "backends/verilog": frozenset("""
        morphhdl.backend.verilog2001.AddressWidthEmitterTests
        morphhdl.backend.verilog2001.AsynchronousEnabledRegisterEmitterTests
        morphhdl.backend.verilog2001.AsynchronousRegisterEmitterTests
        morphhdl.backend.verilog2001.BooleanLocalParameterEmitterTests
        morphhdl.backend.verilog2001.BooleanParameterBindingEmitterTests
        morphhdl.backend.verilog2001.CeilLog2EmitterTests
        morphhdl.backend.verilog2001.CombinationalIfEmitterTests
        morphhdl.backend.verilog2001.DerivedWidthEmitterTests
        morphhdl.backend.verilog2001.GenerateCaseEmitterTests
        morphhdl.backend.verilog2001.GenerateIfEmitterTests
        morphhdl.backend.verilog2001.LaneArrayEmitterTests
        morphhdl.backend.verilog2001.MinMaxEmitterTests
        morphhdl.backend.verilog2001.ParameterForwardingEmitterTests
        morphhdl.backend.verilog2001.SynchronousCounterEmitterTests
        morphhdl.backend.verilog2001.SynchronousEnabledRegisterEmitterTests
        morphhdl.backend.verilog2001.SynchronousReadFirstSimpleDualPortMemoryEmitterTests
        morphhdl.backend.verilog2001.SynchronousReadFirstSinglePortMemoryEmitterTests
        morphhdl.backend.verilog2001.SynchronousRegisterEmitterTests
        morphhdl.backend.verilog2001.SynchronousStreamFifoEmitterTests
        morphhdl.backend.verilog2001.SynchronousStreamM2sPipeEmitterTests
        morphhdl.backend.verilog2001.Verilog2001EmitterTests
    """.split()),
    "morphhdl": frozenset("""
        morphhdl.BackendSyncMergeIsolationTests
        morphhdl.BoundedRecursivePowerTests
        morphhdl.BoundedRecursiveSafetyTests
        morphhdl.CapturedAssignmentNormalizationTests
        morphhdl.CapturedDomainWidthEquivalenceTests
        morphhdl.CounterSingleAuthorityParityTests
        morphhdl.ExternalHierarchyBoolLiteralBindingTests
        morphhdl.FormalParameterClonePropagationTests
        morphhdl.FormalParameterIdentityTests
        morphhdl.GenericExpressionAndStreamTests
        morphhdl.GenericProcessLoweringTests
        morphhdl.HierarchyParameterBindingTests
        morphhdl.MorphCanonicalIrHandoffTests
        morphhdl.MorphSingleSourceVerilogTests
        morphhdl.MorphVerilogTests
        morphhdl.NativeAxi4SlaveFactoryFormalEquivalenceTests
        morphhdl.NativeAxi4SlaveFactoryParameterizedOffsetTests
        morphhdl.NativeLibraryMigrationFormalEquivalenceTests
        morphhdl.NativeLibraryMigrationTests
        morphhdl.NativeLibraryReuseTests
        morphhdl.NativeStreamFifoCCCdcProofTests
        morphhdl.NativeStreamFifoCCFormalEquivalenceTests
        morphhdl.NativeStreamFifoCCParameterizedTests
        morphhdl.NativeStreamFifoFormalEquivalenceTests
        morphhdl.NativeSymbolicMemoryTests
        morphhdl.NativeTypedLibraryCallSurfaceTests
        morphhdl.NaturalSymbolicConditionalTests
        morphhdl.ParameterizedStreamFifoDepthTests
        morphhdl.ParameterizedStreamWidthAdapterTests
        morphhdl.ReduceBalancedTreeNativeContractTests
        morphhdl.SpinalEnumLocalParameterTests
        morphhdl.StreamFifoCompatibilityTests
        morphhdl.StructuralGenerateControlTests
        morphhdl.TypedBlackBoxGenericBindingTests
        morphhdl.TypedCounterAllOnesTests
        morphhdl.TypedElaborationControlTests
        morphhdl.TypedElaborationValueTests
        morphhdl.TypedParameterizedFactoryDirectionTests
        morphhdl.TypedParameterizedVecFormalEquivalenceTests
        morphhdl.TypedParameterizedVecTests
        morphhdl.TypedPrimitiveClosureFormalEquivalenceTests
        morphhdl.TypedStreamWidthAdapterFormalEquivalenceTests
        morphhdl.integration.ExternalSpinalVerilogBaselineTests
        spinal.core.CentralTypedAuthorityAdversarialTests
        spinal.core.FiniteAffineVecReadTests
        spinal.core.FiniteBitsIndexTests
        spinal.core.FiniteFormalBoundaryTests
        spinal.core.FiniteMemIdentityAdversarialTests
        spinal.core.PackedVecIdentityAdversarialTests
        spinal.core.ProceduralIdentityAdversarialTests
        spinal.core.ScalarStructuralIdentityAdversarialTests
        spinal.core.StructuralIdentityAdversarialTests
        spinal.core.TypedElaborationPrimitiveTests
        spinal.core.TypedExactDomainControlTests
        spinal.core.TypedExactDomainSafetyTests
        spinal.core.TypedPrimitiveClosureTests
        spinal.core.TypedProjectionOwnershipTests
        spinal.core.TypedVecShapeTests
        spinal.core.VecEmittedIdentityAdversarialTests
        spinal.core.internals.ParameterizedDataShapeTests
        spinal.core.internals.ParameterizedVerilogStructuralLexicalTests
        spinal.core.internals.ParameterizedVerilogTests
        spinal.core.internals.PureSIntCastTests
        spinal.core.internals.RetainedWidthExpressionEquivalenceTests
        spinal.core.internals.SignedDeclarationPublicationTests
        spinal.core.internals.SignednessBoundaryTests
        spinal.core.internals.SignednessCompatibilityTests
        spinal.core.internals.TypedBalancedReductionCallbackPolicyTests
        spinal.core.internals.TypedBalancedReductionCaptureSafetyTests
        spinal.core.internals.TypedBalancedReductionCaptureTests
        spinal.core.internals.TypedBalancedReductionClosedGraphTests
        spinal.core.internals.TypedBalancedReductionOperatorReplayTests
        spinal.core.internals.TypedBalancedReductionPlanTests
        spinal.core.internals.TypedBalancedReductionPublicationSafetyTests
        spinal.core.internals.TypedBalancedReductionPublicationTests
        spinal.core.internals.TypedBalancedReductionStageReplayTests
        spinal.core.internals.TypedSignednessAuthorityTests
        spinal.core.internals.TypedSignednessResumeTests
    """.split()),
    "morphir": frozenset("""
        morphhdl.ir.v1.CanonicalIrHandoffSpec
        morphhdl.ir.v1.CanonicalIrV1Spec
    """.split()),
    "morphplugin": frozenset("""
        morphhdl.compiler.MorphHdlTypedElaborationControlComponentTests
        morphhdl.compiler.MorphHdlFrontendSymbolicEqualitySafetyComponentTests
    """.split()),
    "core": frozenset("""
        spinal.core.internals.SpinalVerilogPhasePlanTests
    """.split()),
    "morphhdl-passes": frozenset("""
        morphhdl.passes.adapter.CanonicalIrPassAdapterSpec
        morphhdl.passes.api.AllPassConfigurationSpec
        morphhdl.passes.api.PassContractsSpec
        morphhdl.passes.pipeline.WireAliasPassPipelineSpec
        morphhdl.passes.pipeline.WireAssignmentAllPassPipelineSpec
        morphhdl.passes.safety.WireAliasSafetyGateSpec
        morphhdl.passes.transform.NamedWireAliasEliminationPassSpec
        morphhdl.passes.transform.UnnamedWireAliasEliminationPassSpec
        morphhdl.passes.transform.UnnamedWireExpressionAlgebraSpec
        morphhdl.passes.transform.UnnamedWireExpressionEliminationPassSpec
        morphhdl.passes.transform.UnnamedWireExpressionSelectionSafetySpec
    """.split()),
}

# Approved successor inventory. The original 60f boundary above remains frozen;
# this exact union is admitted only after the successor production source audit.
INCREMENT_59D_SUITES = {
    "morphhdl": {
        "spinal.core.ElaborationWidthAuthorityTests": 7,
        "spinal.core.NativeSymbolicWidthProvenanceTests": 5,
        "spinal.core.internals.NativeWidthPublicationSafetyTests": 6,
        "spinal.core.internals.NativePublicationWidthTests": 1,
        "spinal.core.internals.TypedBalancedReductionWidthTransferTests": 11,
        "spinal.core.internals.TypedBalancedReductionWideningPublicationTests": 6,
    },
}

# 59e adds three suites and one test to each of three inherited suites. Keep
# these separate from the immutable 60f boundary and activate them only with its
# complete source profile, which also requires the 59f callback implementation.
INCREMENT_59E_SUITES = {
    "morphhdl": {
        "spinal.core.NativeCloneShapeContractTests": 4,
        "spinal.core.internals.TypedBalancedReductionCompositeTests": 23,
        "spinal.core.internals.TypedBalancedReductionCompositeCallbackPolicyTests": 12,
    },
}
INCREMENT_59E_INHERITED_TESTS = {
    "morphhdl": {
        "spinal.core.PackedVecIdentityAdversarialTests": 8,
        "spinal.core.internals.TypedBalancedReductionPublicationSafetyTests": 8,
        "spinal.core.internals.TypedBalancedReductionOperatorReplayTests": 24,
    },
}

# The width/composite integration adds one replay regression. Standalone E+F
# retains its exact historical 23-case composite suite.
INCREMENT_59D59E_JOINT_TESTS = {
    "morphhdl": {"spinal.core.internals.TypedBalancedReductionCompositeTests": 24},
}

# Separately reviewed descendants extend the frozen inherited inventory by exact
# suite identity. Presence of arbitrary XML or a matching count grants nothing.
# A complete, tracked feature source inventory activates the reviewed additions;
# heads without that feature retain the exact inventory of their other reviewed features.
SUITE_EXTENSIONS = {
    "59e": {
        "sources": (
            "core/src/main/scala/spinal/core/ParameterizedVecElementLayout.scala",
            "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeCallbackPolicy.scala",
            "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala",
            "morphhdl/src/test/scala/spinal/core/NativeCloneShapeContractTests.scala",
            "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeTests.scala",
            "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCompositeCallbackPolicyTests.scala",
        ),
        "projects": {project: frozenset(additions) for project, additions in INCREMENT_59E_SUITES.items()},
    },
    "59f": {
        "sources": (
            "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCertifiedCallbackPolicy.scala",
            "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCaptureSchema.scala",
            "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionScalarGraphReplay.scala",
            "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCertifiedCallbackPolicyTests.scala",
            "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionScalarGraphReplayTests.scala",
            "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionMuxWidthTests.scala",
            "morphhdl/src/test/scala/spinal/core/internals/TypedBalancedReductionCallbackPublicationTests.scala",
        ),
        "projects": {
            "morphhdl": frozenset("""
                spinal.core.internals.TypedBalancedReductionCertifiedCallbackPolicyTests
                spinal.core.internals.TypedBalancedReductionScalarGraphReplayTests
                spinal.core.internals.TypedBalancedReductionMuxWidthTests
                spinal.core.internals.TypedBalancedReductionCallbackPublicationTests
            """.split()),
        },
    },
}

WA07A_SUITES = frozenset({
    "morphhdl.passes.transform.ConstantOperandFixedPointSpec",
    "morphhdl.passes.transform.ConstantOperandFourStateSpec",
    "morphhdl.passes.transform.ConstantOperandSimplificationPassSpec",
})

def require(ok: bool, message: str) -> None:
    if not ok:
        raise RuntimeError(message)


def exact_names(actual: set[str], expected: set[str], label: str) -> None:
    require(actual == expected,
            f"{label}: missing={sorted(expected - actual)}, unexpected={sorted(actual - expected)}")


def compatibility_names() -> set[str]:
    kinds = ("pure", "declarations", "bundles")
    native_modes = ("before", "declarations", "cleanup", "after")
    morph_modes = ("disabled-before", "declarations", "cleanup", "declarations-after",
                   "disabled-explicit", "disabled-after")
    names = {f"compatibility/{kind}/native-{width}-{mode}.{extension}"
             for kind in kinds for width in (1, 5, 8, 32) for mode in native_modes
             for extension in ("v", "vhd")}
    names |= {f"compatibility/{kind}/morph-{mode}.v" for kind in kinds for mode in morph_modes}
    require(len(names) == COUNTS["compatibility"], "compatibility identity inventory changed")
    return names


@functools.lru_cache(maxsize=1)
def closure_module():
    repository = Path(__file__).resolve().parents[2]
    scripts = str(repository / "morphhdl/scripts")
    if scripts not in sys.path:
        sys.path.insert(0, scripts)
    path = Path(scripts) / "check-increment-60f-equivalence-closure.py"
    spec = importlib.util.spec_from_file_location("signedness_closure_inventory", path)
    require(spec is not None and spec.loader is not None, "cannot load inherited artifact inventory")
    closure = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(closure)
    return closure


def inventory(root: Path) -> dict[str, str]:
    # Reuse the authoritative inherited identities and immutable 60a hash gate.
    # This reads generated bytes only; it does not run HDL tools or alter RTL.
    repository = Path(__file__).resolve().parents[2]
    closure = closure_module()
    inherited = closure.inventory(repository, root)
    require(len(inherited) == COUNTS["boundaries"] + COUNTS["pure"], "inherited identity inventory changed")
    expected = set(inherited) | compatibility_names()
    paths = sorted(p for p in root.rglob("*")
                   if p.is_file() and p.suffix in (".v", ".vhd", ".vhdl"))
    exact_names({p.relative_to(root).as_posix() for p in paths}, expected, "RTL file identities")
    result = {}
    for path in paths:
        require(path.stat().st_size > 0, f"empty artifact: {path}")
        result[path.relative_to(root).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    require(all(result[name] == digest for name, digest in inherited.items()),
            "inherited artifacts changed while computing the inventory")
    require(len(result) == sum(COUNTS.values()), "incomplete RTL inventory")
    return result


def snapshot(left: Path, right: Path, output: Path, scala: str) -> None:
    output.unlink(missing_ok=True)
    a, b = inventory(left), inventory(right)
    require(a == b, "fresh-JVM regeneration differs: " +
            str([name for name in sorted(set(a) | set(b)) if a.get(name) != b.get(name)]))
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    output.write_text(json.dumps({"head": head, "scala": scala, "sha256": a}, indent=2) + "\n")
    print(f"60f fresh-JVM byte identity: {len(a)} RTL files, Scala {scala}, {head}")


def compare(left: Path, right: Path) -> None:
    a = json.loads((left / "rtl-manifest.json").read_text())
    b = json.loads((right / "rtl-manifest.json").read_text())
    require({a["scala"], b["scala"]} == {"2.12.18", "2.13.12"}, "both supported Scala lanes are required")
    require(a["head"] == b["head"], "Scala artifacts come from different commits")
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    require(a["head"] == head, "downloaded artifacts do not match this checkout")
    ai, bi = inventory(left / "rtl"), inventory(right / "rtl")
    require(ai == a["sha256"] and bi == b["sha256"], "downloaded RTL does not match its manifest")
    require(ai == bi, "cross-Scala RTL differs: " +
            str([name for name in sorted(set(ai) | set(bi)) if ai.get(name) != bi.get(name)]))
    print(f"60f cross-Scala byte identity: {len(ai)} files at {a['head']}")


def catalog_for_profile(profile: str) -> tuple[dict, dict, dict]:
    features = closure_module().profile_features(profile)
    counts, suites = dict(REGRESSIONS), dict(EXPECTED_SUITES)
    extension = {}
    if "wa07a" in features:
        counts["morphhdl-passes"] = (123, 14)
        suites["morphhdl-passes"] |= WA07A_SUITES
    if "59d" in features:
        extension = {project: dict(additions) for project, additions in INCREMENT_59D_SUITES.items()}
        for project, additions in extension.items():
            require(not suites[project].intersection(additions), "59d inventory replaced an inherited suite")
            suites[project] |= frozenset(additions)
            old_tests, old_suites = counts[project]
            counts[project] = (old_tests + sum(additions.values()), old_suites + len(additions))
    for name in SUITE_EXTENSIONS:
        if name in features:
            for project, additions in SUITE_EXTENSIONS[name]["projects"].items():
                require(not suites[project].intersection(additions),
                        "reviewed suite addition duplicates inherited identity: " + name)
                suites[project] |= additions
                minimum, old_suites = counts[project]
                if name == "59e":
                    exact_new = INCREMENT_59E_SUITES[project]
                    exact_inherited = INCREMENT_59E_INHERITED_TESTS[project]
                    require(set(exact_inherited) <= EXPECTED_SUITES[project],
                            "59e inherited test counts escaped the frozen suite inventory")
                    reviewed_counts = extension.setdefault(project, {})
                    require(not set(reviewed_counts).intersection((*exact_new, *exact_inherited)),
                            "59e exact test counts replaced another reviewed feature")
                    reviewed_counts.update(exact_new)
                    reviewed_counts.update(exact_inherited)
                    # The three inherited suites each gain one adversarial test.
                    minimum += sum(exact_new.values()) + len(exact_inherited)
                    if {"59d", "59e", "59f"}.issubset(features):
                        for suite, count in INCREMENT_59D59E_JOINT_TESTS[project].items():
                            require(suite in exact_new and count > exact_new[suite],
                                    "joint width/composite count escaped its reviewed suite")
                            minimum += count - exact_new[suite]
                            reviewed_counts[suite] = count
                counts[project] = (minimum, old_suites + len(additions))
    return counts, suites, extension


def descendant_extensions(root: Path) -> tuple[str, ...]:
    selected = []
    for name, extension in SUITE_EXTENSIONS.items():
        sources = extension["sources"]
        result = subprocess.run(["git", "ls-tree", "-r", "--name-only", "HEAD", "--", *sources],
                                cwd=root, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        require(result.returncode == 0,
                "cannot inspect reviewed suite source inventory for " + name + ": " + result.stderr)
        tracked = set(result.stdout.splitlines())
        present = [(root / path).is_file() for path in sources]
        if tracked or any(present):
            require(tracked == set(sources) and all(present),
                    "incomplete or uncommitted reviewed suite source inventory for " + name)
            selected.append(name)
    return tuple(selected)


def regressions(root: Path, output: Path) -> None:
    output.unlink(missing_ok=True)
    # Source validation precedes report discovery. XML and feature-file presence
    # cannot select a successor contract or bypass inherited 59d authority audits.
    closure = closure_module()
    profile = closure.regression_profile(root)
    if "59d" in closure.profile_features(profile):
        closure.source_scope(root)
    _regression_inventory(root, output, profile)


def _regression_inventory(root: Path, output: Path, profile: str) -> None:
    """Validate reports only after the caller has validated the source profile."""
    features = closure_module().profile_features(profile)
    extensions = descendant_extensions(root)
    require(extensions == tuple(name for name in SUITE_EXTENSIONS if name in features),
            "committed suite sources differ from the validated production profile")
    counts, suite_inventory, extension = catalog_for_profile(profile)
    records = {}
    for project, (minimum_tests, minimum_suites) in counts.items():
        reports = sorted((root / project / "target/test-reports").glob("*.xml"))
        names = set()
        tests = 0
        for path in reports:
            suite = ET.parse(path).getroot()
            require(suite.tag == "testsuite", f"unexpected test report: {path}")
            name = suite.get("name")
            require(bool(name) and name not in names, f"missing or duplicate suite: {path}")
            names.add(name)
            require(all(int(suite.get(key, "0")) == 0 for key in ("failures", "errors", "skipped")),
                    f"failed or skipped regression: {path}: {suite.attrib}")
            require(not list(suite.iter("skipped")) and not list(suite.iter("failure"))
                    and not list(suite.iter("error")), f"unsuccessful test result: {path}")
            count = int(suite.get("tests", "0"))
            added_count = extension.get(project, {}).get(name)
            if added_count is not None:
                require(count == added_count, f"changed exact reviewed test inventory: {name}: {count}")
            cases = suite.findall("testcase")
            require(count > 0 and len(cases) == count, f"empty/inconsistent suite: {path}")
            case_names = [case.get("name") for case in cases]
            require(all(case_names) and len(set(case_names)) == count,
                    f"missing or duplicated testcase identities: {path}")
            tests += count
        require(tests >= minimum_tests and len(names) >= minimum_suites,
                f"missing {project} regressions: tests={tests}, suites={len(names)}")
        expected = suite_inventory[project]
        require(len(expected) == minimum_suites, f"inconsistent frozen suite inventory: {project}")
        exact_names(names, expected, project + " suite identities")
        records[project] = {"tests": tests, "suites": sorted(names), "skipped": 0}
        print(f"{project}: {tests} tests / {len(names)} suites, zero failures/errors/skips")
    output.write_text(json.dumps(records, indent=2) + "\n")
    print("60f all inherited regressions:", sum(x["tests"] for x in records.values()),
          "non-skipped tests; validated source profile:", profile)


def self_test() -> None:
    """Synthetic Git/source/report controls only; never HDL qualification evidence."""
    from unittest import mock

    rejections = 0

    def rejected(action, label: str) -> None:
        nonlocal rejections
        try:
            action()
        except (RuntimeError, FileNotFoundError, subprocess.CalledProcessError):
            rejections += 1
            return
        raise RuntimeError("inventory self-test accepted " + label)

    expected = compatibility_names()
    name = min(expected)
    exact_names(expected, expected, "positive inventory control")
    rejected(lambda: exact_names(expected - {name}, expected, "missing"), "missing RTL")
    rejected(lambda: exact_names((expected - {name}) | {"compatibility/wrong.v"}, expected, "renamed"),
             "same-count renamed RTL")
    rejected(lambda: exact_names(expected | {"unexpected.v"}, expected, "extra"), "extra RTL")
    with tempfile.TemporaryDirectory(prefix="increment-60f-inventory-self-test-") as temporary:
        root = Path(temporary)
        closure = closure_module()

        def git(*args: str) -> str:
            return subprocess.check_output(["git", *args], cwd=root, text=True,
                                           stderr=subprocess.PIPE).strip()

        def write(path: str, data: bytes) -> None:
            target = root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)

        def commit() -> str:
            git("add", ".")
            git("-c", "user.name=Synthetic Test", "-c", "user.email=synthetic@example.invalid",
                "commit", "-qm", "synthetic source contract fixture")
            return git("rev-parse", "HEAD")

        git("init", "-q")
        wa_paths = tuple(closure.WA07A_PRODUCTION_SHA256)
        callback_paths = tuple(closure.CALLBACK_59F_PRODUCTION_SHA256)
        feature_sources = SUITE_EXTENSIONS["59f"]["sources"]
        composite_feature_sources = SUITE_EXTENSIONS["59e"]["sources"]
        composite_paths = tuple(path for path in composite_feature_sources if "/src/main/" in path) + tuple(
            f"morphhdl/src/main/scala/Synthetic59e{index}.scala" for index in range(11))
        require(len(composite_paths) == 14, "synthetic 59e fixture changed its exact source inventory")
        callback_new = {path for path in feature_sources if "/src/main/" in path}
        write(".gitignore", b"**/target/\n/ignored/\n/result.json\n")
        for path in (*wa_paths[:2], *(path for path in callback_paths if path not in callback_new)):
            write(path, b"synthetic inherited source\n")
        other_source = "core/src/main/scala/Synthetic.scala"
        write(other_source, b"synthetic sealed production\n")
        base = commit()
        write("qualification.txt", b"synthetic qualification-only commit\n")
        qualified = commit()
        source_bytes = {path: ("synthetic reviewed follow-on: " + path + "\n").encode()
                        for path in (*wa_paths, *callback_paths, *composite_paths)}
        composite_hashes = {path: hashlib.sha256(source_bytes[path]).hexdigest() for path in composite_paths}
        wa_hashes = {path: hashlib.sha256(source_bytes[path]).hexdigest() for path in wa_paths}
        callback_hashes = {path: hashlib.sha256(source_bytes[path]).hexdigest() for path in callback_paths}
        # A real immutable Git blob anchors each joint edit's full restoration.
        # It need not be an ancestor of the independent profile fixture heads.
        for path in callback_paths:
            write(path, source_bytes[path])
        integration_base = commit()
        git("branch", "synthetic-callback-baseline", integration_base)
        git("reset", "--hard", qualified)
        integration_path = closure.INTEGRATION_59D59F_CONTRACT
        integration_bytes = {
            path: source_bytes[path] + b"synthetic reviewed joint widthAt integration\n"
            for path in closure.INTEGRATION_59D59F_PATHS}
        integration_review = {"base": integration_base, "files": [
            {"path": path,
             "before_sha256": callback_hashes[path],
             "after_sha256": hashlib.sha256(data).hexdigest(),
             "edits": [{"id": "restore-exact-joint-widthAt",
                        "before": source_bytes[path].decode(), "after": data.decode()}]}
            for path, data in sorted(integration_bytes.items())]}
        integration_manifest = (json.dumps(integration_review, indent=2) + "\n").encode()
        integration_sha256 = hashlib.sha256(integration_manifest).hexdigest()
        publisher_path = "morphhdl/scripts/check-increment-59f-source-scope.py"
        repository = Path(__file__).resolve().parents[2]
        publisher = closure.load(repository, "59f-source-scope")
        composite_integration_path = publisher.COMPOSITE_59DE_PRODUCTION_CONTRACT
        composite_policy = publisher.COMPOSITE_59DE_POLICY
        # The copied real validator remains intact. Only the fixture's immutable
        # baseline and pinned manifest identities differ from production.
        for path in (*callback_paths, *composite_paths):
            write(path, source_bytes[path])
        composite_integration_base = commit()
        git("branch", "synthetic-composite-baseline", composite_integration_base)
        git("reset", "--hard", qualified)
        composite_integrated_bytes = source_bytes[composite_policy] + b"synthetic reviewed scalar auto-resize rejection\n"
        composite_integration_review = {"base": composite_integration_base, "files": [{
            "path": composite_policy,
            "before_sha256": composite_hashes[composite_policy],
            "after_sha256": hashlib.sha256(composite_integrated_bytes).hexdigest(),
            "edits": [{"id": "reject-scalar-auto-resize",
                       "before": source_bytes[composite_policy].decode(),
                       "after": composite_integrated_bytes.decode()}]}]}
        composite_integration_manifest = (json.dumps(composite_integration_review, indent=2) + "\n").encode()
        composite_integration_sha256 = hashlib.sha256(composite_integration_manifest).hexdigest()
        publisher_source = (repository / publisher_path).read_text()

        def fixture_publisher(manifest_sha256: str = composite_integration_sha256) -> bytes:
            source = publisher_source
            for name, value in (("COMPOSITE_59EF_BASE", composite_integration_base),
                                ("COMPOSITE_59DE_PRODUCTION_SHA256", manifest_sha256)):
                before = name + ' = "' + getattr(publisher, name) + '"'
                after = name + ' = "' + value + '"'
                require(source.count(before) == 1, "missing unique real publisher fixture identity: " + name)
                source = source.replace(before, after, 1)
            return source.encode()

        descendant_paths = ("core/src/main/scala/Synthetic59d.scala",
                            "morphhdl/src/main/scala/Synthetic59d.scala")
        descendant_bytes = {path: ("synthetic reviewed 59d: " + path + "\n").encode()
                            for path in descendant_paths}
        review_path = "morphhdl/contracts/increment-59d-production-review.json"
        review = {"base": qualified, "files": [
            {"path": path, "sha256": hashlib.sha256(data).hexdigest()}
            for path, data in sorted(descendant_bytes.items())], "checker_edits": [
            {"path": "morphhdl/scripts/check-increment-60e-signedness-boundaries.py",
             "id": "restore-exact-59d-width-seams", "before": "fixture before", "after": "fixture after"}]}
        profiles = tuple((wa, descendant, composite, callbacks)
                         for wa in (False, True) for descendant in (False, True)
                         for composite in (False, True) for callbacks in (False, True)
                         if not composite or callbacks)
        incomplete_profiles = tuple((wa, descendant, True, False)
                                    for wa in (False, True) for descendant in (False, True))

        def profile_name(wa: bool, descendant: bool, composite: bool, callbacks: bool) -> str:
            selected = [name for name, present in (("wa07a", wa), ("59d", descendant),
                                                   ("59e", composite), ("59f", callbacks)) if present]
            return "60f-with-" + "-and-".join(selected) if selected else "60f-baseline"

        def reports(wa: bool, descendant: bool, composite: bool, callbacks: bool) -> None:
            counts, inventories, extension = catalog_for_profile(profile_name(wa, descendant, composite, callbacks))
            for project, (minimum, suites) in counts.items():
                names = inventories[project]
                require(len(names) == suites, "frozen suite count differs: " + project)
                exact_counts = extension.get(project, {})
                inherited = sorted(names - exact_counts.keys())
                counts_by_suite = {name: exact_counts.get(name, 1) for name in names}
                counts_by_suite[inherited[0]] += minimum - sum(counts_by_suite.values())
                directory = root / project / "target/test-reports"
                directory.mkdir(parents=True, exist_ok=True)
                for old in directory.glob("*.xml"):
                    old.unlink()
                for index, suite_name in enumerate(sorted(names)):
                    count = counts_by_suite[suite_name]
                    suite = ET.Element("testsuite", name=suite_name, tests=str(count),
                                       failures="0", errors="0", skipped="0")
                    for case in range(count):
                        ET.SubElement(suite, "testcase", name=f"synthetic-case-{case}")
                    ET.ElementTree(suite).write(directory / f"suite-{index}.xml")

        def fixture_regressions() -> None:
            # Real Git/profile and XML gates run on synthetic bytes. Historical
            # restoration and overlapping real source unions are audited by
            # test-increment-59d-inherited-60f-scope.py and the 59f scope fixtures.
            output.unlink(missing_ok=True)
            profile = closure.regression_profile(root)
            _regression_inventory(root, output, profile)

        output = root / "result.json"

        def reject_reports(label: str) -> None:
            output.write_text('{"stale_success": true}\n')
            with contextlib.redirect_stdout(io.StringIO()):
                rejected(fixture_regressions, label)
            require(not output.exists(), "failed validation retained stale success: " + label)

        # Only fixture identities are patched. All twelve selections execute real
        # Git ancestry/delta, exact bytes, committed feature-source and XML gates.
        with mock.patch.multiple(closure, BASE=base, QUALIFIED_60F=qualified,
                                 INCREMENT_59D_BASE=qualified,
                                 INCREMENT_59D_PRODUCTION_PATHS=frozenset(descendant_paths),
                                 WA07A_PRODUCTION_SHA256=wa_hashes,
                                 CALLBACK_59F_PRODUCTION_SHA256=callback_hashes,
                                 COMPOSITE_59E_PRODUCTION_SHA256=composite_hashes,
                                 INCREMENT_59E_BASE=qualified,
                                 COMPLETED_59F=integration_base,
                                 COMPLETED_59E=integration_base,
                                 INTEGRATION_59D59F_BASE=integration_base,
                                 INTEGRATION_59D59F_SHA256=integration_sha256):
            for wa, descendant, composite, callbacks in incomplete_profiles:
                incomplete_name = profile_name(wa, descendant, composite, callbacks)
                rejected(lambda: catalog_for_profile(incomplete_name),
                         "59e profile name without required 59f callbacks")
                git("reset", "--hard", qualified)
                git("clean", "-fd")
                for path in composite_paths:
                    write(path, source_bytes[path])
                for path in composite_feature_sources:
                    if "/src/test/" in path:
                        write(path, b"// synthetic composite test fixture\n")
                if wa:
                    for path in wa_paths:
                        write(path, source_bytes[path])
                if descendant:
                    for path, data in descendant_bytes.items():
                        write(path, data)
                    write(review_path, (json.dumps(review, indent=2) + "\n").encode())
                commit()
                reports(False, False, False, False)
                reject_reports("committed 59e production without required 59f callbacks " + incomplete_name)

            for wa, descendant, composite, callbacks in profiles:
                # Destructive reset/clean calls are confined to this temporary Git fixture.
                git("reset", "--hard", qualified)
                git("clean", "-fd")
                selected_bytes = {}
                if wa:
                    selected_bytes.update((path, source_bytes[path]) for path in wa_paths)
                if descendant:
                    selected_bytes.update(descendant_bytes)
                    write(review_path, (json.dumps(review, indent=2) + "\n").encode())
                if callbacks:
                    selected_bytes.update((path, source_bytes[path]) for path in callback_paths)
                if composite:
                    selected_bytes.update((path, source_bytes[path]) for path in composite_paths)
                if descendant and callbacks:
                    selected_bytes.update(integration_bytes)
                    write(integration_path, integration_manifest)
                if descendant and composite:
                    selected_bytes[composite_policy] = composite_integrated_bytes
                    write(composite_integration_path, composite_integration_manifest)
                    write(publisher_path, fixture_publisher())
                for path, data in selected_bytes.items():
                    write(path, data)
                reports(wa, descendant, composite, callbacks)
                if callbacks:
                    # Reviewed production bytes cannot authorize either an
                    # incomplete or a complete-but-uncommitted callback feature.
                    git("add", *selected_bytes)
                    reject_reports("uncommitted callback source inventory")
                    for path in feature_sources:
                        if path not in callback_paths:
                            write(path, b"// synthetic callback test fixture\n")
                    git("add", *feature_sources)
                    reject_reports("complete but uncommitted callback source inventory")
                if composite:
                    reject_reports("incomplete uncommitted composite source inventory")
                    for path in composite_feature_sources:
                        if path not in composite_paths:
                            write(path, b"// synthetic composite test fixture\n")
                    git("add", *composite_feature_sources)
                    reject_reports("complete but uncommitted composite source inventory")
                if selected_bytes:
                    commit()
                expected_profile = profile_name(wa, descendant, composite, callbacks)
                require(closure.production_profile(root) == expected_profile, "source profile mismatch")
                expected_extensions = (*(("59e",) if composite else ()), *(("59f",) if callbacks else ()))
                require(descendant_extensions(root) == expected_extensions,
                        "committed descendant source selection mismatch")
                with contextlib.redirect_stdout(io.StringIO()):
                    fixture_regressions()
                results = json.loads(output.read_text())
                require(len(results["morphhdl"]["suites"]) == 78 + 6 * descendant + 3 * composite + 4 * callbacks,
                        "reviewed descendant inventory omitted or replaced a suite")
                require(results["morphhdl"]["tests"] == 819 + 36 * descendant + 42 * composite +
                        int(descendant and composite),
                        "reviewed descendant minimum test count changed")
                require(results["morphhdl-passes"]["tests"] == (123 if wa else 99),
                        "reviewed WA minimum test count changed")
                require(len(results["morphhdl-passes"]["suites"]) == (14 if wa else 11),
                        "reviewed WA inventory omitted or replaced a suite")
                for project in ("morphhdl", "morphhdl-passes"):
                    directory = root / project / "target/test-reports"
                    # Every inherited/new suite is independently mandatory.
                    for report in sorted(directory.glob("*.xml")):
                        original = report.read_bytes()
                        report.unlink()
                        reject_reports("missing suite " + project + "/" + report.name)
                        report.write_bytes(original)
                    report = directory / "suite-1.xml"
                    original = report.read_bytes()
                    tree = ET.parse(report)
                    tree.getroot().set("name", "synthetic.SubstituteSuite")
                    tree.write(report)
                    reject_reports("same-count substitute suite " + project)
                    report.write_bytes(original)
                    extra = directory / "extra.xml"
                    tree.write(extra)
                    reject_reports("extra suite " + project)
                    extra.unlink()
                    tree = ET.parse(report)
                    ET.SubElement(tree.getroot().find("testcase"), "skipped")
                    tree.write(report)
                    reject_reports("skipped testcase " + project)
                    report.write_bytes(original)
                    largest = directory / "suite-0.xml"
                    original = largest.read_bytes()
                    tree = ET.parse(largest)
                    suite = tree.getroot()
                    suite.remove(suite.find("testcase"))
                    suite.set("tests", str(int(suite.get("tests")) - 1))
                    tree.write(largest)
                    reject_reports("test count below profile minimum " + project)
                    largest.write_bytes(original)

                for path in (other_source, "backend/src/main/scala/New.scala",
                             "ignored/src/main/scala/Hidden.scala"):
                    target = root / path
                    original = target.read_bytes() if target.exists() else None
                    write(path, b"unreviewed source\n")
                    reject_reports("unauthorized tracked/untracked/ignored production " + path)
                    if original is None:
                        target.unlink()
                    else:
                        target.write_bytes(original)
                git("mv", "--", other_source, "moved-outside-production.scala")
                reject_reports("tracked production source renamed outside src/main")
                git("mv", "--", "moved-outside-production.scala", other_source)
                for other in profiles:
                    if other != (wa, descendant, composite, callbacks):
                        reports(*other)
                        reject_reports("report inventory cannot select source profile " + profile_name(*other))
                reports(wa, descendant, composite, callbacks)
                if descendant or composite:
                    directory = root / "morphhdl/target/test-reports"
                    exact_counts = catalog_for_profile(expected_profile)[2]["morphhdl"]
                    for report in directory.glob("*.xml"):
                        if ET.parse(report).getroot().get("name") not in exact_counts:
                            continue
                        original = report.read_bytes()
                        tree = ET.parse(report)
                        suite = tree.getroot()
                        suite.set("tests", str(int(suite.get("tests")) + 1))
                        ET.SubElement(suite, "testcase", name="unreviewed-extra-case")
                        tree.write(report)
                        reject_reports("exact reviewed suite test count increased " + suite.get("name"))
                        report.write_bytes(original)
                        if composite and (suite.get("name") in INCREMENT_59E_INHERITED_TESTS["morphhdl"] or
                                          (descendant and suite.get("name") in INCREMENT_59D59E_JOINT_TESTS["morphhdl"])):
                            tree = ET.parse(report)
                            suite = tree.getroot()
                            suite.remove(suite.find("testcase"))
                            suite.set("tests", str(int(suite.get("tests")) - 1))
                            tree.write(report)
                            # Add a compensating case elsewhere to demonstrate
                            # that an inherited E or joint integration test
                            # cannot be traded away.
                            compensating = directory / "suite-0.xml"
                            compensating_original = compensating.read_bytes()
                            other_tree = ET.parse(compensating)
                            other_suite = other_tree.getroot()
                            other_suite.set("tests", str(int(other_suite.get("tests")) + 1))
                            ET.SubElement(other_suite, "testcase", name="compensating-unreviewed-case")
                            other_tree.write(compensating)
                            reject_reports("missing inherited or joint 59e case with unchanged total " + suite.get("name"))
                            compensating.write_bytes(compensating_original)
                            report.write_bytes(original)
                if not descendant:
                    write(review_path, (json.dumps(review) + "\n").encode())
                    require(closure.production_profile(root) == expected_profile,
                            "59d contract presence selected an unaudited source profile")
                    (root / review_path).unlink()
                if descendant and callbacks:
                    contract = root / integration_path
                    contract.unlink()
                    reject_reports("missing mandatory 59d/59f joint integration review")
                    contract.write_bytes(integration_manifest + b" ")
                    reject_reports("changed pinned 59d/59f integration manifest bytes")
                    forged = json.loads(integration_manifest)
                    extra_path = next(path for path in callback_paths if path not in integration_bytes)
                    forged["files"].append({
                        "path": extra_path,
                        "before_sha256": callback_hashes[extra_path],
                        "after_sha256": callback_hashes[extra_path],
                        "edits": [{"id": "unreviewed-extra-path",
                                   "before": source_bytes[extra_path].decode(),
                                   "after": source_bytes[extra_path].decode()}]})
                    forged["files"].sort(key=lambda entry: entry["path"])
                    forged_bytes = (json.dumps(forged, indent=2) + "\n").encode()
                    contract.write_bytes(forged_bytes)
                    # Re-pin only the synthetic identity so the exact two-path
                    # inventory must independently reject this scope expansion.
                    with mock.patch.object(closure, "INTEGRATION_59D59F_SHA256",
                                           hashlib.sha256(forged_bytes).hexdigest()):
                        reject_reports("joint integration review cannot absorb another callback path")
                    forged = json.loads(integration_manifest)
                    forged["files"][0]["edits"][0]["before"] += "unreviewed restored source\n"
                    forged_bytes = (json.dumps(forged, indent=2) + "\n").encode()
                    contract.write_bytes(forged_bytes)
                    with mock.patch.object(closure, "INTEGRATION_59D59F_SHA256",
                                           hashlib.sha256(forged_bytes).hexdigest()):
                        reject_reports("joint source edit must restore the complete frozen callback blob")
                    contract.write_bytes(integration_manifest)
                if descendant and composite:
                    contract = root / composite_integration_path
                    contract.unlink()
                    reject_reports("missing mandatory width/composite policy integration review")
                    contract.write_bytes(composite_integration_manifest + b" ")
                    reject_reports("changed pinned width/composite policy integration manifest")
                    forged = json.loads(composite_integration_manifest)
                    forged["files"].append(dict(forged["files"][0], path=composite_paths[-1]))
                    forged_bytes = (json.dumps(forged, indent=2) + "\n").encode()
                    contract.write_bytes(forged_bytes)
                    write(publisher_path, fixture_publisher(hashlib.sha256(forged_bytes).hexdigest()))
                    reject_reports("policy integration review cannot absorb another composite path")
                    forged = json.loads(composite_integration_manifest)
                    forged["files"][0]["edits"][0]["before"] += "unreviewed restored source\n"
                    forged_bytes = (json.dumps(forged, indent=2) + "\n").encode()
                    contract.write_bytes(forged_bytes)
                    write(publisher_path, fixture_publisher(hashlib.sha256(forged_bytes).hexdigest()))
                    reject_reports("policy integration must restore the complete frozen composite blob")
                    contract.write_bytes(composite_integration_manifest)
                    write(publisher_path, fixture_publisher())
                for path, data in selected_bytes.items():
                    target = root / path
                    target.write_bytes(data + b"unreviewed mutation\n")
                    reject_reports("reviewed source hash mutation " + path)
                    target.unlink()
                    reject_reports("missing reviewed source " + path)
                    target.write_bytes(data)
                    # Restoring only the worktree must not hide different bytes
                    # still staged for the next committed production tree.
                    target.write_bytes(data + b"unreviewed staged mutation\n")
                    git("add", "--", path)
                    target.write_bytes(data)
                    reject_reports("staged production mutation hidden by worktree restoration " + path)
                    git("add", "--", path)
                    git("rm", "--cached", "--", path)
                    reject_reports("exact reviewed bytes at untracked allowed path " + path)
                    git("add", "--", path)
                    target.chmod(0o755)
                    reject_reports("executable reviewed source " + path)
                    target.chmod(0o644)
                    git("update-index", "--chmod=+x", "--", path)
                    reject_reports("executable index mode with regular worktree source " + path)
                    git("update-index", "--chmod=-x", "--", path)
                    link_target = root / "symlink-source.txt"
                    link_target.write_bytes(data)
                    target.unlink()
                    target.symlink_to(link_target)
                    reject_reports("symlink at reviewed source path " + path)
                    target.unlink()
                    target.write_bytes(data)
                    link_target.unlink()
                if descendant:
                    path = descendant_paths[0]
                    original_source = (root / path).read_bytes()
                    original_review = (root / review_path).read_bytes()
                    uncommitted_source = original_source + b"uncommitted reviewed width change\n"
                    forged = json.loads(original_review)
                    next(entry for entry in forged["files"] if entry["path"] == path)["sha256"] = \
                        hashlib.sha256(uncommitted_source).hexdigest()
                    write(path, uncommitted_source)
                    write(review_path, (json.dumps(forged, indent=2) + "\n").encode())
                    reject_reports("dirty width source paired with matching uncommitted review hash")
                    write(path, original_source)
                    write(review_path, original_review)
                if callbacks:
                    for path in feature_sources:
                        target = root / path
                        original = target.read_bytes()
                        target.unlink()
                        reject_reports("deleted tracked callback source " + path)
                        target.write_bytes(original)
                else:
                    path = next(path for path in feature_sources if "/src/test/" in path)
                    write(path, b"// uncommitted callback source fixture\n")
                    reject_reports("callback suite source cannot select a production profile")
                    (root / path).unlink()
                if composite:
                    for path in composite_feature_sources:
                        target = root / path
                        original = target.read_bytes()
                        target.unlink()
                        reject_reports("deleted tracked composite source " + path)
                        target.write_bytes(original)
                else:
                    path = next(path for path in composite_feature_sources if "/src/test/" in path)
                    write(path, b"// uncommitted composite source fixture\n")
                    reject_reports("composite suite source cannot select a production profile")
                    (root / path).unlink()
                if selected_bytes:
                    with mock.patch.object(closure, "QUALIFIED_60F", git("rev-parse", "HEAD")):
                        reject_reports("historical 60f production change")
                else:
                    git("mv", "--", other_source, "moved-outside-production.scala")
                    renamed_qualification = commit()
                    with mock.patch.object(closure, "QUALIFIED_60F", renamed_qualification):
                        reject_reports("historical production source renamed outside src/main")
                    git("reset", "--hard", qualified)
                if callbacks:
                    callback_completion = git("rev-parse", "HEAD")
                    # Completion remains an ancestor when a descendant commit
                    # restores every feature source and suite to baseline.
                    git("read-tree", "--reset", "-u", qualified)
                    commit()
                    reports(False, False, False, False)
                    with mock.patch.object(closure, "COMPLETED_59F", callback_completion):
                        require(closure.production_profile(root) == "60f-baseline",
                                "complete callback reversion did not restore exact baseline bytes")
                        reject_reports("complete committed callback reversion cannot drop suite obligations")
                    git("reset", "--hard", callback_completion)
                    reports(wa, descendant, composite, callbacks)
                if composite:
                    composite_completion = git("rev-parse", "HEAD")
                    # Retain the complete F (and any D/WA) implementation while
                    # reverting all E production and suite sources in a child.
                    git("read-tree", "--reset", "-u", qualified)
                    for path, data in selected_bytes.items():
                        if path not in composite_paths:
                            write(path, data)
                    for path in feature_sources:
                        if "/src/test/" in path:
                            write(path, b"// synthetic callback test fixture\n")
                    if descendant:
                        write(review_path, (json.dumps(review, indent=2) + "\n").encode())
                        write(integration_path, integration_manifest)
                    commit()
                    reports(wa, descendant, False, callbacks)
                    with mock.patch.object(closure, "COMPLETED_59E", composite_completion):
                        require(closure.production_profile(root) == profile_name(wa, descendant, False, callbacks),
                                "complete composite reversion did not retain exact inherited feature bytes")
                        reject_reports("complete committed composite reversion cannot drop suite obligations")
                    git("reset", "--hard", composite_completion)
                    reports(wa, descendant, composite, callbacks)
                require(closure.production_profile(root) == expected_profile, "fixture restoration failed")

            original_review = (root / review_path).read_bytes()
            forged = json.loads(original_review)
            forged["files"].extend({"path": path, "sha256": digest} for path, digest in wa_hashes.items())
            forged["files"].sort(key=lambda entry: entry["path"])
            write(review_path, (json.dumps(forged) + "\n").encode())
            reject_reports("59d production review cannot absorb WA-07a paths")
            write(review_path, original_review)
            for incomplete in (wa_paths[:1], wa_paths[:2]):
                for path in incomplete:
                    git("checkout", qualified, "--", path)
                reject_reports("partial WA-07a source set in the 59d/59f union")
                for path in incomplete:
                    write(path, source_bytes[path])
                    git("add", "--", path)
            require(closure.production_profile(root) == "60f-with-wa07a-and-59d-and-59e-and-59f",
                    "fixture restoration failed")
    print(f"60f inventory self-test: twelve exact source profiles and {rejections} rejection controls PASS")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("self-test", help="synthetic inventory controls; no HDL qualification")
    fresh = commands.add_parser("snapshot")
    fresh.add_argument("left", type=Path)
    fresh.add_argument("right", type=Path)
    fresh.add_argument("output", type=Path)
    fresh.add_argument("--scala", choices=("2.12.18", "2.13.12"), required=True)
    cross = commands.add_parser("compare")
    cross.add_argument("left", type=Path)
    cross.add_argument("right", type=Path)
    tests = commands.add_parser("regressions")
    tests.add_argument("root", type=Path)
    tests.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.command == "self-test":
        self_test()
    elif args.command == "snapshot":
        snapshot(args.left, args.right, args.output, args.scala)
    elif args.command == "compare":
        compare(args.left, args.right)
    else:
        regressions(args.root, args.output)


if __name__ == "__main__":
    main()
