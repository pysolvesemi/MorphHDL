# MorphHDL compiler issues

Original report compiler: `e8f385721ad7756c4f7648b2dcb5500ee37af22a`.
Configuration: `preserveConstantVecs=true`, `preserveConstantLoops=true`,
`vecLayout=UnpackedArray`. Compiler sources and golden RTL are unchanged.

- [x] **CA-001 — retained constant Vec zero assignments lose contextual width.**
  `GenerateProgressiveTiming` and `GenerateDisplayControllerPhase1Top` fail in
  `PhaseNormalizeNodeInputs`: `laneX_1` through `laneX_4` (13 bits) and
  `laneY_1` through `laneY_4` (12 bits) receive zero-bit `U""` constants.
  Original source uses ordinary `laneX(n + 1) := 0` / `laneY(n + 1) := 0`.
  Evidence: `display-controller-morphhdl/evidence/constant-aggregates-20261002/generated/timing/first.log`
  and `generated/top/first.log` under the same evidence directory.
  DPI PPC1/PPC4 also fail: `x_1`, `y_1`, and `activeLaneRank_0` receive zero-bit
  literals (`generated/dpi1/first.log`, `generated/dpi4/first.log`).
- [x] **CA-002 — CSR retained Vec writes lose native condition evidence.**
  `GenerateCsrCommit` fails with
  `SPINAL-PARAMETERIZED-VERILOG-VEC-DYNAMIC-WRITE-CONTROL-UNSUPPORTED`:
  indexed write of Vec `snapshot` lost its exact captured native Bool conditions.
  The stack reaches `validateAuthoredStaticWrites` / `exactRetainedWriteConditions`.
  Evidence: `display-controller-morphhdl/evidence/constant-aggregates-20261002/generated/csr/first.log`.
- [x] **CA-003 — ordinary register Vec static writes fail retained evidence validation.**
  `GenerateClockResetCdc` fails with
  `SPINAL-PARAMETERIZED-VERILOG-VEC-STATIC-WRITE-EVIDENCE-MISMATCH`:
  static write of Vec `ctrl_release_stages` changed its exact whole-scalar native
  target, source or domain-valid element.
  The same diagnostic affects YCbCr422 `resolved`, YCbCr420 `allocated`, and
  CSC `valid`; it also prevents the DDR unpacker and RGB/CSC alignment hierarchy
  from generating. Evidence is in `generated/{cdc,422,420,csc,unpacker,alignment}/first.log`
  under `display-controller-morphhdl/evidence/constant-aggregates-20261002/`.
- [x] **CA-004 — status/counter child-width identity conflict, also with legacy options.**
  `GenerateStatusCounterInterrupt` fails with
  `SPINAL-PARAMETERIZED-VERILOG-DECLARATION-WIDTH-CONFLICT` on `counterDelta_2`:
  `[DELTA_WIDTH-1:0]` conflicts with `[(clog2(EVENT_PORTS, 0) + 32)-1:0]`.
  The standalone counter generates successfully. Both requested-options and
  legacy-options status generation fail, so this is a current compiler compatibility
  issue rather than a newly isolated constant-vector regression.
  Evidence: `generated/status/first.log` and `legacy-options-control/status.log`.


The retired `MorphWireAssignmentPasses` import was a project API migration issue;
it was removed from the top generator, consistent with the compiler retirement
record. It is not classified as an aggregate compiler defect.

Reproduction: compile/export the project classpath as recorded in the task evidence,
then run `python3 display-controller-morphhdl/script/verify_constant_aggregates.py generate <job>`.
Unit simulation results and golden comparisons are recorded separately; a generation
failure is not a simulation pass.

Control experiment (same compiler, same production DUT classes): temporary copies
of generation configuration used `preserveConstantVecs=false`,
`preserveConstantLoops=false`, `vecLayout=PackedVector`. Generation then passed
for top, timing, CSR, CDC, YCbCr422/420, CSC, alignment, unpacker and both DPI
profiles. Those outputs are diagnostic controls only, never requested candidates.
See `legacy-options-control/results.json` for commands and output/log hashes.

Separate unresolved source/tool compatibility finding: standalone pipeline generation
fails `PhaseCheckCrossClock` with both configurations. Its explicit dispatch child
clock is connected through the project `direct(...)` retained alias to the implicit
parent clock; the checker reports `dispatch/clk` versus `clk`. The complete top
generates with legacy options. This has not been established as a constant-vector
compiler defect; inspect `generated/pipeline/first.log` and
`legacy-options-control/pipeline.log` before deciding whether project clock binding
or compiler clock-alias handling needs correction.

## Repair work, 3 October 2026

Whole-branch qualification and the hourly monitor were stopped at the user's
request before these changes. The retained qualification evidence is historical;
focused repair checks do not constitute full qualification. Application sources,
compiler pins and golden RTL are unchanged.

The compiler repairs address:

- CA-001: leave anonymous literal wrappers eligible for native contextual sizing.
  Preserve their exact literal identity rather than pinning a zero-width carrier.
- CA-002: retain the complete authored scope path for static Vec writes through
  native switches, including enclosing When identities. Dynamic-write restrictions
  remain unchanged.
- CA-003: record each literal clone created by native simplification against its
  exact captured static assignment. Equal-valued foreign replacements remain
  rejected. Array publication also preserves the native `async_reg = "true"`
  attribute when it appears only on the first synchronizer stage: the aggregate
  carries the hint on all stages, alongside common user attributes. Other
  per-element attribute differences remain rejected.
