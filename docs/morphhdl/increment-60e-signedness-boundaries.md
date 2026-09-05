# Increment 60e — Signedness boundaries, aggregates and hierarchy closure

## Scope and mode

This increment extends the explicit `MorphSignedCasts.enable(config)` mode.
`MorphSignedDeclarations.enable(config)` alone remains declaration-only.
Ordinary `SpinalVerilog`, VHDL and feature-disabled MorphHDL generation do not
bind the boundary policy. No default changes here; 60f and 60g remain separate.

The 60b signedness authority, 60a fixture and immutable cast-heavy baseline are
unchanged. Native changes remain confined to `VerilogBase` and
`ComponentEmitterVerilog`. Exact reviewed 60e spans restore all seven modified
production files to merged 60d. The inherited 60c/60d audits then independently
apply their original restoration and immutable-oracle checks. The approved
native-source manifest retains the original upstream baseline.

## Boundary rules

A cast may disappear only around the same already-signed native scalar atom,
materialized expression wrapper, or exactly normalized non-poisoned SInt
literal printed with the signed literal qualifier. A signed destination never
makes an unsigned inline expression signed. The existing native expression
wrapper plan and each intermediate overflow/truncation width remain intact.

The native printer owns unforgeable emitter/parent/operand-slot occurrences.
The generation-local graph snapshot checks exact identities, types, current
geometry and retained width provenance. Stale operands, changed literals or
poison masks, foreign emitters, cloned evidence and unsupported widths cannot
authorize cleanup. No module name, variable name, source spelling, Int witness
or printed RTL is used to infer signedness.

* Bits/UInt conversions reconstruct SInt interpretation at signed scalar
  declarations; SInt-to-unsigned consumers retain real unsigned carriers.
* Comparisons and fixed-width shifts may use those same atoms without redundant
  casts. Comparison/reduction results remain Boolean, and variable shift amounts
  remain unsigned. Inline selections, concatenations and uncertain references
  do not obtain blanket cast-removal permission.
* Negative, sized and normalized unsized SInt literals are printed with their
  exact native width and signed qualifier. Poisoned literals retain the legacy
  printer/cast path; the qualifier is not inferred from their value.
* Symbolic signed resize uses explicit non-negative sign replication and an
  in-range low-bit selection, including independent source/target parameter
  domains that cross between widening and narrowing. A signed result declaration
  reconstructs interpretation only after this explicit bit-level operation.
* Typed SInt resize result carriers are preserved before native normalization
  can erase them. Thus a TARGET-bit resize still truncates at TARGET before a
  subsequent multiply consumes it. The parent consumer cannot widen that boundary.
* Constant-process SInt functions and their result wires receive the same
  graph-owned symbolic range. Declaration-only mode retains its prior fixed-width
  function guard.

For example, an unsigned consumer stays unsigned even though its source is SInt:

```verilog
wire [WIDTH-1:0] unsigned_transport;
assign unsigned_transport = a;
assign logical_result = unsigned_transport >>> amount;
```

The right shift remains logical because the left operand is unsigned. Removing
this transport, or declaring it signed, would change negative-input behavior.

## Aggregates and hierarchy

Bundle, Stream and Flow leaf declarations retain their individual SInt, Bits,
UInt and Boolean types. Packed aggregate carriers stay unsigned, including a
one-field Bundle containing only SInt. An actual `Mem[SInt]` element is signed;
a packed Bundle memory is not. The inherited memory proofs retain their original
validity and initialization contracts.

A parameterized `Vec[SInt]` is still an unsigned flattened structural carrier,
not a memory. Dynamic selection retains the signed slice boundary. A used static
SInt leaf is reconstructed as a separately declared signed scalar of the exact
element width, preserving the native same-atom cast decisions:

```verilog
wire [(WIDTH * DEPTH)-1:0] packed;
wire signed [(WIDTH)-1:0] first_leaf;
assign first_leaf = packed[(0) +: WIDTH];
assign first_shift = first_leaf >>> 1;
```

