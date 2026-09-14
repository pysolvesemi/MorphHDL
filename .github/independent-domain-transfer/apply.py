"""Apply only the checksum-pinned, locally exercised compiler repair.

One-shot transport for a network-isolated editing workspace. This is not an
alternative source generator and does not modify generated Verilog.
"""
import gzip
import hashlib
import json
import os
from pathlib import Path
import subprocess


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


branch = "agent/independent-parameter-domain-composition"
if os.environ.get("GITHUB_REPOSITORY") != "pysolvesemi/MorphHDL":
    raise SystemExit("unexpected repository")
if os.environ.get("GITHUB_REF") != "refs/heads/" + branch:
    raise SystemExit("unexpected branch")
source_head = git("rev-parse", "HEAD")
if source_head != os.environ["GITHUB_SHA"]:
    raise SystemExit("checkout differs from triggering head")
if git("status", "--porcelain"):
    raise SystemExit("checkout must be clean")
root = Path(".github/independent-domain-transfer")
manifest = json.loads((root / "manifest.json").read_text())
subprocess.run(["git", "merge-base", "--is-ancestor", manifest["source_anchor"], "HEAD"], check=True)
chunks = []
for entry in manifest["parts"]:
    data = Path(entry["path"]).read_bytes()
    digest = hashlib.sha1(b"blob " + str(len(data)).encode() + b"\0" + data).hexdigest()
    if digest != entry["sha"]:
        raise SystemExit("transfer blob checksum mismatch: " + entry["path"])
    chunks.append(data)
patch = gzip.decompress(b"".join(chunks))
if len(patch) != 157003 or hashlib.sha256(patch).hexdigest() != "7d062f21fa7c6d653ad914277bec7622aa7331c24459d51823bba5a3e0dea990":
    raise SystemExit("compiler patch checksum mismatch")
patch_path = Path(os.environ["RUNNER_TEMP"]) / "independent-parameter-compiler.patch"
patch_path.write_bytes(patch)
subprocess.run(["git", "apply", "--index", "--check", str(patch_path)], check=True)
subprocess.run(["git", "apply", "--index", str(patch_path)], check=True)
changed = set(git("diff", "--cached", "--name-only").splitlines())
if changed != set(manifest["changed_files"]):
    raise SystemExit("unexpected changed-file inventory")
subprocess.run(["git", "diff", "--cached", "--check"], check=True)
subprocess.run(["git", "config", "user.name", "github-actions[bot]"], check=True)
subprocess.run(["git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com"], check=True)
subprocess.run(["git", "commit", "-m", "fix: compose independent typed parameter domains through native publication", "-m", "Apply the exact locally exercised patch from source anchor 8746b936. Local evidence: native independent overrides and deterministic publication, correlation/provenance rejection controls, and inherited typed-domain/library tests. Clean final-head CI remains required before merge."], check=True)
evidence = Path("independent-parameter-transfer-evidence")
evidence.mkdir()
(evidence / "applied-head.txt").write_text(git("rev-parse", "HEAD") + "\n")
(evidence / "source-inventory.json").write_text(json.dumps({path: git("hash-object", path) for path in sorted(changed)}, indent=2) + "\n")
(evidence / "compiler.patch").write_bytes(patch)
with (evidence / "source-preservation.log").open("w") as log:
    subprocess.run(["python3", "morphhdl/scripts/check-native-source-preservation.py"], stdout=log, stderr=subprocess.STDOUT, check=True)
subprocess.run(["git", "bundle", "create", str(evidence / "repair.bundle"), "HEAD", "^" + manifest["source_anchor"]], check=True)
remote = git("ls-remote", "--exit-code", "origin", "refs/heads/" + branch).split()[0]
if remote != source_head:
    raise SystemExit("branch moved during import; refusing to overwrite concurrent work")
subprocess.run(["git", "push", "origin", "HEAD:refs/heads/" + branch], check=True)
print("Applied and pushed compiler source head " + git("rev-parse", "HEAD"))