- CA-004: after proving formal/actual equivalence over the complete owned domain,
  retain the parent connection expression for publication. This keeps parent
  localparam references and integer-helper spelling consistent with declarations.
- The separate clock-alias finding is confirmed: native top-level clock pulls
  use `ExternalDriverTag`. Synchrony traversal now follows the established exact
  `getSingleDriver` query, preserving the combinational-driver restriction.
  Unrelated-clock crossings remain rejected.
- Top-level replay exposed a further admission inconsistency: concrete parents
  with retained constant Vecs only in their children were incorrectly required
  to own symbolic parameters. Such parents now enter the existing hierarchy
  publisher through the same exact retained-child test used by its dispatcher.

`ConstantAggregateTests` adds ten regressions, including Verilog-2001 simulation,
Yosys synthesis, parameter override checks, and negative controls for foreign
clocks, fabricated literal replacements and incompatible attributes. Existing
structural identity, nested-write and formal-ownership suites are also exercised.
The nested unnamed-literal negative cases remain rejected by the nested terminal
identity check after static literal propagation becomes valid; only their exact
expected diagnostic was updated.

Focused validation completed on Scala 2.12.18 and 2.13.12: 106 distinct cases
per lane across `ConstantAggregateTests` (26), `StructuralIdentityAdversarialTests`,
`NamedFieldVecNestedWriteTests` and `NativeFormalOwnershipTests`. There are no
remaining failures, cancellations or skipped cases in these suites. The final
aggregate suite was rerun after completing the concrete-parent regression; logs
are `aggregate-final-both.log`, `focused-final.log` and `focused-both-scala.log`.
Earlier failed fixture/repair attempts are retained and are not passing receipts.

All 13 application generation replays passed and produced RTL: timing, CSR, CDC,
YCbCr422, YCbCr420, CSC, unpacker, alignment, DPI PPC1/PPC4, status, standalone
pipeline and full top. The final commands and output hashes are in
`final/application-results.json`. These are generation checks, not application
simulation or golden-equivalence claims. Focused compiler tests separately run
Icarus Verilog simulation and Yosys synthesis checks.

Native preservation, retained regression inventory and exact integration seals
are refreshed for the repaired source. Qualification, remote CI and hourly
monitoring remain stopped; no push or merge is part of this repair.

Durable repair evidence is under
`~/.local/state/morphhdl/compiler-issues-20261003/`. The application replay uses
unchanged precompiled DUT/generator classes with this checkout's compiler class
directories, and writes candidates outside the application repository.

## Application RTL retest, 3 October 2026

Retest compiler: `272c185840f6a21bb202088a4408ab74710fb2af`.
Fresh project Scala compilation and all 31 repeat-generation jobs pass with
`preserveConstantVecs=true`, `preserveConstantLoops=true`, `vecLayout=UnpackedArray`.
This includes all 13 previously failed generation jobs. Application unit validation
is recorded under `display-controller-morphhdl/evidence/aggregate-retest-20261003/`.
Each failed candidate case is rerun with the same existing unit testbench/profile
against the supplied `rtl.golden` using the application validation environment.

- [ ] **CA-005 — emitted ANSI port widths reference body-local aliases rejected during RTL compilation.**
  Generation succeeds deterministically, but the standalone CDC artifact uses
  `LIVE_RECORD_BITS`, `DDR_RECORD_BITS` and `COUNT_BITS` in its ANSI port list
  (lines 36, 42 and 46) and declares them as body `localparam integer` values
  only at lines 101–103. The simulator reports an undefined-variable diagnostic, followed
  by duplicate-declaration/port-mode errors. The unpacker has the same failure:
  `s_keep` references `BUS_BYTES` at line 41, declared in the body at line 77.
  The golden artifacts use the parameter expressions directly in those ports.
  The corresponding golden unit cases compile and pass; candidate compilation
  fails before simulation. This is not a failing golden testbench.
  Representative evidence includes the CDC and unpacker candidate compilation
  logs and the corresponding passing golden simulation logs  under the retest evidence directory. Raw artifacts are in
  `generated/cdc/first/DisplayControllerClockResetCdc.v` and
  `generated/unpacker/first/DisplayControllerDdrPixelUnpackerRgbNormalizer.v`.
  Exact compile commands, input hashes and profile parameters are recorded in
  each case's `result.json`. Keep width references valid at the ANSI declaration
  site without losing native parameter overrides. No emitted candidate or
  compiler source has been patched to conceal the failure.

  Final affected scope: **14 CDC cases and 9 standalone/reader-chain unpacker
  cases fail compilation; all 23 corresponding golden cases pass.** The unpacker
  fixed-product case and complete-top unit case pass with their concrete top
  hierarchy; that does not qualify the failing standalone parameterized artifacts.
  A diagnostic control on the same compiler and unchanged DUT Scala, using
  temporary generator copies with `preserveConstantVecs=false`,
  `preserveConstantLoops=false`, `vecLayout=PackedVector`, reproduces the same
  RTL compilation errors for `clock_reset_cdc-depth4-full` and
  `ddr_pixel_unpacker_rgb_normalizer-bus64_ppc1-full`. Thus this is an emitted-RTL
  compatibility issue beyond the unpacked-vector setting, not a recurrence of
  the repaired generation-time CA-001 through CA-004 diagnostics.
  Control commands, output hashes and unit logs are under
  `alias-control/`, driven by the generation and simulation control scripts in the retest evidence directory. Those outputs
  are diagnostic only and do not replace requested-options candidates.

