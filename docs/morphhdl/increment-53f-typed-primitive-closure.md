# Increment 53f — Typed parameter-sensitive primitive closure

Increment 53f extends the typed `ElabInt`/`ElabBool` architecture through the
remaining parameter-sensitive native primitive boundaries. The authoritative
SpinalHDL `Counter`, `Mem`, `Vec`, Stream/Flow and hierarchy algorithms remain
in place. Typed overloads and identity-retained metadata preserve geometry that
ordinary Scala `Int` values would erase; concrete calls continue through their
existing paths.

This document is a closure contract, not a completion declaration. The roadmap
checkbox remains unchecked until the exact implementation head and the later
checkbox-only head pass every canonical gate described below.

## Native Vec shape

A `Vec` is a logical structural collection, not a memory. One typed native Vec
retains both its depth and the ordered native leaf shape of one element:

| Property | Retained meaning |
|---|---|
| `depth` | exact `ElabInt` expression for the number of logical elements |
| `elementLeaves` | ordered paths, native data kinds and exact leaf widths |
| `elementWidth` | factorized sum of the leaf widths |
| `totalPackedWidth` | factorized product `depth * elementWidth` |
| `carrierCapacity` | audited finite native construction capacity; never the public depth |

The typed builder still returns `spinal.core.Vec`. Constant and dynamic access,
whole-Vec assignment, packed conversion, cloning, `HardType`, registers,
Stream/Flow payloads and enclosing Bundles execute the ordinary Vec methods.
Those methods record exact operation and object identities needed at the final
publication boundary. The backend does not discover Vec users from component
names, emitted signal names, element suffixes or source positions.

That list is the supported symbolic-depth Vec surface for Increment 53f, not a
claim that every native Vec API is parameterized. Fixed range selection,
`oneHotAccess`, Vec equality/inequality (including four-state equality),
`getZero`, elementwise bitwise operations/inversion and ranged
`assignFromBits` are deferred because their ordinary carrier algorithms have
not yet proved logical-depth semantics over every admitted parameter value.
They fail closed with `SPINAL-ELAB-VEC-OPERATION-UNSUPPORTED` before RTL
publication. This restriction applies only when Vec geometry is symbolic;
ordinary concrete Vec calls continue through the native concrete path and must
retain their existing elaboration and RTL behavior.

Symbolic Vec depth must be positive, finite, fit the Scala `Int` construction
domain and carry complete exact evidence. A finite internal carrier may use the
maximum admitted depth to let the native algorithm construct and validate its
graph. That capacity is explicit metadata and cannot replace the symbolic
depth, escape as public geometry, or authorize an unrecorded witness-wide
operation. Constant indices must exist at every legal depth. Dynamic addresses
must cover the complete depth domain. Assignment and connection require equal
logical depth and recursively equal element shape, not merely equal defaults.

## Strict Verilog-2001 Vec representation

At a Verilog-2001 boundary, each logical Vec subtree is published as one packed
vector. For `Vec(UInt(WIDTH bits), DEPTH)`, its declaration is equivalent to:

```verilog
wire [(WIDTH * DEPTH)-1:0] vec;
```

Ports use the same single packed representation. Element `i` occupies the
zero-based slice equivalent to:

```verilog
vec[((i + 1) * WIDTH)-1 : i * WIDTH]
```

The lowerer may use the Verilog-2001 indexed part-select form `base +: WIDTH`.
Runtime indexing remains legal Verilog-2001 and keeps native Vec read/write
semantics. Publication must not depend on SystemVerilog multidimensional packed
arrays, unpacked array ports, `logic`, `always_comb`, or one module regenerated
for every parameter value.

Composite elements remain logical composite values in MorphHDL. A three-field,
eight-bit `Pixel` has an element width of 24 bits, so `Vec(Pixel(), DEPTH)` has a
packed boundary width equivalent to `DEPTH * 24`; field paths and types remain
available to ordinary Vec operations before publication.

## Vec is not Mem

