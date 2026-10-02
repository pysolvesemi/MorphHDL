# Parameterized-Verilog corrective roadmap

This file is the controlling implementation checklist for the single-source
parameterized-Verilog front door. It supersedes component-by-component
recommendations in earlier increment notes when those recommendations conflict
with this roadmap.

The approved production architecture from Increment 53d onward is documented
in [Typed elaboration architecture](typed-elaboration-architecture.md). That
decision supersedes the earlier zero-native-edit architecture for all future
unchecked increments. Completed zero-diff increments remain historical evidence
and regression oracles; they are not constraints on the new implementation.

## Roadmap discipline

**Current development plan, 1 October 2026:** implement the remaining standalone
work (CDC-LEG-01 and Increments 64, 65 and 66) one increment at a time on
`work/remaining-parameterized-increments`. Increment 59i remains on its existing
branch; it is not an implementation prerequisite for this development batch.
Leave `parameterized-verilog` untouched until 59i merges. Run focused local
validation after each increment, then reconcile the integration target and run
targeted followed by full final-head CI after the batch is ready. Local completion
records must distinguish implemented/locally validated work from remotely
qualified and merged work; checkboxes remain open until the latter requirements
are satisfied. Do not implement any item from
`morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md` as part of this batch;
that old work is slated for removal, not extension.

**Implementation status, 2 October 2026:** the unchecked boxes below track final
qualification and merged closure, not whether implementation has been written.

| Work item | Implementation and focused local validation | Remaining closure work |
| --- | --- | --- |
| CDC-LEG-01 | Complete; [receipts](remaining-increments-handoff.md) | Expanded local validation, targeted CI, full CI and merge |
| Increment 64 | Complete; [receipts](increment-64-derived-localparams.md) | Expanded local validation, targeted CI, full CI and merge |
| Increment 65 | Complete; [receipts](increment-65-scoped-legality.md) | Expanded local validation, targeted CI, full CI and merge |
| Increment 66, including all five imported `~/prompt.txt` tasks | Complete; [receipts](evidence/increment66/README.md) | Expanded local validation, targeted CI, full CI and merge |
| Increment 59i | Separate existing branch; excluded from this implementation batch | Existing PR qualification and merge |

**Current validation instruction, 2 October 2026:** run the expanded local suite,
then targeted remote CI first. Do not run wire-pass formal proofs or wire-pass CI
workflows: the user has explicitly excluded them because that work is intended
for removal. Inspect aggregate workflows for indirect invocation of those proofs
before dispatch. Record these exclusions explicitly; do not report excluded
checks as passes. Retained compiler and library simulation/formal checks remain
in scope. The expanded local run is in progress; remote CI has not been launched.

- The first unchecked increment remains the default sequential integration
  target. Explicitly declared parallel successors may start once every listed
  dependency is `[x]` on `parameterized-verilog`; increment numbering alone
  does not create a dependency.
- Every parallel branch must start from a merged dependency state and must
  incorporate the latest `parameterized-verilog` before final validation. An
  open branch or pull request never satisfies another increment dependency.
- Parameterizable elaboration values must remain typed symbolic objects through
  the native algorithm. They must never be converted to ordinary Scala `Int` or
  `Boolean` and reconstructed later from witnesses, source positions, component
  names, emitted names or object-shape guesses.
- The neutral low-level carriers are `spinal.core.ElabInt`,
  `spinal.core.ElabBool` and their typed range/width adapters. User-facing
  `HdlInt`/`HdlBool` values may construct or bind those carriers, but native
  `core` and `lib` code must not depend on the higher-level MorphHDL frontend.
- Existing `Int`/`Boolean` APIs remain available for ordinary concrete
  SpinalHDL. A literal call must select the concrete overload and generate the
  same parameter-free native RTL. There must be no implicit conversion from a
  symbolic elaboration value back to `Int` or `Boolean`.
- Small reviewed changes to SpinalHDL `core`, `lib` and helper signatures are
  explicitly allowed when they only introduce typed parameter carriers,
  overloads or mechanical propagation needed by the existing algorithm. Such
  changes must be listed in an approved native-change manifest and must not
  reimplement, fork or duplicate a library algorithm.
- A small compiler bridge may lower natural Scala syntax such as `if`, `else
  if`, typed equality, `require`, Boolean match and finite typed ranges only
  when the source operands are statically proven `ElabInt`/`ElabBool`. It must
  not instrument arbitrary native `Int`/`Boolean` code or recover erased
  provenance after typing.
- Native algorithms remain authoritative. A separately authored
  StreamFifo/StreamWidthAdapter/Counter/Mem implementation, component-name
  recognizer, emitted-signal recognizer or component-specific ParamRTL adapter
  is not an acceptable production substitute.
- Legacy native-`Int` shadow propagation and branch reconstruction may remain
  temporarily as compatibility and regression scaffolding, but no new feature
  may depend on it. The typed migration increments must retire it from the
  production path after parity is proven.
- Existing atomic ParamRTL nodes and historical zero-diff fixtures remain
  regression oracles. Their presence does not establish typed single-source
  support.
- Every increment must retain the applicable concrete parity, simulation,
  lint, synthesis, formal equivalence, mutation, determinism, strict
  Verilog-2001 and dual-Scala gates already established by earlier increments.
- An increment checkbox may change from `[ ]` to `[x]` only after its
  implementation and review are complete and every applicable final-head gate
  passes. Updating the checkbox is the final source change before publication.
- The suggested next sequential increment after completion is the first
  unchecked entry whose dependencies are satisfied. Independently eligible
  siblings may additionally be identified as parallel candidates.

The earlier source audit remains recorded in
[Native SpinalHDL source-preservation audit](native-spinal-source-preservation-audit.md).
It is historical input to the approved-change manifest, not a requirement to
restore an exactly zero-diff native tree.

## Corrective increments

- [x] **Increment 29 — Single-source symbolic-width bridge**

  Carry an existing typed parameter object through the ordinary SpinalHDL
  width front door. One normal component must accept a symbolic configuration
  width and use `in UInt(width bits)` to emit a public Verilog parameter and
  `[WIDTH-1:0]` port width. The same component source must provide its concrete
  default witness; it must not contain a separately authored
  `parameterizedDesign`, `moduleDef`, `packedBits`, or component-specific
  emitter. Existing concrete `SpinalVerilog` behavior remains unchanged.
  The bounded executable contract is documented in
  [Increment 29](increment-29-single-source-symbolic-width.md).

- [x] **Increment 30 — Symbolic data shapes**

  Preserve symbolic widths through `Bits`, `UInt`, `SInt`, ports, registers,
  cloning and `HardType`, then through `Bundle`, `Vec`, `Stream` and `Flow`
  payload shapes. Prove positive-width constraints over the complete declared
  parameter domain.

- [x] **Increment 31 — Generic expressions and connections**

  Lower ordinary Spinal assignments, muxes, arithmetic result widths,
  concatenation, slicing and resize without fixture-specific ParamRTL calls.
  Use the real `Stream.m2sPipe()` path as the first library-reuse proof and
  compare it with the existing Increment 28 behavioral oracle.

- [x] **Increment 32 — Hierarchy and parameter binding**

  Discover child parameter dependencies, declarations and parent bindings from
  ordinary component hierarchy. Infer compatible symbolic payload-width
  bindings from connections, retain one definition per logical component and
  reject ambiguous or inconsistent constraints.

- [x] **Increment 33 — Structural loops and generate control**

  Integrate symbolic ranges and indices with normal component construction so
  parameter-bounded instance/declaration loops lower to Verilog generate
  regions. Cover `until`, `to`, child instances, concurrent connections, Vec
  indexing and slices, then add parameter-controlled generate-if/case with
  explicit diagnostics for unsupported Scala side effects.

- [x] **Increment 34 — Generic combinational and sequential processes**

  Lower normal Spinal combinational and clocked statements instead of atomic
  mux/register nodes. Classify a safe parameter-bounded loop inside a process
  as a procedural Verilog `for`, while structural construction remains a
  generate loop, and retain driver, latch, clock and reset validation.

- [x] **Increment 35 — Native symbolic memories**

  Carry symbolic width, depth and address expressions through ordinary Spinal
  `Mem`, `readSync` and `write`. Validate capacity, enable, collision and
  out-of-range policies against the existing single-port and simple-dual-port
  memory contracts.

- [x] **Increment 36 — Native library reuse**

  Reuse ordinary Spinal `Counter`, Stream/Flow pipeline primitives and
  `StreamFifo` with symbolic payload width and initially static depth. Extend
  shared primitives or core representations where necessary; do not duplicate
  their algorithms in component-specific ParamRTL nodes.

- [x] **Increment 37 — Parameterized StreamFifo depth**

  Adapt the existing StreamFifo source path so symbolic depth controls storage,
  address width, pointers, occupancy and depth-dependent special cases while
  retaining the library algorithm and handshake semantics. Prove depths 1, 3,
  5 and 8 without regenerating or specializing the module.

## Historical native-source preservation increments

The completed increments in this section record the previous zero-native-edit
approach. Their tests remain valuable, but their architectural restrictions are
superseded for every unchecked increment by the typed elaboration architecture.


- [x] **Increment 38 — Native-source inventory and zero-diff guard**

  Convert the reviewed audit into a machine-readable manifest that classifies
  every current change to upstream-owned `core`, `lib` and `idslplugin` source
  as a direct edit, MorphHDL sidecar or generated/backend coupling. Add a CI
  guard that rejects any new unapproved native-source modification. Establish
  exact baseline and current hashes without changing parameterized behavior.

- [x] **Increment 39 — External elaboration and publication boundary**

  Prove that MorphHDL can invoke normal SpinalHDL elaboration, inherited
  validation, graph inspection and Verilog publication from a MorphHDL-owned
  entrypoint without patches to `Spinal.scala`, `Phase.scala`,
  `PhaseVerilog.scala` or `ComponentEmitterVerilog.scala`. Route through
  existing public plugin/configuration/phase facilities where possible. If a
  required capability is inaccessible, stop and present the precise minimal
  native hook and alternatives for approval before changing source.

- [x] **Increment 40 — External symbolic width and data-shape retention**

  Restore the native `BaseType`, `Bits`, `SInt` and `UInt` construction and
  cloning paths. Move bounded symbolic-width ownership to a MorphHDL registry
  or wrapper associated by object identity at the external elaboration
  boundary. Preserve all Increment 29 and 30 contracts through ports,
  registers, clones, `HardType`, `Bundle`, `Vec`, `Stream` and `Flow` without
  native constructor or clone hooks.

- [x] **Increment 41 — External expression, connection and hierarchy lowering**

  Move the Increment 31 and 32 expression, declaration, connection and
  hierarchy analysis behind the external MorphHDL boundary. Remove native
  emitter/phase routing changes while preserving ordinary Spinal expression
  semantics, canonical module definitions and named parameter bindings. Replace
  emitted-text assumptions with graph/AST identity wherever available and
  reject ambiguous mappings explicitly.

- [x] **Increment 42 — External structural and process capture**

  Relocate the Increment 33 and 34 structural-region and procedural-loop
  metadata/lowering from the native `core` source tree into MorphHDL-owned
  modules. Preserve ordinary driver, latch, clock, reset and hierarchy
  validation, and retain parameter-controlled generate-for/if/case and safe
  procedural-for behavior. If native AST ownership prevents an external
  implementation, apply the native-change approval gate before proceeding.

- [x] **Increment 43 — Native memory reuse with zero `Mem.scala` changes**

  Restore the ordinary `Mem` constructors and remove automatic symbolic
  attachment from native memory creation. Discover and associate symbolic
  element width, depth, address and port policies externally while keeping the
  native `Mem`, `readSync` and `write` algorithms unchanged. Preserve all
  Increment 35 capacity, enable, read-first collision, out-of-range and
  concrete-parity contracts.

- [x] **Increment 44 — Native Counter, Stream and Flow reuse with zero library changes**

  Restore native `Counter.scala` and the Increment 36 changes in `Stream.scala`.
  Retain symbolic Counter and payload geometry externally while executing the
  unmodified Counter, `Stream.m2sPipe`, `Stream.s2mPipe`, `Stream.halfPipe`,
  `Flow.m2sPipe` and static-depth StreamFifo algorithms. Preserve their
  concrete-default parity and override tests without component-specific RTL
  reconstruction.

### Historical dependency graph through Increment 53c

The dependencies below describe the completed zero-native-edit work and are
retained for traceability:

- Increments 45, 46 and 48 were independent parallel starts.
- Increment 47 depended on Increment 46; Increment 49 depended on 47;
  Increment 50 depended on 49.
- Increment 51 joined the explicit-condition and native-`Int` paths; Increment
  52 extended that branch-reconstruction path.
- Increment 53 joined memory provenance and symbolic control flow.
- Increments 53a, 53b, 53b.1 and 53c supplied formal, enum and AXI closure.

### Typed architecture dependency graph from Increment 53d

- Increment 53d depends on merged Increment 53c and is the mandatory pivot to
  typed elaboration values; that pivot is implemented and merged.
- Increment 53e follows merged Increment 53d and migrates native StreamFifo
  depth and branch-local geometry to the typed path.
- Increment 53f depends on merged Increment 53e and closes typed Counter, Mem,
  Vec, helper and finite-range primitives needed by broad library reuse.
- Increment 53g depends on merged Increment 53f and removes native-`Int` shadow
  reconstruction and component-specific recognizers from the production path.
- Increments 54 through 57 form the strict consolidation, compatibility and
  broad-migration chain. Increment 57a follows merged Increment 57 to close the
  reviewed native `StreamFifoCC` CDC surface. Increment 57b follows merged
  Increment 57a to qualify joint typed payload-width and depth behavior over
  its finite formal witness matrix; Increment 58 remains blocked until merged
  Increment 57b establishes that proof.

Dependencies are transitive. No future increment may add a new dependency on
the superseded native-`Int` shadow path. Temporary MorphHDL constructor aliases
may remain only as regression scaffolding until typed native-looking parity is
proved.

- [x] **Increment 45 — Automatic native `Mem` symbolic-depth provenance**

  **Dependencies:** Increment 44 implemented and merged.

  Allow ordinary native-looking `Mem(HardType(...), depth)` construction to
  pass only the concrete witness to untouched SpinalHDL while retaining the
  exact originating `HdlInt` depth expression externally, without requiring
  `morphhdl.frontend.Mem`. Associate provenance by a deterministic
  source/call-site token and exact native-object identity; never infer it by
  matching a concrete integer value alone. Increment 45 must not depend on the
  Increment 46 formal-parameter identity API, but its retained provenance must
  remain composable with that later API. Prove that literal `Mem(..., 5)`
  remains concrete, while `Mem(..., DEPTH)` emits `[0:DEPTH-1]`, compound depth expressions retain
  their symbolic bounds, and equal witnesses with distinct symbolic origins
  remain distinguishable. Reject ambiguous or conflicting provenance
  explicitly. Preserve byte-for-byte native `Mem.scala`, ordinary concrete
  `SpinalVerilog`, all Increment 43 memory contracts, deterministic replay and
  both supported Scala versions.

The formalization and symbolic-control-flow increments below follow the explicit
dependency graph above; they are not globally serial. Increment 53 must not
start until Increments 45 through 52 are implemented, reviewed and merged.

- [x] **Increment 46 — Formal parameter identity and canonical child modules**

  **Dependencies:** Increment 44 implemented and merged.

  Separate component-definition formals from parent-instance actual
  expressions. Add an explicit deterministic formal API such as
  `formalParam(actual, "WIDTH")` or an equivalent component-identity registry.
  Prove that `new Leaf(leftWidth)` and `new Leaf(rightWidth)` retain one
  canonical `Leaf #(parameter integer WIDTH = ...)` definition with named
  `.WIDTH(LEFT_WIDTH)` and `.WIDTH(RIGHT_WIDTH)` bindings, even when the legal
  parent domains differ. Reject incompatible defaults/domains, ambiguous slot
  matching and duplicate formal declarations. Explicit names are required
  first; Scala source-name inference may be added only as validated sugar on
  both supported Scala versions.

- [x] **Increment 47 — External formalization boundary for native `Int` APIs**

  **Dependencies:** Increment 46 implemented and merged.

  Introduce MorphHDL-owned `formalComponent`, `formalRegion` or equivalent
  adapters that pass only concrete witnesses to untouched SpinalHDL
  constructors and algorithms while retaining formal-to-actual symbolic
  bindings by component/region identity. Prove simple native `Int`-controlled
  geometry and hierarchy without native-source changes, compiler magic,
  emitted-name recognition or component-specific RTL reconstruction. This
  increment establishes identity and lifetime only; it does not recover
  unselected Scala control-flow branches.

