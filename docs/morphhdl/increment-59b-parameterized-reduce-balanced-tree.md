# Increment 59b - Typed parameterized Vec reduceBalancedTree

## Status and dependency

**In progress. The controlling roadmap checkbox remains unchecked. Do not merge.**

Started from merged Increment 59a at
`2be259338b87ecc30b44e47498f7f09c368e50d0` on `parameterized-verilog`.
The branch preserves merged 60b and WA-07 and must incorporate the latest
integration head before final feature qualification.

The implemented checkpoint includes typed topology planning, the native Vec
receiver boundary, exact callback/bridge graph capture, rejection tests, and
independent concrete native RTL oracles. The authoritative native reduction
algorithm is unchanged. **No production replay backend is installed: a
symbolic-count reduction still fails closed instead of publishing a fixed
carrier tree.** A green checkpoint workflow is not completion of Increment 59b.

## Authoritative native semantics

The algorithm is `TraversableOnceAnyPimped.reduceBalancedTree` in
`lib/src/main/scala/spinal/lib/Utils.scala`; the Data helper delegates to it.

For each non-final level, pair adjacent elements in source order. Apply the
operator to each complete pair, then apply `levelBridge(result, level)`. Pass
an unpaired last element directly through the same level bridge, without an
operator or invented neutral value. Levels start at zero. A one-element input
returns the exact original element without calling either callback. Empty
concrete collections retain their existing assertion.

There are exactly `N - 1` operator invocations and `ceil(log2(N))` active
levels. A one-register bridge delays every leaf by the same number of cycles,
including odd tails. No clock/reset latency is added for `N = 1`.

Concrete Seq/Vec calls retain their existing path, including generic non-Data
callbacks and concrete non-associative operators. Symbolic replay restrictions
must not become restrictions on those concrete calls.

## Typed publication geometry

`TypedBalancedReductionPlan` consumes the original typed count or the exact
identity-owned `ParameterizedVec` count before collection conversion. It
validates untouched declaration authority, then the active projected domain.
The entire admitted count domain must be finite, positive and Int-sized.

The finite schedule has at most `ceil(log2(maximumCount))` metadata entries.
Each retains symbolic input count, pair count, output count, active-stage and
odd-tail predicates. A default count of one does not remove stages needed by
larger legal overrides. Stage input count is `1 + (N - 1) / 2^level`; output
count uses `1 + (input - 1) / 2` to avoid overflow and preserve exact interval
extrema. Summing separate pair/remainder maxima would overstate an even-bound
domain. Each stage is derived directly from the original count to avoid
exponential expression growth. Tests cover every count in domains with maxima
2 through 32, the largest positive concrete Int, and illegal non-default values.

The planner is metadata, not a second reduction algorithm. WIDTH remains on
the independent Vec leaf shape; independent WIDTH and COUNT roots must not be
combined through a lossy single-root elaboration carrier.

## Native receiver boundary and graph capture

`spinal.core.ElabBalancedReduction` is a neutral, scoped internal dispatch
boundary. The library Data conversion preserves an exact symbolic-count Vec
rather than first erasing it through `toSeq`. Concrete inputs continue through
the existing conversion and native helper. The scoped backend is restored in
a `finally` block, including when a callback throws.

`TypedBalancedReductionCapture` invokes the supplied authoritative native
reducer once on the audited finite carrier. It does not copy the reduction
algorithm or synthesize an operator-specific tree. The carrier capacity is
only a construction bound; it never becomes the public count.

The capture record retains the original Vec, typed count/leaf shape, exact
operator operands/results, level-bridge operands/results, native declarations,
and native assignments by object identity. Ordered rows distinguish complete
pairs from odd tails and retain zero-based native levels. The terminal result
must be the exact final row result or singleton input. The receiver's owner,
leaf paths, type objects, widths and declaration authority are checked.

Capture rejects writes to pre-existing signals, replacement/removal of native
assignments or declarations, added initializers on an existing register,
child-hierarchy creation, foreign/null results and invalid owners/shapes.
Initializer assignments are retained as `AssignmentStatement`, not discarded
by a narrower data-assignment-only snapshot.