Final application result: **31/31 generation jobs pass deterministically;
105/128 candidate simulation cases pass, 23 fail compilation, and 23/23 golden
replays pass.** Of the 90 previously generation-blocked cases, 67 now pass and
23 are blocked by CA-005 at Verilog compilation. No additional simulation-time
failure was observed. Timing and DPI PPC1/PPC4, the full top, standalone pipeline,
all 29 status/counter profiles and the remaining reconstruction/CSC cases pass.
No golden replay was needed for passing candidate cases.

See the [application retest report](display-controller-morphhdl/evidence/aggregate-retest-20261003/README.md),
[summary](display-controller-morphhdl/evidence/aggregate-retest-20261003/summary.json),
and [identity/evidence receipt](display-controller-morphhdl/evidence/aggregate-retest-20261003/receipt.json).
Production Scala/RTL, golden RTL, compiler sources and the user-selected compiler
pin were not changed during this retest.


### CA-005 — independent reproducer and suggested fix

The independent [reproducer package](display-controller-morphhdl/evidence/ca005-minimal-20261003/README.md)
uses no display-controller classes, Dan IP, clocks or registers. It includes
[Scala](display-controller-morphhdl/evidence/ca005-minimal-20261003/Ca005.scala),
[reduced failing Verilog](display-controller-morphhdl/evidence/ca005-minimal-20261003/broken.v),
[proposed corrected Verilog](display-controller-morphhdl/evidence/ca005-minimal-20261003/fixed.v),
and a [self-checking bench](display-controller-morphhdl/evidence/ca005-minimal-20261003/tb.v).
These are diagnostic examples, not changes to generated production RTL or a compiler patch.

**Minimal Scala trigger** (full runnable generator and both required compiler plugins
are included in the package):

```scala
class Ca005PortAlias(busBits: ElabInt) extends Component {
  val busBytes: ElabInt = busBits / 8
  val s_keep = in Bits(busBytes bits)
  val m_keep = out Bits(busBytes bits)
  val bodyWire = Bits(busBytes bits).dontSimplifyIt()
  bodyWire := s_keep
  m_keep := bodyWire
}
// Invoke through MorphVerilog with the existing options:
new Ca005PortAlias(HdlInt.param("BUS_LOG2_BYTES", 3, 2, 5).asElabInt.pow2 * 8)
```

The compiler emits `BUS_BYTES` in both port widths and defines it after the port
list. This reproduces with preservation enabled/UnpackedArray and disabled/PackedVector.
The arithmetic below is reduced for clarity; the unmodified generated reproducer
retains `(((1 << BUS_LOG2_BYTES) * 8) / 8)` as the localparam expression.

**Failing independent Verilog:**

```verilog
module Ca005PortAlias #(parameter integer BUS_LOG2_BYTES = 3) (
  input  wire [BUS_BYTES-1:0] s_keep,
  output wire [BUS_BYTES-1:0] m_keep
);
  localparam integer BUS_BYTES = (1 << BUS_LOG2_BYTES);
  wire [BUS_BYTES-1:0] bodyWire;
  assign bodyWire = s_keep;
  assign m_keep = bodyWire;
endmodule
```

The reported compiler diagnostic is `Undefined variable: 'BUS_BYTES'` in the
port list. This is a demonstrated target-tool compatibility failure; it is
not a claim that every Verilog tool rejects the pattern. The existing compiler
`NativeDerivedLocalParameterTests` previously exercised tools that accepted this pattern.

**Recommended output pattern:** expand derived aliases in ANSI port ranges,
while retaining named localparams for module-body declarations and expressions:

```verilog
module Ca005PortAlias #(parameter integer BUS_LOG2_BYTES = 3) (
  input  wire [(1 << BUS_LOG2_BYTES)-1:0] s_keep,
  output wire [(1 << BUS_LOG2_BYTES)-1:0] m_keep
);
  localparam integer BUS_BYTES = (1 << BUS_LOG2_BYTES);
  wire [BUS_BYTES-1:0] bodyWire;
  assign bodyWire = s_keep;
  assign m_keep = bodyWire;
endmodule
```

**Observed validation:** the reduced failing example and both actual MorphHDL
generated examples fail with the same diagnostic in Verilog and SystemVerilog
modes (six expected failures). The corrected example passes in both modes with
`BUS_LOG2_BYTES=2,3,4,5` (eight simulations). Every simulation also tests a separate
unoverridden default instance. Checks cover port widths 4/8/16/32, zeros, ones,
walking-one data and X/Z propagation. Exact commands, logs and hashes are in the
reproducer package results.
This initial evidence validates the proposed output pattern. The compiler repair
and its separate local evidence are recorded below.
CA-005 remains unchecked until compiler-generated application artifacts pass.

**Suggested compiler implementation:**

1. Make width publication aware of the *emitted declaration context* (ANSI port
   versus module body). Do not infer it from a printed identifier or merely from
   a signal also being used as a child port.
2. In ANSI ranges, recursively render the authoritative retained width expression
   in terms of visible public/formal parameters and literals, without module-body
   localparam references. Preserve exact ownership, formal-to-actual bindings,
   arithmetic, signedness and full parameter domains. Do not substitute default
   widths or parse/replace generated text. Nested aliases must expand transitively.
3. Retain current localparam naming/dependency ordering for body wires, registers,
   expressions and child instantiations where those declarations are in scope.
   Do not make derived widths independently overridable public parameters. Moving
   `localparam` into an ANSI parameter list would require a separately qualified
   SystemVerilog output policy; it is not the preferred Verilog-2001 fix here.
