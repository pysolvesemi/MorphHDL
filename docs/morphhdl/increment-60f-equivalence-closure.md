# Increment 60f — Equivalence, compatibility and tool-matrix closure

**Status:** 60f qualified on both Scala lanes. Parent Increment 60 and
Increment 60g remain open. Signed declarations and cast cleanup remain
explicitly opt-in. Final completion-head CI must pass before merge.

**Base:** `feca6b9d599d97af92ed9f6a8bc871ef008c395e` on
`parameterized-verilog`, which includes merged Increment 60e and the inherited
59b regression additions.

## Scope and unchanged behavior

This increment adds qualification scripts, a compatibility fixture writer,
five compatibility tests, a dedicated workflow and this record. It changes no
production implementation, library algorithm, default option, native-change
manifest or sealed semantic oracle. It does not enable the default rollout in
60g or expand unsupported signedness boundaries.

The source gate requires the recorded base to be an ancestor. It rejects
production changes in every tracked or untracked `src/main` path, including
native and MorphHDL implementation roots, and checks the existing 60a/60c/60d/60e writers
and checkers against their base bytes. The inherited source-scope restoration,
immutable baseline hashes, production-retirement, native-source preservation
and typed-overlay audits remain required.

The semantic-reference legs remain independent native `SpinalVerilog`
elaborations of the ordinary fixtures. Parameterized candidates are emitted
once and specialized through parameter overrides. A candidate is never used
to reconstruct its own reference arithmetic.

## Equivalence and defined input domains

The dedicated checker re-runs the complete inherited 60e boundary matrix and
60d/60c/60a qualification, rather than counting a previous CI result as a new
proof. It requires strict Verilog-2001 parsing, Icarus simulation, Verilator
lint, Yosys synthesis and solver-backed equivalence for the inherited proof
corpus.

| Inherited 60e fixture | Independent tuples |
| --- | ---: |
| Scalar boundaries, crossing resize domains and nested multiplication | 16 |
| Mixed Bundle leaves and packed transport | 4 |
| Static and dynamic signed Vec selection and writes | 16 |
| Vec hierarchy bridges, child ports and deduplication | 16 |
| Scalar hierarchy and typed external BlackBox boundaries | 8 |
| Stream and Flow payload pipelines | 4 |
| **Total** | **64** |

WIDTH and TARGET qualification values are 1, 5, 8 and 32; DEPTH values are
1, 3, 5 and 8 where applicable. These concrete qualification points do not
claim exhaustive coverage of every possible parameter value. Formal inputs
remain arbitrary within the inherited defined domains. Simulation retains
minimum negative, minus one, zero, one, maximum positive, overflow, truncation
and enabled sequential-update cases.

The inherited arithmetic wrapper maps an external zero divisor to one on both
legs. Every nonzero signed divisor remains reachable; division or remainder
by zero is not treated as defined. Vec wrappers apply the same legal-index
mapping on both legs. Channel induction retains its common single-clock
transition model; asynchronous reset is additionally exercised in simulation.
No proof pairs combinational temporaries merely because their names match.

All five inherited 60e boundary mutations and the three inherited 60d
arithmetic mutations remain mandatory. Existing unbounded inductive
equivalence gates, including the 60c/60d memory fixture and sealed 60a baseline,
remain unchanged.

## Supplementary arbitrary-state memory check

For each WIDTH in `{1, 5, 8, 32}`, the new closure miter compares the independently
generated native declaration fixture with its cleanup-enabled candidate for
**eight sequential steps**. This is a supplementary bounded SAT proof. It does
not replace or strengthen the scope of the inherited unbounded induction
gates.

Each DUT's initial memory and data-register state is independent and arbitrary.
The checker does not apply `-set-init-zero`. Only the miter's validity state is
initialized:

* A per-word written bit records completed writes.
* A synchronous memory result becomes comparable after an enabled read of a
  previously written word. A simultaneous first write and read still observes
  uninitialized old data under the fixture's read-first contract.
* The data register becomes comparable after its first enabled update.
* Result validity holds across disabled cycles.

The supplementary proof places no constraints on DUT inputs. A live mutation
replaces `memOut` with zero at each width and must produce a genuine SAT witness
within the same eight-step domain. This checks that the validity condition
does not hide all meaningful memory comparisons. JSON and VCD witnesses,
miter RTL, Yosys scripts and command results are retained.

