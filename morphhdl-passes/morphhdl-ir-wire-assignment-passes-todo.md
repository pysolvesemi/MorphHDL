# MorphHDL IR simple-wire assignment passes roadmap

This is the controlling checklist for four optional, behavior-preserving
passes over the canonical MorphHDL-owned IR after parameterization/capture
and before Verilog-2001 emission:

1. remove eligible direct wire aliases represented by unnamed internal signals;
2. remove eligible direct wire aliases represented by explicitly named internal signals;
3. inline the pure right-hand-side expression of an eligible unnamed continuous
   wire assignment into every continuous receiver, then remove the temporary
   declaration and its assignment; and
4. simplify approved constant operands in pure continuous right-hand-side
   expressions without changing their packed value, width or signedness.

WA-07a is the user-authorized addition to this roadmap. It is inserted before
WA-08, whose production handoff must include the new pass and its proof gates.
The historical three-pass pipeline remains the implemented baseline until
WA-07a's common-pipeline integration is complete. No pass executes in production
until WA-08.

Product code has one all-or-none `enabled` flag. `false` executes no pass;
`true` must execute all four in the fixed order above. Internal proof fixtures
may select historical stages directly, but those selections are not product
flags. The complete pipeline must reach an idempotent fixed point: a
simplification that exposes an alias for an earlier stage must not require a
second product invocation to finish the optimization.

No signal-renaming, formatting, generated-Verilog parsing or broader
optimization pass is authorized by this roadmap.

## Fixed architecture

```text
ordinary SpinalHDL component
        |
        v
SpinalHDL elaboration and inherited validation
        |
        v
MorphHDL external parameterization, capture and lowering
        |
        v
canonical MorphHDL-owned IR
        |
        +--> unnamed direct-wire alias elimination
        |
        +--> named direct-wire alias elimination
        |
        +--> unnamed continuous wire-expression inlining
        |
        +--> constant-operand expression simplification
        |
        v
structured Verilog-2001 lowering and emission
        |
        v
final Verilog text
```

PV-58 realizes the read-only publication into this boundary for the bounded
`SimpleWireAssignmentsV1` profile. Its `CanonicalIrHandoff` carries the
validator-normalized graph, producer profile and complete facets directly from
the typed native graph. WA-07 proves the third standalone pass and the
historical one-flag pipeline. WA-07a extends that standalone pipeline; WA-08
implements validated production writeback.

The passes must:

- reuse the canonical MorphHDL-owned IR produced after parameterization;
- operate on declaration, driver and reference identities rather than emitted identifiers;
- preserve symbolic parameter expressions and constraints;
- be implemented generically over canonical IR semantics and identities,
  without recognizing a component, library primitive, module/class name or signal name;
- run before final Verilog text emission; and
- use the existing MorphHDL Verilog backend after transformation.

The passes must not:

- emit Verilog and parse it into another IR;
- introduce a generic Verilog parser or file-to-file postprocessor;
- use regex or emitted-name patterns to identify candidates;
- reconstruct parameter intent from concrete constants;
- duplicate or fork the canonical MorphHDL semantic IR;
- special-case `StreamFifo`, `ParameterizedStreamFifo` or any other component
  or library implementation; or
- modify upstream-owned SpinalHDL source.

## Component-generic implementation rule

Every adapter, validator, pass and ordered pipeline in this roadmap must be
component-generic. Eligibility and transformation decisions may depend only on
validated canonical IR identities, kinds, scopes, drivers, references, packed
types, parameter domains, naming provenance, observability, comments and
attributes defined by this roadmap. No implementation may recognize
`StreamFifo`, `StreamFifoCC`, `ParameterizedStreamFifo`, any other component or
library class, a module/class name or component name, a source filename, or a
generated HDL identifier to select a code path. Pass implementation code must
not inspect a source filename. `SourceLocation` may be retained and reported,
but its path must not change eligibility. Renaming an otherwise identical
fixture from a library component name to an unrelated name must not change
adapter facts, diagnostics, classification or transformation.

## Bounded simple-wire alias contract

An initially eligible alias is an internal combinational signal for which the
canonical IR proves all of the following:

