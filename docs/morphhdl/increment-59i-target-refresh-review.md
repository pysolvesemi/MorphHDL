# Increment 59i: reviewed target refresh and source-audit recovery

This is development-source review, not a completion or final-head qualification
record. Increment 59i remains unchecked. The controlling scope is
`parameterized-verilog-todo.md`; no RTL or proof gate is waived here.

## Immutable inputs

The published 59i predecessor is
`954d9b2763b064dba60af71ad8fa509a9d7cada8`. The recovered implementation source is
`bdfa5e06f0310cc9dcc64c42dc80a2f61c2475e9`, with archived seal
`7fa6daa6f22db7b3d459130a5021b5cc905de49d`. The separate recovery ref preserves
that source/seal unchanged. It is the 133-file / 407-span checkpoint, not the
later unidentified 135-file / 417-span state described in the handoff.

The target refresh is exactly
`61d1fe0dcac0b52856620944a2d7426fd1390a48`; its common base with the published
59i predecessor is `f06d9c412924b99cf2c76422549c375a571757dc`. These immutable
objects, not the current value of a branch name, define the review boundary.
The refresh includes WA-11 Boolean-width normalization and the merged PR #186
audit scheduling and native diagnostic-capture work and must not be discarded to make older audits pass.

## Runtime and conflict reconciliation

The 59i native/library algorithms remain those of the recovered implementation.
The runtime union adds the target's Boolean encoding provenance and normalized
round-trip rendering while retaining 59i's internal read-only native width
query. Neither change reconstructs erased symbolic origins from integer values.
The target's full-domain validation, signed integer sizing, and fresh projected
evidence checks remain present.

The native-change review policy retains every recovered 59i entry. The ElabInt
ownership record includes both increments. The native-source manifest is
regenerated with the existing guard from the combined committed native source,
not selected wholesale from either parent's incompatible root hashes.

The WA-10 scope checker retains the target's immutable FINAL_SOURCE inventory.
Its outer overlay still authenticates current source. The latest WA-08 overlay
contract is retained exactly; its 59i adapter restores the refreshed target view
instead of comparing its newer certificate against the obsolete target bytes.

## Exact source-seal topology

Schema 1 of `check-increment-59i-production-successor.py` retains the historical
single-parent source rule and all existing regression controls. Schema 2 permits
one explicitly reviewed source join, with ordered parents consisting of the
published predecessor and the exact target refresh above. The separate first
seal remains a direct child of that source, changing only the helper hash slot
and adding its manifest. No reseal or arbitrary target is accepted.

Both schemas still authenticate the complete source delta, ordered UTF-8 spans,
file modes, immutable Git trees, source/seal history, live HEAD/index/worktree
identity, and initialized submodules. Completion documentation exceptions do
not authorize source changes or provide proof results.

Schema 2 additionally inventories every path changed by the target refresh and
binds its exact target/source bytes and modes. Target-only changes must survive
byte-for-byte. Only these ten reviewed overlaps can differ from the target:

- the 59d widening and 59h nested-owner workflow files, retaining target timing
  changes and 59i enrollment/cache settings;
- `core/src/main/scala/spinal/core/ElabInt.scala`, retaining both native changes;
- the native review policy and regenerated native-source manifest;
- the WA-08 overlay helper, retaining the target's seal and authenticated 59i
  adapter with exact helper-code identity;
- the 59b, 59d and 59h inherited caller harnesses, preserving every original
  check and mutation while composing the two parents' positive-test budgets;
- the incoming audit-timeout unit tests, preserving all fourteen methods and
  adding four controls for the joined caller budgets.

Every overlap has its own explicit target-to-source reconciliation spans. The
existing historical predecessor projection remains available for the inherited
59i feature review chain. A separate target projection serves the latest
WA-08/WA-10 certificate. Both projections reject unreviewed bytes; neither
replaces the complete live source verification. A final GitHub merge must still
preserve the reviewed feature tree and cannot import a newer unreviewed target.

## Recovered reader repairs

