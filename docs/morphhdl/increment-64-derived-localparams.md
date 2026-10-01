# Increment 64 — Derived localparam implementation handoff

## Status: implementation in progress; qualification deferred

Implementation began on 2 October 2026 on
`work/remaining-parameterized-increments`, from integration baseline
`db54d01e5b21c7664f7a0de3795f061d77a3d259` plus the isolated CDC-LEG-01 checkpoint.
The user's current batch plan supersedes the former roadmap dependency on 59i:
implement independently, validate locally one increment at a time, reconcile
59i later, and run targeted then full CI when the batch is ready. Do not change
`parameterized-verilog` or implement the old wire-pass roadmap.

The controlling contract remains
[the Increment 64 roadmap entry](parameterized-verilog-todo.md#parameter-expression-and-structural-legality-follow-ups-increments-64-and-65).
The source observations and checklist below originated in the 22 September
review of `67d944fd6fa1bd7f3dc65bdc6439ce31e59283ca`; they are design inputs, not
completion evidence. Implementation and acceptance validation are still in
progress, and no remote qualification or merge is claimed.

## Current implementation surface

The native member callback records exact `ElabInt` expression identities and
source binding hints. Typed arithmetic retains its operand graph before naming;
module-local publication selects live uses and emits dependencies first through
the native localparam buffer. Names use uppercase underscore separation and the
existing component allocator for collisions. Reusing the same expression object
reuses its first binding; equal text or equal default values do not establish
identity. Trusted unrestricted width projection and formal-actual normalization
preserve presentation identity. Public case-class copies do not.

Supported named calculations include integer addition, subtraction,
multiplication, division, remainder, ceiling logarithm, address width and powers
of two. The native scalar declaration callback prints local references in ANSI
ports and internal declarations. Typed hierarchy publication prints parent-local
actual references; children retain their independent formals. Existing semantic
comparisons continue to use the original typed expression and domain evidence.

Restricted structural calculations keep the existing direct-expression path
within their generate owner. They are not hoisted into module-local constants.
Method-local values without a compiler member callback also retain direct
expressions. Literal hardware constants and explicit native child-formal APIs
remain in Increment 66's separate contract.

`NativeDerivedLocalParameterTests` contains the unchanged `RecordLink` source,
same-file parameter overrides, independent native concrete specialization
proofs, asymmetric live mutations, dependency chains, name collisions, identity
and cycle controls, helpers, hierarchy and ordinary concrete behavior. Exact
validation receipts are recorded after the active runs finish; this section does
not assert remote qualification.

Reproduce the standalone example with:

```sh
sbt 'morph/Test/runMain morphhdl.examples.DerivedLocalParameterArtifactWriter target/increment64-artifacts'
```

The writer uses ordinary `MorphVerilog` with optional wire passes disabled.
Its `RecordLink` class is also the test fixture, so the published example and
the same-file override/equivalence matrix exercise the same Scala source.

Compatibility validation exposed excessive regex scanning of non-declarations
and absent identifiers in the existing Vec/structural publisher. A lexical
declaration-prefix scan and literal absence prechecks avoid those scans while
retaining exact identifier boundaries, comment/string handling and unique
declaration/ownership checks. No matrix, proof or timeout was reduced.

## Reviewed implementation seams

The following observations are bound to the integration commit above, not to a
future post-59i tree.

| Existing source | Observed behavior and implication |
| --- | --- |
| `idslplugin/src/main/scala/spinal/idslplugin/components/MainTransformer.scala` | The post-uncurry transformer supplies eligible class/object member values and their getter names through `valCallback`. Constructor parameter accessors and `DontName` members are excluded. This is genuine compiler binding metadata and is the first route to evaluate for ordinary component member names. It does not establish coverage of method-local or captured-branch values. |
| `core/src/main/scala/spinal/core/Component.scala` | `valCallbackRec(ref, name)` handles child components and `Nameable` values. The reviewed implementation has no `ElabInt` case. `localNamingScope` already reserves explicit signal names before allocating generated names. |
| `core/src/main/scala/spinal/core/ElabInt.scala` | The typed carrier retains an `ElaborationIntegerExpression`, original parameter roots and structural projection entry points; it is not a `Nameable`. Arithmetic enters shared `ElabInt` helpers. An integer witness must not become declaration identity. |
| `core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala` | The emitter has separate port, localparam, declaration and logic buffers. Its inspected `result` places the port list before the localparam buffer. Adding a declaration to that buffer alone does not establish correct parameterized header ordering or expression-reference substitution. |
| `docs/morphhdl/independent-parameter-domains.md` | The existing contract separates trusted publication provenance, exact/domain proof and structural authority. Automatic factoring is explicitly deferred; naming an expression must not introduce a Cartesian proof requirement. |

Prefer a small generic typed registration hook using existing member callbacks
where they are sufficient. Do not extend the compiler plugin merely because a
name is needed. Conversely, do not claim the existing callback covers lexical
owners it does not observe. Reconcile the combined structural capture and
publication paths after 59i merges, before final batch qualification.

## Implementation and validation checklist

### 1. Capture bindings and preserve the original calculation

- [ ] Register an eligible symbolic value using exact carrier/expression identity,
  its compiler-supplied binding hint, Component identity, lexical structural
  owner and active capture lifetime. Ignore ordinary concrete values without
  changing concrete SpinalVerilog behavior.
- [ ] Preserve the typed operand graph at arithmetic construction, before
  normalization loses intermediate calculations. A callback occurring after an
  RHS has been evaluated supplies a name, not permission to reconstruct a
  vanished operand graph from its Verilog string.
- [ ] Make capture transactional across retries, branch probes, rollback and
  repeated generation. A stale or escaped binding must not become eligible.
- [ ] Define deterministic case conversion, reserved-name handling, collision
  suffixes, anonymous fallback and same-expression reuse. Names are only hints;
  equal names, equal default values and equal rendered strings are not identity.

### 2. Build and validate the owner-scoped declaration graph

- [ ] Build graph edges from authenticated typed operand relationships. Preserve
  declaration roots, correlations, restrictions, checked arithmetic and type
  semantics even when an algebraic normalization cancels a numerical term.
- [ ] Topologically order declarations with deterministic tie-breaking. Reject
  cycles, missing dependencies, incompatible root schemas and scope escapes.
- [ ] Keep raw values, safe physical-width expressions and legality obligations
  distinct. Factoring must not turn an obligation into a domain assumption or
  manufacture stronger structural authority.
- [ ] Select reuse only within compatible owners and authenticated identities.
  Keep branch-local calculations within their active domain. Preserve direct
  expressions for documented unsupported factoring cases, without silently
  bypassing the mandatory named fixtures.

### 3. Integrate structured publication and hierarchy

- [ ] Lower typed references to localparam references before string rendering;
  do not rewrite generated Verilog or recognize an application/component name.
- [ ] Qualify a complete strict Verilog-2001 header/declaration strategy. A
  non-ANSI port list followed by ordered body localparams and port declarations
  is a candidate to test, not a language-mode qualification result established
  by this note. Do not put localparams into a SystemVerilog-only parameter-list
  extension or silently change public positional parameter slots.
- [ ] Preserve integer sizing, signed comparison fences, Boolean normalization,
  helper dependencies and pre-existing parameter defaults. Never freeze a
  derived value to its elaboration witness.
- [ ] Render a child actual in its parent owner, for example `.WIDTH(TOTAL_BITS)`;
  retain the child's independent formal identity and canonical definition.
  Never expose a child's private localparam as a parent's public parameter.
- [ ] Qualify consolidated and one-file-per-component publication, repeated
  canonical instances, external generic bindings and generate-local ownership.

### 4. Establish meaningful tests before declaring implementation complete

This table records the acceptance contract; execution results belong in the
local validation receipt, separately from final qualification and closure.

| Fixture/control | Required observation |
| --- | --- |
| Unchanged roadmap `RecordLink` source | `val totalBits: ElabInt = dataBits + generationBits` automatically supplies the `TOTAL_BITS` naming hint without a new frontend declaration API. |
| Same generated RecordLink file | Public defaults/ranges are DATA_BITS `32 / 1..2048` and GENERATION_BITS `8 / 2..64`; overrides `(32,8)`, `(64,8)`, `(1,2)` and `(2048,64)` produce widths 40, 72, 3 and 2112. |
| Root-sensitive companion fixture | Include an asymmetric typed expression such as `dataBits + generationBits * 2`. Tuples `(32,8)` and `(8,32)` produce 48 and 72. This catches a root-swap mutation that the commutative RecordLink sum alone cannot distinguish. |
| Dependency chain | HEADER_BITS, RECORD_BITS and STORAGE_BITS retain their typed dependencies, legal ordering and correct same-file overrides. |
| Identity and naming negatives | Equal defaults with different roots, same-name independent declarations, collisions with explicit signals/formals, stale expressions, child/parent confusion and branch escape cannot acquire authority through a name. |
| Arithmetic and type controls | Operator changes, default freezing, sign/carry loss, helper misuse, incorrect Boolean normalization and wrong physical/raw width substitutions are detected against an independent oracle. |
| Scope and reuse | Repeated named expressions, alternate defaults, nested owners and distinct child actuals neither duplicate a public formal nor leak a private declaration. |
| Compact-domain control | Naming a previously legal authenticated large-domain expression does not request a new Cartesian enumeration. Preserve existing stronger exact/structural rejection controls. |
| Compatibility | Ordinary concrete output, native library consumers, combined Vec/reduction behavior and both publication modes retain their applicable semantics. |
| Qualification | Both Scala 2.12.18 and 2.13.12, repeated/cross-Scala determinism, strict parsing, lint, synthesis, simulation, independent native specialization equivalence, real mutations and every applicable inherited source/final-head gate pass. |

In particular, a root-swap control must alter an observable result. Merely
swapping the two operands of `DATA_BITS + GENERATION_BITS` is not a useful
functional negative because that expression is commutative. Keep the exact
roadmap fixture and add an asymmetric companion rather than changing the
acceptance example to make testing easier.

### 5. Qualification and closure after the batch is ready

- [ ] Record the actual approved native-change inventory and compatible
  source-review successors without weakening predecessor checks or proofs.
- [ ] Publish a clean implementation candidate with its full commit/tree
  identities and automatic CI suppressed. Determine affected workflows from
  the real changed source and contracts; do not guess workflow IDs in advance.
- [ ] Follow AGENTS.md: targeted dispatch first, immediately enable one hourly
  increment monitor, repair affected failures on fresh heads, then full CI only
  after all applicable targeted lanes pass on the intended candidate.
- [ ] Retain runnable Scala and actual generated Verilog, with source-bound test
  and mutation evidence. Mark completion only under the roadmap's final-head
  rules, merge qualified ancestry with safe post-merge CI suppression, verify
  closure and only then stop the increment monitor.

No targeted CI has been launched for this batch, and no CI monitor is active.
Follow the current user-directed batch timing before remote qualification.
