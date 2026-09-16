#!/usr/bin/env python3
"""Reject changed CDC review inputs; keep the baseline compiler check strict."""
import importlib.util
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HELPER = "morphhdl/scripts/check-increment-62-wa08-source-overlay.py"
CONTRACT = "morphhdl/contracts/increment-62-wa08-source-overlay.json"
BASE = "7f355a859e7e88ca343e1ff82f261fb47b3311d0"
LAYERING_HELPER = "morphhdl/scripts/check-typed-layering-ir.py"
LAYERING_CONTRACT = "morphhdl/contracts/increment-54-typed-layering-ir.contract"
REVIEWED = (
    LAYERING_HELPER,
    LAYERING_CONTRACT,
    "repro/cdc-independent-parameters/test_layering_root.py",
    ".github/workflows/cdc-independent-parameter-consumers.yml",
    "repro/cdc-independent-parameters/.gitignore",
    "repro/cdc-independent-parameters/build.sbt",
    "repro/cdc-independent-parameters/check_baseline.py",
    "repro/cdc-independent-parameters/export_tools.py",
    "repro/cdc-independent-parameters/project/build.properties",
    "repro/cdc-independent-parameters/src/main/scala/CdcIndependentParametersRepro.scala",
    "repro/cdc-independent-parameters/test_source_review.py",
)
spec = importlib.util.spec_from_file_location("cdc_source_overlay", ROOT / HELPER)
overlay = importlib.util.module_from_spec(spec)
spec.loader.exec_module(overlay)

def baseline_unchanged(root):
    result = subprocess.run(["git", "diff", "--quiet", BASE, "--",
        "core", "morphruntime", "morphhdl", "frontend",
        ":(exclude)" + HELPER, ":(exclude)" + CONTRACT,
        ":(exclude)" + LAYERING_HELPER, ":(exclude)" + LAYERING_CONTRACT], cwd=root)
    if result.returncode not in (0, 1):
        raise RuntimeError("baseline Git comparison failed")
    return result.returncode == 0

def rejected(root, label):
    try:
        overlay.verify(root)
    except RuntimeError as error:
        if "WA-08 source overlay:" not in str(error):
            raise
    else:
        raise AssertionError("accepted unreviewed input: " + label)

def main():
    count = 0
    with tempfile.TemporaryDirectory(prefix="cdc-source-review-") as temporary:
        fixture = Path(temporary) / "repo"
        subprocess.run(["git", "worktree", "add", "--quiet", "--detach", str(fixture), "HEAD"], cwd=ROOT, check=True)
        try:
            value = overlay.verify(fixture)
            assert set(REVIEWED) <= {entry["path"] for entry in value["files"]}
            assert baseline_unchanged(fixture)
            for path in REVIEWED + (HELPER, CONTRACT):
                file = fixture / path
                original = file.read_bytes()
                try:
                    file.write_bytes(original + b"\n# unreviewed mutation\n")
                    rejected(fixture, path)
                    count += 1
                finally:
                    file.write_bytes(original)
            victim = fixture / "core/src/main/scala/spinal/core/Bits.scala"
            original = victim.read_bytes()
            try:
                victim.write_bytes(original + b"\n// compiler mutation\n")
                assert not baseline_unchanged(fixture), "compiler delta was excluded"
                rejected(fixture, str(victim))
                count += 1
            finally:
                victim.write_bytes(original)
            unknown = fixture / "repro/cdc-independent-parameters/src/main/scala/Unreviewed.scala"
            try:
                unknown.write_text("// new unreviewed source\n")
                rejected(fixture, str(unknown))
                count += 1
            finally:
                unknown.unlink()
            overlay.verify(fixture)
            assert baseline_unchanged(fixture)
        finally:
            subprocess.run(["git", "worktree", "remove", "--force", str(fixture)], cwd=ROOT, check=True)
    print("CDC_SOURCE_REVIEW_PASS rejected=" + str(count))

if __name__ == "__main__":
    main()
