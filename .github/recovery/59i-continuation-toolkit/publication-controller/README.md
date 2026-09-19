# Exact 59i publication controller

Prepared locally only. It does not update a ref, merge, mark the PR ready, check a TODO, or dispatch full CI.

## Build after the expanded diagnostic and source seal

Run from a clean checkout whose HEAD is the final seal:

```bash
python3 -B /workspace/scratch/68d458e0e937/publication-controller/build.py \
  --repo-root /workspace/scratch/68d458e0e937/59i-dev \
  --source SOURCE_SHA --seal SEAL_SHA \
  --diagnostic-source DIAGNOSTIC_SOURCE_SHA \
  --diagnostic-run SUCCESSFUL_EXPANDED_RUN_ID \
  --diagnostic-controller EXACT_RECOVERY_CONTROLLER_SHA \
  --output /workspace/scratch/68d458e0e937/publication-controller-ready
```

The source, seal, and diagnostic arguments must be full Git SHAs. The diagnostic source must be an ancestor of the reviewed source. Every tracked Scala file, build.sbt, .gitmodules, project file, and three hardware verification programs must have identical bytes in the diagnostic and seal. Both path sets are checked, so adding or deleting a runtime file also fails. Every gitlink path and pinned commit must match. Audit contracts/scripts can legitimately differ. `prepare` also verifies the exact successful diagnostic run and both Scala job identities before any object upload.

The history bundle contains every commit reachable from the seal but not from published 90b, including the 34a93ec7 and 1931c0aa side history, the ordered d76 merge, and all subsequent linear source commits. Raw author/committer identities, timestamps, timezone offsets, messages, ordered parents, trees, and resulting commit SHAs are retained. In particular, +0200 timestamps must not be normalized to UTC. GitHub documents explicit offsets for commit creation: https://docs.github.com/en/rest/using-the-rest-api/timezones-and-the-rest-api . Every returned object SHA is checked before proceeding.

## Publish the recovery controller

Using the connector, put the `controller_files` from `build-receipt.json` under `.github/recovery/59i-local-enable-publication/`, and `stage.yml` at `.github/workflows/increment-59i-local-enable-publication-stage.yml`, on the existing recovery branch `recovery/increment-59i-history-20260914`. Preserve the latest remote parent and use a non-force update. The controller commit itself must allow its narrowly scoped push workflow to run. No other recovery workflow path is changed.

## Review and complete three bounded handoffs

1. Download the source-and-tree-requests artifact. Check its sealed source identity, all 21 source-check return codes and log hashes, passed diagnostic identity, and raw bundle checksum. Call the connector `github_create_tree` once per `connector-tree-requests.json` item, in listed order, with its `repository_full_name`, `base_tree_sha`, and `tree_elements`. Verify each returned SHA equals `expected_tree_sha`. The entries reference uploaded blob SHAs, so large manifests do not need inline connector content.
2. The controller then creates exact commit objects using the Actions token; it does not move refs. Download the exact-commit-staging artifact and verify every expected SHA/tree/ordered-parent tuple. Re-read PR177, feature, and target identities; they must still be draft/open, feature90b, and targete0e9. Non-force update the existing feature ref to the exact seal with the connector. Every source commit has `[skip ci]`; no source history is recreated through connector commit defaults.
3. The controller observes that exact feature update, checks draft PR and target identity again, and dispatches only `increment-59i-local-enable-committed-head.yml` on the feature branch. It reuses any existing same-seal dispatch attempt and does not auto-rerun failures. Download the final dispatch receipt. Full CI remains a separate action after this requirement passes.

The controller's API allowlist permits exact-scope reads, reviewed blob uploads, exact commit creation, and that one workflow dispatch. It contains no ref update endpoint. Unexpected feature/target movement, unsupported commit metadata, changed raw bytes, missing diagnostic lanes, failed source checks, wrong remote SHAs, or truncated run inventory stops the process.

The workflow has three artifact uploads so the root can finish tree creation and feature update while the job is waiting. Artifacts use relative paths and preserve source logs even if a later step fails. The 60-minute waits only occur inside Actions, not in an assistant tool call.

After offline reconstruction, `prepare` explicitly runs `git submodule update --init --recursive`, checks that every reported submodule is at its committed revision, and retains that log and status before the 21 current-source gates. `reconstruct` itself stays offline, so a local smoke test does not require new network access. The source verifier accepts empty uninitialized submodules, but qualification preparation deliberately inspects their initialized contents as well.

Ordinary checks retain the previous staging controller's 1200-second wrapper. The continuation, PR189 replay, and inherited-audit-budget test routers already contain authenticated 3600-second subprocess limits. Their individual limits are unchanged; the new staging controller does not add a shorter 1200-second aggregate wrapper around them. The complete Actions staging job remains bounded at 180 minutes. This staging work does not alter any production workflow or test timeout.

## Local validation

`python3 -B publication-controller/validate.py` parses every Python/YAML/shell block and verifies API commit-body round trips against all existing unpublished commits. A full reconstruction smoke test requires the eventual real seal and payload; after building, run the `reconstruct` phase in a fresh clone/worktree of the preserved repository. That phase has no remote calls.
