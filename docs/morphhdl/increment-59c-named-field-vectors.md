# Increment 59c — Named field vectors

Status: implementation and qualification in progress. This record defines the
publication contract and its validation requirements; it does not mark 59c
complete. The controlling checklist is
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
`NamedFieldVecTests`, naming collision controls in `NamedFieldVecCollisionTests`,
direct child/register contracts in `NamedFieldVecHierarchyTests`, and packed
copy provenance controls in `NamedFieldPackedAliasTests`. `TypedVecShapeTests`
and `ParameterizedVerilogFieldLayoutTests` cover recursive geometry and packing.
`StructuralIdentityAdversarialTests` covers captured conditional/static writes,
assignment priority and feedback rejection. The fixture source in
`NamedFieldVecFixture` supplies candidates and independent ordinary
`SpinalVerilog` references; storage qualification includes a directly connected
enabled register. The executable runner is
`morphhdl/scripts/check-increment-59c-named-field-vectors.py`.

Required closure evidence is tracked below. A source fixture or a planned gate
is not evidence that its executable run passed.

| Gate | Required evidence | Current status |
| --- | --- | --- |
| Dual Scala lanes | Shape, naming, access, nested dimensions, cloning, registers, hierarchy, Stream/Flow and concrete parity contracts | Pending final-head results |
| Interface compatibility | Named and explicit legacy layouts, scalar Vecs and ordinary Bundle outputs; deterministic repeat generation | Pending final-head results |
| Independent specialization equivalence | One COUNT=1-default candidate per static topology/profile; separately elaborated native references and wiring-only interface adapters | Pending final-head results |
| Scalar matrix | WIDTH in `{1, 5, 8, 32}` and COUNT in `{1, 2, 3, 5, 8, 9, 16, 17}`, with independent unequal field widths | Pending final-head results |
| Nested and stateful extensions | Independent inner dimensions, count-one and odd shapes, nested access and packing, conditional/static writes, enabled registers and separate child/Vec bindings | Pending final-head results |
| Tools | Icarus simulation, strict Verilog-2001 parsing, Verilator lint and full Yosys synthesis | Pending final-head results |
| Mutation controls | Real counterexamples for field swaps, reversed element order, wrong offsets and wrong hierarchy/cross-Vec binding | Pending final-head results |
| Inherited admission and safety | Existing illegal-domain, ambiguous-width, foreign-write, partial-driver, unknown-effect and graph-mutation rejection controls | Pending final-head results |
| Native source audit | Exact reviewed file hashes and changed spans; no unreviewed native algorithm changes | Pending settled-source refresh and final-head audit |
| Shared publication source review | Exact 59c overlap reversal followed by all original signedness source checks and genuine source mutation rejections | Pending settled-source refresh and final-head audit |

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

`increment-59c-source-review.json` records the complete eight-file production
delta and the inherited 60e checker adapter as exact before/after byte spans
against merged base `d3a0f112ce3cab9f074e5a7cbbc165c9878ff40a`. The two explicitly
added production files carry their complete source and must be absent from the
baseline. `check-increment-59c-source-review.py` checks the exact changed-file
inventory, every changed span and every unchanged byte between them.

Two external publication files overlap the qualified signedness work. The
inherited 60e restoration removes only their reviewed 59c changes before running
its original exact 60e reversal. The 60f source gate also requires the complete
reviewed 59c production delta to restore its original qualification-only source
state; it admits no unknown production files. The original 60c/60d/60e
baselines, signed printer checks and independent oracle checks remain in force.
The frozen 60f suite identities remain required alongside the explicitly named
59c test suites.

Source-review self-tests validate mutation rejection against the saved review
snapshot; a separate plain invocation is required to check the current source.
`test-increment-59c-inherited-source-scope.py` also runs the complete 60f source
gate on temporary worktrees, including the historical 60f state, the exact
successor and modified or missing source/checker/oracle files. It leaves the
working branch unchanged and runs the original 59b source-scope controls with
their existing negative expectations. An evolving implementation must fail the
source review until its reviewed spans have been refreshed.

This increment qualifies named interfaces and access independently of the
composite-reduction work in 59e. Cross-feature combinations remain the 59i
integration scope. The roadmap checkbox remains unchecked until implementation,
review and every applicable final-head gate are complete.
