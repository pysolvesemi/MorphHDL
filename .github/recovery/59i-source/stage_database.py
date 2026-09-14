#!/usr/bin/env python3
"""Stage exact blobs and commits, leaving workflow-bearing trees to the connector.

Never update remote refs. Source reconstruction and all eight original checks
remain mandatory. A missing tree cannot be treated as successfully published.
"""
from __future__ import annotations
import argparse
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import time

STAGER_BLOB = "47fd5cea8f1e4baef225250b4455f349d8959b16"

def require(ok, message):
    if not ok:
        raise RuntimeError(message)

def load_stager(payload):
    path = payload / "stage_reviewed_source.py"
    require(path.is_file() and not path.is_symlink(), "Missing regular reviewed stager")
    raw = path.read_bytes()
    require(hashlib.sha1(b"blob " + str(len(raw)).encode() + b"\0" + raw).hexdigest() == STAGER_BLOB,
            "Original reviewed stager changed")
    spec = importlib.util.spec_from_file_location("reviewed_stager", path)
    require(spec is not None and spec.loader is not None, "Cannot load reviewed stager")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module

def request_for(module, root, before, after, expected, uploaded):
    old, new = module.tree(root, before), module.tree(root, after)
    changes = []
    for path in sorted(set(old) | set(new)):
        if old.get(path) == new.get(path):
            continue
        entry = new.get(path)
        if entry is None:
            changes.append({"path": path, "mode": old[path][0], "type": old[path][1], "sha": None})
            continue
        mode, kind, oid = entry
        require(kind == "blob" and mode in ("100644", "100755"), "Unsupported changed object")
        if oid not in uploaded:
            raw = module.blob(root, new, path)
            actual = module.api("POST", "/git/blobs", {
                "content": base64.b64encode(raw).decode(), "encoding": "base64"})
            require(actual["sha"] == oid, "Uploaded blob identity differs: " + path)
            uploaded.add(oid)
        changes.append({"path": path, "mode": mode, "type": kind, "sha": oid})
    return {"expected_tree": expected, "base_tree": module.git(root, "rev-parse", before + "^{tree}").decode().strip(),
            "tree": changes}

def wait_for_tree(module, tree, seconds=600):
    deadline = time.monotonic() + seconds
    while True:
        try:
            actual = module.api("GET", "/git/trees/" + tree)
        except RuntimeError as error:
            if not str(error).startswith("GitHub object staging HTTP 404:"):
                raise
            require(time.monotonic() < deadline, "Connector-created tree not available: " + tree)
            time.sleep(min(10, max(0, deadline - time.monotonic())))
        else:
            require(actual.get("sha") == tree and not actual.get("truncated", False),
                    "Wrong or truncated connector-created tree")
            return

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("prepare", "commits"))
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--payload-dir", type=Path, required=True)
    parser.add_argument("--destination", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    payload, root, out = args.payload_dir.resolve(), args.destination.resolve(), args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    m = load_stager(payload)
    require(os.environ.get("GITHUB_REPOSITORY") == m.REPOSITORY, "Wrong authorized repository")
    if args.phase == "prepare":
        require(not root.exists(), "Refusing to overwrite reconstructed source")
        command = [sys.executable, "-B", str(payload / "stage_reviewed_source.py"),
            "--repository", str(args.repository.resolve()), "--destination", str(root),
            "--payload-dir", str(payload), "--output", str(out)]
        subprocess.run(command, check=True, timeout=7200)
    receipt = json.loads((out / "stage-receipt.json").read_text())
    require(receipt["source"] == m.SOURCE and receipt["seal"] == m.SEAL and
            len(receipt["checks"]) == 8 and all(x["returncode"] == 0 for x in receipt["checks"]),
            "All eight original source gates are required")
    for check in receipt["checks"]:
        require(m.digest((out / (check["script"] + ".log")).read_bytes()) == check["log_sha256"],
                "Source-gate log differs")
    require(m.git(root, "rev-parse", "HEAD").decode().strip() == m.SEAL and
            not m.git(root, "status", "--porcelain", "--untracked-files=all"), "Changed source checkout")
    if args.phase == "prepare":
        uploaded = set()
        requests = {
            "source": request_for(m, root, m.ARCHIVED_SOURCE, m.SOURCE, m.SOURCE_TREE, uploaded),
            "seal": request_for(m, root, m.SOURCE, m.SEAL, m.SEAL_TREE, uploaded)}
        (out / "connector-tree-requests.json").write_text(json.dumps(requests, indent=2) + "\n")
        for label, commit in (("source", m.SOURCE), ("seal", m.SEAL)):
            (out / (label + "-commit.txt")).write_bytes(m.git(root, "cat-file", "commit", commit))
            m.git(root, "update-ref", "refs/heads/transport-59i-" + label, commit)
        bundle = out / "reviewed-source.bundle"
        m.git(root, "bundle", "create", str(bundle), "refs/heads/transport-59i-source",
              "refs/heads/transport-59i-seal", "^" + m.BASE, "^" + m.TARGET)
        (out / "bundle.sha256").write_text(m.digest(bundle.read_bytes()) + "  reviewed-source.bundle\n")
        (out / "payload-snapshot.tar").write_bytes(m.git(args.repository.resolve(), "archive", "--format=tar",
              "HEAD", ".github/recovery/59i-source"))
        print("Exact blobs prepared; connector tree creation and commit staging remain pending", flush=True)
    else:
        for expected in (m.SOURCE_TREE, m.SEAL_TREE):
            wait_for_tree(m, expected)
        staged = []
        for label, commit in (("source", m.SOURCE), ("seal", m.SEAL)):
            raw = (out / (label + "-commit.txt")).read_text()
            require(m.git(root, "hash-object", "-t", "commit", "--stdin", input=raw.encode()).decode().strip() == commit,
                    "Commit transport bytes changed")
            staged.append(m.stage_commit(raw, commit))
        (out / "commit-staging-receipt.json").write_text(json.dumps({"objects": staged, "refs_updated": False}, indent=2) + "\n")
        print("Exact source and seal objects staged; no remote ref updated", flush=True)

if __name__ == "__main__":
    main()
