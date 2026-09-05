# Increment 59b replay checkpoint - 2026-09-05

## Scope and status

**59b remains incomplete and unchecked. This is not a merge approval.**

Qualified source commit:
`05bb331dbccb3d76d93aa548f334a076b41e2b28`.

This source includes merged 60c at
`75e581592334e2e596f6e1043beb9596cc20a99b`, the canonical native-review
policy reconciliation, closed native callback observation, conservative
operator-body replay and independent concrete hardware qualification.

The subsequent `e20ae400cd46f912a6dadd947bc4b1555b3431b2` changes only the
main 59b progress document. This evidence record also changes no code. Results
below are attributed to the exact qualified source commit, not to a later head.

## Closed-graph, capture and replay tests

Workflow run: `33972823744`.

| Scala | Job | Result |
| --- | --- | --- |
| 2.12.18 | 101324258482 | PASS, all 73 tests |
| 2.13.12 | 101324258457 | PASS, all 73 tests |

Required suites: 8 native semantics, 8 typed planning, 10 native capture,
12 capture safety/concrete parity, 15 operator-body replay, and 20 closed-graph
observation tests. Native source review and byte-identical regeneration of the
manifest from the canonical review policy passed. No required test was skipped;
the failure-only diagnostic step was correctly skipped on success.

## Independent concrete native replay hardware

Workflow run: `33972823755`.

| Scala | Job | Result |
| --- | --- | --- |
| 2.12.18 | 101324262827 | PASS |
| 2.13.12 | 101324262763 | PASS |

Both jobs passed the 15 operator-replay tests and all 32 hardware configurations:
WIDTH in {1,5,8,32}, COUNT in {1,2,3,5,8,9,16,17}.

Every configuration checks 14 native primitives: Bool/Bits/UInt/SInt AND, OR
and XOR, plus width-preserving modular UInt/SInt addition. The reference and
replay RTL are separately elaborated, then independently regenerated for
byte-identical determinism. Each reference and replay is independently checked
against Python results, compiled/simulated with Icarus Verilog-2001, strictly
linted with Verilator and fully synthesized with Yosys.

For every shape, a combinational miter proves all output bits equivalent for
all input values. The candidate construction requires exactly
`14 * (COUNT - 1)` native replay calls; singleton configurations perform none.
A deliberately mutated observed replay sum produces a genuine satisfying
counterexample and a VCD showing `bad=1` in both Scala lanes. The checker never
accepts missing tools, tool errors, timeout, UNKNOWN or absent proof output.

These results supersede the earlier single-lane operator evidence at 6152fc3:
its Scala 2.12 job was cancelled, whereas both jobs listed above completed.

## What this evidence does not establish

The proven hardware uses **concrete WIDTH and COUNT**. It qualifies native
operator-body replay through the unchanged generic native reduction algorithm.
It does not qualify a generated parameterized COUNT tree, min/max or widening
operator replay, registered bridge replay, arbitrary Scala closure purity,
whole-stage uniformity, or publication after native normalization.

Evidence retains `scope: concrete-native-operator-replay` and
`parameterized_tree_formal: not-run`. The production balanced-stage backend is
not installed, and symbolic-count reduction still rejects unsupported
publication rather than silently emitting its finite native carrier.

Next implementation boundary: combine validated native callback bodies with
symbolic stage/odd-tail/result geometry, validate bridge behavior and complete
generic parameterized publication. Then prove candidate specializations against
independently generated native references, including sequential bridges, and
run all applicable final-head and inherited gates before completing 59b.
