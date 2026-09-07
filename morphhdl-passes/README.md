# MorphHDL IR pass workspace

This standalone workspace owns optional passes over the canonical
MorphHDL-owned IR after parameterization/capture and before structured
Verilog-2001 lowering. It is intentionally not a repository-root SBT/Mill
aggregate member and does not duplicate the canonical IR implementation.

The controlling checklist is
[`morphhdl-ir-wire-assignment-passes-todo.md`](morphhdl-ir-wire-assignment-passes-todo.md).
The current increment is **WA-07b, in progress**. Its implementation and proof
layers are described in [WA-07b notes](wa07b-implementation-notes.md); their
presence does not yet establish final-head qualification or completion.

## Implemented boundary

WA-02 consumes `morphhdl.ir.v1` through `CanonicalIrPassAdapter`. Production
binding accepts the read-only `CanonicalIrHandoff` published by Increment 58;
unit fixtures explicitly use `bindFixture`. The adapter exposes immutable
identity-indexed declarations, drivers, references, packed types, parameter
domains, naming provenance, scope ownership, comments and observability.

WA-03 adds `WireAliasSafetyGate` and the shared-witness proof contract. WA-04
implements `UnnamedWireAliasEliminationPass`; WA-05 implements
`NamedWireAliasEliminationPass`; WA-06 implements the ordered
`WireAliasPassPipeline` for direct aliases. WA-07 adds
`UnnamedWireExpressionEliminationPass` and replaces the product-facing per-pass
Booleans with one product-facing `enabled` flag. WA-07a adds bounded
`ConstantOperandSimplificationPass` and closes the four-stage fixed point.
WA-07b adds `BooleanTernarySimplificationPass` as the fifth stage and extends
fixed-point closure across both simplification passes. The original two-,
three- and four-stage selections remain internal regression oracles. No signal
renaming, generated-Verilog parsing, formatting or broader optimization pass is
implemented here.

The final production handoff remains WA-08. Until then the production
`CanonicalIrHandoff` is read-only; test-only native bridges demonstrate the
same canonical decisions against generated RTL without making the standalone
workspace a root build dependency.

## WA-03 safety contract

A direct wire alias is eligible only when the canonical design proves:

- exactly one full-object continuous driver is a direct signal reference;
- the alias is internal combinational, not an output/clock/reset/memory/state or
  bidirectional/hierarchy-boundary object;
- alias and source have equal signedness, value semantics and packed width for
  every admitted parameter binding, with positive widths throughout;
- source visibility and every rewritten reference are legal by scope identity;
- the substitution cannot introduce a combinational cycle; and
- naming/observability metadata is complete and no preservation, comment,
  attribute, public, probe or source contract would be discarded.

Parameter equivalence is checked over the complete bounded admitted domain,
not a few representative defaults. Failure or an unprovable fact retains the
candidate. Diagnostic and report ordering is deterministic.

## WA-04 unnamed direct-alias pass

`UnnamedWireAliasEliminationPass` discovers candidates only from
`NameOrigin.Unnamed`. It never recognizes `_zz_*` text. For each proven eligible
candidate it replaces exact references, removes the exact declaration and sole
assignment, and leaves all surviving identifiers unchanged. It reaches a fixed
point for chains and fanout and publishes the original input on a validation
failure.

The pass excludes operators, muxes, literals, slices, indexes, concatenations,
casts, resizes, procedural targets, clocks, resets, memory objects, public
exports and incomplete naming/observability. The test-only
`UnnamedWireAliasNativePhase` mirrors the canonical decision into the real
shared witness before backend name allocation; it is not production handoff
or a component-specific implementation.

## WA-05 named direct-alias pass

`NamedWireAliasEliminationPass` discovers candidates only from
`NameOrigin.Explicit`. It applies the same bounded direct-reference contract
with the stricter named observability checks. An eligible named internal alias
and its assignment are removed without transferring the removed name to its
source or inventing a replacement name. Every removed name and available
source location is recorded deterministically.

Public/hierarchical names, preservation/probe contracts, black-box boundaries,
comments, attributes, unknown naming provenance, opaque metadata and all
non-direct expressions remain unchanged. The transformation does not recognize
source filenames, emitted identifiers, component names or library classes.

`NamedWireAliasNativePhase` is a test-only bridge over real pre-emission native
graph identities. It executes the canonical named pass, verifies complete
reference replacement and unchanged surviving native names, then removes only
the exact candidate declaration/assignment. `ParameterizedStreamFifoNamedPassWitness`
emits the shared source through the existing backend while preserving symbolic
`WIDTH` and `DEPTH`. No generated HDL text controls eligibility.

## WA-06 ordered direct-alias pipeline

`WireAliasPassPipeline` is an optional immutable entrypoint over the same
canonical post-parameterization `Design` or production read-only
`CanonicalIrHandoff`. Its configuration is disabled by default. WA-07 replaces
the historical public per-pass flags with one all-or-none product flag while
preserving individual selections only for internal regressions. WA-07a adds the
fourth stage and WA-07b adds the fifth:

