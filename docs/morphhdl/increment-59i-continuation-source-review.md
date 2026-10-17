# Increment 59i: source-certificate composition after Increment 61

This is a source-review design and regression record, not completion of 59i or
hardware qualification. The controlling roadmap checkbox remains open.

## Immutable inputs and source lifecycle

The continuation joins the published 59i seal
`f43100e4899593eb5c9e78537a4dcf6f53f9c30f` and the completed Increment 61 target
`27af65abbee0d2334d6be7a6e4e2408b8af32fd9`, in that exact parent order. It does not
replace the published seal's history with another child of the original 59i
predecessor. The cumulative source inventory still uses
`954d9b2763b064dba60af71ad8fa509a9d7cada8` for existing historical projections.

Schema 1 and schema 2 retain their original rules. Schema 3 adds a fixed
`previous_seal` certificate and the complete target delta from the actual common
base, `61d1fe0dcac0b52856620944a2d7426fd1390a48`. The previous seal, source parent,
tree, manifest digest and normalized verifier digest are fixed code anchors,
not permissions inferred from a branch name or an editable receipt.

The unsealed continuation source must retain the previous manifest verbatim.
Its direct-child seal may change exactly the manifest and the verifier's single
manifest-hash slot. The new manifest binds every cumulative source change
except that separately authenticated manifest transition: paths, modes,
before/after hashes and reversible byte spans. Target-only changes must match
the explicit reconciliation inventory. An unchanged gap cannot be silently
included in a reviewed edit span.

Descendants retain the existing limited documentation transition. One normal
GitHub merge is allowed only when its target is an ancestor of the fixed,
reviewed target and its tree exactly equals the sealed feature tree. A newer
target, second integration merge, competing seal, or changed source requires a
new review; it cannot reuse this certificate.

## Both original certificates remain active

The continuation verifier authenticates the original 59i verifier at `f43100e4`
and the original Increment 61 verifier at `27af65ab` before executing either.
Each executes from a detached worktree containing only that fixed Git tree.
No current file, development import override, or fabricated result is inserted.
The worktree's HEAD, index and tracked files are checked afterward. Successful
immutable-object audits may be cached within a process; current manifest,
verifier, HEAD, index and worktree verification are not cached as authorization.

Increment 61's contract stays byte-identical to the target's certificate. Its
current reader first verifies the full schema-3 continuation and then runs the
unchanged, frozen Increment 61 certificate. Its self-test additionally executes
the original self-tests on that frozen target. This establishes source
composition, not that historical runtime tests qualify the new union.

## Historical audit views versus compilation source

The normal target projection continues to expose `27af65ab`. Older WA-08 and
WA-10 audits need an additional view of Increment 61's certified predecessor,
`7f355a859e7e88ca343e1ff82f261fb47b3311d0`. The original Increment 61 contract
pins that predecessor. The overlay adapter composes those two authenticated
views, accepting only exact current-target or exact predecessor bytes and
preserving unknown inventory entries for rejection by the caller.

This adaptation preserves the original WA-08/WA-10 contract hashes, final
source anchors and historical assertions. It does not remove Increment 61
source from the actual checkout. Compilation and runtime/hardware tests must
continue to use the current committed source, including one-file-per-component
publication and the retained 59i native-geometry integration.

## Regressions and limits

`test-increment-59i-continuation.py` operates on real history in a disposable
worktree. It covers the source/seal parent order, both original certificates,
the original Increment 61 self-tests, exact and idempotent projections,
unknown inventory sentinels, a normal GitHub merge, and rejection of changed
source, modes, manifests, helper code, missing seals, incomplete inventories,
forged spans, future targets and competing seal histories. It does not modify
or qualify the preserved production checkout. The existing schema-1,
schema-2, target-integration and widening suites remain enrolled separately.

The inherited-source workflow adds the continuation suite without replacing
any original command, matrix, time limit, or gate. Existing driver repairs and
both parent implementations are retained. Full inherited source/mutation
harnesses, the missing local-enable deliverables, all applicable dual-Scala
runtime/formal/tool gates, and the combined consolidated/split-output matrix
remain separate requirements before 59i can be completed or merged.
