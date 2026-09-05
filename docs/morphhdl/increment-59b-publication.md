# Increment 59b - Parameterized balanced publication

## Status and public entry

Implementation source `ebc33b9ef065b5591c419f15b1bc9b3085ee6aa7` (tree
`668b1446e0d58574baac1381072bf01cf297df1e`) is qualified on Scala 2.12.18 and 2.13.12
for the documented safe-graph subset. The existing 59b checkbox is complete.
**The documentation-only completion commit must pass its fresh applicable CI
before PR #157 may merge. This document does not claim a merge.**

`MorphVerilog` scopes `TypedBalancedReductionBackend` around native elaboration,
including native retries, and installs its pre-normalization phase handoff.
The existing `Vec.reduceBalancedTree(op, levelBridge)` surface reaches this
backend only when the exact Vec owns a symbolic element count. Ordinary concrete
Seq/Vec calls keep the original generic native helper and its callback behavior.
A typed domain whose maximum is one returns the original element directly,
without executing or restricting either callback.

The backend emits one Verilog-2001 module per component with independent WIDTH
and COUNT. The finite native carrier is a construction bound, not the emitted
logical element count. A default COUNT=1 must not erase branches needed by a
larger legal override. Non-singleton publication currently requires its native
component scope. An outer typed generate/capture owner is rejected with
`NESTED-OWNER` or the earlier ownership diagnostic; ordinary child components
may each own a reduction in their own component scope. Nested typed structural
publication is not claimed by this implementation.

## Supported scope

| Layer | Admitted contract |
| --- | --- |
| Count and layout | Finite positive Int-sized count domain; exact typed Vec identity; one scalar Bool/Bits/UInt/SInt leaf per element; packed WIDTH * COUNT layout. |
| Native operator graph | Exact bitwise AND/OR/XOR, equal-width modular UInt/SInt addition, and exact UInt/SInt less-than-plus-mux minimum/maximum graphs; one uniform semantic operation key. |
| Symbolic result width | The same authoritative positive scalar width function through every operator, bridge and terminal result; independent WIDTH root retained by identity. |
| Level bridge | Identity/transparent aliases or unconditional register chains, with no initializer or a width-independent zero; uniform rows at each level and one exact native clock domain. |
| Host callback code | Capture-free compiler-generated static Scala Function2 lambdas whose exact bytecode and admitted same-class static helpers pass the callback policy. |
| Concrete reductions | Existing native semantics, including generic callbacks outside the symbolic profile. |

All layers must pass together. Bytecode admission of a native method does not
certify its graph or width behavior. In particular, ordinary native min/max and
RegNext may freeze an untyped symbolic-width intermediate through native cloning.
Such calls remain rejected when symbolic WIDTH authority is lost. Native min/max
with concrete element width is supported by the graph profile. Inferred native
min/max graphs can retain symbolic WIDTH for replay certification, but that alone
does not make an arbitrary helper acceptable to the callback-code policy.

Widening addition remains rejected. Native `+^` adds resize nodes and changes
result widths; it needs a separate proof of each stage's input/output formulas,
narrower odd-tail handling, bridge widths and final result. The equal-width
certificate cannot derive that contract from concrete witness sizes. Empty
symbolic domains, composite element shapes, unsupported effects, non-associative
operators and ambiguous width provenance also fail closed.

## Callback admission before execution

`TypedBalancedReductionCallbackPolicy` requires a synthetic final lambda class
with no fields, zero captured arguments and an inspectable static Function2
implementation. The class resource must contain the exact matching JVM lambda
call site. Its complete method bytecode and admitted same-class static adapters
are audited; recursive helpers and unavailable or ambiguous class evidence reject.

The admitted code may use its scalar arguments, locals and enumerated native
scalar construction methods. Bridge code may additionally use level-derived
integer conditions and supported native inferred-register/initializer construction.
Host fields, arbitrary calls, unsupported allocations, exception handlers,
invokedynamic inside the body, loops and backward branches are rejected. Native
immutable source-location construction needed by assignments is explicitly
admitted. No host-state purity conclusion is drawn from running a few sample
callbacks. The policy runs before the first callback execution.

