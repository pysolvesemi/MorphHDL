# MorphHDL IR passes

This is the standalone MorphHDL-owned workspace for the four optional
wire-assignment passes controlled by
[`morphhdl-ir-wire-assignment-passes-todo.md`](morphhdl-ir-wire-assignment-passes-todo.md).
The workspace is deliberately outside the repository root SBT/Mill aggregate and
must not modify upstream-owned SpinalHDL source.

The passes consume the versioned `morphhdl.ir.v1` canonical IR after typed
parameterization and before Verilog-2001 emission. They do not parse generated
Verilog. Every adapter, safety rule, transformation and pipeline decision is
component-generic and identity-based. No implementation may recognize
`StreamFifo`, `StreamFifoCC`, `ParameterizedStreamFifo`, a module or component
name, a source filename, or an emitted identifier.

## WA-01 — isolated workspace and contracts

WA-01 established the cross-Scala nested SBT workspace, immutable pass
configuration/result/diagnostic/elimination-report contracts, the path boundary
guard, mutation-tested boundary checks, and CI coverage for Scala 2.12.18 and
2.13.12. One public `enabled` flag controls the complete pipeline and is disabled by default.

## WA-02 — canonical IR adapter

WA-02 binds the workspace to the separately owned `morphir` project without
adding the workspace to the root aggregate. `CanonicalIrPassAdapter` accepts
only a validated canonical-v1 `Design` at
`PostParameterizationPreEmission` and exposes exact declaration, driver,
reference, scope, packed-type, parameter-domain, naming-provenance, source
location and observability identities.

The adapter fails closed on incomplete or invalid metadata. Mutation guards
reject generated-HDL parsing, regex/name recognition, Spinal implementation
coupling and component-specific logic. WA-02 does not eliminate, rewrite or
rename declarations, drivers, references or expressions.

The shared parameterized regression source remains
[`examples/ParameterizedStreamFifo.scala`](examples/ParameterizedStreamFifo.scala).
It is a proof witness, never a pass implementation template or special case.

## WA-03 — common safety and proof gates

`WireAliasSafetyGate` is the read-only, component-generic eligibility analysis
used by both direct-alias transforms. It proves a sole continuous full-object
direct driver, exact packed signedness/value semantics, width equality over the
complete admitted parameter domain and retained generate domains, legal scope
replacement, and continuous-cycle freedom.

It fails closed for observability, hierarchy, public export, clock/reset,
tri-state, bidirectional, memory-port, instance-port, procedural, comment,
attribute, preservation, probe and incomplete-control contracts. Adversarial
fixtures cover mismatching admitted bindings, multiple/partial drivers,
non-reference expressions, sibling scopes, dependency cycles, registered
feedback, domain-expansion limits and deterministic repeated analysis.

WA-03 also supplies strict Verilog-2001 compilation, lint and synthesis,
representative simulation, formal equivalence over the complete admitted
parameter domain, a live mutation that must fail with a retained counterexample,
and deterministic repeated proof evidence. Sequential miters force a
reset-active clock transition before assertions become active.

WA-03 does not eliminate an alias. WA-04 and WA-05 remain the transforms for
unnamed and explicitly named direct aliases.

## WA-04 — unnamed alias elimination

`UnnamedWireAliasEliminationPass` is disabled when the common
`WireAliasPassConfiguration(enabled = false)` setting disables the pipeline. In
normal product use it executes through the fixed-order pipeline when
`enabled = true`. Package-private regression selection may exercise this stage
independently. It selects candidates solely from canonical `NameOrigin.Unnamed`
provenance, never from emitted-name text.

For each safe candidate it replaces every read by exact symbol identity,
removes the exact declaration and sole direct assignment, preserves surviving
reference identities/owners/source locations/expression structure, and repeats
to a validated fixed point. Alias chains therefore collapse safely and a second
run is idempotent. Invalid intermediate IR returns the original validated input
with deterministic error diagnostics.