4. Relevant reviewed locations at compiler `272c185`: `MorphHdlSignedDeclarationPolicy.scala`
   `scalarRange` (lines 95–103) unconditionally prefers `NativeLocalParameters.reference`;
   `VerilogBase.scala` `emitType` (lines 348–351) supplies a generic `ScalarDeclaration`
   occurrence; `ComponentEmitterVerilog.scala` `emitArchitecture` (line 147) emits
   native localparams in the body. Also inspect
   `ExternalParameterizedVerilogNativeFallback.scala` `retained` (line 2893), which
   sets `publicationReference`, so subsequent range publication cannot reintroduce
   a body-only alias into a header. `NativeLocalParameters.scala` owns authoritative
   calculations, name lookup and dependency-ordered declarations. These are change
   points to review, not a claim that changing one line alone is sufficient.
5. Extend `NativeDerivedLocalParameterTests` with this independent reproducer and
   default/boundary overrides, nested aliases, child formals, input/output/inout
   ranges, signed/unsigned data and both combined/per-component publication.
   Update assertions that currently require `[TOTAL_BITS-1:0]` in the ANSI port
   list; continue asserting that the body localparam and its legitimate uses
   survive. Retain existing Icarus, Verilator and Yosys checks. Include both
   aggregate-option settings. Finally regenerate the unchanged CDC/unpacker
   Scala and rerun all 23 previously failing application cases, then the full
   128-case regression before closing CA-005.


### Compiler repair batch, 3 October 2026

The CA-005 compiler repair expands authoritative retained expressions at ANSI
port declaration sites. Module-body localparams, dependency order, signedness,
and child actual bindings remain intact. The regression covers nested aliases,
input/output/inout ranges, signed and unsigned ports, default and boundary
parameter overrides, and combined/per-component publication with aggregate
preservation enabled and disabled. Existing mutation controls now also check
body widths/local values, so moving port widths away from aliases cannot hide
a corrupted body localparam.

The unpacked-array repair emits direct element/bit concatenations for bounded
fixed-width reads, including multidimensional reads. Symbolic or large continuous
reads use a packed-view slice; symbolic procedural reads retain the helper to
preserve blocking-assignment ordering. Unused packed views are omitted.

Application replay also exposed a native unsigned arithmetic temporary retaining
its elaboration-default width. The compiler now publishes its retained symbolic
width and explicitly sizes unsigned operands from the same width authority.
Nested width selections preserve comparison precedence. A mask-validity
regression exercises overrides of 4, 8, 16 and 32 bytes; lint also checks the
smallest, default and largest widths.

All 31 unchanged application generators also pass twice with identical RTL hashes.

Local repair evidence: 66 focused tests (including signed-declaration checks) pass
on each supported Scala version. Another 16 existing expression/width-safety
regressions pass on each version, including behavioral comparisons against native
RTL at default and boundary widths. The lightweight aggregate/ANSI gate passes all
17 cases on both versions with no warning suppressions. These are local repair
results, not whole-branch qualification. CA-005 application closure still requires
the complete application regression; the historical checkbox is not a claim
that the unmodified failing compiler still remains current. Qualification remains paused while the current repair batch is completed. The
hourly continuation monitor remains enabled and must respect that pause.

### Typed-loop repair batch, 4 October 2026 (in progress)

The following findings were reported against compiler
`10bcd5011a617190661b6c149ca9b5e0a5850456`, with constant Vec and loop
preservation enabled. Full qualification is paused while this repair batch is
being developed. Earlier qualification receipts do not cover these changes.

- [ ] **CA-006: initialized register Vec writes through typed loops lose carrier
  identities.** Both native finite ranges with an enable inside the body and
  frontend typed ranges inside an enable reproduce the generation failure.
  The repair must retain storage, clock, reset, enable and hold behavior for
  every lane. The separate `asBools` byte-mask reproducer also requires exact
  carrier provenance; conversion must preserve writable bit-view semantics.
  Do not suppress residual-reference diagnostics or mark registers unset.
- [ ] **CA-007: full typed Vec-to-packed coverage reports a false latch.** The
  local repair retains exact assignment coverage for native latch checking and
  lowers captured witness writes to their symbolic slice. Constant and
  parameterized full-coverage tests pass locally; partial constant ranges and
  smaller parameter overrides still report a latch. Both supported Scala versions pass the focused coverage tests; complete
  branch qualification remains pending.
- [ ] **CA-008: automatic generate indices carry unconditional allocation
  suffixes.** The local implementation separates opaque/internal loop identity
  from final published names. Available automatic indices begin with `i`, `j`,
  `k`; independent sibling loops reuse a declaration, while enclosing loops
  reserve their active indices. Authored identifiers are reserved conservatively
  across the component to prevent collisions and shadowing. Explicit frontend
  index names remain unchanged. Generated block labels remain unchanged and
  distinct. Consequently, hierarchical references to automatic genvar implicit
  localparams intentionally change from names such as `copy_lanes_index_1_1`
  to `i`; generate-block paths retain their labels. Initial local tests cover
  determinism, sibling bounds/repeated labels, port collisions and supported
  nested loops in packed and unpacked layouts. Focused hierarchy and parameter-override checks also pass on both Scala
  versions. Application replay and qualification remain pending.

The additional multidimensional finding is now repaired for combinational
nested finite Vec selections. Exact alias-leaf identities connect each selected
coordinate to its original aggregate; no generated identifier establishes that
relationship. Two- and three-dimensional cases pass with packed/unpacked layouts,
independent row/column parameter overrides and singleton axes. Partial ranges
and read-only aliases do not authorize missing output drivers. A related
conditional-write defect was also repaired: written aliases are declared at the
component scope so native latch checking includes their enclosing conditions.
Conditional combinational writes without a complete assignment still fail.

