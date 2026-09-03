# MorphHDL IR passes

This is a standalone MorphHDL-owned workspace for the two optional
wire-assignment passes controlled by
[`morphhdl-ir-wire-assignment-passes-todo.md`](morphhdl-ir-wire-assignment-passes-todo.md).

The workspace is intentionally not part of the repository root SBT or Mill
aggregate. It must not modify upstream-owned SpinalHDL source. The passes
consume the versioned `morphhdl.ir.v1` canonical IR after parameterization and
before Verilog-2001 emission; they do not parse generated Verilog.

Every adapter and pass is component-generic. Decisions may depend only on
validated canonical identities and metadata. They must never special-case
`StreamFifo`, `StreamFifoCC`, `ParameterizedStreamFifo`, another component
class or module name, a source filename, or a generated identifier.

WA-01 established:

- the isolated cross-Scala SBT build;
- immutable pass configuration, result, diagnostic and elimination-report
  contracts;
- the pass-workspace boundary guard and its self-tests; and
- CI validation for Scala 2.12.18 and 2.13.12.

WA-02 adds:

- a nested-build dependency on the separately owned `morphir` project without
  adding this workspace to the repository root aggregate;
- a read-only adapter that accepts only a validated canonical-v1 `Design` at
  `PostParameterizationPreEmission`;
- exact identity-indexed access to declarations, drivers, reference
  occurrences, packed types, parameter domains, naming provenance, source
  locations and observability metadata;
- fail-closed canonical diagnostics for incomplete or invalid metadata;
- a mutation-tested source guard against generated-HDL parsing, regex/name
  recognition, Spinal implementation coupling and component-specific logic;
  and
- the shared parameterized StreamFifo witness at
  [`examples/ParameterizedStreamFifo.scala`](examples/ParameterizedStreamFifo.scala).

WA-02 does not eliminate, rewrite or rename any declaration, driver, reference
or expression.

WA-03 adds the shared alias-elimination gates required before either pass may
transform canonical IR:

- `WireAliasSafetyGate`, a read-only, component-generic eligibility analysis
  that proves one continuous full-object direct reference, exact packed
  signedness and value semantics, width equality over the complete admitted parameter domain and
  generate-index domain, legal lexical replacement and
  continuous-cycle freedom;
- explicit fail-closed reasons for every observability, hierarchy,
  bidirectional, memory-port, instance-port, comment and attribute exclusion;
- adversarial Scala fixtures for mismatching parameter bindings, multiple and
  partial drivers, non-reference expressions, sibling scopes, combinational
  cycles, registered feedback, domain-expansion limits and deterministic
  repeated analysis;
- strict Verilog-2001 compilation, lint and synthesis plus representative
  simulation for generic combinational and sequential alias fixtures;
- unbounded formal equivalence over every admitted binding of those generic
  fixtures, together with a live mutation that must fail and retain a
  counterexample; and
- two independent proof runs whose deterministic inputs, configurations and
  evidence must have the same SHA-256 artifact-set signature.

WA-03 does not eliminate an alias. WA-04 and WA-05 remain the only increments
allowed to transform unnamed and named aliases, respectively.

## Common witness and formal-equivalence baseline

The parameterized StreamFifo source is a common regression and formal witness,
not a pass implementation template or special case. Every transforming pass
and every supported pass combination must run on this witness while preserving
symbolic `WIDTH` and `DEPTH`. Small generic positive and negative fixtures are
also required so the witness cannot become a hidden component recognizer.

For each proof run, the reference Verilog must be emitted from the canonical
design snapshot immediately before the entire passes phase, before any pass has
executed. Verilog emitted after each individual pass and after each supported
pass combination must be formally compared against that one common pre-pass
reference through the same structured backend. Comparing only with the output
of the preceding pass is insufficient.

The formal comparison must use identical legal parameter assumptions and
bindings on both sides. A flow that concretizes parameters must cover the
complete admitted bounded parameter domain.

WA-03 generates the shared witness afresh, copies it once to
`common-pre-pass/reference.v`, records its SHA-256, audits the complete admitted
`WIDTH=1..64` by `DEPTH=1..8` Cartesian domain, and runs strict
Verilog-2001 compile/lint/synthesis plus representative simulations. Its
manifest already contains fail-closed slots for WA-04, WA-05 and the WA-06
combined pipeline. A slot remains inactive while its roadmap item is open; as
soon as that item is checked, a missing candidate is an error and every one of
the 512 admitted witness bindings must be proved against the unchanged common
reference. The harness therefore cannot silently accept a partial parameter
sample or a comparison against the preceding pass.

## Local validation

From the repository root, the source and Scala gates are:

```bash
bash morphhdl-passes/scripts/test-boundary-guard.sh
python3 morphhdl-passes/scripts/check-wa02-adapter-boundary.py --self-test
python3 morphhdl-passes/scripts/check-wa02-adapter-boundary.py
python3 morphhdl-passes/scripts/check-wa03-gates.py --self-test
python3 morphhdl-passes/scripts/check-wa03-gates.py
python3 morphhdl-passes/scripts/validate_wire_assignment_equivalence.py --self-test
(
  cd morphhdl-passes
  sbt -batch +test
)
```

The full formal gate requires the pinned CI toolchain and a freshly generated
shared witness. The workflow runs the equivalent of:

```bash
sbt -batch \
  'set morph / Test / unmanagedSources += file("morphhdl-passes/examples/ParameterizedStreamFifo.scala")' \
  'morph / Test / runMain morphhdl.examples.ParameterizedStreamFifoExample morphhdl-passes/build/formal/wire_assignment_ir/generated'

python3 morphhdl-passes/scripts/validate_wire_assignment_equivalence.py \
  --shared-witness morphhdl-passes/build/formal/wire_assignment_ir/generated/parameterized_stream_fifo.v \
  --output morphhdl-passes/build/formal/wire_assignment_ir/evidence \
  --check-determinism
```

The contracts keep both passes disabled by default. Transforming alias
elimination starts only in WA-04.
