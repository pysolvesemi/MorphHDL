# Increment 59d — Generic symbolic widths and widening reductions

## Status

Complete and merged into `parameterized-verilog` through
[PR #164](https://github.com/pysolvesemi/MorphHDL/pull/164) at
`99b6017d7ac69112a088680457029623620224d3`. Both Scala lanes passed the complete
source and post-merge qualification. Work started from merged commit
`d3a0f112ce3cab9f074e5a7cbbc165c9878ff40a`, including completed Increment 59b.
No dependency on an unmerged 59c or other parallel successor is introduced.
The integration branch also includes merged 59f callback graphs from
`c85659a20d428dd58cc6116c12c8b24418c37722`. Their shared callback certificate
must retain the generic width function as well as ordered graph and capture
identity. Both increments' qualification remains required on the combined head.
The branch also integrates 59e composite reductions from
`b25e367d99604e61b8f2c895b2c51ca1ab90d423`. Scalar widening and fixed recursive
composite stages retain their own certified geometry under shared capture and
publication ownership. All three increments require combined-head qualification.

## Native width provenance

The original scalar operators and balanced helper remain authoritative.
Mechanical native hooks retain symbolic width metadata through arithmetic,
casts, mux construction, cloning, HardType and RegNext. The original native
width inference and concrete overloads still determine ordinary SpinalHDL
construction. A native high-bit request retains exact access and source
identity before its construction index becomes an ordinary integer.

Recursive cloning validates leaf kinds, field paths, native width witnesses
and nested Vec identities before copying exact authored or native-derived
geometry. Inspecting a fresh clone does not finalize its width before native
mux construction recomputes the maximum across its inputs. A clone cannot
introduce symbolic width authority absent from its source.

Resize and high-bit publication retain their exact native assignment, source,
target and scope identities. Width checks use the captured declaration owner
after construction branches have ended. Explicit positive-width slices and
nonnegative extension counts preserve resize behavior across narrowing and
widening overrides while keeping strict lint checks enabled. Validation reuse
is confined to one publication call, with fresh checks at both boundaries.

Already-valid native identity resizes and fixed narrowing slices retain their
original emission. Native multiplication and concatenation retain operand-width
addition spelling. The existing normalized UInt resize path continues to own
explicitly fixed consumers, including memory addresses. Captured resize widths
must also agree with the widths actually selected for publication; a named fixed
carrier cannot acquire a different symbolic width through its driver.
The exact recorded `Vec.asBits` wrapper remains owned by its existing packed-Vec
publisher, which validates its finite carrier and logical result together.
For retained composite Vec elements, a private packing constructor creates the
native concatenation graph without eagerly combining independent field domains
into a scalar width. The existing exact leaf-order, driver and carrier checks
run before returning the packed result. Its factorized Vec layout owns public
geometry; ordinary scalar concatenation and subsequent scalar casts still
require their own width proof and retain the exhaustive domain limit.
Ordinary scalar resizes retain their separate source-width check. Rejection
diagnostics identify the declaration and both captured and published widths.
An exact poison-free, zero-width literal source remains with native constant
folding and the retained-zero publisher. It cannot authorize another scalar
resize or remove the positive result-width requirement.

Unsigned padding compares widths at their own exact declarations after
construction scope ends. Correlated symbolic widths must retain matching root,
schema and owner domains before their pointwise difference can authorize an
explicit padding count. Different owner projections preserve the existing
conservative native extension; interval bounds do not merge their domains.

Native inferred UInt addition/subtraction with a Boolean increment retains its
modular width through normalization. The proof still excludes explicit fixed
arithmetic boundaries and requires one exactly same-width unsigned operand.
Signedness analysis validates composed width certificates at their exact native
declaration owner while retaining the original integer-domain checks for other
metadata.
The inherited zero-value publisher uses that same declaration-owner width
validation for composed widths. It still requires one exact live, poison-free
zero literal edge and preserves the separate ownership of retained values and
finite-fold anchors.

`ElaborationWidthAuthority` composes widths from previously authorized typed
expressions. Arithmetic and conditional transfer retain declaration identity,
correlation between repeated roots, and branch scope. Independent roots use
their Cartesian domain, subject to the existing 65,536-evaluation limit.
Copies of public expression fields do not inherit the private certificate.
Width composition does not relax the single-root contract of general native
integer controls and structural loops.

## Generic graph transfer and lane geometry

Operator certificates derive each intermediate and result width from the exact
native scalar graph. Replay substitutes two independently certified operands
into that graph. It does not rerun the Scala callback or select a separate
addition or multiplication reduction algorithm. Explicit scalar resize retains
its native target contract. Callback bytecode and closed-graph guards continue
to reject host state, unknown effects, foreign writes and ambiguous shapes.

Each balanced stage has a full-group width and the actual width of its final
group. The same certified operator transfer calculates both. The native
active-stage and odd-tail predicates select whether the final group passes
through its bridge or participates in a pair. Full pairs, the pair ending in
a narrower group, and an odd tail have separate native templates where their
widths differ. Stage transport contains the full groups followed by the actual
final group; it does not pad the tail to a neighbouring group's width.

For a five-element full product, native widths remain
`W,W,W,W,W → 2W,2W,W → 4W,W → 5W`. These values are a qualification example;
production geometry is derived from the certified scalar graph. Singleton
defaults keep the original element width and bypass all operator and bridge
calls while retaining the generate stages needed by larger legal overrides.
Register processes, reset and clock-enable behavior remain native emission.

Full-pair width proofs use the exact counts at which those groups exist.
Inactive metadata cannot reject a legal correlated WIDTH/COUNT domain. Each
packed stage also carries its own exact width certificate and must satisfy
the configured native vector-width limit; legal scalar widths alone do not
authorize an oversized packed transport.

## Qualification contract

The dedicated fixture creates candidates with WIDTH/COUNT defaults `(5,1)` and
`(8,5)`. Each is reused across WIDTH `{1,5,8,32}` and COUNT
`{1,2,3,5,8,9,16,17}`. References are independently elaborated ordinary native
hardware for each specialization. Qualification checks actual native output
widths and leaf kinds before constructing a miter, as well as product lane
widths and independently driven unsigned and signed input vectors.

The required gates include both Scala lanes, deterministic generation,
strict Verilog-2001 compilation, Icarus simulation, Verilator lint, full Yosys
synthesis, reset-entry and inductive equivalence, and genuine counterexamples
for mutated carry bits, sign bits, default-frozen width behavior and tail
extension. Inherited callback, graph, ownership and native-source controls
remain applicable. A timeout, tool error, missing artifact, UNKNOWN or skipped
test is not passing evidence.

The dedicated workflow builds unmodified upstream Verilator 5.020 from a pinned
commit and archive checksum into its own installation prefix. The container's
4.228 release cannot lint the native 544-bit signed multiplication required by
WIDTH=32, COUNT=17. Bootstrap checks both that multiplication and a deliberately
invalid width assignment whose warning must remain fatal. Source, build logs,
binary identity and tool version are retained. Yosys, ABC and Icarus keep the
existing container versions; no native reference RTL or lint warning is altered.

For synthesis, the checker records actual specialized ports before flattening
the native design, then places every native RTLIL cell in its own submodule.
This bounds individual ABC networks while leaving the native Verilog unchanged.
The preparation must pass formal equivalence and preserve the complete set of
known initialization bits through aliases. Unrestricted `synth -top` still runs
all default passes and ABC across the complete hierarchy. Final inventories
reject unresolved cells, boxes, processes, memories and unreachable modules.
A genuine zero-cell singleton remains valid and still runs the full flow.

The 32 native references can be reused between the two profiles only after
successful checks within the same invocation, keyed by their complete RTL hash
and module identity. All 64 candidate specializations and all comparisons run.
The workflow records runner memory, CPU and tool versions, retains phase timings,
and allows 1,200 seconds per full synthesis and 240 minutes per Scala lane.
These limits accommodate the largest native multiplication networks; exceeding
either limit still fails qualification.

The inherited 59b stage workflow allows 90 minutes for its complete job. Observed
generation reached 57 minutes 30 seconds before a newer revision canceled that
job. Successful runs required another 6 minutes 47 seconds to 7 minutes for
the 96-shape proof and mutation phase.
The budget includes both independent writers and every existing proof; individual
tool limits and failure requirements remain enforced.

## Recorded source and post-merge qualification

The reviewed source `c10bab1e72d051ee03f4ff05c0f56abdc0815553` and PR #164 merge
`99b6017d7ac69112a088680457029623620224d3` have the same complete Git tree,
`31cfab5ae38ad44cd22196f8dfd5a805ff780ead`. Source qualification passed 96 actual
jobs: 20 critical and 76 inherited. Post-merge qualification passed 109 actual
jobs: 16 critical and 93 inherited across 28 inherited workflow families.
Routing-only and intentionally filtered jobs are excluded from these counts.

Mill and some inherited source checks used synthetic PR merge
`d91c60b834e8aa21b72ae545ae942a8fd7ab541a`, independently verified to have that
same tree. The actual target-branch merge above was then qualified separately.
All 91 inherited merge jobs that check out code verified that exact merge SHA;
the other two successful jobs aggregate their prerequisite results.

The table records passing results at both checkpoints. Scala lanes are 2.12.18
and 2.13.12; the source and merge run links retain their separate evidence.

| Gate | Verified result | Source run | Merge run |
| --- | --- | --- | --- |
| 59d widening | Each lane: 145 tests / 13 suites, zero failures/errors/skips; all 64 unique HDL cases and four actual RTL mutation counterexamples | [34043602591](https://github.com/pysolvesemi/MorphHDL/actions/runs/34043602591) | [34051576248](https://github.com/pysolvesemi/MorphHDL/actions/runs/34051576248) |
| 59e composite reductions | Each lane: 65 composite cases / four mutations and 32 inherited publication cases / two mutations; merge tests 224 / 16 suites, zero failures/errors/skips | [34043602599](https://github.com/pysolvesemi/MorphHDL/actions/runs/34043602599) | [34051576289](https://github.com/pysolvesemi/MorphHDL/actions/runs/34051576289) |
| 59f callback graphs | Each lane: 64 callback cases / three mutations and 32 inherited publication cases / two mutations; merge tests 220 / 17 suites, zero failures/errors/skips | [34043602540](https://github.com/pysolvesemi/MorphHDL/actions/runs/34043602540) | [34051576130](https://github.com/pysolvesemi/MorphHDL/actions/runs/34051576130) |
| 60f regression and signedness closure | Each lane: 1,738 tests, including MorphHDL 947 / 91 suites and passes 99 / 11 suites, zero failures/errors/skips; both qualification jobs and 213-file cross-Scala byte identity | [34043602616](https://github.com/pysolvesemi/MorphHDL/actions/runs/34043602616) | [34051576310](https://github.com/pysolvesemi/MorphHDL/actions/runs/34051576310) |
| Baseline and strict downstream contracts | Both Scala lanes and all 21 downstream Verilog-2001 contracts | [34043602517](https://github.com/pysolvesemi/MorphHDL/actions/runs/34043602517) | [34051576270](https://github.com/pysolvesemi/MorphHDL/actions/runs/34051576270) |
| Mill compatibility | Both Scala lanes | [34043602478](https://github.com/pysolvesemi/MorphHDL/actions/runs/34043602478) | [34051576167](https://github.com/pysolvesemi/MorphHDL/actions/runs/34051576167) |
| Inherited native, library, formal and source gates | All 76 source jobs and 93 merge jobs; whole-stage replay includes 96 shapes, reset-entry/induction and latency mutation in each lane | [PR #164 checks](https://github.com/pysolvesemi/MorphHDL/pull/164/checks), [stage](https://github.com/pysolvesemi/MorphHDL/actions/runs/34043602648) | [stage](https://github.com/pysolvesemi/MorphHDL/actions/runs/34051576090), [53g retirement](https://github.com/pysolvesemi/MorphHDL/actions/runs/34051576256), [54 layering](https://github.com/pysolvesemi/MorphHDL/actions/runs/34051576255) |

The 64 widening cases comprise 32 singleton-default and 32 alternate-default
specializations. Every case passed strict tools, independent simulation, exact
native width/kind checks, full synthesis, reset-entry and induction, including
COUNT=16/17. The four mutations changed actual carry, sign, default-width and
tail-extension logic and produced definitive counterexamples.

Widening evidence, ordered Scala 2.12.18 / 2.13.12:

- Source jobs `101514632303` / `101514632324`, artifacts `9994624010` / `9994593104`.
- Merge jobs `101536034722` / `101536034957`, artifacts `9996039606` / `9996272311`.

These artifacts retain both generation roots, complete evidence, XML, source
archive, `head.txt`, tool provenance and phase logs. The Actions logs establish
exact checkout SHAs and the enforced XML attributes; SBT runtime output is
redirected to the retained `sbt.log`. RTL-hash evidence remains associated with
its enclosing source and Scala provenance.

Each Baseline and Mill lane reports 939 successful MorphHDL tests and eight
conditional test cancellations. All eight general 53g/54 SBT and Mill variants
also canceled those same eight conditional opt-in tests. Those cancellations
are not counted as passes: both required 60f lanes executed the cases at
`99b6017d` within the 1,738-test zero-skip inventory. The canceled 59d source
push duplicate ran no jobs and contributes no qualification evidence.

The native manifest regenerated byte-identically with SHA-256
`712fba385c39de57e5379467892b3bfc6f855b308b5a38f290cc730784745d06`.
Source controls passed 59d 7/26, 59b 6/19, 59f 8/122 and 60f 2/10
positive/negative cases; inventory controls passed 12 profiles and 3,042
rejections. The 59d repeat comparison is within each Scala lane; the separate
cross-Scala comparison covers the inherited 213-file 60f corpus.

All required source and merge gates passed before the documentation-only
closeout. This completion record describes the two qualified checkpoints above;
later feature and documentation commits have their own distinct Git trees.