- [ ] **CA-009: value-only native scalar formals prevent canonical child reuse
  across different actual defaults.** Two instances declaring
  `formalParam(actual, "VALUE", 0, 255)` and materializing that value with
  `ElabValue.uintLike` fail canonical schema comparison when parent defaults are
  4 and 9. Same-actual and same-default controls generate. The required result
  is one shared child definition with independent `.VALUE(A)` and `.VALUE(B)`
  bindings. Review definition-owned scalar formal schemas separately from
  instance witnesses; retain exact declaration ownership, domain compatibility
  and native body checks. Validation must include both controls, independent
  default/override simulation, mixed scalar/width formals, reversed construction
  order, deterministic generation and combined/per-component output, with
  negative domain/body cases. This issue is part of the current repair batch;
  local scalar-value controls now pass, including mixed scalar/width formals,
  but complete repair qualification remains pending.

  **Additional structural reproduction:** three instances of the same bit
  synchronizer use explicit native `SYNC_STAGES` formals with actuals 2, 3 and 4
  on separate clock/reset pairs. The fixed four-register carrier chain selects
  its output tap through typed structural conditions. Generation rejects three
  canonical schemas; the all-depth-2 control shares one definition. Normalizing
  child clock/reset port names does not resolve the failure. This requires a
  separate regression from scalar-value materialization, including each
  instance's reset and selected latency. The local repair now covers authenticated structural selectors as well as
  value-only formals. Mixed-depth clock/reset and latency tests pass on both
  Scala versions; complete repair qualification remains pending.

- [ ] **CA-010: explicit native width formals unnecessarily duplicate child
  definitions when instance defaults differ.** A child with `formalParam(actual,
  "WIDTH", 1, 64)`, matching parameterized input/output ports and bitwise inversion
  emits two definitions for actual defaults 8 and 16; equal defaults share one.
  Preserve independent `.WIDTH(A_WIDTH)` and `.WIDTH(B_WIDTH)` bindings while
  publishing one deterministic definition. Canonical comparison must use
  authenticated declaration identity and symbolic native port/body structure;
  class-name matching or emitted-text deduplication is insufficient. Keep
  incompatible domains, arithmetic, reset/clock semantics and non-formal
  construction choices distinct. Validate literal/derived actuals, overrides,
  instance order, repeat generation, nested modules, BlackBox width bindings,
  combined/per-component output and the two application video channels. This
  issue is included in the current repair batch. Local tests cover direct
  native width formals, internal declarations, nested children and BlackBox
  width bindings; qualification remains pending.

### Current local repair evidence, 4 October 2026

The repair batch passed 406 focused tests in 26 suites on each of Scala
2.12.18 and 2.13.12. Coverage includes library sharing and independent instance
bindings, native value sizing, typed-loop storage/coverage, nested Vec writes,
generate-index naming, aggregate publication, signedness, documentation and
adversarial ownership checks. Register mutation controls reject changed clocks,
reset identities, readback bridges, loop ownership and competing writers. The
refreshed lightweight aggregate/ANSI lint gate passes all 17 cases on each Scala
version without warning suppressions. A subsequent standalone-generation control
found that a root component has no parent binding to restore a normalized formal
default. Root definitions now retain their supplied defaults; shared child
schemas still use deterministic definition defaults with explicit instance
bindings. All 101 affected library/value/formal tests pass on both Scala versions,
including three new standalone scalar, width and mixed-formal cases with overrides.

Shrinking constant right shifts now publish exact slices of native declarations
or existing expression wrappers, including width-preserving cast inputs. Source
ranges use the existing retained-width authority. Signed operands explicitly
extend to their common symbolic width when overrides change operand sizes.
Tests cover Bits/UInt/SInt, fixed widths up to 71 bits, compound native temporaries,
signed comparisons, boundary overrides, strict lint, simulation and synthesis.

All seven original library-sharing minimal generation cases pass, including
mixed synchronizer depths and unequal scalar/width defaults. Fresh standalone
CDC output contains one shared video-channel definition; all 14 original CDC
simulation cases passed using unchanged application sources and benches. The
subsequent fresh top, CSR and 4:2:0 generation also passes. Focused CSR/top replay
passes the ten original cases whose commands compile, including the full
configuration-generation-wrap test.

Five original CSR protocol commands remain invalid: they pass parameters that
the protocol bench does not declare. Four additional local wrappers apply the
requested minimum/wide/middle/invalid-buffer DUT parameters, verify each binding,
and pass the unchanged protocol assertions and stimulus. These additional checks
do not turn the original invalid commands into passing receipts. The remaining
`RUN_GENERATION_WRAP` option is a full-bench stimulus option, not a DUT parameter;
it cannot be silently ignored or transferred to the protocol DUT.

Application lint of the 35 generated modules, with their matching external IP
and reachable dependencies, reports no width, missing/duplicate-module or
combinational-loop findings. Six module closures are warning-free. The retained
review inventory contains 100 unused-signal/bit locations and two open, unused
ready inputs (`y444_ready` and `y420_ready`) on disabled stream outputs. No
warnings were suppressed and the application lint run is not recorded as an
unqualified pass. Complete branch lint/coverage inventory, source-review receipts
and whole-branch qualification remain outstanding. The issue checkboxes stay
open until their closure evidence is complete.

