# Increment 57b — typed native StreamFifoCC payload-width formal proof

## Status and dependency

Increment 57b depends on merged Increment 57a. It is a proof successor for the
same authoritative native `spinal.lib.StreamFifoCC`; it does not reopen or
replace the CDC implementation completed by Increment 57a. This document
freezes the evidence contract. The roadmap item remains unchecked on the
implementation revision; after that revision passes every required gate, the
checkbox-only closure revision must pass the same gates before merge.

Increment 57a proved typed `DEPTH` at an eight-bit payload. Increment 57b must
show that retained payload `WIDTH` and `DEPTH` compose correctly across the
native FIFO rather than relying on that construction witness. The candidate
therefore carries independent `ElabInt` values through ordinary
`Bits(WIDTH bits)`, `HardType`, Stream payload cloning, the dual-clock RAM write
and read paths, and the emitted FIFO ports.

## Scope

The implementation under proof remains the sole native FIFO introduced by
Increment 57a. Its ready/valid control, binary and Gray pointers, full and empty
detection, `BufferCC` synchronizers, dual-clock RAM algorithm, occupancy views
and reset buffering are unchanged. Increment 57b may extend the native generic
emission mechanics needed for joint symbolic width and depth, plus fixtures,
formal preparation, miters, boundary tests, workflow evidence and
documentation. Any native edit must be narrowly reviewed and sealed by the
existing source-preservation controls. A proof harness may instantiate the
native FIFO, but it must not add a production MorphHDL FIFO replacement or
copy, reconstruct or specialize any part of the FIFO algorithm.

The typed proof leg must expose independent public integer formals named
`WIDTH` and `DEPTH` for each static reset topology. `WIDTH` controls every
payload-shaped port and storage carrier. `DEPTH` continues to control only the
legal power-of-two FIFO geometry established by Increment 57a. Neither formal
may be inferred from the other's default witness, and neither may be frozen to
the eight-bit Increment 57a proof fixture.

The reference leg must be generated independently for every exact width, depth
and reset topology by ordinary `SpinalVerilog` from the native `Int`
construction:

```scala
new spinal.lib.StreamFifoCC(
  HardType(Bits(width bits)),
  depth,
  pushClock,
  popClock,
  withPopBufferedReset
)
```

It must not share the typed candidate's component, retained parameter metadata,
emitted RTL or preparation output. Separate concrete references prevent a
common parameterized-generation defect from appearing identically on both
sides of the equivalence check.

## Exact formal witness matrix

Every listed dimension is crossed with every other dimension:

| Dimension | Exact witnesses | Purpose |
| --- | --- | --- |
| Payload `WIDTH` | 1, 5, 8, 32 | scalar boundary, odd non-byte width, Increment 57a baseline, matrix upper boundary |
| FIFO `DEPTH` | 2, 4, 8, 16 | every legal power-of-two depth in the Increment 57a override range |
| Pop-reset topology | direct, buffered | both native static reset alternatives |
| Clock ratio | push 2x pop, pop 2x push | both deterministic asynchronous schedule directions |

The Cartesian product contains 64 positive configurations in each enabled
Scala lane. The width-eight slice must remain complete, so extending width
coverage cannot silently remove any of Increment 57a's depth, reset-topology or
clock-ratio configurations.

For each configuration, formal preparation must specialize the candidate with
both exact values before comparison. The reference is the independently
generated native concrete witness for the same `WIDTH`, `DEPTH` and reset mode.
The two clock ratios reuse the deterministic shared schedules and reset
assumptions accepted by Increment 57a; widening this matrix must not weaken
those assumptions or the property set.

## Equivalence observations

The relational miter must bind every candidate and reference port exactly once
with the selected payload width. It compares:

- push ready and pop valid on every observed step;
- push and pop occupancy values;
- the entire `WIDTH`-bit pop payload whenever pop valid is asserted; and
- the same reset-visible behavior and clock scheduling used by Increment 57a.

Payload is intentionally not compared while pop valid is deasserted because
the native FIFO permits a don't-care value then. This mask must be identical on
both legs and must not hide ready/valid or occupancy mismatches.

