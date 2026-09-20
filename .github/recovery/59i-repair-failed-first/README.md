# PR177 repair: targeted failed-first handoff

Prepared offline for candidate `883c5d8f088a0e2eab35592cf171d87792d30bf4`, source
`44313610eb2d72759808f348a552134e97805474`, and live target
`bbae646ba43e6189c69feb308f8decb9b677b15f`. No remote writes were performed by
this preparation. The controller dispatches only the 16 workflows below.
It cannot publish source, move refs, create branches, edit PR177, merge, rerun
failed jobs, or dispatch full CI.

Source staging is run `35522190973`, attempt 1, recovery controller commit
`44825913267ab3972cdfded4a885e05b3b22367a`. Its terminal result and final
artifact are unknown at preparation. `stage-gate.json` deliberately leaves
`artifact_id` and `artifact_sha256` null. The controller refuses to proceed until
both are supplied and the live staging run has completed successfully.

## Before using the dispatcher

1. Wait for staging run 35522190973 to finish successfully. Retrieve the final
   artifact named `increment-59i-local-enable-exact-commit-staging-1` from that
   run. Independently verify the source/commit receipts and artifact API digest.
   Put its exact numeric ID and 64-character digest (without `sha256:`) in
   `stage-gate.json`. Do not substitute the earlier tree-request artifact.
2. Complete the separately reviewed feature publication: existing branch
   `agent/increment-59i-combined-reduction-closure` must point to the exact
   candidate. The target must still point to bbae646 and PR177 must remain open
   and draft. This dispatcher performs those checks but does not publish it.
3. Store this package only under the existing recovery branch
   `recovery/increment-59i-history-20260914`, at
   `.github/recovery/59i-repair-failed-first/`. It can be preserved with a
   `[skip ci]` recovery commit while staging is active. Do not add it to the
   feature branch. Leave `workflow.template.yml` as data until activation.
4. Activate the reviewed template as
   `.github/workflows/increment-59i-repair-failed-first-controller.yml` on that
   same recovery branch. Push-triggered execution uses read-only `plan` mode.
   Inspect its journal, then change only the fallback of `CONTROLLER_MODE` from
   `|| 'plan'` to `|| 'dispatch'` in the installed recovery workflow and push that
   reviewed change to dispatch missing targeted work. This supports the existing
   recovery push transport without a connector workflow-dispatch action. Change
   only that fallback to `|| 'reconcile'` for later read-only reconciliation.
   If a workflow-dispatch action is available, its explicit `mode` input is also
   supported. Do not change branch/head pins, required jobs, or source gates.

If the candidate, live target, staging attempt, or staging controller changes,
stop and review/rebuild these fixed identities. Do not merely alter the head
constants or regenerate a workflow hash to bypass a failed source check.

## Required evidence

The controller checks the staging run's repository, recovery branch, controller
SHA, path, attempt, terminal success, and both executed staging phases. It
downloads the API-bound final artifact without forwarding the GitHub token to
its signed download URL. It verifies the archive size/digest, all 25 source
check commands, every zero return code and log digest, and all three exact
staged commits/trees/ordered parents against the candidate checkout.

The candidate checkout must be clean. All 16 workflow hashes must equal their
actual candidate blobs and the pinned manifest. Live feature/target/PR guards
are checked before each dispatch and afterward. The eight old failures and
seven old cancellations are also rechecked against their original b6f1 attempt
1 identities. Historical results grant no new-head qualification credit.

## Targeted inventory

All prior runs below used `b6f1fefb531ca4cb5aca266628dc29093f6bbafe` on the
existing feature branch. The first workflow is mandatory new-head qualification.

| Workflow file | Prior run | Prior result |
|---|---:|---|
| increment-59i-local-enable-committed-head.yml | — | Required new-head qualification |
| lane-when-expression-diagnostic.yml | 35499173209 | failure |
| independent-parameter-domains.yml | 35499171285 | failure |
| increment-62-wa08-source-overlay.yml | 35499169370 | failure |
| increment-61-one-file-per-component.yml | 35499167334 | failure |
| increment-61-compatibility-matrix.yml | 35499165569 | failure |
| increment-60b-signedness-authority.yml | 35499153709 | failure |
| increment-59i-combined-closure.yml | 35499141350 | failure |
| cdc-independent-parameter-consumers.yml | 35499115467 | failure |
| morphhdl-mill.yml | 35499191135 | cancelled |
| morphhdl-baseline.yml | 35499175068 | cancelled |
| increment-60f-equivalence-closure.yml | 35499161822 | cancelled |
| increment-59h-nested-owners.yml | 35499139515 | cancelled |
| increment-59g-register-bridges.yml | 35499137471 | cancelled |
| increment-59f-callback-graphs.yml | 35499135500 | cancelled |
| increment-59e-composite-reduction.yml | 35499133452 | cancelled |

CDC receives only `mode: checks`; all other dispatch inputs are empty. There
are 55 required expanded job names. CDC's PR189 source-repair job is the sole
intentionally optional job. Every required job must execute successfully before
a successful workflow receives targeted qualification credit.

The seven cancellations were real timeout expirations. Their earlier artifacts
showed ongoing work without assertion failures. This package preserves the
candidate workflow definitions and does not change their runtime budgets.

## Reuse and uncertain requests

The controller inspects all 16 workflows before its first POST. It reuses any
existing active exact-candidate run or, otherwise, the newest exact-candidate
run and authenticates all required jobs for success. A newest same-head failed
or cancelled run blocks dispatch until it is diagnosed; an older success cannot
hide that result. No implicit rerun operation is offered.

Before each POST, the complete journal is atomically written and fsynced with
`dispatch-intent`, the time, and all previously visible run IDs. POST is attempted
once. A lost/failed response leaves `dispatch-uncertain`. Read-only planning
cannot erase that state. Reconciliation binds the single newly visible run;
zero or multiple new runs require continued/manual reconciliation, never a
second blind POST. A previously bound run disappearing also blocks redispatch.

Every invocation uploads its journal in an `always()` artifact. Subsequent
Actions runs/attempts restore the preceding journal using its API digest. If an
earlier controller step started but its journal cannot be recovered, the next
invocation fails closed. Recover that evidence before continuing. Only a prior
attempt whose controller step never started may proceed without a journal.
The workflow concurrency group prevents simultaneous controllers.

An Actions runner killed before artifact upload may leave no durable uploaded
journal; the missing-journal guard specifically prevents treating that case as
a fresh dispatch. Branch guards and dispatch cannot be atomic across GitHub's
APIs, so any detected ref movement stops the controller and requires inspection
of returned run identities.

## Offline verification

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B test_dispatch.py
```

Focused fake-API controls cover the 16-workflow limit, read-only modes, exact-head
reuse, newer failures following older successes, source-stage failure/unknown
artifact, moved feature, historical result drift, required-job skipping,
uncertain POST persistence/reconciliation, missing
bound runs, and forbidden ref/PR/unlisted workflow writes. They make no network
requests. `verification.json` records the results and exact local identities.

Dispatch receipts are not passing CI. Only after all 16 required targeted runs
actually pass should the separate full-CI decision be considered. This package
never marks Increment 59i completed or authorizes merge based on dispatch.