- exactly one full-object continuous driver exists;
- the driver is a direct reference to one existing signal or port;
- source and alias have equivalent packed width, signedness and value semantics
  over the complete declared parameter domain;
- no other continuous or procedural driver targets the alias;
- the alias is not an assignment target, bidirectional endpoint, tri-state
  control, clock, reset, memory object or hierarchy boundary;
- replacing all reads with the exact source symbol cannot create a cycle or
  cross an illegal scope;
- the alias is not externally visible and has no `keep`, `dontTouch`, probe,
  preservation, black-box, public-export or equivalent observability contract; and
- deleting the declaration and sole assignment cannot discard a required
  comment, attribute or source contract.

WA-04 and WA-05 remain bounded to direct wire-to-wire aliases. They do not
inline operators, literals, slices, indexes, concatenations, casts, resizes,
muxes or other expression trees.

## Bounded unnamed continuous wire-expression contract

WA-07 adds a distinct pass for an unnamed internal combinational temporary
whose sole full-object driver is a continuous assignment from any pure
combinational `RtlExpr` currently represented by the canonical IR: literal,
unary or binary operator, mux, concatenation, bit or part select, resize, cast,
or a nesting of those forms. A direct signal reference remains WA-04 scope.

The expression pass must prove all of the following before changing the IR:

- the temporary is classified as unnamed by retained source/elaboration
  provenance, never by matching `_zz_*` or another emitted identifier;
- exactly one full-object continuous assignment drives the temporary;
- the right-hand side is complete canonical expression IR and does not
  reference the temporary itself;
- every reference used by the expression is resolved, legally visible from
  every receiver, and cannot introduce a combinational cycle;
- at least one receiver exists and every receiver is also a continuous assignment;
- neither the temporary assignment nor any receiver is procedural; therefore
  no assignment emitted in an `always` block is changed;
- the temporary has complete packed type and observability metadata and no
  preservation, comment, attribute, public, probe or hierarchy contract; and
- cloning the expression at each receiver preserves the removed assignment's
  packed width and signedness through an explicit type fence.

For example:

```verilog
wire [WIDTH-1:0] temporary;
assign temporary = (left ^ ~right);
assign sink_a = temporary;
assign sink_b = temporary;
```

may become:

```verilog
assign sink_a = (left ^ ~right);
assign sink_b = (left ^ ~right);
```

RHS expressions are copied once per receiver; each copy preserves the removed
assignment's packed type fence. The exact temporary declaration and sole
assignment are removed. This pass does not simplify, reassociate, fold or
otherwise change the cloned expression; WA-07a owns approved simplification.
If any receiver is procedural, the temporary and every use remain unchanged.

Conceptually:

```verilog
wire [WIDTH-1:0] alias;
assign alias = source;
assign sink = alias;
```

may become:

```verilog
assign sink = source;
```

Only the exact alias declaration, its sole assignment and references to that
symbol may change. No surviving signal is renamed.

## Bounded constant-operand simplification contract

WA-07a operates on the existing canonical `RtlExpr` tree, not emitted text.
Its first bounded implementation covers bitwise AND, OR and XOR, logical AND
and OR, logical NOT, safe double negation, zero-distance shifts, and constant
mux conditions. It rewrites pure continuous RHS expressions only. It does not
remove declarations, assignments, ports, state or procedural statements.

For a one-bit comparison result `p = (a > 5)`, the required examples are:

```text
p & 1 -> p        p & 0 -> 0
p | 0 -> p        p | 1 -> 1
p ^ 0 -> p        p ^ 1 -> ~p
```

Both constant-on-left and constant-on-right forms must be handled where the
operator is commutative. Logical identities must return a Boolean truth value,
not an unconverted multi-bit operand. Constant mux conditions may select a
branch only while preserving the original expression's evaluation width and
signedness.

The safety contract is deliberately stricter than two-state Boolean algebra:

- A numeric `1` is not an all-ones mask for a multi-bit value. Never change
  `vector & 1` into `vector` or `vector ^ 1` into `~vector` without a proof that
  the original value and result are one bit, or an equivalent explicit fence.