Preprocessing does not merge corresponding registers across the DUTs. Separate
one-step controls remove the memory or register validity mask at every width;
all eight controls must produce counterexamples. These controls demonstrate
that arbitrary initial data has not silently become correlated.

## Exact 60a mutation and independent witness replay

The closure checker regenerates and verifies the sealed 60a artifacts, then
copies them into an isolated proof directory. It applies the original negative
mutation exactly once:

```verilog
assign negative_out = 8'h00;
```

The unmodified parameterized candidate must first prove equivalent to the
independent fixed reference for that output cone. The mutated candidate must
then produce a SAT counterexample, with nonempty JSON and VCD witnesses. The
signed eight-bit `left` input is arbitrary; constants on unrelated inputs only
prune unused memory and division cones.

The checker extracts the fully defined, nonzero `left` value from the solver's
JSON witness and replays it through Icarus Verilog. Replay must observe the
reference's eight-bit negation, the mutant's zero and a mismatch. A fabricated
input, parser failure, missing module or failed tool invocation cannot satisfy
this gate.

Solver result handling requires a zero process exit code and exactly one
expected proof or counterexample marker. Contradictory or duplicated markers,
`UNKNOWN`, timeouts, errors and absent witnesses fail qualification. The
classification self-test has two accepted cases and twelve rejection controls;
these controls test the harness and do not substitute for real solver runs.

## Compatibility and deterministic artifacts

The new writer uses the existing pure-arithmetic, declaration/memory and mixed
Bundle components. It alternates actual MorphHDL publication modes with native
Verilog and VHDL generation in the same session, for native widths 1, 5, 8 and
32.

Native Verilog and VHDL bytes must match their before-mode output when passed
declaration-only or cleanup options, and again after enabled MorphHDL
publication. This comparison includes the full generated bytes before any
header handling. VHDL checks establish emission compatibility; they do not
claim a new VHDL simulation or synthesis tool matrix.

The declaration-family VHDL leg uses the existing memory-free `Direct` fixture;
its Verilog leg retains the full declaration/memory fixture. Native VHDL rejects
that fixture's read-first memory. A fifth compatibility test requires the same
rejection diagnostic with ordinary, declaration-only and cleanup options, and
again with default options. This increment does not change that limitation.

For parameterized MorphHDL output, implicit opt-out, explicit opt-out and a
later default publication must be byte-identical. Disabling cleanup must
restore declaration-only output. Tests check signed declarations, reduced
cast counts, absence of nested signed casts, retained symbolic WIDTH and exact
unrelated unsigned port declarations, including widths and directions.

| Deterministic corpus | Files per generation and Scala lane |
| --- | ---: |
| Independent 60e boundary references and candidates | 70 |
| Inherited 60d/60c/60a corpus | 29 |
| Native Verilog/VHDL and MorphHDL compatibility modes | 114 |
| **Total** | **213** |

The 114 compatibility files comprise 96 native outputs and 18 MorphHDL outputs.
Only the anchored native generator header is removed from persisted native
files for cross-JVM and cross-Scala replay; RTL body bytes, identifiers, casts,
whitespace and body comments remain untouched. A separate test prevents this
normalization from deleting a body comment or an unanchored header-like block.

Both Scala lanes generate the complete 213-file corpus twice in fresh forked
JVMs. The artifact gate requires the exact 213 relative filenames, nonempty files and equal
SHA-256 inventories. The downstream cross-Scala job downloads both actual RTL
artifacts, recomputes their inventories, verifies the manifests and requires
the same source commit and identical file bytes across Scala 2.12.18 and
2.13.12. A successful upload or a comparison of manifest text alone is
insufficient. The 213-file count is an artifact inventory, not 213 independent
formal proof cases.

## All inherited Scala regressions

The dedicated workflow runs every inherited MorphHDL Scala test project and
the isolated pass-workspace suite in both supported Scala lanes. Minimum
inventories include the five new compatibility tests:

| Project | Minimum tests | Minimum suites |
| --- | ---: | ---: |
| ParamRTL | 234 | 23 |
| Frontend | 257 | 22 |
| Verilog backend | 148 | 21 |
| MorphHDL | 819 | 78 |
| Canonical IR | 32 | 2 |
| MorphHDL compiler plugin | 16 | 2 |
| Native Verilog phase plan | 5 | 1 |
| Isolated IR passes | 99 | 11 |
| **Total per Scala lane** | **1,610** | **160** |

