#!/usr/bin/env python3
"""Transfer one already-reviewed 59i source/seal pair; never update a Git ref.

Every reconstructed tree, commit and catalogue must match the pre-reviewed
identity. The optional GitHub writes create only immutable Git database objects.
A separately authorized connector ref update must publish them after review.
"""
from __future__ import annotations
import argparse
import base64
import copy
from datetime import datetime, timezone
import difflib
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.request

REPOSITORY = "pysolvesemi/MorphHDL"
SOURCE = "2375c64c81f5c19d08ad706634cd3484537e77b3"
SEAL = "1541077ca85082409a6a870884d9ac8b377acdea"
SOURCE_TREE = "24579bbaceb73bd10d14a7d4400d3c6ac6680d04"
SEAL_TREE = "846c09a3909d43b4b5467b647b2d34352032b5d3"
BASE = "954d9b2763b064dba60af71ad8fa509a9d7cada8"
TARGET = "3b547ae5622ae212c17f6e67cc96924127a46a41"
COMMON = "f06d9c412924b99cf2c76422549c375a571757dc"
ARCHIVED_SOURCE = "bdfa5e06f0310cc9dcc64c42dc80a2f61c2475e9"
ARCHIVED_SEAL = "7fa6daa6f22db7b3d459130a5021b5cc905de49d"
PAYLOAD_SHA256 = "06f297e602197bc20a911ec98d1955fe8f3456cfc5384433cd6510ac73497122"
MANIFEST_SHA256 = "38cb687414c3e59494a75d3e6fb258a9e417c2db7078305695237227a429aa6d"
HELPER = "morphhdl/scripts/check-increment-59i-production-successor.py"
CONTRACT = "morphhdl/contracts/increment-59i-production-successor.json"


def require(ok, detail):
    if not ok:
        raise RuntimeError("reviewed 59i transport: " + detail)


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def git(root, *args, input=None):
    result = subprocess.run(["git", *args], cwd=root, input=input,
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180, check=False)
    require(result.returncode == 0, "git " + " ".join(args) + ": " + result.stderr.decode(errors="replace"))
    return result.stdout


def valid_path(path):
    return isinstance(path, str) and bool(path) and Path(path).as_posix() == path and not (
        Path(path).is_absolute() or ".." in Path(path).parts or ".git" in Path(path).parts or "\0" in path)


def tree(root, ref):
    result = {}
    for row in git(root, "ls-tree", "-r", "-z", ref).split(b"\0"):
        if row:
            head, path = row.split(b"\t")
            mode, kind, sha = head.decode().split()
            path = path.decode()
            require(valid_path(path) and path not in result, "invalid Git tree path")
            result[path] = (mode, kind, sha)
    return result


def blob(root, entries, path):
    entry = entries.get(path)
    if entry is None:
        return None
    require(entry[1] == "blob", "unexpected non-blob edit: " + path)
    return git(root, "cat-file", "blob", entry[2])


def write(root, path, raw, mode):
    require(valid_path(path), "unsafe destination path")
    file = root / path
    require(not any((root / Path(*Path(path).parts[:i])).is_symlink()
        for i in range(1, len(Path(path).parts) + 1)), "linked destination: " + path)
    if raw is None:
        file.unlink(missing_ok=True)
    else:
        require(mode in ("100644", "100755"), "unsupported source mode")
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(raw)
        file.chmod(0o755 if mode == "100755" else 0o644)