Typed Vec publication is deliberately separate from memory publication:

| Primitive | Verilog-2001 representation | Reason |
|---|---|---|
| `Vec(UInt(WIDTH bits), DEPTH)` | packed vector of `WIDTH * DEPTH` bits | structural collection and port compatibility |
| `Mem(UInt(WIDTH bits), DEPTH)` | unpacked `reg [WIDTH-1:0] mem [0:DEPTH-1]` | preserve RAM inference and native memory ports |

No Vec registry or lowerer may attach to `Mem`, and no memory lowerer may flatten
storage into a packed vector. Regression tests place both primitives in the
same component and verify the distinction through strict lint and synthesis.

## Counter and shared typed geometry

The native binary Counter accepts exact typed state counts and inclusive limits
through explicit overloads. Its legacy BigInt constructor and companion calls
remain the concrete path, including BigInt values outside the `ElabInt` domain.
Typed construction retains exact start, end, state count, positive address
width and step width beside one native witness.

Boundary constants, reset values and wrap/pin targets are materialized through
the generic typed value adapter. Arithmetic uses explicit typed resize targets.
Power-of-two natural wrapping and bidirectional step selection use `ElabBool`
domain control rather than a default-witness Scala branch. Independent roots,
missing exact evidence, negative bounds, non-positive state counts and
inconsistent limits fail closed. Complete evidence means exact set equality
with the declared universe, not only matching cardinality: duplicate keys and
missing values are rejected before projection. A typed initial value must lie
inside every admitted start/end interval, not merely inside the default
witness interval.

Each directional natural-wrap choice is exhaustive: both generated alternatives
own one complete arithmetic update behind the typed adapter's isolated scratch
target, and only the non-natural alternative adds the boundary override. This
keeps shared state declarations at module scope and prevents partial relocation
of the native process. Concrete selection remains static on `valueNext`.

Typed `addressWidth` is shared by Counter, Mem and Vec algorithms and stays at
least one bit for a positive one-element domain. Memory port normalization may
read one reviewed witness width internally, but the retained `Mem` depth and
emitted unpacked memory bound remain symbolic. Slice, resize, finite-range and
child-formal helpers likewise consume explicit typed expressions and never
recover them from a native `Int`, a source position or an emitted identifier.
Public memory and retained-value metadata attachment accepts only a literal or
one single-root exact table that completely covers its identity-authorized
domain and whose root, schema, extrema, default and native witness agree.
Raw legacy native-`Int` shadow evidence cannot enter or be upgraded into
Increment 53f typed primitive authority; it remains historical scaffolding
until Increment 53g. A trusted typed derivation inside a narrowed structural
branch preserves its exact projection provenance. Copying its case-class data
deliberately loses the provenance and cannot re-certify a partial table.

A retained resize compares the complete source and target width domains. A
same-root resize proves the relation point by point; otherwise only disjoint
bounds are sufficient. A domain that crosses grow, equal and shrink behavior
fails closed instead of freezing the witness operation. Unsigned growth uses
an invariant zero prefix, while symbolic signed growth is rejected before a
witness sign bit can escape. Literal resize calls still delegate to the native
`Int` overload and retain byte-for-byte concrete parity.

## Compatibility and ownership

Concrete `Int` Vec construction remains on the authoritative Int builder;
fully concrete geometry acquires no symbolic Vec shape. `ElabInt.literal`
construction delegates back to that same path. The two must produce
byte-identical parameter-free RTL and preserve native generator evaluation
count and order. The same rule applies to concrete Counter and memory entry
points: typed support must not force ordinary literal calls through symbolic
capture.

Typed metadata is retained by JVM object identity and exact parameter-root
identity. Cloning copies shape mechanically from the known source object.
Hierarchy binding uses the generic child-formal registry and logical Vec schema;
it does not identify a component by class, module or instance name. The
Increment 53f typed-control bridge and new typed primitive paths may recognize
neutral carrier metadata, but do not recognize Vec, Counter, Mem, StreamFifo or
any test fixture. The older explicit `ExternalParameterizedCounterRegistry`
bridge remains outside those paths for compatibility and is scheduled for
retirement in Increment 53g; closure evidence proves that a symbolic
`Counter(ElabInt)` never registers in that legacy bridge.

