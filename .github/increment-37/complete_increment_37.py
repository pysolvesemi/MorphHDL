#!/usr/bin/env python3
from __future__ import annotations

import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

REPO = os.environ["GITHUB_REPOSITORY"]
TOKEN = os.environ["GH_TOKEN"]
OWNER = REPO.split("/", 1)[0]
BASE = "parameterized-verilog"
OLD_FEATURE = "agent/increment-37-parameterized-streamfifo-depth"
FEATURE = "agent/increment-37-final-2"
CONTROLLER = "agent/increment-37-controller-final-2"
DIAGNOSTICS = "agent/increment-37-final-diagnostics"
ROOT = Path.cwd()
TMP = Path("/tmp/increment-37-final-controller")
ALLOWED_FILES = {
    "core/src/main/scala/spinal/core/ParameterizedMemory.scala",
    "core/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala",
    "docs/morphhdl/increment-37-parameterized-streamfifo-depth.md",
    "docs/morphhdl/parameterized-verilog-todo.md",
    "lib/src/main/scala/spinal/lib/ParameterizedStreamFifoDepth.scala",
    "lib/src/main/scala/spinal/lib/Stream.scala",
    "morphhdl/src/test/scala/morphhdl/ParameterizedStreamFifoDepthTests.scala",
}


def run(*args: str, check: bool = True, capture: bool = False, cwd: Path | None = None) -> subprocess.CompletedProcess[str]:
    result = subprocess.run(
        list(args),
        cwd=str(cwd or ROOT),
        check=False,
        text=True,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
    )
    if check and result.returncode != 0:
        output = result.stdout or ""
        raise RuntimeError(f"command failed ({result.returncode}): {' '.join(args)}\n{output}")
    return result


def git(*args: str, check: bool = True, capture: bool = False) -> subprocess.CompletedProcess[str]:
    return run("git", *args, check=check, capture=capture)


def api(
    method: str,
    path: str,
    *,
    data: dict | None = None,
    allow: tuple[int, ...] = (),
) -> tuple[int, object | None]:
    url = f"https://api.github.com/repos/{REPO}{path}"
    body = None if data is None else json.dumps(data).encode("utf-8")
    request = urllib.request.Request(url, data=body, method=method)
    request.add_header("Authorization", f"Bearer {TOKEN}")
    request.add_header("Accept", "application/vnd.github+json")
    request.add_header("X-GitHub-Api-Version", "2022-11-28")
    if body is not None:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=60) as response:
            payload = response.read()
            decoded = json.loads(payload) if payload else None
            return response.status, decoded
    except urllib.error.HTTPError as error:
        payload = error.read()
        decoded: object | None
        try:
            decoded = json.loads(payload) if payload else None
        except json.JSONDecodeError:
            decoded = payload.decode("utf-8", errors="replace")
        if error.code in allow:
            return error.code, decoded
        raise RuntimeError(f"GitHub API {method} {path} failed: {error.code} {decoded}") from error


def branch_sha(branch: str) -> str | None:
    encoded = urllib.parse.quote(branch, safe="")
    status, payload = api("GET", f"/branches/{encoded}", allow=(404,))
    if status == 404:
        return None
    assert isinstance(payload, dict)
    return str(payload["commit"]["sha"])


def delete_branch(branch: str) -> None:
    encoded = urllib.parse.quote(branch, safe="")
    api("DELETE", f"/git/refs/heads/{encoded}", allow=(404, 422))


def pulls_for(branch: str, state: str = "all") -> list[dict]:
    query = urllib.parse.urlencode(
        {
            "state": state,
            "head": f"{OWNER}:{branch}",
            "base": BASE,
            "per_page": "100",
        }
    )
    _, payload = api("GET", f"/pulls?{query}")
    assert isinstance(payload, list)
    return [item for item in payload if isinstance(item, dict)]


def close_open_pulls(branch: str) -> None:
    for pull in pulls_for(branch, "open"):
        api("PATCH", f"/pulls/{pull['number']}", data={"state": "closed"})


