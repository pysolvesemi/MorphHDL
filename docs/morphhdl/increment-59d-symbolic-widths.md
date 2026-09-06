# Increment 59d — Generic symbolic widths and widening reductions

## Status

Implementation and qualification are in progress. The roadmap checkbox remains
open. Work started from merged `parameterized-verilog` commit
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

Final evidence and completion status will be recorded after these gates pass.
