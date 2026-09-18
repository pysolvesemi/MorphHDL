#!/usr/bin/env python3
"""The CDC reproducer is scanned, not excluded, by the closed layering guard."""
import json
import subprocess
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
HELPER = "morphhdl/scripts/check-typed-layering-ir.py"
CONTRACT = "morphhdl/contracts/increment-54-typed-layering-ir.contract"
SOURCE = "repro/cdc-independent-parameters/src/main"

def check(root, expected=None):
    result = subprocess.run(["python3", str(root / HELPER)], cwd=root,
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    if expected is None:
        assert result.returncode == 0, result.stdout
    else:
        assert result.returncode != 0 and expected in result.stdout, result.stdout

def main():
    manifest = json.loads((ROOT / CONTRACT).read_text())
    assert SOURCE in manifest["production_source_roots"]
    broad = [rule for rule in manifest["forbidden_source_rules"]
             if "repro/independent-parameters/src/main/" in rule["path_prefixes"]]
    assert broad and all(SOURCE + "/" in rule["path_prefixes"] for rule in broad)
    with tempfile.TemporaryDirectory(prefix="cdc-layering-root-") as temporary:
        fixture = Path(temporary) / "repo"
        subprocess.run(["git", "worktree", "add", "--quiet", "--detach", str(fixture), "HEAD"], cwd=ROOT, check=True)
        try:
            check(fixture)
            bad = fixture / SOURCE / "scala/LayeringRejected.scala"
            try:
                bad.write_text("object Forbidden { val registry = ExternalParameterizedMemoryRegistry }\n")
                check(fixture, "obsolete-parameterized-sidecar-symbol")
            finally:
                bad.unlink(missing_ok=True)
            try:
                bad.write_text("package spinal.core\nfinal class ElabInt\n")
                check(fixture, "elab-int-owner")
            finally:
                bad.unlink(missing_ok=True)
            unknown = fixture / "repro/unreviewed-cdc-root/src/main/scala/Unknown.scala"
            try:
                unknown.parent.mkdir(parents=True)
                unknown.write_text("object Unknown {}\n")
                check(fixture, "unscanned production roots: repro/unreviewed-cdc-root/src/main")
            finally:
                unknown.unlink(missing_ok=True)
                for directory in [unknown.parent, unknown.parent.parent, unknown.parent.parent.parent, unknown.parent.parent.parent.parent]:
                    directory.rmdir()
            check(fixture)
        finally:
            subprocess.run(["git", "worktree", "remove", "--force", str(fixture)], cwd=ROOT, check=True)
    print("CDC_LAYERING_ROOT_PASS rejected=3")

if __name__ == "__main__":
    main()