### CA-011 and generic-application boundary repairs, 4 October 2026

- [ ] **CA-011: literal-width generic children reject explicitly sized internal
  parent carriers.** The compiler repair is implemented and locally verified;
  exact-head remote qualification remains pending. `connectionEvidence` now
  authenticates the child port's retained explicit formal actual. A positive,
  parameter-free constant actual admits a fixed parent carrier only when both
  widths match exactly. This does not enable the general concrete-internal
  adapter path. Varying symbolic actuals cannot borrow an untagged carrier's
  elaboration width; sliced/expression connections, foreign ownership and
  mismatched widths remain rejected. Explicit instance parameter bindings remain.

`NativeHierarchyWidthBoundaryTests` covers Bits/UInt/SInt inputs and outputs,
internal/direct connections, literal and symbolic actuals, independent mixed
8/16-bit instances, 5/23-bit overrides, one shared child definition, deterministic
repeat generation and combined/per-component publication. Negative tests retain
width, connection, ownership and complete-domain slice checks. Simulation and
synthesis cover both publication formats; strict filename-aware Verilog-2001 lint
uses per-component publication without warning suppression. All 21 cases pass on
both Scala versions. The three new contextual-UInt cases also pass on both.

The independent input and output reproducers supplied with CA-011 now generate
in both direct and internal modes and pass simulation, strict lint and synthesis.
Both previously passing resize reductions still generate. External application
sources, dependency pins and golden RTL were not changed.

The frozen generic application additionally exposed two distinct compiler causes:

- Untagged fixed child inputs followed parent-side drivers during width inference,
  importing a sibling's formal domain into a fixed child declaration. Inference
  now stops at that explicitly fixed input boundary after retained typed-width
  authority has been checked. An independent fixed-child reproducer demonstrates
  the failure; genuinely invalid slices in a child's own formal domain still fail.
- Native normalization padded unsized UInt literals to the elaboration witness.
  Common-width UInt arithmetic/comparisons now use an unsized literal's value
  requirement, while explicit literal widths and symbolic-fill authority remain
  intact. Direct constant UInt assignments publish their retained target width
  only after full-domain value-fit and exact assignment/owner checks. Tests cover
  widths 1/2/4/8/16, carry-preserving rounded averages, left/right literals,
  comparisons, strict lint, synthesis and invalid-width controls.

The complete frozen source matches the closure snapshot. Fresh generic top, CDC
and 4:2:0 generation each passes twice with identical output; all 17 selected
application profiles pass fresh simulation after the final sizing repair. The
43 reachable module lint runs have no width findings. Eight closures are clean;
108 unused-signal/bit locations, 14 mixed synchronous/asynchronous reset-use
locations and two open ready inputs remain recorded for application review.
This is not a claim of warning-free application lint or whole-branch qualification.

The affected hierarchy/formal/resize/signedness run passed 129 tests per Scala
version before the final constant-assignment lint repair. Its final affected
111-test set also passes on both versions (Scala 2.12 evidence spans the focused
reruns). An outdated retained-value spelling assertion was replaced with exact
localparam/slice assertions and simulation of the default and width overrides.
The retained inventory now contains 234 suites and 2,379 cases, including all 24
new cases; the targeted formalization workflow includes both new suites. Full
branch coverage review and exact-head targeted CI still precede full qualification.

### Qualification repair follow-up, 4 October 2026

The broader local run found and repaired four additional interactions: packed
named UInt constants now retain signed 32-bit elaboration arithmetic when reused
by derived expressions; child width formals use their exact captured branch
domain; split combinational processes retain only their visible event dependencies,
including pure condition-driver inputs; and native geometry assignments retain
their registered fill identity instead of being consumed by generic constant
publication. Exclusively consumed conditional-loop selection witnesses are removed
only after graph-identity and emitted-reference checks. No ownership, domain,
latch or dominance diagnostic is suppressed.

The completed local closure queue passed 16 focused tests on Scala 2.12, 723 tests
across 65 suites on Scala 2.13, and 27 frontend/plugin tests on each version.
Earlier Scala 2.12 repair reruns cover the other affected suites. The retained
catalog has 234 suites and 2,384 cases, with five new cases and no removed cases.
The final queue captured 146 successful lint commands and one expected unsigned
comparison rejection for an authored zero-bound comparison. Simulation and
equivalence negative controls remain failures of deliberately invalid candidates,
not waived positive cases. These results are local repair evidence, not remote
or whole-branch qualification.

The publication golden review regenerated the immutable `b459aa9b9` child
profiles and matched both historical checksums. After restoring only the three
child width defaults for comparison, all 41,775 packed-profile tokens and 42,301
field-profile tokens match. The intentional current change is declaration-owned
`WIDTH`, `TAG_WIDTH` and `COORD_WIDTH` defaults of one, plus alignment whitespace.
Parent defaults, actual bindings and hardware bodies are unchanged. All ten
current profiles reproduce byte-for-byte across independent generations. Only
the two child profiles in the reviewed contract change; the checker still binds
full RTL bytes and rejects incorrect defaults, actual bindings and body changes.

One exploratory event-dependency fixture using ordinary constant-folded shared
defaults reached a separate pre-existing structural ownership limitation. The
accepted event regression uses an explicitly retained typed zero value. This
follow-up does not claim that the ordinary shared-default form is repaired.

CI preflight also exposed an inherited source-review mismatch: the old sealed
59i reviewer cannot authenticate the later integrated compiler tree. The current
integration seal must authenticate the exact repaired source, while retained
historical source controls identify their immutable predecessor separately.
Remote targeted qualification remains pending this transition and a clean sealed
candidate. Full CI must wait for all applicable targeted gates on that candidate.

