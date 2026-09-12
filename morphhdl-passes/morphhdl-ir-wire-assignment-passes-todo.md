# MorphHDL IR simple-wire assignment passes roadmap

This is the controlling checklist for six optional, behavior-preserving
passes over the canonical MorphHDL-owned IR after parameterization/capture
and before Verilog-2001 emission:

1. remove eligible direct wire aliases represented by unnamed internal signals;
2. remove eligible direct wire aliases represented by retained named/generated
   internal signals, with provenance-first survivor preference;
3. inline the pure right-hand-side expression of an eligible unnamed continuous
   wire assignment into every continuous receiver, then remove the temporary
   declaration and its assignment;
4. inline the pure right-hand-side expression of an eligible named continuous
   wire assignment into every continuous receiver, then remove that internal
   declaration and its assignment;
5. simplify approved constant operands in pure continuous right-hand-side
   expressions without changing their packed value, width or signedness; and
6. recursively simplify Boolean-valued ternary expressions with opposite
   constant Boolean branches, preserving truth conversion and packed semantics.

WA-07b is the user-authorized fifth pass, inserted before WA-08, whose
production handoff must include the new pass and its proof gates. The historical
four-stage standalone pipeline and its native witness qualification are
recorded in [WA-07a evidence](wa07a-completion-evidence.md).
WA-07b's integrated implementation is qualified. Its exact-source results,
actual emitted examples and separate completion-head merge gate are recorded
in [WA-07b evidence](wa07b-completion-evidence.md); the transformation and proof
layers are described in [WA-07b notes](wa07b-implementation-notes.md).
Production execution and writeback were completed by WA-08. WA-09 is the
explicit successor that adds the sixth stage and deterministic direct-alias
survivor preference; it does not revise WA-08's historical five-stage claim.

Product code has one all-or-none `enabled` flag. `false` executes no pass;
WA-09 extends `true` from the historical five stages to all six in the fixed
order above. Internal proof fixtures retain the exact historical three-, four-
and five-stage selections, but those selections are not product flags. The
complete pipeline must reach
an idempotent fixed point: a simplification that exposes an alias or another
rewrite for an earlier stage must not require a second product invocation to
finish the optimization.

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
        +--> named continuous wire-expression inlining (WA-09)
        |
        +--> constant-operand expression simplification
        |
        +--> recursive Boolean ternary simplification (WA-07b; standalone qualified)
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
historical one-flag pipeline. WA-07a adds constant-operand simplification;
WA-07b adds the qualified recursive Boolean ternary simplification pass.
WA-08 implements validated production writeback after both additions are
complete and merged. WA-09 adds named-expression inlining and source-provenance
aware direct-alias survivor selection while preserving those historical pass
vectors and artifacts.

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

WA-09 refines only which safely removable member of a direct-alias relation is
preferred. A mandatory survivor such as a port, register, hierarchy boundary,
preserved/debug identity, procedural value or otherwise ineligible declaration
always wins. Between removable declarations, a meaningful retained source name
(`Explicit` or `Reflected`) wins over `Unnamed` or `Generated` provenance;
only between meaningful names does the shorter retained source name win.
Deterministic name and symbol-identity tie breakers apply after length. This is
not a spelling heuristic: an explicitly named `_zz` is meaningful, while a
generated short identifier is not. A preference is applied only when the
existing safety proof authorizes removal of the losing declaration; otherwise
both declarations remain.

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
otherwise change the cloned expression; WA-07a and WA-07b own the approved
simplifications. If any receiver is procedural, the temporary and every use
remain unchanged.

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

## Bounded named continuous wire-expression contract

WA-09 applies the WA-07 expression transformation to an internal continuous
wire whose retained naming provenance is `Explicit`, `Reflected` or `Generated`.
It does not infer provenance from the identifier text and does not transfer the
removed name to a survivor.