```scala
WireAliasPassConfiguration()                  // no pass
WireAliasPassConfiguration(enabled = false)   // no pass
WireAliasPassConfiguration(enabled = true)    // unnamed aliases, named aliases,
                                             // unnamed expressions, constants,
                                             // Boolean ternaries, to a fixed point
```

The historical direct-alias stages remain supported as internal proof oracles:

| Regression selection | Historical order |
| --- | --- |
| Unnamed only | unnamed |
| Named only | named |
| Both direct stages | unnamed-then-named |

The pipeline supports alias chains and fanout, preserves deterministic ordered
per-stage reports, and reaches idempotent IR. Repeated execution from the same
input produces identical reports and is suitable for byte-identical repeated
emission. On any validation failure it returns the original pre-pipeline design;
no partially transformed design is published. No pass or pipeline recognizer
uses a component/library class, module name, signal name or source filename.

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

The public `WireAliasPassConfiguration(enabled = true)` now executes all five
passes in the fixed order. `enabled = false` executes none. Tests cover literal,
nested and fanout expressions, exact identity, type fences, cycles, scopes,
metadata, procedural source and receiver exclusions, selection composition and
rejection, determinism, atomic failure, fixed points and idempotence on both
supported Scala versions.

`ParameterizedStreamFifoExpressionPassWitness` emits the expression-only
candidate. `ParameterizedStreamFifoAllPassWitness` retains the historical
three-stage candidate using package-private regression selection; its report
explicitly does not claim execution of the current five-stage common flag.
Both are test-only bridges; WA-08 owns production publication and writeback.

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

The historical four-stage pipeline runs unnamed aliases, named aliases, unnamed
expressions, then constant operands, repeating in that order to a checked fixed
point. The current common pipeline additionally executes Boolean ternaries in
each round. Rewrites are recorded separately as
`simplifiedExpressions`/`simplifiedCount`; `eliminatedCount` still counts only
removed wires. A failed stage rolls back to the original pre-pipeline input.
Standalone simplification retains input item order, surviving names, comments,
declaration and driver identities.

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
to the reference captured before **all** passes. Both proof legs cover all
512 legal WIDTH/DEPTH bindings, not just defaults or selected corners. Four-state
simulation is an additional mandatory gate, not a claim made from two-state
formal alone. The four-stage native witness explicitly selects its historical
stages and is not redirected to the current five-stage flag.

The [WA-07a qualification record](wa07a-completion-evidence.md) identifies the
verified implementation revision, complete aggregate evidence and required
independent CI for its completion commit. WA-08 additionally depends on WA-07b
being qualified, completed and merged; production execution and writeback
remain WA-08 scope.

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

## WA-07b — recursive Boolean ternary simplification (in progress)

`BooleanTernarySimplificationPass` visits every supported pure continuous RHS
child bottom-up, including mux conditions and branches, operands, concatenations,
selects and expression indices, casts and resizes. It removes opposite unsigned
one-bit constant branches, including nested occurrences under nonmatching
parents. Scope and preservation exclusions match the existing simplification
contract. Procedural assignments and state are not changed.

A proven one-bit no-Z logical/comparison condition can replace its positive
ternary directly. Other conditions keep `!!condition`, preserving truth
conversion and raw-Z-to-X behavior. The inverse uses `!condition`, which stays
one bit in a widened context. Using raw `~condition` on an unnormalized vector,
or letting a bitwise complement widen, is not allowed. Wider, signed and
parameter-dependent branch types without a proof are retained.

The implementation adds recursive positive/negative fixtures, four-state
comparisons over 16,384 input patterns, independent functional mutations,
symbolic WIDTH=1..8 rule proofs, cross-stage fixed-point tests and actual native
before/after emission. Its shared StreamFifo standalone and all-five outputs
must both compare with the unchanged pre-all-passes reference across all 512
admitted bindings. A genuine no-op on the shared witness is reported explicitly;
independent native fixtures must still exercise both positive and inverse rules.

The [implementation notes](wa07b-implementation-notes.md) describe these layers
and artifact paths. Until all required final-head gates pass, WA-07b remains
unchecked and WA-08 remains blocked. Test code is not successful proof evidence.

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
python3 morphhdl-passes/scripts/check-wa07b-ternary-pass.py --self-test
python3 morphhdl-passes/scripts/check-wa07b-ternary-pass.py
python3 morphhdl-passes/scripts/validate_wire_assignment_equivalence.py --self-test
(
  cd morphhdl-passes
  sbt -batch +test
)
```

The pinned CI toolchain runs the native witness and strict legality gates with:

```bash
# Includes run-wa07a-regression.sh and its historical prerequisites.
bash morphhdl-passes/scripts/run-wa07b-regression.sh

python3 morphhdl-passes/scripts/validate_wire_assignment_equivalence.py \
  --shared-witness morphhdl-passes/build/formal/wire_assignment_ir/generated/parameterized_stream_fifo.v \
  --output morphhdl-passes/build/formal/wire_assignment_ir/evidence \
  --prove-pending WA-07a --prove-pending WA-07b \
  --check-determinism
