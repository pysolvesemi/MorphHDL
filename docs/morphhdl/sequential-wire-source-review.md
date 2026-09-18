# PR190: native sequential consumer successor review

This records a bounded successor of the merged lane/condition repair. It is not
another feature increment and does not replace the historical proof obligations.
The target is `parameterized-verilog`; development stays on the existing
`agent/wa-sequential-wire-consumers` branch and PR #190.

## Immutable source boundaries

| Boundary | Commit | Tree |
|---|---|---|
| Reviewed predecessor | `f5049ae2abfe5a47cd1fac3574ea08d630bd183f` | `a189550421dd2eabd7ea82faaf2c3449960707ab` |
| Sequential implementation and regressions | `d9c3f574ef7dc036ebdfd8e2078a512c40c5aed9` | `c062abc4b2be7f932fb7b6efc3d1750276689d43` |

The exact introduction contains 15 paths. Four are compiler production files;
the others are the independent reproduction, tests and their focused workflow.
`check-sequential-wire-source-review.py` enumerates those paths, verifies both
immutable trees and the entire introduction, and pins the unchanged implementation
and test bytes. Its separately enumerated review paths permit only the source
review, native approval, signature integration, branch boundary and targeted CI
changes described here. There is no wildcard production exception.

## Reviewed native changes

`NamedWireExpressionNativeBridge.scala` distinguishes combinational blocking
writers from whole-register nonblocking consumers. It retains the existing
source-name provenance, preservation, dependency closure, receiver inventory,
rewrite-count and remaining-reference checks. Register targets must retain their
clock domain; partial writes or unsupported controls are rejected. No register,
assignment scope, initialization, reset, clock enable or branch priority moves.
Narrowing resizes are recognized as select uses, so named arithmetic slice bases
are not erased by general expression inlining.

`UnnamedWireAliasNativeBridge.scala` permits proven same-component input sources
and ordinary whole-register RHS consumers. It requires exact packed kind, width
and signedness, supported scopes, preservation metadata, source intent and no
cycle. The pre-liveness intent check applies to the identity regardless of its
immediate consumer: `.resized` creates a combinational clone before the eventual
narrowing register/output use. Explicit `vital` intent must survive that hop.
Whole-hierarchy reads participate in eligibility; unsupported uses retain the
helper. The historical no-argument constructor remains and the new sequential
profile fails closed without the inventory. Rewrites precede reference checks,
and helper removal follows those checks.

`WireAssignmentProductionBridge.scala` passes the existing pre-liveness inventory
to the native alias phase. The public MorphVerilog entry point and standard
default-enabled pipeline are unchanged. No application override is required.

`VerilogEmitterExpressionInlining.scala` proves a limited late select-base case:
an unannotated, identity-sized UInt/Bits resize whose chain ends at an existing
same-component fixed-width declaration. Kind and width must match at each link;
symbolic widths, signed resizes, casts, arithmetic and unknown metadata fail
closed. The named source is not erased or renamed. Repeated/priority consumers
may share this exact reference because it introduces neither arithmetic width
propagation nor procedural state changes. Arithmetic expressions still retain
their required Verilog-2001 select-base carriers.

## Evidence and limitations

Focused workflow run 35323749215 qualified the immutable implementation head
above. Its evidence artifact 10538761588 has SHA-256
`a2c94150ec6a184f6cbd52ff2b634a59ce7cdff76c6b7c6852803e9c42f11705`.
The logs record 103 passing Scala tests and 45 HDL checks: 24 timing simulations,
six sequential equivalence checks, 12 synthesis checks, a two-clock comparison
and two expected-model mutation detections. All 16 emitted-DUT hashes match the
receipt. The independent public reproduction retains PPC4 and its default zero.
Default/repeat/explicit-enable output is deterministic; disabled-flow behavior is
separately asserted. Reset, priority, multiple-use, signedness, truncation,
clock-enable and four-state cases are covered. No generated DUT is postprocessed.

These are implementation-head results, not a claim that a later review commit
has passed CI. The targeted workflow reruns the same actual source gates,
production checks and focused regression jobs on its own exact checkout.
Yosys uses async2sync for formal comparison; independent simulation checks
between-edge asynchronous resets. Arbitrary analog/event-order equivalence is
not claimed. Full repository qualification remains a separate merge requirement.

## Source audit integration