The positive proof retains Increment 57a's full assertion set, symmetric
initial-state normalization and sound sequential latch correlation before PDR.
Ordinary unknowns remain nondeterministic. A parse-only check, bounded
simulation, an empty property set or a proof that assumes candidate outputs
equal to the reference cannot satisfy this increment.

## Mutation control

At least one non-byte-width configuration must deliberately flip a compared
pop-payload bit after a real CDC transfer. The selected control must use
`WIDTH = 5`, a legal depth and one of the qualified reset/clock schedules. The
unchanged mutation BMC must report a failing payload assertion and retain its
counterexample artifacts.

The control passes only when the solver reports a genuine counterexample.
Missing modules, malformed parameter specialization, parse or preparation
failure, timeout, `UNKNOWN`, tool error, or absence of a trace is a failed gate.
This proves that the width-derived payload comparison is live rather than
vacuous.

## Required evidence

Closure requires all of the following on the exact committed revision:

| Evidence | Required observation |
| --- | --- |
| Matrix identity | exact width set `{1, 5, 8, 32}`, depth set `{2, 4, 8, 16}`, both reset modes and both clock ratios form 64 distinct configurations; the complete width-eight Increment 57a slice remains present |
| Independent generation | one typed parameterized candidate per reset topology and independently elaborated ordinary native concrete references for every width, depth and reset tuple |
| Joint specialization | formal preparation sets both `WIDTH` and `DEPTH`; prepared candidate and reference tops have matching exact payload ports and retain the existing FIFO state/property set |
| Sequential equivalence | every positive configuration passes the existing `lcorr; pdr` proof with ready/valid, occupancy and valid payload observations enabled |
| Mutation | a deliberate compared-payload-bit error at width five produces a solver counterexample with the required assertion diagnostic and trace artifacts |
| Boundary protection | ordinary structural tests and boundary/runtime guards reject deletion of a matrix dimension, fixed eight-bit miter declarations, missing `WIDTH` specialization, shared candidate/reference generation, a disabled payload comparison, relaxed proof engines or a mutation accepted through tool failure |
| Dual Scala and tools | the exact matrix, formal engine and mutation control pass in the canonical Scala 2.12 and 2.13 workflow lanes, with proof artifacts retained |
| Source authority | no FIFO, Gray-pointer, synchronizer, RAM, reset-buffer or CDC control algorithm is copied or reconstructed; the narrow joint-parameter memory-role and invalid-branch payload-carrier edits are explicitly reviewed and sealed in the native-source manifest |

The canonical StreamFifoCC proof workflow must enable the same opt-in formal
gate and preserve one diagnosable result per matrix configuration. A failed,
cancelled or skipped required formal case prevents closure even when all other
configurations pass.

## Limitations and claim boundary

This increment is a finite specialization proof, not quantification over an
unbounded Verilog parameter. A checked and merged Increment 57b establishes
equivalence only for the exact 64 configurations above in each required Scala
lane. In particular, it does not claim:

- formal coverage of payload widths other than 1, 5, 8 and 32, including every
  integer between the selected points or widths above 32;
- payload-width zero or another invalid shape is supported;
- arbitrary payload types, signed payload semantics, nested aggregate packing,
  masks, initialization or mixed-width FIFO ports;
- non-power-of-two depths or depths outside 2, 4, 8 and 16;
- all asynchronous clock frequencies, phases or interruption schedules;
- recovery from arbitrary unilateral mid-traffic reset; or
- analog metastability behavior of the physical synchronizers.

The inherited typed-shape and Increment 57a tests may support broader behavior,
but they do not enlarge this formal claim without a separately recorded proof
increment.

## Closure record

The roadmap checkbox is an evidence-only transition. It stays `[ ]` on the
implementation revision until the exact source scope is reviewed and every
canonical Increment 57b job, including both Scala lanes and the mutation
control, passes. Changing `[ ]` to `[x]` is the final source change; that checked
revision must pass the same required workflow before merge.

Increment 58 remains blocked until the checked Increment 57b revision is merged
into `parameterized-verilog`.