def review_spans(path, before, after, prefix):
    aa, bb = before.splitlines(keepends=True), after.splitlines(keepends=True)
    oa, ob = [0], [0]
    for v in aa:
        oa.append(oa[-1] + len(v))
    for v in bb:
        ob.append(ob[-1] + len(v))
    result = []
    for tag, i, j, k, l in difflib.SequenceMatcher(a=aa, b=bb, autojunk=False).get_opcodes():
        if tag == "equal":
            continue
        x, y, u, v = oa[i], oa[j], ob[k], ob[l]
        while x < y and u < v and before[x] == after[u]:
            x += 1
            u += 1
        while x < y and u < v and before[y-1] == after[v-1]:
            y -= 1
            v -= 1
        while x and x < len(before) and before[x] & 0xC0 == 0x80:
            x -= 1
            u -= 1
        while y < len(before) and before[y] & 0xC0 == 0x80:
            y += 1
            v += 1
        result.append({"id": prefix + ":" + path + ":" + str(len(result) + 1),
            "reason": "Exact reviewed " + prefix + " span for " + path,
            "before_start": x, "before_end": y, "after_start": u, "after_end": v,
            "before": before[x:y].decode(), "after": after[u:v].decode()})
    # A separate direct reversal must exactly reproduce both untouched gaps
    # and the reviewed baseline, not merely the expected number of spans.
    restored, i, j = [], 0, 0
    for e in result:
        a, b, c, d = (e[k] for k in ("before_start", "before_end", "after_start", "after_end"))
        require(before[i:a] == after[j:c], "unreviewed span gap: " + path)
        require(before[a:b] == e["before"].encode() and after[c:d] == e["after"].encode(), "bad span bytes")
        restored += [after[j:c], before[a:b]]
        i, j = b, d
    restored.append(after[j:])
    require(b"".join(restored) == before, "span reversal differs: " + path)
    return result


