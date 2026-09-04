#!/usr/bin/env python3
"""Cancel obsolete bootstrap runs, then open, gate, and merge Increment 59."""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any, Mapping, Optional

OWNER = "pysolvesemi"
REPOSITORY = "MorphHDL"
API_ROOT = f"https://api.github.com/repos/{OWNER}/{REPOSITORY}"
TARGET_BRANCH = "agent/inc59-final"
BASE_BRANCH = "parameterized-verilog"
FINAL_WORKFLOW = "Increment 59 typed BlackBox generic gates"
BAD_CONCLUSIONS = {
    "failure",
    "cancelled",
    "timed_out",
    "action_required",
    "startup_failure",
    "stale",
}


class GitHubFailure(RuntimeError):
    pass


def token() -> str:
    value = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not value:
        raise GitHubFailure("GH_TOKEN/GITHUB_TOKEN is required")
    return value


def request(
    method: str,
    path: str,
    body: Optional[Mapping[str, Any]] = None,
    *,
    expected: tuple[int, ...] = (200, 201, 202, 204),
) -> Any:
    data = None if body is None else json.dumps(body).encode("utf-8")
    headers = {
        "Authorization": f"Bearer {token()}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "Content-Type": "application/json",
        "User-Agent": "MorphHDL-Increment-59-Finalizer",
    }
    last_error: Optional[BaseException] = None
    for attempt in range(8):
        try:
            req = urllib.request.Request(
                API_ROOT + path,
                data=data,
                headers=headers,
                method=method,
            )
            with urllib.request.urlopen(req, timeout=120) as response:
                raw = response.read()
                if response.status not in expected:
                    raise GitHubFailure(
                        f"{method} {path} returned {response.status}, expected {expected}"
                    )
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as error:
            raw = error.read().decode("utf-8", errors="replace")
            retryable = error.code in {403, 429, 500, 502, 503, 504}
            if not retryable or attempt == 7:
                raise GitHubFailure(
                    f"{method} {path} failed with HTTP {error.code}: {raw}"
                ) from error
            last_error = error
        except (urllib.error.URLError, TimeoutError) as error:
            if attempt == 7:
                raise GitHubFailure(f"{method} {path} failed: {error}") from error
            last_error = error
        time.sleep(min(60, 2 ** attempt))
    raise GitHubFailure(f"{method} {path} failed after retries: {last_error}")


def paged(path: str, key: str) -> list[Mapping[str, Any]]:
    values: list[Mapping[str, Any]] = []
    separator = "&" if "?" in path else "?"
    page = 1
    while True:
        payload = request("GET", f"{path}{separator}per_page=100&page={page}")
        batch = payload.get(key, []) if isinstance(payload, dict) else payload
        values.extend(batch)
        if len(batch) < 100:
            return values
        page += 1


def branch_sha(branch: str) -> str:
    encoded = urllib.parse.quote(branch, safe="")
    payload = request("GET", f"/branches/{encoded}")
    return str(payload["commit"]["sha"])


def cancel_bootstrap() -> int:
    query = urllib.parse.urlencode({"branch": TARGET_BRANCH})
    runs = paged(f"/actions/runs?{query}", "workflow_runs")
    candidates = [
        run
        for run in runs
        if run.get("status") != "completed"
        and "Increment 59" in str(run.get("name", ""))
        and "bootstrap" in str(run.get("name", "")).lower()
    ]
    for run in candidates:
        run_id = int(run["id"])
        print(f"Cancelling obsolete Increment 59 bootstrap run {run_id}", flush=True)
        try:
            request(
                "POST",
                f"/actions/runs/{run_id}/cancel",
                expected=(202, 409),
            )
        except GitHubFailure as error:
            if "HTTP 409" not in str(error):
                raise
    if not candidates:
        print("No active Increment 59 bootstrap run required cancellation", flush=True)
        return 0

    deadline = time.time() + 900
    candidate_ids = {int(run["id"]) for run in candidates}
    while time.time() < deadline:
        active = []
        for run_id in candidate_ids:
            run = request("GET", f"/actions/runs/{run_id}")
            if run.get("status") != "completed":
                active.append(run_id)
        if not active:
            print("All obsolete bootstrap runs are terminal", flush=True)
            return 0
        print(f"Waiting for cancelled bootstrap runs: {active}", flush=True)
        time.sleep(15)
    raise GitHubFailure("obsolete Increment 59 bootstrap runs did not stop")


