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

# Separately reviewed descendants extend the frozen inherited inventory by exact
# suite identity. Presence of arbitrary XML or a matching count grants nothing.
# A complete, tracked feature source inventory activates the reviewed additions;
# heads without that feature retain exactly the original 78 MorphHDL suites.
SUITE_EXTENSIONS = {
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
    # The source check runs before report discovery, so reports, suite counts,
    # path presence or branch names cannot opt into a looser contract.
    profile = closure_module().production_profile(root)
    counts, suite_inventory = dict(REGRESSIONS), dict(EXPECTED_SUITES)
    rollout = profile.endswith("-and-60g")
    if rollout:
        # Select by the complete validated production profile, never XML or a
        # partial file inventory. The new checks extend an existing suite.
        profile = profile[:-len("-and-60g")]
        gate = closure_module().load(root, "60g-source-scope")
        gate.source_scope(root)
        counts["morphhdl"] = (REGRESSIONS["morphhdl"][0] + 8, REGRESSIONS["morphhdl"][1])
    require(profile in ("60f-baseline", "60f-with-wa07a", "60f-with-59f",
                        "60f-with-wa07a-and-59f"), "unknown validated source profile: " + profile)
    extensions = descendant_extensions(root)
    has_callbacks = profile in ("60f-with-59f", "60f-with-wa07a-and-59f")
    require(extensions == (("59f",) if has_callbacks else ()),
            "committed suite sources differ from the validated production profile")
    if profile in ("60f-with-wa07a", "60f-with-wa07a-and-59f"):
        counts["morphhdl-passes"] = (123, 14)
        suite_inventory["morphhdl-passes"] |= WA07A_SUITES
    for name in extensions:
        for project, additions in SUITE_EXTENSIONS[name]["projects"].items():
            require(not (suite_inventory[project] & additions),
                    "reviewed suite addition duplicates inherited identity: " + name)
            suite_inventory[project] |= additions
            minimum, suites = counts[project]
            counts[project] = (minimum, suites + len(additions))
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
                        for path in (*wa_paths, *callback_paths)}
        wa_hashes = {path: hashlib.sha256(source_bytes[path]).hexdigest() for path in wa_paths}
        callback_hashes = {path: hashlib.sha256(source_bytes[path]).hexdigest() for path in callback_paths}

        def reports(wa: bool, callbacks: bool) -> None:
            for project, (minimum, suites) in REGRESSIONS.items():
                names = set(EXPECTED_SUITES[project])
                if wa and project == "morphhdl-passes":
                    names |= WA07A_SUITES
                    minimum, suites = 123, 14
                if callbacks:
                    additions = SUITE_EXTENSIONS["59f"]["projects"].get(project, frozenset())
                    names |= additions
                    suites += len(additions)
                require(len(names) == suites, "frozen suite count differs: " + project)
                directory = root / project / "target/test-reports"
                directory.mkdir(parents=True, exist_ok=True)
                for old in directory.glob("*.xml"):
                    old.unlink()
                for index, suite_name in enumerate(sorted(names)):
                    count = minimum - suites + 1 if index == 0 else 1
                    suite = ET.Element("testsuite", name=suite_name, tests=str(count),
                                       failures="0", errors="0", skipped="0")
                    for case in range(count):
                        ET.SubElement(suite, "testcase", name=f"synthetic-case-{case}")
                    ET.ElementTree(suite).write(directory / f"suite-{index}.xml")

        output = root / "result.json"

        def reject_reports(label: str) -> None:
            output.write_text('{"stale_success": true}\n')
            with contextlib.redirect_stdout(io.StringIO()):
                rejected(lambda: regressions(root, output), label)
            require(not output.exists(), "failed validation retained stale success: " + label)

        # Only fixture identities are patched. Every profile selection below
        # executes real Git ancestry/delta, byte, committed source and XML checks.
        with mock.patch.multiple(closure, BASE=base, QUALIFIED_60F=qualified,
                                 WA07A_PRODUCTION_SHA256=wa_hashes,
                                 CALLBACK_59F_PRODUCTION_SHA256=callback_hashes):
            for wa, callbacks, expected_profile in (
                    (False, False, "60f-baseline"),
                    (True, False, "60f-with-wa07a"),
                    (False, True, "60f-with-59f"),
                    (True, True, "60f-with-wa07a-and-59f")):
                # These destructive resets are confined to the temporary fixture.
                git("reset", "--hard", qualified)
                git("clean", "-fd")
                selected = (*(wa_paths if wa else ()), *(callback_paths if callbacks else ()))
                for path in selected:
                    write(path, source_bytes[path])
                reports(wa, callbacks)
                if callbacks:
                    # Exact production bytes do not authorize an incomplete or
                    # merely staged callback feature and its four new suites.
                    git("add", *selected)
                    reject_reports("uncommitted callback source inventory")
                    for path in feature_sources:
                        if path not in callback_paths:
                            write(path, b"// synthetic callback test fixture\n")
                    git("add", *feature_sources)
                    reject_reports("complete but uncommitted callback source inventory")
                if selected:
                    commit()
                require(closure.production_profile(root) == expected_profile, "source profile mismatch")
                require(descendant_extensions(root) == (("59f",) if callbacks else ()),
                        "committed callback source selection mismatch")
                with contextlib.redirect_stdout(io.StringIO()):
                    regressions(root, output)
                results = json.loads(output.read_text())
                require(len(results["morphhdl"]["suites"]) == (82 if callbacks else 78),
                        "reviewed callback inventory omitted or replaced a suite")
                require(len(results["morphhdl-passes"]["suites"]) == (14 if wa else 11),
                        "reviewed WA inventory omitted or replaced a suite")

                for project in ("morphhdl", "morphhdl-passes"):
                    directory = root / project / "target/test-reports"
                    # Every inherited and new identity is individually required.
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
                for wrong_wa, wrong_callbacks in ((not wa, callbacks), (wa, not callbacks)):
                    reports(wrong_wa, wrong_callbacks)
                    reject_reports("report inventory cannot select its own source profile")
                reports(wa, callbacks)
                for path in selected:
                    target = root / path
                    target.write_bytes(source_bytes[path] + b"unreviewed mutation\n")
                    reject_reports("reviewed source hash mutation " + path)
                    target.unlink()
                    reject_reports("missing reviewed source " + path)
                    target.write_bytes(source_bytes[path])
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
                if selected:
                    path = selected[-1]
                    target = root / path
                    target.chmod(0o755)
                    reject_reports("executable reviewed source")
                    target.chmod(0o644)
                    git("rm", "--cached", "--", path)
                    reject_reports("exact reviewed bytes at untracked allowed path")
                    git("add", "--", path)
                    with mock.patch.object(closure, "QUALIFIED_60F", git("rev-parse", "HEAD")):
                        reject_reports("historical 60f production change")
                else:
                    git("mv", "--", other_source, "moved-outside-production.scala")
                    renamed_qualification = commit()
                    with mock.patch.object(closure, "QUALIFIED_60F", renamed_qualification):
                        reject_reports("historical production source renamed outside src/main")
                    git("reset", "--hard", qualified)
                require(closure.production_profile(root) == expected_profile, "fixture restoration failed")
    print(f"60f inventory self-test: four exact source profiles and {rejections} rejection controls PASS")


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