### CA-012–CA-016 repair evidence, 4 October 2026

All five reported compiler changes are implemented and pass focused local
validation. Exact-head remote qualification remains paused; these results do not
close whole-branch qualification. The display-controller reference repository,
its dependency pins and its generated artifacts were not modified.

- **CA-012:** typed unit-stride register tails retain exact range evidence and
  relocate only their selected lanes. Separately assigned prefix registers keep
  their native storage. Complete-domain bounds, whole-leaf writes, component and
  clock ownership, initialization, competing writers and reset lineage remain
  checked. Initialization and `ASYNC_REG` attributes survive publication.
- **CA-013:** anonymous retained Vecs receive stable fallback names before native
  pruning. Proven next-state tail writes receive exact native driver bridges;
  publication validates and consumes those identities. Missing lanes and
  overlapping writers still fail native validation. No no-driver diagnostic is
  disabled.
- **CA-014:** the symbolic library decoder uses a `ceil(log2(width))+1` stage
  Vec and a generate loop with index-owned power-of-two shifts. A proven constant
  selector reads its final stage. Width one has zero XOR iterations and a direct
  connection, including propagation of `Z`; the four-state regression explicitly
  checks that requested behavior. Array-dimension arithmetic receives portable
  helper lowering after unpacked publication. Formal-owned Vec schemas retain
  symbolic geometry and domains independently of instance witnesses; constant
  selector expressions remain part of schema comparison.
- **CA-015:** authenticated symbolic register initializers are emitted before
  native canonical comparison. Equivalent clocked children share a definition
  while reset-value, reset-polarity and attribute differences remain distinct.
- **CA-016:** parameter liveness follows required exact child-formal bindings
  through intermediate components. Public declarations, wrapper overrides and
  domain guards use the same retained inventory. Unused parameters do not leave
  undeclared guard references.

The final focused run passes **149 tests across 14 suites on each of Scala 2.12
and 2.13**. All twelve original independent generation modes pass against the
repaired compiler, including both sharing controls and the wrapped scalar case.
The new retained suites are `ShiftSubrangeAndGrayTests`,
`TypedStagePublicationSafetyTests`, `GrayStageSharingTests`,
`ClockedFormalForwardingTests` and `TransitiveScalarFormalTests`.

Coverage includes fixed/symbolic shift chains, depths 2/3/4/8, the 17-element CSR
case, enable stalls, asynchronous reset, packed/unpacked arrays, zero-stage Gray
decoding, Gray defaults 1/8/64 and overrides through 64, existing width-65 library
equivalence, mixed clocked widths 1/5/64, independent clocks, stopped-clock reset,
reordered instances, two wrapper levels, VALUE overrides 0/7/201/255, combined and
per-component publication, repeat generation and provenance mutation negatives.
The source inventory contains 239 suites and 2,455 cases: 71 added, none removed.
Both affected workflows include the new regression suites.

Simulation uses Icarus Verilog; synthesis and combinational-cycle checks use
Yosys. Strict Verilog-2001 lint uses Verilator `-Wall`, with no warning waiver.
For nontrivial Gray stage arrays, a scoped `split_var` configuration requests
per-element scheduling of the acyclic array; it does not disable `UNOPTFLAT` or
any other warning. Width-one lint runs without that directive. The reset-only
independent fixture intentionally leaves its data input unused; its simulation
and synthesis pass, and that input warning is recorded rather than hidden.
Filename-aware strict hierarchy lint uses per-component publication; combined
publication is compiled, simulated and synthesized separately.

### CA-017 repair evidence, 5 October 2026

- [x] **CA-017 — symbolic-width initialized register Vec reset lineage.**
  Compiler repair and focused local validation are complete; exact-head remote
  qualification remains a separate gate. The original independent `fixed`,
  `direct`, `next` and `full` probes all generate. Before this repair the same
  source passed only `fixed` on compiler `5376ef77d01d612d50c39cf3ae6dbd62665494af`.

Constant-initializer publication now authenticates and sizes each reset assignment
on its exact emitted native target before structural relocation replaces the
register alias with an indexed Vec leaf. The later storage-template, reset,
clock, bridge, selection and assignment-owner checks remain mandatory. Neither
initializer cardinality nor emitted-lineage validation is suppressed, and no
array-element assignment is accepted by an arbitrary text match.

`SymbolicVecInitializerTests` covers nested parameterized children, direct tail
shifts, next-state arrays and full-depth loads in packed and unpacked layouts.
Every variant simulates all nine WIDTH 1/5/64 × DEPTH 2/3/8 combinations with an
independent shift/load scoreboard, asynchronous reset, a stalled reset and enable
holds. Every profile also passes strict Verilog-2001 Verilator lint and Yosys
synthesis/checks. Repeat generation is byte-identical; symbolic zero fills and
`ASYNC_REG` attributes remain present. The six existing register-owner mutations
are additionally exercised with symbolic width. Existing initializer tests retain
missing/changed emitted-edge, copied-width, foreign-root and erased-authority
negative controls.

The affected local run passes **257 tests across 22 suites on each of Scala 2.12
and 2.13**, including earlier Vec/Gray, FIFO, CDC, hierarchy, signed initializer
and BOOT regressions. The structural-process workflow includes the new suite.
The retained catalog now contains 241 suites and 2,468 cases, with no prior cases
removed. The display-controller repository and its issue checklist were read-only
references; this repair does not claim a fresh product application retest.

