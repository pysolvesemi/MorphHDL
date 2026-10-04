#!/usr/bin/env python3
"""Authenticate the current integration, then run a provenance-bound historical audit.

Historical source reviewers describe the pre-integration tree. They must still
pass on that exact tree; their receipts never qualify current RTL. The current
integration seal and native-source review remain separate mandatory gates.
Only the source-audit commands below can execute in the disposable checkout.
Explicitly listed, current authenticated control harnesses repair the exact
schema-22 rejection diagnostic against unchanged historical checkers and fixtures;
its separate source hash and current identity are retained in the receipt.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[2]
INTEGRATION_PARENT = "6bb250972f06ca55c9cfbd7100126adad9e81569"
# The integration parent contains a subsequent compiler repair. Replay the
# immutable certificate at its actual seal; current authentication covers the
# complete integration and every later repair, including that parent's delta.
PREDECESSOR = "9c88f5f75921e28aff329e3cff2d40132315e861"
GUARD = "morphhdl/scripts/check-parameterized-integration-source.py"
REVIEWERS = (
    "check-increment-59c-source-review.py",
    "check-increment-59g-source-review.py",
    "check-increment-59h-source-review.py",
    "check-increment-59i-source-review.py",
    "check-increment-61-source-review.py",
)
CONTROLS = (
    "test-increment-59i-review-composition.py",
    "test-increment-59i-capture-review.py",
    "test-increment-59d-inherited-60f-scope.py",
    "check-increment-59f-source-scope.py",
    "test-increment-59f-source-scope.py",
    "test-increment-59g-source-review.py",
    "test-increment-59b-inherited-source-scope.py",
    "test-increment-59h-inherited-source-scope.py",
    "test-increment-60f-inherited-source-scope.py",
    "test-wa07b-inherited-review.py",
    "test-increment-59i-regression-inventory.py",
)
# This current, integration-authenticated harness preserves every historical
# case while repairing schema 22's exact first-owning dirty-check diagnostic.
# Its checkers and all fixtures still come from the unchanged historical tree.
CURRENT_HARNESSES = frozenset({"test-increment-59h-inherited-source-scope.py",
    "test-increment-60f-inherited-source-scope.py"})
HARNESS_DRIVER = """import pathlib, sys, types
path = pathlib.Path(sys.argv[1])
module = types.ModuleType('reviewed_historical_control')
module.__file__ = str(path)
exec(compile(path.read_bytes(), str(path), 'exec'), module.__dict__)
module.ROOT = pathlib.Path(sys.argv[2])
if path.name == "test-increment-60f-inherited-source-scope.py":
    module.CHECKER = module.ROOT / "morphhdl/scripts/check-increment-60f-equivalence-closure.py"