def workflow_state(sha: str, event: str) -> dict[str, tuple[str, str | None]]:
    query = urllib.parse.urlencode(
        {"head_sha": sha, "event": event, "per_page": "100"}
    )
    _, payload = api("GET", f"/actions/runs?{query}")
    assert isinstance(payload, dict)
    result: dict[str, tuple[str, str | None]] = {}
    runs = payload.get("workflow_runs", [])
    if isinstance(runs, list):
        for item in sorted(
            (entry for entry in runs if isinstance(entry, dict)),
            key=lambda entry: str(entry.get("created_at", "")),
        ):
            name = str(item.get("name", ""))
            if name in ("MorphHDL Mill", "MorphHDL baseline"):
                result[name] = (
                    str(item.get("status", "missing")),
                    None if item.get("conclusion") is None else str(item.get("conclusion")),
                )
    return result


def wait_for_workflows(sha: str, event: str, attempts: int = 180) -> None:
    required = ("MorphHDL Mill", "MorphHDL baseline")
    for _ in range(attempts):
        states = workflow_state(sha, event)
        print(f"workflow state {event} {sha}: {states}", flush=True)
        failed = [
            name
            for name in required
            if name in states
            and states[name][0] == "completed"
            and states[name][1] != "success"
        ]
        if failed:
            raise RuntimeError(f"required workflows failed for {sha}: {failed}")
        if all(
            states.get(name) == ("completed", "success") for name in required
        ):
            return
        time.sleep(10)
    raise RuntimeError(f"timed out waiting for required {event} workflows at {sha}")


def base_is_complete() -> tuple[bool, str]:
    sha = branch_sha(BASE)
    if sha is None:
        raise RuntimeError(f"base branch {BASE} is missing")
    git("fetch", "origin", BASE)
    todo = git(
        "show",
        f"origin/{BASE}:docs/morphhdl/parameterized-verilog-todo.md",
        capture=True,
    ).stdout or ""
    checked = "- [x] **Increment 37 — Parameterized StreamFifo depth**" in todo
    next_unchecked = "- [ ] **Increment 38 — Migration and adapter retirement**" in todo
    names = git("ls-tree", "-r", "--name-only", f"origin/{BASE}", capture=True).stdout or ""
    clean = not any(
        name.startswith(".github/agent/")
        or name.startswith(".github/increment-37/")
        or name.startswith(".github/workflows/agent-increment-37-")
        for name in names.splitlines()
    )
    return checked and next_unchecked and clean, sha


def save_controller_scripts() -> None:
    TMP.mkdir(parents=True, exist_ok=True)
    for name in ("apply_increment_37.py", "build_increment_37_test.py"):
        source = ROOT / ".github/increment-37" / name
        if not source.is_file():
            raise RuntimeError(f"controller source script is missing: {source}")
        shutil.copy2(source, TMP / name)


def prepare_clean_source() -> None:
    git("fetch", "origin", BASE)
    git("checkout", "-B", FEATURE, f"origin/{BASE}")
    run(sys.executable, str(TMP / "apply_increment_37.py"))
    run(sys.executable, str(TMP / "build_increment_37_test.py"))
    git("diff", "--check")
    if not (ROOT / "docs/morphhdl/increment-37-parameterized-streamfifo-depth.md").is_file():
        raise RuntimeError("Increment 37 contract documentation was not generated")
    generated_test = (
        ROOT / "morphhdl/src/test/scala/morphhdl/ParameterizedStreamFifoDepthTests.scala"
    ).read_text()
    if "Vector(1, 3, 5, 8)" not in generated_test:
        raise RuntimeError("four-depth override proof was not generated")
    todo = (ROOT / "docs/morphhdl/parameterized-verilog-todo.md").read_text()
    if "- [ ] **Increment 37 — Parameterized StreamFifo depth**" not in todo:
        raise RuntimeError("Increment 37 must remain unchecked before validation")


def install_mill() -> Path:
    mill = Path("/tmp/morphhdl-mill")
    run(
        "curl",
        "--fail",
        "--location",
        "--retry",
        "3",
        "https://repo1.maven.org/maven2/com/lihaoyi/mill-dist/1.1.0/mill-dist-1.1.0-mill.sh",
        "--output",
        str(mill),
    )
    mill.chmod(0o755)
    return mill