Every WA-07 expression safety condition remains mandatory: one full-object
continuous non-reference driver, complete canonical expression capture, exact
packed kind/width/signedness and symbolic identity, legal local continuous
receivers, cycle freedom, fresh cloned references and an explicit assignment
type fence. Ports, registers, procedural or multiply driven values, hierarchy
boundaries, clock/reset/control identities, native metadata and registry
references, preservation/debug contracts, comments, attributes and unknown
metadata remain ineligible. An unrepresented native RHS fails closed rather
than being approximated by a fabricated expression.

For the ordinary production topology:

```scala
val bitSource = Bits(width bits)
bitSource := a ^ b
val bitCloneAlias = cloneOf(bitSource)
bitCloneAlias := bitSource
clonedResult := bitCloneAlias
```

the six-stage default pipeline removes the longer direct alias first and then
inlines the named expression source, while retaining the output port:

```verilog
assign clonedResult = (a ^ b);
```

The pass does not promise to remove every syntactic intermediate. If either
declaration cannot be removed under the complete safety contract, it stays;
name length never overrides legality, provenance, observability or semantics.

At the native handoff, both the source RHS and each receiver's actual whole RHS
are captured with `NativeWireExpressionCodec`; no representative XOR or guessed
expression may stand in for an unsupported tree. Native Boolean expressions use
the exact `TypeBool` width of one, and the complete shared metadata, clock,
scope, type, receiver and cycle checks apply. Reverse direct-alias preference
must first validate the actual two-edge chain through the canonical named pass,
then may remove only the independently safe source. An expression-driven source
is deferred by provenance: true `Unnamed` goes to
`UnnamedWireExpressionNativePhase`, while named/generated provenance goes to
`NamedWireExpressionNativePhase`.

### Bounded post-pass wrapper elision

Native reproduction also shows that `fillExpressionToWrap` can create anonymous
expression wrappers during Verilog emission, after every canonical/native
wire-assignment phase has run. WA-09 may suppress only wrappers for an exact
homogeneous fixed-width unsigned `UInt` addition tree feeding a whole-object
fixed-width unsigned receiver. All add operands and intermediates must share
that one proven positive width. Leaves are same-width fixed `UInt` declarations
or exact fixed unsigned widening resizes from narrower fixed `UInt` values.
Synthetic `Add`/`ResizeUInt` nodes selected for suppression must be unannotated
and uniquely used; the unannotated whole-object target must be singly driven.
Tags on a real leaf declaration do not authorize its removal: the declaration
remains, and only emitter-created expression wrappers may disappear. This is a
bounded emitter planning rule, not a seventh canonical pass.

The positive witness is four 16-bit inputs accumulated in an exact 18-bit
unsigned domain (`0..262140`); a separate same-width 18-bit witness exercises
the original tree's modular overflow without reassociation. Mixed widths,
signed values, narrowing, bit or part selections, direct symbolic-width
expression nodes, incomplete facts and other operators retain their wrappers.
Fixed native 16-to-18 `ResizeUInt` wrappers inline completely. For parameterized
inputs, tagged parameterized resize `BaseType` carriers remain declared and may
be fixed 18-bit leaves, while only the surrounding generated `Add` wrappers
inline. Eligibility comes from native expression and type identities, never
wrapper names or emitted text.

The exact runnable source is
`morphhdl/src/test/scala/nativeapplication/NestedUnsignedExtendedSumProductionArtifactWriter.scala`
(`morphhdl.examples.NestedUnsignedExtendedSumProductionArtifactWriter`):

```text
sbt "morph/Test/runMain morphhdl.examples.NestedUnsignedExtendedSumProductionArtifactWriter target/wa09-nested-sum"
```

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

## Bounded recursive Boolean ternary simplification contract

WA-07b adds a separate pass over canonical `RtlExpr` mux nodes whose true and
false branches are the opposite constant Boolean values. The condition may
be any validated pure expression, not just a comparison, logical AND, signal
reference or the spelling shown in the user example. The pass operates only
inside eligible pure continuous RHS expressions; it is not a Verilog-text
postprocessor and does not extend this roadmap to procedural assignments.

For a width-proven one-bit Boolean-normalized condition `p`, the target rules
in a one-bit result context are:

```text
p ? 1'b1 : 1'b0 -> p
p ? 1'b0 : 1'b1 -> ~p

((a == 1) && (b > 5)) ? 1'b1 : 1'b0 -> ((a == 1) && (b > 5))
((a == 1) && (b > 5)) ? 1'b0 : 1'b1 -> ~((a == 1) && (b > 5))
```

These are semantic transformation examples, not substitutes for retained
before/after emitted RTL. The implementation uses self-determined logical
negation `!p` for the inverse rule so widening cannot turn it into a wider
bitwise complement. The implementation must obey all of the following:

- Rewrite bottom-up through every represented expression child: mux conditions
  and both branches, unary and binary operands, concatenations, selects and
  their expression indices, casts and resizes. A nonmatching parent must not
  hide a matching descendant. Handle arbitrarily nested supported expression
  trees without a hard-coded pattern depth, including opportunities exposed
  after child simplification. Unsupported or opaque nodes fail closed.
- Preserve the ternary condition's truth conversion. For a multi-bit condition
  `c`, use canonical Boolean normalization equivalent to `!!c` for the positive
  form and logical negation `!c` for the inverse form, not raw `c` or `~c`.
  Bitwise complement is allowed only on a proven one-bit Boolean value with
  its original result/context type preserved.
- Preserve four-state X/Z behavior. `p ? 1'b1 : 1'b0` yields X when a raw
  one-bit `p` is Z, whereas replacing it with raw `p` would yield Z. Direct
  positive substitution therefore needs a no-Z proof from canonical expression
  semantics; a Bool declaration alone is insufficient. Otherwise retain a
  Boolean-normalizing operation or leave the mux unchanged. Unknown-capable
  conditions must not be treated as two-state by assumption.
- Recognize branch values by canonical typed constant semantics, never their
  emitted text. Initially admit opposite one-bit Boolean constants and only
  normalized equivalents with proven identical value, width and signedness.
  Do not assume unsized `0`/`1`, wider or signed constants, all-ones masks,
  X/Z-containing branches or default-bound parameter expressions are equivalent.
  Unproven cases remain unchanged.
- Preserve the original mux result type, surrounding evaluation width,
  truncation, sign/zero extension and cast/resize fences. In particular, fence
  a one-bit negation before widening so its upper bits cannot become a wider
  bitwise complement. Prove symbolic-width equivalence over the complete legal
  parameter domain, not only a default binding.
- Reuse the component-generic scope, observability, preservation, comment and
  attribute exclusions of WA-07a. Do not remove declarations or assignments,
  rename signals, change generate structure or touch state, procedural drivers,
  memory behavior, clocks, resets or hierarchy boundaries. Visit eligible
  drivers in represented scopes without changing those scopes.
- Record each accepted rule by module identity, driver identity and expression
  path, separately from wire-elimination reports. Revalidate transformed IR and
  publish the original input on failure. The standalone pass and the complete
  five-stage pipeline must converge deterministically to an idempotent fixed
  point, including interactions with WA-07a and the earlier alias stages.

This increment removes the specified redundant Boolean ternaries. It does not
introduce unrestricted mux algebra, arithmetic reassociation, inter-signal
constant propagation or special cases for any component or library.

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
every removed name and available source location deterministically.
Product execution is controlled only by the common all-or-none flag; there is
no public per-pass Boolean.

## Fixed non-goals

Apart from the exact direct-alias substitution, bounded unnamed/named
expression-wire inlining, constant-operand rewrites or Boolean ternary rewrites
described above, the
passes must not change:

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
- algebraic or logic simplification beyond WA-07a's and WA-07b's explicitly bounded rules;
- common-subexpression elimination;
- process merging, retiming, register removal or hierarchy flattening; and
- any additional pass beyond these six.

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

One uniquely named MorphHDL-owned workflow may validate this workspace. WA-08
added only the minimum optional handoff in MorphHDL-owned orchestration code;
WA-09 retains that boundary and keeps pass logic under `morphhdl-passes/`.

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
- WA-07a and WA-07b additionally require generic four-state simulation
  regressions: ordinary two-state formal equivalence does not by itself prove
  preservation of X/Z. Simulation coverage must include independent small
  expression fixtures, not only the shared StreamFifo witness.

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

