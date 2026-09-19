# Increment 59i: composite local-enable development candidate

## Status — 19 September 2026

This is development source for the remaining composite local-enable requirement
of PR #177. The published qualified parent is
`90b7fc8f13f2c53dbb6f7f8ab51f4e43cd486be6`; its target includes the merged PR #189
and PR #187 at `e0e9f1d7089d3aa513677a2b94c63eb4a7a7791d`.
The preserved development merge `d76fbd5f84869ac56186b36f35dfc3c480a80cbb`
retains both the qualified parent and the earlier prototype history.

The initial development diagnostic, run `35421115525`, executed 128 tests in ten
suites on each Scala version: 124 passed and four public-emitter fixtures failed
before emission because they disabled `headerWithRepoHash`. Successor
`38e80295d8ec0268f752adf43c4cb22e442ce498` repairs that fixture configuration;
the production configuration guard and all four reset/publication cases remain.
Its targeted replacement, run `35427383974`, passed all 128 cases in ten
suites on both Scala versions. Downloaded ZIP digests, every real XML testcase
identity, zero failure/error/skip counts, and before/after source inventories
were verified. Each artifact retained 24 files. This validates the fixture-only
source; later runtime and hardware additions require their own execution.
Source and run identities are separate from the recovery controller identity.

Subsequent development preserves whole-record native enable scopes and rejects
controls from nested Vec lanes that can be absent under legal overrides. The
mandatory local-enable review contract, checker and checker regressions now
exist. Their source-review checks do not establish Scala or hardware correctness.
A legitimate successor seal and the mandatory committed-head qualification are
still required before this remaining failed requirement is considered passed.
Full final-head CI follows only after that qualification; the increment remains
unchecked until every applicable gate passes.

## Native dependency model

Data remains on its corresponding recursive composite leaf. A register enable
may use a closed Boolean predicate over its exact current data-chain driver and
original scalar leaves of the same composite input. The proof uses native
identity and recursive leaf position; no rendered names or witness-width guesses
establish a binding. Supported predicates retain the existing Boolean literal,
Boolean input, fixed-bit/high-bit, NOT, AND, OR and XOR grammar.

`CurrentData` and `CompositeInput(index)` are deliberately different proof
nodes. Original inputs remain immutable through replay; only the current data
argument advances through the register chain. The distinction also participates
in behavior equality and control-width obligations.

An earlier saved prototype reused the data leaf's integer control slot and
updated that slot after each register. This changes the meaning of, for example:

```scala
val first = RegNext(value.unsigned) init U(0)
val second = RegNextWhen(first, value.unsigned(0)) init U(0)
```

The enable must read the original `value.unsigned`, not `first`. With reset
values zero and successive inputs 2 and 1, the correct second register becomes
2 on the second edge. The conflated prototype instead holds zero. A Boolean
version with successive original inputs true and false also diverges. These
are deterministic counterexamples, not reports of an executed RTL simulation.

## Preserved rejection boundaries

The ordinary two-argument scalar certification entry point retains its existing
current-driver-only enable grammar. Only the composite-control entry point
admits original peer inputs. Registered peer intermediates, foreign signals,
cross-field **data** movement, differing leaf register counts, mismatched native
clocks, unknown scalar classes, stale owner/type/width evidence, unsupported
conditional scopes and effects outside the captured callback remain rejected.
Current-driver registers are not misclassified as registered peers.

Fixed control-bit minima use the referenced control's own width; a high-bit
access follows its retained high-bit provenance at replay. Active COUNT
conditions retain the existing domain-sensitive width checks. The original
initializer parser, clock/reset validator, scalar data-chain inspection and
closed-graph admission/freshness implementation have not been replaced.

Both ordinary and widening composite publication now inventory assignments
inside the exact captured native statement trees. This retains register-enable
When bodies in the freshness observation while preserving the existing pair
versus tail observation modes and all capture operands. It neither searches for
missing drivers elsewhere nor invents a new admission rule.

## Shared scopes and nested control activity

An ordinary `RegNextWhen(record, record.valid)` has one native `When` scope
containing assignments to multiple scalar fields. Its complete closed callback
owns that scope. Each scalar proof authenticates a projection with the original
flattened operand order, exact result identity, a declaration/statement subset,
and every original driver and initializer of each selected declaration. The
proof retains the complete observation, so sibling mutation is still detected.
The scalar certification entry point retains its original strict behavior.

A control dependency must exist whenever its result field exists. Bridge
control reads use the same nested-dimension dominance check already required
for operator data reads. An always-present result cannot depend on an optional
inner lane, and equal witness sizes do not equate independent dimensions.
Inactive transport lanes being zero-filled is not authority to admit such reads.

Equal register counts establish structural pipeline depth. Independently enabled
fields can stall separately; `latencyFor` does not promise fixed transaction
latency when local enables are present.

## Authored tests and qualification scope

`TypedBalancedReductionCompositeLocalEnableTests.scala` contains 31 cases,
including loop-expanded cases. They exercise native capture/replay APIs and
compare original/current/peer identities before normalization. Coverage includes
independent field widths, odd tails, two-register chains, behavior inequivalence,
control bounds, stale controls, reset mutation, foreign/registered peers, operand
inventories, preserved scalar restrictions, nested active dependencies, widening
sum/product bridges, and shared whole-record enables in both publication modes.
The added cases require real execution; source inspection is not a passing test.

The independent hardware fixtures use ordinary concrete Spinal references and
wiring-only interface adapters. Qualification includes reset polarity/edge and
global-enable behavior, original/current control discrimination, parameter
overrides, split/consolidated publication, nested generated children, field and
packed profiles, widening, captures, saturation, actual RTL mutations, strict
Verilog-2001, lint/synthesis, and explicitly bounded formal comparisons. Record
actual executed counts and scope from the resulting receipts; authored matrices
are not evidence that those cases passed.

The local review contract records exact reversible source spans and preserves
the qualified predecessor. Schema-4 successor history retains the original
certificates and preserved development merge. Historical schema-3 tests execute
unchanged against their authenticated immutable source, while current source
checks and new successor tests separately validate the current revision. Scala
and hardware compilation always use the actual candidate source.

Provenance: retained historical repair scripts supplied an unqualified starting
prototype. Their obsolete baseline pins and publication scripts are not used to
assert qualification of this implementation.
