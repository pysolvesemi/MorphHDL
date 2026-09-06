#!/usr/bin/env python3
"""Close 60f qualification without editing the sealed independent RTL writers.

OUTPUT contains boundaries/ from SignednessBoundaryArtifactWriter and pure/ from
PureSIntCastArtifactWriter. The full run preserves the inherited 60d/60e gates and
adds the exact 60a mutation's solver witness/replay plus explicit memory-validity
proofs. The latter are bounded, supplementary checks, not replacements for the
inherited sequential equivalence induction gates.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import re
import shutil
import subprocess
import tempfile
from pathlib import Path

BASE = "feca6b9d599d97af92ed9f6a8bc871ef008c395e"
COMPLETED_60F = "5a669d32095ee722c313bd069b771e7c350a1f81"
COMPLETED_59F = "c85659a20d428dd58cc6116c12c8b24418c37722"
INHERITED_TRACKS = {"60c": "60c-signed-declarations", "60d": "60d-pure-sint-casts",
                    "60e": "60e-signedness-boundaries"}
QUALIFIED_60F = "8ae431f54efcd7b88fb49243e5fe82a9dbcc4ccd"
# Complete reviewed follow-on production delta, frozen from WA-07a f6646f5.
# This does not expand 60f's historical qualification-only production scope.
WA07A_PRODUCTION_SHA256 = {
    "morphhdl-passes/src/main/scala/morphhdl/passes/api/PassContracts.scala":
        "1946882af38c058829564faa5d0f7967209e8efd1ab8cfe3d26060ec206a2cda",
    "morphhdl-passes/src/main/scala/morphhdl/passes/pipeline/WireAliasPassPipeline.scala":
        "e8ae9bdd4ae8bfb9ffd168a62a7a77578ae54b14cee3291b199d90899d1a4f1e",
    "morphhdl-passes/src/main/scala/morphhdl/passes/transform/ConstantOperandSimplificationPass.scala":
        "40a754b3b8029b9cbe047a92e35ef850f644f2b6a941f15cb69786c2b4b30b71",
}
# Exact, separately reviewed 59f production delta. Source selection never uses
# report counts, branch names, partial feature presence, or unchecked paths.
CALLBACK_59F_PRODUCTION_SHA256 = {
    "core/src/main/scala/spinal/core/BitVector.scala": "fc59c3f42ff9be5ea6ddfb4a5d7e32c59f57302ad8b806201285dc648425f9f6",
    "core/src/main/scala/spinal/core/ParameterizedWidth.scala": "f85ea514e70e7be6e46f566bb898bb40f8588d8b42ec68d9c6116a0122ffdce8",
    "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala": "dd2bdf8629d08a0b25fb785e4828c9ae56ef0f5e7c4fc8efd4b086c61140d6a3",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala": "8745879bd3e435ed51c9809e6eb05f9c3d5ede6513fe47b84a1e4dc8bd895064",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCapture.scala": "b08a77c98c5b9fad4f5a44430b5c9a2edc6fa9055c763503b808c0c2688ccc70",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCaptureSchema.scala": "15fecc14650d5a0fb2f03450977cd91da432816bfa47ab1fc4003c573f557f1c",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCertifiedCallbackPolicy.scala": "db37d6540af715b6f535a78891439020fb7196d450dc403af3cde6a9fedc53fb",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionOperatorCertificate.scala": "5abc84b0aeaf26b21d6a21fc0a844f2e8b17833ec4da33cec84f8ce75b7f17d3",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionOperatorReplay.scala": "b401b6cece917c84e945efedaca22148ccc4a88bbe8df9ff7f4073fdf4ab35bf",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionScalarGraphReplay.scala": "a1de8e5058d02f66c801f70409f7e208daed3a2a1d1584491e3be837615352ed",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionStageReplay.scala": "43e5ee1294041a4bf7f46fc92ec56569d0bf20e8180c770749ae976060afcd10",
    "morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionValueEvidence.scala": "da743fb98428d5ffb6d2875096293daad1da99dfb4b796e7cc4c540d76d3f899"
}
WIDTHS = (1, 5, 8, 32)
MEMORY_STEPS = 8
SAT_PASS = "SAT proof finished - no model found: SUCCESS!"
SAT_FAIL = "SAT proof finished - model found: FAIL!"
BAD_RESULT = re.compile(r"\b(?:UNKNOWN|TIMEOUT|timed\s+out)\b|^\s*(?:ERROR|FATAL):", re.I | re.M)


def require(ok: bool, detail: str) -> None:
    if not ok:
        raise RuntimeError(detail)


def load(root: Path, suffix: str):
    path = root / ("morphhdl/scripts/check-increment-" + suffix + ".py")
    spec = importlib.util.spec_from_file_location(suffix.replace("-", "_"), path)
    require(spec is not None and spec.loader is not None, "cannot import " + str(path))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def classify_solver(returncode: int, output: str, expected: str) -> None:
    """A marker never overrides process failure, unknown results or tool errors."""
    require(expected in (SAT_PASS, SAT_FAIL), "unknown expected solver result")
    require(returncode == 0, "solver returned nonzero exit status")
    require(BAD_RESULT.search(output) is None, "solver reported an error, timeout or UNKNOWN")
    require(output.count(expected) == 1, "missing or duplicated expected solver result")
    opposite = SAT_FAIL if expected == SAT_PASS else SAT_PASS
    require(opposite not in output, "contradictory solver results")


def run(command: list[str], directory: Path, label: str, expected: str | None = None,
        timeout: int = 240) -> str:
    try:
        result = subprocess.run(command, cwd=directory, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=timeout)
    except subprocess.TimeoutExpired as error:
        output = error.stdout or ""
        if isinstance(output, bytes):
            output = output.decode("utf-8", errors="replace")
        (directory / (label + ".log")).write_text(output + "\nTIMEOUT\n")
        (directory / (label + ".result.json")).write_text(json.dumps({
            "command": command, "status": "timeout", "timeout_seconds": timeout}, indent=2) + "\n")
        raise RuntimeError(label + " timed out; this is not a proof") from error
    (directory / (label + ".log")).write_text(result.stdout)
    (directory / (label + ".result.json")).write_text(json.dumps({
        "command": command, "returncode": result.returncode,
        "expected": expected}, indent=2) + "\n")
    require(result.returncode == 0, label + " failed:\n" + result.stdout[-16000:])
    require(BAD_RESULT.search(result.stdout) is None, label + " reported an error, timeout or UNKNOWN")
    if expected is not None:
        classify_solver(result.returncode, result.stdout, expected)
    return result.stdout


def self_test() -> None:
    # These are result-classification attack cases, not stand-ins for real tools.
    accepted = ((0, SAT_PASS, SAT_PASS), (0, SAT_FAIL, SAT_FAIL))
    rejected = (
        (1, SAT_FAIL, SAT_FAIL), (1, SAT_PASS, SAT_PASS),
        (0, "ERROR: Module Missing is not part of the design.\n" + SAT_FAIL, SAT_FAIL),
        (0, "ERROR: syntax error, unexpected TOK_ID\n" + SAT_PASS, SAT_PASS),
        (0, "UNKNOWN\n" + SAT_FAIL, SAT_FAIL),
        (0, "TIMEOUT\n" + SAT_PASS, SAT_PASS),
        (0, "solver timed out\n" + SAT_FAIL, SAT_FAIL),
        (0, "tool ran successfully without a proof marker", SAT_PASS),
        (0, SAT_PASS, SAT_FAIL), (0, SAT_FAIL, SAT_PASS),
        (0, SAT_PASS + "\n" + SAT_FAIL, SAT_PASS),
        (0, SAT_FAIL + "\n" + SAT_FAIL, SAT_FAIL),
    )
    for args in accepted:
        classify_solver(*args)
    for args in rejected:
        try:
            classify_solver(*args)
        except RuntimeError:
            continue
        raise RuntimeError("failed to reject invalid result: " + repr(args))
    print(f"60f result classification: {len(accepted)} positive and {len(rejected)} rejection controls PASS", flush=True)
    source_scope_self_test()


def qualification_interval(root: Path, baseline: str, completed: str) -> None:
    """Seal this completed increment's scope without freezing later work."""
    for older, newer in ((baseline, completed), (completed, "HEAD")):
        subprocess.run(["git", "merge-base", "--is-ancestor", older, newer], cwd=root, check=True)
    changed = subprocess.check_output(
        ["git", "diff", "--no-renames", "--name-only", baseline, completed], cwd=root, text=True).splitlines()
    production = sorted(path for path in changed if re.search(r"(?:^|/)src/main/", path))
    require(not production, "completed 60f interval changed production sources:\n" + "\n".join(production))


