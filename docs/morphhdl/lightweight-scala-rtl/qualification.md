# Lightweight roadmap qualification preparation

PR: https://github.com/pysolvesemi/MorphHDL/pull/196
Branch: `work/lightweight-scala-rtl`; target: `parameterized-verilog`.
The roadmap is open. No targeted or full-CI passing result is claimed here.
Application Scala, delivered RTL, cleanup and compiler pins remain unchanged.

## Current regression enrollment

Compiled suite registration discovers 311 retained suites and 3,369 cases across
all retained projects. This is an inventory, not test execution. The two changed
projects add 42 suites and remove none. All additions are enrolled in the
lightweight workflow lanes. The cumulative source catalog authenticates 427
Scala and Java test-source files, including the Java callback-negative fixtures.

One existing case is renamed from “affine Vec selectors reject writes even when
the pair count is safe” to “affine Vec selectors reject writes to input carriers
even when the pair count is safe”. Valid affine writes are now supported; the
negative input/partial-coverage case still rejects with the exact coverage
marker. The rejection is retained, not removed. Three escape/rollback fixtures
now invoke explicit `ElabControl.selectSymbolic` so their deliberate host mutation
still reaches native scope checks instead of stopping at the new ordinary-syntax
host-effect diagnostic. The FIFO assertion adds emitted RTL to failure output.
Other existing registered cases are retained unchanged.

`Inventory.java` enumerates registrations from SBT's discovered suite names and
compiled runtime classpaths without executing tests. The raw discovery logs,
commands and per-project catalogs are under the durable `retained-inventory`
evidence directory recorded in `progress.json`. Frozen predecessor catalogs and
certificate commits are unchanged; the current successor catalog is extended.

## Targeted workflow plan

Workflow identities are from a complete 10-page GitHub workflow inventory (975
entries) retained as `ci-workflow-inventory.json`. Dispatch exact final source,
starting with source/provenance gates, then affected compiler/library lanes.
Do not reuse old-head success or dispatch a duplicate active/successful run.

| Workflow ID | Path under `.github/workflows/` | Affected boundary |
|---|---|---|
| 336665858 | `morphhdl-native-source-guard.yml` | Current source seal, regression inventory and provenance |
| 347814196 | `increment-53g-production-retirement.yml` | Reviewed plugin inventory and retired-feature rejection |
| 348180595 | `increment-54-typed-layering-ir.yml` | Typed plugin/API ownership and SBT/Mill dependencies |
| 337738060 | `morphhdl-external-structural-process.yml` | Staging, recursion, Vec lineage, register/control capture; new four-lane SBT/Mill matrix |
| 345463289 | `morphhdl-typed-elaboration-values.yml` | Parameters, typed conditions and factories |
| 336740926 | `morphhdl-external-symbolic-width.yml` | Formal/actual geometry and hierarchy boundaries |
| 351368935 | `increment-59c-named-field-vectors.yml` | Nested record/vector capacities and signed fields |
| 350495860 | `increment-59a-recursive-verilog-module.yml` | Existing bounded recursion compatibility |
| 350886879 | `increment-59b-reduce-balanced-tree.yml` | Existing Vec reduction topology compatibility |
| 351397918 | `increment-59f-callback-graphs.yml` | Callback ownership and native expression graphs |
| 351947632 | `increment-59g-register-bridges.yml` | Register bridge/reset/enable preservation |
| 351996892 | `increment-59h-nested-owners.yml` | Nested owner and child-boundary lineage |
| 351037194 | `increment-60e-signedness-boundaries.yml` | Signed reconstruction aliases and conversions |
| 348491710 | `increment-55-concrete-compatibility-audit.yml` | Ordinary concrete Scala and native compatibility |
| 348647472 | `increment-56-native-typed-library-surface.yml` | Public typed library surface |

Full CI follows only when all applicable targeted jobs and lanes pass on the
same final commit/tree. Changes during repair require fresh exact-head dispatch.
A suitable hourly continuation scheduler is unavailable in this session; no
monitor ID or active background monitoring is claimed. This limitation remains
explicit when dispatch begins. Questa is unavailable locally; local simulation
uses the pinned Icarus build, with separate Verilator/Yosys checks. Required
application/Questa and broader migration obligations are not marked complete.

### Native-source review refresh

