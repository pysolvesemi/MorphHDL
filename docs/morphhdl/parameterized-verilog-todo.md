# Parameterized-Verilog corrective roadmap

This file is the controlling implementation checklist for the single-source
parameterized-Verilog front door. It supersedes component-by-component
recommendations in earlier increment notes when those recommendations conflict
with this roadmap.

## Authoritative typed-elaboration architecture from Increment 53d onward

This section is the controlling architecture for Increment 53d and every later
parameterization increment. It supersedes earlier zero-native-diff requirements
where they conflict with this section. Completed preservation increments remain
valuable historical evidence, but future implementation must not recover
symbolic meaning after a parameter-sensitive value has already collapsed to a
plain Scala `Int` or `Boolean`.

MorphHDL shall retain elaboration-time parameters as neutral typed values,
`ElabInt` and `ElabBool`, through the SpinalHDL algorithms that consume them.
`HdlInt` and `HdlBool` may remain user-facing construction APIs, but they must
lower to the same typed elaboration values rather than shadow metadata attached
after value erasure.

Small reviewed changes to SpinalHDL `core` and `lib` are explicitly allowed
when limited to parameter-sensitive formal types or overloads, typed helper
functions, explicit type annotations needed for overload resolution, and
mechanical metadata propagation through an existing algorithm. Every changed
native file must be listed in an audited manifest. The original SpinalHDL
algorithm remains authoritative; no primitive may be reimplemented in MorphHDL.

Concrete compatibility is mandatory. Existing `Int`/`Boolean` overloads remain
parameter-free. There is no implicit `ElabInt => Int` or `ElabBool => Boolean`
conversion. Witness extraction is explicit and may collapse a derived typed
expression only when its complete bounded domain proves it constant.

Natural Scala syntax such as `if (depth == 1)` may be retained by a small
compiler syntax bridge that lowers only expressions already proven typed as
`ElabInt`/`ElabBool`. It must not reconstruct provenance from a plain native
`Int`, scan component names, instrument arbitrary Scala integer code, or replay
branches in a graph already typed for another witness.

The production path from Increment 53d onward forbids component/file/module/
port/signal-name recognition, equal-witness or rendered-text identity guesses,
parser-wide native-`Int` shadow propagation, post-erasure branch replay, and
separately authored replacements for native primitives.

A typed migration completes only after Scala 2.12.18 and 2.13.12 pass under SBT
and Mill, ordinary concrete generation remains compatible, all admitted
parameter overrides lint and synthesize, and applicable independent formal and
mutation controls pass.

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
- Increments 38 through 53c retain their historical source-preservation
  evidence. From Increment 53d onward, the authoritative typed-elaboration
  section permits small audited `core`/`lib` type, helper and mechanical
  propagation changes while prohibiting algorithm replacement.
- Any broader native semantic or algorithm change remains approval-gated. Stop
  and present its exact necessity, alternatives and compatibility impact before
  making such a change.
- Every typed migration must retain applicable concrete parity, simulation,
  lint, synthesis, mutation, determinism, strict Verilog-2001 and dual-Scala
  gates, and must validate both SBT and Mill build paths.
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
- Increment 53a is a corrective formal-equivalence closure and depends only on
  the merged Increment 53.
- Increment 53b depends only on the merged Increment 53. Increments 53a and 53b
  may execute independently.
- Increment 53b.1 is a corrective enum-naming closure and depends only on
  the merged Increment 53b.
- Increment 53c depends on the merged Increment 53b and may overlap
  Increments 53a and 53b.1 once Increment 53b is merged.
- Increment 53d is the approved architecture pivot and depends on the merged
  Increments 53a, 53b.1 and 53c. It supersedes the shadow-native-`Int`
  production strategy without invalidating historical regression evidence.
- Increment 54 depends on merged typed Increment 53d. Increments 54 through 58
  form a strict typed-migration and retirement chain.

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

