# Increment 60g — merged checker compatibility

## Integration boundary

This follow-up preserves target `64e8fddc432e859b6b532540bee96c5608d46efa`
(PR #176) and the published 60g source at
`8db3f8858200ade550f629855a8125fe03351897`. It recovers local diagnostic repair
`0abb9901fd267f397875c25d3342e6aa1fc28f63`.

There is no Scala production, native emitter, independent reference arithmetic,
public golden or behavioral-proof change relative to the published 60g head.
The old public golden patch is not reapplied. The target's existing WA-07b
compatibility workflow, immutable manifest, source profiles and 17-suite future
pass inventory remain required. This integration does not implement WA-07b.

## Correct rejection, wrong expected diagnostic

The 59h mutation deliberately changes the inherited 59e source. The outer 60g
inventory correctly rejects that unauthorized edit before the older 59g
inventory runs. The test now expects that precise outer diagnostic only when
the rollout is present. It still requires a failing process and the exact
expected category; it retains every mutation and independent historical replay.

The same rule applies to changed or removed pass sources: the independently
sealed pass reviewer may reject those bytes first. The 59g controls retain all
mutations and require its `unreviewed production delta` category in that case.

## Exact layer composition

The current 60g ledger is rebased onto the exact merged target. Its entries
reverse only reviewed 60g spans, and the original WA-07b adapter then checks
and reverses its own complete frozen spans. Unchanged gaps, complete before and
after hashes, source inventories, modes, ancestry, index and current bytes all
remain checked. Neither reviewer calls the other's full gate recursively.

The outer 60g gate also supports the target's already-qualified future pass
profile. Before projecting its three-file delta, the pass reviewer verifies the
entire committed main/test inventory. WA-07a comparisons use the exact restored
historical bytes, while HEAD/index/worktree comparisons still use actual current
bytes. Partial upgrades, arbitrary sources, changed manifests and hidden staged
changes remain errors. The semantic reference and public-output paths are not
part of this adapter.

The synthetic WA-only controls receive the exact restored original adapter
bytes, not combined source that lacks its required outer reviewer. The real
combined checkout and a separate temporary checkout containing the exact
qualified future pass are checked independently. Their regression inventories
retain all 60g obligations alongside the original pass obligations.

## Qualification

Run the current 60g source audit and self-tests, complete inherited source
chain, 59g/59h corruption controls, and `test-wa07b-inherited-review.py` before
publishing this repair. Full current-head CI remains the merge gate. Earlier
Scala and hardware successes identify their original commits and are not
relabeled as a new integration result.

The implementation-completion records for 60g and parent 60 are retained. This
checker-only repair does not add a new generated-Verilog feature; the actual
Scala/Verilog demonstration remains in the 60g default-rollout record.