- [x] **Increment 48 — Natural symbolic conditionals for explicit `HdlInt`/`HdlBool`**

  **Dependencies:** Increment 44 implemented and merged.

  Add a compiler-plugin or equivalently typed frontend transformation for
  conditionals whose condition is explicitly proven to be MorphHDL symbolic.
  Capture both alternatives and lower them to parameter-controlled Verilog
  structure while keeping the witness-selected path authoritative for ordinary
  Spinal elaboration and validation. Do not add an implicit `HdlBool`-to-
  `Boolean` witness conversion, and leave ordinary Scala `Boolean`
  conditionals unchanged. Cover simple `if`/`else`, chained `else if`,
  diagnostics and dual-Scala behavior before handling native `Int`
  provenance. The Increment 48 closure repair must retain a single
  source-ordered Verilog `if / else if / else` chain without dominance-mask
  sibling generates, and support nested conditionals for already-explicit
  `HdlInt`/`HdlBool` predicates. Natural explicit predicates may override
  generated block labels with `.named("g_true")` on a non-final chain condition
  and `.named("g_true", "g_false")` on a simple conditional or the final chain
  condition; a nested source `else` preserves its custom false-block label.
  Native-`Int` nested control flow remains governed by Increments 51 and 52.

- [x] **Increment 49 — Native `Int` symbolic provenance propagation**

  **Dependencies:** Increment 47 implemented and merged.

  At an Increment 47 formalization boundary, associate each selected native
  `Int` constructor argument or local value with both its concrete Scala
  witness and its MorphHDL symbolic actual. Preserve that shadow provenance
  through component construction, nested formal scopes and Spinal
  re-elaboration without changing the native API or runtime value. Prove
  deterministic identity, cleanup, replay and conflict diagnostics. Do not yet
  transform arbitrary arithmetic or control flow.

- [x] **Increment 50 — Shadow native `Int` expressions and predicates**

  **Dependencies:** Increment 49 implemented and merged.

  Propagate proven symbolic provenance through the bounded operations needed by
  native library code: addition, subtraction, multiplication, division,
  remainder, comparisons, min/max, address/log2 helpers and power-of-two
  predicates. Retain one concrete witness expression and one bounded symbolic
  expression, prove their default agreement and domain safety, and reject
  unsupported calls, boxing, mutable escape or ambiguous aliasing explicitly.

- [x] **Increment 51 — Symbolic native-`Int` branch capture**

  **Dependencies:** Increments 48 and 50 implemented and merged.

  Transform `if`/`else if`/`else` only when its ordinary Scala Boolean
  predicate is proven to depend on shadow-symbolic native `Int` values from
  Increments 49 and 50. Capture every source alternative, keep only the
  witness-selected alternative in the ordinary concrete Spinal graph and
  retain all alternatives in MorphHDL-owned structural IR for generic
  Verilog-2001 lowering. Preserve source order, names and diagnostics; ordinary
  Scala conditionals without symbolic provenance remain untouched.

- [x] **Increment 52 — Nested symbolic control flow and side-effect safety**

  **Dependencies:** Increment 51 implemented and merged.

  Extend native symbolic branch capture to the bounded constructs required by
  real library algorithms: nested conditionals, loops inside alternatives,
  local vals, registers, memories, Areas/ClockingAreas, naming and supported
  assignments. Define an explicit safe side-effect contract and fail closed for
  mutable external state, I/O, reflection, nondeterminism or unsupported
  arbitrary Scala effects. Prove deterministic replay, hierarchy stability,
  driver/latch/clock/reset validation and nested generate legality.

- [x] **Increment 53 — Native StreamFifo parameter structure without source edits**

  **Dependencies:** Increments 45 and 52 implemented and merged.

  Apply Increments 46 through 52 to the real, untouched `StreamFifo` source.
  Restore the Increment 37 `Stream.scala` overload and pointer edits and remove
  the `ParameterizedStreamFifoDepth` library sidecar. Retain the native
  depth-one, power-of-two and non-power-of-two alternatives and lower them to
  one parameterized Verilog definition with parameter-controlled generate
  structure. Remove port/signal-name recognition and
  `rewriteParameterizedStreamFifoDepth`; prove depths 1, 3, 5 and 8 without a
  separately authored FIFO. Stop for architecture approval if the alternatives
  cannot be retained through the generic provenance and branch-capture path.

- [x] **Increment 53a — Native StreamFifo concrete-witness formal equivalence**

  **Dependencies:** Increment 53 implemented and merged.

  Keep Increment 53 checked and add an independent formal proof layer around
  its generated top-level design. From the same untouched
  `spinal.lib.StreamFifo` source, generate ordinary `SpinalVerilog` concrete
  witnesses with literal native-`Int` depths 1, 3, 5 and 8. Generate the
  Increment 53 `MorphVerilog` top once, specialize that one parameterized
  definition to each matching `DEPTH`, and prove every full top-level pair
  sequentially equivalent after a shared synchronous-reset edge under
  arbitrary shared push-valid/payload, pop-ready, flush and later-reset inputs.
  Compare push-ready, pop-valid, occupancy and availability on every proved
  cycle, and compare pop payload only while pop-valid because unwritten memory
  payload is unspecified. The proof must be solver-backed and unbounded, or
  exhaustive with an explicit completeness argument; bounded simulation,
  lint, synthesis, `yosys check`, structural/text equality and a
  concrete-vs-concrete comparison do not satisfy it. Require independently
  generated DUT legs, reject a `DEPTH` parameter on the concrete leg, and add a
  DEPTH=3 negative-control mutation that changes a compared MorphHDL observable
  and must produce a genuine assertion counterexample. Run generation and all
  four proofs on Scala 2.12.18 and 2.13.12 in a pinned formal toolchain while
  retaining strict Verilog-2001, determinism and source-boundary gates. No
  separately authored FIFO, native `StreamFifo` source edit, emitted-name
  heuristic or Increment 53 checkbox change is permitted.

