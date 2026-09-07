# WA-07b implementation and qualification notes

**Status: implementation in progress; not qualified, complete or merged.**

The controlling entry remains unchecked in
[morphhdl-ir-wire-assignment-passes-todo.md](morphhdl-ir-wire-assignment-passes-todo.md).
This document describes code and proof obligations, not successful test results.
Production publication and writeback remain WA-08 scope.

## Canonical transformation

`BooleanTernarySimplificationPass` visits every represented child of a pure
continuous RHS bottom-up. A nonmatching parent does not hide a nested matching
mux. Eligibility depends on canonical identities, driver kinds, preservation
metadata and typed expression semantics, never component or generated names.
Declarations, assignment identities, comments, attributes, owners and scope
structure are retained. Invalid input or output returns the original design
without accepted-rewrite evidence.

Only opposite unsigned one-bit constants, or proven identity unsigned fences
around those constants, are admitted initially. Wider and signed branches,
parameter-dependent branch widths, and unsupported forms are retained. The
condition itself can be any supported pure expression, including a multi-bit
expression. No parameter default is used to recover symbolic intent.

For a self-determined one-bit, no-Z truth producer such as a comparison or
logical operator, the positive mux becomes that producer. Otherwise the
positive form keeps logical Boolean normalization. The inverse form uses
logical negation, not a context-sized bitwise complement:

```text
truth_producer ? 1'b1 : 1'b0 -> truth_producer
condition      ? 1'b1 : 1'b0 -> !!condition
condition      ? 1'b0 : 1'b1 -> !condition
```

These are semantic examples, not a claim of newly generated proof artifacts.
For a one-bit condition, `!condition` has the requested inverse Boolean value.
Unlike an unprotected `~condition`, its result stays one bit when an enclosing
expression or assignment is wider. A raw Bool port may carry Z: `!!condition`
normalizes Z to X as the original opposite-branch ternary does. Direct raw
positive passthrough would not preserve that behavior.

## Pipeline

`WireAliasPassConfiguration(enabled = true)` selects all five stages in order:
unnamed aliases, named aliases, unnamed expression temporaries, constant
operands, and Boolean ternaries. The default `false` executes none.

The shared pipeline repeats its ordered stages to a fixed point. Its strict
lexicographic progress measure first counts declarations, then binary/mux
nodes, then total expression nodes. Boolean normalization may introduce unary
nodes but removes a mux, so it makes progress. Separate rewrite reports retain
module identity, driver identity, expression path and rule. No local expression
rewrite is reported as an eliminated wire.

Historical two-, three- and four-stage proof selections remain private test
facilities. The WA-07a native witness explicitly retains the historical four
stages; it is not silently redirected to the new five-stage product selection.

## Verification layers added by this implementation

`BooleanTernarySimplificationPassSpec` covers both polarities, the compound
comparison example, every supported child position, 128 nested inverse muxes,
nonmatching parents, symbolic and widened signed contexts, preservation and
procedural exclusions, nested represented scopes, invalid input, renamed
components, standalone idempotence and cross-stage fixed points in both
WA-07a/WA-07b directions.

`BooleanTernaryFourStateSpec` builds an independent structured expression
oracle from the actual canonical before/after trees. Both the isolated pass
and the complete five-stage pipeline compare directly with the same original
pre-all-passes tree. Its simulator enumerates 16,384 four-state patterns over
seven input bits, including known-one-plus-X/Z conditions. Mutations cover raw
Z passthrough, wrong polarity, unnormalized multi-bit values and widened
bitwise inversion. Two-state SAT checks independently cover the applicable
functional mutations. Symbolic input/result widths are also proven for every
admitted WIDTH=1..8 binding, signed and unsigned, in both proof legs.

`BooleanTernaryNativeBridge.scala` is a test-only native Boolean-facet witness.
It reuses the existing eligibility and actual-RHS codec from WA-07a, invokes
the canonical pass and decodes its actual output before native Verilog
emission. Unrepresented native expressions are retained, not guessed. The
canonical pass is not restricted to this Boolean test-bridge facet.

`BooleanTernaryGenericNativeWitness` uses ordinary Spinal component source and
requires real positive and inverse rewrites through the native backend. Its
before, standalone and all-five outputs are compared by four-state simulation
and formal proof. A no-op on the shared StreamFifo is reported honestly and
cannot replace this independent positive witness.

## Commands and retained evidence

```bash
(cd morphhdl-passes && sbt -batch ++2.12.18 test)
(cd morphhdl-passes && sbt -batch ++2.13.12 test)
python3 morphhdl-passes/scripts/check-wa07b-ternary-pass.py --self-test
python3 morphhdl-passes/scripts/check-wa07b-ternary-pass.py
bash morphhdl-passes/scripts/run-wa07b-regression.sh
```

The runner includes the historical native regressions before generating its
new candidates. `--after-wa07a` is used only when the same workflow job has
just completed that historical leg.

New candidate paths are `build/pass-outputs/boolean-ternary-simplification.v`
and `build/pass-outputs/wire-assignment-five-pass.v`. Their common reference
is the unchanged `build/formal/wire_assignment_ir/generated/parameterized_stream_fifo.v`.
The proof manifest retains all historical legs and adds both new legs over
all 512 WIDTH=1..64 by DEPTH=1..8 bindings. The existing 16-shard workflow
requires exact full-domain union, repeated-run determinism and functional
mutation evidence. Every candidate compares with the common pre-pass snapshot,
never merely the previous stage.

Rule-oracle artifacts are under `build/wa07b-rule-oracle`; actual native
example RTL and reports are under `build/wa07b-native`; repeated artifacts and
strict Verilog-2001, lint, synthesis and representative FIFO simulation logs
are retained by the regression runner. Actual native examples are also printed
between `WA07B_ACTUAL_NATIVE_*` markers in the job log.

Completion requires successful final-head boundary/static gates, both Scala
lanes, actual native emission and tool checks, full-domain formal aggregation,
mutation controls, deterministic emission and review. The mere presence of
these tests or files does not establish success. Until those gates pass, leave
WA-07b unchecked and WA-08 blocked.
