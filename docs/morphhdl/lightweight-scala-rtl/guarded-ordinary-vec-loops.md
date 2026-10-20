# LWR-36: guarded ordinary Vec loops

Ordinary constant loops that access a native Vec and begin inside an existing
runtime statement scope expand in that original scope. This preserves enclosing
`when`/`otherwise` guards and ordered priority when register lanes also have
individual writers. Expansion happens before structural capture, so it creates
no speculative register aliases or detached assignments to roll back.

The frontend identifies Vec types after Scala type checking and selects a
compiler/runtime bridge. It does not infer ownership from signal names or indexed
syntax: packed Bits accesses use the existing retained-loop path. Native scope
identity determines whether the conservative expansion is necessary. Bounds
still pass the existing immutable-bound checks. Recompile consumers with the
updated compiler plugin and runtime: previously compiled callbacks keep their
original bridge calls.

This is a correctness fallback, not new support for retaining arbitrary mixed
writers. Module-scope ordinary Vec loops, symbolic geometry, and explicit typed
loops retain their existing capture and ownership checks. A symbolic bound is
never unrolled from its default witness. Ordinary constant nonzero ranges and
constant Vec `.length` bounds use the same guarded fallback.

The regression models a four-stage occupancy vector with flush, per-stage
advance, bad-input priority, output stalls and asynchronous reset. It compares
both preservation settings and Vec layouts against an independent scoreboard.
It also retains errors for complete assignment conflicts and undriven/latching
combinational lanes. Existing packed-bit loop, scalar-prefix/register-tail and
typed-register ownership regressions are included in focused validation.

Application Scala and delivery RTL are unchanged. Application replays use
isolated build and output directories. Qualification results and exact identities
are recorded in the adjacent evidence receipt; historical failure evidence is
not counted as a fresh pass.

## Focused validation

Normal Scala 2.12.18 and 2.13.12 builds pass. Across the two versions, 214 test
executions pass: all 28 new mixed-writer executions, all 48 frontend checks,
and the surrounding passing loop/domain/ownership regressions. Two executions
of the same pre-existing `TypedLoopCarrierCoverageTests` writable-`asBools` case
fail with an undriven output. A separate replay against the unchanged
`552bd9fd880daa79d8f731308c0cdd6ffc9756fd` dependency reproduces that failure;
this report does not claim the broader suite is entirely green.

The unchanged standalone CSC generates and passes Icarus Verilog-2001
compilation, Verilator lint and Yosys lowering/driver/latch checks. The old-runtime
CSC control reproduces all four `valid` assignment overlaps and the undriven
`structural_vec_alias_2` latch. Generated artifacts and source/tool identities are
retained under
`/home/kartik/.local/state/morphhdl/lightweight-scala-rtl/lwr36-20261018/`.

Questa is unavailable. No remote CI or full qualification was run.

The unchanged product hierarchy also generates successfully: 77 artifacts in
470.5 seconds. The native generator retried elaboration with source traces
and reported an unassigned `partialGeneration` register warning; those diagnostics
are retained. No application source or generated RTL was patched.
