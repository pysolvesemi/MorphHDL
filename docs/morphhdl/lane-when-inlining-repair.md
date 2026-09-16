# Lane-bit and generated-condition inlining repair

## Status and source

This is the authorized follow-up to WA-10, on the existing
`agent/lane-when-expression-inlining` branch, targeting `parameterized-verilog`.
The implementation is now in the production Scala files, not a development
recipe. The temporary recipe and source-export workflows have been retired.
The follow-up remains **open and unmerged** until its exact published source,
review seals, SBT/Mill builds and all applicable inherited workflows qualify.
No old passing head or source-export workflow qualifies this change.

Reported compiler baseline: `3b547ae5622ae212c17f6e67cc96924127a46a41`.
Recovered development parent: `5af29019efc4ae368dcecaa315502befbb2282aa`.

PR: [#187](https://github.com/pysolvesemi/MorphHDL/pull/187), open and draft.
The six production files were published as
`519d3995ededc61ff101125cd57b951b883b4c72`; the full standalone regression
sources and permanent qualification workflow were published as
`1670d24b1f0d767359338490b4d40ad11d1b70e9`. All six production blobs and
recovered test sources match the previously tested local commit
`52ffd62329ea558cb09fb316e2f453f25ee02e9e`. A green source-recovery workflow
is not a compiler qualification result.
The baseline production inventory was verified unchanged before reproduction.
Only standalone test fixtures were added for supplemental baseline generation.
Local work uses the real production `MorphVerilog` API with both required
compiler plugins and Scala 2.12.18 / 2.13.12; direct compiler execution is not
represented as an SBT, Mill, binary-compatibility or complete CI result.

## Established causes

### Disjoint lane bits

The four-bit `laneDe` and `laneFrameEnd` targets have the exact singleton
`noBackendCombMerge` tag, attached by symbolic resize publication to preserve
process separation. The original target guard required `isEmptyOfTag`, so
`eligibleSelection` rejected the receiver before evaluating its four disjoint
fixed ranges. The actual unsigned resize nodes are 13-to-16 / 12-to-16 bits,
have no symbolic resize width and each has one occurrence. Their temporary
Verilog wires are created by emission, not native declarations to be removed
by the earlier wire-expression pass.

The repair permits **only this exact process-layout tag** at the target boundary.
It does not remove the tag, alter a receiver or process, or bypass the existing
width, signedness, overlap, dynamic-selection or symbolic-width proof. All
other target tags remain fenced. The same proof now handles both the less-than
`laneDe` case and the 16-bit modular subtraction/equality `laneFrameEnd` case.

### Generated `when` predicates

The actual source-location condition carriers have generated/removable naming
provenance, a full driver and `isTypeNode=true`. The original candidate filter
excluded them before receiver analysis. The old receiver path then accepted
only assignment statements, not `WhenStatement` predicates. A downstream
emitter stage also wrapped conditions printed in more than one process.

The repair admits a type node only as a generated/unnamed Boolean condition
candidate. It captures and validates every actual Boolean receiver expression,
uses the existing canonical value-substitution proof, and separately proves
native lexical dominance and combinational-process safety. A projection used
to prove Boolean values is not claimed to model procedural execution; the
independent native proof retains every process, driver kind and control scope.
Fresh operator copies replace exact identity references in condition/RHS fields.
The declaration and its driver are removed only after the counted references
have all been replaced and no dangling reads remain.

Input ports are validated in their component declaration/read scope, not their
parent component's assignment-root scope. Nested predicates must dominate all
receivers through the actual `when` scope chain. Transitive data and control
dependencies are checked against a conservative closure of potentially merged
blocking processes. A dependency written by such a process retains its carrier.

Native liveness propagation eventually marks ordinary live signals vital.
A read-only, per-generation identity capture runs after application transforms
and before the first native liveness sweep, distinguishing explicit vital intent
from inferred reachability. Unobserved/new identities fail closed. Explicit
vital, frozen, debug, unknown-tag, keep, dontSimplify and explicit user-name
controls retain their signals; an allocated `when_*` spelling grants no authority.

The Verilog emitter declines only its repeated-condition sharing request when
its complete typed expression proof and bounded duplication proof succeed.
Other mandatory wrapper reasons remain authoritative. There is no new public
switch and no name-pattern classification or generated-text transformation.

## Actual artifacts

These files are actual generated outputs, not illustrative hand-written RTL:

- [Baseline lane reproduction](evidence/lane-when-inlining/before/lane.v)
  and [repaired lane reproduction](evidence/lane-when-inlining/after/lane.v).
- [Baseline condition coverage](evidence/lane-when-inlining/before/conditions.v)
  and [repaired condition coverage](evidence/lane-when-inlining/after/conditions.v).
- [Baseline receiver coverage](evidence/lane-when-inlining/before/receivers.v)
  and [repaired receiver coverage](evidence/lane-when-inlining/after/receivers.v).

[Artifact hashes](evidence/lane-when-inlining/SHA256SUMS) accompany the outputs.
The reproduction source is
[`GenerateLaneExpressionExample.scala`](../../morphhdl/src/test/scala/morphhdl/GenerateLaneExpressionExample.scala).
It retains the supplied widths, four disjoint bit assignments, five-coordinate
lookahead and symbolic output resize. Package `morphhdl` is the repository
adaptation; no application workaround or fallback generator is used.

For example, the actual repaired lane emits:

```verilog
laneDe[0] = ((io_running && ({3'd0, laneX_0} < io_hActive)) && ({4'd0, laneY_0} < io_vActive));
laneFrameEnd[0] = ((io_running && ({3'd0, laneX_0} == (io_hActive - 16'h0001))) && ({4'd0, laneY_0} == (io_vActive - 16'h0001)));
```

The split coordinate processes emit the same predicate directly:

```verilog
if((laneX_0 == (io_hTotal - 13'h0001))) begin
```

The nested Y condition retains its independent 12-bit subtraction:

```verilog
if((laneY_0 == (io_vTotal - 12'h001))) begin
```

All four lane pairs are optimized. Original generated predicate declarations,
assignments and references disappear; the original process count is retained.
The real four-bit lane vectors and parameter-dependent output resize remain.

## Executable commands

With the checkout's SBT toolchain:

```sh
sbt '++2.12.18' \
  'morph/Test/runMain morphhdl.GenerateLaneExpressionExample /tmp/lane/plain' \
  'morph/Test/runMain morphhdl.LaneInliningRegressionWriter /tmp/lane/after'
sbt '++2.13.12' \
  'morph/Test/runMain morphhdl.LaneInliningRegressionWriter /tmp/lane/after213'
sbt '++2.12.18' \
  'core/Test/testOnly spinal.core.internals.VerilogEmitterExpressionInliningTests' \
  'morph/Test/testOnly morphhdl.LaneWhenInliningRegressionTests spinal.core.MorphVerilogExpressionInliningTests morphhdl.examples.NativeWireExpressionCodecTests spinal.core.BooleanWidthNormalizationTests'
```

Repeat the test command for 2.13.12. The SBT project ID is **`morph`**, not
`morphhdl`. The committed workflow independently generates `/tmp/lane/before`
from the immutable compiler baseline, adding only the four standalone fixture
files. It then runs:

```sh
python3 morphhdl/scripts/check-lane-when-inlining.py \
  --before /tmp/lane/before --after /tmp/lane/after \
  --compare-scala /tmp/lane/after213 --output /tmp/lane/validation
```

The checker fails on missing tools, timeouts, incomplete artifacts, changed
disabled output, unqualified syntax, missing completion markers or undetected
mutations. The read-only trace must also produce byte-identical RTL to the
unobserved public entry point. The existing production enable/disable flag is
used for every mode.

## Local validation and proof boundary

The five focused/inherited Scala suites executed 66 tests per Scala version
with no failed, canceled or ignored tests. This includes 17 new production
regressions and existing emitter, wire-codec, symbolic-width and four-state
safety controls. The standalone artifact matrix has 18 files per Scala version:
three topologies, three flag modes and two generations. The entire matrix is
byte-identical across Scala versions. Disabled RTL matches the independently
built baseline exactly.

Each RTL validation run checks 18 strict Verilog-2001 compilations, 18 Verilator
lint cases, six synthesis/port-width checks and six combinational equivalence
comparisons. Both `PPC4=0` and `PPC4=1` elaborate the same artifact to one and
four bits. Independent Icarus benches exercise 2,833 original-lane vectors,
6,412 condition cases and 10,510 receiver cases per mode, including all lanes,
zero/max dimensions and totals, arithmetic underflow, line/frame wrap, priority,
shared receivers and X/Z behavior. The baseline is simulated with the same
independent benches, separately from enabled/default/disabled candidate modes.

Five actual generated-RTL mutations must fail the simulation oracle: nonzero
extension fill, wrong active subtraction, wrong coordinate subtraction, wrong
priority predicate and case-equality substituted for ordinary equality. The
first four also require explicit SAT counterexamples with mismatch `trigger=1`.
The case-equality mutation is simulation-only because the SAT model is two-state.

An early local proof harness flattened modules before lowering their procedural
drivers, producing a vacuous success even for a faulty design. Those early
formal runs are **invalidated**. The final checker lowers each independently
elaborated design with `proc`, isolates designs with `design -stash`, then
constructs the miter and requires the negative controls above. No earlier
unqualified formal receipt should be used as completion evidence.

## Conservative limits and remaining gates

Condition substitution remains restricted to fixed-width, pure Boolean
predicates with local unsigned/Bool/Bits leaves and supported Boolean receivers.
It does not qualify arbitrary signed/symbolic predicates, sequential receiver
processes, assertions, switch contexts, hierarchy crossings or procedural
feedback. Source trees are bounded at 64 nodes, native receiver occurrences at
32, rendered replacement work at 256, dependency closure at 256 and lexical
walks at 128. Budget exhaustion retains a carrier with a concrete diagnostic.
Emitter duplication has a separate 64-node / 32-copy / 256-work safeguard.

Overlapping writes, dynamic selections, protected targets and genuinely shared
unproven expression identities retain fences. A Bits range constructed through
`CastBoolToBits` remains outside the existing emitter planner's approved node
set; it is not relabeled as an optimized range. A separate disjoint UInt range
regression proves the supported range case with the process-layout tag intact.

The successor review records only the three changed native emitter files;
upstream baseline roots and all other native approval entries remain unchanged.
The cumulative outer seal still authenticates the full HEAD/index/worktree and
immutable source ancestry before any older audit receives its historical view.
The new exact scope inventory names the 18 implementation-introduction paths
and the explicit review-infrastructure paths; it does not authorize source
through a branch wildcard or by updating a test count alone.

The inherited catalog remains exactly 2,012 tests in 197 suites. Complete
lane/when successor enrollment adds only the 17-test production regression
suite, yielding **2,029 tests in 198 suites**; partial enrollment or absent
predecessors fails. The unchanged inherited catalog self-test freshly passed
all 4,089 rejection controls. The full pass workflow now runs on this exact
repair branch instead of silently skipping it; every production lane and all
16 full-domain formal shards are retained.

The emitted-RTL checker now clears old success/counterexample receipts before
preflight or solver execution. A missing artifact or failed run cannot leave a
stale success receipt. This changes evidence handling, not compiler output or
the independent oracle. A real subprocess regression exercises preflight failure.

Both recovered direct-compiler lanes were freshly rebuilt and the 66-test
focused/inherited suite passed on Scala 2.12.18 and 2.13.12. These results are
not SBT/Mill or exact-final-GitHub-head qualification. Full repository CI,
SBT/Mill and binary compatibility remain open until their actual final-head
runs pass. No target branch update or merge is authorized by a partial result.

## Failed-run repair, 2026-09-16 (qualification pending)

The exact `a970cc890b07fbe2ccf708b1b431caaf78a4e17d` receipts identify two failing workflow families: lane qualification (`35053364996`, and duplicate push run `35053360769`) and Increment 60g (`35053365116`).

The lane report-ABI job lost three JVM constructors: `NamedWireExpressionNativePhase(boolean)`, `UnnamedWireExpressionNativePhase()`, and `ProductionWireAssignmentPhase()`. Explicit delegating constructors restore those descriptors without changing the new parameter-aware constructor paths. The existing lane regression now resolves and instantiates all three legacy signatures reflectively. An uncaptured legacy production phase keeps condition inlining fail-closed and allocates its own source-intent state.

The 59h mutation control reached an earlier native-hook authentication guard. The mutated `VerilogBase.scala` was correctly rejected, but the test expected a historical-projection diagnostic. The repair requires the precise current-source rejection, including its file path and a nonzero exit; all other negative cases and production guards remain unchanged. The 98-path signature inventory and 17 lane tests are retained, with additive linkage assertions.

This first repair is based on the failed source head. It does not overwrite or roll back target commit `27af65abbee0d2334d6be7a6e4e2408b8af32fd9` (merged Increment 61). Its integration and associated audit reconciliation remain outstanding before merge. Do not mark this PR complete or merge it. Start only the repaired-head lane and Increment 60g workflows; full CI is prohibited until those targeted workflows pass on the intended final candidate. Old workflow retries still check the old source and do not qualify this repair.

The existing 60g workflow also subscribes to pushes on this exact repair branch, alongside the existing lane workflow. This is additive routing only: no job, assertion, baseline, matrix, trigger on other branches, or qualification gate is disabled. With the separately observed target merge conflict still outstanding, these are the only push workflows matching this branch; resolve target integration only after their repaired-head qualification succeeds, then qualify the integrated source with full CI. Never use an old-head rerun as evidence for these changed sources.

Local checks on the failure repair passed the 177-file source seal, native-source preservation, 19 lane review tests (including 41 safety mutations, 30 complete-gate rejection controls, and 12 job-context rejection controls), and the formal-validator self-tests. The actual previously failing 59h source suite passed two positives and all 28 exact rejections, with unchanged 59c and pre-rollout 59h historical controls separately replayed. These source checks do not claim Scala/JVM, HDL simulation, synthesis, or hosted CI qualification.
