# Increment 59b - Typed parameterized Vec reduceBalancedTree

## Status and dependency

**In progress. The controlling roadmap checkbox remains unchecked. Do not merge.**

Started from merged Increment 59a at
`2be259338b87ecc30b44e47498f7f09c368e50d0` on `parameterized-verilog`.
Merged 60c integration `75e581592334e2e596f6e1043beb9596cc20a99b` is now
included through `ebcc0f96ce359514e8580fed40e59058c378e86f`, preserving
60b, 60c, WA-07 and the reviewed native-source changes on both sides.

The implemented checkpoint includes typed topology planning, the native Vec
receiver boundary, exact callback/bridge capture, closed native graph
observation, conservative scalar operator-body replay, rejection tests and
independent native RTL references. The authoritative generic native reduction
algorithm is unchanged. **No production balanced-stage replay backend is
installed: a symbolic-count reduction still fails closed instead of publishing
a fixed carrier tree.** Passing a checkpoint does not complete Increment 59b.

Detailed operator contracts and hardware qualification scope are in
`increment-59b-operator-replay.md` and `increment-59b-operator-formal.md`.

## Authoritative native semantics

The algorithm is `TraversableOnceAnyPimped.reduceBalancedTree` in
`lib/src/main/scala/spinal/lib/Utils.scala`; the Data helper delegates to it.
For each non-final level, pair adjacent elements in source order. Apply the
operator to each complete pair, then apply `levelBridge(result, level)`. Pass
an unpaired last element directly through the same level bridge, without an
operator or invented neutral value. Levels start at zero. A one-element input
returns the exact original element without either callback. Empty concrete
collections retain their existing assertion.

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
count uses `1 + (input - 1) / 2` to avoid overflow and retain exact extrema.
Summing separate pair/remainder maxima would overstate an even-bound domain.
Each stage derives directly from the original count to avoid exponential text
growth. Tests cover every count in domains with maxima 2 through 32, the
largest positive concrete Int, and illegal non-default values.

This is metadata, not a second reduction algorithm. WIDTH remains on the
independent Vec leaf shape; independent WIDTH and COUNT roots must not be
combined through a lossy single-root elaboration carrier.

## Native receiver boundary and capture

`spinal.core.ElabBalancedReduction` is a neutral scoped internal dispatcher.
The library Data conversion preserves an exact symbolic-count Vec instead of
erasing it through `toSeq`. Concrete inputs retain the historical conversion
and native helper. The scoped backend is restored in a `finally` block.

`TypedBalancedReductionCapture` invokes the supplied authoritative native
reducer once on the audited finite carrier. It retains the Vec, typed shape,
operator/bridge operands and results, declarations and assignments by identity.
Ordered rows distinguish complete pairs and odd tails at zero-based levels.
The terminal result must be the exact final row or singleton input. Carrier
capacity is only a construction bound, never the emitted logical count.

Capture rejects writes to old signals, changed or removed old assignments or
declarations, new initializers on old registers, child hierarchy, foreign/null
results and invalid owners/shapes. Initializers remain `AssignmentStatement`
evidence rather than disappearing through a data-assignment-only snapshot.
The additive observer overload leaves the original four-argument descriptor
available and observes each completed callback before later callbacks run.

`TypedBalancedReductionClosedGraph` traverses every native data dependency,
including right-hand and mux branches, checks local drivers, rejects foreign
reads, unreachable state/effects and cycles, and freezes expression children,
literals, initializers, owner/scope/type/clock and retained-width identities.
Its observation is distinct from algebraic replay permission and expires at
native normalization. Concrete binary mux classes are distinguished from
multi-way multiplexer classes; unknown expression subclasses remain rejected.

`UnvalidatedBalancedReduction.requireReplayCertificate()` still rejects.
Capturing or observing a graph does not prove arbitrary Scala purity, callback
associativity, symbolic width transfer, bridge latency or post-phase safety.
The production dispatcher is not enabled by these observations.

## Closed scalar operator-body replay

`TypedBalancedReductionOperatorReplay` certifies actual captured bodies for
14 exact native primitives: Bool/Bits/UInt/SInt AND, OR and XOR, plus equal-width
modular UInt/SInt addition. Both original operands must appear exactly once
through transparent aliases. The result cone must consume every recorded
local declaration and assignment. State, foreign reads, unused local effects,
partial/conditional drivers, casts/resizes, widening arithmetic, unsupported
operators and fixed-width witness leakage are rejected.

A proof replays one body through a fresh instance of its exact native
expression class and the inherited `wrapBinaryOperator` algorithm. It does
not reexecute the Scala callback, construct a reduction tree or emit RTL.
Every replay checks live source identities and exact owner/type/width authority.
The certified symbolic width root is attached to the fresh result so chained
replay retains its authority rather than falling back to a concrete witness.
Mismatched fixed local widths are rejected even for concrete inputs, preventing
silent truncation from being treated as a transparent alias.

This is an operator-body subprofile, not a certificate of whole-stage
uniformity, arbitrary Scala closure purity or complete parameterized publication.

