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

- [ ] **Increment 59c — Field-preserving parameterized Vec-of-Bundle interfaces**

  **Dependencies:** Increment 59b implemented and merged. Parallel successor;
  no dependency on 59d through 59h or unfinished Increment 60 children.

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

- [ ] **Increment 59d — Generic symbolic result-width provenance and widening reductions**

  **Dependencies:** Increment 59b implemented and merged. Parallel successor;
  no dependency on 59c or 59e through 59h.

  Implementation and qualification record:
  [Increment 59d](increment-59d-symbolic-widths.md). The checkbox remains open
  until the complete final-head acceptance gates pass.

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

- [ ] **Increment 59e — Recursive composite-Data balanced reduction**

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

- [ ] **Increment 59f — Generic safe callback graphs and explicit captured inputs**

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

- [ ] **Increment 59g — Register-bridge semantics and clock/reset qualification closure**

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

- [ ] **Increment 59h — Balanced reduction inside nested typed structural owners**

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
  final-head CI/formal/tool gates before marking this join complete. The user's
  CI skip for this roadmap-only planning commit does not waive implementation
  or merge gates for any of these increments.

### Native signed-Verilog track (Increment 60)

- [ ] **Increment 60 — Native signed `SInt` Verilog**

  **Dependencies:** Increment 59 implemented and merged.

  Follow [the Increment 60 child roadmap](increment-60-sint-signed-verilog-roadmap.md)
  and [signedness semantic contract](increment-60-signedness-contract.md).
  The serial chain is 60a through 60g; this parent remains open until every
  child and its final-head gates are complete. Ordinary SpinalVerilog stays
  unchanged by default.

## Completion target

The roadmap is complete when parameter-sensitive SpinalHDL algorithms retain
`ElabInt`/`ElabBool` values from API entry through elaboration and lower one
readable parameterized Verilog-2001 definition per logical component. Literal
`Int`/`Boolean` calls must still produce ordinary parameter-free SpinalHDL.
Native algorithms must remain authoritative, approved native changes must be
small and mechanical, and the production implementation must not reconstruct
symbolic meaning from erased Scala values, component names, source-file special
cases, emitted identifiers or equal concrete witnesses.
