# Increment 59b - Whole native stage and register-bridge replay

## Status

Whole-stage replay is qualified on source `ebc33b9ef065b5591c419f15b1bc9b3085ee6aa7`
(tree `668b1446e0d58574baac1381072bf01cf297df1e`) on both supported Scala lanes and
feeds the separate parameterized publication backend. A stage certificate alone
remains insufficient permission to publish. The existing 59b checkbox is complete.
Completion head `b0a4388e3babbc01500a620eefe6c0965e9e6343` passed CI, including
both Scala lanes of stage run
[33986577388](https://github.com/pysolvesemi/MorphHDL/actions/runs/33986577388).
The later integration of merged 60e base `dc8cab41cf3fd41b026ba7359f30cb596b14d015`
requires fresh combined-head CI on
[PR #157](https://github.com/pysolvesemi/MorphHDL/pull/157) before merge. No result
for the new combined head or merge is claimed by those historical runs. See
[increment-59b-publication.md](increment-59b-publication.md) for the additional
callback-code and template-handoff obligations. The publication path currently
requires native component scope; outer typed generate/capture owners are rejected.

## Native graph authority

`TypedBalancedReductionStageReplay` invokes the original generic native helper
through `TypedBalancedReductionCapture`, observing every operator and bridge
as it completes. It certifies every row, including odd tails. All operators must
share one admitted semantic operation key. The native mux class alone cannot
establish that key because minimum and maximum use the same class. Every row at
one level must have identical bridge behavior. Levels may have different
register-chain lengths, but all registers belong to one exact native clock domain.

The graph profile contains the original 14 scalar bitwise/modular-add operations
and four signed/unsigned minimum/maximum graphs. Min/max requires the exact
same-signedness native less-than comparator and binary mux, with both original
operand identities in the comparison and arms. Comparison and arm order are
preserved. This graph admission does not bypass width or callback-code checks.

`TypedBalancedReductionValueEvidence` links each intermediate to an earlier
opaque proof of that exact result object. It carries the independent WIDTH
authority without manufacturing metadata from an equal Int witness. The final
result width must equal the input scalar element-width function throughout its
domain. Unequal stage-result/odd-tail widths remain outside this profile.

The exact Vec identity and shape, scalar metadata, local drivers, primitive
inputs, initializers and clock identities are checked for freshness. A whole
statement inventory rejects assertion, memory and other effects outside the
captured declarations and assignments. No recorded effect is silently removed.
Certificates refer to the native graph before normalization; production builds
and separately observes fresh replay templates before handing them to native
compiler phases.

## Bridge subprofile

`TypedBalancedReductionBridgeReplay` supports scalar identity/transparent-alias
bridges and unconditional register chains with zero initialization or no
initializer. Replay uses the inherited native clone, register and assignment
construction inside the exact retained clock domain. It does not call the Scala
bridge again or emit its own clocked process.

Initializer literals may have local native scalar aliases. Every alias and driver
must be consumed, same-typed, constant-only and closed. External, nonzero and
unknown initializer expressions reject. A sized zero also rejects when its width
would constrain an inferred register above the smallest certified data width.
Fresh replay uses native unsized zero literals.

SpinalHDL HardType fixes a driven source width before cloning. Fixing an inferred
width to its already-proved parameter-free constant preserves meaning. Freezing
symbolic WIDTH to its default does not. Ordinary concrete RegNext is admitted;
an untyped symbolic RegNext that freezes an intermediate remains rejected.
Inferred native registers with width-independent initializers exercise the
symbolic-width path without relaxing this guard. The publication fixture uses
an inferred UInt register assigned from its input and initialized with U(0).

Latency is measured in enabled sampling edges of the retained clock domain.
The synchronous-reset/clock-enable fixture follows native `emitClockedProcess`,
which places the clock-enable condition outside the synchronous-reset condition.
Reset is therefore sampled only when enable is active. Both the independent
simulation model and formal reset-entry setup preserve that order.

## Result-width limits

Ordinary native BitVector min/max uses Mux construction that can freeze the
result width to an untyped Int witness. The graph certificate accepts concrete
widths and rejects that loss of symbolic WIDTH authority. An inferred native
mux graph can retain symbolic width evidence; it is still subject to the separate
public callback-code policy before a symbolic-count call can use it.

Native widening addition introduces resize nodes and larger outputs. Its result
width, odd-tail widths and bridge widths require a distinct proof across every
level. Matching defaults cannot supply that proof. Widening remains rejected by
the current stage certificate; no handwritten widening tree replaces it.

## Independent hardware qualification

`TypedBalancedReductionStageArtifactWriter` separately elaborates ordinary
native reference and certified native replay designs, then repeats generation
for byte comparison. WIDTH={1,5,8,32}, COUNT={1,2,3,5,8,9,16,17} and three bridge
modes yield 96 concrete shapes, each exposing all 18 graph-profile outputs.
The modes are identity, one register at each active level, and identity at level
zero followed by two-register chains at later active levels. Singleton domains
invoke neither callback.

The stage workflow requires exact test-suite counts on both Scala versions,
Verilog-2001 compilation, strict Verilator lint, full Yosys synthesis,
deterministic output and independent arithmetic/pipeline simulation. Simulation
covers in-flight resets with enable low and high, long stalls and directed data.
A reset-entry proof starts from unconstrained state and applies an enabled reset.
Temporal induction then proves equality from the established zero state while
later reset, enable and data remain unconstrained. Proof preprocessing may merge
identical native logic and register cells only in this initialized-state scope;
reset entry is proved separately from arbitrary state. An extra enabled-cycle
mutation must produce a definitive counterexample and a VCD showing `bad=1`.
Errors, timeouts, UNKNOWN and missing success markers are not passing evidence.

These artifacts retain `scope: concrete-native-stage-replay` and
`parameterized_tree_formal: not-run`. They qualify native stage/bridge replay;
the separate publication matrix qualifies one actual parameterized candidate.
The expanded matrix passed all 96 shapes with 18 outputs on both Scala 2.12.18
and 2.13.12, including deterministic A/B generation, strict tools and full
synthesis, reset-entry and unbounded temporal proofs, and a genuine extra-enabled-
cycle counterexample showing bad=1. Source-bound evidence is retained in PR run
[33983120710](https://github.com/pysolvesemi/MorphHDL/actions/runs/33983120710)
and push run [33983118357](https://github.com/pysolvesemi/MorphHDL/actions/runs/33983118357).
These expanded results supersede the older 14-output checkpoint for this source;
they do not change the separate scope of the parameterized publication proof.

## Publication boundary

Uniform sampled graphs cannot establish arbitrary Scala callback purity across
COUNT/WIDTH specializations. A stateful bridge can appear uniform on the maximum
carrier while changing behavior when a smaller count invokes fewer callbacks.
For that reason `Certificate.requirePublicationCertificate()` still rejects.

Production uses an independent composition of obligations: exact zero-capture
callback bytecode admission before execution, complete native stage/bridge
certification, fresh native replay templates, and their observed handoff before
normalization, with operand naming/preservation/full-driver policies checked at
both handoff and publication. Only those distinct templates enter native emission; the carrier
probe graph is removed. Symbolic pair loops, odd-tail blocks and inactive-stage
bypasses contain the native emitted scalar bodies. See the publication document
for that path and its separate passing 32-specialization formal gate. All
applicable executed source-qualification and inherited gates passed; skipped
cases are not counted as passing. The completed 59b head subsequently passed CI.
Fresh applicable CI and final PR-head verification are now required for the later
60e reconciliation; historical stage proofs cannot substitute for that check.