- [x] **WA-07a — Constant-operand expression simplification**

  **Dependencies:** WA-07 implemented and merged.

  **Status:** `COMPLETED`.

  Implementation qualification and the separate final-head merge gate are
  recorded in [WA-07a evidence](wa07a-completion-evidence.md).

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

- [x] **WA-07b — Recursive Boolean ternary expression simplification**

  **Dependencies:** WA-07a implemented and merged.

  **Status:** `COMPLETED`.

  Integrated implementation `62e62146c0366b2db37f2243017d32232d5a981d`
  passed workflow `34200640436`, including both Scala lanes, actual native
  checks, all 16 formal shards and exact full-domain aggregation. All nine
  candidates were compared with the same pre-ALL-passes reference for all
  512 bindings, twice: 9,216 equivalence and 9,216 reachability results.
  Inherited/signedness workflow `34200640264` also passed. Exact artifact
  identities, actual Scala/generated-Verilog examples, proof limits and the
  separate completion-head CI/merge gate are recorded in
  [WA-07b evidence](wa07b-completion-evidence.md). No production writeback is
  claimed; see [implementation notes](wa07b-implementation-notes.md).

  Add a component-generic `BooleanTernarySimplificationPass` implementing the
  bounded contract above over canonical `RtlExpr`, before Verilog emission.
  Simplify both `p ? 1'b1 : 1'b0` and `p ? 1'b0 : 1'b1`, including the user's
  compound comparison example. Recursively visit every supported expression
  child, not merely a top-level assignment or a fixed nesting pattern. Preserve
  Boolean truth conversion, X/Z behavior, width, signedness, type fences,
  symbolic parameters, scope and observability; fail closed without a proof.

  Add generic positive and negative fixtures on Scala 2.12.18 and 2.13.12 for
  both polarities, compound conditions, nested ternaries in conditions and
  branches, and ternaries nested under each represented expression kind.
  Include nonmatching parents, expressions exposed by earlier passes, one-bit
  raw-Z inputs, multi-bit zero/nonzero/unknown conditions, sized and unsized
  literals, signed/widened contexts, casts/resizes, symbolic widths, protected
  and procedural drivers, invalid inputs, and renamed unrelated components.
  Require deterministic diagnostics, byte-identical repeated emission,
  standalone and cross-stage fixed points, idempotence and rollback tests.

  Add reusable four-state simulation comparisons against the unchanged
  pre-pass Verilog for independent expression fixtures, not just StreamFifo
  or this specific example. Cover scalar `0`, `1`, `X`, `Z` and mixed multi-bit
  conditions, including known-one-plus-unknown bits. Retain mutations showing
  that wrong polarity, unsafe raw-Z passthrough and an unnormalized multi-bit
  or widened bitwise complement are detected by the applicable oracle.

  Extend the existing common all-or-none flag to the fixed five-stage pipeline
  with this pass after WA-07a; do not add a public per-pass switch. Preserve
  all historical proof legs. Apply the standalone new pass and the complete
  pipeline to the shared parameterized StreamFifo and formally compare each
  directly with the same common pre-pass reference over all 512 `WIDTH=1..64`
  by `DEPTH=1..8` bindings. Also prove independent fixtures that actually trigger
  the new rules; a no-op StreamFifo result alone cannot establish coverage.
  Retain strict Verilog-2001 compilation, lint, synthesis, simulation, formal
  mutation and deterministic-emission gates. Record actual before/after emitted
  Verilog and proof evidence before marking this increment complete.

  Test source and proof wiring alone do not satisfy these completion gates.
  Production integration remains WA-08 scope.

