#!/usr/bin/env python3
"""Fail-closed Increment 61 GitHub Actions controller."""
from __future__ import annotations

import argparse
import json
import os
import time
import urllib.parse
import urllib.request
from pathlib import Path

REQUIRED = [
    "Increment 61 one-file-per-component publication",
    "Increment 61 per-component compatibility matrix",
    "Increment 60c signed declarations",
    "Increment 60d pure SInt casts",
    "Increment 60e signedness boundaries",
    "Increment 60f equivalence closure",
    "Increment 60g default signed Verilog",
    "MorphHDL baseline",
    "MorphHDL Mill",
    "MorphHDL native source guard",
]


class GitHub:
    def __init__(self) -> None:
        self.repo = os.environ["GITHUB_REPOSITORY"]
        self.token = os.environ["GH_TOKEN"]
        self.rest_base = f"https://api.github.com/repos/{self.repo}"
        self.headers = {
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {self.token}",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "morphhdl-increment-61-controller-v7",
        }

    def rest(self, method: str, path: str, body=None):
        data = None if body is None else json.dumps(body).encode()
        request = urllib.request.Request(
            self.rest_base + path,
            data=data,
            method=method,
            headers=self.headers,
        )
        with urllib.request.urlopen(request, timeout=60) as response:
            raw = response.read()
            return None if not raw else json.loads(raw)

    def graphql(self, query: str, variables: dict):
        request = urllib.request.Request(
            "https://api.github.com/graphql",
            data=json.dumps({"query": query, "variables": variables}).encode(),
            method="POST",
            headers=self.headers,
        )
        with urllib.request.urlopen(request, timeout=60) as response:
            return json.load(response)

    def workflows(self) -> dict[str, dict]:
        result: dict[str, dict] = {}
        page = 1
        while True:
            value = self.rest("GET", f"/actions/workflows?per_page=100&page={page}")
            batch = value.get("workflows", [])
            for workflow in batch:
                result[workflow["name"]] = workflow
            if len(batch) < 100:
                return result
            page += 1