- [x] **Increment 53b — MorphHDL-owned module-local SpinalEnum parameters**

  **Dependencies:** Increment 53 implemented and merged.

  Keep all upstream-owned SpinalHDL `core`, `lib` and `idslplugin`
  production sources byte-identical. In MorphHDL-owned post-publication
  code, discover exact `SpinalEnum` definitions, elements and encodings from
  the native graph, replace global enum `` `define `` references with
  module-local Verilog-2001 `localparam`s named by the uppercase enum and
  element, for example Scala `State.IDLE` becomes Verilog `STATE_IDLE`.
  Never add a component, module or hierarchy prefix. Retain encoding-specific
  values and one-hot index helpers, remove recognized global macros from the
  final `MorphVerilog` output, and allow identical names in different module
  scopes. Fail closed on conflicting final names or existing identifiers.
  Ordinary `SpinalVerilog` output must remain unchanged. In both supported
  Scala lanes, formally prove the native macro RTL and MorphHDL localparam RTL
  equivalent at the concrete parameter witness using Yosys `equiv_make`,
  sequential induction and `equiv_status -assert`, in addition to deterministic
  generation, strict Verilog-2001 lint/synthesis and native-source preservation.

- [x] **Increment 53b.1 — SCREAMING_SNAKE_CASE SpinalEnum localparam names**

  **Dependencies:** Increment 53b implemented and merged.

  Refine only the MorphHDL-owned module-local enum publication naming from
  Increment 53b. Convert each resolved enum type and element identifier to
  deterministic SCREAMING_SNAKE_CASE before joining them: split lowercase-or-
  digit to uppercase boundaries, split acronym-to-word boundaries, preserve
  existing underscores and digits, and uppercase with locale-independent
  rules. For example, Scala `Inc53bFormalState.IDLE` must become Verilog
  `INC53B_FORMAL_STATE_IDLE`, and `AXI4ReadState.waitResp` must become
  `AXI4_READ_STATE_WAIT_RESP`. Apply the same base name to retained one-hot
  `_OH_ID` bit-index helpers without changing their semantics. Never add a
  component, module, instance or hierarchy prefix. Fail closed when distinct
  source identifiers such as `FooBar` and `Foo_Bar` canonicalize to the same
  module-local name, even when their encoded values happen to match. Keep
  ordinary `SpinalVerilog` macro output and every upstream-owned SpinalHDL
  production source unchanged. Re-run deterministic Verilog-2001 lint and
  synthesis plus macro-versus-localparam sequential formal equivalence on
  Scala 2.12.18 and 2.13.12.

- [x] **Increment 53c — Native AXI4 Slave Factory parameterized offsets**

  **Dependencies:** Increment 53b implemented and merged.

  Preserve bounded symbolic register-map offsets while application source uses
  the real, untouched `spinal.lib.bus.amba4.axi.Axi4SlaveFactory`. MorphHDL may
  add only compiler/runtime provenance, exact-object metadata and
  parameter-aware native case-key lowering. It must not modify upstream-owned
  SpinalHDL `core`, `lib` or `idslplugin` production sources, reimplement or
  replace the factory, duplicate AXI/register-map algorithms, recognize
  emitted module or signal text, or infer symbolic identity from equal
  concrete addresses. Prove direct and derived offsets, unrelated fixed-address
  isolation, deterministic dual-Scala `MorphVerilog`, ordinary concrete
  `SpinalVerilog` parity and strict Verilog-2001 lint/synthesis. Generate
  independent native-`Int` concrete witnesses at offsets `0x010`, `0x040` and
  `0x070`, specialize the single MorphHDL definition to each matching offset,
  and prove the complete top-level AXI/register behavior sequentially
  equivalent after a shared reset under arbitrary shared AXI inputs. Compare
  response payloads only while their valid outputs are asserted, and require a
  deliberately mutated MorphHDL observable to produce a genuine assertion
  counterexample. Run all positive proofs and the mutation control on Scala
  2.12.18 and 2.13.12 in the pinned formal toolchain while retaining the native
  source-preservation boundary.

- [x] **Increment 53d — Typed elaboration carriers and native StreamWidthAdapter migration**

  **Dependencies:** Increment 53c implemented and merged.

  Replace the production native-`Int` reconstruction path for relational width
  logic with neutral `spinal.core.ElabInt` and `spinal.core.ElabBool` carriers.
  Each carrier must retain one concrete witness, the exact bounded expression,
  parameter schemas and source identity through the native algorithm. Preserve
  ordinary `Int`/`Boolean` overloads for parameter-free SpinalHDL and prohibit
  implicit symbolic-to-concrete conversion.

  Add typed arithmetic, comparison, equality/inequality, Boolean combination,
  `elabWidthOf`, packed-width, resize and constant-factor Counter adapters.
  Extend the compiler only with a small statically typed syntax bridge for
  natural `if / else if / else`, typed `==`/`!=` and `require`; it must never
  discover symbolic meaning from an ordinary Scala `Int`, component name,
  source-file special case, emitted identifier or equal witness.

  Mechanically migrate the authoritative native `StreamWidthAdapter` algorithm
  to obtain its two widths as `ElabInt`; retain its equal-width, downsize and
  upsize code unchanged apart from typed signatures/helpers. Concrete `Int`
  calls must remain parameter-free and behaviorally identical. Prove equal,
  downsize and upsize parameter domains, backpressure and byte order, reject
  independent ambiguous roots, and run dual-Scala compilation, deterministic
  Verilog-2001 lint/synthesis, simulation and concrete-specialization formal
  equivalence. Record every approved native source change in the typed native
  bridge manifest. The legacy shadow-width implementation remains only as an
  oracle and must not be used by the migrated adapter.

- [x] **Increment 53e — Typed StreamFifo depth and branch-local geometry**

  **Dependencies:** Increment 53d implemented and merged.

  Migrate the real native StreamFifo depth path to `ElabInt` while retaining the
  existing `Int` overload and the authoritative FIFO algorithm. Add the typed
  `log2Up`, `isPow2`, Boolean-to-integer, memory/Vec depth, finite range and
  generate adapters needed by that source. A symbolic alternative must be
  validated in its own narrowed parameter domain rather than injected into the
  default-witness graph. Prove one parameterized definition at depths 1, 3, 5
  and 8, ordinary concrete parity, complete handshake/storage behavior and
  sequential formal equivalence on both supported Scala versions. No native-
  `Int` shadow capture, component-name recognizer or separate FIFO is allowed.

- [x] **Increment 53f — Typed parameter-sensitive primitive closure**

  **Dependency graph:** Increment 53e is implemented and merged; Increment 53g
  remains blocked until every Increment 53f closure gate passes.

  Generalize typed elaboration through Counter limits, Mem/Vec depths, address
  and logarithm helpers, slices, resize, finite structural/procedural ranges and
  child formal bindings. Keep concrete overloads authoritative for literal
  calls and fail closed when a typed operation cannot prove a finite legal
  domain. Migrate representative native Counter, Stream/Flow, memory and
  hierarchy users without algorithm duplication and prove parity on both Scala
  lanes.

- [x] **Increment 53g — Retire native-Int shadow reconstruction from production**

  **Dependencies:** Increment 53f implemented and merged.

  Remove the parser-wide native-`Int` provenance, source-position alias,
  constructor-boundary and component-specific branch-reconstruction machinery
  from the production compiler path. Keep narrowly scoped historical fixtures
  only as explicit regression oracles where useful. Add guards that reject new
  references from production code to native-`Int` shadow capture, file-specific
  component eligibility, witness-value inference and emitted-name recognition.
  Re-run all migrated library, formal, simulation, lint, synthesis and
  determinism gates before deleting obsolete runtime registries.

- [x] **Increment 54 — Typed elaboration layering and canonical IR cleanup**

  **Dependencies:** Increment 53g implemented and merged.

  Consolidate the neutral `ElabInt`/`ElabBool` expression model, typed control
  bridge and approved native adapters into stable low-level packages that do
  not depend on the high-level MorphHDL frontend. Remove circular build
  coupling and obsolete shadow registries while preserving source locations,
  bounded diagnostics and the canonical post-parameterization IR/API required
  by optional passes.

- [x] **Increment 55 — Concrete compatibility and approved-native-change audit**

  **Dependencies:** Increment 54 implemented and merged.

  Replace the old zero-diff gate with an exact approved-change manifest. Prove
  that only reviewed parameter-sensitive signatures, overloads and mechanical
  propagation hooks differ from the selected SpinalHDL baseline. Run complete
  ordinary `SpinalVerilog` parity, binary/source compatibility checks where
  applicable, and all inherited parameterized simulation, lint, synthesis,
  formal, mutation and determinism gates. Unrelated native source must remain
  byte-identical.

- [x] **Increment 56 — Native-looking typed library-call surface**

  **Dependencies:** Increment 55 implemented and merged.

  Make application source use ordinary imported SpinalHDL constructors and
  methods while overload resolution selects concrete `Int`/`Boolean` behavior
  for literals and typed `ElabInt`/`ElabBool` behavior for parameters. Cover
  Counter, Stream, Flow, Mem, Vec and hierarchy calls without MorphHDL-prefixed
  production constructors, implicit symbolic-to-concrete conversion or runtime
  provenance reconstruction.

- [x] **Increment 57 — Broad native library migration and proof**

  **Dependencies:** Increment 56 implemented and merged.

  Migrate the remaining reviewed parameter-sensitive library algorithms to the
  typed elaboration surface using only mechanical signature/helper changes.
  Preserve each authoritative algorithm and prove concrete parity plus
  parameter override behavior across Counter, Stream/Flow pipelines, FIFOs,
  memory users and representative bus/register-map components. Expand the
  approved native-change manifest only with independently reviewed entries.

- [x] **Increment 57a — Typed native StreamFifoCC depth and CDC proof**

  **Dependencies:** Increment 57 implemented and merged.

  Migrate the real native `StreamFifoCC` depth path, both companion entry
  points, and the ordinary `Stream.queue(..., pushClock, popClock)` and
  `queueWithPushOccupancy` helpers to `ElabInt`. Retain every existing `Int`
  API and descriptor and keep the native dual-clock Gray-pointer, memory,
  synchronizer and reset-buffering algorithm as the sole implementation.
  Preserve the exact legal power-of-two depth subset through the child formal,
  RAM depth, pointer/address and occupancy widths, Gray-code comparisons and
  formal helpers. A parameter range may contain non-power-of-two values only
  when generated structural guards exclude them from the FIFO algorithm and
  fail closed for those overrides; witness-only legality is not sufficient.

  Prove one deterministic parameterized definition per static reset topology
  at depths 2, 4, 8 and 16 against independently elaborated ordinary
  `SpinalVerilog` witnesses under faster-push and faster-pop clock schedules.
  Require concrete parity, dual-Scala compilation, asynchronous-clock
  simulation, strict Verilog-2001 lint and synthesis, sequential formal
  equivalence, a live mutation counterexample and the approved-native-change
  audit. Clock domains, `withPopBufferedReset`, synchronizer metadata and CDC
  topology remain static. Do not add a separately authored FIFO, native-`Int`
  shadow reconstruction, component/source/emitted-name recognition, or a
  copied CDC algorithm.

- [x] **Increment 57b — Typed native StreamFifoCC payload-width formal proof**

  **Dependencies:** Increment 57a implemented and merged.

  Extend the native `StreamFifoCC` relational proof so payload `WIDTH` and FIFO
  `DEPTH` are independent typed parameters on the candidate definition. Prove
  the exact Cartesian witness matrix `WIDTH` in `{1, 5, 8, 32}`, `DEPTH` in
  `{2, 4, 8, 16}`, both direct and buffered pop-reset topologies, and both
  faster-push and faster-pop clock ratios. This is 64 positive configurations
  per enabled Scala lane. Width one is the scalar boundary, width five is an
  odd non-byte shape, width eight preserves the Increment 57a baseline, and
  width 32 is the declared proof-matrix upper boundary.

  Generate each concrete reference independently through ordinary
  `SpinalVerilog` and the native `Int` `StreamFifoCC` construction for its exact
  width, depth and reset topology. Specialize the typed candidate by setting
  both `WIDTH` and `DEPTH`, compare handshake, occupancy and valid payload
  observations under the existing CDC assumptions, and retain a deliberate
  payload mutation that must produce a genuine counterexample. Parse failure,
  missing modules, timeout, `UNKNOWN` or a tool error is not a proof or a valid
  mutation result. Do not copy or re-author the FIFO, Gray-pointer,
  synchronizer, RAM or reset-buffering algorithm as a second implementation;
  a proof harness may only instantiate the authoritative native FIFO.

  The finite matrix qualifies only the listed widths and depths; it does not
  claim universal formal quantification over every positive `WIDTH`, a new
  payload type, arbitrary clock schedules, or reset behavior beyond the
  Increment 57a contract. See
  [Increment 57b](increment-57b-typed-streamfifocc-payload-width.md).

- [x] **Increment 58 — Legacy adapter and shadow-path retirement**

  **Dependencies:** Increment 57b implemented and merged.

  Remove or deprecate dual-factory, component-specific, emitted-name and native-
  `Int` shadow production paths after every supported feature has typed parity.
  Keep old atomic contracts only as explicit compatibility or mutation oracles.
  Finalize the stable typed post-parameterization, pre-emission handoff used by
  optional MorphHDL-owned IR passes.

- [x] **Increment 59 — Typed BlackBox parameter and generic binding**

  **Dependencies:** Increment 58 implemented and merged.

  Extend ordinary `BlackBox.addGeneric` so typed `ElabInt` and `ElabBool`
  actuals retain exact expression and declaration-root authority while native
  Verilog/VHDL emitters receive only their concrete witnesses. On the
  single-source Verilog-2001 path, declare BlackBox-only roots on the owning
  generated parent and rewrite only the exact named generic and packed-port
  associations of the exact external instance. Preserve mixed generic order
  and types, external-module ownership, concrete parity, hierarchy coexistence,
  canonical parameter merging, deterministic output and both Scala lanes.
  Reject duplicate or missing generic/port associations, ambiguous instances,
  unsupported port bindings, schema/root collisions and inexact projections.
  Require focused simulation, strict lint/synthesis, formal equivalence, a live
  mutation control and every inherited audit/compatibility gate. Do not
  generate or reconstruct the external module, recover symbolic meaning from
  witnesses, recognize component/source/emitted signal names, or add a
  component-specific RTL implementation.

- [x] **Increment 59a — Bounded recursive Verilog module generation and proof**

  **Dependencies:** Increment 59 implemented and merged.

  Validate and support one strict Verilog-2001 module whose parameter-controlled
  recursive step instantiates the same emitted module with an exact decreasing
  actual such as `.N(N - 1)`, and whose explicit base branch terminates the
  elaborated hierarchy. Use an ordinary typed MorphHDL/SpinalHDL component and
  exact object-owned metadata; a generated self-reference may use a same-name
  BlackBox declaration only as the Verilog identity of that emitted component,
  not as a separately authored implementation. Prove a representative unsigned
  modular power function `x^N`, including `N = 0`, odd and even exponents, one
  canonical module definition, deterministic named binding, legal generated
  branch structure and rejection of non-decreasing, negative-domain or
  otherwise unprovable recursion. Require strict Verilog-2001 parsing,
  simulation and synthesis with the supported open-source tools, concrete-
  specialization formal equivalence, a live mutation counterexample, dual-Scala
  compilation and every inherited audit/compatibility gate. Do not claim or
  admit arbitrary runtime recursion, cyclic hardware, unbounded elaboration or
  tool-portable recursion beyond the explicitly qualified tool matrix.

- [x] **Increment 59b — Typed parameterized Vec reduceBalancedTree**

  **Dependencies:** Increment 59a implemented and merged.

  Migrate the authoritative SpinalHDL `reduceBalancedTree` helper so a native
  `Vec[T]` with typed symbolic element width and typed symbolic element count can
  retain a balanced reduction topology in one parameterized Verilog-2001
  definition. Preserve the existing concrete `Seq`/`Vec` behavior and generic
  associative/commutative operator callback; do not replace the helper with an
  operation-specific adder, OR tree or component recognizer. Define exact
  non-empty-domain, odd-tail, level-bridge and result-width semantics, and lower
  only operator bodies whose typed graph can be replayed safely in generated
  stages. Prove sizes including 1, odd, non-power-of-two and power-of-two cases,
  parameterized element widths, deterministic topology, logarithmic depth,
  strict lint/synthesis, simulation, formal specialization equivalence,
  mutation, dual-Scala and inherited compatibility gates. Fail closed for an
  empty domain, non-associative/unsupported side effects, ambiguous shapes or a
  topology whose finite bound cannot be proven.

  **Completion evidence:** implementation source `ebc33b9ef065b5591c419f15b1bc9b3085ee6aa7`,
  tree `668b1446e0d58574baac1381072bf01cf297df1e`. The qualified safe-graph scope,
  source-bound dual-Scala proofs and explicit rejected cases are recorded in
  [the 59b completion record](increment-59b-parameterized-reduce-balanced-tree.md).
  Completion head `b0a4388e3babbc01500a620eefe6c0965e9e6343` passed its CI.
  The combined 59b/60e final head `33105c07fd0f93d3335469120381b0c959bb9e86`
  subsequently qualified and merged through
  [PR #157](https://github.com/pysolvesemi/MorphHDL/pull/157) as
  `feca6b9d599d97af92ed9f6a8bc871ef008c395e`. The completed 59b checkbox refers
  only to its documented safe-graph subset; the extensions below remain open.

### Parallel Vec and balanced-reduction extensions (59c through 59i)

The following are new unchecked capabilities, not a reopening of the qualified
59b safe-graph subset. They cover the remaining field-preserving interfaces,
symbolic result widths, nested composite reductions, callback expressiveness,
register bridges and structural ownership discussed after 59b.

**Dependency and parallel-start rules:**

| Increment | Required merged dependencies | Parallel work |
| --- | --- | --- |
| 59c | 59b (including its inherited 53f Vec foundation) | Independent of 59d through 59h and the unfinished Increment 60 children |
| 59d | 59b | Independent of 59c and 59e through 59h |
| 59e | 59b | Independent of 59c, 59d and 59f through 59h |
| 59f | 59b | Independent of 59c through 59e, 59g and 59h |
| 59g | 59b | Independent of 59c through 59f and 59h |
| 59h | 59b | Independent of 59c through 59g |
| 59i | 59c, 59d, 59e, 59f, 59g and 59h | Final cross-feature integration and qualification join |

59c through 59h may each start from the current merged base. Numbering does not
create a serial chain. Each track includes the minimal typed propagation,
capture/admission and native lowering needed to qualify its own standalone
surface against 59b; its acceptance must not silently require an unmerged sibling.
They must agree on shared exact-object shape, width, capture and owner contracts,
reuse existing infrastructure and reconcile overlapping edits on the latest
integration branch. Full combinations belong to 59i. The existing Increment 60
dependency chain is unchanged and may proceed independently.

**Common architecture and acceptance rules for these extensions:**

- Keep ordinary `Vec(...).reduceBalancedTree(op, levelBridge)` and component
  source authoritative. Examples are qualification fixtures, never class-name,
  field-name, callback-name, source-position or emitted-text recognizers. Do not
  replace native algorithms with an adder, multiplier, RGB tree or handwritten
  RTL implementation.
- Retain recursive field paths, native leaf kinds, directions, exact symbolic
  widths/counts, parameter-root identity and ownership before normalization.
  Generic native IR transfer rules must determine acceptance; matching concrete
  witnesses or a few successful callback executions cannot supply that evidence.
- Preserve the native helper's exact pairing order, singleton bypass, odd-tail
  bridge calls and parameter-dependent active levels. Do not pad a tail, insert
  a neutral operand, reassociate an expression or change latency without proving
  identical native behavior, result shape and width. Non-associativity alone is
  not evidence of an unsafe host callback when the exact native topology is
  preserved; any actual reassociation needs a separate algebraic proof.
- Preserve the ordinary concrete `Int`/`Boolean` APIs and parameter-free
  `SpinalVerilog` output. Keep the strict Verilog-2001 target, native arithmetic
  and clocked emission, typed signedness boundaries and approved-native-change
  audit. Packed multi-element transport is not one signed scalar. `Mem` remains
  native memory; these Vec extensions must not repack memory storage.
- Every implementation needs its own dual-Scala tests, deterministic generation,
  Icarus simulation, strict Verilog-2001 parsing, Verilator lint, full Yosys
  synthesis, independent native-reference specialization equivalence, genuine
  mutation counterexamples and applicable inherited gates. Use WIDTH in
  `{1, 5, 8, 32}` and COUNT in `{1, 2, 3, 5, 8, 9, 16, 17}` as the common
  minimum scalar matrix, extended for independent field/nested dimensions and
  each supported operation's legal domain. Prove positive finite symbolic
  geometry over the declared domain; a finite formal matrix is not universal
  formal quantification over all parameter values.
- Generate one parameterized candidate per declared static topology/profile,
  including a COUNT=1 default that permits larger overrides. Independently
  elaborate the ordinary native reference for each specialization; do not
  regenerate the candidate per override or build the reference from candidate
  replay. Different interfaces may use independently specified wiring-only
  wrappers. Assert exact native result widths and leaf types as well as values;
  do not hide a shape mismatch by truncating or widening both sides of a miter.
  Drive fields, elements and distinct Vecs independently so swapped
  channels or cross-Vec wiring cannot be hidden by equal test inputs.
- Retain graph-mutation, foreign-write, partial-driver, unknown-call/effect,
  ambiguous-width and illegal-domain rejection controls. Host-state mutation,
  unbounded elaboration and uncertified effects remain rejected. Parser/tool
  errors, timeouts, UNKNOWN and skipped tests are not passing proof or mutation
  evidence. Planning these tasks does not mark any implementation complete.

- [x] **Increment 59c — Field-preserving parameterized Vec-of-Bundle interfaces**

  **Dependencies:** Increment 59b implemented and merged. Parallel successor;
  no dependency on 59d through 59h or unfinished Increment 60 children.

  **Implementation record:** [Named field vectors](increment-59c-named-field-vectors.md)
  records the opt-in interface, recursive packing and naming contract, and
  completed implementation and local qualification at production checkpoint
  `3166ddbde97047b45f5ca48abf9ac6e1ec75aabe`: 216 main and 16 separate register
  layout specializations passed, with 11 main and two register SAT/VCD mutation
  counterexamples. This completion checkbox is on the PR branch. All required
  final-head CI must pass before [PR 163](https://github.com/pysolvesemi/MorphHDL/pull/163)
  merges and brings the record onto the integration branch.

  Add a generic field-preserving publication profile for the same source
  `val pixels = in Vec(Rgb(width), count)`. Derive one packed vector per scalar
  element-field path from retained typed shape metadata. For the RGB example,
  the required new-profile interface is equivalent to:

  ```verilog
  input wire [(WIDTH * COUNT)-1:0] pixels_red;
  input wire [(WIDTH * COUNT)-1:0] pixels_green;
  input wire [(WIDTH * COUNT)-1:0] pixels_blue;
  ```

  Do not require rewriting `Vec[Bundle]` as `Bundle[Vec]`, calling `asBits`,
  supplying a layout map, or teaching the compiler about RGB. A scalar
  `Vec[UInt]` retains one WIDTH*COUNT carrier. An ordinary output `Rgb` remains
  separate WIDTH-bit `result_red`, `result_green` and `result_blue` leaves.

  Recurse through nested Bundles and Vecs, retaining distinct leaf widths/types
  and every independent Vec dimension. A nested path such as `color.red`
  becomes a deterministic field path such as `pixels_color_red`; array
  dimensions contribute to that leaf's packed width, not to a parameter-varying
  list of port names. Define exact dimension ordering, element slices, legal
  identifier escaping/collision handling and leaf directions. Preserve readable
  field grouping on ports, internal stage/storage signals and parent/child
  connections where these are structural Vec values.

  Cover static/dynamic reads and writes, whole-Vec assignment, cloning/HardType,
  registers, explicit packed conversions, Stream/Flow payloads, nested shapes,
  module deduplication and named hierarchy binding. Field grouping must not
  imply independent per-field arithmetic: callbacks may couple multiple leaves.
  Keep explicit bit-packing semantics unchanged through wiring conversions.
  Document the interface change and retain an explicit legacy packed-interface
  compatibility path; qualify both layouts without altering ordinary concrete
  SpinalVerilog. A parameter override cannot add numbered module ports.

  Prove equal payload behavior against independently flattened native leaves,
  including unequal field widths, signed leaves, count-one and odd/nested
  shapes. Mutation controls must detect field swaps, reversed element order,
  wrong offsets and incorrect parent/child or cross-Vec binding. This track
  qualifies interfaces and access without waiting for composite reduction.

- [x] **Increment 59d — Generic symbolic result-width provenance and widening reductions**

  **Dependencies:** Increment 59b implemented and merged. Parallel successor;
  no dependency on 59c or 59e through 59h.

  **Complete and merged:** [PR #164](https://github.com/pysolvesemi/MorphHDL/pull/164),
  merge `99b6017d7`. Both source and merge Scala lanes passed 145 tests / 13
  suites, all 64 widening specializations and four actual RTL mutation
  counterexamples. All 96 source and 109 post-merge qualification jobs passed;
  both full regression lanes ran 1,738 tests with zero skips. See the
  [59d qualification record](increment-59d-symbolic-widths.md).

  Replace the equal-width-only reduction certificate with generic scalar
  input/intermediate/result width functions derived from native typed IR.
  Carry independent WIDTH/COUNT roots through arithmetic, resize, mux/min/max,
  native cloning, HardType and register construction without freezing them to
  a default Int width. Qualify natural symbolic-width min/max and RegNext paths,
  not only inferred-construction workarounds. Every native propagation edit
  remains mechanical, audited and concrete-compatible.

  Support native `values.reduceBalancedTree(_ +^ _)` and
  `values.reduceBalancedTree(_ * _)` through the same transfer/replay mechanism,
  including UInt and SInt and required intermediate nodes. Addition and
  multiplication are fixtures, not separate production reduction algorithms.
  Retain each node/lane's actual shape: different groups at one level can have
  different widths. In a five-element full-width product, native group widths
  are W,W,W,W,W -> 2W,2W,W -> 4W,W -> 5W; uniform stage padding must not change
  this native result contract.

  Preserve narrower odd tails and their bridge input widths until native
  semantics require resize, zero extension or sign extension. Derive terminal
  widths symbolically for every legal COUNT, including COUNT=1 and alternate
  defaults. Widening-sum W+ceil(log2(COUNT)) is an acceptance example where the
  native helper derives it, not a universal width rule for arbitrary callbacks.
  Keep strict Verilog-2001 constant-function support for required logarithms.

  Compare every specialization with independently elaborated native results,
  including signed extremes, carries, truncation, unequal intermediate widths
  and transparent or already-certified scalar bridges. Mutation controls must
  detect dropped carry/sign bits, default-frozen widths and incorrect tail
  extension. Widening composite/expanded-bridge combinations are joined in 59i.

- [x] **Increment 59e — Recursive composite-Data balanced reduction**

  **Dependencies:** Increment 59b implemented and merged. Parallel successor;
  no dependency on 59c, 59d or 59f through 59h.

  Generalize scalar-only capture, ownership validation, graph replay and result
  reconstruction to recursive compatible Data shapes: Bundles, nested Bundles,
  Vecs inside Bundles, Bundles inside Vecs and nested Vec combinations. Preserve
  leaf paths, direction legality, UInt/SInt/Bits/Bool interpretation, independent
  leaf-width/count roots and complete assignment ownership at each stage.
  Include the minimal certified composite construction/mux/assignment callback
  admission needed for standalone publication; no blanket arbitrary-call escape
  is allowed.

  Qualify channel-wise RGB min/max, a whole-record selection using a native
  comparison key with deterministic tie behavior, complex-valued modular
  arithmetic and nested tagged records. A callback selecting one whole pixel
  must keep its tag/coordinates with that pixel; do not replace it with
  independently selected channel values. Include cross-field dependencies and
  fields with different widths and signedness. These are examples of general
  recursive shape handling, never named Bundle implementations.

  Establish stage-invariant composite shapes first using 59b's packed boundary
  and certified scalar leaves; use the same shape contract that 59c can expose
  as named field vectors. This increment does not require 59c's interface layout
  or 59d's changing stage widths to land. Preserve native singleton/odd-tail
  behavior and qualified identity/register bridges. Reject missing fields,
  incompatible shapes, partial/foreign drivers and unsupported cyclic shapes.
  Prove complete records and detect leaf swaps, corrupted tags and cross-field
  wiring. Combined named-field, widening and expanded-bridge behavior is 59i.

- [x] **Increment 59f — Generic safe callback graphs and explicit captured inputs**

  **Completed:** [PR #166](https://github.com/pysolvesemi/MorphHDL/pull/166),
  merged at `c85659a20`. Both Scala lanes passed all 64 callback and 32 inherited
  publication specializations, mutation controls and the complete regression
  inventory. All applicable post-merge workflows passed. See the
  [59f qualification record](increment-59f-callback-graphs.md).

  **Dependencies:** Increment 59b implemented and merged. Parallel successor;
  no dependency on 59c through 59e, 59g or 59h.

  Expand the narrow single-operation/capture-free callback profile through
  generic typed expression and statement transfer rules. Support certifiable
  multi-node compositions, typed local temporaries, comparisons, mux/when
  alternatives, bit/part selection, concatenation, resize and native constants.
  Use fixed-result-width unsigned saturation with a widened intermediate as
  one fixture, built from native nodes and a typed symbolic-width constant;
  never compute a symbolic constant via an ordinary Scala Int/BigInt witness.
  Include user-authored pure helpers and source-equivalent callback forms when
  their complete call/effect graph can be inspected and certified.

  Admit immutable captured typed configuration and read-only hardware operands
  only through an explicit exact-identity capture schema. Validate each capture's
  type, width, owner, lifetime and per-stage binding, and keep runtime inputs
  runtime. Distinguish those reads from forbidden writes to external signals or
  mutable host state. For example, a captured input bias is not inherently
  non-associative/unsafe, but its binding and number of native operator uses must
  be preserved and formally proven. Unknown host fields/calls and mutation must
  fail before effects execute; representative samples do not prove purity.

  Preserve native pairing for order-sensitive callbacks such as subtraction;
  only admit them after exact-topology specialization proof, without introducing
  reassociation. No universal acceptance of arbitrary Scala code is promised.
  Stage-varying or parameter-dependent code needs exact typed semantics, not a
  finite-carrier uniformity guess. This track qualifies scalar fixed-result
  graphs using existing or locally certified native intermediate transfers;
  changing stage widths and composite combinations are joined in 59i.

  Prove accepted helper/capture forms equivalent to separate native references,
  test independent captured inputs and alternate parameter defaults, and retain
  rejection/mutation controls for external writes, changed capture bindings,
  stateful host callbacks, dropped operations and reordered operands.

- [x] **Increment 59g — Register-bridge semantics and clock/reset qualification closure**

  Implemented and qualified on both Scala 2.12.18 and 2.13.12 at
  `a327dd01b7e3003370827580083e56ce7992d2a7`: 252 dedicated reduction tests,
  222 bridge specializations across 24 clock/reset/enable profiles, four real
  mutation counterexamples, and 1,895 full inherited regression tests per lane
  with no failures, errors or skips. See the
  [bridge semantics, actual Scala/Verilog example and qualification record](increment-59g-register-bridges.md).
  Mixed sibling combinations remain the separate 59i integration gates.

  **Dependencies:** Increment 59b implemented and merged. Parallel successor;
  no dependency on 59c through 59f or 59h.

  Extend and qualify the native level-bridge graph beyond the existing
  unconditional scalar-chain, zero/no-initializer profile. Cover identity,
  transparent aliases, ordinary native register helpers, level-selected register
  depths, typed width-correct nonzero initializers and certifiable local
  register enables. Retain native clock-edge, clock-enable, synchronous and
  asynchronous reset polarity and reset/enable precedence; do not author a
  replacement clocked process. Use explicit legal initialization/validity
  contracts for uninitialized state rather than silently assuming zero.

  Start independently with fixed-width or already-certified scalar shapes.
  Reconstruct each operator result and odd tail before applying its native
  bridge, preserve zero added latency and no callback execution for COUNT=1,
  and establish exact latency/stall behavior at every active level. Freeze and
  prove a finite clock/reset configuration matrix; differing or unsupported
  clock domains, unmodelled CDC and unsupported side effects remain rejected.

  Require independent native simulation and sequential formal proof with reset
  entry, enable stalls and in-flight reset cases for every admitted profile.
  Mutations must detect an added/removed stage, wrong initial value and altered
  reset/enable precedence. Sibling widening, composite, symbolic clone and
  nested-owner combinations are additional 59i gates, not start dependencies.

- [x] **Increment 59h — Balanced reduction inside nested typed structural owners**

  Implemented through PR #170 and qualification follow-up PR #172, merged as
  `125eec24465bb8576e517865089c71bf3cb0448a`. Final source
  `04fa8344ceb50607baf2150831dfcbece853eb65` passed all 39 applicable workflows:
  405 dedicated tests and 1,895 full inherited tests per Scala lane, with no
  failures, errors or skips. Both lanes passed all 516 native-reference
  specializations, 408 combinational proofs, 108 reset-entry/induction cases,
  and six genuine RTL mutation counterexamples. The 521 original generated
  RTL files match across independent A/B generation and Scala versions. See the
  [scope, actual Scala/Verilog example and source-bound qualification record](increment-59h-nested-structural-owners.md).
  These finite-matrix results do not claim universal parameter proof or the
  combined feature support reserved for 59i.

  **Dependencies:** Increment 59b implemented and merged. Parallel successor;
  no dependency on 59c through 59g.

  Remove the current component-scope-only publication limit where exact typed
  ownership can be established. Support reductions inside parameter-controlled
  generate-if/case and finite generate-for regions, including nested regions
  and ordinary child components with canonical parameter binding. Begin with
  59b's supported scalar operations and bridges so this track is independently
  implementable; mixed composite/widening/layout cases are tested in 59i.

  Retain exact outer-owner, branch-domain, Vec, callback-template, index and
  result-anchor identities through capture, retries, normalization and
  publication. Preserve lexical ownership and driver scope, branch narrowing,
  bound index reads, default COUNT=1 alternate branches and correct removal of
  probe hardware. Do not rediscover owners from component or emitted names.
  Reject sibling-scope capture leaks, escaping results, conflicting drivers,
  invalid finite bounds and callbacks creating uncertified child hardware.

  Prove independently elaborated native hierarchy/region specializations,
  deterministic generated names and one canonical definition per logical
  component/profile. Mutation controls must detect wrong owner or branch
  binding, stale indices and cross-instance result wiring.

- [ ] **Increment 59i — Combined Vec/reduction compatibility, proof and publication closure**

  **Dependencies:** Increments 59c, 59d, 59e, 59f, 59g and 59h implemented and
  merged. This is the integration join, not a prerequisite for their own gates.

  Combine named field vectors, recursive composite shapes, generic widening
  rules, certified callback captures, registered bridges and nested typed
  owners using one shared typed graph/shape representation. Retire superseded
  scalar-only/packed-only restrictions only where their replacement has exact
  coverage. Do not remove safety diagnostics for unproven cases or introduce
  component-specific compatibility paths.

  Qualify WIDTH/COUNT and independent field/inner-Vec parameter overrides across
  unsigned/signed widening sums and products, saturation, nested record
  selection, cross-field arithmetic, registered composites and reductions in
  generated child hierarchies. Cover every pair of the new mechanisms and
  representative end-to-end combinations. Compare legacy packed and new
  field-preserving interfaces using wiring-only adapters and independent native
  reference elaborations; arithmetic or callback code must not be duplicated
  inside the adapters. Include existing signedness modes, hierarchy binding,
  native library consumers and all inherited 59b proof/mutation gates.

  Freeze exact default/compatibility behavior for the field-preserving profile,
  document migration of existing generated-port consumers, and retain ordinary
  concrete SpinalVerilog behavior. Update runnable generic examples,
  architecture/support-matrix documentation, reviewed native-change manifests
  and deterministic golden contracts. Include field/index/capture misbinding,
  carry/sign loss, wrong odd-tail handling and latency/reset mutations. Record
  source-bound results on both Scala lanes and require all applicable
  final-head CI/formal/tool gates before marking this join complete. If
  Increment 61 has merged, those final gates must also exercise
  `oneFilePerComponent = true`; publication mode must not change callback,
  field, width, ownership, latency or reset semantics. The user's CI skip for
  this roadmap-only planning commit does not waive implementation
  or merge gates for any of these increments.

### Native signed-Verilog track (Increment 60)

- [x] **Increment 60 — Native signed `SInt` Verilog**

  **Dependencies:** Increment 59 implemented and merged.

  Follow [the Increment 60 child roadmap](increment-60-sint-signed-verilog-roadmap.md)
  and [signedness semantic contract](increment-60-signedness-contract.md).
  The serial chain 60a through 60g is complete. The
  [60g final qualification record](increment-60g-default-rollout.md#final-implementation-qualification)
  identifies the exact source, actual Scala/Verilog example and terminal
  dual-Scala CI evidence: 1,872 tests across 182 suites per lane, independent
  signedness proofs, all integration hardware matrices, deterministic output,
  native-source audits, baseline and Mill. The final completion transition is
  documentation-only; its applicable checks and protected merge are tracked in
  PR #167. Ordinary SpinalVerilog and VHDL remain unchanged by default.

### Per-component parameterized publication track (Increment 61)

- [x] **Increment 61 — Parameterized `oneFilePerComponent` publication**

  **Implementation qualified:** commit `1ec23d4c222e90b3dddfe2cc29312fc18436ad75`
  passed all 45 applicable workflow runs, including dual-Scala publication,
  compatibility, all 2,032 regression tests per Scala version, complete
  pass-workspace proofs and WA-11 normalization.
  Actual Scala, generated RTL, source lists and exact run evidence are recorded
  in [the increment document](increment-61-one-file-per-component.md).
  The continuation preserves target `7f355a859e7e88ca343e1ff82f261fb47b3311d0`'s
  independent parameter-domain and native legality changes, extends split-output
  interaction coverage, and updates the sealed integration anchors. The combined
  catalog now requires 2,067 tests in 202 suites per Scala version. All
  applicable checks must pass on that final published commit before
  [PR #179](https://github.com/pysolvesemi/MorphHDL/pull/179) is merged.

  **Dependencies:** Increment 60 implemented and merged. This publication
  track is independent of Increment 59i and may be implemented and merged in
  parallel. If Increment 61 merges first, 59i's final integration qualification
  must retain both consolidated and per-component publication modes.

  Remove the current MorphHDL parameterized-Verilog restriction that rejects
  `oneFilePerComponent = true`. Treat this as publication-path compatibility,
  not as a second parameter model or RTL implementation. Reuse the canonical
  typed module graph and exact logical-component identity; do not generate a
  consolidated file and then recover component boundaries by reparsing or
  emitted-text heuristics.

  When enabled, emit one deterministic Verilog source file per generated logical
  component definition while retaining that component's complete parameterized
  module header and owned declarations. Parameter declarations, module-local
  localparams/enums, helper functions, memories, signed declarations, generate
  regions and other module-owned artifacts must stay with the owning definition.
  Parent/child instances in separate files must preserve exact named parameter
  bindings and one canonical definition per logical component even when several
  instances use different parameter actuals. Recursive generated self-instances
  must continue to target the same canonical module. `BlackBox`/external-module
  definitions must not be regenerated or copied merely because publication is
  split across files.

  Define deterministic file naming, collision diagnostics, source ordering and
  the returned/generated-source manifest. A split output must not accidentally
  depend on declarations stranded in another component file; any intentionally
  shared compilation-unit artifact must be explicit, deterministic and included
  in the reported source set. Repeated generation into an existing target
  directory must define safe stale-file handling without deleting unrelated
  user files. Do not silently fall back to consolidated output when the option
  is requested.

  Preserve the existing consolidated parameterized output when
  `oneFilePerComponent = false` and preserve ordinary concrete `SpinalVerilog`
  behavior. Qualify nested hierarchy, repeated canonical children with distinct
  parameter actuals, Mem/Vec and Stream/StreamFifo/StreamFifoCC users, signed
  `SInt`, parameter-controlled generate regions, recursive modules,
  every currently merged field-preserving/reduction surface and typed BlackBox
  generic binding. Increment 59i must add its combined feature cases to both
  publication modes before 59i itself is completed. Compile/lint/synthesize the
  complete emitted file set with the supported tool
  matrix and prove representative split-file specializations equivalent to the
  existing consolidated parameterized publication. Require deterministic
  dual-Scala output, live negative controls for missing/duplicate/wrong-file
  module publication and every inherited compatibility/audit gate before this
  checkbox can be marked complete.

### WA-08 inherited workflow compatibility track (Increment 62)

- [x] **Increment 62 — WA-08 inherited source-audit and workflow closure**

  **Status:** `COMPLETED`. The exact overlay, inherited mutation controls and
  profile compatibility are implemented and qualified with WA-08 in
  [PR #178](https://github.com/pysolvesemi/MorphHDL/pull/178). The complete
  reviewed inventory is recorded in
  `morphhdl/contracts/increment-62-wa08-source-overlay.json`.

  **Dependencies:** Increment 60 and WA-07b implemented and merged. This is the
  qualification-repair companion for the open WA-08 production handoff. It is
  independent of Increment 59i and Increment 61 and must be completed before
  WA-08 merges.

  Fix the inherited workflow failures caused when the intentional WA-08
  canonical-IR and pass-adapter changes are presented to older closed
  source-scope checkers. The observed Increment 60d failure is a source-review
  rejection of the new `CanonicalIrPassAdapter.scala` bytes, not evidence that
  pure-`SInt` cast behavior or signed-Verilog semantics failed.

  Add one exact, immutable WA-08 source-overlay compatibility contract covering
  every intended production, test, boundary, signature and canonical-IR handoff
  file. Bind each path to reviewed SHA-256 bytes, file mode, HEAD/index/worktree
  identity and immutable baseline/final anchors. Historical checkers may project
  only that fully verified WA-08 delta out of their own historical inventory;
  the outer WA-08 gate must still validate the real current bytes. Do not add
  branch-name, PR-number, component-name or emitted-text exceptions.

  Update the inherited WA-07b, 59x, 60d, 60f and 60g reviewers only through
  exact reversible spans or one common generic overlay helper. Keep their
  original source inventories and semantic checks intact. Reject partial
  rollout, missing or extra files, changed hashes, symlinks, executable-bit
  changes, staged or untracked content, mismatched profile/facet claims and any
  unknown production delta. Add self-tests for each attack and prove the overlay
  cannot hide a genuine historical-source mutation.

  Preserve `SimpleWireAssignmentsV1` only for explicit legacy fixtures. The
  WA-08 production handoff and pass adapter must require
  `PureWireExpressionsV1` and the `PureExpressions` completeness facet. Do not
  weaken or skip workflows, convert failures to allowed failures, or modify
  SInt or parameterized RTL merely to satisfy a source audit.

  Completion requires the previously failing inherited workflows, the WA-08
  pass workspace, baseline and Mill, both Scala lanes and all applicable formal,
  determinism, strict-Verilog and source-audit gates to pass on one exact final
  head. Record the original failure classification and complete reviewed overlay
  inventory. This increment changes qualification/source-audit compatibility
  only and does not change generated Verilog.

### WA-09 named-expression optimization track (Increment 63)

- [x] **Increment 63 — Named expression-wire elimination and provenance-first alias preference**

  **Status:** `COMPLETED`. The implementation candidate
  `d48687d0cca4d5f8437b877e480ca4fe09ad20c4` passed every applicable workflow
  with both Scala production lanes, cross-Scala byte identity, the exact
  1,982-test/194-suite full inventory per lane, and the two-run 16-shard proof
  over all 512 bindings for each of 11 pass identities (11,264 equivalence and
  11,264 reachability proofs). The final completion-only commit remains subject
  to the identical complete gate set before merge.

  **Dependencies:** Increment 62 and WA-08 implemented and merged. This
  successor is independent of unfinished Increments 59i and 61.

  Extend the one-flag production wire pipeline from its frozen historical five
  stages to six by adding bounded named continuous wire-expression inlining
  after the unnamed expression stage and before constant/ternary
  simplification. Reuse the canonical expression safety and rewrite engine,
  capture the exact native RHS, and fail closed when the expression, naming,
  type, scope, receiver or metadata inventory is incomplete. Preserve explicit
  opt-out and exact historical three-, four- and five-stage artifacts.

  Native validation must capture the actual source and receiver RHS with
  `NativeWireExpressionCodec`, replace only whole-RHS continuous receivers and
  represent `TypeBool` at width one; it must not fabricate a representative
  XOR or another expression. Reverse source removal requires the canonical
  preference decision over both actual direct edges and independent native
  safety. Expression-driven sources retain pass ownership: true unnamed
  provenance dispatches to `UnnamedWireExpressionNativePhase`, while
  explicit/reflected/generated provenance dispatches to
  `NamedWireExpressionNativePhase`.

  Refine direct-alias planning with provenance before length. A non-removable
  port, register, hierarchy/preservation identity or otherwise ineligible
  declaration always survives. Among independently removable declarations,
  `Explicit`/`Reflected` names beat `Unnamed`/`Generated`; only meaningful names
  are compared by length, then by deterministic name and symbol identity.
  Never infer provenance from `_zz` or any emitted spelling, rename a survivor,
  transfer a removed name, or reverse an assignment without the full existing
  removal proof.

  Close the separately reproduced emitter boundary where
  `fillExpressionToWrap` creates anonymous expression carriers only after all
  pre-emission passes have finished. Permit emission-time wrapper elision solely
  for an exact homogeneous fixed-width unsigned `UInt` addition tree feeding a
  whole-object fixed-width unsigned receiver. Its leaves are same-width fixed
  `UInt` values or exact unsigned widening resizes from narrower fixed `UInt`
  values, and every synthetic expression node must be unannotated and uniquely
  used. Fixed native 16-to-18 `ResizeUInt` wrappers inline completely. Tagged
  parameterized resize carriers remain declared and may serve as proven fixed
  18-bit leaves while only their surrounding `Add` wrappers inline. The
  positive witness uses four 16-bit inputs in an 18-bit domain (`0..262140`),
  and a same-width 18-bit companion proves unchanged modular-overflow tree
  semantics. Retain wrappers for mixed widths, signed values, narrowing,
  selections, direct symbolic-width expression nodes, other operators or
  incomplete facts. This is a graph/type-proven emitter policy, not a seventh
  pass or emitted-text cleanup. The exact source is
  `morphhdl/src/test/scala/nativeapplication/NestedUnsignedExtendedSumProductionArtifactWriter.scala`;
  run `sbt "morph/Test/runMain morphhdl.examples.NestedUnsignedExtendedSumProductionArtifactWriter target/wa09-nested-sum"`.

  The public ordinary-alias fixture must reduce the chain through `bitSource`
  and `bitCloneAlias` to `assign clonedResult = (a ^ b);`, while retaining the
  output port. Add both alias orientations, generated-short and explicitly
  named `_zz` controls, equal-length ties, fanout, symbolic-width/signedness,
  four-state and all safety exclusions, plus the unsigned-add positive and
  wrapper-retention controls above. Require dual-Scala deterministic and
  idempotent results, strict Verilog-2001, lint, synthesis, simulation,
  functional mutation, formal equivalence and every inherited final-head gate
  before marking this increment complete. The bounded contract is recorded in
  [`wa09-named-expression-and-name-preference.md`](../../morphhdl-passes/wa09-named-expression-and-name-preference.md).

### Parameter-expression and structural-legality follow-ups (Increments 64 and 65)

**Planning status, 20 September 2026:** the following entries are documentation
only. Both remain unchecked; their examples describe intended behavior, not
executed Scala or actual generated Verilog. The current boundaries are recorded
in [Independent HDL parameters](independent-parameter-domains.md). The planning
baseline is `4b8a86e25f5a1a3f0cb4c37dc537a8dd8aa7b097`.

**Dependencies and ordering (revised 1 October 2026):** implement both tracks from
the existing merged typed-expression, hierarchy/publication and structural-legality
facilities on `parameterized-verilog`, retaining the merged PR #188/#189/#190
behavior. Neither track requires unmerged 59i functionality to begin or to obtain
local feature qualification. Work through 64 and then 65 on the development
branch; 65's standalone scope does not require localparam factoring. Integrate
59i after its merge and qualify combined scope/ownership and Vec/reduction
interactions before final integration of this batch. This replaces the earlier
planning dependency on merged 59i without waiving those interaction checks.

- [ ] **Increment 64 — Derived `localparam` support**

  **Implementation complete; focused local validation passed.** Final
  qualification and merged closure remain pending; see the checkpoint below.

  Automatically retain and publish named, parameter-dependent typed calculations
  as non-overridable module-local constants through the normal MorphVerilog
  flow. This is distinct from the already-supported enum localparams and direct
  symbolic expressions. Keep ordinary Scala component source authoritative; do
  not require raw Verilog, a second parameter declaration API or application
  rewrites merely to give a derived expression a reusable HDL name.

  **Acceptance example (planned):**

  ```scala
  import spinal.core._

  class RecordLink(dataBits: ElabInt, generationBits: ElabInt)
      extends Component {
    val totalBits: ElabInt = dataBits + generationBits
    val dataIn  = in Bits(totalBits bits)
    val dataOut = out Bits(totalBits bits)
    dataOut := dataIn
  }
  ```

  With independently declared `DATA_BITS` and `GENERATION_BITS`, the intended
  declaration excerpt is below. `TOTAL_BITS` illustrates the source-derived
  naming policy; this is not a complete module or a generated artifact.

  ```verilog
  localparam integer TOTAL_BITS = DATA_BITS + GENERATION_BITS;
  input  wire [TOTAL_BITS-1:0] dataIn;
  output wire [TOTAL_BITS-1:0] dataOut;
  assign dataOut = dataIn;
  ```

  - [ ] Retain the derived expression, exact declaration roots, type and lexical
    owner before normalization. Obtain naming hints from genuine typed binding
    metadata, not reparsed source text, equal witnesses or emitted identifiers.
    Define deterministic source-name conversion, collision handling, anonymous
    expression fallback and repeated-expression reuse. Names never establish
    parameter identity or authorize merging distinct declarations.
  - [ ] Emit a dependency-ordered localparam graph with no cycles, unresolved
    names or scope escapes. Cover chained calculations such as `HEADER_BITS`,
    `RECORD_BITS = DATA_BITS + HEADER_BITS` and
    `STORAGE_BITS = RECORD_BITS * DEPTH`. Derived values must not become public
    overridable parameters, consume positional parameter slots, or freeze to
    their defaults. Preserve checked arithmetic, integer sizing, signedness,
    Boolean normalization and existing legality obligations exactly.
  - [ ] Qualify complete strict Verilog-2001 modules, including port-width uses,
    internal declarations, constants, supported helper functions and generate
    scopes. Choose legal declaration/header ordering without requiring a
    SystemVerilog-only parameter-list extension. Keep branch-local calculations
    in a valid owner; never hoist them beyond their domain or expose a child's
    private localparam as a parent parameter. Preserve canonical child modules,
    named actuals such as `.WIDTH(TOTAL_BITS)`, external generic bindings and
    both consolidated and `oneFilePerComponent = true` source ownership.
  - [ ] Exercise independent same-file overrides, alternate defaults, repeated
    instances and equal-default/different-root negatives. For the RecordLink
    fixture, use `DATA_BITS` default/range `32 / 1..2048` and
    `GENERATION_BITS` `8 / 2..64`; verify at least `(32,8)`, `(64,8)`, `(1,2)`,
    `(2048,64)` and unequal swapped-value bindings. The first two widths must
    be 40 and 72, not a default-frozen 40. Cover large compact parameter domains
    without imposing new Cartesian enumeration merely to name an expression.
  - [ ] Prove factored output equivalent to independent native concrete
    references and the direct-expression behavior over the declared test
    matrix. Detect actual mutations that freeze a default, change an operator,
    swap a root, lose sign/carry information, break declaration order or bind a
    derived value to the wrong component. Keep unsupported expressions on the
    documented direct-expression path or reject them precisely; do not weaken
    width/domain authority or silently skip the required factoring fixtures.

  Completion requires deterministic repeated and cross-Scala output on Scala
  2.12.18 and 2.13.12, ordinary concrete SpinalVerilog parity, native library and
  combined Vec/reduction compatibility, strict parsing, lint, synthesis,
  simulation, independent specialization equivalence, mutation controls and
  all applicable inherited/source-audit/final-head gates. Record any native
  changes in the approved manifest. Publish runnable Scala and actual generated
  Verilog with source-bound evidence before checking this item. No generated-
  Verilog text rewriting or replacement library algorithm is permitted.

  **Local implementation checkpoint, 2 October 2026:** Increment 64 is
  implemented on `work/remaining-parameterized-increments` at source
  `0c2f07b4a49cacc918923663dbd0859f27b57c7d`. Final focused validation passed
  59 tests on each Scala lane; the Scala 2.13 compatibility run passed all
  311 tests, including widening byte determinism. Native-source and retirement
  audits passed. [Implementation boundaries and receipts](increment-64-derived-localparams.md)
  and [actual generated RecordLink RTL](evidence/increment64/RecordLink.v) are
  retained. Remote qualification and merge remain deferred; this checkbox is
  intentionally open. No 59i source or old wire-roadmap item was implemented.

- [ ] **Increment 65 — Targeted structural-parameter extensions**

  **Implementation complete; focused local validation passed.**

  Local implementation and qualification details are tracked in
  [the Increment 65 handoff](increment-65-scoped-legality.md). Remote gates and
  merge remain deferred under the batch plan; this checkbox remains open.

  Extend the existing typed structural-capture and native legality machinery,
  beginning with parameter-dependent `require` inside an optional hardware
  branch. This is not a proposal to add generate-if from scratch or to accept
  arbitrary Scala effects. At the planning baseline, mixed branch-scoped
  obligations reject with
  `SPINAL-ELAB-REQUIRE-STRUCTURAL-SCOPE-UNSUPPORTED`; a requirement already proved
  true does not hit that particular rejection. Reproduce the exact source case
  before implementation and retain the prior rejection as historical evidence.

  **Acceptance example (planned, using the normal compiler plugins):**

  ```scala
  import spinal.core._

  class OptionalPipeline(width: ElabInt, usePipeline: ElabInt)
      extends Component {
    val dataIn  = in Bits(width bits)
    val dataOut = out Bits(width bits)
    if (usePipeline == 1) {
      require(width >= 8, "Pipeline mode requires WIDTH >= 8")
      dataOut := RegNext(dataIn)
    } else {
      dataOut := dataIn
    }
  }
  ```

  Declare `WIDTH` with default 16 and range `1..64`, and `USE_PIPELINE` with
  default 0 and range `0..1`, as independent HDL parameters. The width rule is
  this fixture's contract, not an inherent minimum width of a register. The
  retained obligation must mean `(USE_PIPELINE == 1) implies (WIDTH >= 8)`.
  Intended diagnostic excerpt inside the native pipeline generate branch:

  ```verilog
  `ifndef SYNTHESIS
    if (WIDTH < 8) begin : g_invalid_width
      initial $fatal(1, "%s", "Pipeline mode requires WIDTH >= 8");
    end
  `endif
  ```

  - [ ] Add exact owner-scoped obligation records carrying the complete branch
    activation predicate, typed requirement, original message/source location,
    parameter roots and formal/actual bindings. Participate in capture rollback,
    retries and cleanup so probe elaboration cannot leak or duplicate an
    obligation. Do not globalize a branch requirement or discard it because the
    default selects the other branch. Preserve root identity through projection
    and publication instead of reconstructing it from names or default values.
  - [ ] Preserve immediate evaluation of ordinary concrete requirements and
    existing unconditionally false top-level rejection. Under symbolic owners,
    classify the complete guarded obligation, not the branch predicate alone:
    an inactive branch must never report failure, and a condition false only
    when an optional branch is active must remain activation-guarded. A true
    requirement needs no diagnostic. Specify and test invalid-default behavior
    consistently with existing deferred legality where hardware construction
    is safe; never silently change the requested parameter defaults.
  - [ ] Qualify `if`/`else`, ordered `else if`, nested typed alternatives and
    already-supported Boolean match/finite generate-for owners. Preserve the
    complete enclosing path, loop-index binding and child-instance activation.
    Cover sibling branches and repeated canonical children with different
    actuals; obligations must stay with the correct logical owner in both
    publication modes. Preserve registers, clock/reset/enable semantics,
    driver/latch checks and zero-cycle bypass versus one-cycle pipeline latency.
  - [ ] Extend only the bounded multi-root structural/child-binding cases with
    explicit typed evidence: include a relational branch such as
    `if (dataBits >= lanes)` with independent parameters and a child receiving
    both actuals. Prove the activated domain, finite topology and exact bindings
    through the shared native machinery. Preserve the distinction between
    symbolic publication, exact/domain proof and structural authority. A
    deferred `require` is not an assumption permitting unsafe slicing, invalid
    geometry or unbounded Scala graph construction. Unsupported correlations,
    arbitrary formal remapping, host mutation and uncertified effects must
    still fail closed; increasing enumeration limits is not the feature.
  - [ ] Generate one candidate per declared static topology/publication mode
    and override that same output at `USE_PIPELINE` in `{0,1}` and `WIDTH` in
    `{1,4,7,8,16,64}`. Repeat with an enabled default so both capture directions
    are exercised. Bypass must accept WIDTH=4 without a register or fatal;
    pipeline WIDTH=4 must terminate with the intended diagnostic; WIDTH=8 and
    16 must preserve the respective bypass/pipeline behavior. Also cover
    universally true width domains, concrete parameters, nested constraints,
    distinct instance inputs and repeated generation after a rejected capture.
  - [ ] Emit native simulation-only `$fatal`, not `$error`, under
    `ifndef SYNTHESIS`, retaining literal message escaping and one diagnostic
    per actual violated obligation. An equivalent fully guarded placement is
    acceptable only with scope/activation proof. Keep all real hardware outside
    the diagnostic guard. Require nonzero simulation termination and the exact
    expected message for invalid overrides; crashes and parser failures are
    not successful rejection. An invalid synthesis tuple remains invalid even
    though simulation diagnostics are excluded.
  - [ ] Prove valid specializations against independently elaborated native
    concrete references, with explicit initial-state/latency contracts. Add
    live mutations for a dropped/inverted activation guard, an incorrectly
    hoisted constraint, lost else priority, stale loop/child binding, duplicate
    capture and changed pipeline latency. Retain forged/stale root, scope-leak,
    unsafe-domain and unsupported-host-effect rejection controls. Replace old
    negative expectations only for the precisely newly qualified surface.

  Completion requires both Scala lanes and both publication modes, strict
  Verilog-2001 hardware checks with `SYNTHESIS`, separate supported diagnostic
  language-mode simulation/lint without `SYNTHESIS`, synthesis, independent
  behavioral/formal qualification, actual mutations, determinism and every
  applicable inherited/source-audit/final-head gate. Do not present bounded
  matrices as universal proofs. Update the supported/unsupported matrix and
  approved native-change manifests; retain ordinary concrete SpinalVerilog
  behavior. Publish runnable Scala and actual generated Verilog, including the
  OptionalPipeline case, before checking this item.

The documentation-only CI suppression for this planning update does not waive
implementation or merge gates. Neither entry authorizes weakening existing
proofs, changing application RTL, introducing a PROFILE workaround, or removing
safety diagnostics outside its explicitly qualified replacement surface.

### Integrated native parameter and RTL readability extensions (Increment 66)

- [ ] **Increment 66 — Native loops, symbolic values, child formals and named local constants**

  **All five imported tasks are implemented and passed focused local validation.**
  Their unchecked boxes below retain the outstanding qualification/closure gates.

  **Planning status, 1 October 2026:** the five unchecked tasks below are imported
  from the user's `~/prompt.txt` as one integrated compiler increment. Preserve
  every task's implementation and validation requirements. Internal sequencing
  does not create separate delivered increments; references below to separate
  increments mean separately validated internal steps if included in this scope.
  Optional extensions remain optional, and supported surfaces must be explicit.

  **Integration plan:** implement on `work/remaining-parameterized-increments`
  from merged `parameterized-verilog` baseline
  `db54d01e5b21c7664f7a0de3795f061d77a3d259`. This increment does not require
  unfinished Increment 59i, 64 or 65 as an implementation prerequisite. Keep
  `parameterized-verilog` untouched until 59i merges. Run focused local validation
  during implementation; defer remote CI until the planned branch work is ready.
  Incorporate the then-current integration target, qualify affected targeted
  workflows and full final-head CI, and verify combined interactions before merge.
  Implemented but unqualified work must remain unchecked.

  **Local checkpoint, 2 October 2026:** all five internal feature steps are
  implemented at `b6e83061d8c2b1ece94e76abadc956a2201a99dc`. Both Scala lanes pass
  329 runtime, 35 native and 18 plugin tests, plus the legacy AXI4 formal suite.
  See [supported APIs](increment-66-native-parameters.md) and
  [retained local evidence](evidence/increment66/README.md). Another 118 interaction
  tests per lane, binary compatibility and inherited source-review integration
  also pass. Remote qualification remains outstanding; this is not closure.
  See the [batch handoff](remaining-increments-handoff.md).

  **Shared implementation with Increment 64:** the explicit typed-local-constant
  API below and 64's automatic derived-binding retention should reuse declaration,
  dependency, ownership and emission machinery. Neither checkbox substitutes for
  the other's acceptance requirements. No application source, compiler pin or
  generated-Verilog cleanup script is changed by this compiler increment.

  Complete every mandatory task and its validation before checking this increment
  or its constituent tasks. The examples and historical observations below retain
  their stated evidence limits; proposed APIs are not claims of existing support.

- [ ] Retain conditional procedural loops

#### Future compiler task: retain conditional procedural loops
Extend MorphHDL generically so a bounded Scala loop containing conditional indexed assignments can emit a procedural Verilog for loop instead of repeated statements. Work in the compiler's own repository under its current instructions. Do not modify Display Controller application source or its compiler pin for this task. Preserve existing loop lowering and cleanup coverage; do not implement this by recognizing application names or reparsing generated Verilog.

##### Observed baseline
At compiler commit db54d01e5b21c7664f7a0de3795f061d77a3d259, an ordinary Scala `0 until 4` loop unrolls. The retained parameterized procedural-loop path in morphruntime/src/main/scala/spinal/core/ParameterizedProcess.scala requires a parameter-dependent count, exactly one direct assignment and an indexed packed target slice, and rejects nested control statements. Verify these facts against the current compiler before changing anything; do not downgrade newer support.

##### Simple Scala reproducer
Place this component in an existing MorphVerilog test fixture and emit it through the normal production generation path. Use the imports/configuration from morphhdl/src/test/scala/morphhdl/GenericProcessLoweringTests.scala; adapt frontend qualification only as required by the current public API.

```scala
import spinal.core._
import morphhdl.frontend._

