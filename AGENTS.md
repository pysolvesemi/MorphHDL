# MorphHDL project instructions

These instructions apply to the whole repository. A more specific `AGENTS.md`
may narrow them for its subtree. A direct user instruction for the current task
takes precedence.

## Targeted CI before full CI

For every increment, qualify repaired or newly affected source with targeted CI
before starting full CI. Local tests are useful repair evidence but are not a
substitute for remote qualification.

1. Publish a clean candidate commit and record its full SHA and tree SHA.
2. Determine the affected workflow set from changed source, tests, generated
   artifacts, manifests, formal registries, source-review contracts and prior CI
   failures. Record workflow IDs as well as paths; workflow names are not stable
   identities.
3. Launch each affected workflow with `workflow_dispatch` on the exact candidate
   branch. Do not duplicate an active or successful run for the same workflow and
   head SHA.
4. Treat a workflow as passing only after every applicable job and matrix lane is
   successful on the intended head. Enumerate all run, job, check and status pages;
   aggregate labels alone are insufficient. A skipped applicable job, missing
   lane, pending job, cancellation or old-head success is not passing evidence.
5. Start one applicable full-CI qualification only after all targeted workflows
   pass. Do not use repeated full-CI launches as a replacement for targeted repair
   qualification.

Rerunning an existing job preserves that run's original source SHA. Use a
specific-job or failed-job rerun only for an unchanged-source transient failure.
After any source, test, workflow, manifest, registry or review-contract repair,
publish the corrected source and dispatch a fresh run on the new head.

## Browserless targeted dispatch

Do not require the user to run GitHub CLI commands. Do not depend on browser UI,
a devbox or an external MCP server when repository write access is available.
Use the following launch order:

1. Use a connected GitHub `workflow_dispatch` operation when it is exposed.
2. Otherwise, use the standing authorization below to publish and trigger a new,
   increment-specific repository dispatcher.

### Standing authorization for a repository dispatcher

When direct dispatch is unavailable, the agent is authorized to create a narrowly
scoped controller on a dedicated non-target, non-feature control branch. This is
an ordinary implementation mechanism for targeted CI, not a request for separate
user confirmation. The controller may use the GitHub-hosted runner's automatic
`${{ github.token }}` to call the Actions workflow-dispatch REST endpoint.

The controller must:

- start from the current integration target and live outside the feature PR;
- use a unique branch and controller identity for the increment and candidate
  head; never repurpose a controller pinned to another increment, PR, branch or
  SHA;
- have an exact `push` branch filter plus path filters limited to its own workflow
  and reviewed controller files;
- request only `contents: read` and `actions: write` permissions;
- use no personal access token, extracted credential, browser session, default-
  branch change, feature-branch trigger modification or unrelated workflow edit;
- contain an explicit allowlist of repository, PR, base ref and SHA, candidate ref
  and SHA, workflow IDs, dispatch payloads and any permitted inputs;
- accept only `POST /repos/OWNER/REPO/actions/workflows/WORKFLOW_ID/dispatches`
  for allowlisted workflows, normally with the exact body
  `{"ref":"CANDIDATE_BRANCH"}`;
- validate the live PR, target and candidate identities, candidate tree, original
  failure evidence when applicable, and workflow definitions before making any
  dispatch;
- enumerate existing same-workflow, same-head `workflow_dispatch` runs and retain
  queued, running or successful runs instead of duplicating them;
- recheck identities between dispatches, fail closed on movement or unexpected
  data, use bounded timeouts, and write an auditable dispatch ledger;
- dispatch only the affected workflow set. It must not update source refs, rerun
  old heads, launch full CI, merge, cancel unrelated work or broaden triggers.

Before publishing the controller, inspect all workflows that can match a push to
its control branch. The controller push must launch only the intended controller;
if other substantive workflows would also run, choose a safe isolated route or
stop without dispatching. The controller-launch commit must not contain
`[skip ci]`, because that would suppress the controller itself. Keep controller
commits off the feature and integration branches.

After the controller starts, verify each resulting run has the expected workflow
ID, event `workflow_dispatch`, candidate branch and exact head SHA. The actor may
be `github-actions[bot]` because the controller uses the job token; this is
expected and is not by itself qualification evidence.

## Failure handling and publication

- Leave unrelated and still-running jobs intact for the next scheduled snapshot.
- For a genuine source failure, repair the generic cause, run proportional local
  tests, publish a clean checkpoint with `[skip ci]`, and dispatch only affected
  workflows on the corrected head.
- Preserve authoritative ancestry. Do not squash, rebase, force-push or qualify a
  different tree under an old result.
- Do not weaken tests, simulations, formal proofs, mutation controls, source
  reviews, timeouts or workflow matrices merely to obtain a pass.
- After targeted qualification, follow the increment's documented full-CI,
  completion, merge and post-merge suppression requirements.
