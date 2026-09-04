#!/usr/bin/env python3
"""Record terminal Increment 59 v3 finalizer jobs and failed steps."""

from __future__ import annotations

import json
import os
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

API = "https://api.github.com/repos/pysolvesemi/MorphHDL"
WORKFLOW_NAME = "Increment 59 parallel finalizer v3"
BRANCH = "agent/inc59-finalizer-v3"


def get(path: str) -> dict:
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        raise RuntimeError("GitHub token is missing")
    request = urllib.request.Request(
        API + path,
        headers={
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "MorphHDL-Increment-59-Monitor-v3",
        },
    )
    with urllib.request.urlopen(request, timeout=120) as response:
        return json.load(response)


def latest_run() -> dict | None:
    query = urllib.parse.urlencode({"branch": BRANCH, "per_page": 100})
    payload = get(f"/actions/runs?{query}")
    runs = [
        run for run in payload.get("workflow_runs", []) if run.get("name") == WORKFLOW_NAME
    ]
    return max(runs, key=lambda value: value["id"]) if runs else None


def jobs(run_id: int) -> list[dict]:
    result: list[dict] = []
    page = 1
    while True:
        payload = get(f"/actions/runs/{run_id}/jobs?per_page=100&page={page}")
        batch = payload.get("jobs", [])
        result.extend(batch)
        if len(batch) < 100:
            return result
        page += 1


def main() -> int:
    deadline = time.time() + 6 * 60 * 60
    run = None
    while time.time() < deadline:
        run = latest_run()
        if run is not None:
            print(
                f"finalizer run {run['id']}: {run.get('status')} / {run.get('conclusion')}",
                flush=True,
            )
            if run.get("status") == "completed":
                break
        time.sleep(20)
    if run is None or run.get("status") != "completed":
        raise RuntimeError("Increment 59 v3 finalizer did not reach a terminal state")

    job_records = jobs(int(run["id"]))
    record = {
        "updated_at": datetime.now(timezone.utc).isoformat(),
        "run": {
            "id": run.get("id"),
            "status": run.get("status"),
            "conclusion": run.get("conclusion"),
            "head_sha": run.get("head_sha"),
            "url": run.get("html_url"),
        },
        "jobs": [
            {
                "id": job.get("id"),
                "name": job.get("name"),
                "status": job.get("status"),
                "conclusion": job.get("conclusion"),
                "url": job.get("html_url"),
                "failed_steps": [
                    {
                        "number": step.get("number"),
                        "name": step.get("name"),
                        "status": step.get("status"),
                        "conclusion": step.get("conclusion"),
                    }
                    for step in job.get("steps", [])
                    if step.get("conclusion") not in {None, "success", "skipped"}
                ],
            }
            for job in job_records
        ],
    }
    output = Path(os.environ.get("INC59_MONITOR_OUTPUT", "inc59-finalizer-v3-status.json"))
    output.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    for marker in output.parent.glob("INC59_FINALIZER_V3_*"):
        marker.unlink()
    suffix = "SUCCESS" if run.get("conclusion") == "success" else "FAILURE"
    (output.parent / f"INC59_FINALIZER_V3_{suffix}").write_text(
        record["updated_at"] + "\n", encoding="utf-8"
    )
    return 0 if suffix == "SUCCESS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
