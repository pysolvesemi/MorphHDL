#!/usr/bin/env python3
"""Fail-closed dispatcher for the schema-22 Increment 59i local-enable requirement."""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import time
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen


REPOSITORY = "pysolvesemi/MorphHDL"
OWNER, REPO = REPOSITORY.split("/")
PR = 177
FEATURE = "agent/increment-59i-combined-reduction-closure"
CANDIDATE = "9c88f5f75921e28aff329e3cff2d40132315e861"
CANDIDATE_TREE = "79df0c9df93f30ffcd94e4fe140b2b2f7e865c80"
SOURCE = "aef4ffdb13043226e633ad95e9902eb4adb549a5"
TARGET_REF = "parameterized-verilog"
TARGET = "db54d01e5b21c7664f7a0de3795f061d77a3d259"
CONTROL_REF = "recovery/increment-59i-schema22-local-enable-9c88f5f7"
CONTROL_PARENT = "26ba3c051e8837c81e88f5bd0479d35888341d5c"
RECOVERY_REF = "recovery/increment-59i-history-20260914"
RECOVERY_INTENT = "0fb1136c9476d579d94b0e214c977248e71c23aa"
SOURCE_RUN = 36332110651
SOURCE_ARTIFACT = 10942315960
SOURCE_DIGEST = "bbfa9a94228b9f7e7afb6acf38f34204fad88d6ef52e41965201fc540018b7b1"
RECON_RUN = 36350885528
RECON_ARTIFACT = 10942017917
RECON_DIGEST = "a7d25eb7b0e86118bc4734d90c2ef2b565a37a856eda420a6af5312ebf65b724"
FAILED_LOCAL_RUN = 36312354658
FAILED_LOCAL_HEAD = "90eb789ab8a955ffd4d3ef6a4dff575350aff771"
FAILED_LOCAL_ARTIFACT_1 = 10930546836
FAILED_LOCAL_DIGEST_1 = "7ded588695e08863d733a76e80ceccbe1406bc70150fd0c53304fed9e4159d74"
FAILED_LOCAL_ARTIFACT_2 = 10931852639
FAILED_LOCAL_DIGEST_2 = "8057afc1d782b3cc1234828e488ec097d54a632c24659a422a5869675dafcee1"

