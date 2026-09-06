# Increment 59e — Composite and nested Bundle/Vec reductions

## Status

Standalone 59e was qualified on published commit
`4f323bda5ad130d4d1e8e7ee9b2f7cf5904bc9ef`, incorporating integration commit
`5a669d32095ee722c313bd069b771e7c350a1f81`. The local qualification commit
`104e8fd97ab29c1a188393efca2dad0c25930bfe` has the identical Git tree
`92d50f48b337d62c178ea4f1831a27f94d4e7e5f`. All 36 applicable workflow runs on
that publication passed; retired workflow invocations are not proof evidence.

The subsequent standalone completion publication
`ba479403017d762fb1cf8227fa2f130d992bee38` incorporated integration commit
`3e80cef258ddfdd6ce74819a2fbf200a8d2c5a64`, reconciling its WA-07a inventory
registration with the historical/current source checks. This reconciliation
changed no production code, Scala fixture, generated-artifact writer or 59e
proof harness. All 36 applicable workflow runs on `ba479403` also passed,
including both 59e Scala lanes and the complete inherited 60f workflow.

Before PR #162 could merge, the integration branch advanced to completed 59f
at `c85659a20d428dd58cc6116c12c8b24418c37722`. The combined production source
qualified locally is `da47a78faf60217c795e47e1e8c598e2fccb61a0`, with tree
`e5f83db999501e1941702d259c694bddd6e94a37`. It includes the reviewed integration
repair for exact assignment-domain unsigned resize padding described below.
Both supported Scala versions have passed 358 targeted tests across 27 suites,
with zero failures, errors or skips. Complete independent A/B generation and all 65 composite, 64 callback and
32 inherited hardware cases passed on each Scala version, including all nine
actual RTL mutation counterexamples. The three resulting evidence JSON files
are byte-identical across Scala versions. The subsequent integration update
`ddbc9ff637ec0c42093111e7f8e48fc87957580f` changes only the 59f completion
document and roadmap; incorporating it changes no qualified production source.