The current native policy now enrolls the roadmap's scoped-index API, Bool
condition diagnostic, Vec port factories/forwarding, nested hierarchy adaptation,
recursive/callback annotations and opt-in BalancedTree component. Review retains
unchanged historical edit metadata and authenticates each revised native span;
no frozen certificate is rewritten. The generated native manifest covers 77
paths and 318 edits across seven roots. Local preservation, eight mutation
rejections, typed overlay, retirement, layering and both StreamFifoCC boundary
checks pass. These are preparation results, not remote qualification.


### Fresh local candidate checks — 15 October 2026

Compiler `a3274b97a2e27a8674f473571805e4ae8c6ef65f`, tree
`60838fd1f88820d09376db5c50cfbe2dbdc025f8`, passes the complete enrolled
lightweight set on Scala 2.12.18 and 2.13.12: 97 plugin cases plus 274 native
cases per version. The runner authenticates the retained catalog, requires every
registered case, rejects failed/skipped/missing reports and checks that source
identity remains unchanged. Mill independently passes all 97 plugin cases on
each version; this is not a fresh complete native Mill qualification claim.

The unchanged Rust cleaner was built with locked offline dependencies into an
isolated output directory. Each Scala version passes eleven library profiles in
both default and activation-preserving cleanup modes: 22 profiles, deterministic
repeated cleanup, independent four-state scoreboards, lint and synthesis. Four
cleaned combinational SAT proofs and four inverted-property rejection controls
also pass per Scala version. No application Scala, delivered RTL, cleaner source
or compiler pin was changed. These are local development results, not remote CI.

Exact commands, source/tool/input/output hashes and logs are retained beneath
`/home/kartik/.local/state/morphhdl/lightweight-scala-rtl/`:

- `enrolled-a3274b97-{212,213}/results.json` and `tools/`;
- `plugin-mill-a3274b97/results.json`;
- `cleanup-a3274b97-{212,213}/results.json` and archived replay scripts;
- `cleaned-formal-a3274b97-{212,213}/results.json`;
- `cleanup-build-receipt.json`;
- `provenance-a3274b97.json` (122 certificates, 34.4 seconds, zero historical programs executed);
- `application-baseline-f1ebf40b/manifest.json` (read-only pre-migration archive,
  80 application/build files and 870 delivery/test files; no fresh baseline
  generation or qualification is claimed).

The current CI enrollment installs the same authenticated simulator in affected
full-suite jobs as well as the dedicated lightweight matrix. Existing jobs,
matrices, test commands and timeouts are retained. The later full-CI phase must
include MorphHDL baseline workflow **332279971** (`morphhdl-baseline.yml`) and
MorphHDL Mill workflow **332476711** (`morphhdl-mill.yml`); they are not launched
before the affected targeted gates pass. The native source/provenance producer
**336665858** runs first, followed by the other affected targeted workflows.

The local Git credential cannot publish workflow edits. The connected GitHub app
successfully published the reviewed workflow bytes with automatic CI suppressed;
normal merges preserve that publication ancestry. No changed successor is
qualified by the local results above. Dispatch and receipts must identify the
final published candidate explicitly. Hourly scheduling remains unavailable.

### Targeted CI source-contract repairs (15 October 2026)

Candidate `f0ee2a168c0dc35140e7197d6639ffd78264dd2b` passed the native-source
producer, typed-value workflow and symbolic-width workflow. Layering run
38725475612 and named-field run 38725542339 failed before behavioral tests at
an obsolete `recordWholeAssignment` source assertion. The repaired guard checks
`captureWholeAssignment` and its exact native assignment/forwarding capture.
These results qualify only that historical candidate, not a successor.

Dispatcher 38725395750 stopped with HTTP 422 because Increment 59a lacked
`workflow_dispatch`. Increments 55 and 56 also lacked it. Their successor routing
now admits explicit dispatch while retaining original push/PR routing and every
behavioral job, matrix and timeout. Dispatch consumes the same-head provenance
producer and current integration seal, and verifies the frozen increment scope
against the authenticated integration basis. It does not compare the successor's
complete delta against an obsolete increment-only file list or replay historical
programs. Original increment routing retains its original scope check.

Local preflight also repaired the Stream structural-legality hook assertion and
59a's stale zero-only recursion/unfinished-59b assertions. The current recursion
contract checks every recursive child, the legal lower and upper bounds, and
strict decrease. Mutation controls remove each safety boundary and must fail.
The provenance consumer CLI revalidates its existing receipt without refetching
or producing one; missing/stale receipt rejection remains mandatory.

Fresh successor remote qualification remains required. Full CI, application
migration and all roadmap completion boxes remain pending. No hourly monitor is
active because a suitable scheduling capability is unavailable.