## Result widths and remaining callback scope

The native operator and bridge remain authoritative for type and width. The
concrete baseline includes modular UInt addition, OR/XOR, signed/unsigned
minimum/maximum, widening UInt addition and registered bridges. For equally
wide UInt inputs, the widening sum has width `WIDTH + ceil(log2(N))`.
An odd tail must not silently acquire an operator or lose a bridge while
carrying narrower intermediate values.

Min/max closure is not yet min/max replay permission. Widening shapes,
registered bridge replay, their clock/reset behavior, and stage-level validity
must be certified before production publication. Unsupported side effects,
external drivers, ambiguous widths and non-associative symbolic bodies must
fail closed. Arbitrary Scala purity is not inferred from sample executions.
A handwritten adder/OR tree is not an acceptable substitute.

## Source-review reconciliation

The native review adds only the core dispatcher and two mechanical library
entry changes; the generic `TraversableOnceAnyPimped` algorithm is unchanged.
Canonical `increment-55-native-change-review.json` now includes the already
reviewed 59b delta. The 60c/59b policies were reconciled per exact native path
against their common ancestor, rejecting overlapping conflicting changes.
The combined manifest was regenerated, and normal CI requires byte-identical
regeneration from the canonical policy. No guard was weakened.
Consumed templates, materializers and the one-time integration workflow were
removed. Regular qualification workflows are read-only.

## Qualification scopes

The native baseline matrix is WIDTH={1,5,8,32} crossed with
COUNT={1,2,3,5,8,9,16,17}: 32 concrete shapes per Scala lane. Its Python oracle
checks arithmetic, signed comparisons, widening sums and pipeline history;
two independent generations must match exactly. Icarus Verilog-2001 simulation,
strict Verilator lint and full Yosys synthesis are required. The native
baseline's observed-sum mutation is a simulation mutation, not formal proof.
Its evidence remains `native-oracle-only`, `typed_candidate_formal: not-run`.

The operator-replay hardware matrix separately elaborates ordinary native
reference and replay RTL at the same 32 concrete shapes. Each shape compares
all 14 admitted primitive outputs, including independent Bool inputs. The
candidate must execute exactly `14 * (COUNT - 1)` replay calls, with none at
COUNT=1. Both sides are independently checked against Python expectations,
linted and fully synthesized, and their repeated generation must match.
A Yosys miter proves equality for every input bit pattern. Its mutation must
produce a definitive counterexample and a VCD showing `bad=1`.

Operator hardware evidence explicitly records
`scope: concrete-native-operator-replay`, `parameterized_tree_formal: not-run`.
It is not formal evidence for a parameterized COUNT tree or registered replay.
Missing tools, parse errors, timeouts, UNKNOWN and skipped/cancelled jobs are
never passes or valid mutation outcomes.

### Verified checkpoints

- `5b8fd179e3c526bb0fbec6bf87572c10befff6fc`: run `33968641240`, job
  `101313111623`, passed the original 38 tests and 32 native RTL shapes on
  Scala 2.12.18 and 2.13.12, including the native source/retirement guards.
- `66dead6103379127c0c45342a183d8c5f6190bca`: run `33972344356`, jobs
  `101322972619` and `101322972577`, passed all 53 native/plan/capture/safety/
  operator-replay tests and all 32 native RTL shapes on both Scala versions.
  Its separate closed-graph suite exposed two min/max class-admission failures;
  `05bb331dbccb3d76d93aa548f334a076b41e2b28` corrects those exact classes, but
  that newer head requires its own results.
- `6152fc3bcb112f9df6db82f8ba2ff5b9c712ded3`: operator run `33972618993`,
  Scala 2.13.12 job `101323698543`, passed all 15 replay tests, 32 concrete
  native-replay miters, independent simulation/lint/full synthesis, deterministic
  regeneration and the formal mutation counterexample. Artifact `9971415115`
  retains the evidence. The Scala 2.12 job passed elaboration but was cancelled
  during HDL qualification when the branch advanced; it is not recorded as a
  pass. Both lanes must qualify the combined subsequent head.

Later commits need their own exact-head evidence. These historical records
must not be presented as completed qualification of a different head.

## Remaining completion work

1. Complete stage-level replay certification: whole callback uniformity and
   purity constraints, result/odd-tail width transfer, registered bridge state,
   clock/reset, and freshness across the relevant native compiler phases.
2. Install the production backend and connect generic staged replay to one
   strict Verilog-2001 definition with independent WIDTH/COUNT, singleton bypass,
   odd tails, exact result widths and native bridge semantics. Do not publish
   the finite carrier or add operation/component-specific tree implementations.
3. Prove parameterized candidate specializations against independently generated
   concrete references, including sequential bridges and a real formal mutation.
   The concrete operator-body matrix does not discharge this obligation.
4. Incorporate any newer integration changes and pass every applicable final-head
   dual-Scala, concrete compatibility, determinism, strict lint/full synthesis,
   simulation, formal and inherited gate. Only then check the roadmap and merge
   the qualified head into `parameterized-verilog`.
