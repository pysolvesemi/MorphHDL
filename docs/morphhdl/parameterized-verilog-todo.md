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

- [ ] **Increment 59b — Typed parameterized Vec reduceBalancedTree**

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