def source_scope_self_test() -> None:
    """Exercise the historical boundary and the real current native auditor."""
    audit_path = Path(__file__).with_name("check-native-source-preservation.py")
    spec = importlib.util.spec_from_file_location("closure_native_audit", audit_path)
    require(spec is not None and spec.loader is not None, "cannot load native audit controls")
    audit = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(audit)
    with tempfile.TemporaryDirectory(prefix="morphhdl-60f-scope-") as directory:
        root = Path(directory) / "repository"
        root.mkdir()

        def git(*args: str) -> str:
            return subprocess.check_output(["git", *args], cwd=root, text=True).strip()

        def commit(message: str) -> str:
            git("add", ".")
            git("-c", "core.hooksPath=/dev/null", "commit", "-qm", message)
            return git("rev-parse", "HEAD")

        git("init", "-q")
        git("config", "user.name", "MorphHDL scope control")
        git("config", "user.email", "scope@example.invalid")
        for index, source_root in enumerate(audit.EXPECTED_SOURCE_ROOTS):
            marker = root / source_root / "scala" / f"Marker{index}.scala"
            marker.parent.mkdir(parents=True)
            marker.write_text(f"object Marker{index}\n")
        upstream = commit("upstream")
        config = root / audit.DEFAULT_UPSTREAM_CONFIG
        config.parent.mkdir(parents=True, exist_ok=True)
        config.write_text("UPSTREAM_COMMIT=" + upstream + "\n")
        initial = "core/src/main/scala/InitialSupport.scala"
        (root / initial).write_text("object InitialSupport\n")
        baseline = commit("reviewed implementation before qualification")

        def reviewed_support(path: str) -> dict:
            return dict(path=path, baseline_path=None, change="added", classification="typed-support-file",
                        introduced_by=["source-scope control"], reason="explicitly reviewed native support", edits=[])

        policy = dict(schema_version=1, repository=audit.EXPECTED_REPOSITORY,
                      baseline_commit=upstream, files=[reviewed_support(initial)])
        policy_path = Path(directory) / "review.json"
        manifest_path = Path(directory) / "manifest.json"

        def approve_current() -> None:
            policy_path.write_text(json.dumps(policy))
            manifest_path.write_text(json.dumps(audit.generate_manifest_value(root, policy_path)))
            audit.validate_repository(root, manifest_path)

        approve_current()
        baseline_manifest = manifest_path.read_bytes()
        (root / "qualification.txt").write_text("tests and evidence only\n")
        completed = commit("completed qualification-only increment")
        qualification_interval(root, baseline, completed)
        audit.validate_repository(root, manifest_path)

        later = "core/src/main/scala/LaterSupport.scala"
        (root / later).write_text("object LaterSupport\n")
        current = commit("later implementation increment")
        qualification_interval(root, baseline, completed)
        try:
            audit.validate_repository(root, manifest_path)
        except audit.AuditError as error:
            require(error.code.endswith("UNAPPROVED-PATH"), "wrong unreviewed-source rejection: " + str(error))
        else:
            raise RuntimeError("later native production changes escaped the current approved-source audit")
        policy["files"].append(reviewed_support(later))
        approve_current()
        try:
            qualification_interval(root, baseline, current)
        except RuntimeError as error:
            require("changed production sources" in str(error), "wrong historical source rejection")
        else:
            raise RuntimeError("production changes were accepted inside a qualification-only interval")

        (root / later).write_text("object LaterSupport { val unreviewed = true }\n")
        try:
            audit.validate_repository(root, manifest_path)
        except audit.AuditError as error:
            require(error.code.endswith("DIRTY-WORKTREE"), "wrong dirty-source rejection: " + str(error))
        else:
            raise RuntimeError("uncommitted native production changes escaped the current source audit")
        (root / later).write_text("object LaterSupport\n")

        # Exercise the new selector with real Git deltas and the real native
        # auditor. This small fixture replaces only the full repository's sealed
        # oracle files/history with its synthetic completed interval.
        from unittest import mock
        wa_bytes = {path: ("synthetic WA source " + path + "\n").encode()
                    for path in WA07A_PRODUCTION_SHA256}
        wa_hashes = {path: hashlib.sha256(data).hexdigest() for path, data in wa_bytes.items()}
        scope_calls = 0

        def fixture_evolution_scope(repository: Path) -> None:
            nonlocal scope_calls
            scope_calls += 1
            qualification_interval(repository, baseline, completed)
            audit.validate_repository(repository, manifest_path)

        def rejected_profile(label: str) -> None:
            try:
                regression_profile(root)
            except (RuntimeError, audit.AuditError):
                return
            raise RuntimeError("regression selector accepted " + label)

        with mock.patch.dict(globals(), BASE=baseline, QUALIFIED_60F=completed,
                             COMPLETED_60F=completed, WA07A_PRODUCTION_SHA256=wa_hashes,
                             source_evolution_scope=fixture_evolution_scope):
            approved_manifest = manifest_path.read_bytes()
            manifest_path.write_bytes(baseline_manifest)
            rejected_profile("unapproved committed native evolution")
            manifest_path.write_bytes(approved_manifest)
            require(regression_profile(root) == "60f-baseline", "later native source lost baseline pass profile")
            for path, data in wa_bytes.items():
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
            commit("exact reviewed pass profile after later native evolution")
            require(regression_profile(root) == "60f-with-wa07a", "later native source lost exact WA profile")
            require(scope_calls >= 3, "later production skipped the current native scope gate")

            first = next(iter(wa_bytes))
            (root / first).write_bytes(wa_bytes[first] + b"staged mutation\n")
            git("add", "--", first)
            (root / first).write_bytes(wa_bytes[first])
            rejected_profile("staged mutation hidden by restored working-tree bytes")
            git("reset", "-q", "HEAD", "--", first)

            (root / ".gitignore").write_text("ignored/\n")
            ignored = root / "ignored/src/main/scala/Hidden.scala"
            ignored.parent.mkdir(parents=True)
            ignored.write_text("object Hidden\n")
            rejected_profile("ignored untracked production during later evolution")
            ignored.unlink()

            extra = "morphhdl-passes/nested/backend/src/main/scala/Extra.scala"
            target = root / extra
            target.parent.mkdir(parents=True)
            target.write_text("object Extra\n")
            commit("unreviewed nested pass project")
            rejected_profile("committed production in a nested pass project")
            git("rm", "-q", "--", extra)
            commit("remove nested pass project")

            (root / first).write_bytes(wa_bytes[first] + b"committed mutation\n")
            commit("changed pass source hash")
            rejected_profile("committed WA source hash mutation")
            (root / first).write_bytes(wa_bytes[first])
            commit("restore exact pass source")
            git("rm", "-q", "--", first)
            commit("missing required pass source")
            rejected_profile("committed missing WA source")
            (root / first).parent.mkdir(parents=True, exist_ok=True)
            (root / first).write_bytes(wa_bytes[first])
            commit("restore missing pass source")
            require(regression_profile(root) == "60f-with-wa07a", "selector fixture restoration failed")
        print("60f evolved-source/pass-profile selection and six rejection controls PASS", flush=True)
    print("60f historical scope and current approved native-source controls PASS", flush=True)


