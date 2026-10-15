# Increment 59i development integration of merged PR190

This is an unsealed development checkpoint, not completed source review or
runtime qualification. Increment 59i and PR177 remain incomplete.

The preceding source `545134a42dd200c5cee679db5dde0fea0acb15c9` passed the fresh
two-Scala diagnostic `35449602915`, with all 138 tests per lane, retained RTL,
bounded proofs, and real mutation checks independently verified. Its separate
two-file seal is `58fb59773a2deebba0251b5b626c19a22453f0a4`. Those results apply
to that source only.

Source publication run `35456999564` stopped at its live target guard before
any current-source gates or remote source publication: `parameterized-verilog`
had advanced to PR190 merge `4b8a86e25f5a1a3f0cb4c37dc537a8dd8aa7b097`, tree
`ebe59eecbc8f550d265e78c717fb093603329055`. PR177's API base SHA still reported
the older target; direct branch references must be checked before publication.

This development merge preserves the exact local-enable seal as its first
parent and the exact PR190 target as its second parent. All compiler and Scala
test changes merge without textual conflict. The four conflicts concern the
native source tree hash, CDC/Increment61 reviewer composition, and the complete
regression inventory's report projection and aggregation.

The complete 59i inventory now retains PR190's six emitter tests, 18 native
sequential tests, and 13 retention tests: 2,307 expected testcases in 230 suites,
with 590 test-source hashes. These are source expectations, not executed results
on this merge. A single authenticated inventory owns each copied report; the
59i path checks all identities and preserves the immutable old catalogue, while
the original PR190 path remains available when no 59i certificate is present.
Every copied suite must match its untouched current XML before projection.

The production successor helper is explicitly `UNSEALED`, and its predecessor
manifest remains byte-identical to the preceding seal. The reviewed successor
lifecycle, target projections, inherited reviewer routing, and final source
seal still need to be extended for this exact merge. Existing gates must reject
the development source until that work is reviewed and sealed. No previous
success is relabeled as qualification of this combined source.

Next: run the affected dual-Scala local-enable diagnostic on this exact merged
runtime, finish the new target/source review and its rejection controls, then
publish the preserved history and run committed-head local-enable qualification.
Full final-head CI and completion remain after that gate.
