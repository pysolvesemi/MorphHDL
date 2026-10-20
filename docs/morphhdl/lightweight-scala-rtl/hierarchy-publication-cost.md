# Hierarchy publication cost (LWR-32)

The LWR-31 isolated production replay on `075740e4b52a96b7ce59f06cfaf495457a15c34c`
exceeded its 600-second budget. Its retained thread snapshot places the main
thread in finite declaration-width projection through formal-witness matching.
That historical timeout is not a qualification result for this repair.

Formal-witness matching previously rebuilt both components' complete width
binding inventories for each candidate formal. Each inventory validates native
port and packed-Vec width evidence, potentially evaluating and sorting a large
finite domain. Matching now computes each inventory once per search, preserving
exact formal identity, uniqueness and declaration compatibility checks.

Read-only hierarchy analysis additionally uses a transaction-local identity map
of completed binding inventories. Each exact component's declarations, retained
expressions and captured owner domains are validated before its first result is
stored. Equal-schema components never share an entry. The map is thread-local,
is not inherited by other threads, and is discarded on normal or exceptional
exit. Nested transactions have separate maps and restore their caller's map.
Outside this read-only analysis boundary, every call validates afresh. No proof
survives into a phase that can change native declarations or structural capture.
The map must not be used around graph mutation or author callbacks.

No finite domain, physical-width limit, timeout, expression authority or hierarchy
binding check is relaxed. This change avoids repeated proof construction; it does
not substitute witness widths for symbolic geometry or introduce schema-keyed
cross-owner proof caching.

## Validation

Both Scala 2.12.18 and 2.13.12 builds pass. Twelve affected suites pass 87 tests
per version (174 total), including a 65,535-value formal width domain and 200
repeated witness lookups. The new fixture checks separate equal-schema child
owners, copied schema rejection, nested exception unwinding, and changed owner
rejection after a transaction ends. Existing hierarchy, compact argument,
projection, correlation and native publication-lineage negatives also pass.

The test does not assume that filtering an immutable Vector allocates a new
object: Scala 2.13 can reuse the input when every entry survives. Freshness is
checked by invalidating ownership between transactions and requiring rejection.

All 62 application Scala sources compile with both required plugins. The replay
uses those freshly compiled classes, writes only to a temporary evidence
directory, and retains the existing 600-second timeout and 65,536-bit limit.
Application Scala, delivery RTL and cleanup tooling are not edited by this task.
Questa is unavailable; no full CI or full qualification is requested.

Production timing and final source-integrity results are recorded in the
accompanying evidence and publication receipts.

## Production replay outcome

The isolated `GenerateDisplayControllerTop <temporary-dir> 32 128 16` replay
finishes in **367.474 seconds**, below the unchanged 600-second timeout. Peak
resident memory is **2,354,768 KiB (2.25 GiB)**. The retained process snapshot
confirms one production-generation JVM. This is a completed measurement, **not
a successful production publication**: generation exits 1 with
`SPINAL-PARAMETERIZED-VERILOG-FORMAL-ACTUAL-CONNECTION-CONFLICT` on
`cdc.m_live_payload`, whose parent alias retains different parameter roots or
owner domains. No final RTL, production lint, cleanup or application simulation
pass is claimed. Successful full-publication runtime remains unmeasured.

A small diagnostic control reproduces the same rejection with two literal
actuals, a compound-width child output and a parent `cloneOf(child.result)`
alias. Both the repaired compiler and a source-exact overlay of the two changed
files from baseline `075740e4b52a96b7ce59f06cfaf495457a15c34c` reject that control.
All other compiler source is unchanged from that baseline. This separates the
newly exposed connection limitation from the performance repair; it does not
suppress the ownership guard or change application source.

The focused tool ledger records 116 Icarus compilations, 116 simulations,
14 Verilator lint invocations and 30 Yosys checks across both Scala versions.
Sixteen simulation exits are expected failing parameter-guard controls.
Historical LWR-31 timeouts remain unchanged and separate from these results.
The JSON report records commands, source and artifact hashes; durable logs,
thread snapshots and final integrity/publication receipts are under
`~/.local/state/morphhdl/lightweight-scala-rtl/lwr32-20261017/`.