The original WA-08 base, lane source, Increment 61 source and integrated target
anchors are unchanged. The outer overlay's verifier implementation is unchanged
apart from its sealed manifest digest. Its canonical records bind every current
reviewed file, mode and before/after hash to an immutable source checkpoint,
HEAD, index and worktree. Unknown, ignored, staged, linked and foreign-source
changes remain rejected. Exact current bytes alone can enter historical
projection; a historical worktree is never substituted for current-source proof.

The lane/Increment61 union verifier retains all six Increment 61 production files
and all eight original lane production entries at their historical revisions.
Only the explicitly enumerated sequential production paths can take the new
qualified source bytes, with separate current checks for all four. The original
Increment 61 contract remains byte-for-byte unchanged. The 98-entry formal
source-signature registry keeps its schema and path set; the four actual
changed production digests, the two explicitly reviewed boundary-check/test
digests and the additive regression-catalogue digest are updated. Native approval records describe the
emitter's bounded backend obligation; the canonical native manifest is generated
by the existing, unchanged review-policy generator and must reproduce exactly.

The original lane safety/catalog/mutation tests and the original 25 current
integration rejection controls remain. Additional controls reject worktree and
committed mutations, partial rollbacks, hidden index changes, mode changes,
foreign source roots and altered projections for the sequential successor.
The predecessor's original 14-file/98-signature current review also executes
independently at the predecessor commit. Passing it does not waive current checks.

The branch-boundary exception is the exact existing repair branch, conditioned
on these verified path inventories. A lookalike branch and extra production
path are negative tests. Unrelated application sources and repositories are
outside this repair.

## Targeted-first CI operation

After deterministic source-review failures in full PR qualification, PR #190 was
temporarily closed so checkpoint pushes would not relaunch the full PR suite.
A one-time branch/head-scoped controller requested cancellation of unfinished
PR190 runs while retaining completed evidence and leaving other branches alone.
The final targeted workflow removes that cancellation code and its Actions write
permission; it uses read-only permissions.

Targeted CI first verifies current source, new rejection controls, the native
approval/canonical manifest and branch boundary. It then calls the unchanged job
graph of `increment-62-wa08-source-overlay.yml`: original source-overlay checks,
both Scala production checks and the cross-Scala comparison. A `workflow_call`
trigger is the only change to that inherited workflow. After those pass, it calls
the original sequential regression jobs. Narrow push paths suppress redundant
standalone focused runs on review-only commits; the default compiler passes and
PR regression trigger are unchanged. No original job command or dependency edge
is removed. Reopen the same PR for full qualification only after targeted CI
passes on the exact final head; never merge merely because historical or focused
checks passed.

## Additive full-regression enrollment

The completed Scala 2.12 inherited run also exposed a catalogue mismatch: the new
sequential suites were executed but were not yet enrolled in the exact source-
selected inventory. The original 2,029-test/198-suite lane catalogue is retained
unchanged. A separate flag requires the complete authenticated six-file test/
reproduction cluster and the lane predecessor before adding exactly 37 tests in
three new suites (6 core, 18 native and 13 retention). It cannot be selected from
XML presence. Partial source clusters, changed added-source identities and a
missing predecessor are rejected. The optional independent-parameter successor
composes additively as well. Original XML failures/errors/skips, suite identity,
case identity and exact-count checks are unchanged; no count is inferred from a
failed report to waive a missing test. Existing catalogue self-tests run along
with 14 new source/catalog negative controls.

## Complete standalone-root scanning

A subsequent local run of the unchanged independent-parameter source controls
found that `repro/remaining-wires/src/main` was absent from the closed Increment
54 layering inventory. The repair adds exactly that root as a scanned high-level
application root. It does not add a low-level exception or change any ownership,
retirement, build-graph, compiler-isolation, source-extension or forbidden-source
pattern. The existing all-source retired-sidecar rule gains the matching prefix
so the new directory receives the same checks as every other high-level root. The layering checker changes only its canonical manifest digest; the
successor verifier proves that exact one-root policy delta against the immutable
predecessor. All three standalone Scala sources are included in the live scan.

Seven additional controls reject a retired sidecar, duplicate typed owner,
unknown extra source root, removal of the enrolled root, promotion to a low-level
exception, deleted forbidden rule, and removal of Scala from scanning. The two
layering review files also gain live source-tampering controls (28 total). The
existing 44 independent-parameter Python source/evidence tests run unchanged in
targeted preflight, together with the original layering self-test and live audit.