class ConditionalLaneLoop extends Component {
  val previousData = in(morphhdl.frontend.Bits(120 bits))
  val previousMask = in(morphhdl.frontend.Bits(4 bits))
  val clear = in(Bool())
  val selected = in(morphhdl.frontend.UInt(3 bits))
  val pixel = in(morphhdl.frontend.Bits(30 bits))
  val assembled = out(morphhdl.frontend.Bits(120 bits))
  val mask = out(morphhdl.frontend.Bits(4 bits))

  assembled := previousData
  mask := previousMask
  when(clear) {
    assembled := 0
    mask := 0
  }
  for (lane <- 0 until 4) {
    when(selected === U(lane, 3 bits)) {
      assembled(lane * 30 + 29 downto lane * 30) := pixel
      mask(lane) := True
    }
  }
}
```

This example has not been independently compiled as a standalone fixture; its loop/body mirror the inspected production Scala. First establish a compiling reproduction and preserve the emitted unrolled baseline for comparison.

##### Desired equivalent Verilog shape (illustrative names)
```verilog
integer lane;
always @(*) begin
  assembled = previousData;
  mask = previousMask;
  if (clear) begin
    assembled = 120'b0;
    mask = 4'b0;
  end
  for (lane = 0; lane < 4; lane = lane + 1) begin
    if (selected == lane) begin
      assembled[lane * 30 +: 30] = pixel;
      mask[lane] = 1'b1;
    end
  end