def run_validation(mill: Path) -> None:
    logs = TMP / "logs"
    logs.mkdir(exist_ok=True)
    for scala in ("2.12.18", "2.13.12"):
        commands = [
            [
                str(mill),
                f"morph[{scala}].testOnly",
                "morphhdl.ParameterizedStreamFifoDepthTests",
                "morphhdl.NativeLibraryReuseTests",
                "morphhdl.NativeSymbolicMemoryTests",
            ],
            [
                str(mill),
                f"core[{scala}].testOnly",
                "spinal.core.internals.ParameterizedVerilogTests",
                "spinal.core.internals.ParameterizedDataShapeTests",
            ],
        ]
        with (logs / f"scala-{scala}.log").open("w") as stream:
            for command in commands:
                process = subprocess.Popen(
                    command,
                    cwd=str(ROOT),
                    text=True,
                    stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT,
                )
                assert process.stdout is not None
                for line in process.stdout:
                    sys.stdout.write(line)
                    stream.write(line)
                status = process.wait()
                if status != 0:
                    raise RuntimeError(
                        f"Increment 37 validation failed on Scala {scala}: {' '.join(command)}"
                    )


def publish_diagnostics(error: BaseException) -> None:
    try:
        (TMP / "failure.txt").write_text(str(error) + "\n")
        patch = git("diff", "--binary", capture=True, check=False).stdout or ""
        (TMP / "implementation.patch").write_text(patch)
        git("reset", "--hard", "HEAD", check=False)
        git("clean", "-fdx", check=False)
        git("checkout", "--orphan", DIAGNOSTICS, check=False)
        git("rm", "-rf", ".", check=False)
        target = ROOT / "diagnostics"
        target.mkdir(parents=True, exist_ok=True)
        for source in TMP.rglob("*"):
            if source.is_file():
                relative = source.relative_to(TMP)
                destination = target / relative
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, destination)
        git("add", "-A")
        git("config", "user.name", "github-actions[bot]")
        git(
            "config",
            "user.email",
            "41898282+github-actions[bot]@users.noreply.github.com",
        )
        git("commit", "-m", "Capture Increment 37 final validation diagnostics")
        git("push", "--force", "origin", f"HEAD:{DIAGNOSTICS}")
    except BaseException as diagnostic_error:
        print(f"failed to publish diagnostics: {diagnostic_error}", file=sys.stderr)


def finalize_and_push() -> str:
    todo_path = ROOT / "docs/morphhdl/parameterized-verilog-todo.md"
    todo = todo_path.read_text()
    old = "- [ ] **Increment 37 — Parameterized StreamFifo depth**"
    new = "- [x] **Increment 37 — Parameterized StreamFifo depth**"
    if old not in todo:
        raise RuntimeError("unchecked Increment 37 roadmap entry is missing")
    todo_path.write_text(todo.replace(old, new, 1))
    git("diff", "--check")
    git("config", "user.name", "github-actions[bot]")
    git(
        "config",
        "user.email",
        "41898282+github-actions[bot]@users.noreply.github.com",
    )
    git("add", *sorted(ALLOWED_FILES))
    status = git("status", "--short", capture=True).stdout or ""
    changed = {
        line[3:]
        for line in status.splitlines()
        if len(line) >= 4 and not line.startswith("?? .github/")
    }
    unexpected = changed - ALLOWED_FILES
    if unexpected:
        raise RuntimeError(f"unexpected Increment 37 source paths: {sorted(unexpected)}")
    git("commit", "-m", "Implement Increment 37 parameterized StreamFifo depth")
    sha = git("rev-parse", "HEAD", capture=True).stdout.strip()
    git("push", "--force", "origin", f"HEAD:{FEATURE}")
    return sha