## Native capture and replay templates

The backend invokes `TypedBalancedReductionStageReplay.capture`, which executes
the authoritative generic native helper on the finite construction carrier and
certifies every pair and odd tail. Closed-graph observations retain exact
operands, result objects, local declarations/drivers, initialization and clock
identities. Value evidence carries the original typed width root through each
certified result. Native operator/bridge proofs remain distinct from callback
code admission.

For each possible active level, the backend builds a separate pair template and
odd-tail template. The pair uses native operator replay followed by native bridge
replay. The tail uses the bridge only. Both use fresh typed scalar anchors and
fresh native expression/register nodes in the retained clock domain. The Scala
callbacks are not rerun to produce templates. Each template, its input anchors
and the public result anchor receive their own closed-graph observations.

After the source stage certificate is revalidated, the finite probe declarations
and assignments are removed. Only the distinct replay templates enter the
native compiler pipeline. This prevents publication of a default-size or
maximum-carrier reduction tree.

## Handoff and native RTL ownership

A scheduled phase immediately before `PhaseNameNodesByReflection` revalidates
all template and result-anchor observations and records a successful handoff.
Operand anchors must remain named, combinational and protected from simplification
and backend merging, with one exact full driver. The same anchor-policy checks
run again at publication; removing a preservation policy fails closed.
Elaboration-time mutations of a replay operator or the public result assignment
must fail this boundary. Publication also checks the original Vec shape identity
and requires that the handoff actually occurred.

`ParameterizedVerilogStructural.extractNativeTemplates` extracts the scalar
bodies from the native emitted component using their captured structural blocks.
Their declarations, expression syntax, signedness handling and register processes
remain native emission. The balanced backend does not author arithmetic syntax,
clocked processes, reset polarity or enable precedence. Each scalar input/result
transfer must retain exactly one native full assignment before it is rewired.

The backend adds packed stage buses, generate conditions and loops. Active levels
instantiate one native pair body per complete pair and one native tail body when
needed. Inactive levels bypass their input bus. Indexed part selects use the
retained element WIDTH. Scalar template types keep signed data interpretation
inside the native bodies; packed interstage buses carry the corresponding bits.
Generated bus, genvar and block names avoid existing module identifiers.

## Topology and latency

Level `l` has `1 + (COUNT - 1) / 2^l` inputs, floor(inputCount/2) pairs and
`1 + (inputCount - 1) / 2` outputs. A level is active only when inputCount > 1.
An odd last input invokes its bridge without an operator. The complete tree has
COUNT-1 operations and ceil(log2(COUNT)) active levels.

One register per active level gives ceil(log2(COUNT)) enabled-edge latency.
COUNT=1 invokes no bridge and therefore has zero added latency. The qualified
registered fixture uses the native synchronous active-high reset and active-high
enable clock domain. Native clocked emission gates synchronous reset by enable;
reset with enable low holds state. Other native clock configurations are not
claimed as formally qualified by that fixture.

## Parameterized qualification gate

`TypedBalancedReductionPublicationArtifactWriter` invokes the public helper in
one candidate with WIDTH=5 and COUNT=1 defaults and domains WIDTH=1..32,
COUNT=1..17. It exposes UInt add, SInt add, Bits XOR, Bool AND and registered UInt
add. Ordinary native Spinal elaboration independently builds the reference for
each WIDTH={1,5,8,32}, COUNT={1,2,3,5,8,9,16,17} pair. All 32 specializations must
use the same emitted candidate file; references are not generated by replaying
the candidate implementation. Unsigned, signed, Bits and Bool Vec ports are
independent inputs in both designs. The formal miter leaves each packed input
unconstrained, and simulation drives separate data patterns. Only the registered
UInt result shares the unsigned Vec with its combinational UInt counterpart.
Repeated independent generation must match bytes.

