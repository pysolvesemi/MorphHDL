# Compound child-output aliases (LWR-33)

A parent `cloneOf(child.result)` now projects authenticated compound port widths
through the child's retained formal-to-actual bindings. For a child output
`Bits((A + B) bits)` instantiated with parent `P` and `Q`, the parent clone uses
`P + Q`, preserving the parent declarations and their complete admitted domains.
Literal actuals produce a literal parent width. Bits, UInt and SInt retain their
native types and signedness.

The projection is restricted to immediate child ports and exact formal identities.
It proves every actual is within its formal domain and evaluates the compound
width over the bounded parent domain before issuing a private width certificate.
Existing direct-formal projection is unchanged. Product-domain comparison can
consume these certificates through a read-only bridge that validates ownership
and scope; it cannot accept caller-provided proof tables. Unrelated roots,
forged/copied evidence, incomplete bindings, out-of-domain actuals and mismatched
widths remain rejected. No hardware width is inferred from a construction witness.

The finite proof path retains its existing enumeration limit; this change does
not add unbounded Cartesian-domain projection or support arbitrary expression
connections where hierarchy publication requires a direct carrier.

Focused evidence and the isolated production replay are recorded in the adjacent
`compound-output-aliases.json`. Historical LWR-32 failure evidence is unchanged.
Application Scala and delivery RTL are unchanged. No full CI or full qualification
is required by LWR-33; Questa is unavailable in this environment.

## Fresh focused results (18 October 2026)

Both Scala 2.12.18 and 2.13.12 builds pass, with 90 tests passing per version
(180 total). Coverage includes literal/symbolic aliases, separate equal-schema
children, all three packed types, minimum/maximum overrides, deterministic output,
X/Z and randomized simulation, strict Verilog-2001 compilation, Verilator lint,
Yosys synthesis/checks, and retained ownership/domain/width rejection tests.
All 62 unchanged application Scala sources compile with both plugins.

The isolated `GenerateDisplayControllerTop <temporary-dir> 32 128 16` replay
succeeds in **455.625 seconds**, peak RSS **3,229,524 KiB**, at the unchanged
600-second timeout and 65,536-bit limit. It publishes 77 artifacts and resolves
the reported `cdc.m_live_payload` connection rejection. The generation log retains
Spinal's diagnostic retry and its `live.partialGeneration` unassigned-register
warning. No application tags or source changes suppress that warning.

Full-top Verilator lint exits zero with 11 warnings: seven TIMESCALEMOD, three
WIDTHEXPAND and one UNOPTFLAT on `formatter.ranks`. This is not warning-free lint.
Icarus SystemVerilog compilation passes. Full-top strict Verilog-2001 compilation
rejects the existing external `axivdisplay.v` localparams in its parameter-port
list; no library source was changed. Full application simulation and synthesis
are not claimed. The focused alias fixtures pass those behavioral/synthesis checks.

Commands, logs, tested source hashes, artifact hashes and thread snapshots are
retained under `/home/kartik/.local/state/morphhdl/lightweight-scala-rtl/lwr33-20261018/`.
`publication.json` records the final commit/tree, branch/dependency synchronization
and six current-source integrity checks separately from historical evidence.