def checked_pass_profile(root: Path, changed: set[str]) -> str:
    if not changed:
        return "60f-baseline"
    require(changed == set(WA07A_PRODUCTION_SHA256),
            "unreviewed production delta: " + str(sorted(changed)))
    for path, digest in WA07A_PRODUCTION_SHA256.items():
        source = root / path
        require(source.is_file() and not source.is_symlink() and not source.stat().st_mode & 0o111,
                "reviewed production source must be a regular non-executable file: " + path)
        require(hashlib.sha256(source.read_bytes()).hexdigest() == digest,
                "reviewed WA-07a production source hash differs: " + path)
        stage = subprocess.check_output(["git", "ls-files", "--stage", "--", path], cwd=root).decode("utf-8").split()
        require(len(stage) == 4 and stage[0] == "100644" and stage[2] == "0" and stage[3] == path,
                "reviewed WA-07a production source is not uniquely tracked: " + path)
    return "60f-with-wa07a"


def production_profile(root: Path) -> str:
    """Select an exact source contract before consulting regression reports."""
    def git(*args: str) -> bytes:
        return subprocess.check_output(["git", *args], cwd=root)

    def production_paths(data: bytes) -> set[str]:
        return {path.decode("utf-8") for path in data.split(b"\0")
                if re.search(rb"(?:^|/)src/main/", path)}

    require(subprocess.run(["git", "merge-base", "--is-ancestor", BASE, QUALIFIED_60F],
                           cwd=root).returncode == 0,
            "60f baseline must be an ancestor of the qualified commit")
    require(subprocess.run(["git", "merge-base", "--is-ancestor", QUALIFIED_60F, "HEAD"],
                           cwd=root).returncode == 0,
            "qualified 60f must be an ancestor of HEAD")
    historical = production_paths(git("diff", "--no-renames", "--name-only", "-z", BASE, QUALIFIED_60F))
    require(not historical, "qualified 60f must remain production-zero: " + str(sorted(historical)))
    # Include every production project (also nested backends and new roots),
    # including ignored untracked sources; an allowed pathname alone is not a
    # source contract. New WA files must be tracked and all three bytes exact.
    untracked = production_paths(git("ls-files", "--others", "-z"))
    require(not untracked, "untracked production sources: " + str(sorted(untracked)))
    changed = production_paths(git("diff", "--no-renames", "--name-only", "-z", BASE))
    wa, callbacks = set(WA07A_PRODUCTION_SHA256), set(CALLBACK_59F_PRODUCTION_SHA256)
    require(wa and callbacks and not wa & callbacks, "reviewed production profiles overlap or are empty")
    profiles = {
        frozenset(): ("60f-baseline", {}),
        frozenset(wa): ("60f-with-wa07a", WA07A_PRODUCTION_SHA256),
        frozenset(callbacks): ("60f-with-59f", CALLBACK_59F_PRODUCTION_SHA256),
        frozenset(wa | callbacks): ("60f-with-wa07a-and-59f",
                                   {**WA07A_PRODUCTION_SHA256, **CALLBACK_59F_PRODUCTION_SHA256}),
    }
    require(frozenset(changed) in profiles,
            "unreviewed production delta: " + str(sorted(changed)))
    profile, hashes = profiles[frozenset(changed)]
    for path, digest in hashes.items():
        source = root / path
        require(source.is_file() and not source.is_symlink() and not source.stat().st_mode & 0o111,
                "reviewed production source must be a regular non-executable file: " + path)
        require(hashlib.sha256(source.read_bytes()).hexdigest() == digest,
                "reviewed production source hash differs: " + path)
        stage = git("ls-files", "--stage", "--", path).decode("utf-8").split()
        require(len(stage) == 4 and stage[0] == "100644" and stage[2] == "0" and stage[3] == path,
                "reviewed production source is not uniquely tracked: " + path)
    return profile


