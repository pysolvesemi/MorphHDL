# Increment 59d — Generic symbolic widths and widening reductions

## Status

Implementation and qualification are in progress. The roadmap checkbox remains
open. Work started from merged `parameterized-verilog` commit
`d3a0f112ce3cab9f074e5a7cbbc165c9878ff40a`, including completed Increment 59b.
No dependency on an unmerged 59c or other parallel successor is introduced.

## Native width provenance

The original scalar operators and balanced helper remain authoritative.
Mechanical native hooks retain symbolic width metadata through arithmetic,
casts, mux construction, cloning, HardType and RegNext. The original native
width inference and concrete overloads still determine ordinary SpinalHDL
construction. A native high-bit request retains exact access and source
identity before its construction index becomes an ordinary integer.

Resize and high-bit publication retain their exact native assignment, source,
target and scope identities. Width checks use the captured declaration owner
after construction branches have ended. Explicit positive-width slices and
nonnegative extension counts preserve resize behavior across narrowing and
widening overrides while keeping strict lint checks enabled. Validation reuse
is confined to one publication call, with fresh checks at both boundaries.

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

Final evidence and completion status will be recorded after these gates pass.