The baseline before 60f has 1,506 runnable tests across the main projects when
all formal gates are enabled. Adding five compatibility tests and 99 pass
tests yields 1,610. These are required minima, not recorded 60f results.
The inventory checker requires the exact 160 named suite reports, consistent
testcase counts and zero failures, errors or skipped/canceled tests.

All seven existing optional proof flags are set to `1`: StreamFifo formal,
StreamFifoCC formal, StreamFifoCC CDC, typed Vec formal, typed primitive closure,
native library migration and native AXI4 formal. This makes the eight tests
that ordinary baseline jobs cancel part of the new mandatory regression run.
The job requires Python, Icarus, vvp, Verilator, Yosys, SymbiYosys, Yices and
Yosys ABC before executing tests. `SBT_TEST_PARALLEL=0` keeps retained proof
workspaces deterministic.

Each of the seven proof workspaces is retained separately. XML reports, test
logs, the test inventory, source commit and the StreamWidthAdapter temporary
formal/mutation directories are uploaded as evidence. Baseline and Mill remain
separate required CI checks; their ordinary success does not substitute for
the no-skip 60f regression inventory.

## Workflow and completion requirements

`.github/workflows/increment-60f-equivalence-closure.yml` checks out the exact
PR head or pushed commit, using `ghcr.io/spinalhdl/docker:v1.2.0`. Both Scala
qualification jobs and both full-regression jobs must succeed before the
cross-Scala comparison can run. The workflow retains qualification, source,
tool, RTL, solver and regression artifacts for 30 days, including failure
evidence from completed steps.

The complete entry point is:

```sh
python3 morphhdl/scripts/check-increment-60f-equivalence-closure.py target/increment-60f/a
```

The workflow first creates that directory through the inherited boundary and
pure-arithmetic writers and the new compatibility writer. The checker's
`--closure-only` diagnostic mode runs only the supplementary proofs and labels
its result accordingly; it cannot close 60f. Likewise `--self-test` and
`--source-only` do not establish RTL qualification.

The completion transition requires successful exact-head qualification,
no-skip regression inventories in both Scala lanes, downloaded cross-Scala
byte comparison, native audits, required baseline/Mill CI and review. The
qualified commit, PR, workflow links and evidence must be recorded before
marking 60f complete. After merge, inspect push workflows for the actual merge
commit. Post-merge results from 60e do not establish 60f success.

## Qualified implementation and retained evidence

