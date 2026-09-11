#!/usr/bin/env python3
"""Current-source attack controls plus separately scoped frozen 59c controls."""
from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASE = "0018da2740645e0ac0c419ded7b67c01622d2bb7"
CONTRACT = "morphhdl/contracts/increment-59g-source-review.json"
CHECKER = "morphhdl/scripts/check-increment-60f-equivalence-closure.py"
DRIVER = """import importlib.util, sys
from pathlib import Path
spec = importlib.util.spec_from_file_location('closure_scope', sys.argv[2])
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
module.source_scope(Path(sys.argv[1]))
"""


def git(root: Path, *args: str) -> str:
    result = subprocess.run(["git", "-C", str(root), *args], text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            timeout=120, check=False)
    if result.returncode:
        raise RuntimeError("59g source-control Git failure:\n" + result.stdout)
    return result.stdout.strip()


def check(root: Path, label: str, expected: str | None = None) -> dict:
    result = subprocess.run([sys.executable, "-c", DRIVER, str(root), str(ROOT / CHECKER)],
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            timeout=180, check=False)
    if expected is None:
        if result.returncode or "inherited native audits PASS" not in result.stdout:
            raise RuntimeError(label + " failed complete source qualification:\n" + result.stdout)
    elif not result.returncode or expected not in result.stdout:
        raise RuntimeError(label + " did not reject for " + expected + ":\n" + result.stdout)
    print("PASS:", label, "[" + (expected or "accepted") + "]", flush=True)
    return dict(case=label, expected_rejection=expected, exit_code=result.returncode,
                head=git(root, "rev-parse", "HEAD"))


def frozen_59c_controls(root: Path, current_check) -> None:
    """Keep every old mutation unchanged while current 59g checks stay mandatory."""
    head = git(root, "rev-parse", "HEAD")
    current = current_check()
    output = root / "target/increment-59c-source-scope"
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="morphhdl-59g-frozen-59c-") as directory:
        historical = Path(directory) / "completed-59c"
        git(root, "worktree", "add", "--quiet", "--detach", str(historical), BASE)
        try:
            result = subprocess.run([sys.executable, "morphhdl/scripts/test-increment-59c-inherited-source-scope.py"],
                                    cwd=historical, text=True, stdout=subprocess.PIPE,
                                    stderr=subprocess.STDOUT, timeout=900, check=False)
            (output / "historical-59c-controls.log").write_text(result.stdout)
            if result.returncode or "59c current-source controls PASS" not in result.stdout:
                raise RuntimeError("frozen 59c source controls failed:\n" + result.stdout)
            evidence = json.loads((historical / "target/increment-59c-source-scope/evidence.json").read_text())
            if evidence["head"] != BASE:
                raise RuntimeError("frozen 59c controls identify a different source tree")
        finally:
            git(root, "worktree", "remove", "--force", str(historical))
    if git(root, "rev-parse", "HEAD") != head:
        raise RuntimeError("frozen 59c controls changed the real branch HEAD")
    (output / "evidence.json").write_text(json.dumps(dict(
        head=head, current_source_cases=[current], historical_contract_controls=evidence,
        scope="Current complete 59g and inherited audits plus unchanged historical 59c controls; historical cases do not qualify 59g."),
        indent=2) + "\n")
    print("59c current-source controls PASS: current complete 59g audit; frozen 59c mutations separately scoped", flush=True)