The tests cover direct aliases, nested fanout, neighboring symbol isolation,
unsafe rejection evidence, deterministic reports, exact surviving names and
metadata, invalid-input rollback, and the complete symbolic `WIDTH=1..64` by
`DEPTH=1..8` domain.

The test-only `UnnamedWireAliasNativePhase` executes before name allocation,
constructs a conservative canonical candidate, invokes the canonical pass, and
writes an approved result back to the same native graph by exact object
identity. It does not parse or postprocess emitted HDL and does not create the
WA-08 production handoff.

## WA-05 — named alias elimination

`NamedWireAliasEliminationPass` is controlled by the same single master flag. In
normal product use it runs after the unnamed direct-alias stage when
`WireAliasPassConfiguration(enabled = true)`; package-private regression
selection may exercise it independently. It selects only canonical
`NameOrigin.Explicit` candidates carrying an explicit source name. Unnamed,
reflected, generated and unknown origins remain untouched even when their
emitted text appears user-friendly.

The unchanged WA-03 safety contract retains every public, hierarchical,
preservation, probe, attribute, comment and source-contract dependency. A safe
candidate is replaced by exact symbol identity; only its exact declaration and
sole assignment are removed, without transferring the removed name to its
source or another signal. The deterministic report retains every removed name
and source location when available.

The pass reaches a validated fixed point, fails closed atomically, is
idempotent, and leaves all surviving names and metadata unchanged. Tests cover
exact identity, recursive expressions, adjacent symbols, chains, protected
named-debug contracts, deterministic evidence, invalid-input rollback, and the
complete `WIDTH=1..64` by `DEPTH=1..8` domain.

The test-only `NamedWireAliasNativePhase` uses source/elaboration provenance on
the witness, not component or identifier recognition. It invokes the canonical
pass and applies the approved result to exact native identities before name
allocation. It does not create the WA-08 production handoff.

## WA-06 — ordered optional pipeline and closure

`WireAliasPassPipeline` is the single optional canonical MorphHDL-IR entrypoint
for these transforms. WA-06 proved the historical direct-alias stages and their
unnamed-then-named order. WA-07 exposes only one product-facing `enabled` flag:
`false` executes no pass; `true` executes unnamed direct aliases, named direct
aliases, then unnamed continuous expression temporaries. Package-private stage
selection exists only for regression evidence. The result retains one ordered
report per executed stage.

A stage consumes the validated output of the preceding stage. Any failed stage
publishes the original pipeline input, preserving atomic fail-closed behavior.
Successful execution reports `Changed` when any enabled stage transforms the
design and `Unchanged` only after all enabled stages reach their fixed point.

Cross-Scala tests validate alias chains and fanout without parsing emitted
Verilog, package-private historical stage selection, exact fixed ordering,
deterministic reports, idempotent IR, atomic rollback, surviving
metadata/reference identity, module/source-path independence, and the complete
512-binding `WIDTH`/`DEPTH` domain.

The test-only `OrderedWireAliasNativePhase` first validates the real canonical
pipeline order on a component-neutral identity graph, then executes the already
reviewed WA-04 and WA-05 native graph rewrites in that same order on the shared
witness before name allocation. `ParameterizedStreamFifoCombinedPassWitness`
emits the combined candidate and a machine-readable ordered report. This proof
bridge is not the WA-08 production integration.

`run-wa06-regression.sh` generates the one common pre-pass reference plus each
individual and combined candidate. It rejects empty transformations, verifies
byte-identical repeated emission and report output, runs strict Verilog-2001
compile/lint/synthesis, and executes representative parameterized simulations.
The formal harness then proves WA-04, WA-05 and the ordered combination directly
against the unchanged common pre-pass StreamFifo reference for all 512 admitted
bindings. It never compares only against the preceding pass.

## WA-07 — unnamed continuous expressions and one common flag

