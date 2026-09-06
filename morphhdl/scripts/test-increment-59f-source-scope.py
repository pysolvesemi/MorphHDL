#!/usr/bin/env python3
"""Attack exact 59f restoration in isolated worktrees, retaining signed gates."""
from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HELPER = "morphhdl/scripts/check-increment-59f-source-scope.py"
CONTRACT = "morphhdl/contracts/increment-59f-publisher-edits.json"
FALLBACK = "morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala"
BOUNDARY = "morphhdl/scripts/check-increment-60e-signedness-boundaries.py"
CLOSURE = "morphhdl/scripts/check-increment-60f-equivalence-closure.py"
DRIVER = """import importlib.util, sys
from pathlib import Path
root = Path(sys.argv[1])
spec = importlib.util.spec_from_file_location('reviewed_scope', root / sys.argv[2])
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
module.source_scope(root)
"""


def git(root: Path, *arguments: str) -> str:
    return subprocess.check_output(["git", "-C", str(root), *arguments], text=True,
                                   stderr=subprocess.STDOUT, timeout=120).strip()


def check(root: Path, checker: str, label: str, rejection: str | None = None) -> dict:
    result = subprocess.run([sys.executable, "-c", DRIVER, str(root), checker],
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            timeout=120, check=False)
    if rejection is None:
        if result.returncode or "PASS" not in result.stdout:
            raise RuntimeError(label + " did not pass:\n" + result.stdout)
    elif not result.returncode or rejection not in result.stdout:
        raise RuntimeError(label + " did not reject for " + rejection + ":\n" + result.stdout)
    print("PASS:", label, "[" + (rejection or "accepted") + "]", flush=True)
    return {"case": label, "checker": checker, "expected_rejection": rejection,
            "exit_code": result.returncode}


def main() -> None:
    head = git(ROOT, "rev-parse", "HEAD")
    records = [check(ROOT, HELPER, "current exact publisher delta"),
               check(ROOT, CLOSURE, "current full inherited source gates")]
    with tempfile.TemporaryDirectory(prefix="morphhdl-59f-source-scope-") as temporary:
        fixture = Path(temporary) / "fixture"
        git(ROOT, "worktree", "add", "--detach", str(fixture), head)
        try:
            # Copy only this review's files so the test also checks uncommitted
            # source-gate work. No mutation is ever applied to the real checkout.
            for path in (HELPER, CONTRACT, FALLBACK, BOUNDARY, CLOSURE):
                shutil.copyfile(ROOT / path, fixture / path)
            fallback = (fixture / FALLBACK).read_text()
            boundary = (fixture / BOUNDARY).read_text()
            manifest = (fixture / CONTRACT).read_text()
            data = json.loads(manifest)
            span = next(entry for entry in data["files"] if entry["path"] == FALLBACK)["edits"][0]["after"]
            cases = (
                ("unrelated fallback addition", FALLBACK, fallback + "\n// unrelated mutation\n",
                 CLOSURE, "unreviewed source change outside 59f spans"),
                ("reviewed zero proof changed", FALLBACK,
                 fallback.replace("literal.getValue() == 0", "literal.getValue() == 1", 1),
                 CLOSURE, "unreviewed source change outside 59f spans"),
                ("duplicate reviewed span", FALLBACK, fallback + span,
                 HELPER, "unreviewed source change outside 59f spans"),
                ("unrelated sealed checker addition", BOUNDARY, boundary + "\n# unrelated mutation\n",
                 CLOSURE, "unreviewed source change outside 59f spans"),
                ("restoration hook removed", BOUNDARY,
                 boundary.replace("source = module.restore_59f_source(root, path, source)", "source = source", 1),
                 CLOSURE, "unreviewed source change outside 59f spans"),
                ("manifest baseline changed", CONTRACT,
                 manifest.replace(data["base"], "0" * 40, 1),
                 HELPER, "59f reviewed publisher manifest changed"),
                ("manifest path broadened", CONTRACT, manifest.replace(FALLBACK, "other/src/main/Unreviewed.scala", 1),
                 HELPER, "59f reviewed publisher manifest changed"),
                ("manifest span changed", CONTRACT, manifest.replace("literal.getValue() == 0", "literal.getValue() == 1", 1),
                 HELPER, "59f reviewed publisher manifest changed"),
                ("missing manifest", CONTRACT, None, CLOSURE, "FileNotFoundError"),
                ("missing restoration helper", HELPER, None, CLOSURE, "sealed writer/checker changed"),
                ("changed independent oracle", "morphhdl/src/test/scala/nativeapplication/SIntSignedVerilogBaselineFixture.scala",
                 "// independent oracle was replaced\n", CLOSURE, "sealed writer/checker changed"),
                ("dirty native signed hook", "core/src/main/scala/spinal/core/internals/VerilogBase.scala",
                 "// native signed hook was replaced\n", CLOSURE,
                 "MORPH-NATIVE-AUDIT-DIRTY-WORKTREE"),
            )
            for label, path, replacement, checker, rejection in cases:
                target = fixture / path
                original = target.read_bytes()
                if replacement is not None and replacement.encode() == original:
                    raise RuntimeError("mutation did not change source: " + label)
                try:
                    if replacement is None:
                        target.unlink()
                    else:
                        target.write_text(replacement)
                    records.append(check(fixture, checker, label, rejection))
                finally:
                    target.write_bytes(original)
            records.append(check(fixture, CLOSURE, "restored fixture preserves inherited gates"))
        finally:
            git(ROOT, "worktree", "remove", "--force", str(fixture))
    if git(ROOT, "rev-parse", "HEAD") != head:
        raise RuntimeError("source-scope fixtures changed the real checkout HEAD")
    output = ROOT / "target/increment-59f/source-scope"
    output.mkdir(parents=True, exist_ok=True)
    (output / "evidence.json").write_text(json.dumps({"head": head, "cases": records}, indent=2) + "\n")
    print("PASS: three positive and twelve exact negative 59f source-scope controls", flush=True)


if __name__ == "__main__":
    main()