def write_json(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n")


def command_wait_run(args) -> None:
    gh = GitHub()
    deadline = time.time() + args.timeout
    latest = None
    while time.time() < deadline:
        value = gh.rest(
            "GET",
            "/actions/runs?" + urllib.parse.urlencode(
                {"branch": args.branch, "per_page": 100}
            ),
        )
        candidates = [
            run
            for run in value.get("workflow_runs", [])
            if run.get("name") == args.name
        ]
        if candidates:
            latest = max(candidates, key=lambda run: run.get("created_at", ""))
            write_json(args.output, latest)
            print(
                "wait-run",
                latest["id"],
                latest["status"],
                latest.get("conclusion"),
                flush=True,
            )
            if latest["status"] == "completed":
                return
        time.sleep(30)
    raise SystemExit(f"workflow did not become terminal: {args.name}; latest={latest}")


def command_pr(args) -> None:
    gh = GitHub()
    value = gh.rest("GET", f"/pulls/{args.number}")
    write_json(args.output, value)
    print(
        "pr",
        value["number"],
        value["state"],
        value.get("merged"),
        value.get("draft"),
        value["head"]["sha"],
        flush=True,
    )


def command_gate(args) -> None:
    gh = GitHub()
    workflows = gh.workflows()
    missing = [name for name in REQUIRED if name not in workflows]
    if missing:
        raise SystemExit(f"missing required workflows: {missing}")
    args.output.mkdir(parents=True, exist_ok=True)

    def dispatch(name: str) -> None:
        workflow = workflows[name]
        gh.rest(
            "POST",
            f"/actions/workflows/{workflow['id']}/dispatches",
            {"ref": args.branch},
        )
        print("dispatched", name, workflow["path"], flush=True)

    for name in REQUIRED:
        dispatch(name)

    retried: set[str] = set()
    started = time.time()
    deadline = started + args.timeout
    while time.time() < deadline:
        value = gh.rest(
            "GET",
            "/actions/runs?"
            + urllib.parse.urlencode(
                {
                    "branch": args.branch,
                    "per_page": 100,
                    "exclude_pull_requests": "false",
                }
            ),
        )
        candidates = [
            run
            for run in value.get("workflow_runs", [])
            if run.get("head_sha") == args.head
        ]
        latest: dict[str, dict] = {}
        for run in sorted(candidates, key=lambda item: item.get("created_at", "")):
            if run.get("name") in REQUIRED:
                latest[run["name"]] = run
        snapshot = {
            name: None
            if name not in latest
            else {
                "id": latest[name]["id"],
                "status": latest[name]["status"],
                "conclusion": latest[name].get("conclusion"),
                "event": latest[name].get("event"),
                "created_at": latest[name].get("created_at"),
                "html_url": latest[name].get("html_url"),
            }
            for name in REQUIRED
        }
        write_json(args.output / "live.json", {"head": args.head, "runs": snapshot})
        print(args.stage, json.dumps(snapshot, sort_keys=True), flush=True)

        if all(
            name in latest
            and latest[name]["status"] == "completed"
            and latest[name].get("conclusion") == "success"
            for name in REQUIRED
        ):
            write_json(
                args.output / "success.json",
                {
                    "head": args.head,
                    "runs": snapshot,
                    "elapsed_seconds": int(time.time() - started),
                },
            )
            print(args.stage, "PASS", args.head, flush=True)
            return

        bad = [
            name
            for name in REQUIRED
            if name in latest
            and latest[name]["status"] == "completed"
            and latest[name].get("conclusion") != "success"
        ]
        exhausted = [name for name in bad if name in retried]
        if exhausted:
            write_json(
                args.output / "failure.json",
                {"head": args.head, "runs": snapshot, "exhausted": exhausted},
            )
            raise SystemExit(
                f"{args.stage} failed after one fresh rerun: {exhausted}"
            )
        for name in bad:
            dispatch(name)
            retried.add(name)
            print("fresh rerun", name, flush=True)
        time.sleep(30)
    raise SystemExit(f"{args.stage} timed out")


def command_merge(args) -> None:
    gh = GitHub()
    pr = gh.rest("GET", f"/pulls/{args.number}")
    if pr["state"] != "open" or pr.get("merged"):
        raise SystemExit(f"PR is not open and unmerged: {pr['state']} {pr.get('merged')}")
    if pr["head"]["sha"] != args.head:
        raise SystemExit(
            f"PR head changed before merge: expected {args.head}, observed {pr['head']['sha']}"
        )
    if pr.get("draft"):
        result = gh.graphql(
            "mutation($id:ID!){markPullRequestReadyForReview(input:{pullRequestId:$id}){pullRequest{isDraft}}}",
            {"id": pr["node_id"]},
        )
        if result.get("errors") or result["data"]["markPullRequestReadyForReview"][
            "pullRequest"
        ]["isDraft"]:
            raise SystemExit(f"could not mark PR ready: {result}")
    merged = gh.rest(
        "PUT",
        f"/pulls/{args.number}/merge",
        {
            "commit_title": "Increment 61: parameterized one-file-per-component publication (#179)",
            "commit_message": (
                "Publish one strict Verilog-2001 file per canonical component while "
                "preserving parameters, hierarchy, external ownership, deterministic "
                "manifests and safe atomic output management. Includes dual-Scala, "
                "strict-tool, equivalence, mutation, compatibility, baseline and Mill closure."
            ),
            "sha": args.head,
            "merge_method": "merge",
        },
    )
    write_json(args.output, merged)
    if not merged.get("merged"):
        raise SystemExit(f"GitHub refused guarded merge: {merged}")
    print("merged", merged["sha"], flush=True)


def main() -> None:
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("wait-run")
    p.add_argument("--branch", required=True)
    p.add_argument("--name", required=True)
    p.add_argument("--timeout", type=int, default=21600)
    p.add_argument("--output", type=Path, required=True)
    p.set_defaults(run=command_wait_run)

    p = sub.add_parser("pr")
    p.add_argument("--number", type=int, required=True)
    p.add_argument("--output", type=Path, required=True)
    p.set_defaults(run=command_pr)

    p = sub.add_parser("gate")
    p.add_argument("--branch", required=True)
    p.add_argument("--head", required=True)
    p.add_argument("--stage", required=True)
    p.add_argument("--timeout", type=int, default=14400)
    p.add_argument("--output", type=Path, required=True)
    p.set_defaults(run=command_gate)

    p = sub.add_parser("merge")
    p.add_argument("--number", type=int, required=True)
    p.add_argument("--head", required=True)
    p.add_argument("--output", type=Path, required=True)
    p.set_defaults(run=command_merge)

    args = parser.parse_args()
    args.run(args)


if __name__ == "__main__":
    main()
