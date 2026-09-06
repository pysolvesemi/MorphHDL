# Increment 60 — Native signed `SInt` Verilog roadmap

**Status:** 60a through 60e qualified; 60f in qualification; 60f and 60g remain unchecked.

The frozen rules and baseline limits are in [the signedness contract](increment-60-signedness-contract.md).

**Dependency:** Increment 59 must be implemented, reviewed and merged before
Increment 60a starts. The child sequence below is serial. Completion of an
individual child does not complete parent Increment 60.

**Primary target:** MorphHDL single-source, strict IEEE 1364-2001 Verilog
publication. Ordinary `SpinalVerilog` output remains upstream-compatible and
byte-for-byte unchanged by default unless a later, separately reviewed opt-in
configuration is approved.

## Original baseline finding

Before Increment 60c, MorphHDL did **not** implement native signed declarations
for `SInt`. The baseline paths reviewed for this roadmap showed the following:

- `core/src/main/scala/spinal/core/internals/VerilogBase.scala` renders
  `TypeSInt` with the same packed range spelling as `TypeUInt`; it does not add
  the Verilog `signed` keyword.
- `ComponentEmitterVerilog.scala` combines that range with ordinary `wire` or
  `reg` declarations for ports, internal signals and expression wrappers.
- Signed arithmetic, relational operators and arithmetic right shift recover
  signed interpretation by emitting `$signed(...)` around operands.
- MorphHDL's external parameterized-Verilog publication path rewrites symbolic
  ranges and structure but does not replace this declaration/expression
  signedness policy.
- `morphhdl/examples/contracts/symbolic_data_shapes.v` consequently declares
  `SInt` ports, internal wires and registers as unsigned packed vectors.

At that baseline, parameterized `SInt` widths were retained, but native Verilog
signedness was not. Increment 60c now provides explicit opt-in signed
declarations through `MorphSignedDeclarations.enable(config)`, with the
[qualified scope and retained boundaries](increment-60c-signed-declarations.md)
documented separately. Declaration-only mode retains existing expression casts.
Increment 60d adds separately enabled pure-`SInt` cast cleanup through
`MorphSignedCasts.enable(config)`, with the [exact removal rule and qualification](increment-60d-pure-sint-casts.md).
Neither mode is enabled by default.

## Goal

A scalar SpinalHDL `SInt` must publish as a native signed Verilog object wherever
its declaration owns the value's arithmetic interpretation, including
parameterized widths:

```verilog
input  wire signed [WIDTH-1:0] a;
input  wire signed [WIDTH-1:0] b;
reg          signed [WIDTH-1:0] accumulator;
output wire signed [WIDTH-1:0] result;

assign result = (a + b) * accumulator;
```

The goal is **minimal necessary casts**, not a global ban on `$signed`. Casts
must remain at real type boundaries, for example when an unsigned `Bits` or
`UInt` expression, a concatenation, or a part-select is intentionally consumed
as an `SInt`.

## Mandatory semantic rules

- Signedness authority comes from the typed graph/canonical IR and exact
  `TypeSInt` identity. It must never be inferred from Scala source text,
  component names, emitted signal names, concrete values or a Verilog regex.
- Declaration conversion and cast removal are separate increments. Signed
  declarations land first while the existing casts remain, providing a small
  equivalence step before expression cleanup.
- The implementation must model expression signedness, width and context
  together. An assignment to a signed destination must not be assumed to repair
  an incorrectly sized or unsigned right-hand subexpression.
- Direct scalar `SInt` ports, nets, variables and exact `SInt` memory elements
  are signed. `Bits`, `UInt`, `Bool`, addresses, masks and structural packed
  aggregate carriers remain unsigned.
- A flattened `Vec` or `Bundle` transport is not made signed merely because it
  contains one or more `SInt` leaves. Each reconstructed scalar `SInt` leaf must
  recover signed interpretation at its exact boundary.
- Part-selects, bit-selects, concatenations, unsigned literals, mixed
  signed/unsigned operators and uncertain external boundaries are treated as
  unsigned or unknown until an exact typed rule proves otherwise.
- `$signed` may be removed only when the emitted operand or complete
  subexpression is already signed under strict Verilog-2001 rules for every
  legal parameter value.
- BlackBox module source remains externally owned. MorphHDL may declare and use
  its local typed `SInt` connection as signed, but must not rewrite or invent the
  external module declaration.
- Ordinary parameter-free `SpinalVerilog`, VHDL generation and every unrelated
  data type must retain existing behavior when the new mode is disabled.
- Any required native `core` emitter change must be small, mode-gated, listed in
  the approved native-change manifest and covered by exact source-scope tests.

## Dependency graph

The Increment 60 chain is deliberately serial because each step establishes the
semantic authority used by the next:

`60a -> 60b -> 60c -> 60d -> 60e -> 60f -> 60g`

Increment 60 is complete only when every child checkbox below is `[x]` on
`parameterized-verilog` and all final-head gates pass.

## Increment plan