- [ ] **Increment 53d — Typed elaboration pivot and native StreamWidthAdapter closure**

  **Dependencies:** Increments 53a, 53b.1 and 53c implemented and merged.

  Establish neutral `ElabInt` and `ElabBool` carriers below the SpinalHDL
  library layer. Preserve a concrete witness, bounded symbolic expression and
  exact parameter identity. Keep existing primitive overloads concrete and
  parameter-free, and extend the natural-control compiler bridge only for
  statically proven typed expressions.

  Migrate the existing native `StreamWidthAdapter` algorithm to typed payload
  width expressions through a generic `widthOfExpr` or equivalent helper.
  Preserve equal-width, downsize and upsize alternatives, arithmetic, counter,
  resize, slicing and backpressure without a MorphHDL-authored adapter. Allowed
  native changes are limited to formal types, helper calls, explicit annotations
  and mechanical typed propagation and must be audited.

  Prove concrete `Int`/`widthOf` generation remains parameter-free. Prove typed
  paths on Scala 2.12.18 and 2.13.12 under SBT and Mill with deterministic strict
  Verilog-2001, simulation/backpressure, lint, synthesis and independent formal
  equivalence plus mutation counterexample. The production path must not depend
  on native-`Int` shadow reconstruction or component/source-name recognition.

- [ ] **Increment 54 — Typed StreamFifo depth and structural-domain validation**

  **Dependencies:** Increment 53d implemented and merged.

  Change only the parameter-sensitive StreamFifo depth surface and generic
  helpers required by its existing algorithm to use `ElabInt`/`ElabBool`.
  Preserve bypass, depth-one, power-of-two, non-power-of-two, asynchronous and
  synchronous alternatives. Validate each captured alternative under its own
  narrowed domain rather than the default-witness graph. Retain concrete `Int`
  compatibility and rerun the independent depth 1, 3, 5 and 8 formal proofs.

- [ ] **Increment 55 — Typed geometry helper and primitive migration matrix**

  **Dependencies:** Increment 54 implemented and merged.

  Generalize typed overloads for log/address helpers, power-of-two predicates,
  ranges, Counter, Mem, Vec, bit counts, resize, slices and hierarchy actuals.
  Migrate representative components only through parameter-sensitive types,
  helpers and mechanical propagation. Add an audited inventory of typed versus
  host-side primitive arguments.

- [ ] **Increment 56 — Shadow-native-Int production retirement**

  **Dependencies:** Increment 55 implemented and merged.

  Remove native-`Int` shadow provenance, source-position alias reconstruction,
  component-specific constructor state and post-erasure branch replay from the
  production path. Keep historical fixtures only as compatibility or negative
  regression evidence. Reduce the compiler plugin to typed syntax lowering.

- [ ] **Increment 57 — Native-library typed migration and compatibility proof**

  **Dependencies:** Increment 56 implemented and merged.

  Apply the typed surface to the reviewed native library set, including Counter,
  Stream/Flow pipelines, StreamFifoCC and AXI/register-map geometry where useful.
  `Int` calls stay concrete; `HdlInt`/`ElabInt` calls select typed overloads.
  Preserve original algorithms and prove simulation, lint, synthesis,
  determinism and formal equivalence for each migrated family.

- [ ] **Increment 58 — Stable typed API, release boundary and legacy cleanup**

  **Dependencies:** Increment 57 implemented and merged.

  Freeze the low-level `ElabInt`/`ElabBool` ABI, user-facing construction API and
  post-parameterization IR handoff. Audit all allowed native edits, retire
  superseded adapters and shadow pathways, publish migration guidance and run
  the complete dual-Scala SBT/Mill regression and formal inventory.

## Completion target

The roadmap is complete when parameter-sensitive values remain typed from the
application boundary through the original SpinalHDL algorithms into one
readable parameterized Verilog-2001 definition per logical component. Ordinary
`Int`/`Boolean` calls remain source-compatible and parameter-free. Typed calls
need no post-erasure provenance reconstruction, component-name rewrite or
separately authored primitive. All native edits are small, audited type/helper/
mechanical changes, and both Scala versions pass under SBT and Mill with the
applicable simulation, lint, synthesis and formal gates.