def create_pr() -> dict:
    existing = pulls_for(FEATURE, "open")
    if existing:
        return existing[0]
    _, payload = api(
        "POST",
        "/pulls",
        data={
            "title": "Implement Increment 37 parameterized StreamFifo depth",
            "head": FEATURE,
            "base": BASE,
            "body": """## Summary

- retain one bounded public `DEPTH` parameter through the ordinary Spinal `StreamFifo` source path
- make the native memory, address and pointer widths, pointer terminal count, occupancy/availability widths, and capacity arithmetic depend on `DEPTH`
- preserve the existing StreamFifo handshake, read arbitration, collision policy, pointer-update, and occupancy-update algorithms
- compile the same strict Verilog-2001 definition at depths 1, 3, 5, and 8
- preserve the existing concrete `Int` API and concrete emission path
- document the supported default-option boundary and mark Increment 37 complete only after validation

## Validation

The source was validated on Scala 2.12.18 and 2.13.12 with the focused four-depth suite, Increment 36 native-library regressions, native symbolic-memory regressions, and parameterized core regressions. The repository's normal pull-request workflows remain the merge gate.
""",
        },
    )
    assert isinstance(payload, dict)
    return payload


def verify_pr_files(number: int) -> None:
    _, payload = api("GET", f"/pulls/{number}/files?per_page=100")
    assert isinstance(payload, list)
    files = {str(item["filename"]) for item in payload if isinstance(item, dict)}
    if files != ALLOWED_FILES:
        raise RuntimeError(
            f"PR {number} does not contain the exact Increment 37 source delta: {sorted(files)}"
        )


def merge_pr(number: int, head_sha: str) -> str:
    _, payload = api(
        "PUT",
        f"/pulls/{number}/merge",
        data={
            "merge_method": "squash",
            "sha": head_sha,
            "commit_title": "Increment 37: parameterized StreamFifo depth",
            "commit_message": "Retain one ordinary StreamFifo source path while bounded DEPTH controls storage, address width, pointers, occupancy and depth-dependent behavior. Prove depths 1, 3, 5 and 8 without regeneration.",
        },
    )
    assert isinstance(payload, dict)
    if payload.get("merged") is not True:
        raise RuntimeError(f"GitHub refused to merge PR {number}: {payload}")
    return str(payload["sha"])


def cleanup() -> None:
    for branch in (
        OLD_FEATURE,
        FEATURE,
        "agent/increment-37-transfer",
        "agent/increment-37-merge-guard",
        "agent/increment-37-merge-guard-v2",
        "agent/increment-37-controller-v1",
        CONTROLLER,
    ):
        try:
            delete_branch(branch)
        except BaseException as error:
            print(f"cleanup warning for {branch}: {error}", file=sys.stderr)


def main() -> None:
    complete, base_sha = base_is_complete()
    if complete:
        print(f"Increment 37 is already present on {BASE} at {base_sha}")
        wait_for_workflows(base_sha, "push")
        cleanup()
        return

    save_controller_scripts()
    close_open_pulls(OLD_FEATURE)
    close_open_pulls(FEATURE)
    delete_branch(FEATURE)

    try:
        prepare_clean_source()
        mill = install_mill()
        run_validation(mill)
        head_sha = finalize_and_push()
    except BaseException as error:
        publish_diagnostics(error)
        raise

    pull = create_pr()
    number = int(pull["number"])
    verify_pr_files(number)
    wait_for_workflows(head_sha, "pull_request")
    merge_sha = merge_pr(number, head_sha)
    wait_for_workflows(merge_sha, "push")

    complete, observed_base_sha = base_is_complete()
    if not complete:
        raise RuntimeError(
            f"Increment 37 merge {merge_sha} did not produce the required clean roadmap state"
        )
    git("fetch", "origin", BASE)
    ancestor = git(
        "merge-base",
        "--is-ancestor",
        merge_sha,
        f"origin/{BASE}",
        check=False,
    )
    if ancestor.returncode != 0:
        raise RuntimeError(
            f"merge {merge_sha} is not an ancestor of current {BASE} head {observed_base_sha}"
        )
    cleanup()
    print(f"Increment 37 completed and merged at {merge_sha}")


if __name__ == "__main__":
    main()
