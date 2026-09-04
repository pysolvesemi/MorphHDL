#!/usr/bin/env python3
"""Cancel obsolete Increment 59 bootstrap/finalizer runs before v3 preparation."""

from __future__ import annotations

import json
import os
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://api.github.com/repos/pysolvesemi/MorphHDL"
TARGET_NAMES = {
    "Increment 59 completion bootstrap v6",
    "Increment 59 parallel finalizer",
}
TARGET_BRANCHES = {
    "agent/inc59-final",
    "agent/inc59-finalizer-v2",
}


def request(method: str, path: str) -> dict:
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        raise RuntimeError("GitHub token is missing")
    req = urllib.request.Request(
        API + path,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "MorphHDL-Increment-59-Cancel",
        },
        method=method,
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as response:
            raw = response.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as error:
        if method == "POST" and error.code == 409:
            return {}
        raw = error.read().decode("utf-8", errors="replace")
        raise RuntimeError(f"{method} {path} failed with HTTP {error.code}: {raw}") from error


def main() -> int:
    current_run = int(os.environ.get("GITHUB_RUN_ID", "0") or 0)
    active: set[int] = set()
    for branch in sorted(TARGET_BRANCHES):
        query = urllib.parse.urlencode({"branch": branch, "per_page": 100})
        payload = request("GET", f"/actions/runs?{query}")
        for run in payload.get("workflow_runs", []):
            run_id = int(run["id"])
            if (
                run_id != current_run
                and run.get("name") in TARGET_NAMES
                and run.get("status") != "completed"
            ):
                active.add(run_id)

    for run_id in sorted(active):
        print(f"Cancelling obsolete Increment 59 run {run_id}", flush=True)
        request("POST", f"/actions/runs/{run_id}/cancel")

    deadline = time.time() + 900
    while active and time.time() < deadline:
        terminal = {
            run_id
            for run_id in active
            if request("GET", f"/actions/runs/{run_id}").get("status") == "completed"
        }
        active -= terminal
        if active:
            print(f"Waiting for obsolete runs to stop: {sorted(active)}", flush=True)
            time.sleep(15)
    if active:
        raise RuntimeError(f"obsolete runs did not stop: {sorted(active)}")
    print("No competing Increment 59 writer remains active", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