- Preserve Verilog context sizing, sized and unsized-constant widening,
  truncation, signed extension, unsigned extension, and cast/resize fences.
  All-ones masks must cover the effective evaluation width, not just a signal's
  declared width. A narrow mask under a wider assignment cannot be discarded.
- Preserve four-state X/Z behavior. A Boolean type does not prove that a port
  or wire cannot carry Z. For example, `Z & 1`, `Z | 0` and `Z ^ 0` produce X,
  so replacing such an operation with an arbitrary raw reference is forbidden.
  A comparison or ordinary logical/bitwise operator cannot produce Z and can
  provide the local proof required for these neutral-element rewrites.
- Do not apply `x ^ x -> 0`, `x == x -> 1`, `x - x -> 0`, `x * 0 -> 0`,
  `x + 0 -> x`, `x * 1 -> x`, or division/modulo cancellation to unknown-capable
  operands. Arithmetic constant folding and inter-signal constant propagation
  remain outside this increment's bounded initial operator set.
- Keep public signal identities, comments and attributes intact. Honor keep,
  dontTouch, probe, preserve and boundary contracts. Memory, clock, reset,
  bidirectional and procedural drivers remain unchanged.
- Keep symbolic WIDTH, DEPTH and other parameter expressions symbolic. Never
  use a default parameter binding as a proof over the complete domain.
- Revalidate the resulting canonical design and publish the original input on
  failure. Record each rewrite by module identity, driver identity, expression
  path and rule, without misreporting it as a removed wire.
- Every individual pass and the complete pipeline must be deterministic and
  idempotent. New simplification opportunities exposed by earlier stages must
  be handled without oscillation or unbounded rewriting.

## Unnamed and named classification

Classification comes from source/elaboration naming metadata retained in the
MorphHDL IR before backend Verilog identifiers are allocated.

- **Unnamed internal signal:** no explicit user/source name was assigned. A
  backend identifier such as `_zz_*` does not make it named, and matching such
  text is forbidden.
- **Named internal signal:** an explicit user/source name was assigned to the
  internal signal. Ports, module names, instance names, parameters,
  local-parameters, generate labels, memory names and public or hierarchical
  names are outside the alias-elimination passes.

The named pass removes an eligible named alias and its assignment. It must not
transfer the removed name to another signal or invent a replacement name.
Because this removes an internal waveform/debug point, the named pass reports
every removed name and available source location deterministically. Product
execution is controlled only by the common all-or-none flag; there is no public
per-pass Boolean.

## Fixed non-goals

Apart from the exact direct-alias substitution, expression-temporary inlining,
or constant-operand rewrites described above, the passes must not change:

- module, instance, port, parameter, local-parameter or generate-label names;
- any surviving internal signal name;
- parameters, constraints, widths or signedness;
- expression structure or semantics outside the approved local transformations;
- clock, reset, sensitivity, scheduling or procedural statement order;
- generate structure, hierarchy, module boundaries or parameter bindings;
- memory behavior, library algorithms, register state or latency;
- declaration or module-item order except for removed alias artifacts; or
- comments and attributes attached to surviving IR objects.

The following remain outside this roadmap:

- signal renaming or name beautification;
- formatting or pretty-printing;
- dead-code elimination beyond the exact approved alias or expression temporary;
- inter-signal constant propagation and unrestricted arithmetic constant folding;
- algebraic or logic simplification beyond WA-07a's explicitly bounded rules;
- common-subexpression elimination;
- process merging, retiming, register removal or hierarchy flattening; and
- any additional pass beyond these four.

A signal-renaming pass may be planned later only through a separate explicit
roadmap update.

## Isolation and ownership boundary

All pass implementations, tests, fixtures, pass-specific build configuration
and evidence live under the standalone top-level `morphhdl-passes/` workspace.

The workspace must:

- use its own nested SBT and/or Mill build;
- not be added to the repository root SBT/Mill aggregate;
- consume a versioned MorphHDL-owned canonical IR API rather than Verilog text;
- treat the MorphHDL IR and backend as dependencies rather than copy them;
- not place source in a `spinal.*` package;
- not require a Git submodule;
- fail closed when symbol identity, driver ownership, type equivalence,
  name-origin or observability metadata is unavailable; and
