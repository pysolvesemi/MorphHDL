# Current 59i checkpoint: PR190 source review ready, runtime diagnostic pending

Read this before the older publication or integration checkpoints. Standing
authorization still requires failed-first CI, then full final-head CI, then
completion and merge. Preserve all history; no force push, new feature branch,
weakened gate, duplicate runtime dispatch or messages to others.

## Exact source and current run

Unsealed source: `99aacc05a783b9dc51aaeabd4e1395de385cab28`, tree
`b8af523e55e53803cd65317652fd104ec12f71c1`. It adds two audit-only commits
(`40a001a8877dd5b357ecc444574e2aa0527c6d56`, then99aacc05) after the exact
PR190 merge checkpoint `ccec54986c70077e361f291cd322f0aa547aee15`.
Source59i-dev is clean and unsealed; its old schema4 manifest remains intact.

Fresh two-Scala diagnostic is run `35458181915`, controller
`5f2d416138ace882dd8465a41b4187211f368ccc`, controller tree
`cd5d79d605b1b2326a6ac29a167f55df32214754`. It compiles and tests sourceccec,
not the later audit-only files. At the last job inspection both lanes had
passed reconstruction/frozen audits and were compiling. Inspect actual current
status and artifacts; no result is implied by this checkpoint.

Target is `4b8a86e25f5a1a3f0cb4c37dc537a8dd8aa7b097`, tree
`ebe59eecbc8f550d265e78c717fb093603329055`. The actual branch ref is
authoritative; PR177's base SHA has been stale in connector responses.
Feature remains `90b7fc8f13f2c53dbb6f7f8ab51f4e43cd486be6`, draft/open.
The older source545/seal58fb and successful diagnostic35449602915 remain
preserved history. Staging35456999564 stopped on target movement before any
source gate, blob upload or feature update. Never rerun that stale controller.

## Durable restoration

`pr190-reviewed-source-checkpoint/manifest.json` binds a complete 44-commit
Git bundle beyond published90b. Concatenate its twelve ordered base64 parts,
verify each part hash, decode, verify compressed SHA256
`c5f11a1b57c89066b48fc1b6c783cde49c31332df42c1f1d4f158e37ac6436f2`,
decompress XZ, and verify 409,793 bytes / bundle SHA256
`26b18e54e2f38148536f19b746cb6cfb826bb8950be325a4eb91ce5b9d475e0c`.
Verify the bundle against the repository with90b available, fetch it and check
out exact99aacc05. Check every listed commit/tree/ordered-parent/raw hash. All
Scala, build, resource, oracle and hardware checker bytes still equalccec.

The original active diagnostic payload remains under the separate recovery
directory `59i-local-enable-probe-20260919/`. The continuation toolkit contains
the exact PR190 builder/templates/inspector, versioned seal and publication
builders, review reasons, source bundle, planning tools, actual local logs and
receipts. Restore toolkit relative paths and verify its manifest hashes.

## Source review performed

Production helper normalized SHA256:
`b6d8b5183402b15e5b6c0c0ebc1bc79f54e3a822a913c2f92453cdf050759377`.
Schema5 preserves/replays complete seal58fb (322 source/93 target records),
binds exact mergeccec with ordered parents58fb+4b and common basee0e9, checks
the new complete40-path target delta with nine exact audit reconciliations,
and rejects any non-enumerated or runtime change in every source-review
descendant, including drift later restored. Schemas1-4 remain available.

Current review independently reconstructs1,845 runtime/build/resource
identities, including the one gitlink. It checks exact combined CI and the
2,307-case/230-suite inventory with590 test-source hashes. Current PR190, CDC
and Increment61 entries authenticate the live seal before historical replay.
The PR190 entry authenticates the integration reviewer before executing bytes;
it does not trust a Python bytecode cache.

