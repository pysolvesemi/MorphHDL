# Increment 59b - Typed parameterized Vec reduceBalancedTree

## Status and dependency

**Implemented publication path; final qualification is in progress. The controlling
roadmap checkbox remains unchecked. PR #157 must remain unmerged until the final
combined head passes every applicable gate.**

Work began from merged Increment 59a at
`2be259338b87ecc30b44e47498f7f09c368e50d0` on `parameterized-verilog`.
The branch includes the reconciled 60c changes and merged 60d through
`c29b683aa`; inherited 60c/60d rejection controls were exercised separately in
`6ae8b1f7a`. Later implementation needs its own final-head qualification.

`MorphVerilog` now installs `TypedBalancedReductionBackend` around native
elaboration. A supported symbolic-count `Vec` reduction creates one
parameterized Verilog-2001 definition with independent WIDTH and COUNT.
The backend captures the unchanged generic native reduction, validates its
callbacks and every native row, builds distinct native scalar templates, and
places their native RTL inside symbolic balanced stages. It does not substitute
an operation-specific tree or emit its own arithmetic or register process.

Frozen implementation source `13967f2c51c808bea29037b9562dd5506457cd24`
passed the canonical SBT suite locally: 133 tests on each of Scala 2.12.18 and
2.13.12. Both lanes also passed the stronger 32-specialization publication
matrix with five outputs and independently unconstrained unsigned, signed,
Bits and Bool inputs. Strict tools, full synthesis, simulation, reset-entry
proofs, unbounded equivalence and two actual generated-RTL mutation controls
passed. Final PR CI remains pending; these local results do not authorize merge.

The publication architecture and supported scope are specified in
[increment-59b-publication.md](increment-59b-publication.md). Separate native
operator and stage contracts remain in
[increment-59b-operator-replay.md](increment-59b-operator-replay.md) and
[increment-59b-stage-replay.md](increment-59b-stage-replay.md).

## Authoritative native semantics

The algorithm is `TraversableOnceAnyPimped.reduceBalancedTree` in
`lib/src/main/scala/spinal/lib/Utils.scala`; the Data helper delegates to it.
At each level it pairs adjacent inputs in source order, invokes the operator
on each complete pair, and invokes `levelBridge(result, level)`. An odd last
input passes through the same level bridge without an operator or an invented
neutral value. Levels start at zero. A singleton returns its exact original
input and calls neither callback. Empty concrete collections retain their
existing native assertion.

There are exactly `N - 1` operator invocations and `ceil(log2(N))` active levels.
Every leaf traverses the same active bridge levels, including odd tails.
One register per active level therefore gives `ceil(log2(N))` enabled-edge
latency; N=1 introduces no clock/reset latency. Concrete Seq/Vec calls retain
their native generic behavior, including callbacks outside the symbolic replay
profile. The new callback-code policy applies only to symbolic-count dispatch;
a singleton-only typed domain also bypasses both callbacks and that policy.

## Typed topology and width authority

`ElabBalancedReduction.sourceSeq` preserves the exact symbolic-count Vec before
Scala collection conversion can erase its depth. The scoped backend is restored
in a `finally` block, including rejected elaborations and native retries. Non-singleton publication
currently requires the native component scope. A reduction inside an outer typed
generate/capture owner fails closed with the nested-owner or ownership diagnostic;
an ordinary child component can own a reduction in its own native component scope.

`TypedBalancedReductionPlan` checks untouched declaration authority and the
active projected count domain. The complete admitted domain must be finite,
positive and Int-sized. Its schedule has at most `ceil(log2(maximumCount))`
entries. Each retains the symbolic input count, pair count, output count,
active-stage condition and odd-tail condition. Default COUNT=1 retains all stages
needed by larger legal overrides. Counts derive directly from the original COUNT
to avoid exponential expression growth; output count uses
`1 + (inputCount - 1) / 2` to avoid overflow.

WIDTH remains the independent scalar leaf-width expression. The input Vec is a
flattened packed structural collection with total width WIDTH * COUNT. Width and
count do not become one lossy single-root elaboration carrier. The generated
stage buses and indexed part selects combine their retained expressions only
at structural emission. `TypedBalancedReductionValueEvidence` transfers a width
through opaque proofs tied to exact native result identities; matching concrete
defaults never supply missing symbolic provenance.