`UnnamedWireExpressionEliminationPass` accepts only canonical
`NameOrigin.Unnamed` internal combinational temporaries with one full-object
continuous driver whose right-hand side is not a direct reference. It supports
all pure `RtlExpr` forms represented by canonical v1, including literals,
unary and binary operators, muxes, concatenations, selections, resizes and
casts. The pass never recognizes `_zz_*` text.

Every source reference must resolve and be legally visible from every receiver.
Every receiver must be a continuous driver. A procedural source assignment or
any procedural receiver causes a fail-closed rejection, so no assignment in a
Verilog `always` block is rewritten. Canonical `DriverKind.Procedural` receivers
and sources are retained unchanged. At each accepted whole-object receiver the
complete RHS is cloned with fresh reference identities and wrapped in the
removed alias's packed width and signedness before the exact temporary
declaration and its sole assignment are deleted.

Selected uses are not replaced blindly. A zero-offset full-width select is
collapsed to a whole-object receiver. A partial receiver select is composed only
when the RHS is a direct source part-select of the complete alias width and the
receiver offset and width are literal, in range, and therefore provably safe.
The composed result carries an explicit receiver-width fence and unsigned
selection semantics, including when the eliminated whole object is signed. A
whole-object receiver continues to use the eliminated assignment's original
signedness.

Arithmetic, mux, cast, resize, nested, dynamic and other selected uses retain
the temporary, avoiding invalid nested or general-expression selections in
strict Verilog-2001. The one-bit `temporary[0]` case is treated as a whole-object
use only when the temporary is proven one bit wide. The complete selected-use
contract is documented in
[`WA07_SELECTED_USE_CONTRACT.md`](WA07_SELECTED_USE_CONTRACT.md).

The public `WireAliasPassConfiguration(enabled = true)` executes all four
passes in the fixed order. `enabled = false` executes none. Tests cover literal,
nested and fanout expressions, exact identity, type fences, cycles, scopes,
metadata, procedural source and receiver exclusions, selection composition and
rejection, determinism, atomic failure, fixed points and idempotence on both
supported Scala versions.

`ParameterizedStreamFifoExpressionPassWitness` emits the expression-only
candidate. `ParameterizedStreamFifoAllPassWitness` retains the historical
three-stage candidate using package-private regression selection; its report
explicitly does not claim execution of the current four-stage common flag.
Both are test-only bridges;
WA-08 owns production publication and writeback.

`run-wa07-regression.sh` generates the unchanged common reference, every
historical direct candidate, the expression-only candidate and the all-pass
candidate. It requires non-empty transformations, zero procedural rewrites,
byte-identical repeated Verilog and reports, strict Verilog-2001 compilation,
lint, synthesis and representative simulations. The formal harness compares
the expression-only and all-pass candidates directly against the same common
pre-pass StreamFifo capture over all 512 admitted `WIDTH`/`DEPTH` bindings.

## WA-07a — constant-operand simplification

`ConstantOperandSimplificationPass` rewrites pure continuous canonical RHS
expressions without removing declarations or assignments. The bounded rules
cover bitwise AND/OR/XOR, logical AND/OR/NOT, safe double negation, zero-distance
shifts and constant-condition muxes. For a one-bit comparison result `p`, examples
include `p & 1 -> p`, `p & 0 -> 0`, `p | 0 -> p`, `p | 1 -> 1`,
`p ^ 0 -> p` and `p ^ 1 -> ~p`; commutative operands may appear in either order.

A numeric one is not a multi-bit all-ones mask. Rewrites preserve evaluation
width and signedness, including wider context, captured unsized constants and
explicit cast/resize fences. Symbolic widths are not replaced by defaults.
Unknown-capable raw signals retain neutral bitwise operations that normalize Z
to X; a Boolean type alone is not a non-Z proof. Logical identities Booleanize
vectors and retain self-determined truth-conversion boundaries. Unproven cases,
procedural statements and preservation contracts remain untouched. Arithmetic
cancellation and inter-signal constant propagation are not part of this pass.