end
```

Separate procedural loops for the two outputs are also acceptable if native process partitioning requires them. Do not require process merging merely for cosmetic output. This is a procedural for inside always, not a structural generate-for. Retaining a loop improves emitted readability; it does not imply reduced synthesized hardware.

##### Implementation scope
1. Inspect the existing frontend range capture, native process representation and procedural emitter. Reuse them. Start with this constant-bound example, then extend the same representation to supported parameterized bounds and lane widths. Keep ordinary Scala syntax where the frontend can capture it safely. If ordinary constant ranges cannot retain provenance with the current architecture, explain the limitation and use the smallest supported source-level capture mechanism; do not claim an unchanged ordinary loop is retained when only an alternative API works.
2. Support conditional bodies and multiple assignments to distinct packed targets, preserving native assignment order, default/override priority, source ownership and process semantics. Include bit selects and fixed-width indexed part-selects. Preserve width/signedness of index comparisons and expressions; never infer parameter families from default elaboration alone.
3. Preserve the order of clear and lane writes: when clear is asserted, the selected lane still receives pixel and its mask bit becomes one. For selected=4..7, neither lane write occurs. Preserve Verilog four-state conditional behavior for unknown indices and clear, including cases where previous data or pixel contains X/Z. Do not replace procedural if with a mux or dynamic write unless its semantics are proven equivalent.
4. Fail closed or retain the existing unrolled form for unsupported bounds, strides, dependencies or control flow. Treat cross-iteration dependencies, overlapping writes, signed indices, nested loops and sequential targets explicitly; support them only when justified by native semantics. Keep loop-variable names deterministic and collision-free. Do not use a combinational loop index as a hardware register.

##### Acceptance
- Add a permanent regression for the exact ordinary-Scala reproducer and any explicit-capture variant needed. Assert actual emitted procedural loops, both target updates, absence of accidental latches, and deterministic generation.
- Compare looped and unrolled output behavior using all selected values 0..7, clear low/high, randomized old data/mask/pixel, and four-state X/Z inputs. Check against an independent lane-update oracle as well as differential simulation.
- Add parameterized lane-count and pixel-width cases using the same emitted artifact across valid parameter overrides. Include one lane and non-power-of-two lane counts. Keep the constant four-lane case independent of any newly introduced public parameter.
- Qualify process partitioning, assignment priority and protected/named signal behavior. Add negative tests for unsupported constructs rather than silently changing their meaning.
- Run existing structural generate, combinational/sequential procedural loop, slice, parameterization and cleanup regressions, plus applicable repository-required lint, synthesis and formal/equivalence checks. Distinguish two-state proofs from four-state simulation; report any unexecuted checks explicitly.

Deliver the generic compiler change, minimal runnable example, generated before/after RTL and validation evidence. Keep this incremental; this request does not require developing an unrelated general-purpose optimizer.

- [ ] Simplify symbolic resize lowering

#### Improve MorphHDL symbolic resize lowering

Implement a focused, generic improvement in the MorphHDL compiler so symbolic resize expressions simplify when their width relationships are provable. Work in the compiler repository under its current instructions. Do not modify Display Controller application RTL, its compiler pin, or its Python cleanup script for this task. Inspect the current integration target first; do not downgrade newer compiler work.

##### Problem
A real application contains:

  val keepWide = io.s_keep.asUInt.resize(busBytes + 1)

The source has width W = (1 << BUS_LOG2_BYTES) * 8 / 8 and the destination has width W + 1. The supported BUS_LOG2_BYTES domain is 2 through 5. Generated resize expressions contain generic padding and min-width selection. After a separate Python cleanup removes explicit zero padding, this redundant slice remains:

  assign logic_keepWide = s_keep[((((1 << BUS_LOG2_BYTES) * 8 / 8 + 1 < (1 << BUS_LOG2_BYTES) * 8 / 8) ? ((1 << BUS_LOG2_BYTES) * 8 / 8 + 1) : ((1 << BUS_LOG2_BYTES) * 8 / 8)) - 1):0];

The above line is postprocessed output, not claimed to be the exact native compiler output. Reproduce and capture the actual native output before changing the compiler.

Conceptually, generic unsigned resize uses:
  padding = max(destinationWidth - sourceWidth, 0)
  copiedWidth = min(destinationWidth, sourceWidth)

For destinationWidth = W + 1, the valid domain proves padding = 1 and copiedWidth = W. A clear, conservative native result for this whole assignment is:

  assign keepWide = {1'b0, keep};

With an unsigned source and a correctly declared wider destination, this is also equivalent to:

  assign keepWide = keep;

Implement explicit-padding simplification first. Implicit assignment extension is a separate, optional increment requiring context/type proof; it must not be a blanket expression rewrite.

##### Minimal reproduction
Create a standalone production MorphVerilog regression using the current public frontend APIs. Follow the imports and generation fixture in morphhdl/src/test/scala/morphhdl/GenericProcessLoweringTests.scala. A candidate component is:

```scala
import spinal.core._
import morphhdl.frontend._

