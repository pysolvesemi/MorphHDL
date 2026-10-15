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
