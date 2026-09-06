# Increment 59c — Named field vectors

Status: implementation and local qualification complete on
[PR 163](https://github.com/pysolvesemi/MorphHDL/pull/163). Passing final-head CI
remains required before merge. The completion checkbox is recorded on the PR
branch; the integration branch receives it when the PR merges. The controlling
checklist is
[the parameterized-Verilog roadmap](parameterized-verilog-todo.md).

## Select the interface

The opt-in profile publishes a structural `Vec` as one packed Verilog vector
for each recursive scalar field path. The component keeps its existing source:

```scala
val pixels = in Vec(Rgb(width), count)
```

Select the profile when invoking MorphHDL:

```scala
val namedConfig = MorphNamedFieldVectors.enable(config)
MorphVerilog(namedConfig) { new MyComponent(/* existing arguments */) }
```

`enable` returns a configuration copy and leaves the supplied configuration
unchanged. Use `MorphNamedFieldVectors.disable(config)` to explicitly select
the legacy packed interface. An unmodified configuration also retains that
interface. The marker is consumed by `MorphVerilog`; ordinary concrete
`SpinalVerilog` publication retains its native behavior.

For an RGB element with three `WIDTH`-bit leaves, the named profile has these
ports, regardless of the concrete `COUNT` override:

```verilog
input wire [(WIDTH * COUNT)-1:0] pixels_red;
input wire [(WIDTH * COUNT)-1:0] pixels_green;
input wire [(WIDTH * COUNT)-1:0] pixels_blue;
```

This is a module-interface change. Parent and child publication must use the
same profile, and external integrations must connect the selected interface.
The legacy profile keeps one packed `pixels` port. A scalar `Vec[UInt]` has
one packed carrier in either profile. A standalone output Bundle keeps its
ordinary scalar leaves, such as `result_red`; it does not acquire a Vec axis.
No `Bundle[Vec]` rewrite, user layout map or explicit `asBits` call is needed.

## Recursive geometry and names

The native Vec metadata retains recursive Bundle paths, scalar leaf kinds,
exact symbolic widths, independent Vec dimensions and native leaf identities
before normalization. Publication does not recover shape from generated
names, concrete widths or component classes. Exact parameter-root and
ownership evidence remains part of shape and hierarchy validation.

Each field carries the outer Vec dimension followed by each nested Vec
dimension encountered on that field's path. A dimension contributes a width
factor, rather than an index in the port name. Bundle boundaries contribute
path segments. For an outer `COUNT`-element Vec of records containing `tag`
and `colors: Vec[Pixel]` with depth `INNER`, the layout is:

| Native leaf | Published vector | Packed width | Scalar offset |
| --- | --- | --- | --- |
| `pixels(i).tag` | `pixels_tag` | `3 * COUNT` | `i * 3` |
| `pixels(i).colors(j).red` | `pixels_colors_red` | `WIDTH * COUNT * INNER` | `(i * INNER + j) * WIDTH` |
| `pixels(i).colors(j).blue` | `pixels_colors_blue` | `BLUE_WIDTH * COUNT * INNER` | `(i * INNER + j) * BLUE_WIDTH` |

Axes are ordered outermost first. The last axis varies fastest, and coordinate
zero occupies the least significant scalar slice. For dimensions
`D0, D1, ..., Dk` and coordinates `i0, i1, ..., ik`, the scalar ordinal is
`(...((i0 * D1 + i1) * D2 + i2)...) * Dk + ik`. Multiply that ordinal by
the exact scalar width to obtain the indexed part-select offset. Fields with
different paths may therefore have different widths and different axis lists.
Every published width and dimension must be positive and finite throughout
its declared legal parameter domain.

The natural identifier joins the native Vec base name and Bundle path
segments with underscores: `color.red` becomes `pixels_color_red`. A segment
character outside ASCII letters, digits, `_` and `$` is encoded as `_uXXXX_`
using its four-digit hexadecimal UTF-16 code unit. An empty segment has the
readable spelling `empty`. Base names must already be portable Verilog
identifiers.

If distinct paths produce the same readable name, or a readable name is
already occupied, publication appends `__p` and an injective encoding of the
complete path. Each encoded segment includes its length and hexadecimal
UTF-16 code units. Thus `a_b` and `a.b` retain distinct identities. A collision
with that fully encoded fallback is rejected with a deterministic diagnostic;
allocation does not depend on a mutable numbering counter or declaration
discovery order. Field order follows native structural declaration order.

## Values, directions and native packing

Field vectors transport element bits as unsigned packed vectors, including
vectors whose scalar leaves are `SInt`. Selecting one signed element preserves
its native scalar kind and signed expression boundary. Concatenating several
signed elements must not turn their entire transport vector into one signed
arithmetic operand. This interface profile does not enable the separate
signed-declaration cleanup profile.

Each scalar field preserves the direction and storage kind of its exact native
leaves after `in`, `out`, `master` or `slave` has been applied. Directions may
differ between fields, as with forward payload and reverse ready signals.
All elements of one published field must agree on direction and storage kind;
inconsistent evidence is rejected. Internal structural stage values, cloned
values, registers and parent/child bindings use the same field geometry.
The named profile can therefore publish a Vec of Stream records with separate
forward payload/valid and reverse ready vectors. The legacy single-carrier
profile retains its existing rejection of mixed-direction Vecs; compatibility
qualification covers both layouts wherever the legacy layout is supported and
checks that this rejection remains explicit. Memory storage remains native
`Mem` storage.

Ordinary static and dynamic access, whole-Vec assignment, cloning, `HardType`,
registers and Stream/Flow payload connections retain their native operations.
The publisher transfers captured assignment and access identities onto exact
field slices. It does not introduce independent per-field arithmetic:
expressions may still couple leaves or different Vecs. Existing typed Vec
index behavior remains in force, including clamped reads and guarded writes
for out-of-range dynamic indices. Read bounds and write guards use the full
original runtime address; normalizing the emitted part-select width must not
truncate that address before the bounds decision.

Static writes retain their exact selected scalar leaf, source, assignment
target, assignment kind and enclosing conditions before normalization. Dynamic
writes also retain the native address resize, decoder and per-element guards.
Nested `when`/`otherwise` paths keep their original Bool identities and branch
polarity. Publication validates those identities and preserves the native
ordering of whole-Vec, static-index and dynamic-index assignments. It rejects
removed evidence, replaced sources, partial targets and mutated controls;
matching emitted text cannot authorize a replacement assignment.

Nested writes retain the native forwarding invocation tree for each selected
axis. Static/static, static/dynamic, dynamic/static and dynamic/dynamic forms
compose exact scalar coordinates; each dynamic axis keeps its own full runtime
bounds check. The qualification fixture applies static scalar overrides,
static-outer/dynamic-inner replacement, dynamic-outer/static-inner replacement,
and two-axis dynamic replacement in that order. Later active writes win for
their selected leaves, including when several forms select the same element.
A rejected dynamic address leaves earlier assignments in force.

A separate journal retains completed native write calls. Before pruning or
choosing publication owners, the publisher requires an exact one-to-one match
between that journal and retained indexed-write operations, including the
selected scalar identities. Removing a forwarding parent therefore cannot hide
its decoder or freeze a nested axis at the finite carrier size. Journal entries
store the selected leaf and operation; read-only snapshots reconstruct the
owner. The stored entries therefore do not add an explicit reference to their
own weak-key Vec.

Direct writes into a nested `Reg(Vec(...))` retain the exact common clock edge
and user enable. A disabled or out-of-range write holds the complete prior
state. Direct nested indexed writes require one common clock domain without
register initialization, reset, soft reset or an implicit clock-enable wrapper.
Authored `when` enables remain supported. Other state controls fail closed.
Captured forwarding metadata does not replace the native assignment algorithm
or authorize altered decoder, source, target or condition identities.

Combining native scalar processes into one field-vector process requires a
separate dependency check. If an indexed write or its condition reads the
target carrier and consolidation could change the native process ordering,
publication rejects the operation with
`SPINAL-PARAMETERIZED-VERILOG-VEC-INDEXED-WRITE-FEEDBACK-UNSUPPORTED`.
Supporting static writes does not authorize arbitrary procedural feedback.

A finite structural loop may select a Bundle that itself contains Vecs. Those
nested Vec clones belong to the captured structural selection and must not
acquire duplicate storage declarations. Scalar static reads and writes through
that selection retain their exact symbolic strides. Live dynamic access,
whole-Vec assignment, packed conversion or auto-connect on the finite
structural alias requires a separate aggregate projection and is rejected with
`SPINAL-PARAMETERIZED-VERILOG-VEC-STRUCTURAL-ALIAS-AGGREGATE-UNSUPPORTED`.
This is a boundary on captured finite-loop aliases; ordinary nested Vec access
and packing use the recursive layout described above.

An enabled register assigned directly from a child Vec output retains an
intermediate wire for the child connection and its original clocked update.
The child output must not bypass the register or its enable by being bound
directly to the registered destination. This applies to both interface layouts.

`Vec.asBits` and `assignFromBits` retain native recursive element-major packing.
Named field grouping uses a different physical arrangement, so explicit packed
conversions include wiring permutations between those arrangements. The
retained packing tree preserves native Bundle declaration order and every
array stride. Generate loops and scalar indexed part-selects implement the
conversion; changing a parameter never creates numbered ports. A simple
concatenation of the named field vectors is not a substitute for `asBits`.

Packing provenance can pass through a complete, unconditional, same-component
native `Bits` copy, such as `packed := pixels.asBits` followed by
`restored.assignFromBits(packed)`. The copy's exact target, source and driver
identities are retained and checked again before publication. A register,
conditional copy, partial assignment or equal-width unrelated signal does not
supply that evidence. A fixed-width copy whose width merely matches the
default witness cannot acquire the Vec's varying symbolic geometry. The
native intermediate packing assignments needed by nested `asBits` remain
retained until their exact wiring replacement is validated.

The small native `Vec.scala` change lets inherited packing methods construct
their complete finite carrier without intermediate nested logical-width
wrappers. The scope restores its previous state after packing, including when
an exception is thrown. Recursive geometry remains in neutral
`ParameterizedVec` metadata; native algorithms do not depend on the MorphHDL
frontend or on the selected publication profile.

## Qualification and evidence

The implementation includes interface and compatibility contracts in
`NamedFieldVecTests`, nested write contracts in `NamedFieldVecNestedWriteTests`,
naming collision controls in `NamedFieldVecCollisionTests`,
direct child/register contracts in `NamedFieldVecHierarchyTests`, and packed
copy provenance controls in `NamedFieldPackedAliasTests`. `TypedVecShapeTests`
and `ParameterizedVerilogFieldLayoutTests` cover recursive geometry and packing.
`StructuralIdentityAdversarialTests` covers captured conditional/static writes,
forwarding-journal completeness, assignment priority and feedback rejection.
The fixture source in
`NamedFieldVecFixture` supplies candidates and independent ordinary
`SpinalVerilog` references; storage qualification includes a directly connected
enabled register. The executable runner is
`morphhdl/scripts/check-increment-59c-named-field-vectors.py`.

The qualified production checkpoint is
`3166ddbde97047b45f5ca48abf9ac6e1ec75aabe`. Both local HDL matrices, source audits,
current source-mutation controls, both complete Scala 2.12 and 2.13 inherited CI
regression lanes, the 59c contract lanes and repeated generation passed at that
checkpoint. The subsequent qualification-runner scheduling change described
below changes no production RTL. Passing CI at the final PR head is the
remaining merge requirement; checkpoint results do not claim that pending
final-head CI has passed.

The 60f Scala 2.12 CI job `101559393764` and Scala 2.13 job `101559393811` each
passed all 1,813 tests below without failures, errors or skipped tests. Each
lane's MorphHDL run contributed 1,022 tests across 97 suites. All seven
formal-enable flags were logged as `1`, so the eight optional cases cancelled
by the earlier broad run executed in CI. The complete 60f workflow, including
cross-Scala determinism, passed in run `34060256475`.

| CI project | Passed tests per Scala lane |
| --- | ---: |
| MorphHDL | 1,022 |
| Parameterized RTL | 234 |
| Frontend | 257 |
| Verilog backend | 148 |
| MorphIR | 32 |
| Morph plugin | 16 |
| Core | 5 |
| Isolated passes | 99 |
| Total | 1,813 |

The separate Scala 2.12 formal run passed 126 tests across ten suites with no
cancellations. The local Scala 2.13 contract run passed 197 tests across eleven
suites with no skipped tests; the PR's Scala 2.13 contract and repeated-generation
step also passed. These local and 59c-specific checks supplement the complete
dual-Scala inherited CI result above.

The Mill Scala 2.12 run passed 1,014 tests with eight optional formal
cancellations. Those cancellations contribute no formal proof evidence; formal
closure comes from the enabled, zero-skip runs recorded above.

Required closure evidence is tracked below. A source fixture or a planned gate
is not evidence that its executable run passed.

| Gate | Required evidence | Current status |
| --- | --- | --- |
| Dual Scala lanes | Shape, naming, access, nested dimensions, cloning, registers, hierarchy, Stream/Flow and concrete parity contracts | Complete Scala 2.12 and 2.13 inherited CI passed: 1,813 tests per lane, including MorphHDL 1,022 tests / 97 suites; 59c contracts, repeated generation and inherited cross-Scala determinism passed |
| Inherited opt-in formal lane | All seven formal-enable flags set under Scala 2.12 | Full inherited CI passed with zero skips; the separate 126-test / 10-suite formal run also passed without cancellations |
| Interface compatibility | Named and explicit legacy layouts, scalar Vecs and ordinary Bundle outputs; deterministic repeat generation | Passed locally for both supported layouts; main and register generation repeated deterministically |
| Independent specialization equivalence | One COUNT=1-default candidate per static topology/profile; separately elaborated native references and wiring-only interface adapters | Passed locally: 216 main and 16 separate register layout specializations |
| Scalar matrix | WIDTH in `{1, 5, 8, 32}` and COUNT in `{1, 2, 3, 5, 8, 9, 16, 17}`, with independent unequal field widths | Passed all declared main-matrix cases |
| Nested and stateful extensions | Independent inner dimensions, count-one and odd shapes, nested access and packing, conditional/static writes, enabled registers and separate child/Vec bindings | Passed the complete local main matrix and separate nested-register matrix |
| Direct nested register writes | Eight independent configurations, sixteen layout specializations, complete FF inventory and common-state inductive preservation | Passed at the published checkpoint: 16 layouts, 1,536 samples, 64 warmup edges and two genuine SAT/VCD mutation counterexamples |
| Tools | Icarus simulation, strict Verilog-2001 parsing, Verilator lint and full Yosys synthesis | Passed every local qualified case with Yosys 0.41, Verilator 5.020 and Icarus 12 |
| Mutation controls | Real counterexamples for field swaps, reversed element order, wrong offsets and wrong hierarchy/cross-Vec binding, nested write gates/bounds and register enable/axis binding | Passed: 11 main and two separate register mutations produced verified SAT counterexamples and VCD traces |
| Inherited admission and safety | Existing illegal-domain, ambiguous-width, foreign-write, partial-driver, unknown-effect and graph-mutation rejection controls | Complete Scala 2.12 and 2.13 inherited CI passed at the published checkpoint |
| Native source audit | Exact reviewed file hashes and changed spans; no unreviewed native algorithm changes | Passed at the published checkpoint: 43 approved native paths and 224 reviewed spans |
| Shared publication source review | Exact 59c reversal, inherited source checks and source mutation rejection | Passed at the published checkpoint: 12 files / 222 spans, 77 review controls, two current positives and 20 current negative controls |
| Final-head CI | All required workflows passing at the final PR head before merge | Required merge gate; results in [PR 163](https://github.com/pysolvesemi/MorphHDL/pull/163) |

At `3166ddb`, the main ledger passed all 216 layout specializations, 46,976
simulation samples and 11 genuine SAT/VCD mutation counterexamples. All 1,877
nested proof partitions passed: 720 named-layout partitions and 1,157 legacy
partitions, covering exactly 21,240 canonical output bits per layout across the
nested matrix. The longest partition process took 19.544 seconds and the longest
preparation took 19.736 seconds. The complete main run took 50 minutes 19.7
seconds. Candidate and reference generation repeated deterministically. The
separate register ledger below adds 16 layout specializations and two mutation
counterexamples; these remain distinct qualification ledgers.

`NamedFieldNestedRegisterArtifactWriter` and
`check-increment-59c-nested-register-writes.py` provide a separate register
ledger. They enumerate every flip-flop bit and require the result ports to
observe the complete state. Formal preservation starts from equal corresponding
states with otherwise unconstrained values and proves equality after an
unconstrained next write. It assumes no initialized register value and does not
claim convergence from unrelated starting states. Simulation first writes every
cell, then checks writes, enable holds and full-width invalid addresses against
an independent Python state update. Both enable and axis mutations must produce
real SAT counterexamples whose before/after states validate the intended fault.

At `3166ddb`, the complete separate register ledger passed all 16 layout
specializations, 1,536 simulation samples and 64 warmup edges. Complete
flip-flop inventory checks and common-state inductive preservation passed;
both enable and axis mutations produced verified SAT counterexamples and VCD
traces. Candidate and reference generation repeated deterministically. The run
used Yosys 0.41, Verilator 5.020 and Icarus 12 and completed in 37.968 seconds.
This result closes the standalone nested-register matrix. Both local HDL
matrices are complete; passing final-head CI remains required before merge.

Nested combinational equivalence is the conjunction of a complete, ordered
cover of canonical output bits. The named layout proves each output port; the
legacy layout proves consecutive chunks of at most 32 bits. Each case prepares
one immutable RTLIL checkpoint from the unchanged candidate/reference miter,
then removes unrelated outputs and reduces unused widths for each proof. Every
partition must return a definitive pass, with all original inputs unconstrained.
The ledger rejects missing, duplicate, reordered or incorrectly sized partitions
and records the checkpoint and source hashes. Timeouts remain failures: each
solver has 120 seconds and each tool invocation has 180 seconds.

The runner preserves manifest result order at every worker count. A scheduling
follow-up after the qualified checkpoint adds FIFO worker admission: later
lightweight cases cannot bypass an earlier case waiting for the complete worker
budget. Access cases with `COUNT * pixel_bits >= 1024` and nested cases with
`COUNT * (3 + INNER * pixel_bits) >= 1024` reserve that complete budget. Here
`pixel_bits` is the sum of the independent pixel-field widths, and the nested
three-bit tag belongs to each outer record. Only the largest nested case in the
declared matrix meets its threshold. This bounds concurrent synthesis memory;
other cases may share the budget. Scheduler self-tests pass, including the
ordering control that rejects the previous bypass behavior. This follow-up
changes no production RTL, proof inputs or tool deadlines, and final-head CI
remains its merge gate. The workflow allows 75 minutes for the complete matrix;
each individual proof and tool deadline remains unchanged.

Inputs must drive fields, elements and distinct Vecs independently. References
must assert native scalar kinds and exact result widths without adapting away
a shape mismatch. Tool errors, timeouts, UNKNOWN and skipped cases do not count
as passing evidence. Finite specialization equivalence establishes those
specializations; it does not universally quantify over every parameter value.

The canonical native audit is generated from
`morphhdl/contracts/increment-55-native-change-review.json`, whose filename is
historical but whose policy is still used by the required native-source guard.
The reviewed 59c changes to `Vec.scala` and `ParameterizedVec.scala` must update
that policy and regenerate `native-source-preservation.json` after native
source is committed and clean. The guard must reproduce the committed manifest
byte for byte. The old typed-overlay file and 59b capture-review snapshot remain
historical records.

`increment-59c-source-review.json` records the exact 59c delta against the
merged 59d/59e/59f baseline
`99b6017d7ac69112a088680457029623620224d3`. Its 12 files comprise eight production
files and four checker adapters: the 59f source-scope checker, the 60e signedness
checker, the 60f artifact-inventory checker and the 60f equivalence-closure
checker. The review contains 222 exact before/after byte spans. The two added
production files carry their complete source and must be absent from that
baseline. Every unchanged gap and tail must remain byte-identical to the
baseline; reviewed sources must also be regular, non-executable and uniquely
tracked. The checker pins the manifest SHA-256, so changing a source and its
review together does not authorize an unreviewed change.

The 59c layer is reversed before inherited 59d/59e/59f publisher, width and
packing checks. Their frozen manifests and source hashes remain unchanged.
The complete current source union, canonical native audit, original signed
printer checks and independent oracle checks remain mandatory. The 60f gate
also runs the original 60c/60d/60e qualification-only checks on their frozen
completed tree. Its inherited suite identities and exact test-count obligations
remain required alongside six explicitly named 59c suites; each new suite has
a missing-suite rejection control.

At the published checkpoint, the plain source-review check, canonical native
audit and complete 60f source gate passed. The source-review self-test passed
12 reviewed-snapshot reversals and 77 mutation rejections. It exercises the
direct review parser and reversal logic; production loading still enforces the
manifest pin.

`test-increment-59c-inherited-source-scope.py` passed two current-source positives
and 20 exact current-source rejections. These include changed or missing core
and helper sources, all four checker-adapter branches, a paired source/manifest
forgery, unknown production paths, sealed oracle/checker changes, and live
59d backend, 59e composite-replay and 59f capture-schema mutations. The latter
three reach the original inherited source-hash checks after 59c restoration.
Each case records its source HEAD and scope; the final ledger identifies the
published checkpoint. Temporary worktrees leave the working branch unchanged.

Historical controls have a separate scope. Unchanged fixtures at the frozen
`99b6017` tree retain 59b's six positives and 19 negatives, 59d's seven positives
and 26 negatives, and 59f's eight positives and 122 negatives. The 60f historical
fixture also passed its two-positive/ten-negative contract. These replays test
the frozen mutation targets and original diagnostics; their negative counts
are not current 59c mutation evidence. The final current fixture repeats its
built-in historical 60f/59b checks, while the unchanged standalone 59d/59f
historical runs remain separately recorded from the preceding audited
checkpoint.

This increment qualifies named interfaces and access independently of the
composite-reduction work in 59e. Cross-feature combinations remain the 59i
integration scope. The PR branch records completed implementation and local
qualification against production checkpoint `3166ddb`. The named and supported
legacy matrices, deterministic generation, required local simulation, synthesis,
proof, mutation and source-review checks are complete. [PR 163](https://github.com/pysolvesemi/MorphHDL/pull/163)
must pass all required CI at its final head before merge; the integration
roadmap receives this completion record and checkbox only through that merge.
