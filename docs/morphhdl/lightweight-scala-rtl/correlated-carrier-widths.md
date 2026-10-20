# Correlated carrier widths and typed local initializers (LWR-41–42)

Unsigned scalar value sizing can use unconditional, component-owned retained
parameter contracts together with the carrier's symbolic width. The proof
partitions the finite width axes and encloses the remaining value axes under the
same authenticated comparisons in each partition. For example, a FIFO with
`DEPTH` in 2..16, `capacity = DEPTH.pow2 + 1`, and
`1 <= MARGIN && MARGIN < capacity` can place `capacity - MARGIN` in a
`DEPTH + 2` bit carrier. An empty threshold constrained to
`0 <= EMPTY && EMPTY < capacity` uses that same carrier.

This does not narrow structural parameter domains. Runtime domain guards remain
in the emitted module. Parent-only constraints, branch-local requirements and
unproved disjunctions cannot grant this scalar sizing authority. Contradictory
contracts do not prove an otherwise invalid carrier. Width axes that are compact
or exceed the existing joint proof budget fall back to the existing conservative
checks (including the authenticated logarithmic-width proof); they are not
sampled at witness defaults.

A whole unsigned typed local initializer such as

```scala
val TIMEOUT: Param[Int] = Param(1024)
require(TIMEOUT >= 32 && TIMEOUT <= 16777216)
@localparam val DEADLINE = U(TIMEOUT - 1, (TIMEOUT + 1).log2Up bits)
```

uses the retained elaboration expression directly in the typed localparam.
Carrier representability and exact assignment lineage are still checked. Nested
hardware arithmetic keeps explicit operand sizing and intermediate localparams,
so modular overflow is preserved. Signed adapters retain their established
sizing path. This is not a general expression-inlining optimization.

Validation receipts record focused local checks only; no CI or full qualification
is requested for these roadmap items.

Verilator 5.020 reports `WIDTHTRUNC` for the requested direct deadline initializer:
the integer expression is 32 bits while the localparam has its proven smaller
width. The domain proof establishes that no value is lost. Focused lint records
this warning using the existing nonfatal-warning invocation; no suppression
pragma is emitted. Existing fixed-width constant forms keep their sized helpers
and their strict-lint regression remains enabled.