def find_or_create_pr(target_sha: str) -> Mapping[str, Any]:
    query = urllib.parse.urlencode(
        {
            "state": "open",
            "head": f"{OWNER}:{TARGET_BRANCH}",
            "base": BASE_BRANCH,
        }
    )
    pulls = request("GET", f"/pulls?{query}")
    if pulls:
        pull = pulls[0]
        if pull.get("head", {}).get("sha") != target_sha:
            raise GitHubFailure(
                f"existing PR #{pull['number']} does not point at expected head {target_sha}"
            )
        return pull
    return request(
        "POST",
        "/pulls",
        {
            "title": "Increment 59 — Typed BlackBox parameter and generic binding",
            "head": TARGET_BRANCH,
            "base": BASE_BRANCH,
            "body": (
                "Implements exact typed ElabInt/ElabBool BlackBox generic binding, "
                "symbolic parent propagation, concrete Verilog/VHDL parity, strict "
                "Verilog-2001 validation, formal equivalence, mutation controls, "
                "native-source audit updates, and dual-Scala closure."
            ),
            "maintainer_can_modify": True,
        },
    )


def workflow_runs(target_sha: str) -> list[Mapping[str, Any]]:
    query = urllib.parse.urlencode({"branch": TARGET_BRANCH})
    return [
        run
        for run in paged(f"/actions/runs?{query}", "workflow_runs")
        if run.get("head_sha") == target_sha
    ]


def check_runs(target_sha: str) -> list[Mapping[str, Any]]:
    return paged(f"/commits/{target_sha}/check-runs?", "check_runs")


def combined_status(target_sha: str) -> Mapping[str, Any]:
    return request("GET", f"/commits/{target_sha}/status")