`check-increment-59b-publication.py` requires strict Verilog-2001 compilation,
strict Verilator lint and full Yosys synthesis of every specialization/reference.
Its independent integer/pipeline simulation covers separately driven Vec inputs,
signed/modular values, singleton and odd counts, enable stalls and enabled/disabled
in-flight resets.
Reset-entry formal proof begins with arbitrary state and an enabled reset.
Unbounded temporal induction then checks all five outputs from the established
zero state while subsequent inputs, reset and enable remain unconstrained.

After all 32 normal checks succeed, two controls mutate the actual generated RTL.
The first replaces a pair's right input connection with its left input. The second
replaces the signed reduction's Vec source binding with the independent unsigned
Vec. Each must produce a definitive formal counterexample and its own VCD with
bad=1. The second control checks that the independently driven input families
can expose an incorrect source binding. Tool errors, timeouts, UNKNOWN or missing
success markers cannot satisfy either positive proof or mutation requirements.
A focused `--case` run deliberately emits no complete qualification evidence.

## Source-bound completion evidence

The full evidence scope is `parameterized-native-balanced-publication`, separate
from `concrete-native-operator-replay` and `concrete-native-stage-replay`.
For source `ebc33b9ef065b5591c419f15b1bc9b3085ee6aa7` and tree
`668b1446e0d58574baac1381072bf01cf297df1e`, 133 focused source tests passed on each
of Scala 2.12.18 and 2.13.12, including public-helper generation, callback policy,
anchor-policy and nested-owner safety tests.

Each Scala lane passed all 32 specializations with five outputs, 5,586 independent
simulation cycles across the matrix, strict Verilog-2001 compilation/lint, full
synthesis, 32 reset-entry proofs and 32 unbounded temporal-induction proofs.
The unsigned, signed, Bits and Bool inputs were independently unconstrained in
formal. Both actual-RTL mutation controls produced bad=1 counterexamples in
each lane: changed pair operand and changed cross-Vec source binding.

Qualified local A/B generation in each Scala lane produced byte-identical
candidate RTL. All four local candidate artifacts have SHA-256:

`6a47e29b6bcbb7a109f36da64ba586d9e1c7d757340d150d7b53d7d5c9e5db64`

The publication PR run [33983120584](https://github.com/pysolvesemi/MorphHDL/actions/runs/33983120584)
and push run [33983118307](https://github.com/pysolvesemi/MorphHDL/actions/runs/33983118307)
retain the source-bound dual-Scala publication qualification. PR artifact metadata
identifies `9974706211` (Scala 2.12.18) and `9974714093` (Scala 2.13.12) at the
qualified source head. CI checks byte-identical A/B candidates within each lane
and reuses one candidate across all 32 specializations. The checksum above is
local evidence; remote payload hashes were not independently inspected or
compared across Scala lanes. The separate
concrete-stage matrix passed all 96 shapes with 18 outputs per lane, including
deterministic A/B generation, strict tools/synthesis, reset-entry and unbounded
proofs and a real extra-enabled-cycle mutation counterexample. Its PR/push runs
are [33983120710](https://github.com/pysolvesemi/MorphHDL/actions/runs/33983120710)
and [33983118357](https://github.com/pysolvesemi/MorphHDL/actions/runs/33983118357).
Concrete stage proof remains a separate evidence scope from parameterized proof.

All applicable executed source-qualification gates passed; environmental skips
are not passes. The documentation-only completion commit still needs fresh
applicable CI and final PR-head verification before merge. The main 59b document
records the operator and Mill compatibility references and preserves the explicit
unsupported scope.

Publication safety tests also require rejection before host-state callback effects,
pre-handoff graph mutation rejection, exact singleton callback bypass, generated
name collision handling, rejection of nested typed owners and rejection of
removed native anchor-preservation policy. Native source-preservation, canonical policy regeneration,
retirement and inherited signedness/compatibility gates remain mandatory.
