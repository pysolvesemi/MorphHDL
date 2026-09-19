# Increment 53a — Native StreamFifo concrete-witness formal equivalence

## Objective

Increment 53a adds the missing semantic closure for the already merged
Increment 53. It does not reopen or uncheck Increment 53. It proves that the
single parameterized native `StreamFifo` emitted by MorphHDL is sequentially
equivalent to independently elaborated ordinary SpinalHDL witnesses at depths
1, 3, 5 and 8.

The proof boundary is the complete generated harness, not only the child FIFO
module. This intentionally covers parent-to-child parameter binding, derived
port widths, instance slices and fixed-width top-level adaptation in addition
to the FIFO algorithm itself.

## Independent DUT generation

Both sides originate from the real, untouched `spinal.lib.StreamFifo` source,
but they are elaborated independently:

- Each reference witness is generated in a fresh elaboration with ordinary
  `SpinalVerilog` and a literal native `Int` depth of 1, 3, 5 or 8.
- The candidate is generated once with `MorphVerilog` from the Increment 53
  parameterized harness, then instantiated four times with `.DEPTH(1)`,
  `.DEPTH(3)`, `.DEPTH(5)` and `.DEPTH(8)` in separate proofs.
- Concrete and parameterized modules receive disjoint deterministic definition
  names so that a shared formal model cannot resolve both legs to one module by
  accident.
- A concrete reference containing a `DEPTH` formal parameter is rejected. A
  candidate regenerated separately for each concrete depth is also rejected:
  the proof must exercise one shared parameterized definition.
- The outer harness exposes the same fixed-width ABI on both legs, including
  8-bit stream payloads and 4-bit occupancy and availability. No child-only
  comparison may bypass hierarchy-level width propagation.

No separately authored FIFO, behavioral reference model, emitted-name
recognizer or replacement of native FIFO logic is accepted as a proof witness.
The ordinary concrete path must remain ordinary SpinalHDL, and generated DUT
RTL must remain valid strict Verilog-2001.

## Sequential equivalence model

For each depth, one formal miter drives both DUTs from the same clock and the
same arbitrary inputs:

- push valid and 8-bit push payload;
- pop ready;
- flush; and
- synchronous active-high reset.

The miter constrains reset high on the first sampled active clock edge. This
establishes a shared reachable base state without assuming equality of
uninitialized implementation storage. Reset is shared but otherwise arbitrary
on later edges, so a later reset remains part of the proved input space rather
than an excluded behavior. Assertions begin only after the initial reset edge
has taken effect and remain enabled thereafter.

The proof compares these public observations on every asserted cycle:

| Observation | Equivalence condition |
|---|---|
| push ready | always equal |
| pop valid | always equal |
| occupancy | all four top-level bits always equal |
| availability | all four top-level bits always equal |
| pop payload | equal only when the already-equal pop-valid signal is asserted |

The payload guard is required because a FIFO with no valid pop item may expose
unwritten or otherwise unspecified memory data. Constraining or comparing that
invalid payload would prove an implementation artifact instead of the stream
contract. All traffic inputs remain arbitrary; the miter must not assume away
full, empty, simultaneous push/pop, flush, wraparound or later-reset cases.

## Required proof strength

The positive gate uses SymbiYosys in `mode prove` with solver-backed base-case
checking and temporal induction, or an equivalent solver-backed unbounded
sequential proof. Acceptance requires a formal `PASS` for every depth. A fixed
trace depth alone is not a completeness argument and cannot close this
increment, even if simulations and bounded model checking find no mismatch.

The following remain useful supporting gates but are not equivalence evidence
on their own:

- simulation or randomized traffic;
- Verilator/Icarus parsing and lint;
- Yosys hierarchy, synthesis or `check`;
- structural or emitted-text comparison; and
- a proof in which both legs are concrete elaborations or both resolve to the
  same generated module.

Proof setup must fail closed on missing modules, undriven or multiply driven
ports, unsupported formal constructs, solver errors, timeouts and unknown
statuses. Only a solver-completed `PASS` closes a positive case.

## Live-gate mutation control

A separate negative control copies the parameterized candidate, changes one
compared MorphHDL observable for DEPTH=3, and runs the same miter assumptions.
For example, it may invert the candidate's top-level push-ready observation.
The control succeeds only when the solver reports formal `FAIL` and preserves
a trace identifying a violated equivalence assertion.

A nonzero process exit is insufficient: parse errors, missing modules, solver
startup failures, timeout and `UNKNOWN`/`ERROR` status must fail the workflow.
The production RTL is never modified by this control, and depths 1, 5 and 8
remain positive proofs.

## Reproducible toolchain and matrix

The permanent workflow runs two independent matrix legs, Scala 2.12.18 and
Scala 2.13.12. Each leg regenerates both DUT families and runs all four positive
proofs plus the DEPTH=3 negative control; one Scala line may not consume RTL or
proof status produced by the other.

Formal execution is pinned to the repository's stable
`ghcr.io/spinalhdl/docker:v1.2.0` image and the exact Mill 1.1.0 distribution.
The workflow checks and records the available `yosys`, `sby` and `yices-smt2`
executables and their versions before generation, and fails before testing if
the expected formal stack is unavailable. It must not silently fall back to a
host executable, a fake EDA shim, an unpinned `latest` image or simulation-only
validation.

## Completion contract

Increment 53a is complete only when both Scala matrix legs establish all of the
following on the exact candidate revision:

- independent concrete witness generation at depths 1, 3, 5 and 8;
- one deterministic MorphHDL parameterized definition shared by all proofs;
- full top-level solver-backed sequential equivalence at all four depths;
- the first-edge reset and invalid-payload rules above;
- a genuine DEPTH=3 mutation counterexample;
- strict Verilog-2001, deterministic generation and native-source boundary
  checks; and
- all inherited Increment 53 generation, simulation, lint and synthesis gates.

Only after that revision and its roadmap-checkbox revision both pass the full
required CI may Increment 53a be checked and merged. Increment 54 depends on
that merged proof closure.