def regression_profile(root: Path) -> str:
    """Choose the pass inventory from source, retaining later increment development."""
    profile = current_regression_profile(root)[0]
    # The later rollout owns its exact three-file publication policy. Its
    # source gate, not XML counts or a branch name, admits the extra tests.
    if (root / "morphhdl/scripts/check-increment-60g-source-scope.py").is_file():
        load(root, "60g-source-scope").source_scope(root)
        profile += "-and-60g"
    return profile


def current_regression_profile(root: Path) -> tuple[str, bool]:
    def git(*args: str) -> bytes:
        return subprocess.check_output(["git", *args], cwd=root)

    def production_paths(data: bytes) -> set[str]:
        return {path.decode("utf-8") for path in data.split(b"\0")
                if re.search(rb"(?:^|/)src/main/", path)}

    require(subprocess.run(["git", "merge-base", "--is-ancestor", QUALIFIED_60F, "HEAD"],
                           cwd=root, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0,
            "qualified 60f must be an ancestor of HEAD")
    # Check index and working tree independently: an uncommitted staged edit
    # cannot be hidden by restoring only the working-tree bytes to HEAD.
    for label, args in (("staged", ("diff", "--cached", "--no-renames", "--name-only", "-z", "HEAD")),
                        ("unstaged", ("diff", "--no-renames", "--name-only", "-z"))):
        dirty = production_paths(git(*args))
        require(not dirty, label + " production sources: " + str(sorted(dirty)))
    untracked = production_paths(git("ls-files", "--others", "-z"))
    require(not untracked, "untracked production sources: " + str(sorted(untracked)))
    changed = production_paths(git("diff", "--no-renames", "--name-only", "-z", BASE, "HEAD"))
    # The whole pass workspace remains closed, including possible nested
    # production projects. A new path cannot evade the fixed WA inventory.
    pass_changes = {path for path in changed if path.startswith("morphhdl-passes/")}
    callbacks_completed = subprocess.run(
        ["git", "merge-base", "--is-ancestor", COMPLETED_59F, "HEAD"], cwd=root,
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    # Preserve the four exact original profiles, including their full source
    # hash controls. Other committed evolution uses the completed-increment
    # history and current audits below rather than relabeling an exact profile.
    callback_changes = set(CALLBACK_59F_PRODUCTION_SHA256)
    if changed in (set(), set(WA07A_PRODUCTION_SHA256), callback_changes,
                   set(WA07A_PRODUCTION_SHA256) | callback_changes):
        profile = production_profile(root)
        require(not callbacks_completed or profile in ("60f-with-59f", "60f-with-wa07a-and-59f"),
                "completed 59f callback profile cannot disappear from a descendant")
        return profile, False
    # The completed historical interval, sealed semantic authorities and current
    # native manifest remain gates. Other committed front-end development is
    # qualified by its own increment; this is not a global approval ledger.
    qualification_interval(root, BASE, QUALIFIED_60F)
    source_evolution_scope(root)
    profile = checked_pass_profile(root, pass_changes)
    # Completed callback support retains its complete regression inventory as
    # later increments extend shared implementation files. Mere file presence
    # cannot activate or suppress these separately qualified suites.
    if callbacks_completed:
        profile = "60f-with-wa07a-and-59f" if profile == "60f-with-wa07a" else "60f-with-59f"
    return profile, True


def restore_rollout(root: Path, path: str, source: str) -> str:
    helper = root / "morphhdl/scripts/check-increment-60g-source-scope.py"
    if not helper.is_file():
        return source
    spec = importlib.util.spec_from_file_location("rollout_scope", helper)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module.restore_60g_source(root, path, source)


def source_scope(root: Path) -> None:
    profile, current_scope_checked = current_regression_profile(root)
    if not current_scope_checked:
        source_evolution_scope(root)
    print("60f current regression pass profile: " + profile + " PASS", flush=True)


def source_evolution_scope(root: Path) -> None:
    qualification_interval(root, BASE, COMPLETED_60F)
    frozen = [
        "morphhdl/scripts/check-increment-60a-sint-baseline.py",
        "morphhdl/scripts/check-increment-60c-signed-declarations.py",
        "morphhdl/scripts/check-increment-60d-pure-sint-casts.py",
        "morphhdl/scripts/check-increment-60e-signedness-boundaries.py",
        "morphhdl/src/test/scala/nativeapplication/SIntSignedVerilogBaselineFixture.scala",
        "morphhdl/src/test/scala/nativeapplication/SIntSignedDeclarationsFixture.scala",
        "morphhdl/src/test/scala/nativeapplication/PureSIntCastFixture.scala",
        "morphhdl/src/test/scala/spinal/core/SignednessBoundaryFixture.scala",
        "morphhdl/src/main/scala/spinal/core/internals/MorphHdlSignednessAnalysis.scala",
        "morphhdl/src/main/scala/morphhdl/analysis/SignednessFacts.scala",
        "morphhdl/contracts/increment-60d-emitter-edits.json",
        "morphhdl/contracts/increment-60e-boundary-edits.json",
    ]
    for path in frozen:
        old = subprocess.check_output(["git", "show", BASE + ":" + path], cwd=root)
        current = restore_rollout(root, path, (root / path).read_text()).encode()
        if path == "morphhdl/scripts/check-increment-60e-signedness-boundaries.py" and \
                (root / "morphhdl/scripts/check-increment-59f-source-scope.py").exists():
            current = load(root, "59f-source-scope").restore_59f_source(
                root, path, current.decode()).encode()
        require(current == old, "sealed oracle/authority/contract changed: " + path)
    # The declaration/cast printers remain sealed at the completed signedness
    # qualification even as unrelated reviewed native entry points evolve.
    for path in ("core/src/main/scala/spinal/core/internals/VerilogBase.scala",
                 "core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala"):
        old = subprocess.check_output(["git", "show", COMPLETED_60F + ":" + path], cwd=root)
        require((root / path).read_bytes() == old,
                "native signed declaration/cast hooks changed after their frozen qualification")
    # Exact publisher restoration remains a current check after callback
    # support has merged; the historical signedness tree cannot replace it.
    if (root / "morphhdl/scripts/check-increment-59f-source-scope.py").exists():
        load(root, "59f-source-scope").source_scope(root)
    # Preserve the predecessor source gates' ongoing inference bans on current
    # policies; these are not historical diff-scope restrictions.
    for name in ("MorphHdlSignedWidth.scala", "MorphHdlSignedDeclarationPolicy.scala", "MorphHdlPureSIntCastPolicy.scala"):
        source = (root / "morphhdl/src/main/scala/spinal/core/internals" / name).read_text()
        for token in ("getName", "definitionName", "getScalaLocation", "ThreadLocal", "replaceAll", ".r\n"):
            require(token not in source, "signedness authority uses forbidden inference: " + token)
    # The inherited restoration checks describe the implementation that 60f
    # qualified. Re-run them on that exact completed tree; later increments
    # legitimately extend these same production files. Their current native
    # changes must still pass the complete path/blob/span audit below, and the
    # current semantic oracles above remain sealed to their original bytes.
    with tempfile.TemporaryDirectory(prefix="morphhdl-60f-history-") as directory:
        historical = Path(directory) / "completed"
        subprocess.run(["git", "worktree", "add", "--quiet", "--detach", str(historical), COMPLETED_60F],
                       cwd=root, check=True)
        try:
            for suffix in ("60c-signed-declarations", "60d-pure-sint-casts", "60e-signedness-boundaries"):
                load(historical, suffix).source_scope(historical)
        finally:
            subprocess.run(["git", "worktree", "remove", "--force", str(historical)], cwd=root, check=True)
    subprocess.run(["python3", "morphhdl/scripts/check-native-source-preservation.py"], cwd=root, check=True)
    print("60f completed qualification-only scope, sealed current oracles and current native audits PASS", flush=True)


def inventory(root: Path, out: Path) -> dict[str, str]:
    boundary = load(root, "60e-signedness-boundaries")
    paths = ["boundaries/" + kind + "/" + file
             for kind in boundary.KINDS
             for file in ["candidate.v", *("fixed-" + boundary.key(p) + ".v" for p in boundary.tuples(kind))]]
    pure = [*(f"fixed-{w}.v" for w in WIDTHS), *(f"boundary-fixed-{w}.v" for w in WIDTHS),
            "disabled.v", "declarations.v", "pure-true.v", "boundaries.v", "baseline-clean.v",
            "declaration-fixture-clean.v", *(f"inherited/fixed-{w}.v" for w in WIDTHS),
            *("inherited/" + name + ".v" for name in ("functions-fixed", "functions", "disabled", "signed",
              "direct", "surfaces", "bundle-surfaces", "baseline-signed")),
            *("baseline/" + name + ".v" for name in ("sint_cast_heavy_fixed", "sint_cast_heavy_parameterized",
                                                       "sint_cast_heavy_nested"))]
    require(len(paths) == 70 and len(pure) == 29, "inherited artifact inventory changed")
    paths += ["pure/" + path for path in pure]
    manifest = {}
    for path in paths:
        require((out / path).is_file(), "missing independently generated artifact: " + path)
        manifest[path] = hashlib.sha256((out / path).read_bytes()).hexdigest()
    load(root, "60a-sint-baseline").verify_hashes(root, out / "pure/baseline")
    return manifest


def prefixed_modules(source: str, prefix: str) -> str:
    modules = re.findall(r"\bmodule\s+(\w+)", source)
    require(modules and len(modules) == len(set(modules)), "missing or duplicated generated module")
    mapping = {name: prefix + name for name in modules}
    return re.sub(r"\b(?:" + "|".join(map(re.escape, modules)) + r")\b",
                  lambda match: mapping[match[0]], source)


def invalidate_summaries(out: Path) -> None:
    # Even source/tool/inventory rejection must invalidate a prior success claim.
    for path in ("qualification-60f.json", "closure/memory-qualification.json",
                 "closure/baseline-mutation/qualification.json"):
        (out / path).unlink(missing_ok=True)


def sat_script(directory: Path, files: list[str], label: str, *, steps: int | None = None,
               counterexample: bool = False) -> str:
    options = f"-seq {steps} " if steps is not None else ""
    # No -set-init-zero: each DUT's untouched memory/register state is arbitrary.
    options += "-prove equal_result 1 -show-inputs -show-outputs"
    if counterexample:
        for extension in ("json", "vcd"):
            (directory / (label + "-witness." + extension)).unlink(missing_ok=True)
        options += f" -dump_json {label}-witness.json -dump_vcd {label}-witness.vcd"
    # Never opt_merge the two DUTs: matching uninitialized FF inputs must not
    # correlate their independent arbitrary initial Q values. Only local constant
    # folding and dead-cone removal are used before the SAT state model.
    commands = ["read_verilog " + " ".join(files), "hierarchy -check -top ClosureMiter",
                "proc", "flatten", "memory_map", "opt_expr", "opt_clean", "dffunmap", "opt_clean",
                "check -assert", "sat " + options]
    (directory / (label + ".ys")).write_text("\n".join(commands) + "\n")
    output = run(["yosys", "-s", label + ".ys"], directory, label,
                 SAT_FAIL if counterexample else SAT_PASS)
    if counterexample:
        for extension in ("json", "vcd"):
            witness = directory / (label + "-witness." + extension)
            require(witness.is_file() and witness.stat().st_size > 0, "solver did not save its witness: " + str(witness))
    return output


def baseline_mutation(root: Path, out: Path) -> None:
    (out / "closure/baseline-mutation/qualification.json").unlink(missing_ok=True)
    baseline = load(root, "60a-sint-baseline")
    source = out / "pure/baseline"
    baseline.verify_hashes(root, source)
    directory = out / "closure/baseline-mutation"
    directory.mkdir(parents=True, exist_ok=True)
    candidate = (source / "sint_cast_heavy_parameterized.v").read_text()
    mutant, count = re.subn(r"\bassign\s+negative_out\s*=\s*[^;]+;",
                            "assign negative_out = 8'h00;", candidate)
    require(count == 1, "exact 60a mutation must replace one negative_out assignment")
    # Only module identifiers in an isolated reference copy are prefixed. Neither
    # reference arithmetic nor candidate expressions are reconstructed here.
    (directory / "gold.v").write_text(prefixed_modules((source / "sint_cast_heavy_fixed.v").read_text(), "Gold_"))
    (directory / "candidate.v").write_text(candidate)
    (directory / "mutant.v").write_text(mutant)
    (directory / "external.v").write_text("""module SIntCastHeavyExternal #(parameter integer WIDTH=8)(
input wire [WIDTH-1:0] din, output wire [WIDTH-1:0] dout);
assign dout=din;
endmodule
""")
    # The cone is precisely the 60a mutation. Other outputs are open; constants on
    # unrelated inputs prune memory/division, never constrain arbitrary left.
    connections = (".left(left),.clk(1'b0),.enable(1'b0),.choose_left(1'b0),"
                   ".write_enable(1'b0),.address(2'd0),.right(8'd0),.third(8'd0),"
                   ".divisor(8'd1),.memory_write_data(8'd0)")
    (directory / "miter.v").write_text(f"""module ClosureMiter(input wire [7:0] left,
output wire equal_result, output wire [7:0] gold_negative, candidate_negative);
Gold_SIntCastHeavyBaseline gold({connections},.negative_out(gold_negative));
SIntCastHeavyBaseline #(.WIDTH(8)) candidate({connections},.negative_out(candidate_negative));
assign equal_result = gold_negative == candidate_negative;
endmodule
""")
    sat_script(directory, ["gold.v", "candidate.v", "external.v", "miter.v"], "positive-control")
    sat_script(directory, ["gold.v", "mutant.v", "external.v", "miter.v"], "negative-control", counterexample=True)
    wave = json.loads((directory / "negative-control-witness.json").read_text())
    signals = {entry["name"].lstrip("\\"): entry for entry in wave.get("signal", []) if isinstance(entry, dict)}
    require("left" in signals, "solver witness does not contain the arbitrary signed input")
    data = signals["left"].get("data", [])
    require(data and re.fullmatch(r"[01]{8}", str(data[0])) is not None,
            "solver witness must contain a fully defined 8-bit left value")
    value = int(data[0], 2)
    require(value != 0, "solver witness cannot expose the exact mutation at left=0")
    (directory / "replay.v").write_text(f"""module WitnessReplay;
reg [7:0] left;
wire equal_result;
wire [7:0] gold_negative, candidate_negative;
ClosureMiter dut(.left(left),.equal_result(equal_result),.gold_negative(gold_negative),
.candidate_negative(candidate_negative));
initial begin
left=8'h{value:02x}; #1;
if(equal_result !== 1'b0 || gold_negative !== 8'h{(-value) & 255:02x} || candidate_negative !== 8'h00) begin
$display("FAIL:60A_SOLVER_WITNESS_REPLAY"); $finish;
end
$display("SIXTY_A_SOLVER_WITNESS_REPLAY_OK"); $finish;
end
endmodule
""")
    run(["iverilog", "-g2001", "-s", "WitnessReplay", "-o", "replay.vvp", "gold.v", "mutant.v",
         "external.v", "miter.v", "replay.v"], directory, "replay-compile")
    replay = run(["vvp", "replay.vvp"], directory, "replay")
    require("SIXTY_A_SOLVER_WITNESS_REPLAY_OK" in replay and "FAIL:" not in replay,
            "independent simulator did not reproduce the solver counterexample")
    (directory / "qualification.json").write_text(json.dumps({
        "mutation": "assign negative_out = 8'h00;", "positive_control": "proved",
        "negative_control": "solver_counterexample", "witness_left": value,
        "icarus_replay": "passed"}, indent=2) + "\n")
    print("60f exact 60a mutation: positive proof, genuine SAT witness and Icarus replay PASS", flush=True)


def memory_validity(root: Path, out: Path) -> None:
    (out / "closure/memory-qualification.json").unlink(missing_ok=True)
    declaration = load(root, "60c-signed-declarations")
    candidate = (out / "pure/declaration-fixture-clean.v").read_text()
    for width in WIDTHS:
        directory = out / f"closure/memory-{width}"
        directory.mkdir(parents=True, exist_ok=True)
        native = (out / f"pure/inherited/fixed-{width}.v").read_text()
        physical = declaration.ports(native)
        inputs = [(bits, name) for direction, bits, name in physical if direction == "input"]
        require({name for _, name in inputs} == {"clk", "enable", "choose", "write", "address", "amount",
                                                    "a", "b", "raw", "wideIn"}, "memory fixture inputs changed")
        (directory / "gold.v").write_text(prefixed_modules(native, "Gold_"))
        (directory / "candidate.v").write_text(candidate)
        mutant, count = re.subn(r"\bassign\s+memOut\s*=\s*[^;]+;", "assign memOut = 0;", candidate)
        require(count == 1, "memory observability mutation must affect exactly one output")
        (directory / "mutant.v").write_text(mutant)
        ports = ",\n".join(f"input wire [{bits-1}:0] {name}" for bits, name in inputs)
        connections = ",".join(f".{name}({name})" for _, name in inputs)
        (directory / "miter.v").write_text(f"""module ClosureMiter({ports},
output wire equal_result, output reg memory_valid, output reg register_valid,
output wire [{width-1}:0] gold_memory, candidate_memory, gold_register, candidate_register);
// Only harness validity is initialized. DUT memory and data registers remain arbitrary.
reg [1:0] written;
initial begin written=2'b00; memory_valid=1'b0; register_valid=1'b0; end
always @(posedge clk) begin
  if(write) written[address] <= 1'b1;
  // readFirst: a simultaneous first write/read still returns uninitialized old data.
  if(enable) begin memory_valid <= written[address]; register_valid <= 1'b1; end
end
Gold_SignedDeclarations gold({connections},.memOut(gold_memory),.regOut(gold_register));
SignedDeclarations #(.WIDTH({width})) candidate({connections},.memOut(candidate_memory),.regOut(candidate_register));
assign equal_result = (!memory_valid || gold_memory == candidate_memory) &&
                      (!register_valid || gold_register == candidate_register);
endmodule
""")
        sat_script(directory, ["gold.v", "candidate.v", "miter.v"], "validity-proof", steps=MEMORY_STEPS)
        sat_script(directory, ["gold.v", "mutant.v", "miter.v"], "validity-observability",
                   steps=MEMORY_STEPS, counterexample=True)
        masked = (directory / "miter.v").read_text()
        original_comparison = ("assign equal_result = (!memory_valid || gold_memory == candidate_memory) &&\n"
                               "                      (!register_valid || gold_register == candidate_register);")
        require(masked.count(original_comparison) == 1, "memory validity comparison must be unique")
        for state in ("memory", "register"):
            # Separate controls prove that neither memory nor register initial
            # values were silently correlated by preprocessing. At step 1 there
            # has been no write or enabled read/update; equality is not promised.
            unmasked = masked.replace(original_comparison,
                                      f"assign equal_result = gold_{state} == candidate_{state};")
            name = f"uninitialized-{state}"
            (directory / (name + ".v")).write_text(unmasked)
            sat_script(directory, ["gold.v", "candidate.v", name + ".v"], name,
                       steps=1, counterexample=True)
        print(f"60f WIDTH={width}: {MEMORY_STEPS}-cycle validity proof, live mutation and independent memory/register initial-state controls PASS", flush=True)
    (out / "closure/memory-qualification.json").write_text(json.dumps({
        "widths": list(WIDTHS), "bounded_steps": MEMORY_STEPS,
        "dut_initialization": "independent arbitrary states; no zero initialization",
        "memory_comparison": "enabled synchronous read of previously written word; readFirst; validity holds when disabled",
        "register_comparison": "after first enabled update; validity holds when disabled",
        "input_constraints": "none", "observability_mutations": len(WIDTHS),
        "independent_initial_state_controls": {"memory": len(WIDTHS), "register": len(WIDTHS), "steps": 1},
        "unbounded_equivalence": "retained inherited 60c/60d induction gates"}, indent=2) + "\n")


def qualify_inherited(root: Path, out: Path, track: str) -> None:
    """Run a sealed predecessor's unchanged behavioral gates on current RTL."""
    require(track in INHERITED_TRACKS, "unknown inherited qualification track: " + track)
    inherited = load(root, INHERITED_TRACKS[track])
    if track == "60e":
        inherited.qualify(root, out, tuple(inherited.KINDS), ("simulation", "formal", "tools"))
        inherited.mutations(out)
    else:
        # Both 60c and 60d qualify() include their original mutation gates.
        inherited.qualify(root, out)
    print(track + " inherited behavioral qualification on current artifacts PASS", flush=True)


def qualify(root: Path, out: Path, closure_only: bool = False) -> None:
    invalidate_summaries(out)
    for tool in ("yosys", "iverilog", "vvp", "verilator"):
        require(shutil.which(tool) is not None, "missing required tool: " + tool)
    manifest = inventory(root, out)
    if not closure_only:
        qualify_inherited(root, out / "boundaries", "60e")
        qualify_inherited(root, out / "pure", "60d")
    baseline_mutation(root, out)
    memory_validity(root, out)
    require(inventory(root, out) == manifest, "qualification mutated independently generated RTL")
    (out / "qualification-60f.json").write_text(json.dumps({
        "scope": "supplementary closure only" if closure_only else "full inherited and closure qualification",
        "independent_generated_files": len(manifest), "sha256": manifest,
        "boundary_equivalence_tuples": None if closure_only else 64,
        "arithmetic_domain": "same nonzero divisor mapping on both inherited proof legs",
        "exact_60a_mutation": "SAT counterexample, positive proof and Icarus witness replay",
        "memory_widths": list(WIDTHS), "memory_bounded_steps": MEMORY_STEPS,
        "independent_initial_state_counterexamples": 2 * len(WIDTHS),
        "publication_default": "owned by the calling increment; this proof checks candidate semantics"}, indent=2) + "\n")
    print("60f equivalence, defined domains and solver-counterexample closure PASS", flush=True)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", nargs="?", type=Path)
    parser.add_argument("--source-only", action="store_true")
    parser.add_argument("--self-test", action="store_true", help="run solver-result and real Git/native-audit source-scope controls")
    parser.add_argument("--closure-only", action="store_true", help="run supplementary proofs only; never claims full qualification")
    parser.add_argument("--inherited-track", choices=tuple(INHERITED_TRACKS),
                        help="run one unchanged predecessor's full artifact qualification after the historical/current source gate")
    parser.add_argument("--skip-source", action="store_true", help="artifact stages after a separately completed source gate")
    args = parser.parse_args()
    require(not (args.inherited_track and args.closure_only), "inherited-track cannot select supplementary 60f closure")
    root = Path(__file__).resolve().parents[2]
    output = args.output.resolve() if args.output else None
    if output is not None:
        invalidate_summaries(output)
    self_test()
    if args.self_test and not args.output and not args.source_only:
        return
    if not args.skip_source:
        source_scope(root)
    if not args.source_only:
        require(args.output is not None, "artifact output directory is required")
        if args.inherited_track:
            qualify_inherited(root, output, args.inherited_track)
        else:
            qualify(root, output, args.closure_only)


if __name__ == "__main__":
    main()
