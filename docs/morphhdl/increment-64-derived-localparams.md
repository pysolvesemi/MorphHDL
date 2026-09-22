# Increment 64 — Derived localparam implementation handoff

## Status: preparatory review; implementation dependency not satisfied

Reviewed on 22 September 2026 against integration commit
`67d944fd6fa1bd7f3dc65bdc6439ce31e59283ca`, tree
`d19d225af835fcba77bcf96e142cf8a79f4a0fb6`.

The controlling contract remains
[the Increment 64 roadmap entry](parameterized-verilog-todo.md#parameter-expression-and-structural-legality-follow-ups-increments-64-and-65).
That contract requires Increments 59i, 61 and 63 implemented and merged,
including the retained PR188/189/190 repairs. At this review, Increment 59i
[PR #177](https://github.com/pysolvesemi/MorphHDL/pull/177) is still open,
draft and unmerged; its reported feature head is
`9d6d738d32c71ff4359384ae9d87f715bf20ec55`.

This document records source inspection and a proposed implementation sequence.
It does not waive the dependency, start an implementation branch, change a
completion checkbox, or claim compiled Scala, generated Verilog or passing
qualification. No compiler, application RTL, workflow or source-audit contract
is changed by this documentation checkpoint. **No generated-Verilog effect.**

Before implementation, re-read the actual merged dependency source and current
AGENTS.md. Do not use the pending 59i branch as an integration baseline or treat
an old successful workflow as qualification of a new source tree.

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
owners it does not observe. Inspect the actual post-59i structural capture and
publication paths before deciding the final change inventory.

## Proposed implementation sequence — not started

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

All entries below are required or proposed tests, not executed results.

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

### 5. Qualification and closure after the dependency is merged

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

No targeted CI was launched for this preparatory document, so it creates no
Increment 64 CI monitor. The outstanding next implementation prerequisite is
merged, qualified Increment 59i followed by a fresh dependency-state review.