Publication and the final CI record belong to
[PR #162](https://github.com/pysolvesemi/MorphHDL/pull/162). The standalone
results below remain historical evidence. Final combined CI results, including
the broad regression inventory, are retained on that PR and must pass for its
published head before merge. No unmerged
parallel successor supplies an implementation dependency.

## Native source contract

The ordinary `Vec(...).reduceBalancedTree(op, levelBridge)` source and the
existing native reduction algorithm remain authoritative. Adjacent elements
are paired in their original order. An odd tail bypasses the operation and
passes through its native level bridge. A singleton bypasses both callbacks.
No neutral element, padding operand or reassociation is introduced.

Composite callbacks retain the full record. A key comparison used to select a
record selects its tag, coordinates and payload together; separate output
fields do not imply separate selections. Cross-field modular add/subtract is
qualified against the native topology, including non-associative expressions.

## Capture and replay

`TypedBalancedReductionCompositeReplay` validates a recursive Bundle/Vec shape
before transferring any scalar leaf evidence. It retains field paths, native
leaf kinds, exact independent width functions, container ownership, nested
dimensions and complete assignments. Closed-graph observations freeze mutable
expression children and drivers before another callback can run.

Each admitted expression has a transfer rule and a constructor for its exact
native expression class. Replay builds a fresh native DAG from the certified
operand-leaf positions. Shared comparisons remain shared dependencies of the
whole record. It does not rerun the Scala callback or emit arithmetic Verilog.

The existing scalar identity/register bridge certificate is applied to each
complete leaf path. All leaves must advance together through equal register
counts and one exact clock domain. Native registers continue to define reset,
enable and initialization behavior. Missing fields, foreign reads or writes,
partial drivers, cycles, changed widths and unsupported effects reject.

Callback bytecode admission remains separate from graph admission. Native
clone, mux, access and assignment calls receive only the minimal audited
composite extension. User Bundle constructors, accessors and construction
hooks require explicit inspection; a successful sampled graph does not prove
that arbitrary host code is safe.

The initial construction profile admits final ordinary Bundle classes with
immutable `Int`, `ElabInt` or `HdlInt` shape parameters. Arbitrary object, Data or
Function0 constructor parameters, custom construction hooks, extra callback
traits and opaque HardType factories reject. Locally constructed native Vec
generators are recursively audited. These restrictions apply to symbolic
reduction admission; they do not change ordinary concrete callback execution.
Per-instance assignment redirects also reject before either callback executes,
including redirects on a nested leaf or Vec container.

## Integration with completed 59f

The shared backend dispatches from the actual element shape. Exact native
scalar `Bool`, `Bits`, `UInt` and `SInt` operands use 59f's certified helper and
captured-input schema, scalar stage capture and scalar graph replay. Composite
Bundle/Vec operands retain 59e's separate constructor/assignment admission,
recursive shape certificate and composite replay. Both paths perform the
pre-execution runtime-value checks, preserve their own freshness observations
and feed the shared native stage topology. This integration does not admit
broader captured composite callback graphs; that combined feature remains a
separate qualification obligation.

The native scalar mux uses `ParameterizedWidth.preserveMuxWidth` with an
ordinary native `this.clone.setWidth(w)` result. The clone initially carries no
pre-copied width evidence from one arm. Metadata is retained only after every
input proves the same exact width function; native maximum-width selection
remains authoritative. Ordinary `cloneOf` still propagates 59e's existing
shape metadata through its separate validated clone boundary.

Combining that clone propagation with 59f's unsigned resize publisher exposed
two captured-register initialization regressions. The cloned resize input can
carry branch-projected width metadata while padding publication runs at module
scope.
The repair passes the exact component and assignment to the padding helper,
checks projected assignment dominance and obtains each width's admitted domain
from `exactAssignmentDomainOf`. Width-authority validation and subtraction run
inside those exact owner contexts. Proven same-root zero padding retains the
established one-zero-bit normalization; a projection or concrete witness alone
does not authorize the proof.

The checksum-pinned publisher ledger retains all six original 59f spans and
four original 59e spans unchanged, then appends three reversible repair spans.
Reversing the three repair spans reproduces the initially merged fallback;
reversing the ten preserved spans then reproduces the completed 60f baseline.
Each span must match uniquely, and complete before/after hashes are checked. The final fallback SHA-256 is
`f436d56464ad291904d66ec8381121d3b12dfa58fbfaf35b0206d778fca9ca79`; the ledger
SHA-256 is `a7413d5d50fcb9a073cdd40a980c1476dddb9ef7d727635d045565b39b5b3f9a`.

## Packed transport and native propagation

The shared publication backend handles scalar or composite Data templates.
It reconnects exact native leaf anchors to factorized packed stage slices;
the native emitter still supplies every operator and clocked process.

`ParameterizedVecElementLayout` retains a recursive layout beside the finite
native carrier. Independent nested counts multiply the logical packed width;
they do not become a fixed list of numbered public ports. Native flat leaves
retain separate logical offsets and presence conditions. Inactive carrier
leaves do not occupy bits in the published logical record.

Transient packed support wires require exact live, unconditional, full-object
combinational drivers in the owning component. A changed register, port,
foreign source or partial driver cannot authorize removal of that wire.
Generated presence labels use the same collision-aware allocation discipline
as the stage buses.

This increment uses the legacy packed Vec interface. Named field-vector
interfaces belong to 59c, changing stage widths to 59d, and combined feature
qualification to 59i. Those siblings are not dependencies of 59e.

The native `cloneOf` boundary propagates existing typed metadata, while a
native scalar mux retains width metadata only when all participating operands
prove the same exact width function. Concrete APIs and native width selection
remain authoritative. The approved native-change manifest records these
mechanical edits.

## Qualification contract

The Scala suites cover positive recursive graphs and rejection controls. The
tool harness independently elaborates ordinary native references for every
specialization. One parameterized candidate per static topology is generated
at COUNT=1 and reused for larger overrides. Independent input fields and Vecs
prevent swapped-record wiring from being hidden by identical stimuli.

Required evidence includes both supported Scala versions, deterministic A/B
generation, exact port and leaf widths, strict Verilog-2001 parsing, Icarus
simulation, Verilator lint, full Yosys synthesis and native-reference formal
equivalence. Real mutations must produce observable counterexamples for leaf
swaps, corrupted tags and cross-field wiring. Errors, missing tools, timeouts,
UNKNOWN and skipped cases do not qualify as proofs.

The minimum scalar geometry is WIDTH in `{1,5,8,32}` and COUNT in
`{1,2,3,5,8,9,16,17}`, extended by independent field widths and nested dimensions.
These finite specialization proofs are not universal formal quantification
over all parameter values. Domain validity and symbolic shape transfer have
their own implementation checks.

| Profile | Specializations | Coverage |
| --- | ---: | --- |
| Main composite | 40 | 32 common width/count cases plus eight independent field-width cases; RGB min/max, stable complete-record selection, modular cross-field arithmetic, mixed nested records and a complete-record register bridge |
| Independent nested counts | 25 | Inner Vec length and both rectangular grid dimensions retain separate roots; three dimension/width profiles across all eight counts, plus the all-singleton default |

The actual returned reduction Data supplies the candidate and native oracle
logical shape descriptors. Declared output adapters have their own separate
physical port checks, so those adapters cannot supply the expected result kind
or hide a result-width mismatch. The fixture sets the packed vector limit to
8192 bits to cover its declared maximum carrier domains.

The inherited signedness workflows retain their sealed qualification history
and oracle files while rerunning their original behavioral gates on the current
implementation. The completed 60f source interval does not freeze later
production development; current native edits still require the approved-change
audit.

## Standalone qualification — historical results

This table records the standalone 59e qualification associated with `4f323bda`
and its subsequent `ba479403` completion publication. The 1,652-test broad
inventories are historical standalone counts, not a claim about the combined
59e/59f build.

| Gate | Scala 2.12.18 | Scala 2.13.12 |
| --- | ---: | ---: |
| Targeted composite, native shape, safety and signedness tests | 263 / 263 in 19 suites | 263 / 263 in 19 suites |
| Broad inherited regression inventory, with formal gates enabled | 1,652 / 1,652 | 1,652 / 1,652 |
| Composite A/B artifact pairs | 65 cases, two reused candidates | 65 cases, two reused candidates |
| Composite strict tools, synthesis, simulation and equivalence | 65 / 65 | 65 / 65 |
| Composite independent-model simulation cycles | 23,316 | 23,316 |
| Actual composite mutation counterexamples | 4 / 4 | 4 / 4 |
| Inherited 59b strict tools, simulation and equivalence | 32 / 32 | 32 / 32 |
| Inherited 59b simulation cycles | 5,586 | 5,586 |
| Actual inherited 59b mutation counterexamples | 2 / 2 | 2 / 2 |

Both Scala test inventories contain zero failures, errors or skipped tests.
Every hardware case checks exact raw result kinds and widths independently of
physical output adapters. A/B checks cover the manifest, both candidates and
all independently elaborated native references. The local tools were Icarus
12, Verilator 5.020 and Yosys 0.33, with the strict Verilog-2001 target and full
Yosys synthesis/check enabled.

For each Scala version, the main 40 cases require reset-entry and unbounded
induction checks; the 25 independently counted cases require combinational
SAT equivalence. All four composite mutations compile successfully, disagree
with the independent simulation model and produce SAT counterexamples with
`bad=1` VCD witnesses. The two inherited 59b mutations retain their original
SAT counterexample checks.

The [59e workflow](https://github.com/pysolvesemi/MorphHDL/actions/runs/34016723645)
retains source archives, generated RTL, test XML, solver logs and mutation
traces. The [60f inherited qualification workflow](https://github.com/pysolvesemi/MorphHDL/actions/runs/34016723672)
records the complete regression inventories and original signedness gates.

The later standalone [59e run 34026753088](https://github.com/pysolvesemi/MorphHDL/actions/runs/34026753088)
and [60f run 34026753083](https://github.com/pysolvesemi/MorphHDL/actions/runs/34026753083)
passed on `ba479403`. Both complete 59e Scala lanes reproduced 65 composite
cases, 32 inherited cases and all four actual composite mutation witnesses.
Both broad Scala inventories passed 1,652 non-skipped tests, and the downloaded
cross-Scala signedness comparison matched 213 RTL files at that publication.

The approved native-change audit, retirement guards and typed source overlay
checks passed. Regenerating the native source manifest produced identical
bytes; its SHA-256 is
`e55be2ea851e74ab310b4d816f630fd21aa823ab832a004b59b46ca40ec2f9f2`.
Final source review found no blocker in constructor/callback admission,
recursive root and ownership retention, live support-wire removal or the
independent result-shape oracle.

## Combined 59e/59f qualification — local results

The following results apply only to production commit
`da47a78faf60217c795e47e1e8c598e2fccb61a0` and tree
`e5f83db999501e1941702d259c694bddd6e94a37`.

| Gate | Scala 2.12.18 | Scala 2.13.12 |
| --- | --- | --- |
| Combined targeted composite, callback, native shape, safety and signedness tests | 358 / 358 in 27 suites; zero failures/errors/skips | 358 / 358 in 27 suites; zero failures/errors/skips |
| Complete independent A/B generation | All six writer invocations; deterministic pairs | All six writer invocations; deterministic pairs |
| Composite strict tools, synthesis, independent simulation and equivalence | 65 / 65 | 65 / 65 |
| Composite independent-model simulation cycles | 23,316 | 23,316 |
| Composite reset-entry / induction / combinational proofs | 40 / 40 / 25 | 40 / 40 / 25 |
| Actual composite RTL mutation counterexamples | 4 / 4, simulation and SAT | 4 / 4, simulation and SAT |
| Inherited 59f callback strict tools, synthesis, independent simulation and equivalence | 64 / 64 | 64 / 64 |
| Callback independent-model simulation vectors / combinational proofs | 27,330 / 64 | 27,330 / 64 |
| Actual callback RTL mutation counterexamples | 3 / 3, SAT | 3 / 3, SAT |
| Inherited 59b strict tools, synthesis, independent simulation and equivalence | 32 / 32 | 32 / 32 |
| Inherited 59b simulation cycles / reset-entry / induction proofs | 5,586 / 32 / 32 | 5,586 / 32 / 32 |
| Actual inherited 59b mutation counterexamples | 2 / 2, SAT | 2 / 2, SAT |

All actual mutation controls require `bad=1` counterexample VCD witnesses. The
four composite mutants additionally compile and disagree with the independent
simulation model. The inherited callback and 59b mutation controls retain their
original SAT checks; no separate mutant simulation is claimed for those five.

The completed evidence files have the following SHA-256 hashes. Each file is
byte-identical between Scala 2.12.18 and 2.13.12:

| Evidence | SHA-256 |
| --- | --- |
| Composite 59e | `7b16cab728d832fe1a23ed256900f23521729365290130e01f91ce29edce31d8` |
| Callback 59f | `9f357e33506d8f97940d9902b7794929e9e5109d941f6cfe880b894e1f185986` |
| Inherited 59b | `df0bbc90ddffd6cb0af97bbc47925d1ff32488a32ee247cfaf069b222095e966` |

The repaired committed source passed all four source gates: current 60f source
and native audits, three positive/nine negative inherited-source worktree
controls, exact combined publisher restoration, and three positive/fourteen
negative publisher worktree controls. These include a changed assignment-owner
repair proof and a committed unrelated fallback mutation. The inventory gate
passed 533 rejection controls across all four exact original source profiles;
solver classification and source-evolution checks passed their 12 and six
rejection controls respectively.

The current native manifest SHA-256 is
`d0b9594b76b109222adaba7818233d239a7f68f553f29b758e062d388c1ad236`.
The combined broad inventory requires all 85 MorphHDL suites, including every
59e and 59f addition. Current staged, unstaged or ignored/untracked production
changes, altered sealed authorities, unregistered pass deltas and complete
callback-source reversions remain rejection cases. These source checks do not
replace the hardware evidence or the final published-head CI. The 59f workflow
explicitly admits the 59e integration branch, so the final PR also reruns the
complete callback gate alongside 59e and the inherited regression workflows.

## Proof scope

These are finite specialization proofs using two-state synchronous transition
semantics. Pipeline induction starts from zero state; reset-entry separately
checks output equality after an enabled reset transition. Every qualified
pipeline leaf explicitly resets and initializes to zero under the same clock,
reset and enable domain. This does not claim a generic invariant over arbitrary
internal post-reset states or universal quantification over parameter values.

The shared proof setup performs ordinary Yosys optimization to merge identical
candidate/reference register and enable cones before SAT. It adds no assumptions
and starts from a miter comparing every declared output; optimization may
simplify equivalent comparisons. Parser errors, synthesis errors, timeouts,
UNKNOWN results and absent counterexample traces fail the gate.