The generic publisher normally requires at least one native input and one
native output. Increment 53f has one narrower output-only exception for a
finite structural Vec surface: the component has no parameterized child
hierarchy, every port is an output, every output leaf is by exact JVM identity
part of a publication-retained typed Vec carrier, and every owning Vec is used
by an exact retained structural Vec index. This exception cannot admit an
output-only scalar, an output surface mixing typed-Vec and scalar leaves, an
input-only or `inout` surface, or a mixed-direction Vec auto-connect. Those
cases retain their existing fail-closed diagnostics; ordinary components with
both native inputs and outputs remain on the general publisher path.

A typed child formal whose actual value is a canonical parameter-free finite
literal binds through the same child-formal mechanism as the established
literal `HdlInt` path. Symbolic actuals require a complete exact root. In both
cases the live child, parent and formal binding identities are validated; a
same-valued or same-named substitute is not authority.

## Finite native algorithms and StreamFifo inspection

`ElabFiniteRange` is the shared bridge for native algorithms whose concrete
form iterates a finite Scala range. A concrete count still executes the
ordinary native loop. A symbolic count is legal only during parameterized
structural capture: one representative native body is retained with an exact
typed bound and published as a Verilog-2001 generate loop. A symbolic call
outside that capture fails closed instead of witness-unrolling.

Zero is a legal default for a range such as `[0, N)` when another admitted
point is positive. Capture therefore proves that the complete count domain is
non-negative and contains at least one body-bearing point; it does not require
the public default itself to be positive. The zero specialization emits no
body. Typed exact finite counts and the established analyzed `HdlInt` path are
accepted independently, while an inexact `foreach` or `countOne` request is
rejected before RTL is written.

Vec and Mem selection through a finite generate index reuse their existing
native accessors. The range count must be exactly equivalent to the logical
Vec depth or Mem depth, including when the collection itself has a concrete
depth. A retained Mem selection records the exact native read port, typed
address node, direct literal-witness assignment, `readBits` bridge and selected
leaf assignments. Publication accepts only the same live address bridge or the
native normalization to that exact retained literal identity, and rewrites only
the whole assignment for the retained port; another read of the same witnessed
address is left unchanged.

Closure evidence proves a scalar retained read while an unrelated same-address
port remains native, rejects replacement of a removed retained port by
same-name text, and accepts one composite word only when its complete packed
leaf lineage reaches that exact retained port. Increment 53f makes no broader
claim for split or multi-symbol composite memory ports. Mem storage itself
remains an unpacked memory and is never converted to a fold or packed Vec
carrier.

Procedural lowering applies the same identity rule. The live retained loop
assignment, its owner, marker occurrence and slice assignment must each be
unique and identical to the captured graph objects. Replacing an assignment
with copied text, duplicating a marker, or presenting an ambiguous target/RHS
slice fails closed. An unrelated fixed slice with coincident text is preserved
and cannot be consumed as the symbolic rewrite target.

The generic `reduceOr` adapter proves packed source/count compatibility and
uses the native reduction. The generic symbolic population count retains its
source, result, sole zero anchor, exact count and exact result width by object
identity. Strict Verilog-2001 publication emits a uniquely named combinational
integer loop bounded by the typed expression. A concrete count delegates the
ordinary native `CountOne` callback. An extra driver, nonzero/replaced anchor,
missing width provenance or mismatched source count is rejected.

StreamFifo retains explicit owner tokens for its one-stage and storage
alternatives. A token contains exact component, capture, elaboration-root and
admitted-domain identity; it is never rediscovered from an emitted name or
source position. Later formal helpers append validated native hardware to each
applicable owner. Singleton depth-one and storage-only domains may use an exact
module-scope owner; direct module statements remain native, and only validated
nested structural regions are promoted once. Owner coverage must be complete
and disjoint. This supports `formalCheckLastPush`, `formalCheckRam`, both word
and predicate forms of `formalContains` and `formalCount`, and
`formalFullToEmpty` across one unchanged symbolic FIFO definition.

