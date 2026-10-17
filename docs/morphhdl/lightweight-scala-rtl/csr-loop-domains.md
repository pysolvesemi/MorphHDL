# CSR loop bounds and compact optional domains (LWR-35)

Ordinary hardware statement loops now accept immutable nonzero Int starts.
Endpoints are checked after Scala typing: mutable values and effectful getters
remain rejected. Range lengths use BigInt arithmetic to detect overflow before
conversion. Empty ranges execute no body; active indices must be nonnegative.
The compiler rebases the captured index through its authenticated affine-index
API; application Scala does not need rebasing.

A direct immutable native Vec `.length` bound uses the exact Vec's retained
logical depth. It does not read a parameter-dependent carrier-count witness.
The bridge's typed Vec argument excludes arbitrary getters named `length`, and
the owner must be immutable. Fixed and parameter-sized Vecs share this path.

`Param.optional` no longer imposes an enumerated-domain size ceiling. Existing
formal declaration/domain validation, declaration ownership, absence/default
validation and recursive presence checks remain authoritative. This supports
nonnegative integer capacities through 2147483647 without narrowing the range.
Absence still omits the formal; `Some(0)` still declares it.

Focused tests cover separate scalar prefix plus retained register tail, reset,
enable stalls, empty/singleton ranges, inclusive combinational array slices,
Vec-depth overrides, capacity boundaries, recursive forwarding and invalid
bounds/owners. Existing ordinary-loop and optional-recursion suites remain gates.

This does not expand arbitrary register writer partitioning: multiple separately
captured partial loops still require the existing ownership/coverage proof.
Symbolic starts, negative active indices, effectful bounds, and unsupported host
collection traversal are not newly admitted. No generated-text transformation or
application Scala change is involved. No full CI/full qualification is requested.

Compact scalars can be read in authenticated structural branches on unrelated
parameter roots. Their interval is unchanged; this does not grant a narrowed
compact-domain proof. Typed native recursive self-edges may forward an unchanged
compact scalar while a separate finite parameter proves termination. The exact
self-edge, parent formal/schema and captured path are authenticated. Changing the
scalar on a recursive edge is rejected. Compact recursion metrics and compact
recursive port geometry are outside this supported subset. Ordinary captured
compact child declarations still retain their existing rejection checks.

## Focused evidence

184 checks pass across Scala 2.12.18 and 2.13.12: 70 runtime/domain tests and
22 frontend checks per version. The runtime suites include Icarus Verilog-2001
compilation/simulation, Verilator lint and existing Yosys checks. Questa is
unavailable. This is focused compiler/library evidence, not production CSR or
full-application qualification.

The adjacent JSON records commands, source hashes, suite inventories and tool
ledgers. Retained evidence is under
`/home/kartik/.local/state/morphhdl/lightweight-scala-rtl/lwr35-20261018/`.
The final runtime/owner changes were compiled separately against the built
compiler on both Scala versions for prompt repair testing; the recorded
classpath puts those exact classes first. Normal sbt build refresh and final
source-integrity/publication receipts are recorded separately there.
