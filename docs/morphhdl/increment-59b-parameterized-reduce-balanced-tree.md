# Increment 59b - Typed parameterized Vec reduceBalancedTree

## Status and dependency

**In progress. The controlling roadmap checkbox remains unchecked.**

Started from merged Increment 59a at
`2be259338b87ecc30b44e47498f7f09c368e50d0` on `parameterized-verilog`.
The integration branch already contains Increment 60b and WA-07; this work
must preserve them and incorporate the latest integration head before closure.

This first checkpoint contains typed publication geometry, executable native
semantic contracts, and concrete native RTL oracles. It does **not** yet connect
the native Vec callback boundary to graph capture/replay or emit a parameterized
reduction. A green checkpoint workflow is not completion of Increment 59b.

## Authoritative native semantics

The source is `TraversableOnceAnyPimped.reduceBalancedTree` in
`lib/src/main/scala/spinal/lib/Utils.scala`; the Data helper delegates to it.

For each non-final level, pair adjacent elements in source order. Apply the
operator to each complete pair, then apply `levelBridge(result, level)`. Pass
an unpaired last element directly through the same level bridge, without an
operator or invented neutral value. Levels start at zero. A one-element input
returns the exact original element without calling either callback. Empty
concrete collections retain their existing assertion.

There are exactly `N - 1` operator invocations and `ceil(log2(N))` active
levels. Applying a one-register bridge at every level delays every leaf by
that same number of cycles, including odd tails. No clock/reset latency is
added for `N = 1`.

Existing concrete Seq/Vec calls remain unchanged, including generic non-Data
callbacks and concrete non-associative operators. Restrictions on symbolic
replay must not become restrictions on those existing concrete calls.

## Typed publication geometry

`TypedBalancedReductionPlan` consumes the original typed count or the exact
identity-owned `ParameterizedVec` count before collection conversion. It
validates untouched declaration authority, then the active projected domain.
The entire admitted count domain must be finite, positive and Int-sized.

The finite schedule has at most `ceil(log2(maximumCount))` metadata entries.
Each entry retains symbolic input count, complete pair count, output count,
active-stage predicate and odd-tail predicate. A default count of one does
not remove stages needed by larger legal overrides. Stage input count is
`1 + (N - 1) / 2^level`; output count uses `input / 2 + input % 2` to avoid
overflow at `Int.MaxValue`. Each stage is derived directly from the original
count so expression text does not grow exponentially.

This is publication metadata, not a second reduction implementation: it does
not invoke callbacks, construct a native operator graph, emit operation-specific
RTL, or inspect component/signal/source names. WIDTH stays on the independent
Vec leaf shape; the planner must not combine independent WIDTH and COUNT roots
through a lossy single-root elaboration carrier.

## Result widths and callback scope

The native operator and level bridge are authoritative for result type and
width. The baseline covers width-preserving modular UInt addition, bitwise OR
and XOR, unsigned and signed minimum/maximum, and widening UInt addition.
For equally wide UInt inputs, the widening-add baseline result width is
`WIDTH + ceil(log2(N))`. Odd tails must not silently gain a different operation
or lose a required bridge when carrying narrower intermediate values.

Before symbolic publication is enabled, callback graph capture must establish
an exact replayable body and exact result/odd-tail shape rules. Unsupported
state, side effects, external drivers, ambiguous widths and non-associative
symbolic bodies must fail closed. Arbitrary Scala callback purity or
associativity is not inferred from a few samples. A safe, reviewed body subset
or explicit structural proof is required; a handwritten adder/OR-tree substitute
is not acceptable.

## Qualification matrix and evidence boundaries

The checkpoint matrix is WIDTH in `{1, 5, 8, 32}` crossed with COUNT in
`{1, 2, 3, 5, 8, 9, 16, 17}`: 32 concrete shapes per Scala lane.

The native oracle uses ordinary concrete `SpinalConfig.generateVerilog`, Vec
and the existing helper. Independent Python arithmetic supplies expected
values, including signed two's-complement comparisons, growing sums and
pipeline history. Stimuli exhaust small input spaces and include deterministic
corner cases, every operand position, alternating values and seeded random
vectors for larger spaces. Two independent generations must be byte-identical.

The checkpoint workflow runs both Scala 2.12.18 and 2.13.12, requires the exact
semantic/planner test suites to execute without skips, and checks the oracle
with Icarus Verilog-2001 simulation, Verilator strict-language lint and full
Yosys synthesis. A deliberate observed-sum mutation must produce a real value
mismatch. This is a **simulation mutation**, not the pending formal mutation.
The evidence explicitly records `native-oracle-only` and candidate formal
proof as `not-run`.

## Remaining completion work

- Preserve the exact Vec receiver through the native helper entry, with a small
  reviewed native-change manifest update and unchanged concrete descriptors.
- Capture the authoritative native operator/bridge graph by exact object
  identity; validate callback replay and shape/side-effect rejection.
- Connect staged replay to generic parameterized publication, preserving one
  strict Verilog-2001 module, both independent parameters, odd tails, singleton
  bypass, result widths and level-bridge semantics.
- Prove specialized candidate RTL against independently generated concrete
  native references, including sequential bridges, and obtain a genuine formal
  mutation counterexample. Missing tools, timeout, UNKNOWN or parse errors
  are not proof or valid mutation outcomes.
- Run final-head dual-Scala, strict lint/full synthesis, simulation, deterministic
  replay, approved-native-change, concrete compatibility and inherited gates.
  Check the roadmap only after implementation/review and all gates pass, then
  merge the qualified head into `parameterized-verilog`.
