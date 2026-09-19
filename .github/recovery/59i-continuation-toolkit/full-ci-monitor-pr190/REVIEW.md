PR190 refresh: expect53 workflows,150 required jobs,2 deliberately skipped publishers and95 artifacts. Complete regression inventory is2307 tests/230 suites. The added native sequential consumer artifact and all named generation/native/inherited/simulation/synthesis steps are required. The plan remains unqualified and must be refreshed on the final exact seal.

This is a read-only evidence collector and review plan. It does not dispatch or retry CI, change refs or source, check the TODO, or certify completion from green workflow badges. No remote collection has been run.

The reviewed fleet expands to **53 workflows, 150 required jobs, 2 intentionally skipped jobs, and 94 expected artifacts**. These are expected counts, not completed CI. The only allowed skipped jobs are CDC’s PR189 source-repair publisher and the old60a source-seal publisher. Matrix-specific skipped diagnostic steps are enumerated separately, including baseline’s2.12-only RTL/smoke steps and each inherited shard’s other shard bodies.

The current plan records the dispatcher manifest before the local-enable `include-hidden-files` change. The stale-definition guard detected this difference. After the final workflow definitions are committed, regenerate the dispatcher manifest first, then regenerate the evidence plan:

```sh
python3 -B full-ci-monitor/monitor.py prepare \
  --repo-root /absolute/59i-sealed-checkout \
  --manifest full-ci-controller/dispatch-manifest.json \
  --output full-ci-monitor/evidence-plan.json
```

Collect progress or terminal evidence with a read-only token:

```sh
GITHUB_TOKEN=... python3 -B full-ci-monitor/monitor.py collect \
  --repo-root /absolute/59i-sealed-checkout \
  --plan full-ci-monitor/evidence-plan.json \
  --source-sha SOURCE --seal-sha SEAL \
  --output /absolute/evidence/snapshot-TIMESTAMP \
  --logs \
  --download-workflow increment-59i-local-enable-committed-head.yml \
  --download-workflow increment-59i-inherited-source-qualification.yml \
  --download-workflow increment-59i-combined-closure.yml \
  --download-workflow increment-60f-equivalence-closure.yml \
  --download-workflow increment-61-one-file-per-component.yml
```

`--logs` retains completed-job logs for every required job, including proof workflows with no upload step. Repeat `--download-workflow NAME.yml` for other artifact content reviews. Omit downloads for inexpensive progress snapshots. A future Actions wrapper needs only `contents: read` and `actions: read`; no write token is necessary. Set `MORPHDL_59I_DISPATCHER` to the reviewed dispatcher’s absolute path if the remote directory layout differs from the scratch layout. Locally the connector may download the recorded artifact/job IDs instead when a direct GitHub token is unavailable.

Collection checks the exact source/seal parent and schema4 certificate, pinned workflow bytes, live draft/open PR177, feature ref and targete0e9 before and after collection. It queries only runs at the exact seal, chooses the active run or newest completed run per workflow, and blocks ambiguous duplicates. It never selects an old green run over a newer failure. Earlier implementation heads and the eleven historical failed-first replacements never enter final-head totals.

Both latest-effective jobs and every attempt’s jobs are retained. A successful job reused by a failed-jobs retry can count only within that same run/seal/workflow definition, with its origin attempt recorded. Required expanded jobs and named proof/test/source steps must actually succeed. The two out-of-scope publishers must remain skipped if present. CDC’s448dfdf baseline probe is tagged `historical-baseline-dependency-only`; its compilation cannot count as final-source runtime work.

Each expected artifact is tied to its producing job/attempt, API artifact ID, name, run/seal and API SHA-256. Missing, expired, ambiguous, empty or unbound artifacts are reported separately. Downloads verify ZIP checksums and produce safe member inventories with file hashes; nothing is extracted. Unsafe paths, symlinks and duplicate members are rejected. GitHub tokens are removed before following signed storage redirects. Original/v3 generated-child artifacts remain separate under their distinct run IDs despite matching names.

The main output is `qualification-review-index.json`. `ci_execution_pass` reports actual terminal runs/jobs/steps. `artifact_index_complete` reports availability and identity only. `content_review_pass` and `final_qualification_pass` remain false until the root reviews actual proof/test/RTL contents. Every workflow has explicit `separate_review_required` instructions; absent artifacts or unresolved reviews remain visible.

| Central evidence | Required independent content checks |
| --- | --- |
| Local-enable tests |138exact cases/10suites per Scala; raw XML identities and zero outcomes; head/tree/index equality; cross-Scala test/RTL inventories; original A/B hashes after mutations |
| Main local-enable hardware |96native cases/192comparisons/384cycles;32bounded18-step proofs; strict tools;2emitted-netlist mutants with simulation mismatch and SAT witnesses; normalized unmodified baseline |
| Supplemental hardware |6native cases/24comparisons/320cycles;4actual publication profiles;1emitted root-enable mutant; formal explicitly not-run |
|60f regression evidence |2307exact cases/227suites per Scala against the sealed contract;8projects’ raw XML; current seal receipt; subordinate historical/Increment61 records and actual formal workspaces |
|60f RTL | Actual manifest-derived HDL count/hashes; A/B and cross-Scala bytes; strict tools; native equivalence and mutation witnesses. Do not assume the old213file total |
| Inherited source |5shards covering the unchanged27-command catalogue with exact head/tree/run, reused-attempt origins, ordered returncodes/log hashes; aggregate and preflight checks also succeed |
| Combined59i join | Full nested/capture/widening/saturation/child/mechanism matrices, original emitted RTL/proof inventories and10ABI/golden profiles across both Scala archives; retain focused scope flags |
| Both child workflows | Review each distinct run and its hierarchy/mode/tools/proof inventory; neither workflow silently substitutes for the other |
| Increment61 | Exact source, complete split/single corpora, publication-proof.json, strict tools/equivalence and generated-a/b/cross-Scala byte identity |
| Workflows without uploads | Retain actual completed test/proof logs and hashes; derive case/proof counts and limits from those logs |

Completion should cite exact source and seal, selected run IDs and origin attempts, complete test inventory, actual RTL/proof counts and limitations, then the separately authorized completion/merge result. Do not dispatch duplicate post-merge CI.