- [x] **Increment 60a — Baseline, semantic contract and independent oracle**

  **Dependencies:** Increment 59 implemented and merged.

  Add one ordinary SpinalHDL fixture covering fixed and parameterized `SInt`
  input/output ports, internal wires, registers, combinational and procedural
  assignments, nested arithmetic, signed comparisons, unary negation,
  arithmetic right shift, resize, mux, slice, concatenation, memory, hierarchy
  and a typed BlackBox connection. Record the current unsigned declarations and
  cast-heavy expressions as the compatibility oracle and include a focused
  reproducer for nested `$signed($signed(...))` output.

  Freeze the target rules for declaration signedness and expression transfer
  before changing emission. Establish a feature-disabled baseline path and an
  independent candidate path from the same ordinary component source. Add a
  mutation that changes one negative-value result and must be detected by the
  later equivalence harness. Register the Increment 60 parent entry in
  `docs/morphhdl/parameterized-verilog-todo.md` only after Increment 59 is
  present and `[x]` on the merged base.

- [x] **Increment 60b — Typed declaration and expression signedness authority**

  **Dependencies:** Increment 60a implemented and merged.

  Add a target-neutral signedness fact to the canonical pre-emission handoff or
  an equivalently exact emitter analysis. At minimum distinguish signed scalar,
  unsigned scalar/aggregate, Boolean and unknown/context-dependent results.
  Define transfer rules for references, literals, unary operators, arithmetic,
  comparison, shifts, muxes, casts, resize, concatenation, selection, memory
  reads and hierarchy boundaries. Validate that every emitted declaration and
  every cast-elimination decision maps back to exact graph identity.

  Implemented by the [exact graph signedness analysis](increment-60b-signedness-authority.md).
  Both Scala lanes qualify exact use identity, conservative transfer, deterministic
  replay and byte-identical observer-enabled output against the sealed 60a oracle.

  This increment must not change published Verilog yet. It supplies unit tests,
  fail-closed diagnostics and deterministic replay for the signedness analysis.
  A text parser, emitted-name table or blanket `TypeSInt` string replacement is
  not an acceptable substitute.

- [x] **Increment 60c — Native signed declarations with casts retained**

  **Dependencies:** Increment 60b implemented and merged.

  Under an explicit MorphHDL publication mode, emit strict Verilog-2001 signed
  declarations for scalar `SInt` input, output and inout ports; internal `wire`
  and `reg` objects; process-driven outputs; expression temporaries/wrappers;
  exact scalar function arguments/results where applicable; and `Mem[SInt]`
  packed elements. Preserve symbolic ranges such as
  `wire signed [WIDTH-1:0]` and retain the existing `$signed(...)` expression
  casts unchanged in this increment.

  Cover scalar width one, odd widths, fixed widths and compound symbolic width
  expressions. Keep structural flattened aggregate carriers unsigned unless
  they represent exactly one scalar `SInt`. Prove strict Verilog-2001 parser,
  lint and synthesis acceptance before any cast is removed. With the mode off,
  ordinary `SpinalVerilog` must remain byte-identical to its baseline.

  Implemented and qualified by the [native signed-declaration mode](increment-60c-signed-declarations.md).
  Both Scala lanes pass declaration, identity and isolation regressions; strict
  Verilog-2001 tool checks; independent-reference equivalence at WIDTH 1/5/8/32;
  the sealed 60a oracle; and a genuine mutation counterexample. Native function
  fallback results require exact fixed widths; parameter-dependent result sizing
  fails closed until the later literal/resize boundary is qualified. This does
  not close 60d cast elimination or 60e aggregate/boundary work.

- [x] **Increment 60d — Pure-`SInt` redundant cast elimination**

  **Dependencies:** Increment 60c implemented and merged.

  Use the typed signedness facts to remove `$signed` only from expression trees
  composed entirely of already-signed scalar `SInt` references and operators
  whose Verilog-2001 result remains signed. Cover addition, subtraction,
  multiplication, division, remainder, unary negation, signed relational
  comparisons and arithmetic right shift, plus nested combinations of those
  operators. Preserve widths, parenthesization, precedence and existing
  overflow/truncation behavior.

  Add readability contracts requiring that the pure-`SInt` fixture contains no
  redundant casts and that generated output contains no
  `$signed($signed(...))` pattern. These checks must complement, not replace,
  semantic equivalence. Division/remainder proofs must constrain the divisor to
  non-zero where the language result is otherwise undefined.

  Implemented and qualified by the [pure-`SInt` cast policy](increment-60d-pure-sint-casts.md).
  Both Scala lanes pass all 87 tests across seven suites, with 29 byte-identical
  generated files across fresh JVMs and compiler lanes. The pure fixture drops
  from 53 casts to zero while preserving native intermediate-width wrappers.
  Strict Verilog-2001 parsing, simulation, lint, synthesis and independent
  WIDTH 1/5/8/32 equivalence pass, including nonzero-divisor proofs, retained
  boundary and memory checks, the immutable 60a oracle and three genuine
  mutation counterexamples. Cleanup remains opt-in; unsupported symbolic signed
  widening and `cutLongExpressions=false` still fail closed. This does not close
  60e boundary minimization or the 60f/60g rollout gates.