## Callback, graph and stage certificates

Before the first non-singleton symbolic callback runs,
`TypedBalancedReductionCallbackPolicy` requires a capture-free compiler-generated
static Scala Function2 lambda and audits its exact class bytecode. It checks the
lambda call site and recursively checks admitted same-class static adapters.
Host field access, captured state, arbitrary method calls, recursion, exception
handlers, loops and unsupported allocation fail closed. Operator control flow is
restricted to the admitted scalar construction profile; bridges may branch on
level-derived integer values. This is an explicit supported language subset,
not an inference of arbitrary Scala purity from a sampled graph.

`TypedBalancedReductionCapture` runs the authoritative native reducer once on
its finite construction carrier. It records exact Vec/shape, callback operands
and results, declarations, assignments and initializers, in invocation order.
The carrier is never the emitted logical count. Capture rejects external writes,
changed pre-existing declarations or drivers, child hierarchy, invalid results,
and inconsistent owner/shape evidence.

`TypedBalancedReductionClosedGraph` checks complete dependencies, drivers,
cycles, foreign reads and unreachable effects. Each observation freezes native
expression children, literals, initializers and owner/scope/type/clock/width
identities before later callbacks run. Whole-statement inventory rejects effects
such as assertions or memories outside the captured scalar graph.

`TypedBalancedReductionStageReplay` certifies every pair and odd tail. All
operators must share the exact semantic operation key; minimum and maximum
cannot pass uniformity by sharing a mux class. Every row at a level must have
identical bridge behavior, and all register chains use one exact native clock
domain. The terminal width must preserve the input element-width function.
Neither a capture descriptor nor a stage certificate alone authorizes publication.
The production backend adds the callback-code contract and the distinct template
handoff described in the publication document.

## Supported result and bridge shapes

The scalar graph profile includes Bool/Bits/UInt/SInt AND, OR and XOR, equal-width
modular UInt/SInt addition, and exact signed/unsigned less-than-plus-mux min/max
graphs. Replay creates the same native expression classes without rerunning the
Scala callback. All captured local declarations and assignments must belong to
the consumed result graph. Partial drivers, foreign reads, unused effects,
unsupported operations and fixed-width leakage reject.

Identity/transparent-alias bridges and unconditional register chains are
supported with no initializer or a width-independent zero initializer. The native
clock domain owns reset, enable and initialization semantics. Inferred native
registers preserve independent symbolic WIDTH; an ordinary native RegNext or
Mux allocation that freezes an untyped intermediate to WIDTH's default remains
rejected. Ordinary native min/max therefore supports concrete element widths;
inferred native min/max graph replay can retain symbolic WIDTH but does not by
itself satisfy the public callback-code contract.

Widening addition remains outside the current equal-width profile. Native `+^`
introduces resize nodes and larger results. Supporting it requires a separate
input/output width-function proof at every level, reconciliation with narrower
odd tails, and matching register-bridge/result-width proofs. The native baseline
includes widening examples as an independent semantic reference; those examples
are not claims of parameterized widening support. Aggregates with several leaves,
ambiguous widths, unsupported state and non-associative symbolic operators also
remain rejected. These restrictions do not alter ordinary concrete reductions.

## Source and emission boundaries

The reviewed library entry changes preserve receiver identity and add neutral
scoped dispatch; the generic native reduction algorithm remains unchanged.
Native-source and retirement guards must pass, and canonical policy regeneration
must reproduce the reviewed manifest byte-for-byte. Inherited 60c/60d source
boundaries retain their own positive and rejection controls.

Publication templates are observed again at the scheduled handoff before
`PhaseNameNodesByReflection`. Operand anchors must remain named, combinational,
protected from simplification/merging, and driven by one exact full assignment.
After handoff, the native naming, normalization, pruning and emission pipeline
may transform the templates. The anchor policies are checked again at publication.
The template extractor retains
the native emitted declarations, scalar expression syntax and register processes;
the balanced backend rewires certified scalar transfer anchors and adds only
stage topology. Probe hardware is removed before publication. Generated stage
names avoid collisions with existing module identifiers.

## Qualification scopes