module.main()
"""
AUDIT_TIMEOUT = 10800
COMMANDS = frozenset(
    [(name, *args) for name in REVIEWERS for args in ((), ("--self-test",))] +
    [(name,) for name in CONTROLS] +
    [("test-increment-59i-review-composition.py", "-v"),
     ("check-increment-59i-production-successor.py",),
     ("check-increment-60f-equivalence-closure.py", "--source-only")])


def require(ok, detail):
    if not ok:
        raise RuntimeError("Integrated inherited source audit: " + detail)


def git(root, *args):
    return subprocess.check_output(["git", "--literal-pathspecs", *args],
        cwd=root, stderr=subprocess.PIPE, timeout=120)


def command(arguments):
    require(bool(arguments), "missing audit command")
    path = Path(arguments[0])
    require(path.as_posix() == "morphhdl/scripts/" + path.name and
        (path.name, *arguments[1:]) in COMMANDS, "command is not an allowlisted source audit")
    return [sys.executable, "-B", *arguments]


def identity(root):
    return {"head": git(root, "rev-parse", "HEAD").decode().strip(),
        "tree": git(root, "rev-parse", "HEAD^{tree}").decode().strip()}


def authenticate(root):
    spec = importlib.util.spec_from_file_location("current_integration_review", root / GUARD)
    require(spec is not None and spec.loader is not None, "current source reviewer is missing")
    guard = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(guard)
    guard.verify(root)
    require(INTEGRATION_PARENT in guard.PARENTS, "historical audit has no authenticated integration parent")
    git(root, "merge-base", "--is-ancestor", PREDECESSOR, INTEGRATION_PARENT)
    git(root, "merge-base", "--is-ancestor", INTEGRATION_PARENT, "HEAD")
    return identity(root)


def unchanged(root, before):
    require(authenticate(root) == before, "candidate moved during historical audit")


def run(root, arguments):
    invocation = command(arguments)
    before = authenticate(root)
    output = root / "target/parameterized-inherited-audits"
    require(not output.is_symlink(), "linked evidence directory")
    output.mkdir(parents=True, exist_ok=True)
    evidence = Path(tempfile.mkdtemp(prefix=Path(arguments[0]).stem + "-", dir=output))
    record = {"current_source": before, "historical_source": PREDECESSOR,
        "command": invocation, "scope": "historical source controls plus current exact-tree authentication",
        "rtl_qualification": False, "started_ns": time.time_ns()}
    result = None
    try:
        with tempfile.TemporaryDirectory(prefix="morphhdl-integrated-audit-") as directory:
            historical = Path(directory) / "source"
            # Nested immutable auditors create/remove many worktrees. A linked
            # checkout shares their administrative namespace with concurrent
            # wrappers, racing Git's worktree allocation/removal. Give each
            # audit its own metadata while sharing only immutable Git objects.
            git(root, "clone", "--shared", "--no-checkout", str(root), str(historical))
            git(historical, "checkout", "--detach", PREDECESSOR)
            record["git_metadata_isolation"] = "independent clone; shared immutable objects"
            try:
                require(identity(historical)["head"] == PREDECESSOR, "historical checkout moved")
                frozen = git(root, "show", PREDECESSOR + ":" + arguments[0])
                require((historical / arguments[0]).read_bytes() == frozen, "historical reviewer differs")
                record["historical_tree"] = identity(historical)["tree"]
                record["reviewer_sha256"] = hashlib.sha256(frozen).hexdigest()
                if Path(arguments[0]).name in CURRENT_HARNESSES:
                    require(len(arguments) == 1, "repaired harness takes no unchecked arguments")
                    harness = root / arguments[0]
                    record["control_harness_source"] = before
                    record["control_harness_sha256"] = hashlib.sha256(harness.read_bytes()).hexdigest()
                    record["control_harness_path"] = arguments[0]
                    record["scope"] = "reviewed current control harness against immutable historical source; current exact-tree authentication remains mandatory"
                    invocation = [sys.executable, "-B", "-c", HARNESS_DRIVER, str(harness), str(historical)]
                    record["command"] = invocation
                env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1", GITHUB_WORKSPACE=str(historical))
                with (evidence / "audit.log").open("w") as log:
                    # The workflow and each unchanged historical caller retain
                    # their own timeouts. This outer bound cannot turn a timeout
                    # or a failed historical result into successful evidence.
                    result = subprocess.run(invocation, cwd=historical, env=env,
                        stdout=log, stderr=subprocess.STDOUT, timeout=AUDIT_TIMEOUT)
                record["exit"] = result.returncode
                print((evidence / "audit.log").read_text(), end="", flush=True)
                require(identity(historical)["head"] == PREDECESSOR, "historical audit moved its head")
                artifacts = historical / "target"
                if artifacts.exists():
                    shutil.copytree(artifacts, evidence / "historical-target", symlinks=True)
            finally:
                shutil.rmtree(historical)
        unchanged(root, before)
        require(result.returncode == 0, "historical audit failed; see " + str(evidence / "audit.log"))
        record["status"] = "pass"
    except Exception as error:
        record["status"] = "fail"
        record["error"] = str(error)
        raise
    finally:
        record["finished_ns"] = time.time_ns()
        (evidence / "receipt.json").write_text(json.dumps(record, indent=2) + "\n")
    print("INTEGRATED_INHERITED_SOURCE_PASS current=" + before["head"] +
        " historical=" + PREDECESSOR + " receipt=" + str(evidence / "receipt.json"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    run(ROOT, args.command)


if __name__ == "__main__":
    main()