WORKFLOWS = (
    (358545233, ".github/workflows/increment-59i-local-enable-committed-head.yml",
     "0b5b041215367c58b1fe9c481f5ca013cfc08ce2",
     "af61ce0d634c23b738146eef8dcd45c22fc667226f79f54ea849696d3f289369"),
)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def digest(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n")


class Api:
    def __init__(self, token: str):
        require(bool(token), "GITHUB_TOKEN is required")
        self.token = token

    def request(self, method: str, path: str, body: object | None = None):
        url = "https://api.github.com/repos/" + REPOSITORY + path
        raw = None if body is None else json.dumps(body, separators=(",", ":")).encode()
        request = Request(url, data=raw, method=method, headers={
            "Authorization": "Bearer " + self.token,
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "increment-59i-schema22-targeted-controller",
            **({"Content-Type": "application/json"} if raw is not None else {}),
        })
        try:
            with urlopen(request, timeout=45) as response:
                payload = response.read()
                return None if not payload else json.loads(payload)
        except HTTPError as error:
            detail = error.read().decode(errors="replace")
            raise RuntimeError(f"GitHub API {method} {path} failed: {error.code}: {detail}") from error

    def get(self, path: str):
        return self.request("GET", path)

    def post(self, path: str, body: object):
        return self.request("POST", path, body)


def artifact_guard(api: Api, artifact_id: int, expected_digest: str) -> dict:
    artifact = api.get(f"/actions/artifacts/{artifact_id}")
    require(artifact["id"] == artifact_id and not artifact["expired"],
            f"artifact {artifact_id} unavailable")
    require(artifact.get("digest") == "sha256:" + expected_digest,
            f"artifact {artifact_id} digest changed")
    return artifact


def run_guard(api: Api, run_id: int, conclusion: str, head: str | None = None) -> dict:
    run = api.get(f"/actions/runs/{run_id}")
    require(run["id"] == run_id and run["repository"]["full_name"] == REPOSITORY,
            f"run {run_id} identity changed")
    require(run["status"] == "completed" and run["conclusion"] == conclusion,
            f"run {run_id} conclusion changed")
    if head is not None:
        require(run["head_sha"] == head, f"run {run_id} head changed")
    return run


def guard(api: Api) -> None:
    require(os.environ.get("GITHUB_REPOSITORY") == REPOSITORY, "repository context changed")
    require(os.environ.get("GITHUB_REF_NAME") == CONTROL_REF, "controller ref changed")
    controller_sha = os.environ.get("GITHUB_SHA", "")
    control = api.get("/git/ref/heads/" + CONTROL_REF)
    require(control["object"]["sha"] == controller_sha, "live controller ref changed")
    control_commit = api.get("/git/commits/" + controller_sha)
    require([row["sha"] for row in control_commit["parents"]] == [CONTROL_PARENT],
            "controller repair parent changed")
    control_parent = api.get("/git/commits/" + CONTROL_PARENT)
    require([row["sha"] for row in control_parent["parents"]] == [TARGET],
            "controller base is not a direct child of the exact target")
    feature = api.get("/git/ref/heads/" + FEATURE)
    target = api.get("/git/ref/heads/" + TARGET_REF)
    recovery = api.get("/git/ref/heads/" + RECOVERY_REF)
    require(feature["object"]["sha"] == CANDIDATE, "feature ref moved")
    require(target["object"]["sha"] == TARGET, "target ref moved")
    require(recovery["object"]["sha"] == RECOVERY_INTENT, "recovery intent ref moved")
    commit = api.get("/git/commits/" + CANDIDATE)
    require(commit["tree"]["sha"] == CANDIDATE_TREE, "candidate tree changed")
    require([row["sha"] for row in commit["parents"]] == [SOURCE],
            "candidate ancestry changed")
    pr = api.get(f"/pulls/{PR}")
    require(pr["state"] == "open" and pr["draft"] and not pr["merged"],
            "PR state changed")
    require(pr["head"]["ref"] == FEATURE and pr["head"]["sha"] == CANDIDATE,
            "PR head changed")
    require(pr["base"]["ref"] == TARGET_REF and pr["base"]["sha"] == TARGET,
            "PR base changed")


def validate_evidence(api: Api) -> None:
    run_guard(api, SOURCE_RUN, "success", "5005bb18ab36f39852f393660057148c91f623cf")
    artifact_guard(api, SOURCE_ARTIFACT, SOURCE_DIGEST)
    run_guard(api, RECON_RUN, "success", "cd0a2c1fb0606a3fafcdc75fa24b83fa611eab6e")
    artifact_guard(api, RECON_ARTIFACT, RECON_DIGEST)
    failed = run_guard(api, FAILED_LOCAL_RUN, "cancelled", FAILED_LOCAL_HEAD)
    require(failed["workflow_id"] == 358545233,
            "preserved local-enable failure workflow changed")
    artifact_guard(api, FAILED_LOCAL_ARTIFACT_1, FAILED_LOCAL_DIGEST_1)
    artifact_guard(api, FAILED_LOCAL_ARTIFACT_2, FAILED_LOCAL_DIGEST_2)


def validate_workflows(api: Api) -> None:
    for workflow_id, path, blob_sha, expected_digest in WORKFLOWS:
        workflow = api.get(f"/actions/workflows/{workflow_id}")
        require(workflow["id"] == workflow_id and workflow["path"] == path,
                f"workflow {workflow_id} path changed")
        require(workflow["state"] == "active", f"workflow {workflow_id} is not active")
        blob = api.get("/git/blobs/" + blob_sha)
        raw = base64.b64decode(blob["content"])
        require(blob["sha"] == blob_sha and digest(raw) == expected_digest,
                f"workflow {workflow_id} bytes changed")
        require(b"workflow_dispatch:" in raw, f"workflow {workflow_id} lacks dispatch trigger")


def all_runs(api: Api) -> list[dict]:
    rows: list[dict] = []
    for page in range(1, 11):
        query = urlencode({"head_sha": CANDIDATE, "per_page": 100, "page": page})
        payload = api.get("/actions/runs?" + query)
        batch = payload["workflow_runs"]
        rows.extend(batch)
        if len(batch) < 100:
            require(payload["total_count"] == len(rows), "all-event pagination changed")
            return rows
    raise RuntimeError("all-event inventory exceeded bounded pagination")


def exact_runs(rows: list[dict], workflow_id: int) -> list[dict]:
    return [row for row in rows
            if row["workflow_id"] == workflow_id
            and row["event"] == "workflow_dispatch"
            and row["head_sha"] == CANDIDATE
            and row["head_branch"] == FEATURE]


def classify_existing(rows: list[dict], workflow_id: int) -> dict | None:
    matches = exact_runs(rows, workflow_id)
    require(len(matches) <= 1, f"duplicate exact-head runs for workflow {workflow_id}")
    if not matches:
        return None
    run = matches[0]
    reusable = run["status"] in {"queued", "in_progress", "waiting", "pending", "requested"}
    reusable = reusable or (run["status"] == "completed" and run["conclusion"] == "success")
    require(reusable, f"existing exact-head run for workflow {workflow_id} requires diagnosis")
    return run


def dispatch_one(api: Api, workflow_id: int) -> dict:
    guard(api)
    before_rows = all_runs(api)
    existing = classify_existing(before_rows, workflow_id)
    if existing is not None:
        return {"workflow_id": workflow_id, "action": "retained", "run_id": existing["id"],
                "status": existing["status"], "conclusion": existing["conclusion"]}
    before_ids = {row["id"] for row in before_rows}
    api.post(f"/actions/workflows/{workflow_id}/dispatches", {"ref": FEATURE})
    for _ in range(12):
        time.sleep(5)
        guard(api)
        created = [row for row in exact_runs(all_runs(api), workflow_id)
                   if row["id"] not in before_ids]
        require(len(created) <= 1, f"dispatch created duplicate runs for workflow {workflow_id}")
        if created:
            run = created[0]
            return {"workflow_id": workflow_id, "action": "dispatched", "run_id": run["id"],
                    "status": run["status"], "conclusion": run["conclusion"]}
    raise RuntimeError(f"dispatch receipt did not appear for workflow {workflow_id}")


def validate_local(controller: Path, intent: Path) -> dict:
    expected_controller = os.environ.get("EXPECTED_CONTROLLER_SHA256", "")
    expected_intent = os.environ.get("EXPECTED_INTENT_SHA256", "")
    require(digest(controller.read_bytes()) == expected_controller,
            "controller bytes changed")
    require(digest(intent.read_bytes()) == expected_intent, "intent bytes changed")
    value = json.loads(intent.read_text())
    require(value["schema"] == "increment-59i-schema22-local-enable-targeted-dispatch-intent-v1",
            "intent schema changed")
    require(value["qualified_head"]["commit"] == CANDIDATE
            and value["qualified_head"]["tree"] == CANDIDATE_TREE,
            "intent candidate changed")
    require(value["full_ci"] is False and value["deduplication"]["total_count"] == 0,
            "intent scope changed")
    require([row["workflow_id"] for row in value["workflows"]]
            == [row[0] for row in WORKFLOWS], "intent workflow order changed")
    return value


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--intent", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    controller = Path(__file__).resolve()
    ledger = {"candidate": CANDIDATE, "full_ci": False, "results": []}
    try:
        intent = validate_local(controller, args.intent.resolve())
        write_json(args.output / "intent.json", intent)
        api = Api(os.environ.get("GITHUB_TOKEN", ""))
        guard(api)
        validate_evidence(api)
        validate_workflows(api)
        initial = all_runs(api)
        require(not initial, "exact-head inventory is no longer zero before local-enable dispatch")
        for workflow_id, _, _, _ in WORKFLOWS:
            ledger["results"].append(dispatch_one(api, workflow_id))
            write_json(args.output / "dispatch-ledger.json", ledger)
        final = all_runs(api)
        for workflow_id, _, _, _ in WORKFLOWS:
            require(len(exact_runs(final, workflow_id)) == 1,
                    f"final exact-head receipt count differs for workflow {workflow_id}")
        ledger["final_all_event_count"] = len(final)
        ledger["complete"] = True
        write_json(args.output / "dispatch-ledger.json", ledger)
        write_json(args.output / "all-event-runs.json", final)
        print("INCREMENT_59I_SCHEMA22_TARGETED_DISPATCH_PASS")
    except Exception as error:
        ledger["complete"] = False
        ledger["error"] = str(error)
        write_json(args.output / "dispatch-ledger.json", ledger)
        raise


if __name__ == "__main__":
    main()