The widening audit authenticates its pinned production successor before
restoring the immutable widening view. Full Git history detects removed seal
files even when a tree-preserving merge could hide its second parent's history.
The original widening file inventory, contract, hashes, and span reversals stay
unchanged.

The 60f integration reader uses `current_inherited_source` only when the
named-field review layer exists. Older combined checkpoints retain their direct
read. A missing projection API or failed authentication cannot fall back to
unauthenticated bytes. Both frozen callback integration files retain their
original hashes and reversal checks.

## Measured scheduling repair

The saved complete 60f source-only development traversal, including its final
repeated profile, passed in 1621.921 seconds. It used two external development
reader modules against an unchanged archived seal. Its stdout SHA-256 is
`7d3e96fe4fe0e11a86acfc5f419d765e3536c0ca623a409d1d11b641f9e768bd`.
This is retained timing evidence, not qualification of this new source.

For a production-successor checkout, the original inherited 60f harness now
reserves 3600 seconds for its current positive case only. The historical
600/900-second positive selections and every 120-second historical/negative/Git
limit remain unchanged. All 18 original cases, exact checks, and failure
handling remain. No positive audit or repeated profile is removed or cached.
The 60f qualification job allows 180 minutes for both complete audits and its
RTL work; the full inherited 59i source job allows 240 minutes. Actual final-head
workflow timing still must be observed, and timeout is never treated as a pass.

## Required qualification

The original successor suite remains mandatory, together with the new target
refresh and scheduling controls, retained widening tests, widening adversarial
fixtures, and real-history 60f integration-reader tests. The original complete
60f inherited harness and all other inherited source/RTL gates must run on the
new sealed source. Synthetic controls and archived RTL are not substitutes for
fresh dual-Scala, simulation, formal, synthesis, determinism, baseline, Mill,
or final-head CI results. No completion checkbox or merge is authorized by this
review record alone.

## WA-08 inherited fixture ownership

The original WA-08 harness used `Bits.scala` as a path outside its own overlay.
That remains true for byte restoration, and its original restoration payload
and committed mutation are retained. The recovered 59i source now independently
owns its `asBits` width-provenance change. The complete inventory projection must
therefore remove that delta when returning the older target view. The fixture
now separately tests that cancellation against immutable Git history and uses
`BitVector.scala`, asserted to be outside all three authenticated inventories,
for the unrelated-inventory preservation assertion. No production projection
was changed to satisfy the test; all original mutation payloads remain. The
external development harness passed all 131 exact mutation controls before the
fixture update was incorporated into this source. Final-head execution remains
required.


## PR #186 reconciliation and retained scheduling controls

The earlier staged source `2375c64c81f5c19d08ad706634cd3484537e77b3` and its
seal `1541077ca85082409a6a870884d9ac8b377acdea` remain immutable recovery points.
The new target adds 15 commits and 16 changed paths after `3b547ae5`; none
changes `src/main`. Every incoming path is retained. The complete native runtime
and both native manifests are unchanged from the earlier staged source.

The pending `3d5e97ce94c185f19b2e331972f5ed4d2e0e8496` regression restoration is
included byte-for-byte: all eighteen source-budget tests, the historical portable
whole-harness fingerprint, and both additive workflow enrollments. The separate
six-test scheduling suite remains unchanged.

The two incoming 600-second 59b/59d positives call the same complete 60f traversal
already measured at 1621.921 seconds. They now select the previously reviewed
3600-second budget only when the successor manifest is present. Target-only
positives still select 600 seconds; historical/mutation/Git defaults remain 120
seconds. The 59h current-positive overlap retains 900 seconds for its joined
parent-union audit and the incoming 600 seconds otherwise; all historical and
negative 59h checks retain 180 seconds. Presence selects a finite resource budget,
not source authority: the executed audit must still authenticate the source.

The fourteen incoming timeout tests still execute their original wrapper and
entrypoint assertions against the correct target-only filesystem state. Four
additional tests cover both marker states, actual joined entrypoint propagation,
and failed or timed-out processes. No rejection case or proof invocation is
removed, and the 59d/59h workflow fields and commands from both parents survive.
This record does not assert full Scala, hardware, formal or final-head CI success.