### CA-018 and CA-019 repair evidence, 5 October 2026

- [x] **CA-018 — neutral compiler-generated helper identifiers.** Helper names
  are allocated from neutral stems, including `param_value`, structural aliases,
  Vec collision fallbacks and balanced-reduction stages. Existing local naming
  allocators retain collision handling and deterministic allocation. Internal
  expression tokens are lowered before publication; provenance comments are
  unchanged. Generated helper/hierarchy names intentionally change.
- [x] **CA-019 — public parameter-constant Vec reads.**
  `ElabVec.select(vector, index)` supports Bool, Bits, UInt and SInt while retaining
  native type, signedness, width and assignment identity. The complete parameter
  domain must prove the index in range. Independent index parameters and scalar
  forwarding through wrappers remain live; hardware UInt indexing is unchanged.
  See [the public API](parameter-constant-vec-selection.md).

The 17 selector cases cover packed/unpacked layouts, first/interior/last indices,
widths 1/5/64, depths 2/3/8, every reset-release depth 2..8, stopped-clock reset,
invalid domains, independent same-name roots, independent hardware indexing and
three-level scalar forwarding with independent overrides. The naming regression
checks collisions, repeated expressions, narrowing/widening and repeat generation.
Positive profiles include Icarus simulation, strict Verilator lint and Yosys
synthesis. A packed-read bridge now uses its retained exact carrier, avoiding an
unused-signal warning without removing identity checks. A consumed structural
static access cannot authorize an unrelated residual scalar carrier.

The final Scala 2.13 run passed 642 tests across 51 suites, followed by the FIFO
formal-helper generation control. Scala 2.12 passed the affected 145-test repair
run and final 70-test scalar/selector/provenance run, plus its FIFO helper control.
The retained inventory contains 243 suites and 2,486 cases; no previous cases
were removed. Container-only FIFO formal proofs were not run locally. These are
local repair results; fresh exact-head targeted and full qualification remain
separate gates. The application repository remains unchanged, and no fresh
product application retest is claimed.

### CA-020 repair evidence, 5 October 2026

- [x] **CA-020 — shared parameter-sized register Vec children with different
  depth actuals.** Compiler repair and local validation are complete. Exact-head
  remote qualification and fresh product application retesting are separate gates.

The unchanged independent probe reproduces the reported failure on `cff246cd3`:
`fixed` and `same` generate; `array`, `explicit` and `dynamic` reject canonical
schema matching. After repair, all five unchanged modes generate exactly one
child definition. Direct authenticated Vec-depth formals now participate in the
definition/actual separation already used for scalar formals. Their definition
default is normalized while each instance retains its exact actual binding.
Derived branch-owned dimensions keep their existing domain validation path.
Native mux wrappers also publish their authenticated symbolic width before native
module identity comparison, so mixed-width hardware-index reads share correctly.
Neither fix changes canonical schema checks, merges by class name, specializes
literal-depth instances or patches generated module names.

`StageDepthFormalSharingTests` adds 74 cases: 64 positive profiles combine
literal/symbolic actuals, reversed instance order, direct/wrapped children,
static/hardware-index selection, combined/per-component publication and
packed/unpacked layouts. Each asserts one shared generic child definition,
independent depth bindings and byte-identical repeat generation. Independent
clock scoreboards exercise widths 1/5/64, depths 2/3/4, non-default overrides,
enable stalls and asynchronous reset while a clock is stopped. Every profile
passes Icarus simulation and Yosys synthesis/checks; per-component profiles pass
strict Verilator lint. Ten negative profiles retain separate definitions for
different reset values, polarity, attributes, logic or domains, with both selectors.

All 252 affected tests across 13 suites pass on Scala 2.13. On Scala 2.12 the
178 existing cases passed in the affected run and all 74 new cases passed in the
final rerun. Existing FIFO CDC, scalar forwarding, register-initializer, Vec
selection, library sharing and ownership-negative coverage remains passing.
The display-controller repository was used only as a read-only reproducer source.

### CA-021 repair evidence, 5 October 2026

- [x] **CA-021 — ordinary-import parameter-constant Vec indexing.** Compiler
  repair and local validation are complete. Exact-head remote qualification
  remains a separate gate; the application repository remains read-only.

`import spinal.core._` now supplies `vector(index: ElabInt)` for scalar
Bool/Bits/UInt/SInt Vecs. The extension delegates to the existing authenticated
static selector, relocated unchanged from runtime to core. `ElabVec.select`
remains compatible. An extension preserves frontend `StructuralVecOps` overload
resolution without adding a core-to-runtime dependency, extracting a witness or
converting the selector to hardware UInt. Native Int/Range/UInt accesses and
structural HdlInt/GenIndex accesses retain their existing semantics.

All 155 affected tests across seven suites pass on Scala 2.12.18 and 2.13.12.
The new 33-case suite reruns the 17 established selector cases through the
shorthand and adds 16 byte-identical RTL comparisons with the compatibility API:
all four scalar types, fixed/symbolic dimensions and packed/unpacked publication,
with both core and StructuralVecOps imports. Simulation, strict Verilator lint
and Yosys synthesis/checks cover non-default widths 1/5/64 and depths 2/3/8;
reset-release scoreboards cover every depth 2..8 including stopped-clock reset.
Existing typed-loop, affine-access, mixed-depth sharing, initializer-lineage and
full-domain bounds rejection tests remain passing. The retained inventory now
contains 245 suites and 2,594 cases; no existing test was removed. Application
selection migrations and application-specific simulation were not performed.