### Local repair of targeted behavioral failures (15 October 2026)

The retirement run 38725457946 on historical candidate
`f0ee2a168c0dc35140e7197d6639ffd78264dd2b` exposed two balanced-reduction
publication failures in both build systems and Scala versions. The pinned
Icarus elaborator rejected stage-net selections before their declarations.
The local pre-repair run of `spinal.core.internals.TypedBalancedReductionPublicationTests`
and `spinal.core.internals.TypedBalancedReductionCompositeTests` reproduced
both failures (26 passing, two failing cases). The emitter now places its
owned module-stage declarations after the module header and before native
consumers; nested reductions retain their exact lexical scope. Scalar,
composite and widening-composite publication use the same placement rule.
Operator bodies, register placement, signedness and topology are unchanged.

The complete local Mill 2.12 run on `ea7d5ae74c8137295054ffe2da5d613347d63b2d`
passed 97 plugin cases and 273 native cases, but failed one mixed-index case:
Mill's cached fork PATH selected system Icarus 12 despite the runner selecting
the pinned simulator. This is a failed run, not qualification. `build.mill` now
tracks caller PATH with `Task.Input`, retaining the selected JDK first. The
A/B/A regression changes PATH against the same cached outer/test module graph;
it passes on Scala 2.12.18 and 2.13.12. The two focused mixed-index cases pass
on Scala 2.12 with the corrected fork environment. Successor qualification is
still required.

Eight environment-gated formal/CDC cases were cancelled in the retirement
unit-suite jobs. They are not passes. Their applicable proof runs are enrolled
separately: primitive/Vec/StreamFifo proofs in the retirement/layering workflows,
and library/Axi4/CDC proofs in Increment 59a's pinned proof jobs. Closure must
verify those exact-head proof jobs, including mutation controls, independently
of the unit-suite totals.

The completed repair checks pass 66 selected balanced-reduction tests on each
Scala version under SBT (including nested ownership, widening, register bridges
and safety rejection). Mill 2.13 passes the 28 previously affected publication
cases plus both mixed-index cases; Mill 2.12 passes both mixed-index cases.
No selected case is cancelled or skipped. Logs and exact development-source
hashes are retained in the external `balanced-path-repair.json` receipt. These
are local repair results; remote qualification of the sealed successor remains
pending. Layering, retirement, recursive-module mutation guards and the
integration-seal mutation self-test also pass locally.

### Workflow graph repair after dispatch rejection (15 October 2026)

Candidate `6796f91c23bdf657a4afb225c1cbabed2b5522c0` passed source/provenance
run 38732910506. Six behavioral workflows started. The dispatcher then received
HTTP 422 for Increment 59a. Local actionlint 1.7.12 identified its dangling
`strict-tools` dependency: earlier retirement editing had removed the job header,
leaving the proof steps inside `focused-recursion`. The separate job is restored
byte-for-byte in parsed configuration from integration target `db54d01e`, with
both Scala lanes, its original timeout, proof commands and artifact retention.
No gate is removed from closure.

Three non-matrix source jobs also used `matrix.scala` in artifact names; they now
use the same effective literal `source` name. All 17 enrolled targeted/full-CI
workflow definitions pass actionlint locally. The source gate now runs the same
validation using a release archive pinned by SHA-256 before producing provenance.
Existing source tests, matrices and behavioral commands remain mandatory.
The six prior-head runs remain intact; they cannot qualify this repaired source.

The first restored-graph source run (38735247608, candidate `8859adf02`) passed
workflow validation and provenance production, then correctly rejected three
workflow bytes against the unchanged migration manifest. The manifest now records
the exact artifact-name substitutions and added validation step; its reversal
still reproduces the frozen baseline. All six migration tests, including
missing/duplicate/modified-hunk rejection, pass. Historical evidence is unchanged.

The `7eccc0d74e3247915a4efa350a35c952c450389e` targeted runs exposed JVM ABI
regressions on both supported Scala versions. Documentation storage on the
statement trait hierarchy introduced abstract getters/setters, and the direction
factory's builder override introduced an abstract trait setter. These failures
are compiler defects, not qualification timeouts. The repair keeps region notes
in elaboration-owned identity metadata and builder storage on concrete direction
objects. Existing trait method descriptors, source overloads and compatibility
checks remain intact. Focused documentation/port regressions include checks for
the new abstract requirements; the complete binary baseline comparison remains
mandatory. Local and remote successor results must be recorded before treating
this repair as qualified. Historical partial/interrupted logs are not passes.
