# PR190-integrated 59i publication controller

This version targets exact4b8a86e25f5a1a3f0cb4c37dc537a8dd8aa7b097 and
source99aacc05a783b9dc51aaeabd4e1395de385cab28 through schema5. It requires
successful fresh diagnostic35458181915 on ccec54986 before publication.
No runtime or final qualification is claimed by this package.

The actual offline transport check preserves15 unpublished source commits;
its isolated provisional-seal reconstruction preserves16. Existing signed and
unsigned PR190 target history is excluded from recreation and required as an
exact pinned prerequisite. Unknown commit headers are rejected for newly
staged feature history; exact author, committer, timezone, message, ordered
parents and tree identities are preserved. The final legitimate seal is still
pending the diagnostic. Never publish the offline fixture payload or either
provisional fixture seal196f3e1c6/a48bc678b.

After independently validating both actual diagnostic ZIPs, use
build-local-enable-pr190-seal.py with the exact clean source99 and reviewed
pr190-review-reasons.json, commit its exact two-file seal with[skip ci], and
verify it. Then build a NEW payload using:

    python3 -B publication-controller-pr190/build.py --repo-root 59i-dev \
      --source 99aacc05a783b9dc51aaeabd4e1395de385cab28 --seal ACTUAL_SEAL \
      --diagnostic-source ccec54986c70077e361f291cd322f0aa547aee15 \
      --diagnostic-run 35458181915 \
      --diagnostic-controller 5f2d416138ace882dd8465a41b4187211f368ccc \
      --output publication-controller-pr190-ready

Inspect the build receipt, exact payload and actual runtime comparison. All1847
regular runtime/resource/build/hardware-checker files and the separate pinned
gitlink must equal diagnostic sourceccec. No source qualifier or filename can
substitute for this identity check.

Publish stage.yml as the existing
.github/workflows/increment-59i-local-enable-publication-stage.yml and the
reviewed generated stage.py/payload.json/parts under
.github/recovery/59i-local-enable-publication/ on the existing recovery branch.
Do not modify feature or target during this controller publication. The old
schema4/e0e9 controller remains historical and must never be retried.

The Actions controller has contents:write and actions:write, but implements no
remote ref update. Its first phase reconstructs exact history, runs the actual
original308-record parent audit, verifies current diagnostic/runtime identity,
initializes exact gitlinks, and executes25 mandatory current-source commands.
It retains every original21 gate and adds the integration reviewer, its14
current topology/source controls, the complete PR190 sync controls and the
complete sequential source/safety controls. No gate is optional.

After those checks pass, it uploads only authenticated blob objects and writes
connector-tree-requests.json. Download and independently validate the actual
artifact digest, source/seal, all25 command identities/zero returncodes/log
hashes and complete payload/history. Create every requested Git tree through
the connector in listed order; every returned SHA must equal the expected SHA.
Do not update the feature yet. The controller waits at most60 minutes.

It then creates exact Git commits and retains exact ordered-parent/tree/SHA
receipts. Download and verify those receipts, refresh actual feature/target/PR
state, and non-force fast-forward only the existing feature from90b to the
legitimate seal. The controller waits at most60 minutes for this reviewed
connector handoff. It rechecks source receipts, exact refs and diagnostic
success before dispatching ONLY increment-59i-local-enable-committed-head.yml.
It reuses any existing same-head dispatch and never retries a failed run.

Each wait is an actionable handoff when its artifact is available. Complete
both handoffs in that monitoring iteration; do not delay one an extra hour.
The overall Actions job remains bounded at180 minutes. All original command
budgets and history/source checks remain; full CI is never dispatched here.

A timeout, failed check, moved target or changed feature is a failure requiring
inspection. Preserve existing objects/refs. Never bypass checks, reconstruct
history with default connector metadata, force push, or retry stale payloads.
See59i-pr190-source-review-resume.md for the complete diagnostic, full-CI and
completion sequence. Read review-receipt.json for actual offline results and
remaining remote gates.
