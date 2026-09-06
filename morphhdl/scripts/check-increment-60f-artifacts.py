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
    require(profile in ("60f-baseline", "60f-with-wa07a", "60f-with-59d", "60f-with-wa07a-and-59d"),
            "unknown validated source profile: " + profile)
    counts, suites = dict(REGRESSIONS), dict(EXPECTED_SUITES)
    extension = {}
    if profile in ("60f-with-wa07a", "60f-with-wa07a-and-59d"):
        counts["morphhdl-passes"] = (123, 14)
        suites["morphhdl-passes"] |= WA07A_SUITES
    if profile in ("60f-with-59d", "60f-with-wa07a-and-59d"):
        extension = INCREMENT_59D_SUITES
        for project, additions in extension.items():
            require(not suites[project].intersection(additions), "59d inventory replaced an inherited suite")
            suites[project] |= frozenset(additions)
            old_tests, old_suites = counts[project]
            counts[project] = (old_tests + sum(additions.values()), old_suites + len(additions))
    return counts, suites, extension


def regressions(root: Path, output: Path) -> None:
    output.unlink(missing_ok=True)
    # The source check runs before report discovery. Reports cannot select
    # a successor contract or bypass the inherited 59d authority audits.
    profile = closure_module().production_profile(root)
    if profile in ("60f-with-59d", "60f-with-wa07a-and-59d"):
        closure_module().source_scope(root)
    _regression_inventory(root, output, profile)


