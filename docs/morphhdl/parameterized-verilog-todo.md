# Parameterized-Verilog corrective roadmap

This file is the controlling implementation checklist for the single-source
parameterized-Verilog front door. It supersedes component-by-component
recommendations in earlier increment notes when those recommendations conflict
with this roadmap.

## Roadmap discipline

- The first unchecked increment remains the default sequential integration
  target. Explicitly declared parallel successors may start once every listed
  dependency is `[x]` on `parameterized-verilog`; increment numbering alone
  does not create a dependency.
- Every parallel branch must start from a merged dependency state and must
  incorporate the latest `parameterized-verilog` before final validation. An
  open branch or pull request never satisfies another increment dependency.
- Parameterizable values remain typed symbolic objects through elaboration;
  they must not be replaced by their concrete defaults before symbolic RTL is
  captured.
- The production path must lower ordinary SpinalHDL component logic. A new
  component-specific ParamRTL adapter is not an acceptable substitute for that
  integration.
- Existing atomic ParamRTL nodes and their fixtures remain regression oracles
  while the generic path is built. Their presence does not establish
  single-source support.
- Upstream-owned SpinalHDL source must remain unchanged by the preservation
  increments. MorphHDL-specific files currently located under native `core`,
  `lib` or `idslplugin` source trees are also scheduled for extraction.
- If an increment cannot satisfy its contract without changing an
  upstream-owned SpinalHDL file, implementation must stop before that change.
  The exact minimal hook, alternatives and compatibility impact must be
  presented for explicit approval; no native-source exception is implicit.
- Every preservation increment must retain the applicable concrete parity,
  simulation, lint, synthesis, mutation, determinism, strict Verilog-2001 and
  dual-Scala gates already established by Increments 29 through 37.
- An increment checkbox may change from `[ ]` to `[x]` only after its
  implementation and review are complete and the full applicable local gates
  pass. Updating this checkbox is the final source change before publication.
- The suggested next sequential increment after completion is the first
  unchecked entry whose dependencies are satisfied. Independently eligible
  siblings may additionally be identified as parallel candidates.

The source audit and classification behind Increments 38 through 58 are
recorded in
[Native SpinalHDL source-preservation audit](native-spinal-source-preservation-audit.md).

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

## Native-source preservation increments

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

### Dependency graph and parallel execution for Increments 45 through 58

After Increment 44 is implemented and merged:

- Increments 45, 46 and 48 are independent parallel starts.
- Increment 47 depends only on Increment 46 and may overlap unfinished work on
  Increments 45 and 48.
- Increment 49 depends only on Increment 47; Increment 50 depends only on
  Increment 49. This native-`Int` chain may continue while 45 or 48 remains in
  progress.
- Increment 51 joins the explicit-condition path and native-`Int` path; it
  depends on Increments 48 and 50.
- Increment 52 depends only on Increment 51.
- Increment 53 joins memory provenance and symbolic control flow; it depends
  on Increments 45 and 52.
- Increments 54 through 58 then form a strict sequential closure chain.

Dependencies are transitive. Two increments with no dependency edge between
them are intentionally eligible for parallel implementation and review.

Native-looking source compatibility is a closure requirement. Temporary
MorphHDL constructor aliases such as `MorphCounter`, `MorphStream` and
`MorphFlow` may remain as regression scaffolding while Increments 45 through 55
stabilize the generic provenance and zero-native-diff architecture, but they
must not become the required application-facing migration surface. Increments
56 through 58 replace that temporary construction surface with ordinary-looking
SpinalHDL library calls and retire the compatibility path only after parity is
proven.

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

- [ ] **Increment 46 — Formal parameter identity and canonical child modules**

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

- [ ] **Increment 47 — External formalization boundary for native `Int` APIs**

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
  provenance.

- [ ] **Increment 49 — Native `Int` symbolic provenance propagation**

  **Dependencies:** Increment 47 implemented and merged.

  At an Increment 47 formalization boundary, associate each selected native
  `Int` constructor argument or local value with both its concrete Scala
  witness and its MorphHDL symbolic actual. Preserve that shadow provenance
  through component construction, nested formal scopes and Spinal
  re-elaboration without changing the native API or runtime value. Prove
  deterministic identity, cleanup, replay and conflict diagnostics. Do not yet
  transform arbitrary arithmetic or control flow.

- [ ] **Increment 50 — Shadow native `Int` expressions and predicates**

  **Dependencies:** Increment 49 implemented and merged.

  Propagate proven symbolic provenance through the bounded operations needed by
  native library code: addition, subtraction, multiplication, division,
  remainder, comparisons, min/max, address/log2 helpers and power-of-two
  predicates. Retain one concrete witness expression and one bounded symbolic
  expression, prove their default agreement and domain safety, and reject
  unsupported calls, boxing, mutable escape or ambiguous aliasing explicitly.

- [ ] **Increment 51 — Symbolic native-`Int` branch capture**

  **Dependencies:** Increments 48 and 50 implemented and merged.

  Transform `if`/`else if`/`else` only when its ordinary Scala Boolean
  predicate is proven to depend on shadow-symbolic native `Int` values from
  Increments 49 and 50. Capture every source alternative, keep only the
  witness-selected alternative in the ordinary concrete Spinal graph and
  retain all alternatives in MorphHDL-owned structural IR for generic
  Verilog-2001 lowering. Preserve source order, names and diagnostics; ordinary
  Scala conditionals without symbolic provenance remain untouched.