def reconstruct(repository, destination, payload):
    require(payload["source"] == SOURCE and payload["seal"] == SEAL and
        payload["base"] == ARCHIVED_SOURCE and payload["old_seal"] == ARCHIVED_SEAL and
        payload["manifest_sha256"] == MANIFEST_SHA256, "unexpected payload anchors")
    require(not destination.exists(), "destination must be absent")
    require(git(repository, "rev-parse", ARCHIVED_SOURCE + "^{tree}").decode().strip() == payload["base_tree"],
        "archived source tree differs")
    git(repository, "worktree", "add", "--detach", str(destination), ARCHIVED_SOURCE)
    r = destination
    references = {ref: tree(r, ref) for ref in (ARCHIVED_SOURCE, TARGET)}
    paths = [e["path"] for e in payload["files"]]
    require(paths == sorted(set(paths)), "unordered or duplicated transport inventory")
    for entry in payload["files"]:
        path, ref, desired = entry["path"], entry["ref"], entry["entry"]
        require(ref in references, "unreviewed source reference")
        data = blob(r, references[ref], path)
        if "edits" in entry:
            baseline, parts, end = data or b"", [], 0
            for start, stop, text in entry["edits"]:
                require(type(start) is int and type(stop) is int and end <= start <= stop <= len(baseline),
                    "invalid transport span")
                parts += [baseline[end:start], text.encode()]
                end = stop
            parts.append(baseline[end:])
            data = b"".join(parts)
        if desired is None:
            data = None
        else:
            require(desired[0] in ("100644", "100755") and desired[1] == "blob", "unsupported destination mode")
            require(data is not None and git(r, "hash-object", "--stdin", input=data).decode().strip() == desired[2],
                "transported source blob differs: " + path)
        write(r, path, data, None if desired is None else desired[0])
    git(r, "add", "--all")
    require(git(r, "write-tree").decode().strip() == SOURCE_TREE, "reconstructed source tree differs")
    raw = payload["source_raw"].encode()
    require(git(r, "hash-object", "-t", "commit", "-w", "--stdin", input=raw).decode().strip() == SOURCE,
        "raw source commit differs")
    git(r, "reset", "--hard", SOURCE)
    require(git(r, "rev-list", "--parents", "-n", "1", SOURCE).decode().split() == [SOURCE, BASE, TARGET],
        "ordered source parents differ")
    oldraw = git(r, "show", ARCHIVED_SEAL + ":" + CONTRACT)
    require(digest(oldraw) == payload["old_manifest_sha256"], "archived source review differs")
    old = {e["path"]: e for e in json.loads(oldraw)["files"]}
    source_tree = tree(r, SOURCE)
    before_trees = {ref: tree(r, ref) for ref in (BASE, TARGET)}
    reused = set(payload["manifest_reused_files"])

    def record(path, before_ref, prefix):
        before, after = blob(r, before_trees[before_ref], path), blob(r, source_tree, path)
        value = {"path": path, "before_mode": before_trees[before_ref].get(path, (None,))[0],
            "after_mode": source_tree.get(path, (None,))[0],
            "before_sha256": None if before is None else digest(before),
            "after_sha256": None if after is None else digest(after)}
        if prefix == "source" and path in reused:
            require(path in old and all(old[path][k] == v for k, v in value.items()), "changed reused source review")
            return copy.deepcopy(old[path])
        reasons = payload["manifest_source_reasons"] if prefix == "source" else payload["manifest_target_reasons"]
        require(path in reasons, "missing pre-reviewed file reason")
        value.update(reason=reasons[path], edits=review_spans(path, before or b"", after or b"", prefix))
        return value

    current_paths = git(r, "diff", "--name-only", BASE, SOURCE).decode().splitlines()
    require(set(current_paths) == reused | set(payload["manifest_source_reasons"]), "source review inventory differs")
    value = copy.deepcopy(payload["manifest_header"])
    value["files"] = [record(path, BASE, "source") for path in sorted(current_paths)]
    target_paths = git(r, "diff", "--name-only", COMMON, TARGET).decode().splitlines()
    require(set(target_paths) == set(payload["manifest_target_reasons"]), "target review inventory differs")
    value["target_integration"]["files"] = [record(path, TARGET, "target") for path in sorted(target_paths)]
    rawmanifest = (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()
    require(digest(rawmanifest) == MANIFEST_SHA256, "reconstructed catalogue differs from reviewed bytes")
    helper = (r / HELPER).read_bytes()
    slot = b'CONTRACT_SHA256 = "UNSEALED"\n'
    require(helper.count(slot) == 1, "source seal slot is ambiguous")
    write(r, CONTRACT, rawmanifest, "100644")
    write(r, HELPER, helper.replace(slot, b'CONTRACT_SHA256 = "' + MANIFEST_SHA256.encode() + b'"\n', 1), "100644")
    git(r, "add", "-f", "--", HELPER, CONTRACT)
    require(set(git(r, "diff", "--cached", "--name-only", SOURCE).decode().splitlines()) == {HELPER, CONTRACT},
        "seal changed more than its two authorized files")
    require(git(r, "write-tree").decode().strip() == SEAL_TREE, "reconstructed seal tree differs")
    require(git(r, "hash-object", "-t", "commit", "-w", "--stdin", input=payload["seal_raw"].encode()).decode().strip() == SEAL,
        "raw seal commit differs")
    git(r, "reset", "--hard", SEAL)
    require(not git(r, "status", "--porcelain", "--untracked-files=all"), "reconstruction is dirty")
    return r


def api(method, path, body=None):
    token = os.environ.get("GH_TOKEN")
    require(bool(token), "GitHub object staging needs the job's repository token")
    require(path.startswith("/git/"), "only Git database object endpoints are allowed")
    data = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request("https://api.github.com/repos/" + REPOSITORY + path,
        data=data, method=method, headers={"Authorization": "Bearer " + token,
        "Accept": "application/vnd.github+json", "Content-Type": "application/json",
        "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "MorphHDL-reviewed-source-transport"})
    # Never log the token, request headers or environment.
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        raise RuntimeError("GitHub object staging HTTP " + str(error.code) + ": " +
            error.read().decode(errors="replace")[:3000]) from None


def stage_tree(r, before, after, expected):
    old, new = tree(r, before), tree(r, after)
    changes = []
    for path in sorted(set(old) | set(new)):
        if old.get(path) == new.get(path):
            continue
        entry = new.get(path)
        if entry is None:
            changes.append({"path": path, "mode": old[path][0], "type": old[path][1], "sha": None})
        else:
            require(entry[1] == "blob", "unexpected Git database non-blob update")
            changes.append({"path": path, "mode": entry[0], "type": "blob",
                "content": blob(r, new, path).decode()})
    actual = api("POST", "/git/trees", {"base_tree": git(r, "rev-parse", before + "^{tree}").decode().strip(), "tree": changes})
    require(actual["sha"] == expected, "GitHub source tree differs from reviewed tree")
    return {"sha": actual["sha"], "changed_files": len(changes)}


def stage_commit(raw, expected):
    header, message = raw.split("\n\n", 1)
    value = {"message": message, "parents": []}
    for line in header.splitlines():
        key, rest = line.split(" ", 1)
        if key == "tree":
            value["tree"] = rest
        elif key == "parent":
            value["parents"].append(rest)
        else:
            require(key in ("author", "committer"), "unsupported raw commit header")
            m = re.fullmatch(r"(.*) <([^<>]+)> ([0-9]+) \+0000", rest)
            require(m is not None, "unsupported raw author metadata")
            value[key] = {"name": m[1], "email": m[2],
                "date": datetime.fromtimestamp(int(m[3]), timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")}
    actual = api("POST", "/git/commits", value)
    require(actual["sha"] == expected, "GitHub raw commit identity differs: " + actual["sha"])
    return {"sha": actual["sha"], "tree": actual["tree"]["sha"], "parents": [v["sha"] for v in actual["parents"]]}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--repository", type=Path, required=True)
    p.add_argument("--destination", type=Path, required=True)
    p.add_argument("--payload-dir", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.add_argument("--stage-github-objects", action="store_true")
    args = p.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    parts = sorted(args.payload_dir.glob("reviewed-source.part-*.b64"))
    require(len(parts) == 5, "expected all five reviewed payload parts")
    raw = base64.b64decode(b"".join(p.read_bytes() for p in parts), validate=True)
    require(digest(raw) == PAYLOAD_SHA256, "transport payload checksum differs")
    value = json.loads(gzip.decompress(raw))
    r = reconstruct(args.repository.resolve(), args.destination.resolve(), value)
    checks = ["check-increment-59i-production-successor.py", "check-native-source-preservation.py",
        "check-typed-native-source-overlay.py", "check-increment-59i-target-integration.py",
        "check-increment-62-wa08-source-overlay.py", "check-wa10-source-scope.py",
        "check-increment-59i-widening-source-review.py", "check-increment-59i-rollout-composition.py"]
    evidence = {"source": SOURCE, "source_tree": SOURCE_TREE, "seal": SEAL, "seal_tree": SEAL_TREE,
        "manifest_sha256": MANIFEST_SHA256, "checks": [], "refs_updated": False}
    for checker in checks:
        log = args.output / (checker + ".log")
        with log.open("wb") as stream:
            process = subprocess.run(["python3", "-B", "morphhdl/scripts/" + checker], cwd=r,
                env=dict(os.environ, PYTHONDONTWRITEBYTECODE="1"), stdout=stream, stderr=subprocess.STDOUT,
                timeout=900, check=False)
        evidence["checks"].append({"script": checker, "returncode": process.returncode, "log_sha256": digest(log.read_bytes())})
        (args.output / "stage-receipt.json").write_text(json.dumps(evidence, indent=2) + "\n")
        require(process.returncode == 0, "source preflight failed: " + checker)
    if args.stage_github_objects:
        require(os.environ.get("GITHUB_REPOSITORY") == REPOSITORY, "wrong authorized repository")
        evidence["github_objects"] = []
        evidence["github_objects"].append(stage_tree(r, ARCHIVED_SOURCE, SOURCE, SOURCE_TREE))
        evidence["github_objects"].append(stage_commit(value["source_raw"], SOURCE))
        evidence["github_objects"].append(stage_tree(r, SOURCE, SEAL, SEAL_TREE))
        evidence["github_objects"].append(stage_commit(value["seal_raw"], SEAL))
    require(not git(r, "status", "--porcelain", "--untracked-files=all"), "source changed during preflight")
    evidence["status"] = "exact-reviewed-objects-staged" if args.stage_github_objects else "offline-transport-verified"
    (args.output / "stage-receipt.json").write_text(json.dumps(evidence, indent=2) + "\n")
    print(json.dumps(evidence, indent=2))


if __name__ == "__main__":
    main()