- not modify upstream-owned source under `core/`, `lib/`, `idslplugin/`,
  `idslpayload/`, `sim/` or `tester/`.

One uniquely named MorphHDL-owned workflow may validate this workspace. The
final WA-08 increment may add only the minimum optional handoff in
MorphHDL-owned orchestration code; pass logic remains under `morphhdl-passes/`.

## Dependency and execution discipline

- Implement one `WA-*` increment at a time.
- Only the first unchecked increment whose dependencies are implemented and
  merged into `parameterized-verilog` is eligible to start.
- `PV-N` means Increment N in `docs/morphhdl/parameterized-verilog-todo.md`.
  The numbering includes the inserted native-memory provenance Increment 45.
- The canonical-IR adapter gate is PV-54 and the stable production-publication
  gate is PV-58; these pass-roadmap dependencies are unaffected by parallel
  work on independent earlier PV increments.
- A dependency is satisfied only when its checkbox is `[x]` on
  `parameterized-verilog`; a branch or open pull request does not satisfy it.
- A request for a `BLOCKED` increment must stop with the exact unsatisfied
  dependency. Work must not skip, partially implement or substitute another increment.
- Mark an increment `[x]` only in its reviewed pull request after all applicable
  gates pass, and update the next increment's `Status` in the same pull request.
- Every pass and pass combination must be deterministic and idempotent.

## Mandatory genericity, common witness and formal baseline

The following rules apply to every transforming pass and every enabled pass
combination in this roadmap:

- The pass implementation must remain generic over the canonical IR. It must
  not contain a `StreamFifo` recognizer, component-specific RTL logic,
  module-name check, library-structure check, source-position inference or
  emitted-signal-name pattern. Component-specific tests may exercise a generic
  pass but may not drive its implementation.
- Every pass must be applied to the shared parameterized StreamFifo source at
  `morphhdl-passes/examples/ParameterizedStreamFifo.scala`, retaining symbolic
  `WIDTH` and `DEPTH`. Additional small generic positive and negative fixtures
  remain mandatory so the shared witness cannot become a hidden special case.
- For each proof run, emit and retain the reference Verilog from the canonical
  design snapshot immediately before the entire passes phase, before any pass
  has executed.
- After each individual pass, and after every supported pass combination, emit
  the transformed Verilog through the same structured backend and formally
  compare it with that one common pre-pass reference Verilog. Comparing only
  with the output of the preceding pass is not sufficient.
- The formal comparison must use identical legal parameter assumptions and
  bindings on both sides. When the selected formal flow requires concrete
  parameter values, it must cover the complete admitted bounded parameter
  domain rather than only defaults or hand-picked values.
- A transforming pass cannot be checked complete without retained formal
  success evidence for the shared StreamFifo witness and a mutation test that
  demonstrates the equivalence harness fails for an intentional functional change.
- WA-07a additionally requires four-state simulation regressions: ordinary
  two-state formal equivalence does not by itself prove preservation of X/Z.

WA-02 is a read-only adapter and produces no transformed Verilog. WA-03 must
establish the common pre-pass capture and formal-equivalence harness before
WA-04 or WA-05 can remove an alias.

## Incremental plan

- [x] **WA-01 — Isolated IR-pass workspace and boundary guard**

  **Dependencies:** none.

  **Status:** `COMPLETED`.

  Added a standalone nested SBT workspace supporting Scala 2.12.18 and 2.13.12
  without changing repository root build files. Added immutable pass
  configuration, result, diagnostic and elimination-report contracts for the
  authorized wire-assignment passes. Added a boundary guard and self-tests that
  permit `morphhdl-passes/**` and `.github/workflows/morphhdl-passes.yml`, reject
  root and upstream-owned changes, and reserve MorphHDL-owned production
  handoff paths for an eligible WA-08 branch after its dependencies are checked.
  No RTL transformation is implemented by WA-01.

