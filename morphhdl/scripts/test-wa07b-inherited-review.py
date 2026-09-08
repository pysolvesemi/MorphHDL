#!/usr/bin/env python3
"""Real-Git controls for the bounded WA-07b inherited-audit composition.

Only disposable repositories/worktrees are changed. Synthetic source checks
and current full inherited checks are separate; neither replaces RTL proofs.
"""
from __future__ import annotations

import copy
import importlib.util
import json
import shutil
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HELPER = "morphhdl/scripts/check-wa07b-inherited-review.py"
CLOSURE = "morphhdl/scripts/check-increment-60f-equivalence-closure.py"
ARTIFACTS = "morphhdl/scripts/check-increment-60f-artifacts.py"


def load(path: Path, name: str):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load " + str(path))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def git(root: Path, *args: str) -> bytes:
    result = subprocess.run(["git", "-c", "user.name=WA07b validation", "-c",
                             "user.email=wa07b-validation@example.invalid", *args], cwd=root,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
    if result.returncode:
        raise RuntimeError("fixture git failed: " + result.stdout.decode())
    return result.stdout


def rejected(label: str, action, text: str = "WA07B inherited review") -> None:
    try:
        action()
    except RuntimeError as error:
        if text not in str(error):
            raise RuntimeError(label + " failed for an unrelated reason: " + str(error)) from error
    else:
        raise RuntimeError("accepted mutation: " + label)


def synthetic_controls(helper_path: Path, manifest: dict, baseline: dict[str, bytes],
                       candidate: dict[str, bytes], adapters: dict[str, bytes]) -> int:
    """Run actual Git/index checks; only immutable anchor constants use fixture SHAs."""
    module = load(helper_path, "wa07b_synthetic_review")
    negatives = 0
    with tempfile.TemporaryDirectory(prefix="wa07b-source-controls-") as directory:
        root = Path(directory)
        git(root, "init", "-q")
        for path, data in baseline.items():
            target = root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        git(root, "add", ".")
        git(root, "commit", "-qm", "synthetic frozen source baseline")
        base = git(root, "rev-parse", "HEAD").decode().strip()
        # The actual qualified successor is not an ancestor of the no-B sibling.
        for path, data in candidate.items():
            target = root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        git(root, "add", ".")
        git(root, "commit", "-qm", "synthetic qualified successor")
        qualified = git(root, "rev-parse", "HEAD").decode().strip()
        git(root, "reset", "--hard", base)
        value = copy.deepcopy(manifest)
        value["baseline"], value["qualified_pass_commit"] = base, qualified
        module.BASE, module.QUALIFIED = base, qualified
        raw = (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()
        contract = root / module.CONTRACT
        contract.parent.mkdir(parents=True, exist_ok=True)
        contract.write_bytes(raw)
        module.CONTRACT_SHA256 = module.digest(raw)
        for path, data in adapters.items():
            (root / path).write_bytes(data)
        git(root, "add", ".")
        git(root, "commit", "-qm", "synthetic compatibility adapters without B")
        assert module.verify(root) is False

        def production_diagnostics(paths: dict[str, bytes], profile: str) -> int:
            # Exercise real committed mutations of every main source in both
            # profiles. The unmodified inherited 59g controls expect this exact
            # category for changed and removed merged sibling sources.
            snapshot = git(root, "rev-parse", "HEAD").decode().strip()
            rejected_count = 0
            for path in sorted(p for p in paths if p.startswith(module.ROOTS[0] + "/")):
                for mutation in ("changed", "removed"):
                    target = root / path
                    if mutation == "changed":
                        target.write_bytes(target.read_bytes() + b"\n// diagnostic mutation\n")
                    else:
                        target.unlink()
                    git(root, "add", "--", path)
                    git(root, "commit", "-qm", "synthetic production diagnostic control")
                    rejected(profile + " " + mutation + " " + path,
                             lambda: module.verify(root), "unreviewed production delta")
                    rejected_count += 1
                    git(root, "reset", "--hard", snapshot)
            assert module.verify(root) is (profile == "wa07b")
            return rejected_count

        negatives += production_diagnostics(baseline, "pre-wa07b")
        # Mirror the real integration: original pass bytes remain exactly those
        # qualified independently, not a hand-authored replacement fixture.
        for path, data in candidate.items():
            target = root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
        git(root, "add", ".")
        git(root, "commit", "-qm", "synthetic B integration")
        head = git(root, "rev-parse", "HEAD").decode().strip()
        assert module.verify(root) is True
        negatives += production_diagnostics(candidate, "wa07b")
        delta = set(value["production_delta"])
        unknown = "foreign/src/main/MustRemain.scala"
        assert module.inherited_inventory(root, delta | {unknown}, base) == {unknown}
        for path in delta:
            expected = baseline.get(path, b"")
            assert module.restore_pass_source(root, path, candidate[path]) == expected
        for entry in value["checker_adapters"]:
            path = entry["path"]
            assert module.restore_adapter(root, path, adapters[path].decode()).encode() == baseline[path]
            rejected("adapter suffix " + path,
                     lambda: module.restore_adapter(root, path, (adapters[path] + b"# changed\n").decode()))
            negatives += 1
        # Every reviewed main or test source is individually mutation-sensitive.
        for path in candidate:
            target = root / path
            original = target.read_bytes()
            target.write_bytes(original + b"\n// unreviewed\n")
            rejected("changed source " + path, lambda: module.verify(root))
            negatives += 1
            target.write_bytes(original)
        source = root / module.MARKER
        original = source.read_bytes()
        source.unlink()
        rejected("missing marker", lambda: module.verify(root)); negatives += 1
        source.write_bytes(original)
        source.chmod(0o755)
        rejected("executable source", lambda: module.verify(root)); negatives += 1
        source.chmod(0o644)
        source.unlink()
        source.symlink_to(root / next(path for path in candidate if path != module.MARKER))
        rejected("symlink source", lambda: module.verify(root)); negatives += 1
        source.unlink(); source.write_bytes(original)
        source.write_bytes(original + b"\n// hidden staged difference\n")
        git(root, "add", "--", module.MARKER)
        source.write_bytes(original)
        rejected("hidden staged source", lambda: module.verify(root)); negatives += 1
        git(root, "reset", "--hard", head)
        extra = root / module.ROOTS[0] / "Unreviewed.scala"
        extra.write_text("// not reviewed\n")
        rejected("extra source", lambda: module.verify(root)); negatives += 1
        (root / ".gitignore").write_text("Unreviewed.scala\n")
        rejected("ignored extra source", lambda: module.verify(root)); negatives += 1
        extra.unlink(); (root / ".gitignore").unlink()
        # A source-name marker cannot select the profile while other files remain old.
        path = next(p for p in delta if p != module.MARKER)
        (root / path).write_bytes(baseline[path])
        rejected("partial pass upgrade", lambda: module.verify(root)); negatives += 1
        git(root, "reset", "--hard", head)
        contract.write_bytes(raw + b"\n")
        rejected("altered manifest", lambda: module.verify(root)); negatives += 1
        paired = copy.deepcopy(value)
        paired["ternary_sources"][module.MARKER] = module.digest(original + b"\n// paired\n")
        contract.write_text(json.dumps(paired))
        source.write_bytes(original + b"\n// paired\n")
        rejected("paired source and review change", lambda: module.verify(root)); negatives += 1
        git(root, "reset", "--hard", head)
        source.write_bytes(original + b"\n// committed but not reviewed\n")
        git(root, "add", "."); git(root, "commit", "-qm", "synthetic unreviewed descendant")
        rejected("committed unreviewed bytes", lambda: module.verify(root)); negatives += 1
        git(root, "reset", "--hard", head)
        assert module.verify(root) is True
        # Once the qualified branch is merged, wholesale removal cannot silently
        # downgrade the source profile to the old 14-suite obligations.
        tree = git(root, "rev-parse", "HEAD^{tree}").decode().strip()
        merge = git(root, "commit-tree", tree, "-p", head, "-p", qualified,
                    "-m", "synthetic qualified merge").decode().strip()
        git(root, "reset", "--hard", merge)
        assert module.verify(root) is True
        for prefix in module.ROOTS:
            shutil.rmtree(root / prefix)
        for path in value["baseline_sources"]:
            target = root / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(baseline[path])
        git(root, "add", "."); git(root, "commit", "-qm", "synthetic forbidden complete downgrade")
        rejected("qualified profile downgrade", lambda: module.verify(root)); negatives += 1
    print("WA07B_INHERITED_MUTATIONS_PASS rejected=" + str(negatives), flush=True)
    return negatives


def current_controls(root: Path) -> None:
    review = load(root / HELPER, "wa07b_current_review")
    manifest = review.load_contract(root)
    baseline = {path: git(root, "show", review.BASE + ":" + path)
                for path in manifest["baseline_sources"]}
    baseline.update({entry["path"]: git(root, "show", review.BASE + ":" + entry["path"])
                     for entry in manifest["checker_adapters"]})
    candidate = {path: git(root, "show", review.QUALIFIED + ":" + path)
                 for path in manifest["ternary_sources"]}
    # Synthetic WA-only controls exercise the unchanged qualified adapter layer.
    # Real combined sources are separately checked below by the complete gates.
    adapters = {path: review.restore_rollout(root, path, (root / path).read_text()).encode()
                for path in review.ADAPTER_PATHS}
    negatives = synthetic_controls(root / HELPER, manifest, baseline, candidate, adapters)
    closure = load(root / CLOSURE, "wa07b_current_closure")
    artifacts = load(root / ARTIFACTS, "wa07b_current_artifacts")
    # No synthetic counts or source stubs enter these full inherited checks.
    closure.source_scope(root)
    source_head = git(root, "rev-parse", "HEAD").decode().strip()
    result = {"head": source_head, "source_mutations_rejected": negatives,
              "current_profile": closure.regression_profile(root)}
    with tempfile.TemporaryDirectory(prefix="wa07b-inherited-integration-") as directory:
        fixture = Path(directory) / "integrated"
        git(root, "worktree", "add", "--quiet", "--detach", str(fixture), "HEAD")
        try:
            for path, data in candidate.items():
                target = fixture / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
            git(fixture, "add", "--", *review.ROOTS)
            git(fixture, "commit", "--allow-empty", "-qm", "WA-07b exact qualified source integration control")
            closure.source_scope(fixture)
            profile = closure.regression_profile(fixture)
            assert "wa07b" in closure.profile_features(profile)
            counts, suites, exact = artifacts.catalog_for_profile(profile, packing=True)
            assert counts["morphhdl-passes"] == (144, 17)
            assert exact["morphhdl-passes"] == manifest["pass_suites"]
            assert suites["morphhdl-passes"] == set(manifest["pass_suites"])
            # The historical profile retains its original pass inventory.
            old = artifacts.catalog_for_profile(profile[:-len("-and-wa07b")], packing=True)
            assert old[0]["morphhdl-passes"] == (123, 14)
            result["integrated_profile"] = profile
            result["integrated_head"] = git(fixture, "rev-parse", "HEAD").decode().strip()
            result["qualified_pass_commit"] = review.QUALIFIED
        finally:
            git(root, "worktree", "remove", "--force", str(fixture))
    assert git(root, "rev-parse", "HEAD").decode().strip() == source_head
    output = root / "target/increment-60f/wa07b-source-composition.json"
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n")
    print("WA07B_INHERITED_COMPOSITION_PASS current and exact B source audits; historical profile retained", flush=True)


if __name__ == "__main__":
    current_controls(ROOT)