Implementation head: `e93850ae6de855be375d8773eec75ae66723aa4f` in
[PR #161](https://github.com/pysolvesemi/MorphHDL/pull/161). Its tree is
`e9c3c6209abd34e4a6e643effb94806568f94749`.

[Dedicated qualification run 34011149044](https://github.com/pysolvesemi/MorphHDL/actions/runs/34011149044)
passed both Scala 2.12.18 and 2.13.12 qualification jobs, both full-regression
jobs and the downloaded cross-Scala comparison. Each lane passed exactly
1,610 tests across all 160 named suites, with zero failures, errors or skips.
All 213 generated RTL files reproduced byte-for-byte in fresh JVMs and across
compiler lanes. The cross-Scala log confirms successful artifact downloads,
manifest validation and matching actual RTL bytes at the implementation head.

The retained logs confirm all 64 inherited boundary tuples, inherited 60d/60c/60a
tool and induction gates, all inherited mutations, the exact 60a SAT witness and
Icarus replay, and all four supplementary memory widths. Each width passed its
eight-step validity proof, live memory-output mutation and separate arbitrary
initial memory/register counterexamples. Source and native-change audits passed.

[Baseline run 34011151123](https://github.com/pysolvesemi/MorphHDL/actions/runs/34011151123)
passed both Scala jobs and strict Verilog-2001 contracts.
[Mill run 34011150989](https://github.com/pysolvesemi/MorphHDL/actions/runs/34011150989)
passed both Scala lanes. One inherited 60c job initially failed during sbt
download with Maven HTTP 502, before compilation or tests; its targeted retry
passed. This infrastructure failure is not counted as a proof result.

GitHub retains these implementation-run artifacts:

| Artifact | ID | SHA-256 reported by GitHub |
| --- | --- | --- |
| Qualification, Scala 2.12.18 | 9982575669 | `102f133869fd5f30d75e76040f57a67dd85bef21dcbc8c6e1c7b4cf18b3e9e2d` |
| Qualification, Scala 2.13.12 | 9982572471 | `b5a08ceeeafbc874af82878213f6e48472f9f6c2153716e4364a81db619e7e0b` |
| Regressions, Scala 2.12.18 | 9982655593 | `5287e0511945d568bffd52ac908d71ee774e148a2a7903155a78ab96b45ab959` |
| Regressions, Scala 2.13.12 | 9982654064 | `9eadc4bead1425dc12cb6100ebc2f9b80f6e33f45d6bf448b1e3ffb7b5ffc624` |
| RTL, Scala 2.12.18 | 9982575036 | `3c1cdae6d709c38701fcba2962aad614453196e5f027adaac9e2a6c2c6b62f6f` |
| RTL, Scala 2.13.12 | 9982571957 | `39cc421cf2c568f953f7552f2bd4e7e87c2dc3e2230e6bc1c277b605506c97b6` |

This completion transition changes only this record and the child roadmap.
Executable source, proof settings, fixture bytes and tool pins are unchanged.
Fresh checks of the final documentation commit must pass before merge; their
results and the subsequent exact-merge push CI are recorded on PR #161. No
60f post-merge result is claimed by this pre-merge record.

## Verified prerequisite: 60e post-merge CI

For prerequisite information only, [PR #160](https://github.com/pysolvesemi/MorphHDL/pull/160)
merged 60e at `dc8cab41cf3fd41b026ba7359f30cb596b14d015` on September 5, 2026
at 20:07:22 UTC. Its exact-merge push workflows completed with 34 successes and
one scope skip. All 164 check runs completed: 98 succeeded and 66 were skipped,
with no failed, canceled or pending checks.

* [60e dedicated post-merge qualification](https://github.com/pysolvesemi/MorphHDL/actions/runs/33989154117)
  passed both Scala lanes, each with 139 non-skipped tests, 64 independent
  native-reference tuples and the inherited mutation/qualification gates.
* [Baseline post-merge CI](https://github.com/pysolvesemi/MorphHDL/actions/runs/33989154060)
  passed both Scala jobs and strict Verilog-2001 contracts.
* [Mill post-merge CI](https://github.com/pysolvesemi/MorphHDL/actions/runs/33989154098)
  passed both Scala lanes.

Those checks finished by 20:41:09 UTC. The legacy combined-status endpoint
contained no status entries; its default `pending` value was not evidence of
unfinished Actions work. The completed push workflows and paginated check-run
results establish the prerequisite status above.

## Follow-on WA-07a CI compatibility

The historical 60f qualification above remains tied to its recorded source.
The workflow also runs for changes under `morphhdl-passes/`, so its source and
test inventory gates must recognize the reviewed WA-07a addition without
accepting arbitrary production changes or test suites.

The source gate retains the production-zero check from the fixed pre-60f base
to the qualified 60f commit. For the current checkout it accepts either that
unchanged production profile or the exact three-file WA-07a production delta
registered from `f6646f574a1bc2c16050e7c27e93b86523af3bd8`:

- `PassContracts.scala`
- `WireAliasPassPipeline.scala`
- `ConstantOperandSimplificationPass.scala`

The checker pins each complete path and source hash. Missing files, changed
hashes and any other tracked or untracked production change are rejected.
The sealed native writers/checkers and inherited native audits still run.

The regression inventory follows the validated source profile. The historical
profile retains its exact 11 pass suites and minimum 99 non-skipped tests.
The WA-07a profile requires all 14 pass suites and at least 123 non-skipped tests,
including `ConstantOperandSimplificationPassSpec`, `ConstantOperandFourStateSpec`
and `ConstantOperandFixedPointSpec`. Other project inventories are unchanged.
Missing, substituted, duplicated or unexpected suites, failed/skipped tests and
stale success records remain errors.

This compatibility registration does not qualify WA-07a. Its dedicated workflow
must still prove every candidate against the common pre-pass reference across
all 512 bindings, repeat independently and validate the complete shard union.
The 60f signedness proofs, domains, mutation controls, repeated RTL generation
and cross-Scala comparison remain required by their existing workflow.