class SymbolicResizeProbe(width: HdlInt) extends Component {
  val keep = in(morphhdl.frontend.UInt(width bits))
  val keepWide = out(morphhdl.frontend.UInt((width + 1) bits))
  keepWide := keep.resize(width + 1)
}

// Instantiate through the repository's normal MorphVerilog test harness:
// new SymbolicResizeProbe(HdlInt.param("WIDTH", default = 8, min = 1, max = 32))
```

This candidate fixture has not been compiled independently. Verify the exact public overloads against the current compiler and make only the small API adjustments needed. Add a second fixture with the derived bus-width expression above so the real expression shape is covered. Do not replace symbolic widths with elaboration-default constants to make tests pass.

##### Incremental implementation
1. Locate the existing symbolic-width representation, range/domain authority, resize lowering and expression simplifier. Reuse existing machinery; do not introduce a general-purpose compiler framework or a Verilog text postprocessor. Record where the redundant expression originates.
2. Prove simple relations such as min(W + 1, W) = W and max((W + 1) - W, 0) = 1 under established valid parameter domains. Preserve the distinction between compiler width arithmetic and emitted Verilog integer arithmetic. Do not assume algebraic identities across overflow, signedness changes, invalid widths or unresolved domains. Use symbolic/range reasoning where supported rather than enumerating huge parameter spaces.
3. Remove proven full-width source slices where their expression type is preserved. A Verilog part-select of a signed vector is unsigned, so dropping a full-width slice can change semantics. Preserve any required cast or type boundary. Handle equal-width and narrowing resize cases with the same generic rules.
4. Retain explicit extension/truncation boundaries inside expressions unless a separate proof permits their removal. In particular, concatenations, arithmetic, comparisons, shifts and shift counts can depend on operand width and signedness. A rule that only excludes left shifts is insufficient. Constant-one padding is not sign extension; sign extension must repeat the actual sign bit.
5. Optionally, in a separately validated increment, replace explicit zero extension with a bare unsigned source for a whole assignment when destination conversion is proven equivalent. Treat signed destinations/sources and surrounding expressions explicitly. Preserve X/Z behavior and required truncation.
6. When proof is unavailable, retain the valid generic resize formula and existing diagnostics. Keep output deterministic and simplification terminating. Do not special-case application signal names, module names or the observed default parameter value.

##### Validation and acceptance
- Permanent structural regressions must demonstrate simpler native emitted RTL for W -> W + 1 and the derived bus-width example, with no redundant min-width full slice. Preserve public parameters.
- Cover unsigned and signed sources, widening, equal widths and narrowing; W=1 and representative larger widths; positive, negative and X/Z payloads; unrelated source/destination width parameters; and cases near arithmetic domain limits where simplification must be refused.
- Include nested concatenations, arithmetic, comparisons, left/right logical and arithmetic shifts, and self-determined shift counts. Verify negative controls retain necessary width/type boundaries.
- Compare baseline and changed emitted RTL using the same parameterized artifact across supported overrides, including BUS_LOG2_BYTES=2,3,4,5 for the derived-width case. Use an independent resize oracle as well as differential simulation. Include four-state simulation; do not present a two-state formal proof as X/Z coverage.
- Run existing parameterization, signedness, slice/resize, expression cleanup and generation regressions plus applicable repository-required lint, formal/equivalence and synthesis checks. Report any unexecuted checks clearly. Do not relax existing assertions or hide unsupported cases.
- Deliver the minimal runnable example, focused compiler change, before/after native Verilog, exact source identity and validation evidence. Keep any optional whole-assignment optimization separate from the first proven width-expression simplification.

- [ ] Support symbolic ElabInt hardware values

#### Extend MorphHDL: symbolic ElabInt values in typed hardware constants

Implement a focused, generic compiler/API extension that preserves an elaboration-parameter expression when it is used as the VALUE of a hardware UInt constant. Work in the MorphHDL compiler repository under its current instructions and qualification rules. Do not modify Display Controller application RTL, its compiler pin or its Python cleanup scripts in this task. Inspect the current compiler first and reuse any support already present; do not downgrade newer work.

##### Width support is not value support
Existing `UInt(busBytes bits)` declares a signal with a parameter-dependent WIDTH:

  wire [BUS_BYTES-1:0] signal;

The requested feature is a fixed-width hardware signal with a parameter-dependent VALUE:

  wire [7:0] busBytesValue;
  assign busBytesValue = BUS_BYTES;

Proposed user syntax, subject to compatibility review:

  val busBytesValue = U(busBytes, 8 bits)

Here busBytes is an ElabInt, not a concrete Scala Int. Preserve its symbolic expression and parameter provenance rather than substituting the elaboration default. Do not change the meaning of the existing parameterized width constructors.

##### Observed baseline and motivation
Read-only inspection at commit db54d01e5b21c7664f7a0de3795f061d77a3d259 found concrete Int/Long/BigInt value overloads in core/src/main/scala/spinal/core/Literal.scala, but no ElabInt value overload or public ElabInt-to-UInt value conversion. This is a source-inspection finding, not a freshly compiled failure report. Establish the actual current behavior before implementation; an equivalent existing public API may change the required scope.

The application currently obtains a hardware value equal to a symbolic width by counting a constant mask:

  val busZero = Bits(busBytes bits)
  busZero := 0
  val busBytesValue = spinal.lib.CountOne((~busZero).resize(32)).resize(8)

For busBytes in 4,8,16,32, the count is necessarily busBytes. This preserves parameter dependence but emits a verbose fixed-size population-count network. Synthesis should fold the constant network; reduced hardware area has not been measured or claimed. The goal is direct, correct symbolic-value representation and readable native RTL.

##### Minimal reproduction
Use the existing production MorphVerilog generation/test harness. The following uses parameter construction already present in the application generator; verify exact APIs and build setup against the current checkout.

```scala
import spinal.core._
import morphhdl.MorphVerilog
import morphhdl.frontend.HdlInt

class SymbolicValueProbe(busBytes: ElabInt) extends Component {
  val io = new Bundle {
    val value = out UInt(8 bits)
    val plusThree = out UInt(9 bits)
  }

  // Requested new value overload, not an assertion of current support:
  io.value := U(busBytes, 8 bits)
  io.plusThree := U(busBytes + 3, 9 bits)
}

object GenerateSymbolicValueProbe {
  def main(args: Array[String]): Unit = {
    require(args.length == 1, "Expected output directory")
    val config = SpinalConfig(
      targetDirectory = args(0),
      oneFilePerComponent = true,
      headerWithDate = false
    )
    MorphVerilog(config) {
      val logBytes = HdlInt.param(
        "BUS_LOG2_BYTES", default = 3, min = 2, max = 5
      ).asElabInt
      new SymbolicValueProbe(logBytes.pow2)
    }
  }
}
```

This standalone example has not been compiled in the application workspace. First record whether the current compiler rejects it, supports it correctly, or loses symbolic identity. Compile a baseline variant with the existing zero-mask/CountOne construction if the requested overload is unavailable; do not patch the baseline compiler to create the baseline.

Desired native output, with illustrative port names:

```verilog
module SymbolicValueProbe #(
  parameter integer BUS_LOG2_BYTES = 3
) (
  output wire [7:0] io_value,
  output wire [8:0] io_plusThree
);
  assign io_value = (1 << BUS_LOG2_BYTES);
  assign io_plusThree = (1 << BUS_LOG2_BYTES) + 3;
endmodule
```

Equivalent explicitly sized expressions are acceptable. The critical properties are retained parameter expressions and exact hardware width/signedness, not this particular spelling. No mask/population-count implementation should be needed for the requested direct-value API.

##### Implementation requirements
1. Reuse the existing ElabInt expression/domain authority and native expression/emission machinery. Add the smallest coherent public value-conversion API, preferably an appropriate U(ElabInt, width) overload if compatible with current literal factories. Do not create an unrelated compiler framework or repair emitted Verilog with text substitution.
2. Preserve parameter identity and composite expressions through native representation, simplification, expression copying, canonical capture/proof, and publication wherever those paths apply. Never carry only the default witness or recover expression identity from signal names. Fail clearly for unsupported expression families.
3. Give the hardware value an explicit packed type. Enforce unsigned range/width rules over the admitted domain, following existing literal semantics. Reject invalid values or require explicit truncation; never silently substitute a witness, clamp values or introduce an undocumented truncation policy. Respect exact domain constraints, not just an overly broad interval when precise evidence exists.
4. Preserve the explicit hardware width when the value is embedded in arithmetic, concatenations, comparisons and shift operands/counts. A bare Verilog parameter expression often has integer width and signedness; emitting it in every context can change semantics even if whole-assignment examples work. Add sizing/casts or a typed carrier where necessary, using the project's supported Verilog dialect.
5. Handle Verilog integer overflow and signedness consistently with ElabInt's admitted expression domain. Do not assume host arbitrary-precision arithmetic and emitted integer arithmetic are identical. Preserve parameter overrides on a single generated artifact.
6. Start with explicitly sized unsigned values and literal widths. Then qualify parameterized destination widths and signed S(...) or bit-vector B(...) value construction as separate increments if required by the repository's API design. State the exact supported surface; do not claim unsigned support implies signed support. Preserve existing concrete literals and UInt(ElabInt bits) behavior.
7. Keep stable names, parameter metadata and deterministic generation. Unsupported conversions must produce actionable diagnostics, not opaque Scala overload errors where the new API is applicable or silently frozen constants.

##### Validation and acceptance
- Add permanent positive and negative tests plus a runnable version of the reproducer.
- Generate once, then compile/simulate that same parameterized RTL with BUS_LOG2_BYTES=2,3,4,5. Expect value=4,8,16,32 and plusThree=7,11,19,35 respectively. Also compare against the mask/population-count baseline or an independent oracle.
- Cover direct parameters, derived expressions, constant ElabInt values, zero, maximum representable values, non-default overrides, and constrained domains. Include negative/out-of-range unsigned values, insufficient destination widths, unresolved or unsupported expressions, and arithmetic-domain boundaries.
- Test nested arithmetic, comparisons, concatenations and both shift operands with runtime inputs, including four-state inputs where relevant. These tests must expose accidental 32-bit/signed parameter-expression leakage and lost explicit truncation boundaries. Distinguish two-state proof evidence from four-state simulation.
- For separately supported signed or parameterized-width variants, test their full claimed semantics, including negative signed values and all admitted width/value combinations.
- Run existing literal, symbolic-width, parameterization, native expression cleanup/copy, generation and applicable formal/equivalence/lint/synthesis suites under repository policy. Do not relax existing assertions, skip failures silently or present default-only checks as parameter-family validation.
- Deliver source-bound evidence, exact compiler identity, before/after generated RTL, supported API documentation and remaining limitations. Application replacement of busBytesValue and the analogous ppcValue workaround is a subsequent source change after this compiler feature is qualified.


- [ ] Support explicit child formals for native ElabInt expressions and preserve scalar formal bindings

#### Fix the specific native parameter-boundary gaps in MorphHDL

Work in the compiler repository under its current instructions. Reuse the existing
formalParam, HdlInt, native ElabInt and hierarchy-publication machinery. Do not
modify Display Controller application sources, its compiler pin, generated RTL or
Python cleanup scripts. Inspect the current compiler first and preserve newer
support. This item belongs to the single integrated increment requested above.

##### Correct problem statement
A child should be able to declare its own WIDTH parameter while the parent supplies
a calculated bit width. For example, the desired output is:

```verilog
module Child #(parameter integer WIDTH = 64) (
  input  wire [WIDTH-1:0] din,
  output wire [WIDTH-1:0] dout
);
  assign dout = din;
