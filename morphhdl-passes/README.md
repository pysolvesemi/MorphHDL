# MorphHDL IR passes

This is the standalone MorphHDL-owned workspace for the two optional
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
2.13.12. Both pass selections are disabled by default.

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
used by both transforms. It proves a sole continuous full-object direct driver,
exact packed signedness/value semantics, width equality over the complete
admitted parameter domain and retained generate domains, legal scope
replacement, and continuous-cycle freedom.

It fails closed for observability, hierarchy, public export, clock/reset,
tri-state, bidirectional, memory-port, instance-port, procedural, comment,
attribute, preservation, probe and incomplete-control contracts. Adversarial
fixtures cover mismatching admitted bindings, multiple/partial drivers,
non-reference expressions, sibling scopes, dependency cycles, registered
feedback, domain-expansion limits and deterministic repeated analysis.

WA-03 also supplies strict Verilog-2001 compilation, lint and synthesis,
representative simulation, complete-domain formal equivalence, a live mutation
that must fail with a retained counterexample, and deterministic repeated proof
evidence. Sequential miters force a reset-active clock transition before
assertions become active.

WA-03 does not eliminate an alias. WA-04 and WA-05 remain the only transforms
for unnamed and explicitly named aliases.

## WA-04 — unnamed alias elimination

`UnnamedWireAliasEliminationPass` is disabled by default and executes only when
`eliminateUnnamedAliases = true`. It selects candidates solely from canonical
`NameOrigin.Unnamed` provenance, never from emitted-name text.

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
WA-07 production handoff.

## WA-05 — named alias elimination

`NamedWireAliasEliminationPass` is disabled by default and executes only when
`eliminateNamedAliases = true`. It selects only canonical
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
allocation. It does not create the WA-07 production handoff.

## WA-06 — ordered optional pipeline and closure

`WireAliasPassPipeline` is the single optional canonical MorphHDL-IR entrypoint
for these transforms. Both remain disabled by default. Configuration may enable either pass independently; when both are enabled, the
fixed order is unnamed then named. The result retains one ordered report per executed stage, so named
and unnamed decisions cannot be conflated.

A stage consumes the validated output of the preceding stage. Any failed stage
publishes the original pipeline input, preserving atomic fail-closed behavior.
Successful execution reports `Changed` when either stage transforms the design
and `Unchanged` only after both enabled stages reach their fixed point.

Cross-Scala tests validate alias chains and fanout without parsing emitted
Verilog, independent enablement, exact unnamed-then-named order, deterministic
reports, idempotent IR, atomic rollback, surviving metadata/reference identity,
module/source-path independence, and the complete 512-binding `WIDTH`/`DEPTH`
domain.

The test-only `OrderedWireAliasNativePhase` first validates the real canonical
pipeline order on a component-neutral identity graph, then executes the already
reviewed WA-04 and WA-05 native graph rewrites in that same order on the shared
witness before name allocation. `ParameterizedStreamFifoCombinedPassWitness`
emits the combined candidate and a machine-readable ordered report. This proof
bridge is not the WA-07 production integration.

`run-wa06-regression.sh` generates the one common pre-pass reference plus each
individual and combined candidate. It rejects empty transformations, verifies
byte-identical repeated emission and report output, runs strict Verilog-2001
compile/lint/synthesis, and executes representative parameterized simulations.
The formal harness then proves WA-04, WA-05 and the ordered combination directly
against the unchanged common pre-pass StreamFifo reference for all 512 admitted
bindings. It never compares only against the preceding pass.

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
python3 morphhdl-passes/scripts/validate_wire_assignment_equivalence.py --self-test
(
  cd morphhdl-passes
  sbt -batch +test
)
```

The pinned CI toolchain runs the native witness and strict legality gates with:

```bash
bash morphhdl-passes/scripts/run-wa06-regression.sh

python3 morphhdl-passes/scripts/validate_wire_assignment_equivalence.py \
  --shared-witness morphhdl-passes/build/formal/wire_assignment_ir/generated/parameterized_stream_fifo.v \
  --output morphhdl-passes/build/formal/wire_assignment_ir/evidence \
  --check-determinism
```

The regression publishes:

- `morphhdl-passes/build/pass-outputs/wire-alias-unnamed.v`;
- `morphhdl-passes/build/pass-outputs/wire-alias-named.v`; and
- `morphhdl-passes/build/pass-outputs/wire-alias-combined.v`.

All three are compared to the same captured pre-pass design. WA-06 completes
standalone pipeline orchestration and regression closure. WA-07 remains the
separately reviewed production handoff into MorphHDL-owned generation flow.