def _regression_inventory(root: Path, output: Path, profile: str) -> None:
    """Validate reports only after the caller has validated the source profile."""
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
                require(count == added_count, f"changed exact 59d test inventory: {name}: {count}")
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
    """Synthetic inventory/report controls only; never HDL qualification evidence."""
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
        paths = tuple(closure.WA07A_PRODUCTION_SHA256)
        write(".gitignore", b"**/target/\n/ignored/\n/result.json\n")
        for path in paths[:2]:
            write(path, b"synthetic inherited source\n")
        other_source = "core/src/main/scala/Synthetic.scala"
        write(other_source, b"synthetic sealed production\n")
        base = commit()
        write("qualification.txt", b"synthetic qualification-only commit\n")
        qualified = commit()
        wa_bytes = {path: ("synthetic reviewed follow-on: " + path + "\n").encode() for path in paths}
        hashes = {path: hashlib.sha256(data).hexdigest() for path, data in wa_bytes.items()}

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

        def profile_name(wa: bool, descendant: bool) -> str:
            if descendant:
                return "60f-with-wa07a-and-59d" if wa else "60f-with-59d"
            return "60f-with-wa07a" if wa else "60f-baseline"

        def reports(wa: bool, descendant: bool) -> None:
            counts, inventories, extension = catalog_for_profile(profile_name(wa, descendant))
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
            # These synthetic sources exercise the real profile and XML gates.
            # Full historical oracle/authority audits use the real repository
            # fixtures in test-increment-59d-inherited-60f-scope.py.
            output.unlink(missing_ok=True)
            profile = closure.production_profile(root)
            _regression_inventory(root, output, profile)

        output = root / "result.json"

        def reject_reports(label: str) -> None:
            output.write_text('{"stale_success": true}\n')
            with contextlib.redirect_stdout(io.StringIO()):
                rejected(fixture_regressions, label)
            require(not output.exists(), "failed validation retained stale success: " + label)

        # Only fixture identities are patched. Every selection/rejection below
        # executes the real Git delta and byte validation, never a mocked result.
        with mock.patch.multiple(closure, BASE=base, QUALIFIED_60F=qualified,
                                 INCREMENT_59D_BASE=qualified,
                                 INCREMENT_59D_PRODUCTION_PATHS=frozenset(descendant_paths),
                                 WA07A_PRODUCTION_SHA256=hashes):
            for wa, descendant in ((False, False), (True, False), (False, True), (True, True)):
                git("reset", "--hard", qualified)
                if wa:
                    for path, data in wa_bytes.items():
                        write(path, data)
                if descendant:
                    for path, data in descendant_bytes.items():
                        write(path, data)
                    write(review_path, (json.dumps(review, indent=2) + "\n").encode())
                if wa or descendant:
                    commit()
                expected_profile = profile_name(wa, descendant)
                require(closure.production_profile(root) == expected_profile, "source profile mismatch")
                reports(wa, descendant)
                with contextlib.redirect_stdout(io.StringIO()):
                    fixture_regressions()
                require(output.is_file(), "positive XML control published no inventory")
                # Each named inherited/additional pass suite is independently
                # required; count-preserving substitutions cannot replace one.
                directory = root / "morphhdl-passes/target/test-reports"
                for report in sorted(directory.glob("*.xml")):
                    original = report.read_bytes()
                    report.unlink()
                    reject_reports("missing suite " + report.name)
                    report.write_bytes(original)
                report = directory / "suite-1.xml"
                original = report.read_bytes()
                tree = ET.parse(report)
                tree.getroot().set("name", "synthetic.SubstituteSuite")
                tree.write(report)
                reject_reports("same-count substitute suite")
                report.write_bytes(original)
                extra = directory / "extra.xml"
                tree.write(extra)
                reject_reports("extra suite")
                extra.unlink()
                tree = ET.parse(report)
                ET.SubElement(tree.getroot().find("testcase"), "skipped")
                tree.write(report)
                reject_reports("skipped testcase")
                report.write_bytes(original)
                largest = directory / "suite-0.xml"
                original = largest.read_bytes()
                tree = ET.parse(largest)
                suite = tree.getroot()
                suite.remove(suite.find("testcase"))
                suite.set("tests", str(int(suite.get("tests")) - 1))
                tree.write(largest)
                reject_reports("test count below source profile minimum")
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
                for other in ((False, False), (True, False), (False, True), (True, True)):
                    if other != (wa, descendant):
                        reports(*other)
                        reject_reports("report inventory cannot select source profile " + profile_name(*other))
                reports(wa, descendant)
                if descendant:
                    directory = root / "morphhdl/target/test-reports"
                    for report in sorted(directory.glob("*.xml")):
                        original = report.read_bytes()
                        report.unlink()
                        reject_reports("missing inherited or 59d suite " + report.name)
                        report.write_bytes(original)
                    added = next(report for report in directory.glob("*.xml")
                                 if ET.parse(report).getroot().get("name") in INCREMENT_59D_SUITES["morphhdl"])
                    original = added.read_bytes()
                    tree = ET.parse(added)
                    suite = tree.getroot()
                    count = int(suite.get("tests"))
                    suite.set("tests", str(count + 1))
                    ET.SubElement(suite, "testcase", name="unreviewed-extra-case")
                    tree.write(added)
                    reject_reports("exact 59d suite test count changed")
                    added.write_bytes(original)
                else:
                    write(review_path, (json.dumps(review) + "\n").encode())
                    require(closure.production_profile(root) == expected_profile,
                            "59d contract presence selected an unaudited source profile")
                    (root / review_path).unlink()
                if not wa and not descendant:
                    git("mv", "--", other_source, "moved-outside-production.scala")
                    renamed_qualification = commit()
                    with mock.patch.object(closure, "QUALIFIED_60F", renamed_qualification):
                        reject_reports("historical production source renamed outside src/main")
                    git("reset", "--hard", qualified)
            for path, data in {**wa_bytes, **descendant_bytes}.items():
                target = root / path
                target.write_bytes(data + b"unreviewed mutation\n")
                reject_reports("reviewed source hash mutation " + path)
                target.unlink()
                reject_reports("missing reviewed source " + path)
                target.write_bytes(data)
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
            original_review = (root / review_path).read_bytes()
            forged = json.loads(original_review)
            forged["files"].extend({"path": path, "sha256": digest} for path, digest in hashes.items())
            forged["files"].sort(key=lambda entry: entry["path"])
            write(review_path, (json.dumps(forged) + "\n").encode())
            reject_reports("59d production review cannot absorb WA-07a paths")
            write(review_path, original_review)
            for incomplete in (paths[:1], paths[:2]):
                for path in incomplete:
                    git("checkout", qualified, "--", path)
                reject_reports("partial WA-07a source set in the 59d union")
                for path in incomplete:
                    write(path, wa_bytes[path])
                    git("add", "--", path)
            with mock.patch.object(closure, "QUALIFIED_60F", git("rev-parse", "HEAD")):
                reject_reports("historical 60f production change")
            require(closure.production_profile(root) == "60f-with-wa07a-and-59d", "fixture restoration failed")
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
