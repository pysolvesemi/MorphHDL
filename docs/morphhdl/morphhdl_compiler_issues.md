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