- [x] **WA-08 — Final MorphHDL IR-stage production handoff**

  **Dependencies:** WA-07, WA-07a, WA-07b and PV-58 implemented and merged.

  **Status:** `COMPLETED`.

  Implemented the production flag and the existing five-pass writeback pipeline.
  The subsequent user-requested default-on update enables the single-source
  `MorphVerilog` path automatically and retains explicit `enabled = false` opt-out;
  CI was skipped for that update. For the original WA-08 revision, both Scala
  lanes, inherited compatibility workflows,
  strict Verilog tools, determinism, live mutation controls and the full
  512-binding proof qualify the implementation. See
  [implementation and qualification notes](wa08-implementation-notes.md) and
  [PR #178](https://github.com/pysolvesemi/MorphHDL/pull/178).

  The production named-alias discovery defect found at `39d888874` is a repair
  of this completed handoff. Ordinary source/elaboration names must qualify
  without the former fixture-only tag. The repair retains the existing
  source-scope, use-context, type and preservation limits; its public-path
  regression coverage and actual emitted examples are recorded in
  [named-alias repair evidence](wa08-named-alias-fix.md).

  Eligible to start only once WA-07b is implemented, checked complete and
  merged into `parameterized-verilog`; an open implementation PR does not
  satisfy that dependency. Expand PV-58's validated
  publication profile to carry the approved pure expression algebra and
  connect the one-flag five-pass pipeline to the MorphHDL single-source
  production path after parameterization/capture and before Verilog lowering.
  PV-58 currently publishes a read-only bounded snapshot; it does not execute
  or write back any pass. Keep pass implementation under `morphhdl-passes/` and
  add only minimum MorphHDL-owned integration, configuration and validated
  writeback glue. Existing generation remains unchanged unless the one common
  flag is enabled. Do not add a generated-Verilog parser, file postprocessor,
  signal-renaming pass, formatting pass or broader optimization pass.

- [x] **WA-09 — Named expression-wire elimination and provenance-first alias preference**

  **Dependencies:** WA-08 and PV-62 implemented and merged.

  **Status:** `COMPLETED`.

  The implementation candidate `d48687d0cca4d5f8437b877e480ca4fe09ad20c4`
  passed all 39 applicable workflows; the 15 pre-existing retired workflows
  remained skipped. Both production Scala lanes and cross-Scala comparison
  passed 31 deterministic artifacts, 51 equivalence cases, 6,672 four-state
  cases and four mutation controls per lane. The 16-shard full-domain proof
  passed all 11 pass identities over 512 bindings in two identical runs, with
  11,264 equivalence and 11,264 reachability proofs. Both full-suite lanes
  contained exactly 1,982 non-skipped tests in 194 suites with no failure,
  error, cancellation or skip. The final completion-only commit must repeat
  the same complete gate set before merge.

  Add `NamedWireExpressionEliminationPass` as the fourth stage, after unnamed
  expression inlining and before the two simplification stages. Reuse the
  canonical expression safety and rewrite engine; do not create a second IR,
  parse emitted Verilog or fabricate an expression when native capture is
  incomplete. Extend the production common flag to six stages while preserving
  the exact historical three-, four- and five-stage pass vectors, native
  witnesses, reports and proof artifacts.

  Native validation must capture the actual source and receiver RHS through
  `NativeWireExpressionCodec`, accept only whole-RHS continuous receivers, and
  represent `TypeBool` at width one; a representative XOR is forbidden. For a
  preferred reverse direct chain, require the canonical decision over both
  actual edges before removing the independently safe source. Expression-source
  deferral remains provenance-owned: `UnnamedWireExpressionNativePhase` handles
  true unnamed origins and `NamedWireExpressionNativePhase` handles
  explicit/reflected/generated origins.

  Refine direct-alias survivor selection so non-removable identities win first,
  meaningful `Explicit`/`Reflected` provenance wins over `Unnamed`/`Generated`,
  and shorter names win only between otherwise removable meaningful names.
  Identifier spelling is never provenance: generated `_zz`-style temporaries
  lose to a meaningful name regardless of string length, while an explicitly
  user-named `_zz` remains meaningful. If the losing declaration cannot pass
  the existing removal proof, retain the relation rather than reverse an
  assignment or transfer a name unsafely.

  Qualify both direct-alias orientations, equal-length deterministic ties,
  generated/unnamed and explicitly `_zz`-named controls, fanout and chains,
  named expression nesting, symbolic-width identity, signedness, four-state
  values and all preservation/procedural/hierarchy exclusions. The ordinary
  production fixture must emit `assign clonedResult = (a ^ b);` with neither
  intermediate declaration. Require repeat and cross-Scala byte identity,
  fixed-point/idempotence, strict Verilog-2001, lint, synthesis, simulation,
  functional mutations and formal equivalence to the common pre-pass reference.
  Reproduce and close the later emitter-created-wrapper boundary with only the
  homogeneous fixed-width unsigned-add policy above. Require the four-input
  16-to-18-bit positive and mixed-width, signed, narrowing, selection and
  direct symbolic-width retention controls. Tagged parameterized resize
  `BaseType` carriers must remain declared even when the fixed 18-bit add
  wrappers around them are proven redundant and inline. Run the committed
  witness with `sbt "morph/Test/runMain morphhdl.examples.NestedUnsignedExtendedSumProductionArtifactWriter target/wa09-nested-sum"`.
  All inherited gates and complete proof domains remain mandatory before this
  checkbox may be marked complete.

- [ ] **WA-10 — General typed expression inlining through final emission**

  **Dependencies:** WA-09 implemented and merged.

  **Status:** `IN PROGRESS`.

  This user-authorized successor extends the existing expression-elimination
  stages and their structured-emitter policy. It supersedes the historical
  direct-receiver, continuous-receiver-only and addition-only restrictions for
  cases proved safe below; it does not revise earlier qualification evidence.
  The existing default-enabled, all-or-none production flag remains the only
  switch. Symbolic Boolean/integer parameter normalization is a separate issue.

  - [x] Execute the supplied reduced timing fixture through production
    `MorphVerilog`, retaining baseline, enabled, disabled and repeat artifacts.
    Classify native candidates by retained provenance and actual rejection
    reasons; distinguish wrappers first introduced during emission.
  - [x] Extend identity-based canonical/native substitution to eligible nested
    receivers, including literals, proven resize/extension cases and subtraction.
    Preserve each node's authoritative packed width and signedness, complete
    capture, assignment fences and validated writeback. Unsupported symbolic or
    otherwise unprovable cases fail closed.
  - [x] Admit safe procedural RHS reads of continuously driven combinational
    expressions while preserving assignment kind, timing, conditional scope,
    register state, clocks and reset behavior. Never inline a register driver.
  - [x] Generalize typed emitter planning across comparison, arithmetic, mux and
    procedural RHS contexts. Keep any carrier required for legal Verilog-2001
    selection, truncation, extension, signedness or modular arithmetic.
  - [x] Preserve ports, hierarchy, protected/debug/vital identities, driver
    restrictions, cycles, metadata and parameter identities. Bound duplication
    and shared-expression growth; delete a driver/declaration only after every
    reference has been safely replaced.
  - [x] Add dedicated production regressions for all three reported patterns and
    controls for mixed widths/signedness, overflow/underflow, truncation, slices,
    nested and multiple uses, conditional register updates and protected signals.
  - [x] Compile the same native parameterized artifact with `PPC4=0` and `PPC4=1`;
    execute equivalence and four-state simulation checks, including zero/max and
    boundary arithmetic, and demonstrate that the oracle detects mutations.
  - [x] Verify deterministic generation and documented legacy disabled behavior;
    regenerate the production timing source when accessible without making the
    standalone regression depend on the application repository.
  - [ ] Record exact commands, actual before/after Verilog, validation outcomes,
    per-pattern root causes and remaining limitations. Complete the repository's
    applicable workflow and exact-source review gates before merge/completion.

  Executed reproductions, corrected root causes, output evidence, exact commands
  and conservative remaining boundaries are recorded in
  [`wa10-general-expression-inlining.md`](wa10-general-expression-inlining.md).

## Completion target

The original roadmap completed at WA-08 with five production transformations.
WA-09 established that MorphHDL runs all six from one
flag on canonical post-parameterization IR, writes the validated result back
into structured Verilog-2001, and applies provenance-first alias survivor
selection without renaming any surviving identifier. The authorized WA-10
successor completes when eligible general expressions inline through final
emission under the same flag, with the above semantic and qualification gates
satisfied. General signal renaming and symbolic Boolean/integer parameter
normalization remain separate future work.
