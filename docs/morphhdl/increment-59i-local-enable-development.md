# Increment 59i: composite local-enable development candidate

## Status — 18 September 2026

This is **uncompiled, unsealed development source**, based on published audit
seal `37d1629f9b78c4d9cd6646abee9962fdd046e413`. It is not an accepted source
certificate, a passing CI result, or completion of Increment 59i. The existing
committed-head local-enable gate is unchanged and must continue to reject this
candidate until the required review contract/checker/tests are implemented and
its legitimate successor certificate is issued. Do not add an unconditional
success reviewer or promote older CI results to this source.

The integrated target remains `27af65ab`; live target `f5049ae2` also still needs
reconciliation. The active audit run `35331945529` continues on its own unchanged
`37d1629f` source, not this development candidate.

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

## Tests and remaining qualification

`TypedBalancedReductionCompositeLocalEnableTests.scala` contains 21 test cases
(including loop-expanded cases). They use the actual native capture/replay
APIs and compare original/current/peer operand identities before normalization.
They cover independent field widths, odd tails, two-register composite chains,
behavior inequivalence, peer bounds, stale controls, reset mutation, external
reads, operand inventories, and the preserved scalar API restriction. The
public fixtures cover SYNC/high/rising and ASYNC/low/falling reset/edge profiles
in consolidated and one-file-per-component modes.

**These Scala tests have not been compiled or run in this continuation.**
Remaining work includes both Scala lanes, the complete reset/polarity/enable
profile matrix, nested-inner-Vec and generated-child combinations, independent
simulation/formal references, functional mutations, and all requirements in the
59i roadmap. This development subset is not the complete local-enable closure.
The existing mandatory review contract, checker and checker regression suite
are still required. No source-review hash was repinned to disguise those gaps.

Provenance: the anchored code transformations in the repository's retained
`morphhdl/repair-59i-composite-local-enable/apply.py` and `fix-generated.py` were
used as an **unqualified starting prototype**, then corrected for distinct
current/original dependency roles, legal own-chain register controls, and the
current publication observer. Their obsolete baseline pins and publication
scripts were not used to assert that the current source was qualified.