| Evidence scope | Matrix and required result |
| --- | --- |
| Native oracle | 32 concrete WIDTH/COUNT shapes; independent arithmetic/signed/widening/pipeline simulation, strict tools and deterministic generation. This is not candidate formal evidence. |
| Concrete operator replay | 32 independently elaborated reference/replay pairs, 18 outputs each; simulation, strict tools, full synthesis, formal equality and a real mutation trace. |
| Concrete whole-stage replay | 96 shapes across three bridge modes, 18 outputs each; independent pipeline simulation, reset-entry proof, unbounded induction and an extra-cycle mutation trace. |
| Parameterized publication | One WIDTH=5/COUNT=1 candidate, specialized at all 32 WIDTH/COUNT combinations against separately native references; strict tools, simulation, reset-entry/unbounded equivalence, deterministic generation and mutations of actual generated pair and cross-Vec source connections. |

All matrices use WIDTH={1,5,8,32} and COUNT={1,2,3,5,8,9,16,17}.
The publication matrix currently exposes uAdd, sAdd, bXor, qAnd and registered
rAdd. The concrete operator/stage matrices expose the broader 18-output graph
profile. Their evidence files deliberately retain different scopes.
Missing tools, errors, timeouts, UNKNOWN, absent success markers and skipped or
cancelled checks never count as proof or as a valid mutation counterexample.

### Frozen-source local qualification; PR CI pending

The following local results qualify implementation commit
`13967f2c51c808bea29037b9562dd5506457cd24`. They include the latest anchor-policy
and nested-owner rejection tests. They are canonical SBT and tool-backed local
results, not completed GitHub PR CI.

| Qualification | Scala 2.12.18 | Scala 2.13.12 |
| --- | --- | --- |
| Canonical SBT tests | 133 passed | 133 passed |
| Same-candidate WIDTH/COUNT specializations | 32 passed, five outputs each | 32 passed, five outputs each |
| Independent simulation cycles across the matrix | 5,586 passed | 5,586 passed |
| Strict Verilog-2001 compilation/lint and full synthesis | Passed for every candidate/reference specialization | Passed for every candidate/reference specialization |
| Reset-entry formal proofs | 32 passed | 32 passed |
| Unbounded temporal-induction proofs | 32 passed | 32 passed |
| Generated pair-operand mutation | Counterexample with bad=1 | Counterexample with bad=1 |
| Generated cross-Vec source-binding mutation | Counterexample with bad=1 | Counterexample with bad=1 |

The unsigned, signed, Bits and Bool packed Vec inputs are independent in the
formal miters and simulation stimulus. The signed and Bits reductions are not
tied to the unsigned input; the registered UInt reduction intentionally uses
the same unsigned Vec as its combinational counterpart. The cross-Vec mutation
replaces the signed reduction's source with the unsigned Vec and must therefore
be observable. The evidence scope is `parameterized-native-balanced-publication`,
with candidate WIDTH=5/COUNT=1 defaults.

Both repeated generations in both Scala lanes produced the same candidate bytes.
All four candidate artifacts have SHA-256:

`6a47e29b6bcbb7a109f36da64ba586d9e1c7d757340d150d7b53d7d5c9e5db64`

The expanded local Scala 2.12 concrete whole-stage matrix also completed: all
96 shapes with 18 outputs passed deterministic repeated generation, strict tools,
full synthesis, reset-entry proof, unbounded induction and a real extra-enabled-
cycle mutation with bad=1. This has scope `concrete-native-stage-replay` and is
separate from parameterized-candidate evidence. The Scala 2.13 stage CI gate
remains pending.

The earlier local Scala 2.12 operator checkpoint passed all 23 operator tests
and 32 concrete replay configurations with 18 outputs, including independent
simulation, strict tools, synthesis, deterministic generation and a mutation
counterexample. Its source hashes and evidence remain under
`target/local-59b-operator-2.12/`. That historical concrete-operator result must
not be substituted for the frozen-source publication proofs above.

### Historical verified checkpoints


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

1. Complete final PR CI for both supported Scala versions, retaining the
   frozen-source local evidence and requalifying any implementation changes.
2. Complete every remaining expanded native operator/stage and inherited
   compatibility gate, including the pending Scala 2.13 stage qualification.
3. Reconcile any newer integration changes and pass every applicable native
   source, retirement, inherited signedness, determinism and strict tool gate.
4. Record exact-head CI evidence. Only then mark the existing 59b roadmap item
   complete and merge that qualified head into `parameterized-verilog`.