- [x] **WA-02 — Canonical MorphHDL IR pass adapter and alias contract**

  **Dependencies:** WA-01 and PV-54 implemented and merged.

  **Status:** `COMPLETED`.

  Bound the standalone workspace to the stable canonical MorphHDL-owned IR
  after external parameterization/capture and before Verilog lowering. Added a
  read-only identity-indexed adapter exposing declarations, drivers, references,
  packed types, parameter domains, naming provenance, source locations and
  observability metadata required by the bounded alias contract. Added
  fail-closed diagnostics, cross-Scala tests, and mutation-tested guards against
  generated-Verilog parsing, emitted-name matching, Spinal implementation
  coupling and component-specific logic. Added the common parameterized
  StreamFifo witness and froze the common pre-pass formal-equivalence baseline
  for every later transforming pass and supported pass combination. No alias was eliminated.

- [x] **WA-03 — Alias-elimination equivalence, safety and determinism gates**

  **Dependencies:** WA-02 implemented and merged.

  **Status:** `COMPLETED`.

  Add validation shared by both direct-alias passes. Prove type and parameter-domain
  equivalence, cycle freedom, legal scope replacement and preservation of every
  exclusion. Establish the common pre-pass Verilog capture and formally compare
  the output after each pass with that unchanged reference on the shared
  parameterized StreamFifo witness. Validate strict Verilog-2001 compilation,
  lint and synthesis, complete admitted parameter-domain proof where
  concretization is required, representative simulations, negative safety
  fixtures, a formal mutation test, repeated-run determinism and idempotence.

- [x] **WA-04 — Unnamed simple-wire assignment elimination pass**

  **Dependencies:** WA-03 implemented and merged.

  **Status:** `COMPLETED`.

  Eliminate only aliases classified as unnamed from retained source/elaboration
  metadata. Replace reads by exact symbol identity, remove the exact declaration
  and sole direct assignment, and leave all surviving names unchanged. Never
  recognize candidates from `_zz_*` or another emitted-name convention. Apply
  the generic pass to the shared parameterized StreamFifo and formally compare
  its post-pass Verilog with the common pre-pass reference.

- [x] **WA-05 — Named simple-wire assignment elimination pass**

  **Dependencies:** WA-04 implemented and merged.

  **Status:** `COMPLETED`.

  Applied the same safety contract to explicitly named internal aliases with
  stricter rejection for public, hierarchical, preservation, probe, attribute,
  comment or source-contract dependencies. Do not rename or transfer the
  removed name. Report each removed name and source location deterministically.
  Applied the component-generic pass to the shared parameterized StreamFifo and
  formally compared its post-pass Verilog with the common pre-pass reference
  over the complete `WIDTH=1..64` by `DEPTH=1..8` admitted domain. Added
  cross-Scala transformation, rejection, fixed-point, idempotence, exact-name
  preservation, deterministic-report and mutation-sensitive proof gates.

- [x] **WA-06 — Ordered two-pass pipeline and regression closure**

  **Dependencies:** WA-05 implemented and merged.

  **Status:** `COMPLETED`.

  Added one optional MorphHDL-IR pipeline entrypoint and proved the historical
  unnamed-only, named-only and unnamed-then-named stages. WA-07 replaces the
  product-facing independent switches with one all-or-none flag while retaining
  those selections only inside regression code. Validated alias chains and
  fanout without parsing emitted Verilog, including deterministic reports,
  idempotent IR, byte-identical repeated emission, strict Verilog-2001 legality,
  synthesis and formal equivalence of each individual pass and the ordered
  combination against the one common pre-pass StreamFifo reference. The
  pipeline publishes the original input if any stage fails, retains ordered
  per-stage evidence, and remains component-generic.