Actual local checks:14 current integration tests,9 unchanged schema4 lifecycle
tests, original PR190 controls(3 shell/54 CI/48 live mutations), and original
sequential controls(14 catalog/7 layering/28 live/58 safety) pass. WA08 overlay
passes401 records; rollout composition and the native guard pass. The exact-source99
local-enable review also passed all66 mutations. Consult
`evidence/pr190-source-review-receipt.json` and the actual v2 logs for the
source/fixture identities; earlier source40 logs are retained separately. All25 publication source gates remain
mandatory in Actions; these local results are not Scala/hardware qualification.

## Next actions

1. Finish diagnostic35458181915. Independently verify both actual ZIP digests
   and run `verify-local-enable-pr190-diagnostic.py` in success mode with the
   exact controller/suite inventory and distinct extraction paths. All138
   cases/10 suites, fresh original RTL, strict tools/native/bounded proofs and
   actual mutants must pass on BOTH Scala versions. Supplemental formal stays
   explicitly not-run. No unbounded proof is established.
2. When the diagnostic and source review are successful, on clean99aacc05 use
   `build-local-enable-pr190-seal.py --repo-root 59i-dev --source
   99aacc05a783b9dc51aaeabd4e1395de385cab28 --review-reasons
   pr190-review-reasons.json`, first dry-run then `--stage`. Commit only its
   exact helper slot and manifest, with `[skip ci]`, then run the actual
   production verifier. Candidate catalog has345 source records(289 exact old
   records reused, none removed) and40 new target records. The full prior93
   target records stay mandatory through seal58fb replay.
   With these exact source/reasons, the validated catalog SHA256 is
   `8748c74e631d0de7bbd57f38eceabdf2b5f93176dad78f3fd31585d2e307dab4`
   and sealed tree is `e5c16951aa2538c21bb0299df36df353363b666e`.
3. Build a NEW final payload with `publication-controller-pr190/build.py` using
   source99, the legitimate seal, diagnostic sourceccec/run35458181915 and
   controller5f2d. **Do not publish the offline fixture payload or either
   provisional fixture seal196f3e1c6/a48bc678b.** Those exist only in disposable
   local worktrees for genuine current-source testing. The production source
   ancestry does not contain them.
4. The versioned publication controller preserves all21 earlier source gates
   and adds four current PR190/sequential gates (25 total). Its offline test
   reconstructed16 unpublished commits exactly,1847 regular runtime/checker
   files and one pinned gitlink. Existing target commits are pinned bundle
   prerequisites and are never recreated. Refresh real refs/PR before writes.
   Publish only the reviewed staging controller on the existing recovery
   branch. After actual25 source checks, validate retained receipts, create the
   exact Git trees in order, then validate exact-commit receipts and non-force
   fast-forward the existing feature. Preserve raw commit metadata/parents.
   Actions dispatches ONLY committed-head local-enable qualification.
5. After that mandatory source+bothScala+crossScala gate and retained artifacts
   pass, refresh the versioned full-CI map/manifest/evidence plan on the exact
   seal. `build_59i_pr190_ci_mapping.py`, `full-ci-controller-pr190/` and
   `full-ci-monitor-pr190/` expect53 workflows/150 required jobs/2 intentional
   skipped publishers/95 artifacts,2307 tests/230 suites. The added native
   sequential consumer runtime gate is deliberate integration coverage even
   though unchanged target-owned files do not appear in the PR diff. Existing
   branch-gated historical exclusions retain their exact predicates. All12
   dispatch failure-injection controls passed; no full-CI POST was issued.

The full-CI controller still requires successful committed-head local-enable
before any dispatch, freshly checks every historical prerequisite and exact
live ref, reuses successful/active same-head runs, refuses unresolved failures
and uncertain prior requests, and supports only explicitly diagnosed failed-job
retries. Read its versioned REVIEW.md. Keep draft/TODO unchecked until the whole
required fleet and actual evidence succeed. Only then complete and merge under
standing authorization, reporting source/seal/merge/run IDs and an actual
Scala/generated-Verilog example; no duplicate broad postmerge dispatch.