```

The regression publishes:

- `morphhdl-passes/build/pass-outputs/wire-alias-unnamed.v`;
- `morphhdl-passes/build/pass-outputs/wire-alias-named.v`;
- `morphhdl-passes/build/pass-outputs/wire-alias-combined.v`;
- `morphhdl-passes/build/pass-outputs/wire-expression-unnamed.v`;
- `morphhdl-passes/build/pass-outputs/wire-assignment-all.v` (historical three-stage);
- `morphhdl-passes/build/pass-outputs/constant-operand-simplification.v`;
- `morphhdl-passes/build/pass-outputs/wire-assignment-four-pass.v` (historical four-stage);
- `morphhdl-passes/build/pass-outputs/boolean-ternary-simplification.v`; and
- `morphhdl-passes/build/pass-outputs/wire-assignment-five-pass.v`.

All nine must be compared to the same captured pre-pass design. WA-07b extends
the one-flag standalone pipeline with recursive ternary simplification. WA-08
remains the separately reviewed production handoff into MorphHDL-owned generation
flow.

### WA-07a and WA-07b complete-domain proof shards

The native generation job emits one pre-pass reference and all nine candidates.
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
rerun removes any stale aggregate PASS. Only this final job can qualify the full
9 x 512 x 2 = 9,216 equivalence proofs and their 9,216 comparison-reachability
proofs for WA-07b. The historical WA-07a qualification had seven candidates and
7,168 results per proof category. Those results are not reused to qualify a new
source revision. Native legality and four-state evidence remains in the separate
native-input artifact; actual solver records are retained in all shard artifacts.

Run `python3 morphhdl-passes/scripts/test_wire_assignment_shards.py -v` to test
partitioning and fail-closed aggregation. These synthetic metadata mutation tests
are not substitutes for actual RTL proofs. The default command without shard
options still proves the entire domain in one process.

The runner submits at most the worker count of binding proofs, checks completed
failures before submitting replacements, drains already-running work, and emits
binding progress while retaining manifest order in its results. A failed
preflight removes a stale gate status without deleting previous diagnostics.

Each successful Yosys preparation records the input, preparation script and
RTLIL hashes. Aggregation checks those records, the exact parameter bindings,
and the configuration and input copies retained by SBY for reachability and
mutation runs. Positive equivalence uses the complete property proof described
below. Repeated-run evidence cannot be a symlink to the first run. These checks
detect stale or altered workflow artifacts; they do not replace solver execution
or permit partial-domain qualification.

### Complete output-property proofs

The large shared witness made whole-output PDR expensive. The positive proof
now expands every output equality into its complete set of bit equalities,
retaining the same guards, explicit clock edges, reset sequence and independent
uninitialized state. The original miter still supplies each binding's mandatory
SBY comparison-reachability check; the existing functional SBY mutations remain.

Yosys preserves every assertion and assumption and produces a complete AIG.
ABC folds the temporal assumptions before extracting each property's sequential
cone, then simplifies and proves that cone with PDR. The gate requires an exact
mapping for every property, including constant and repeated properties. Within
one binding only, byte-identical normalized formulas may share a proved
representative. The representative is regenerated and its serialized formula
must match exactly before its proof is accepted. Parameter bindings, pass
candidates and repeated runs each execute independently; no width or bit is
sampled and no prior binding's proof is reused.

Each qualification retains the full input model, all property mappings,
normalized formulas, canonical commands, actual solver records and invariants.
The aggregator validates this evidence in addition to the original reachability
and mutation records. A timeout, counterexample, omitted property, changed model
or unverified result cannot qualify. The per-binding timeout remains unchanged.
The required equivalence results count complete binding qualifications, each
of which may contain several distinct PDR property proofs.

Each nonconstant representative uses a fixed PDR search sequence: default,
monolithic, then monolithic with structural flop priorities, each with
`-C100 -D100 -F64`, then a monolithic search with `-C256 -D256 -F64`, then
`-m -i -p` with the same 256-conflict and 64-frame bounds, followed by the
original `-m -y -r` search. The `-i -p` profile changes clause-pushing order
and reuses blocked proof obligations; it does not change the formula or add
assumptions. Every retry starts from the same canonical model. Each profile's
declared bounds drive both its command and its accepted limit diagnostic.
A later search starts only when a successful command explicitly reports the
configured conflict or frame limit and an undecided property. Counterexamples,
timeouts, tool errors and malformed evidence stop qualification. All attempts
share the existing 600-second binding budget; an undecided attempt never
supplies a proof.

Schema 3 evidence retains every attempted script, model, execution record, log
and clause dump in order. The checker matches each attempted model to the
extracted formula and requires a final proved property with a verified
invariant. Clauses dumped after an effort limit are retained as unverified
diagnostics. Their canonical contents participate in repeated-run determinism,
and omitted attempts or extra attempts after success are rejected.

`test_wire_assignment_cone_tools.py` exercises the actual proof backend with
positive cases and mutations of clock behavior, temporal assumptions, independent
initial state, comparison reachability and later output properties. These small
controls do not replace any shared-witness binding.