- [x] **WA-07 — Unnamed continuous wire-expression elimination and common pass flag**

  **Dependencies:** WA-06 implemented and merged.

  **Status:** `COMPLETED`.

  Replace the product-facing per-pass Booleans with one `enabled` flag. When
  disabled, execute no wire-assignment pass. When enabled, execute unnamed
  direct aliases, named direct aliases, then unnamed continuous expression
  temporaries in that fixed order. Retain direct stage selection only as a
  package-private regression facility. This is the historical three-pass
  contract; WA-07a adds the fourth stage without changing these individual passes.

  Add a component-generic canonical-IR pass for unnamed internal combinational
  temporaries driven by any pure canonical RHS expression. Clone the exact
  expression into every continuous receiver, preserve the removed assignment's
  width and signedness through an explicit type fence, then remove only the
  temporary declaration and sole assignment. Do not infer unnamed status from
  `_zz_*`. Reject candidate or receiver assignments represented by procedural
  drivers, so assignments emitted in `always` blocks remain unchanged.

  A full-width receiver select with zero offset is first collapsed to a
  whole-object use. A partial receiver select is rewritten only when its
  literal in-range offset and width can be composed with a direct source
  part-select of the complete temporary width. Arithmetic, mux, cast,
  resize, nested, dynamic and otherwise non-composable selected uses retain
  the temporary, preventing invalid forms such as `(a + b)[3:0]` or
  `source[7:0][3:0]` in strict Verilog-2001. Every accepted bit or part-select
  replacement retains unsigned selection semantics; only a whole-object
  receiver inherits the removed assignment's signedness.

  Add direct, nested, literal, fanout, cycle, scope, observability, procedural
  source, procedural receiver, deterministic, fixed-point, idempotence and
  fail-closed tests on Scala 2.12.18 and 2.13.12. Apply the expression-only pass
  and the common-flag all-pass pipeline to the shared parameterized StreamFifo.
  Emit both candidates through the existing structured backend and formally
  compare each directly with the one unchanged pre-pass reference over all 512
  `WIDTH=1..64` by `DEPTH=1..8` bindings. Retain strict Verilog-2001, lint,
  synthesis, representative simulation, mutation and repeated-emission gates.

- [ ] **WA-07a — Constant-operand expression simplification**

  **Dependencies:** WA-07 implemented and merged.

  **Status:** `IN PROGRESS`.

  Add `ConstantOperandSimplificationPass` over the canonical IR. Implement the
  bounded bitwise, Boolean, unary, zero-shift and constant-mux rules above,
  including the `(a > 5) & 1` and `(a > 5) & 0` examples. Keep the implementation
  component-generic and preserve widths, signedness, symbolic parameters,
  context sizing and four-state X/Z behavior. Report rewrites separately from
  removed wire aliases; revalidate and roll back on failure.

  Add positive, negative, nested, commuted, signed, multi-bit-mask, widened
  context, cast/resize-fence, parameterized-width, preservation, procedural,
  invalid-input, determinism and idempotence tests on Scala 2.12.18 and 2.13.12.
  Add a four-state simulator oracle and a mutation that demonstrates unsafe
  raw-Z neutral-element rewriting is detected.

  Extend the common all-or-none pipeline with the new final stage and close
  fixed-point behavior across stages. Preserve historical individual proof
  legs. Apply the new standalone pass and complete four-pass pipeline to the
  shared parameterized StreamFifo and compare each directly with the same
  unchanged common pre-pass reference across all 512 `WIDTH=1..64` by
  `DEPTH=1..8` bindings. Retain strict Verilog-2001 compilation, lint, synthesis,
  representative simulation, formal mutation and deterministic-emission
  evidence. Do not check this item complete from unit tests or a preceding-pass
  comparison alone.

- [ ] **WA-08 — Final MorphHDL IR-stage production handoff**

  **Dependencies:** WA-07, WA-07a and PV-58 implemented and merged.

  **Status:** `BLOCKED`.

  Blocked by WA-07a's implementation and proof closure. Expand PV-58's validated
  publication profile to carry the approved pure expression algebra and
  connect the one-flag four-pass pipeline to the MorphHDL single-source
  production path after parameterization/capture and before Verilog lowering.
  PV-58 currently publishes a read-only bounded snapshot; it does not execute
  or write back any pass. Keep pass implementation under `morphhdl-passes/` and
  add only minimum MorphHDL-owned integration, configuration and validated
  writeback glue. Existing generation remains unchanged unless the one common
  flag is enabled. Do not add a generated-Verilog parser, file postprocessor,
  signal-renaming pass, formatting pass or broader optimization pass.

## Completion target

This roadmap completes at WA-08 when MorphHDL can optionally run all four
transformations from one flag on its canonical post-parameterization IR and
write the validated result back into the structured Verilog-2001 production
path while preserving parameterized RTL behavior and every surviving
identifier. Signal renaming remains future work.