Static reconstruction is read-only. An unclaimed static leaf writer is rejected
rather than redirected to a read alias. Unsupported structural signed slice
contexts retain explicit diagnostics. Legal Vec indices are the proof domain;
no new semantics are claimed for native out-of-range Vec selection.

Exact whole-Vec parent-internal-to-child-input connections are now accepted when
native operation lineage, direct-parent ownership, complete leaf correspondence,
live assignments and top-level assignment scope agree. Leaf-wise lookalikes are
not accepted as equivalent lineage. Parent and child publishers independently
validate their sides. Canonical child module deduplication is retained.

Typed Increment 59 BlackBox generic binding coexists with signed local boundary
wrappers. External RTL declarations are not rewritten. The proof supplies one
shared external implementation with deliberately unsigned ports, testing both
enabled and disabled generic overrides.

## Qualification

`SignednessBoundaryFixture` uses ordinary native algorithms for both legs.
Each candidate is emitted once. Every concrete reference is a fresh native
SpinalVerilog elaboration, not a second copy of the boundary publisher.

| Fixture | Independent tuples |
| --- | ---: |
| Scalar boundaries, crossing resize domains and nested multiplication | 16 |
| Mixed Bundle leaves and packed transport | 4 |
| Static/dynamic signed Vec selection and writes | 16 |
| Vec parent-internal bridges, child ports and canonical deduplication | 16 |
| Scalar hierarchy and typed external BlackBox boundaries | 8 |
| Stream/Flow payload pipelines, backpressure and resets | 4 |
| **Total** | **64** |

WIDTH and TARGET overrides are 1, 5, 8 and 32. DEPTH overrides are 1, 3, 5 and 8.
The width/domain rules are symbolic; these concrete overrides are qualification
points, not an assertion that a finite matrix alone proves every possible width.

The shared Vec wrapper maps out-of-range test addresses to zero on both legs,
covering every legal index without changing either DUT. Sequential channel
formal comparison applies `async2sync` equally to both sides and proves the
single-clock transition model; simulation additionally pulses asynchronous
reset. Correspondence is through ports and actual sequential Q nets, never
coincident combinational temporary names.

Each tuple must pass strict Verilog-2001 parsing, Icarus simulation (400 vectors
including signed extrema and random wide aggregates), Verilator lint, Yosys
synthesis/check and independent solver-backed equivalence. Five deliberate
corruptions must each yield an actual SAT counterexample: lost sign extension,
lost signed negative-literal extension, unsigned-consumer contamination, lost
Vec leaf signedness, and lost external-boundary reconstruction. Parser errors,
missing modules, timeouts and unknown results do not satisfy a negative test.

The dual Scala 2.12.18/2.13.12 workflow additionally runs exact-graph adversarial,
mode-isolation, native compatibility, typed BlackBox and typed Vec regressions;
it enables the inherited tool-backed Vec formal suite rather than skipping it.
Fresh generation must match byte-for-byte for all 70 new reference/candidate
files and all 29 inherited 60d files. The entire inherited 60d qualification,
three live mutations, memory validity proofs and immutable 60a oracle also run.
All source, XML, RTL, simulation, lint, synthesis and solver evidence is retained
in `increment-60e-<Scala version>` workflow artifacts.

```sh
sbt -batch '++2.13.12' \
  'morph/Test/runMain spinal.core.SignednessBoundaryArtifactWriter target/60e'
python3 morphhdl/scripts/check-increment-60e-signedness-boundaries.py target/60e
```

## Remaining gates

60e does not change defaults or authorize arbitrary inline-expression cast
removal. The existing `cutLongExpressions=false` restriction and unsupported
structural/ambiguous boundary diagnostics remain. Full rollout qualification
and compatibility closure are tracked in 60f; default behavior and final
readability rollout are tracked in 60g.