def write_status(value: Mapping[str, Any]) -> None:
    path = Path(os.environ.get("INC59_STATUS_FILE", "/tmp/inc59-finalizer-status.json"))
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def wait_and_merge(target_sha: str, base_sha: str, pull: Mapping[str, Any]) -> Mapping[str, Any]:
    number = int(pull["number"])
    print(f"Increment 59 PR #{number}: {pull['html_url']}", flush=True)
    deadline = time.time() + 5.5 * 60 * 60
    stable_since: Optional[float] = None

    while time.time() < deadline:
        current_target = branch_sha(TARGET_BRANCH)
        current_base = branch_sha(BASE_BRANCH)
        if current_target != target_sha:
            raise GitHubFailure(
                f"target branch moved during verification: {target_sha} -> {current_target}"
            )
        if current_base != base_sha:
            raise GitHubFailure(
                f"base branch moved during verification: {base_sha} -> {current_base}; rerun on the new base"
            )

        pull_state = request("GET", f"/pulls/{number}")
        if pull_state.get("merged"):
            return {
                "pr": number,
                "url": pull_state["html_url"],
                "head_sha": target_sha,
                "merge_sha": pull_state.get("merge_commit_sha"),
                "already_merged": True,
            }
        if pull_state.get("state") != "open":
            raise GitHubFailure(f"PR #{number} closed without merge")

        runs = workflow_runs(target_sha)
        final_runs = [run for run in runs if run.get("name") == FINAL_WORKFLOW]
        final_success = any(
            run.get("status") == "completed" and run.get("conclusion") == "success"
            for run in final_runs
        )
        bad_workflows = [
            run
            for run in runs
            if run.get("status") == "completed"
            and run.get("conclusion") in BAD_CONCLUSIONS
        ]
        checks = check_runs(target_sha)
        pending_checks = [run for run in checks if run.get("status") != "completed"]
        bad_checks = [
            run for run in checks if run.get("conclusion") in BAD_CONCLUSIONS
        ]
        status = combined_status(target_sha)
        bad_statuses = [
            item
            for item in status.get("statuses", [])
            if item.get("state") in {"error", "failure"}
        ]
        pending_statuses = [
            item for item in status.get("statuses", []) if item.get("state") == "pending"
        ]

        snapshot = {
            "state": "waiting",
            "pr": number,
            "url": pull_state["html_url"],
            "head_sha": target_sha,
            "base_sha": base_sha,
            "mergeable": pull_state.get("mergeable"),
            "mergeable_state": pull_state.get("mergeable_state"),
            "workflow_runs": [
                {
                    "id": run.get("id"),
                    "name": run.get("name"),
                    "status": run.get("status"),
                    "conclusion": run.get("conclusion"),
                    "url": run.get("html_url"),
                }
                for run in runs
            ],
            "check_runs": [
                {
                    "id": run.get("id"),
                    "name": run.get("name"),
                    "status": run.get("status"),
                    "conclusion": run.get("conclusion"),
                    "url": run.get("html_url"),
                }
                for run in checks
            ],
            "combined_status": status.get("state"),
        }
        write_status(snapshot)

        if bad_workflows or bad_checks or bad_statuses:
            failed = {
                "workflows": [
                    (run.get("name"), run.get("conclusion"), run.get("html_url"))
                    for run in bad_workflows
                ],
                "checks": [
                    (run.get("name"), run.get("conclusion"), run.get("html_url"))
                    for run in bad_checks
                ],
                "statuses": [
                    (item.get("context"), item.get("state"), item.get("target_url"))
                    for item in bad_statuses
                ],
            }
            snapshot = dict(snapshot)
            snapshot["state"] = "failed"
            snapshot["failures"] = failed
            write_status(snapshot)
            raise GitHubFailure(f"Increment 59 CI failed: {failed}")

        all_complete = (
            final_success
            and not pending_checks
            and not pending_statuses
            and status.get("state") in {"success", None}
            and bool(checks)
        )
        if all_complete:
            if stable_since is None:
                stable_since = time.time()
            elif time.time() - stable_since >= 90:
                try:
                    merged = request(
                        "PUT",
                        f"/pulls/{number}/merge",
                        {
                            "sha": target_sha,
                            "merge_method": "merge",
                            "commit_title": (
                                "Merge Increment 59 typed BlackBox generic binding"
                            ),
                        },
                    )
                except GitHubFailure as error:
                    if "HTTP 405" in str(error) or "HTTP 409" in str(error):
                        print(f"Merge not ready yet: {error}", flush=True)
                        stable_since = None
                        time.sleep(30)
                        continue
                    raise
                if not merged.get("merged"):
                    raise GitHubFailure(
                        f"PR #{number} merge endpoint did not merge: {merged}"
                    )
                result = {
                    "state": "merged",
                    "pr": number,
                    "url": pull_state["html_url"],
                    "head_sha": target_sha,
                    "base_sha": base_sha,
                    "merge_sha": merged.get("sha"),
                    "already_merged": False,
                }
                write_status(result)
                return result
        else:
            stable_since = None

        print(
            "Waiting for Increment 59 gates: "
            f"final_success={final_success}, workflows={len(runs)}, "
            f"checks={len(checks)}, pending_checks={len(pending_checks)}, "
            f"combined_status={status.get('state')}, "
            f"mergeable_state={pull_state.get('mergeable_state')}",
            flush=True,
        )
        time.sleep(30)

    raise GitHubFailure("Increment 59 PR gates did not finish before finalizer timeout")


def open_gate_merge() -> int:
    target_sha = os.environ.get("INC59_TARGET_SHA") or branch_sha(TARGET_BRANCH)
    base_sha = os.environ.get("INC59_BASE_SHA") or branch_sha(BASE_BRANCH)
    if branch_sha(TARGET_BRANCH) != target_sha:
        raise GitHubFailure("target SHA changed before PR creation")
    if branch_sha(BASE_BRANCH) != base_sha:
        raise GitHubFailure("base SHA changed before PR creation")
    pull = find_or_create_pr(target_sha)
    result = wait_and_merge(target_sha, base_sha, pull)
    print(json.dumps(result, indent=2), flush=True)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("cancel-bootstrap", "open-gate-merge"))
    arguments = parser.parse_args()
    try:
        if arguments.command == "cancel-bootstrap":
            return cancel_bootstrap()
        return open_gate_merge()
    except GitHubFailure as error:
        write_status({"state": "failed", "detail": str(error)})
        print(f"Increment 59 finalizer failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
