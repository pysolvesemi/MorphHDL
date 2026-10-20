# 59i inherited-source audit scheduling

## Measured failure

Run 35306242993, attempt 1, checked out immutable seal
`969af59a0378fca5b964d04d8f2af49b123dd804` (tree
`86732918d3d071ec0b505c435f9df45f477f26b9`). Job 105478903638
started at 2026-09-18 04:15:04 UTC and was cancelled at 08:15:04 UTC,
matching the enclosing `timeout-minutes: 240` setting. This is not a claim
that an individual 3,600-second positive audit timed out.

The terminal log completed 59h's two positives/32 rejections, WA07B's
current/exact-B composition, the standalone full 60f source check, all
18 inherited 60f cases (two positives and sixteen rejections), and all
59f eight positives/122 rejection controls. The next original 59c inherited
harness was interrupted. The final artifact self-test and exact-parent
native/overlay audits had not run. The workflow was cancelled, not passed.

The retained GitHub artifact is 10537814127, SHA-256
`2bfc3102b3de3dbbb2d433314c04bf2c399a3e3b6837000a4fe72eea891b0b99`.
The full job log is the source for timestamps. The artifact preserves the
untimestamped audit log and exact head/tree, not a complete job-log archive.

## Scheduling-only repair

The same workflow partitions all 27 original audit commands, in their
original within-group order, into five groups: composition, 59h, WA07B,
60f, and 59f/59c. Every group gets the original committed-source preflight.
`max-parallel: 2` bounds active source jobs; `fail-fast: false` lets other
selected groups finish and retain evidence. Each group retains the original
240-minute job limit. No individual audit timeout, historical case,
negative diagnostic, Scala source, hardware gate, or native manifest changes.

Each literal original shell command has timestamped BEGIN/END markers.
Bash errexit/pipefail ensures a failed command cannot acquire its END marker
or be hidden by tee. A success-only step reauthenticates the live checkout
and creates a receipt for every expected command. Each attempt's artifact
has a distinct name; failures retain partial logs without a passing receipt.

The aggregate check keeps the old `source` check-run name. It runs even when
a dependency fails, explicitly requires `needs.source.result == success`,
and then validates the exact five-shard inventory, head/tree, workflow hash,
run identity, complete command markers, timestamps, and log digests. A
newer incomplete attempt cannot fall back to an older successful attempt.
A failed-job retry may retain earlier successful jobs from the SAME run and
head, with their original attempt numbers recorded. It never borrows an
old-head or different-run pass. After aggregation the four original native
and typed-overlay checks run on the same two frozen historical parents.

## Validation and completion boundary

The scheduling/evidence tests execute the actual generated shell blocks
with synthetic child commands. They exercise success, nonzero exits,
missing and malformed receipts, stale/mixed source, cancellations, duplicate
markers, and retry handling. They are not executions of the full inherited
source chain. The original command inventory is checked against the exact
969af59a workflow Git blob. Source-review and importer pins advance to retain
969af59a as an authenticated predecessor; verifier semantics do not change.

Only the previously failed inherited-source workflow should be dispatched
on the resulting sealed repair. Five successful replacements remain tied
to their own heads. Full CI, roadmap completion, and PR merge remain blocked
until all failed requirements (including composite local-enable) pass.
