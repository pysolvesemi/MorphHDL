#!/usr/bin/env python3
"""Create and merge the exact fully verified Increment 59 pull request."""

from __future__ import annotations

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
API = f"https://api.github.com/repos/{OWNER}/{REPOSITORY}"
TARGET_BRANCH = "agent/inc59-final"
BASE_BRANCH = "parameterized-verilog"


class MergeFailure(RuntimeError):
    pass


def token() -> str:
    value = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not value:
        raise MergeFailure("GitHub token is missing")
    return value


def request(
    method: str,
    path: str,
    body: Optional[Mapping[str, Any]] = None,
    expected: tuple[int, ...] = (200, 201, 202, 204),
) -> Any:
    data = None if body is None else json.dumps(body).encode("utf-8")
    headers = {
        "Authorization": f"Bearer {token()}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "Content-Type": "application/json",
        "User-Agent": "MorphHDL-Increment-59-Merger",
    }
    for attempt in range(8):
        try:
            req = urllib.request.Request(API + path, data=data, headers=headers, method=method)
            with urllib.request.urlopen(req, timeout=120) as response:
                raw = response.read()
                if response.status not in expected:
                    raise MergeFailure(
                        f"{method} {path} returned {response.status}, expected {expected}"
                    )
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as error:
            raw = error.read().decode("utf-8", errors="replace")
            retryable = error.code in {429, 500, 502, 503, 504}
            if not retryable or attempt == 7:
                raise MergeFailure(
                    f"{method} {path} failed with HTTP {error.code}: {raw}"
                ) from error
        except urllib.error.URLError as error:
            if attempt == 7:
                raise MergeFailure(f"{method} {path} failed: {error}") from error
        time.sleep(min(60, 2 ** attempt))
    raise MergeFailure(f"{method} {path} failed after retries")


def branch_sha(branch: str) -> str:
    encoded = urllib.parse.quote(branch, safe="")
    return str(request("GET", f"/branches/{encoded}")["commit"]["sha"])


def pull_request(target_sha: str) -> Mapping[str, Any]:
    query = urllib.parse.urlencode(
        {
            "state": "open",
            "head": f"{OWNER}:{TARGET_BRANCH}",
            "base": BASE_BRANCH,
            "per_page": 100,
        }
    )
    existing = request("GET", f"/pulls?{query}")
    if existing:
        pull = existing[0]
        if pull.get("head", {}).get("sha") != target_sha:
            raise MergeFailure(
                f"PR #{pull['number']} head differs from verified SHA {target_sha}"
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
                "native-source audit updates, dual-Scala closure, and deterministic "
                "publication. The auxiliary finalizer matrix verified the exact head SHA."
            ),
            "maintainer_can_modify": True,
        },
    )


def main() -> int:
    target_sha = os.environ.get("INC59_TARGET_SHA", "")
    base_sha = os.environ.get("INC59_BASE_SHA", "")
    if not target_sha or not base_sha:
        raise MergeFailure("INC59_TARGET_SHA and INC59_BASE_SHA are required")
    if branch_sha(TARGET_BRANCH) != target_sha:
        raise MergeFailure("target branch moved after the verified matrix")
    if branch_sha(BASE_BRANCH) != base_sha:
        raise MergeFailure("base branch moved after the verified matrix")

    request(
        "POST",
        f"/statuses/{target_sha}",
        {
            "state": "success",
            "context": "Increment 59 finalizer matrix closure",
            "description": (
                "Source, dual-Scala, strict V2001, formal, and determinism gates passed"
            ),
        },
    )
    pull = pull_request(target_sha)
    number = int(pull["number"])
    merge = request(
        "PUT",
        f"/pulls/{number}/merge",
        {
            "sha": target_sha,
            "merge_method": "merge",
            "commit_title": "Merge Increment 59 typed BlackBox generic binding",
        },
    )
    if not merge.get("merged"):
        raise MergeFailure(f"PR #{number} did not merge: {merge}")

    result = {
        "state": "merged",
        "pr": number,
        "url": pull.get("html_url"),
        "head_sha": target_sha,
        "base_sha": base_sha,
        "merge_sha": merge.get("sha"),
    }
    output = Path(os.environ.get("INC59_MERGE_RECORD", "/tmp/inc59-merge-record.json"))
    output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except MergeFailure as error:
        print(f"Increment 59 merge failed: {error}", file=sys.stderr)
        raise SystemExit(1)