The symbolic Vec-storage branch uses a target-owned normalized write index and
assigns stable diagnostic names to the exact dynamic-read result and write
index/target/data carriers. The symbolic storage pop address retains the exact
native target-sizing contract in one target-owned weak-named `.resized`
carrier so native cleanup cannot replace its address-width boundary with the
optional extra-MSB pointer width. It remains combinational: retained low
address bits and their four-state values pass unchanged, while the
power-of-two wrap bit is discarded exactly as before. The concrete branch
keeps the original direct assignment and emits no carrier. Formal storage
index normalization uses the same target-owned native auto-resize boundary.
The spellings are diagnostic only; exact graph identity and owner coverage
authorize publication, and ordinary cleanup may remove an unused read carrier.
The selected native Vec register is fed through one direct data carrier that
holds its exact prior value unless `io.push.fire` overrides it with the push
payload. Consequently the module-scope decoder guards remain authoritative
while the native push-fire acceptance predicate, selected-register hold
behavior and four-state semantics are preserved. The literal-depth branch is
unchanged and emits none of these symbolic diagnostic names.

`formalFullToEmpty` keeps its branch-local history registers inside the exact
owners and drives one retained observation point from their complete,
disjoint alternatives; one ordinary module-scope `cover` consumes that point.
Arbitrary assertion statements remain unsupported inside structural capture.
The depth-one occurrence count intentionally matches the pre-existing concrete
helper: its one-stage buffer is visible to both `formalCheckRam` and
`formalCheckOutputStage`, so a matching live payload contributes two. Increment
53f preserves that concrete algorithm instead of silently changing its RTL.

## Canonical verification

Increment 53f can close only when the exact pull-request head passes all of the
following on Scala 2.12.18 and 2.13.12:

- complete SBT and Mill MorphHDL suites plus native Counter and StreamFifo
  regressions;
- focused typed primitive, exact-domain, Vec shape, Counter, Mem, Stream/Flow,
  resize, slice, finite-map/fold, StreamFifo formal-helper and hierarchy tests;
- concrete width/depth, symbolic width/depth, compound depth, Vec-in-Bundle,
  Vec-of-Bundle, ports, internals, compatible/incompatible assignments,
  cloning, constant access and dynamic access coverage;
- illegal zero/negative depth domains, insufficient dynamic addresses,
  projection escape, independent-root, finite count/depth mismatch, fold
  anchor/width mismatch and structural-owner negative controls;
- duplicate/missing exact evidence, forged public Mem/value summaries,
  crossing resize domains, stale or duplicate emitted assignments and copied
  procedural markers;
- one unchanged parameterized module exercised at depths 1, 3, 5 and 8 and
  multiple widths;
- strict IEEE-1364-2001 Verilator lint, Icarus simulation and Yosys synthesis;
- explicit proof that Vec is packed while Mem remains an unpacked inferred
  memory;
- formal equivalence between specializations of the parameterized definitions
  and independently generated ordinary concrete RTL, with deliberate mutation
  counterexamples;
- successful operation after unregistering the native-`Int` shadow compiler
  component;
- exact source inventory, reviewed native overlay, native-change manifest and
  generic architecture-boundary scripts.

Merge also requires the independently triggered `MorphHDL baseline / Strict
Verilog-2001 contracts` status to succeed on the exact head. GitHub Actions
cannot express a `needs` dependency across workflow files, so that baseline
status remains a separate required merge check rather than being inferred from
the Increment 53f canonical-closure job.

The canonical workflow requires both
`TypedParameterizedVecFormalEquivalenceTests` and
`TypedPrimitiveClosureFormalEquivalenceTests`; ordinary concrete Counter formal
tests alone are a regression, not proof of typed primitive closure.
