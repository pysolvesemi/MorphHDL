#!/usr/bin/env python3
"""Fail-closed dispatcher for the six schema-21 Increment 59i requirements."""

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
CANDIDATE = "90eb789ab8a955ffd4d3ef6a4dff575350aff771"
CANDIDATE_TREE = "b4deca446c1d043662d2f98627cd7ad3c4347c2c"
SOURCE = "aff0b4f2cfed941b2958157e48b14e5412b44aa1"
TARGET_REF = "parameterized-verilog"
TARGET = "db54d01e5b21c7664f7a0de3795f061d77a3d259"
CONTROL_REF = "recovery/increment-59i-schema21-targeted-90eb789a"
RECOVERY_REF = "recovery/increment-59i-history-20260914"
RECOVERY_INTENT = "1799c6594d88f5ecb8e025ab919cbcff3a55a429"
SOURCE_RUN = 36289367772
SOURCE_ARTIFACT = 10925708881
SOURCE_DIGEST = "50f9214f575834217f59e33888d23795e4610ad8f504459966c7972db39ffd76"
RECON_RUN = 36305442960
RECON_ARTIFACT = 10926957812
RECON_DIGEST = "a823b932e1591e96a8d3e85b7bb1359badb17d4891dafefd3cb21de63516860d"
FAILED_60F_RUN = 36275378799
FAILED_60F_ARTIFACT = 10920685660
FAILED_60F_DIGEST = "e4968234a6bcecdb881b98d9e5d5e8a9f10844e4d4a549305962801838652886"

WORKFLOWS = (
    (351327055, ".github/workflows/increment-60f-equivalence-closure.yml",
     "03b4fd45b5137ebda5a54648e62df713f0f1f456",
     "224c4bc93dfef8c278be8cf93088e12c64dc8fbc4c8cfdf09ccaf06373e350d6"),
    (352827927, ".github/workflows/increment-59i-combined-closure.yml",
     "acce6319fbbdfc39c645f425f304eb44159f59ed",
     "d183330757c8f3933679b40a418faed2240517668b482da45198bcd130fe5437"),
    (351996892, ".github/workflows/increment-59h-nested-owners.yml",
     "609b2606ba6076cae95f135a43e8eac0343bb776",
     "c29048bc747a6604e87e34bbf8e69e9991cc03732f3a2f0dabbdf2206698afab"),
    (351397918, ".github/workflows/increment-59f-callback-graphs.yml",
     "bf63e979092e990e6389e8db72d32da52ee469a2",
     "bb47eaeca764e1c92b4c3d64e72d4137af4067bf15ff284cece3039873ca7266"),
    (354645379, ".github/workflows/increment-62-wa08-source-overlay.yml",
     "5ab92ecf5f588664c989c747f6d282d13b8fbee8",
     "250382848e77e10067f1ac5c9af7428e9a7727a1ec0053b6d5c7249e5fcbaa77"),
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
            "User-Agent": "increment-59i-schema21-targeted-controller",
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
    require([row["sha"] for row in control_commit["parents"]] == [TARGET],
            "controller is not a direct child of the exact target")
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
    run_guard(api, SOURCE_RUN, "success")
    artifact_guard(api, SOURCE_ARTIFACT, SOURCE_DIGEST)
    run_guard(api, RECON_RUN, "success")
    artifact_guard(api, RECON_ARTIFACT, RECON_DIGEST)
    failed = run_guard(api, FAILED_60F_RUN, "failure", "e98e9d034ab91a791377e212bc0e4b37d8c44716")
    require(failed["workflow_id"] == 351327055,
            "preserved 60f failure workflow changed")
    artifact_guard(api, FAILED_60F_ARTIFACT, FAILED_60F_DIGEST)


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
    require(value["schema"] == "increment-59i-schema21-targeted-dispatch-intent-v1",
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
        require(not initial, "exact-head inventory is no longer zero before dispatch")
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
        print("INCREMENT_59I_SCHEMA21_TARGETED_DISPATCH_PASS")
    except Exception as error:
        ledger["complete"] = False
        ledger["error"] = str(error)
        write_json(args.output / "dispatch-ledger.json", ledger)
        raise


if __name__ == "__main__":
    main()
