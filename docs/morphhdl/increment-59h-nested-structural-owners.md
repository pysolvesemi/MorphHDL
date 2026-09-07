# Increment 59h — Balanced reduction in nested typed owners

Status: implementation and qualification in progress. The controlling roadmap
checkbox remains open until review and all applicable final-head gates pass.
This branch started from merged `parameterized-verilog` commit
`0018da2740645e0ac0c419ded7b67c01622d2bb7`.

## Scope

The native `Vec.reduceBalancedTree` algorithm remains authoritative. The
extension places its certified scalar templates and balanced transport in the
exact typed generate-if, generate-case or finite generate-for owner where the
helper was called. Ordinary child components retain their own module scope,
canonical parameter bindings and native clock/reset behavior.

The standalone qualification uses the existing scalar operations and zero-init
register bridges from 59b. Named-field, widening, composite-reduction and nested
aggregate-alias combinations remain the 59i integration scope. Unsupported
packed operations on a nested structural Vec aggregate alias remain rejected.

## Ownership and publication

- Lexical handles retain exact component and capture identities independently
  of the reduction count's parameter root.
- Registered region identities and their enclosing capture identities reject
  replaced branches, sibling migration, detached captures and stale retries.
- Exact case capture retains the selector and admitted root values for each
  block. Registration checks the literal/default pairing. Later freshness
  checks re-enter only the immutable domains retained by the original owner.
- A narrowed count uses an identity-preserving prefix of the original native
  carrier. The complete Vec and all original leaf evidence remain retained;
  no callback runs for carriers excluded by the branch's admitted count.
- Before native normalization, anchors and replay templates must belong to the
  exact direct owner. A reduction result cannot be consumed outside that owner
  or its descendants.
- Existing structural driver, branch-reference and finite-index validation
  runs over the full native graph. The publisher then replaces the certified
  templates inside that exact block, after index substitution. Each reduction
  must be consumed exactly once.
- Native expression and register emission remains authoritative. The new
  publisher emits only balanced transport and structural control.
- After a scoped tree replaces its checked synthetic zero drivers, expression
  publication recognizes those exact private anchors as already consumed.
  User-authored zero assignments retain the existing lineage checks.
- A retained scalar static-Vec-index assignment wrapper is admitted only when
  its complete wrapper chain proves the exact native scalar and Vec identities.
  Opaque assignment hooks still reject before executing callback code.

## Qualification

`TypedBalancedReductionNestedOwnerArtifactWriter` generates five candidate
profiles and independent concrete native references: nested conditional,
finite loop, ordinary hierarchy, registered loop and generated hierarchy.
The loop profiles use scalar Vec inputs plus independent row biases and nested
finite reads, without a handwritten candidate datapath.

`check-increment-59h-nested-owners.py` checks strict Verilog-2001, independent
simulation, synthesis, native-reference equivalence, reset entry and induction
for registered cases, and wrong-branch, stale-index and cross-instance RTL
mutations. The inherited 59b qualifications and both supported Scala lanes
remain required. Final source-bound results and actual generated RTL examples
will be recorded here after qualification completes.
