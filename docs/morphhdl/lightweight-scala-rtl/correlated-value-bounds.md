# Correlated unsigned value bounds (LWR-29)

A retained scalar value may use unconditional legality requirements belonging to
its own component to prove that its legal values fit its carrier. For example:

```scala
val capacity = depth.pow2 + 1
require(margin >= 1 && margin < capacity)
val threshold = ElabValue.uintLike(capacity - margin, U(0, 18 bits), "threshold")
```

With depth 2..16, the legal threshold is 1..65536. The original symbolic
subtraction and parameter domains remain unchanged. The component still emits
its legality and domain guards, including when instantiated independently or
overridden in downstream HDL. A parent requirement cannot substitute for a
missing child requirement.

The proof uses authenticated expression atoms and declaration-root identities,
not Verilog names or expression strings. For a retained inequality `f >= k`,
it bounds `target = f + (target-f)` and `target = -f + (target+f)`. Normalization
cancels identical authenticated terms; conservative enclosures bound the
remainders. Strict integer inequalities contribute a bound of one. Conjunctions
contribute both facts; disjunctions contribute none. This avoids enumerating the
Cartesian product of parameter domains.

For compact symbolic counters, an additional authenticated rule proves that a
nonnegative value `v < n` fits a `log2Up(n)` carrier. This handles
`timeout - 1` in `(timeout + 1).log2Up` bits without comparing the maximum value
against the minimum width from a different parameter value. Oversized values
still fail the normal width check.

The contractual enclosure applies to native compositional expression certificates;
existing exact single-root pointwise validation remains unchanged.

The same query runs at value construction and native publication, after the
existing value/owner authentication. It does not change the stored expression
certificate, independent parameter bounds, widths, arithmetic, or defaults.
Scoped requirements are excluded. Structural consumers do not gain new authority
from deferred requirements. Unsupported implications remain conservatively
rejected; this is not a general constraint solver.

Legality guards retain the existing simulation-only contract: synthesis assumes
legal parameter overrides. Guard checks must not be disabled when validating
configuration legality.

## Focused validation

Fresh local checks on Scala 2.12.18 and 2.13.12: **86 tests passed** across
six suites per version. These cover direct child contracts, parent-to-child
bindings, invalid child overrides, missing/disjunctive positivity, insufficient
carriers, compact logarithmic counters, and existing configuration/value tests.
There were 492 recorded HDL-tool invocations: 454 succeeded and 38 invalid
configuration simulations intentionally failed. The runs include strict
Verilog-2001 compilation, Icarus simulation, Verilator lint, Yosys synthesis,
no-latch checks and existing value-operation formal equivalence.

All 62 application Scala sources compiled. The production top generator was
replayed with the same entry point and profile as `generate_rtl.py`, redirecting
output into an isolated directory. Both value-carrier blockers are gone. The
replay **does not pass**: `config_0_transport.i_a_data` has admitted width
1..65536, exceeding the existing BlackBox port limit 4096, and publication
rejects it with `SPINAL-PARAMETERIZED-VERILOG-BLACKBOX-PORT-WIDTH-DOMAIN-INVALID`.
The physical-width limit and application domain were not changed. The replay
log also retains the native trace retry and unassigned-register warning; none
is presented as passing application evidence. No production RTL lint or
simulation is claimed. Application Scala and 74 protected delivery artifacts
remain unchanged.

Commands, source hashes, tool logs, artifact hashes and the separate production
blocker are recorded in [the evidence receipt](correlated-value-bounds.json).
Durable evidence is under
`/home/kartik/.local/state/morphhdl/lightweight-scala-rtl/lwr29-20261017`.
Questa is unavailable. No CI or full qualification was requested or run.