endmodule

// Inside a parent declaring BUS_LOG2_BYTES:
Child #(.WIDTH((1 << BUS_LOG2_BYTES) * 8)) child (...);
```

WIDTH means the number of data bits. Binding `.WIDTH(BUS_LOG2_BYTES)` would be
incorrect for this interface. The child-local formal WIDTH and parent expression
BUS_LOG2_BYTES must remain separate identities.

Do not claim that all derived parameter bindings or pow2 arithmetic are broken.
The following distinctions were freshly checked at compiler commit
`db54d01e5b21c7664f7a0de3795f061d77a3d259`:

A. ALREADY WORKS: a child accepting HdlInt and explicitly declaring
   `formalParam(actualWidth, "WIDTH", 32, 256)` emits
   `.WIDTH((BUS_BYTES * 8))` when given the derived HdlInt expression BUS_BYTES*8.
   Both frontend width carriers and `width.asElabInt` consumers emit correctly.
   The native-consumer output compiled at BUS_BYTES=8 and BUS_BYTES=16.
   Preserve this behavior as a positive control; do not reimplement it.

B. MISSING NATIVE API BOUNDARY: `logBytes.asElabInt.pow2 * 8` produces an ElabInt,
   whereas the current public formalParam overloads accept HdlInt. No public
   ElabInt-to-HdlInt bridge was found in the inspected pin. The requested support
   is a coherent explicit child-formal API accepting a native ElabInt expression
   while retaining its symbolic actual. A native overload is preferable to forcing
   callers through a reverse bridge, but choose the smallest compatible design.
   Never convert through a Scala Int/default witness.

C. NO AUTOMATIC CHILD WIDTH: simply declaring `class Child(width: ElabInt)` does
   not automatically introduce a fresh Verilog parameter called WIDTH. A direct
   BUS_BITS root currently emits `.BUS_BITS(BUS_BITS)`. The exact native pow2
   example below fails with HIERARCHY-BINDING-UNRESOLVED for BUS_LOG2_BYTES. That
   failure does not prove explicit expression-valued bindings are unsupported.
   Use an explicit formal declaration for the requested WIDTH interface; automatic
   promotion of every Scala constructor argument is not required by this task.

D. SEPARATE SCALAR-FORMAL FAILURE: a child explicitly declaring its own
   `formalParam(HdlInt.literal(2), "BUS_LOG2_BYTES", 2, 5)` and deriving its port
   width with `.asElabInt.pow2 * 8` also fails hierarchy publication. This tests
   retention of a child-local scalar formal through derived widths. Its correct
   binding is `.BUS_LOG2_BYTES(2)`, NOT `.WIDTH(...)`, because this different child
   deliberately declares BUS_LOG2_BYTES as its public formal.

E. SEPARATE ZERO-DOMAIN RESTRICTION: `formalParam(HdlInt.literal(0), "PPC4", 0, 1)`
   is rejected as a nonpositive formal domain even when its derived packed width
   `PPC4 * 3 + 1` is always positive. A zero scalar value is not a zero packed width.

##### Runnable double-check: original failure and existing working explicit API
This exact fixture was compiled and executed with the pinned production plugins
and MorphVerilog. It includes a default-64-bit failing pow2 example, a direct
native parameter control, and working explicit WIDTH bindings for HdlInt products.

```scala
package displaycontroller.diagnostics
import spinal.core._
import morphhdl.{MorphVerilog,MorphWireAssignmentPasses}
import morphhdl.frontend.{HdlInt,formalParam}
object DoubleCheck {
 class Child(width: ElabInt) extends Component {
  setDefinitionName("Child")
  val din=in Bits(width bits)
  val dout=out Bits(width bits)
  dout:=din
 }
 class PlainTop(derived:Boolean) extends Component {
  setDefinitionName("Top")
  private val busBits:ElabInt=if(derived) HdlInt.param("BUS_LOG2_BYTES",3,2,5).asElabInt.pow2*8
    else HdlInt.param("BUS_BITS",64,32,256).asElabInt
  val din=in Bits(busBits bits)
  val dout=out Bits(busBits bits)
  val child=new Child(busBits)
  child.din:=din
  dout:=child.dout
 }
 class ExplicitChild(actual:HdlInt,native:Boolean) extends Component {
  setDefinitionName("ExplicitChild")
  @dontName private val width=formalParam(actual,"WIDTH",32,256)
  val din=if(native) in Bits(width.asElabInt bits) else in Bits(width bits)
  val dout=if(native) out Bits(width.asElabInt bits) else out Bits(width bits)
  dout:=din
 }
 class ExplicitTop(native:Boolean) extends Component {
  setDefinitionName("ExplicitTop")
  @dontName private val busBits=HdlInt.param("BUS_BYTES",8,4,32)*HdlInt.literal(8)
  val din=in Bits(busBits.asElabInt bits)
  val dout=out Bits(busBits.asElabInt bits)
  val child=new ExplicitChild(busBits,native)
  child.din:=din
  dout:=child.dout
 }
 def main(args:Array[String]):Unit={
  for(mode<-Seq("exact","direct","explicit-native","explicit-frontend")) {
   val config=SpinalConfig(targetDirectory=args(0)+"/"+mode,oneFilePerComponent=true,headerWithDate=false)
   try{
    val report=MorphVerilog(MorphWireAssignmentPasses(config,enabled=false)) {
     if(mode=="exact" || mode=="direct") new PlainTop(mode=="exact")
     else new ExplicitTop(mode=="explicit-native")
    }
    println("CHECK "+mode+" EMITTED "+report.generatedSourcesPaths.mkString(","))
   }catch{case e:Exception=>println("CHECK "+mode+" REJECTED "+e.getMessage)}
  }
 }
}
```

Observed results:
- exact: rejects publication with
  `SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-BINDING-UNRESOLVED` for scalar parameter
  BUS_LOG2_BYTES of Child.
- direct: emits both modules with BUS_BITS and `.BUS_BITS(BUS_BITS)`.
- explicit-native and explicit-frontend: emit child WIDTH=64, WIDTH-sized ports,
  parent BUS_BYTES=8 and `.WIDTH((BUS_BYTES * 8))`.

The runner catches exceptions to execute every case. Its zero process exit is NOT
proof that every case passed. Convert the intended successful cases into asserting
permanent compiler regressions. Evidence/source are in the application workspace:
`display-controller-morphhdl/diagnostics/parameter-restoration/DoubleCheck.scala`
and `double-check-result.json` in the same directory.

Reproduce there without editing build files:

```sh
cd /home/kartik/projects/py_francis/display_controller/display-controller-morphhdl
sbt \
  'set ddrPixelUnpacker / Compile / unmanagedSources += file("diagnostics/parameter-restoration/DoubleCheck.scala")' \
  'ddrPixelUnpacker/runMain displaycontroller.diagnostics.DoubleCheck /tmp/morphhdl-child-width-check'
```

For compiler development, copy the small fixture into the compiler's own standard
harness instead of depending on application directories.

##### Requested native API example
The following is the desired API shape, NOT a claim that this overload currently
compiles. Establish the current type error and add the supported native equivalent:

```scala
import spinal.core._
import morphhdl.frontend.{HdlInt, formalParam}

class NativeWidthChild(actualWidth: ElabInt) extends Component {
  // Proposed native ElabInt overload; preserve the actual expression and domain.
  @dontName private val width = formalParam(actualWidth, "WIDTH", 32, 256)
  val din = in Bits(width bits)
  val dout = out Bits(width bits)
  dout := din
}

class NativeWidthTop extends Component {
  private val busBits =
    HdlInt.param("BUS_LOG2_BYTES", 3, 2, 5).asElabInt.pow2 * 8
  val din = in Bits(busBits bits)
  val dout = out Bits(busBits bits)
  val child = new NativeWidthChild(busBits)
  child.din := din
  dout := child.dout
}
```

Required output: NativeWidthChild owns WIDTH and uses [WIDTH-1:0] ports; the parent
binds `.WIDTH((1 << BUS_LOG2_BYTES) * 8)`. Preserve the symbolic expression rather
than substituting 64. The actual supported API spelling may differ if compatibility
requires it; document it and retain this native-expression semantic contract.

##### Separate scalar-formal and zero-domain regressions
The previously executed `ParameterBindingProbe.scala` remains a valid separate
reproducer, not a substitute for the native WIDTH example above. Its two failures
can be reproduced with this child and a fixed-width parent:

```scala
class ScalarFormalChild(zeroCase: Boolean) extends Component {
  @dontName private val schema = if (zeroCase)
    formalParam(HdlInt.literal(0), "PPC4", 0, 1)
  else
    formalParam(HdlInt.literal(2), "BUS_LOG2_BYTES", 2, 5)

  private val bits = if (zeroCase) schema.asElabInt * 3 + 1
    else schema.asElabInt.pow2 * 8
  val din = in Bits(bits bits)
  val dout = out Bits(bits bits)
  dout := din
}
```

Use the complete executed harness at
`display-controller-morphhdl/diagnostics/parameter-restoration/ParameterBindingProbe.scala`
(and adjacent result.json); it includes direct parent connections, a fixed root
schema anchor and direct-width positive controls. The fragment above is extracted
for explanation; retain that full harness when reproducing its observed errors.
The scalar case fails with HIERARCHY-BINDING-UNRESOLVED. The zero case fails with
MORPH-FRONTEND-FORMAL-PARAMETER-DOMAIN-INVALID, requiring a positive domain.
After the fix, emit `.BUS_LOG2_BYTES(2)` with 32-bit ports in the scalar case and
`.PPC4(0)` with one-bit ports in the zero case. Do not change these public names or
encode zero as a different positive-only parameter.


##### CDC-specific native child-formal regression (2026-09-28)
Add this regression under the same child-formal/API task, not as a separate
checkbox or a request to implement production CDC logic in the compiler.

Fresh probes at pin db54d01e5b21c7664f7a0de3795f061d77a3d259 reproduced
SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-BINDING-UNRESOLVED for FIFO_LOG_DEPTH
in both ProbeCdcSynchronizer and ProbeGrayToBinary when their parent supplies
an ElabInt width of FIFO_LOG_DEPTH + 2. Their standalone parameterized forms
both generate successfully. The current direct-constructor probes do not declare
explicit local formals: use the supported explicit native formal API introduced
by this task in the positive regression; automatic constructor-argument promotion
is not required.

Project reproducer and qualification files, relative to the display-controller
repository root:
- display-controller-morphhdl/diagnostics/cdc-hierarchy/CdcHierarchyProbe.scala
- display-controller-morphhdl/diagnostics/cdc-hierarchy/qualify_standalone.py
- display-controller-morphhdl/notes/cdc-helper-hierarchy-20260928.md
- display-controller-morphhdl/evidence/cdc-helper-hierarchy-20260928/

Run from display-controller-morphhdl/toolchain/clock-reset-cdc:

```sh
sbt 'set Compile / unmanagedSources += file("../../diagnostics/cdc-hierarchy/CdcHierarchyProbe.scala")' \
  'runMain displaycontroller.cdc.CdcHierarchyProbe sync /tmp/cdc-probe-sync'
sbt 'set Compile / unmanagedSources += file("../../diagnostics/cdc-hierarchy/CdcHierarchyProbe.scala")' \
  'runMain displaycontroller.cdc.CdcHierarchyProbe decoder /tmp/cdc-probe-decoder'
```

The existing modes fail with a nonzero exit; do not interpret an exception-catching
runner or a directory of internal witness RTL as successful hierarchy publication.
For positive standalone controls, use modes standalone-sync and standalone-decoder.
Those artifacts passed nine Icarus profiles: WIDTH=1,5,18 crossed with STAGES=2,3,4,
512 samples each, checking latency, asynchronous active-low reset, midstream reset,
and Gray decoding against an independent reduction-XOR oracle. This evidence is
standalone simulation only, not hierarchy, formal or physical CDC qualification.

Required native Scala parameter flow, using the new supported explicit formal API:

```scala
val fifoDepth = HdlInt.param("FIFO_LOG_DEPTH", 3, 2, 16).asElabInt
val syncDepth = HdlInt.param("SYNC_STAGES", 2, 2, 4).asElabInt
val counterWidth: ElabInt = fifoDepth + 2
// Synchronizer declares child-local WIDTH and STAGES formals from these actuals.
// Decoder declares its own child-local WIDTH formal from counterWidth.
```

Required parent bindings (illustrative instance/port names):

```verilog
CdcSynchronizer #(.WIDTH(FIFO_LOG_DEPTH + 2), .STAGES(SYNC_STAGES)) gray_sync (...);
CdcSynchronizer #(.WIDTH(1), .STAGES(SYNC_STAGES)) flag_sync (...);
GrayToBinary #(.WIDTH(FIFO_LOG_DEPTH + 2)) gray_decode (...);
```

Acceptance additions:
- Expose a coherent public native ElabInt formal-binding API. The inspected
  spinal.core.ElabFormalComponent adapter is private[spinal]; do not access it
  through a forged library package, reflection, or patched compiled artifacts.
- Preserve WIDTH and STAGES as independent child-local identities. STAGES must
  remain bindable when used only in structural conditions/output-tap selection,
  rather than packed widths. Include literal WIDTH=1 and sibling instances with
  different widths, as well as derived counter widths and symbolic stage counts.
- Generate a parameterized parent hierarchy once; test the same artifact at
  FIFO_LOG_DEPTH=2,3,16 and SYNC_STAGES=2,3,4, including the one-bit sibling.
  Retain the standalone WIDTH=1,5,18 controls. Verify declared ports/registers,
  parameter bindings, selected latency, zero initialization and ASYNC_REG/CDC
  attributes under every tested override. Reject unsupported/illegal overrides
  through the supported contract; do not assume Scala require validates arbitrary
  downstream Verilog overrides.
- Preserve the existing library fromGray implementation in the decoder wrapper.
  BufferCC currently accepts Option[Int] for depth; do not silently freeze an
  ElabInt stage count to use it. Extending that library API is a distinct capability,
  not a prerequisite for proving the native child-formal binding fix with the probe.
- Check asynchronous active-low reset assertion, reset during traffic and exact
  2/3/4-cycle latency. Compare Gray-to-binary results with an independent oracle,
  exercise upper bits and X/Z behavior, and retain attributes through publication.
- Verify deterministic standalone and hierarchical publication with real children,
  lint and synthesis. A helper-only pass is not acceptance of the full production
  ClockResetCdc refactor; application migration and child-manifest updates follow
  separately once the compiler/API regressions pass.

##### Implementation and acceptance
1. Reuse the already-working explicit HdlInt formal/actual binding path. Extend
   the native ElabInt boundary without losing symbolic identity, exact domain
   evidence or source ownership. Preserve parent actual expressions separately
   from child definition formals through capture/copy, arithmetic and publication.
2. Preserve scalar child formals used only inside derived widths. Support zero-
   based scalar/Boolean formals where their uses are valid, while rejecting real
   zero/negative packed widths, out-of-domain actuals and incompatible connections.
3. Test literal, direct symbolic, derived and multilevel actuals; differently named
   parent/child parameters; siblings with different actuals; equal defaults with
   independent identities. Do not infer a binding from a default width or name.
4. Generate the native WIDTH artifact once and compile/simulate the same files at
   BUS_LOG2_BYTES=2,3,4,5, expecting 32,64,128,256-bit pass-through respectively.
   Exercise upper bits and four-state payloads with an independent oracle. Verify
   child WIDTH remains local and the parent's expression appears in its binding.
5. Preserve the working explicit HdlInt BUS_BYTES*8 tests and direct-width controls.
   Separately qualify child-local BUS_LOG2_BYTES overrides and PPC4=0/1 giving
   widths 1/4. Test standalone and oneFilePerComponent hierarchical publication,
   deterministic generation, invalid domains and unsupported expression families.
6. Do not special-case application names, patch generated Verilog, freeze witnesses
   or introduce an unrelated compiler framework. Automatic local formals for all
   Scala arguments and support for all possible host expressions are not required.
7. The real normalizer also hit direct-child-connection restrictions for expression
   uses of child outputs/inputs. Treat this as a separate integration finding:
   qualify supported direct carriers or document the exact remaining restriction.
   Do not claim full-controller success from these small parameter tests alone.
8. Run applicable existing hierarchy/formal binding, typed-width, native ElabInt,
   Boolean parameter, library, per-component generation and required lint/simulation/
   formal-equivalence regressions. Keep two-state proof and four-state simulation
   claims distinct. Report skipped/unavailable checks and remaining limitations.

Deliver the focused compiler/API change, permanent runnable tests, supported API
documentation, before/after RTL, exact compiler identity and validation evidence.
Restoring application parameters and qualifying the complete real-child controller
remain a subsequent application task after these capabilities are available.


- [ ] Retain named typed local constants as native localparams in custom decoders and AXI slave factories

#### Generic native local constants and readable register address decoding

Extend the current MorphHDL compiler/library generically so an application can
explicitly declare a named, module-local, typed elaboration constant and retain
its identity in native generated Verilog. The main motivating use is fixed CSR
byte addresses emitted as localparam declarations and referenced by name in case
labels. These are NOT externally overridable register-address parameters.

Work in the MorphHDL repository under its current instructions. Inspect existing
support first, reuse it, and keep the Display Controller application, its compiler
pin and its Python/Rust cleanup implementations unchanged during this compiler
task. Integrate this checkbox into the single increment requested at the top of
this document.

##### Scope and current evidence
At inspected pin db54d01e5b21c7664f7a0de3795f061d77a3d259:
- FSM/enum state encodings already emit named localparams and case labels. Do not
  describe the compiler as lacking all localparam support. Investigate reusing
  the existing declaration, naming and emission machinery.
- frontend StructuralExpressionBridge.rejectLocalParameters explicitly reports
  MORPH-FRONTEND-STRUCTURAL-LOCAL-PARAMETER-UNSUPPORTED for the general frontend
  local references on the inspected native bridge paths. ParamRtlFrontend has
  local-parameter handles/declarations; that does not establish native single-
  source MorphVerilog integration. Extend the public native path rather than
  directing applications to a separate/deprecated RTL authoring framework.
- A Scala val holding an Int is normally elaborated to a literal; its Scala name
  alone is not a native localparam declaration. Preserve explicit constant
  identity, not a guessed name reconstructed from emitted literals.
- AxiLite4SlaveFactory builds switch branches for SingleMapping and separate
  when(address.hit(...)) branches for typed ElabIntSingleMapping. Verilog permits
  localparams AND parameters as case labels; comparisons are a library lowering
  choice, not a language restriction. Inspect Axi4SlaveFactory independently.
- The actual production DisplayControllerAxiLiteCsrCommit currently uses a custom
  AXI-Lite decoder, not either slave factory. It must be possible to use the new
  constant API there without migrating to a factory.

Executed parameterization control, distinct from the requested feature:
  display-controller-morphhdl/diagnostics/slave-factory-addresses/
  SlaveFactoryAddressProbe.scala, generated/SlaveFactoryAddressProbe.v, result.json
The public AxiLite4SlaveFactory typed-address example emitted CONTROL_WORD and
STATUS_WORD integer parameters multiplied by four into morphhdl_typed_value_*
wires and equality/if decoding. All 16 declared address combinations passed its
focused Icarus checks; deterministic generation, lint with width warnings, and
default synthesis passed. This proves configurable address values, NOT fixed
named localparams or named case-label retention. The directory is application
workspace evidence; locate it if available, otherwise reconstruct the equivalent
small parameterized control in the compiler test harness.

##### Minimal custom-decoder reproduction and desired API
Use the established typed single-source MorphVerilog harness with the pinned
plugins. The following is PROPOSED API PSEUDOCODE: namedUIntLocal is a placeholder
for the smallest coherent public API chosen after reviewing current machinery;
it is not a claim that this API already exists. Convert it to a runnable permanent
Scala reproducer as part of implementation.

```scala
import spinal.core._

