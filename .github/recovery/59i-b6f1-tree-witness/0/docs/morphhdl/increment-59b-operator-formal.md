# Increment 59b: native operator replay hardware qualification

## Scope

These gates compare **concrete** native operator replay against separately
elaborated ordinary SpinalHDL reference RTL. They do not claim parameterized
COUNT stage publication, registered bridge replay, min/max or widening-tree
support. The controlling 59b checkbox remains unchecked.

The matrix is WIDTH in {1, 5, 8, 32} crossed with COUNT in
{1, 2, 3, 5, 8, 9, 16, 17}, on Scala 2.12.18 and 2.13.12. Every shape exposes
all 14 admitted native primitive outputs: Bool/Bits/UInt/SInt AND/OR/XOR and
modular UInt/SInt addition. Each formal miter quantifies every input bit,
including independent Bool operands, rather than only the simulation samples.

## Independent construction paths

`TypedBalancedReductionOperatorArtifactWriter` elaborates each reference and
replay component separately. The reference calls the unchanged native generic
`reduceBalancedTree` with the ordinary DSL operation. The replay side captures
the actual operation graph on driven probes, certifies the first operator
body, and invokes `Proof.replay` from the same unchanged native generic
reducer. No handwritten tree or Verilog operation substitutes for the native
algorithm. A construction assertion requires exactly `14 * (COUNT - 1)` replay
calls; singleton shapes require no body capture and no replay calls.

The entire generation is repeated independently. Each reference and replay
RTL file must be byte-identical to its corresponding second generation.

## Executable checks

Both sides undergo strict Verilog-2001 Icarus compilation/simulation, Verilator
lint and full Yosys synthesis with a failing structural check. Simulation
compares both sides independently against Python arithmetic and bitwise
results, not just against one another.

A Yosys combinational miter compares all 14 output bit patterns. Success
requires a zero exit status and the solver's definitive no-model SUCCESS
result. The mutation changes the observed replay sum's low bit, then requires
a definitive counterexample and a generated VCD with `bad = 1`. Missing tools,
syntax errors, nonzero tool exits, timeouts and inconclusive output are never
accepted as proofs or successful mutations.

Artifacts record `scope: concrete-native-operator-replay` and
`parameterized_tree_formal: not-run`. Results belong to the exact source head
in `head.txt`; adding the workflow is not evidence that it has passed.