The record is deliberately named `UnvalidatedBalancedReduction`.
`requireReplayCertificate()` always rejects it. Graph capture alone does not
prove arbitrary Scala purity, callback associativity, width-generalization,
state/reset legality or safe replay. These are still required before production
publication can be enabled. Tests use a scoped inspection backend only; its
native carrier output is not a parameterized candidate or formal reference.

The native manifest preserves every previously reviewed edit and adds only
the core dispatch support and two mechanical library entry changes. The
existing generic `TraversableOnceAnyPimped` algorithm remains unchanged.
Temporary source templates and the write-enabled materialization workflow were
removed after the implementation was installed and tested. The regular
checkpoint workflow is read-only and tests the actual committed sources.

## Result widths and callback scope

The native operator and level bridge remain authoritative for result type and
width. The concrete oracle covers width-preserving modular UInt addition,
bitwise OR/XOR, unsigned and signed minimum/maximum, and widening UInt addition.
For equally wide UInt inputs, the widening-add baseline result width is
`WIDTH + ceil(log2(N))`. An odd tail must not silently acquire a different
operator or lose a bridge while carrying narrower intermediate values.

Before symbolic publication, replay validation must establish an exact safe
body, result/odd-tail shape rules, and state/clock/reset behavior. Unsupported
side effects, external drivers, ambiguous widths and non-associative symbolic
bodies must fail closed. Arbitrary Scala purity or associativity must not be
inferred from a few samples. A reviewed body subset or explicit structural
proof is required; a handwritten adder/OR-tree substitute is unacceptable.

## Qualification matrix and evidence boundaries

The native oracle matrix is WIDTH in `{1, 5, 8, 32}` crossed with COUNT in
`{1, 2, 3, 5, 8, 9, 16, 17}`: 32 concrete shapes per Scala lane.

The oracle uses ordinary concrete `SpinalConfig.generateVerilog`, Vec and the
native helper. Independent Python arithmetic checks signed two's-complement
comparisons, growing sums and pipeline history. Stimuli exhaust small input
spaces and exercise corner cases, every operand position, alternating values
and seeded random vectors for larger spaces. Two independent generations must
be byte-identical.

The checkpoint requires four suites per Scala lane: 8 native semantics tests,
8 typed planner tests, 10 capture tests, and 12 safety/concrete-parity tests.
The parity test compares patched concrete Vec routing byte-for-byte with the
original generic helper at all eight count boundaries, including a registered
level bridge. A suite that is absent, incomplete, skipped or failing is not a
passing checkpoint.

The native RTL matrix is checked with Icarus Verilog-2001 simulation,
Verilator strict-language lint and full Yosys synthesis. A deliberately
mutated observed sum must produce a real value mismatch. This is a
**simulation mutation**, not the pending formal mutation. Generated evidence
continues to say `native-oracle-only` and `typed_candidate_formal: not-run`.

### Verified native capture checkpoint

Implementation commit `5b8fd179e3c526bb0fbec6bf87572c10befff6fc` was tested and
published by workflow run `33968641240`, job `101313111623`. Both Scala
2.12.18 and 2.13.12 passed all 38 tests and all 32 native configurations,
including deterministic generation, simulation, strict lint, synthesis and the
simulation mutation control. Native source and production-retirement guards
passed. Later commits must obtain their own exact-head qualification; this
record does not claim passing results for untested subsequent changes.

## Remaining completion work

1. Validate native callback graphs for replay, including generic supported
   operators, associativity, purity, external dependencies, result widths,
   odd-tail shapes, sequential bridge state, clock/reset and retained evidence
   freshness across compiler phases.
2. Install the production backend and connect generic staged replay to one
   strict Verilog-2001 definition with independent WIDTH/COUNT, singleton bypass,
   odd tails, exact result widths and level-bridge semantics. Do not publish the
   finite native carrier or add operation/component-specific implementations.
3. Prove candidate specializations against independently generated concrete
   references, including sequential bridges, and obtain a genuine formal
   mutation counterexample. Missing tools, timeout, UNKNOWN and parse/tool
   errors are neither proofs nor valid mutation outcomes.
4. Incorporate the latest integration branch and pass all applicable final-head
   dual-Scala, concrete compatibility, determinism, strict lint/full synthesis,
   simulation, formal and inherited gates. Only then check the roadmap and
   merge the qualified head into `parameterized-verilog`.