def main() -> None:
    head = git(ROOT, "rev-parse", "HEAD")
    records = [check(ROOT, "current exact 59g source and all inherited guards")]
    spec = importlib.util.spec_from_file_location("bridge_review", ROOT / "morphhdl/scripts/check-increment-59g-source-review.py")
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    cases = [("unreviewed suffix " + path, path, "suffix",
              "unreviewed source change outside reviewed 59g spans") for path in helper.PATHS]
    cases += [
        ("changed reviewed manifest", CONTRACT, "manifest", "59g reviewed source manifest changed"),
        ("missing reviewed manifest", CONTRACT, "remove", "59g source-review checker or contract is missing"),
        ("missing reviewed checker", "morphhdl/scripts/check-increment-59g-source-review.py",
         "remove", "59g source-review checker or contract is missing"),
        ("extra production file", "foreign/src/main/Unreviewed.scala", "suffix", "unreviewed production delta"),
        ("changed merged sibling source", "morphhdl-passes/src/main/scala/morphhdl/passes/transform/ConstantOperandSimplificationPass.scala", "suffix", "unreviewed production delta"),
        ("removed merged sibling source", "morphhdl-passes/src/main/scala/morphhdl/passes/transform/ConstantOperandSimplificationPass.scala", "remove", "unreviewed production delta"),
        ("untracked production file", "morphhdl/src/main/Unreviewed.scala", "untracked", "untracked production sources"),
        ("executable reviewed source", next(iter(sorted(helper.PRODUCTION_PATHS))), "executable", "59g reviewed source must be a regular non-executable file"),
        ("symlink reviewed source", next(iter(sorted(helper.PRODUCTION_PATHS))), "symlink", "59g reviewed source must be a regular non-executable file"),
        ("paired manifest and source", next(iter(sorted(helper.PRODUCTION_PATHS))), "paired", "59g reviewed source manifest changed"),
        ("staged source hidden by restored worktree", next(iter(sorted(helper.PRODUCTION_PATHS))), "staged", "staged production sources"),
        ("changed independent native oracle", "morphhdl/src/test/scala/nativeapplication/SIntSignedVerilogBaselineFixture.scala", "suffix", "sealed writer/checker changed"),
    ]
    # On a combined rollout, overlapping bytes are sealed first by its exact
    # outer layer. Keep every original mutation; require that layer's precise
    # diagnostic rather than misclassifying a correct earlier rejection.
    rollout = helper.rollout_scope(ROOT) if hasattr(helper, "rollout_scope") else None
    if rollout is not None:
        adapted = []
        for label, path, mutation, expected in cases:
            if mutation == "suffix" and path in rollout.PATHS:
                expected = "or 60g publication spans: " + path
            elif mutation in ("suffix", "remove") and (
                    path.startswith("foreign/src/main/") or path in rollout.WA07A_PRODUCTION_SHA256):
                expected = ("unreviewed production delta"
                            if path in rollout.WA07A_PRODUCTION_SHA256 and
                            (ROOT / "morphhdl/scripts/check-wa07b-inherited-review.py").is_file()
                            else "60g publication/serialization delta differs from the merged baseline")
            adapted.append((label, path, mutation, expected))
        cases = adapted
    if (ROOT / "morphhdl/scripts/check-increment-62-wa08-source-overlay.py").is_file():
        # The positive check above verifies the exact outer overlay. Keep each
        # attack intact and require its earlier, path-specific rejection.
        overlay_paths = {entry["path"] for entry in json.loads((ROOT /
            "morphhdl/contracts/increment-62-wa08-source-overlay.json").read_text())["files"]}
        adapted = []
        for label, path, mutation, expected in cases:
            if mutation == "suffix" and path in overlay_paths:
                expected = "WA-08 source overlay: unreviewed bytes cannot enter historical projection: " + path
            elif mutation == "staged" and path in overlay_paths:
                expected = "WA-08 source overlay: HEAD/index/worktree identity differs: " + path
            elif path.startswith("foreign/src/main/"):
                expected = "WA-08 source overlay: unreviewed production delta: governed inventory differs: " + repr([path])
            elif mutation in ("untracked", "staged"):
                expected = "WA-08 source overlay: staged, unstaged or untracked governed content: " + repr([path])
            adapted.append((label, path, mutation, expected))
        cases = adapted
    with tempfile.TemporaryDirectory(prefix="morphhdl-59g-source-control-") as temporary:
        for index, (label, path, mutation, expected) in enumerate(cases):
            fixture = Path(temporary) / str(index)
            git(ROOT, "worktree", "add", "--quiet", "--detach", str(fixture), head)
            try:
                target = fixture / path
                target.parent.mkdir(parents=True, exist_ok=True)
                original = target.read_bytes() if target.exists() else b""
                if mutation == "remove":
                    target.unlink()
                elif mutation == "manifest":
                    review = json.loads(original)
                    review["files"][0]["reason"] += " forged review"
                    target.write_text(json.dumps(review, indent=2) + "\n")
                elif mutation == "executable":
                    target.chmod(0o755)
                elif mutation == "symlink":
                    replacement = fixture / "symlink-source.txt"
                    replacement.write_bytes(original)
                    target.unlink()
                    target.symlink_to(replacement)
                elif mutation == "paired":
                    target.write_bytes(original + b"\n// paired unreviewed suffix\n")
                    review = json.loads((fixture / CONTRACT).read_text())
                    entry = next(item for item in review["files"] if item["path"] == path)
                    entry["reason"] += " forged paired review"
                    (fixture / CONTRACT).write_text(json.dumps(review, indent=2) + "\n")
                else:
                    comment = "#" if target.suffix == ".py" else "//"
                    target.write_bytes(original + ("\n" + comment + " isolated unreviewed source mutation\n").encode())
                if mutation != "untracked":
                    git(fixture, "add", "--", path)
                    if mutation == "paired":
                        git(fixture, "add", "--", CONTRACT)
                    if mutation == "staged":
                        target.write_bytes(original)
                    else:
                        git(fixture, "-c", "user.name=59g source fixture", "-c", "user.email=source@example.invalid",
                            "commit", "--no-verify", "-qm", "isolated 59g negative source control")
                records.append(check(fixture, label, expected))
            finally:
                git(ROOT, "worktree", "remove", "--force", str(fixture))
    if git(ROOT, "rev-parse", "HEAD") != head:
        raise RuntimeError("59g source controls changed the real branch HEAD")
    output = ROOT / "target/increment-59g/source-scope"
    output.mkdir(parents=True, exist_ok=True)
    (output / "evidence.json").write_text(json.dumps(dict(head=head, cases=records), indent=2) + "\n")
    print(f"59g current-source controls PASS: one positive and {len(cases)} exact rejections", flush=True)


if __name__ == "__main__":
    main()
