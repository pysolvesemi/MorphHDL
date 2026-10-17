#!/usr/bin/env python3
"""Require complete, same-head receipts for all inherited-audit matrix shards.

This records command completion, never substitutes for any historical source
check and never dispatches CI. Missing, cancelled, failed or stale shards fail
closed. Earlier successful jobs from the SAME run/head may be used on a failed-
job retry; a later incomplete attempt always supersedes an older success.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

ROOT = Path(__file__).resolve().parents[2]
BASELINE = "969af59a0378fca5b964d04d8f2af49b123dd804"
WORKFLOW = ".github/workflows/increment-59i-inherited-source-qualification.yml"
PREFIX = "increment-59i-inherited-source-qualification-shard-"
COMMANDS = ('python3 morphhdl/scripts/check-increment-59i-target-integration.py',
 'python3 morphhdl/scripts/test-increment-59i-target-integration.py',
 'python3 morphhdl/scripts/check-increment-62-wa08-source-overlay.py',
 'python3 morphhdl/scripts/check-wa10-source-scope.py',
 'python3 morphhdl/scripts/check-increment-59i-rollout-composition.py --self-test',
 'python3 morphhdl/scripts/test-increment-59i-rollout-composition.py',
 'python3 morphhdl/scripts/check-increment-59i-widening-source-review.py',
 'python3 morphhdl/scripts/test-increment-59i-widening-source-review.py',
 'python3 morphhdl/scripts/test-increment-59i-widening-successor.py',
 'python3 morphhdl/scripts/check-increment-59i-source-review.py',
 'python3 morphhdl/scripts/test-increment-59i-review-composition.py',
 'python3 morphhdl/scripts/check-increment-60g-source-scope.py',
 'python3 morphhdl/scripts/check-increment-60g-source-scope.py --self-test',
 'python3 morphhdl/scripts/check-increment-60g-default-rollout.py self-test',
 'python3 morphhdl/scripts/check-increment-59g-source-review.py',
 'python3 morphhdl/scripts/check-increment-59h-source-review.py',
 'python3 morphhdl/scripts/test-increment-59h-inherited-source-scope.py',
 'python3 morphhdl/scripts/check-wa07b-inherited-review.py',
 'python3 morphhdl/scripts/test-wa07b-inherited-review.py',
 'python3 morphhdl/scripts/test-increment-60f-source-budget.py',
 'python3 morphhdl/scripts/test-increment-59i-inherited-audit-budgets.py',
 'python3 morphhdl/scripts/check-increment-60f-equivalence-closure.py --source-only',
 'python3 morphhdl/scripts/test-increment-60f-integration-reader.py -v',
 'python3 morphhdl/scripts/test-increment-60f-inherited-source-scope.py',
 'python3 morphhdl/scripts/test-increment-59f-source-scope.py',
 'python3 morphhdl/scripts/test-increment-59c-inherited-source-scope.py',
 'python3 morphhdl/scripts/check-increment-60f-artifacts.py self-test')
SHARDS = {'composition': (0, 14), '59h': (14, 17), 'wa07b': (17, 19), '60f': (19, 24), '59f-59c': (24, 27)}


def require(ok: bool, message: str) -> None:
    if not ok:
        raise RuntimeError("59i audit shards: " + message)


def sha(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def regular(path: Path) -> bytes:
    require(not path.is_symlink() and path.is_file(), "missing or linked file: " + str(path))
    return path.read_bytes()


def strict_json(raw: bytes) -> dict:
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "duplicate JSON key")
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=unique)


def git(root: Path, *args: str) -> str:
    return subprocess.check_output(["git", "--literal-pathspecs", *args], cwd=root,
        text=True, stderr=subprocess.PIPE, timeout=120).strip()


def identity(root: Path, expected: str) -> tuple[str, str]:
    require(re.fullmatch(r"[0-9a-f]{40}", expected) is not None, "invalid expected commit")
    head = git(root, "rev-parse", "HEAD")
    require(head == expected, "checkout HEAD differs")
    require(not git(root, "status", "--porcelain", "--untracked-files=all"), "changed checkout")
    return head, git(root, "rev-parse", "HEAD^{tree}")


def markers(shard: str, raw: bytes) -> list[dict]:
    require(shard in SHARDS, "unknown shard")
    events = []
    for line in raw.decode("utf-8", errors="replace").splitlines():
        if not line.startswith("59I_AUDIT_"):
            continue
        match = re.fullmatch(r"59I_AUDIT_(BEGIN|END) ([0-9]+) ([0-9a-f]{64}) ([0-9]+)", line)
        require(match is not None, "malformed command marker")
        events.append((match[1], int(match[2]), match[3], int(match[4])))
    start, stop = SHARDS[shard]
    expected = [(kind, i, sha(COMMANDS[i].encode()))
                for i in range(start, stop) for kind in ("BEGIN", "END")]
    require([e[:3] for e in events] == expected, "missing, duplicate or reordered command completion")
    require(all(a[3] <= b[3] for a, b in zip(events, events[1:])), "command timestamps reversed")
    return [dict(index=i, command=COMMANDS[i], returncode=0,
                 started=events[2*j][3], completed=events[2*j+1][3])
            for j, i in enumerate(range(start, stop))]


def record(root: Path, directory: Path, expected: str, shard: str,
           run_id: str, attempt: int) -> dict:
    require(not directory.is_symlink() and directory.is_dir(), "invalid evidence directory")
    require(run_id.isdecimal() and attempt >= 1, "invalid workflow identity")
    require(not (directory / "receipt.json").exists(), "refusing to overwrite a receipt")
    head, tree = identity(root, expected)
    require(regular(directory / "head.txt").decode().strip() == head, "preflight HEAD differs")
    require(regular(directory / "tree.txt").decode().strip() == tree, "preflight tree differs")
    log = regular(directory / "source-audits.log")
    commands = markers(shard, log)
    value = dict(schema=1, shard=shard, head=head, tree=tree, run_id=run_id, attempt=attempt,
                 commands=commands, log_sha256=sha(log), workflow_sha256=sha(regular(root / WORKFLOW)))
    # Creation is atomic; a cancelled write cannot leave an apparently complete receipt.
    temporary = directory / "receipt.tmp"
    with temporary.open("x") as stream:
        stream.write(json.dumps(value, indent=2, sort_keys=True) + "\n")
    temporary.replace(directory / "receipt.json")
    return value


def aggregate(root: Path, directory: Path, expected: str,
              run_id: str, attempt: int) -> dict:
    head, tree = identity(root, expected)
    require(run_id.isdecimal() and attempt >= 1, "invalid workflow identity")
    require(directory.is_dir() and not directory.is_symlink(), "missing shard artifacts")
    selected = {}
    for path in directory.iterdir():
        require(path.is_dir() and not path.is_symlink(), "invalid artifact directory")
        match = re.fullmatch(re.escape(PREFIX) + r"(.+)-attempt-([1-9][0-9]*)", path.name)
        require(match is not None and match[1] in SHARDS, "unexpected shard artifact")
        name, number = match[1], int(match[2])
        require(number <= attempt, "artifact from a future attempt")
        if name not in selected or number > selected[name][0]:
            selected[name] = (number, path)
    require(set(selected) == set(SHARDS), "missing shard artifacts")
    checked = []
    for name in SHARDS:
        number, path = selected[name]
        value = strict_json(regular(path / "receipt.json"))
        require(set(value) == {"schema", "shard", "head", "tree", "run_id", "attempt",
                             "commands", "log_sha256", "workflow_sha256"}, "invalid receipt schema")
        require(type(value["schema"]) is int and value["schema"] == 1 and
                type(value["attempt"]) is int and value["attempt"] == number and
                value["shard"] == name and value["run_id"] == run_id, "wrong shard/run identity")
        require(value["head"] == head and value["tree"] == tree, "mixed-head source evidence")
        require(regular(path / "head.txt").decode().strip() == head and
                regular(path / "tree.txt").decode().strip() == tree, "preflight identity differs")
        require(value["workflow_sha256"] == sha(regular(root / WORKFLOW)), "workflow identity differs")
        log = regular(path / "source-audits.log")
        require(value["log_sha256"] == sha(log), "changed command log")
        require(value["commands"] == markers(name, log), "command receipt differs")
        require(all(type(c["returncode"]) is int and c["returncode"] == 0 for c in value["commands"]),
                "nonzero command result")
        checked.append(value)
    require([c["command"] for v in checked for c in v["commands"]] == list(COMMANDS),
            "complete original audit inventory differs")
    return dict(head=head, tree=tree, run_id=run_id, attempt=attempt,
                status="all-shards-passed", original_commands=len(COMMANDS), shards=checked)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("phase", choices=("record", "aggregate"))
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--shard", choices=tuple(SHARDS))
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    run_id = os.environ["GITHUB_RUN_ID"]
    attempt = int(os.environ["GITHUB_RUN_ATTEMPT"])
    if args.phase == "record":
        require(args.shard is not None, "shard is required")
        result = record(ROOT, args.directory, args.head, args.shard, run_id, attempt)
    else:
        require(args.output is not None, "aggregate output is required")
        result = aggregate(ROOT, args.directory, args.head, run_id, attempt)
        with args.output.open("x") as stream:
            stream.write(json.dumps(result, indent=2, sort_keys=True) + "\n")
    print("59I_AUDIT_SHARDS_" + args.phase.upper() + "_PASS")


if __name__ == "__main__":
    main()