class NamedAddressDecoderProbe extends Component {
  val io = new Bundle {
    val address = in UInt(12 bits)
    val writeFire = in Bool()
    val writeStrobe = in Bits(4 bits)
    val statusWriteValid = out Bool()
  }
  // Explicitly typed elaboration constants, not UInt wires or runtime registers.
  val addrStatusClear = namedUIntLocal("ADDR_STATUS_CLEAR", 12, 0x0c0)
  val addrStatusMask  = namedUIntLocal("ADDR_STATUS_MASK",  12, 0x0c4)
  val addrEventClear  = namedUIntLocal("ADDR_EVENT_CLEAR",  12, 0x104)

  io.statusWriteValid := False
  when(io.writeFire && io.address(1 downto 0) === 0) {
    switch(io.address) {
      is(addrStatusClear, addrStatusMask, addrEventClear) {
        io.statusWriteValid := io.writeStrobe.orR
      }
    }
  }
}
```

Names in this example are illustrative, not an authoritative production CSR map.
Desired native output, ignoring incidental signal names and formatting:

```verilog
localparam [11:0] ADDR_STATUS_CLEAR = 12'h0c0;
localparam [11:0] ADDR_STATUS_MASK  = 12'h0c4;
localparam [11:0] ADDR_EVENT_CLEAR  = 12'h104;

always @(*) begin
  statusWriteValid = 1'b0;
  if(writeFire && address[1:0] == 2'b00) begin
    case(address)
      ADDR_STATUS_CLEAR, ADDR_STATUS_MASK, ADDR_EVENT_CLEAR:
        statusWriteValid = |writeStrobe;
    endcase
  end
end
```

Separate case arms are acceptable if the library does not merge identical bodies.
The key requirements are native localparam declarations, retained symbolic case
labels, and equivalent behavior. Do not meet this by emitting runtime carrier
wires, externally overridable parameters, literal-only labels, or postprocessing
Verilog. Ordinary comparisons must also retain local constant references.

##### Implementation requirements
1. Provide an explicit public application API for module-local elaboration
   constants with stable names and packed width/signedness. Reuse enum/localparam
   emission and existing typed-expression/ownership facilities where suitable;
   do not misuse state enums to encode arbitrary register addresses.
2. Preserve identity through native lowering, copying, simplification, canonical
   capture, validation and deterministic publication. Keep declarations local to
   their owning component; handle dependencies, name collisions, illegal names,
   duplicate declarations and cross-component misuse with clear diagnostics.
   Do not automatically convert every Scala val or repeated literal to localparam.
3. Support unsigned fixed-width constants sufficient for address decoding, with
   explicit range checks. Define supported signed forms separately and test them
   if claimed. Preserve widths/signs in comparisons, arithmetic, concatenations
   and shifts; do not leak unsized/signed 32-bit integer semantics into hardware.
4. Support local constants derived from other locals and valid public elaboration
   parameter expressions where the existing domain machinery can prove legality.
   Such constants remain localparam, retain dependencies, and recompute under
   overrides of their public roots. Fixed addresses must remain non-overridable.
5. Support direct custom switch/is consumers and comparisons. Named typed locals
   used as switch keys must remain compile-time constants in native case labels,
   not runtime signals requiring a different decode architecture.
6. Integrate shared BusSlaveFactory named single-address support into BOTH
   AxiLite4SlaveFactory and Axi4SlaveFactory, including read, write, readAndWrite,
   onRead and onWrite consumers as applicable. Use the same public local-constant
   API as custom decoders. Preserve references as named case labels for exact
   single-address mappings when safe; do not claim that one factory qualifies the
   other or add application-name-specific rules.
7. Preserve factory alignment, address masking, bus width, byte-strobe handling,
   handshake, response, event, read/write side-effect and error behavior. Retain
   fallback paths for ranges/masks and unsupported mapping forms. Do not combine
   independent matching conditions into exclusive case arms unless disjointness
   and intended grouping/priority are established across the admitted parameter
   domain. Detect ambiguous/overlapping mappings under existing factory policy.
8. Do not broadly rewrite if chains into case or change X/Z matching behavior.
   Preserve default/no-match semantics and cases with shared addresses containing
   multiple read/write jobs. Preserve existing literal maps and FSM enum output.
   Avoid compiler forks, signal-name inference and generated-text repairs.

##### Validation and acceptance
- Establish the current baseline with runnable probes; distinguish source-only
  findings from executed failures. Keep the working configurable-address control.
- Add permanent custom-decoder, AxiLite4SlaveFactory and Axi4SlaveFactory examples
  with named fixed local addresses. Inspect native output for declarations and
  named case labels, not merely behavior or parameter-looking headers.
- Test address 0, high valid aligned addresses, repeated reuse of one constant,
  independent constants with equal values, shared read/write mappings, unaligned
  and out-of-range values, name/ownership errors, and dependency cycles or forward
  references according to the API contract. Reject unsupported cases explicitly.
- Add derived localparam examples under public base-address parameter overrides;
  generate once and test the same artifact at default, representative and boundary
  admitted values. Check alignment/range and overlap over the supported domain.
- Simulate the custom decoder across the complete small address space, strobes,
  writeFire and representative X/Z values, comparing the existing literal decode
  with the named-localparam decode. Exercise default/no-match behavior explicitly.
- Exercise real factory read/write/event behavior, byte enables, stalls, resets and
  responses; use applicable existing AXI4 burst/ID and AXI-Lite independent-channel
  qualification rather than assuming these protocols are interchangeable. Compare
  against the unchanged literal-map behavior with real factory implementations.
- Run existing enum/FSM, literal-map, typed-address, symbolic-value and hierarchy
  regressions plus applicable lint, simulation, formal/equivalence and synthesis
  checks. Generate twice and compare output deterministically. Distinguish limited
  emission/address tests from full protocol or production-CSR qualification.
- Deliver supported API documentation, exact compiler/source identities, before/
  after RTL and results. A later application task may replace the custom CSR's
  literals with named constants; a factory migration is NOT required for that use.

## Completion target

The roadmap is complete when parameter-sensitive SpinalHDL algorithms retain
`ElabInt`/`ElabBool` values from API entry through elaboration and lower one
readable parameterized Verilog-2001 definition per logical component, with
correct publication in both consolidated and `oneFilePerComponent = true`
modes. Literal `Int`/`Boolean` calls must still produce ordinary parameter-free
SpinalHDL. Native algorithms must remain authoritative, approved native changes
must be small and mechanical, and the production implementation must not
reconstruct symbolic meaning from erased Scala values, component names,
source-file special cases, emitted identifiers or equal concrete witnesses.
The independent Increment 63 track additionally requires the six-stage
production wire pipeline and provenance-first direct-alias survivor selection
to pass its complete final-head qualification without changing these
parameterized-publication requirements.

The planned Increments 64 and 65 additionally require qualified non-overridable
derived localparams and correctly activation-scoped structural requirements,
with their bounded support contracts, compatibility gates and actual output
evidence complete. Planning examples alone do not satisfy those requirements.

Increment 66 additionally requires all five integrated native-extension tasks
above to meet their stated acceptance criteria, with runnable compiler examples,
actual generated RTL and source-bound local and final-head qualification evidence.
Implementation checkpoints alone do not establish completion.

## September 20 CDC report — parameter-legality presentation

This is a new, unchecked follow-up reported against compiler
`86242bce51a0469ec3a7468391cbeb914d004731`. It changes presentation only, not
parameter domains, legality semantics, guard ownership or fatal termination.
It does not reopen historical completion checkboxes or gate the existing next
integration target. Wire-expression issues from the same report are tracked in
[the existing wire-pass roadmap](../../morphhdl-passes/morphhdl-ir-wire-assignment-passes-todo.md#september-20-cdc-report--recursive-expression-cleanup).

- [ ] **CDC-LEG-01 — Product-neutral legality labels and user-only fatal messages.**

  **Implementation complete; focused local validation passed.** Final
  qualification and merged closure remain pending.

  Replace native generated `g_morphhdl_parameter_legality_0` with
  `G_PARAMETER_LEGALITY_0`, applying the same spelling to subsequent deterministic
  indices. Remove the injected `MorphHDL parameter legality failed: ` prefix:
  emit `initial $fatal(1, "%s", "LIVE_LANES must be 1 or 4");` for this example.
  Update the structured emitter rather than rewriting generated text. Preserve
  component/scope ownership, uniqueness, ordering, the safe literal `%s` format,
  fatal finish argument and synthesis guard. Simulator-owned file/time/scope
  reporting is outside this change.

  **Standalone reproduction:** save this complete source as `/tmp/CdcLegalityMessageRepro.scala`.
  Run the commands below from the MorphHDL repository root; no Display Controller
  source, Dan IP or parent repository is needed. The temporary SBT setting selects
  only this fixture for Test compilation and retains the repository's compiler plugins.

  ```scala
  // Reproduction: CdcLegalityMessageRepro
  package roadmap

  import spinal.core._
  import spinal.lib._
  import morphhdl.{MorphVerilog, MorphWireAssignmentPasses}
  import morphhdl.frontend.HdlInt

  object CdcLegalityMessageRepro extends App {
    require(args.length == 2, "Expected output-directory and passes-enabled")
    val config = MorphWireAssignmentPasses(SpinalConfig(
      targetDirectory = args(0), oneFilePerComponent = true,
      headerWithDate = false, headerWithRepoHash = true
    ), enabled = args(1).toBoolean)
    MorphVerilog(config) {
      new Component {
        setDefinitionName("CdcLegalityMessageRepro")
        val lanes: ElabInt = HdlInt.param("LIVE_LANES", 1, 1, 4).asElabInt
        require(lanes == 1 || lanes == 4, "LIVE_LANES must be 1 or 4")
        val inputBits = in Bits(lanes bits)
        val outputBits = out Bits(lanes bits)
        outputBits := inputBits
      }
    }
  }
  ```

  ```sh
  sbt 'set morph / Test / unmanagedSources := Seq(file("/tmp/CdcLegalityMessageRepro.scala"))' \
    'morph/Test/runMain roadmap.CdcLegalityMessageRepro /tmp/CdcLegalityMessageRepro-on true' \
    'morph/Test/runMain roadmap.CdcLegalityMessageRepro /tmp/CdcLegalityMessageRepro-off false'
  iverilog -g2012 -s CdcLegalityMessageRepro -o /tmp/CdcLegalityMessageRepro.vvp /tmp/CdcLegalityMessageRepro-on/CdcLegalityMessageRepro.v
  ```

  **Expected emitted form:** the mixed-domain guard is labelled
  `G_PARAMETER_LEGALITY_0` and its fatal message contains only the user text.
  Neither lowercase nor `morphhdl` occurs in generated legality labels.
  Exercise multiple requirements and hierarchy/sibling scopes before closure;
  do not rename user-authored labels. Both enabled and disabled wire-pass modes
  must use the requested presentation and deterministic indices.

  **Runtime reproduction:** compile the same artifact with
  `iverilog -g2012 -s CdcLegalityMessageRepro -P CdcLegalityMessageRepro.LIVE_LANES=2 -o /tmp/cdc-invalid.vvp /tmp/CdcLegalityMessageRepro-on/CdcLegalityMessageRepro.v`,
  then run `vvp /tmp/cdc-invalid.vvp`. It must terminate nonzero and report the
  user message without the compiler-added prefix. Overrides 1 and 4 must remain
  legal; override 3 must also fail. Repeat with the `-off` artifact. Preserve
  quotes, newlines, percent signs and backslashes in further message controls,
  empty-message/default-message behavior, and synthesis exclusion. These are
  acceptance obligations, not claims that the requested change is implemented.

  **Baseline reproduction checked, September 20:** the exact source above
  compiled with the repository's SBT/Scala 2.12.18 plugins and generated in both
  pass modes; Icarus compiled both artifacts. On the enabled artifact, overrides
  1/4 exited successfully and 2/3 terminated nonzero with the current prefixed
  message. That baseline used `g_morphhdl_parameter_legality_0`; it is historical
  reproduction evidence, not the status of the implementation below.

  **Local implementation checkpoint, 2 October 2026:** implemented on
  `work/remaining-parameterized-increments`; remote qualification and merge are
  pending the batch-wide CI phase. The native emitter allocates
  `G_PARAMETER_LEGALITY_<index>` through its existing name scope and passes the
  escaped original message directly to the unchanged guarded `$fatal` call.
  `NativeLegalityPresentationTests` and `SymbolicPublicationEvidenceTests` pass
  together on Scala 2.12.18 and 2.13.12 (22 tests per lane). Coverage includes
  repeated emission, both existing pass modes, sibling scopes, collisions with
  user names, obligation ordering, default/empty/escaped messages, Icarus
  valid/invalid overrides and four-state passthrough, synthesis exclusion,
  Verilog-2001 parsing, Verilator lint and Yosys synthesis. The existing Python
  diagnostic checker now requires exactly one original diagnostic and rejects
  the removed prefix; all 15 policy tests pass. Local run log:
  `/tmp/morphhdl-cdc-legality-final.log`. These are local implementation receipts,
  not remote CI or merge receipts.
