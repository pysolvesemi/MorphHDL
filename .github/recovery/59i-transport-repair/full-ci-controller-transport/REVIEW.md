Transport successor planning: exact seal268cdc63ca5b12177e01b8715688a6e001dff06f/source8c634b150c778607b280dd6831095dd6e474a4c1. All14 mock dispatch controls passed; live target refs are authoritative. Source/runtime qualification is still pending and no full-CI POST has been issued. Read59i-transport-planning.md first; earlier source identities below are historical context.

PR190 refresh: this version is planning-only until the mandatory committed-head local-enable result passes. Current unsealed reviewed source is99aacc05a783b9dc51aaeabd4e1395de385cab28; diagnostic source is ccec54986. The additional native sequential consumer workflow qualifies current combined runtime despite no target-owned paths in the feature diff. All12 dispatch failure-injection controls passed locally. No POST was issued.

This is a scratch-only controller package. No remote mutation has been executed. Eleven read-only GitHub run lookups refreshed the historical source, branch, path, event, attempt, and success identities; their compact results are retained in historical-run-identities.json.

The script supports offline `prepare`, read-only remote `run --mode plan`, normal missing-workflow `run --mode dispatch`, and explicit `run --mode retry-failed --retry-failed-run ID`. Retry mode only requests failed jobs for selected exact-head failed runs; it never dispatches missing workflows. It does not repair code or decide whether a retry is warranted.

Before publication, regenerate the workflow map if any workflow definition has changed, then run:

```sh
python3 -B dispatch.py prepare --repo-root /absolute/59i-sealed-checkout --workflow-map /absolute/59i-pr190-full-ci-workflow-map.json --output dispatch-manifest.json
```

This pins all 53 applicable workflow definitions and all 14 exclusions, including their target definitions. The exact fleet is also embedded in dispatch.py. CDC receives mode=checks; no PR189 source-repair action is dispatched. The map records two intentionally skipped jobs: PR189 repair and the old 60a source-seal publisher. All other required job names, including every literal matrix expansion, must actually succeed before an existing completed run can count as reusable success.

The execution interface is:

```sh
GITHUB_TOKEN=... python3 -B dispatch.py run --repo-root /absolute/clean-seal-checkout --manifest dispatch-manifest.json --source-sha SOURCE --seal-sha SEAL --local-enable-run RUN --mode plan --output /absolute/receipts
```

SOURCE and SEAL are full, distinct commit SHAs. SEAL must have SOURCE as its sole parent and a schema-5 production certificate binding SOURCE and its tree. The successful mandatory local-enable workflow must be on SEAL and have exactly its source job, Scala 2.12.18 lane, Scala 2.13.12 lane, and cross-Scala job successfully executed. The script rechecks all 11 recorded historical replacements at their exact recorded SHAs and all 12 live conclusions before considering full CI. Historical replacements remain historical evidence only. It reruns the committed source-review verifier locally, then checks that the checkout remains clean.

The PR and both live refs must still be exactly PR177, the existing feature branch at SEAL, and parameterized-verilog at 4b8a86e25f5a1a3f0cb4c37dc537a8dd8aa7b097. PR177 must remain draft and open. These identities are checked before every workflow action and at the end. The normal GitHub dispatch API takes the existing feature ref, so final run evidence must still verify the actual sealed head: the guard detects ref movement, and the controller never claims dispatch equals qualification.

Use workflow.template.yml as a separate controller on the existing recovery branch; replace all @...@ tokens with reviewed literal values and initially choose plan. Put dispatch.py and dispatch-manifest.json under .github/recovery/59i-full-ci/. The controller has contents:read and actions:write, persists no checkout credential, serializes its invocations under one concurrency group, and never edits source or refs. Source/seal publication and the initial targeted local-enable dispatch remain the publication controller's responsibility. This full-CI controller is published/triggered only after that local-enable result is actually successful.

Before any full dispatch, it scans the complete fleet for existing same-head failures/cancellations and stops rather than starting unrelated missing runs. Before each individual dispatch, it freshly queries all events for that workflow at the sealed head. Existing successful or active runs are reused; the already passed local-enable run therefore is reused. Multiple active duplicates or unexpected run origins stop for reconciliation. The receipt records every existing run ID and selected run attempt.

Every POST has a durable local intent record first. No POST is automatically retried. An API timeout yields an uncertain record and stops; inspect live runs before any resumption. Preserve and restore dispatch-journal.json into the same output directory when resuming an interrupted controller. An unresolved prior dispatch intent without a visible run is refused, and an explicit failed-jobs retry is refused until its previously requested attempt becomes visible. For a controller rerun, download its prior receipt artifact first rather than deleting the journal. The template is intentionally the initial invocation; add a prior-artifact restore step when resuming it.

Review outputs include start.json, failed-first-pass.json, source-review.log, dispatch-journal.json, and controller-error.json on error. The journal is an action receipt, not a full-CI success certificate. It does not wait for newly dispatched workflows to finish, merge PR177, change the TODO, or trigger post-merge CI. Continue monitoring all 53 final-head workflows and verify their required jobs and artifacts before completion.

Local validation completed: 12 in-memory API failure-injection controls passed. They demonstrate no POST before failed-first success, exact historical recovery-branch pinning, both Scala/cross-Scala gate enforcement, moved-ref rejection, read-only planning, reuse of passed and active runs, whole-fleet failure blocking, rejection of skipped required jobs, explicit failed-jobs-only retries, unknown retry-selector rejection, and refusal to repeat an uncertain dispatch. These controls mock source verification and do not substitute for real source review or CI.