- [x] **Increment 60e — Signedness boundaries, aggregates and hierarchy closure**

  **Dependencies:** Increment 60d implemented and merged.

  Retain or introduce the minimum cast needed at every non-pure boundary:
  `Bits`/`UInt` to `SInt`, `SInt` to unsigned consumers, sized and unsized
  literals, negative constants, bit/part selection, concatenation, replication,
  mux/`when` alternatives, resize and sign extension, equality/relational
  mixtures, logical/reduction operators, shift amounts and assignment sizing.
  Prove that a signed left-hand declaration is never used as a substitute for
  correct right-hand expression typing.

  Close `Bundle`, `Vec`, `Stream`, `Flow`, `Mem[SInt]`, child ports, canonical
  module deduplication and Increment 59 typed BlackBox generic/port coexistence.
  Flattened aggregate carriers remain unsigned; exact `SInt` leaves become
  signed only after reconstruction or on independently declared leaf ports.
  Reject unsupported or ambiguous boundaries explicitly instead of silently
  deleting a cast.

  Implementation and exact boundary/proof contracts are recorded in
  [the 60e closure record](increment-60e-signedness-boundaries.md).
  Both Scala lanes pass all 139 tests across ten suites without skips, including
  the inherited Vec formal suite. All 64 independent native-reference tuples
  pass strict Verilog-2001 tools, simulation and equivalence; five boundary
  mutations produce genuine SAT counterexamples. Fresh generation reproduces
  all 70 new and 29 inherited RTL files in each lane. The inherited 60d/60a
  qualification and native source audits also pass. Signed resize, reconstructed
  Vec leaves and hierarchy boundaries remain explicitly opt-in. Parent 60 and
  the separate 60f/60g rollout gates remain unchecked.

- [ ] **Increment 60f — Equivalence, compatibility and tool-matrix closure**

  **Dependencies:** Increment 60e implemented and merged.

  **Status:** Implementation in qualification; no successful 60f qualification
  or merge is recorded yet. The precise proof domains, compatibility corpus,
  regression minima and completion gates are in the
  [60f qualification record](increment-60f-equivalence-closure.md).

  Generate the feature-disabled cast-heavy reference independently from the
  feature-enabled signed-declaration candidate. Prove combinational and
  sequential equivalence for parameter overrides `WIDTH` in `{1, 5, 8, 32}`
  using arbitrary signed inputs, including minimum negative, `-1`, zero, one,
  maximum positive, overflow and truncation cases. Compare memory data only
  under the same validity/initialization contract and constrain undefined
  divide/remainder inputs consistently.

  Run both supported Scala lanes, deterministic regeneration, strict
  Verilog-2001 parsing, Icarus simulation, Verilator lint, Yosys synthesis and
  solver-backed formal equivalence. The negative mutation from Increment 60a
  must produce a genuine counterexample. Missing modules, parser failure,
  timeout, `UNKNOWN` or a tool error is not a proof. Re-run the approved native
  source audit and all inherited MorphHDL regression suites.

  The closure adds an exact 60a solver-witness replay and supplementary
  eight-step memory-validity proofs without replacing inherited unbounded
  induction. Require deterministic regeneration and downloaded cross-Scala
  byte comparison of 213 RTL files, native Verilog/VHDL and opt-out mode
  compatibility, and at least 1,610 non-skipped regression tests per Scala lane,
  including the isolated pass workspace. No production or default-policy
  change is included. Keep 60f unchecked until all exact-head gates pass.

- [ ] **Increment 60g — Default rollout, documentation and legacy-cast cleanup**

  **Dependencies:** Increment 60f implemented and merged.

  Enable the reviewed signed-declaration/minimal-cast policy by default for the
  MorphHDL strict Verilog-2001 publication path. Keep ordinary `SpinalVerilog`
  upstream-compatible by default; any broader native default change requires a
  separate explicit approval and compatibility plan. Document the mode and
  signedness boundary rules in the architecture profile, update examples and
  golden contracts, and record the final native-change manifest.

  Remove obsolete signed-operator helper code only where every caller is
  covered by the typed analysis. Keep explicit cast helpers for real boundaries
  and state clearly that some `$signed(...)` uses are correct and expected.
  Mark Increment 60a through 60g and the controlling Increment 60 parent `[x]`
  only as the final source transition after the exact final-head gate passes.

## Completion criteria

Increment 60 is complete when ordinary SpinalHDL `SInt` values publish as
native signed Verilog objects in MorphHDL, pure signed arithmetic is readable
without redundant `$signed` nesting, every necessary boundary cast remains,
and the new output is formally equivalent to the existing cast-heavy reference
across the declared parameter matrix. The implementation must remain generic;
no fixture-specific, component-specific or emitted-name-specific rule may be
introduced.