- [ ] **Increment 52 — Nested symbolic control flow and side-effect safety**

  **Dependencies:** Increment 51 implemented and merged.

  Extend native symbolic branch capture to the bounded constructs required by
  real library algorithms: nested conditionals, loops inside alternatives,
  local vals, registers, memories, Areas/ClockingAreas, naming and supported
  assignments. Define an explicit safe side-effect contract and fail closed for
  mutable external state, I/O, reflection, nondeterminism or unsupported
  arbitrary Scala effects. Prove deterministic replay, hierarchy stability,
  driver/latch/clock/reset validation and nested generate legality.

- [ ] **Increment 53 — Native StreamFifo parameter structure without source edits**

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

- [ ] **Increment 54 — MorphHDL module extraction and native-tree cleanup**

  **Dependencies:** Increment 53 implemented and merged.

  Move remaining MorphHDL-specific parameter metadata, capture and lowering
  files out of native `core`, `lib` and `idslplugin` source trees into
  MorphHDL-owned modules/packages. Remove build coupling that requires a forked
  native implementation while retaining both Scala 2.12.18 and 2.13.12 support,
  source locations, diagnostics and public MorphHDL behavior. Publish the
  stable canonical MorphHDL-owned post-parameterization IR/API required by
  downstream optional passes.

- [ ] **Increment 55 — Upstream parity and complete zero-diff proof**

  **Dependencies:** Increment 54 implemented and merged.

  Restore every upstream-owned runtime, library, emitter, phase and compiler
  plugin file identified by the audit to the selected upstream snapshot. Add an
  exact native-source manifest gate with no exception unless previously
  approved. Run the complete inherited validation inventory and all concrete,
  parameter-override, simulation, lint, synthesis, mutation and determinism
  gates for Increments 29 through 54.

- [ ] **Increment 56 — Native-looking SpinalHDL library-call provenance bridge**

  **Dependencies:** Increment 55 implemented and merged.

  Make the application source call the ordinary-looking imported SpinalHDL
  constructors directly, for example `Counter(width bits)`,
  `Stream(Bits(width bits))` and `Flow(Bits(width bits))`, while keeping the
  returned objects exactly `spinal.lib.Counter`, `spinal.lib.Stream` and
  `spinal.lib.Flow`. Implement the symbolic call boundary in MorphHDL-owned
  code, using a typed compiler transformation, deterministic call-site token or
  equivalent mechanism that passes only the concrete witness into the
  untouched native constructor and then associates the exact symbolic origin
  with the returned native object. Do not add a provenance-losing implicit
  `ParameterizedBitCount`-to-`BitCount` conversion, modify `Counter.scala` or
  `Stream.scala`, recognize emitted signal/component names, or reconstruct a
  library algorithm. Ordinary concrete `Counter`, `Stream` and `Flow` calls
  must remain unchanged when no MorphHDL symbolic value is present. Prove the
  bridge on Scala 2.12.18 and 2.13.12 and fail closed if a symbolic call cannot
  be associated unambiguously with one native result object.

- [ ] **Increment 57 — Native-looking Counter, Stream and Flow migration proof**

  **Dependencies:** Increment 56 implemented and merged.

  Migrate the production-facing Increment 44 fixtures and examples from
  `MorphCounter`, `MorphStream` and `MorphFlow` constructor aliases to ordinary
  `spinal.lib` imports and native-looking constructor calls. Prove that the
  resulting source still executes the untouched native Counter and Stream/Flow
  pipeline methods, that the concrete-default `SpinalVerilog` result preserves
  parity, and that non-default parameter overrides produce the same legal
  Verilog-2001 behavior. Include Counter increment/clear/wrap/completion,
  Stream `m2sPipe`/`s2mPipe`/`halfPipe`, Flow `m2sPipe`, static-depth FIFO
  payload propagation, and negative provenance tests showing that unrelated
  fixed-width user assignments are not treated as native-library internals.
  The test source must not require MorphHDL-prefixed library constructor names.

- [ ] **Increment 58 — Migration and adapter retirement**

  **Dependencies:** Increment 57 implemented and merged.

  Migrate the remaining reviewed artifacts to the zero-native-edit,
  native-looking single-source lowering path, preserve their simulation, lint,
  synthesis, mutation and determinism gates, and deprecate the dual-factory,
  component-specific and MorphHDL-prefixed library-constructor production
  paths. Keep old aliases and atomic contracts only as explicit compatibility
  and regression oracles where removal would unnecessarily break historical
  tests. Finalize the stable post-parameterization, pre-emission production
  handoff used by optional MorphHDL-owned IR passes.

## Completion target

The roadmap is complete when normal, unmodified SpinalHDL component and library
source can retain typed public parameters through MorphHDL-owned integration,
including parameter-dependent native Scala expressions and structural
alternatives, producing one readable parameterized Verilog-2001 definition per
logical component without separately handwritten ParamRTL implementations,
component-name rewrites or unapproved native-source modifications. Application
RTL must be able to use native-looking SpinalHDL library construction without
requiring `MorphCounter`, `MorphStream`, `MorphFlow` or equivalent
MorphHDL-prefixed constructor aliases.