The common pipeline runs unnamed aliases, named aliases, unnamed expressions,
then constant operands, repeating in that order to a checked fixed point.
Rewrites are recorded separately as `simplifiedExpressions`/`simplifiedCount`;
`eliminatedCount` still counts only removed wires. A failed stage rolls back to
the original pre-pipeline input. Standalone simplification retains input item
order, surviving names, comments, declaration and driver identities.

The test-only `ConstantOperandNativePhase` captures the **actual complete
Boolean RHS tree**, runs the canonical pass, and decodes its actual output back
to that assignment. Unrepresented native nodes fail closed. Wider and symbolic
expression rules have an independent canonical before/after-tree simulator and
Yosys oracle; that oracle does not replace the native full-design proof. No
component name, emitted name, sampled width, fake surrogate expression or
Verilog text drives native capture/writeback decisions. WA-08 remains the
separate production publication and writeback increment.

The shared FIFO source includes ordinary redundant Boolean expressions on its
parent-side valid signal in **every** generation mode, including the unchanged
common pre-pass reference. The generic native fixture adds eight independent
outputs, checked over all sixteen combinations of two four-state inputs.
The canonical rule oracle checks 1,024 input patterns and must detect both an
unsafe Z identity mutation and a functional mutation in the formal miter.

`run-wa07a-regression.sh` retains all five historical candidates and adds
`constant-operand-simplification.v` plus `wire-assignment-four-pass.v`. Both new
candidates must perform real rewrites, reach fixed points and reproduce
byte-identical Verilog/reports. The new native reference must be byte-identical
to the reference captured before **all** passes. Both new proof legs cover all
512 legal WIDTH/DEPTH bindings, not just defaults or selected corners. Four-state
simulation is an additional mandatory gate, not a claim made from two-state
formal alone.

### Non-vacuous clocked proofs

The formal miter uses both an explicit DUT clock and the formal global timestep.
Its clock edges must be retained (`multiclock on`); abstracting them into a single
implicit clock can contradict the two-step reset assumptions. A solver PASS
under an unreachable comparison region is not accepted as equivalence evidence.

Every admitted binding must first cover the comparison region after reset is
released and retain an actual cover trace. Only then is its unbounded equivalence
proof run. Every shared-witness candidate also has an intentional functional
mutation that must fail with a counterexample; the generic sequential fixture
has an independent mutation control in addition to the combinational one.

`test_wire_assignment_clock_model.py` runs before native regression. It proves
the correct clock model, detects a real functional mutation, and deliberately
restores the unsafe single-clock abstraction to verify that the reachability
gate rejects it before equivalence runs. Solver-private trace workspaces are
excluded from byte-determinism comparison; generated cover configurations,
reachability evidence and equivalence artifacts remain compared. This corrects
the proof model without weakening the reference snapshot, parameter domains,
output comparisons, or four-state simulation requirements.

## Common witness and formal-equivalence baseline

Every transforming pass and supported combination runs on the shared
parameterized StreamFifo while preserving symbolic `WIDTH` and `DEPTH`.
Independent generic positive and negative fixtures ensure the witness cannot
become a component recognizer.

For every proof run, reference Verilog is emitted from the design immediately
before the entire passes phase. Verilog emitted after each individual pass and
after the ordered combination is compared against that one common pre-pass
capture through the same structured backend. Both sides use identical legal
parameter assumptions.

The manifest audits the full Cartesian product `WIDTH=1..64` and `DEPTH=1..8`.
A checked roadmap slot must publish its candidate and pass all 512 formal
bindings. The formal mutation remains mandatory and demonstrates that a real
functional difference is rejected.

## Local validation

From the repository root, run the static and Scala gates with:

```bash
bash morphhdl-passes/scripts/test-boundary-guard.sh
python3 morphhdl-passes/scripts/check-wa02-adapter-boundary.py --self-test
python3 morphhdl-passes/scripts/check-wa02-adapter-boundary.py
python3 morphhdl-passes/scripts/check-wa03-gates.py --self-test
python3 morphhdl-passes/scripts/check-wa03-gates.py
python3 morphhdl-passes/scripts/check-wa04-pass.py --self-test
python3 morphhdl-passes/scripts/check-wa04-pass.py
python3 morphhdl-passes/scripts/check-wa05-pass.py --self-test
python3 morphhdl-passes/scripts/check-wa05-pass.py
python3 morphhdl-passes/scripts/check-wa06-pipeline.py --self-test
python3 morphhdl-passes/scripts/check-wa06-pipeline.py
python3 morphhdl-passes/scripts/check-wa07-expression-pass.py --self-test
python3 morphhdl-passes/scripts/check-wa07-expression-pass.py
python3 morphhdl-passes/scripts/check-wa07a-constant-pass.py --self-test
python3 morphhdl-passes/scripts/check-wa07a-constant-pass.py
python3 morphhdl-passes/scripts/validate_wire_assignment_equivalence.py --self-test
(
  cd morphhdl-passes
  sbt -batch +test
)
```

The pinned CI toolchain runs the native witness and strict legality gates with:

```bash
bash morphhdl-passes/scripts/run-wa07a-regression.sh

python3 morphhdl-passes/scripts/validate_wire_assignment_equivalence.py \
  --shared-witness morphhdl-passes/build/formal/wire_assignment_ir/generated/parameterized_stream_fifo.v \
  --output morphhdl-passes/build/formal/wire_assignment_ir/evidence \
  --prove-pending WA-07a \
  --check-determinism
```

The regression publishes:

- `morphhdl-passes/build/pass-outputs/wire-alias-unnamed.v`;
- `morphhdl-passes/build/pass-outputs/wire-alias-named.v`;
- `morphhdl-passes/build/pass-outputs/wire-alias-combined.v`;
- `morphhdl-passes/build/pass-outputs/wire-expression-unnamed.v`; and
- `morphhdl-passes/build/pass-outputs/wire-assignment-all.v` (historical three-stage);
- `morphhdl-passes/build/pass-outputs/constant-operand-simplification.v`; and
- `morphhdl-passes/build/pass-outputs/wire-assignment-four-pass.v`.

All seven are compared to the same captured pre-pass design. WA-07a extends the
one-flag standalone pipeline with constant simplification. WA-08 remains the
separately reviewed production handoff into MorphHDL-owned generation flow.


### WA-07a complete-domain proof shards

The native generation job emits one pre-pass reference and all seven candidates.
It records their hashes together with the exact source commit, proof manifest and
signature-registry hashes. Every proof job checks those identities before using
the artifacts; a previous revision's results cannot qualify a newer checkout.

The unchanged 512-binding WIDTH/DEPTH domain is divided into 16 disjoint,
non-empty shards. Each shard runs every historical and new pass candidate against
the same pre-pass reference, preserves the explicit-clock model, proves comparison
reachability, exercises functional mutations, and independently repeats its proofs
and deterministic artifacts. Each binding retains its own result ledger. No
parameter bound, assertion, solver mode, timeout or clock assumption is reduced.

A successful shard reports `SHARD_PASS`, **not** full qualification. The final
aggregation job requires all 16 jobs to succeed and checks the exact disjoint
union for both runs and every pass. It rereads the actual solver statuses, miters,
clock configurations, cover traces, mutation counterexamples and artifact hashes.
Missing, duplicate, stale, reordered or failed evidence is rejected. A failed
rerun removes any stale aggregate PASS. Only this final job can report the full
7 x 512 x 2 = 7,168 equivalence proofs, with 7,168 comparison-reachability proofs,
as complete. Native legality and four-state evidence remains in the separate
native-input artifact; actual solver records are retained in all shard artifacts.

Run `python3 morphhdl-passes/scripts/test_wire_assignment_shards.py -v` to test
partitioning and fail-closed aggregation. These synthetic metadata mutation tests
are not substitutes for actual RTL proofs. The default command without shard
options still proves the entire domain in one process.
