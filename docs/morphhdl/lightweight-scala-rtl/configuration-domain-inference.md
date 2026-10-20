# Configuration-domain inference (LWR-28)

Configuration requirements now admit literal Scala `Int` arithmetic and shifts,
including `timeout <= (1 << 24)`. Folding uses 32-bit overflow, signed/unsigned
right shift, and JVM shift-distance masking (including negative distances).
Symbolic shifts in configuration derivations remain explicit diagnostics; this
feature does not reinterpret them as host operations or parameter witnesses.

Unconditional authenticated requirements infer conservative intervals. Conjunctions
intersect bounds; disjunctions join branch envelopes; dependent bounds propagate
through authenticated arithmetic and `pow2` with a proven exponent range. Analysis
uses bounded passes over declarations, independent of domain cardinality. Neither
defaults nor conditional/shadowed `require` calls establish parameter bounds.
Unknown or cyclic bounds remain errors. Original requirements remain symbolic
legality guards, so an interval `[1,4]` for `lanes == 1 || lanes == 4` does not
make 2 or 3 legal.

For the production CDC contract this retains:

- lanes: envelope 1..4, with the original sparse alternatives;
- FIFO exponent: 2..16; capacity: `2**fifoLogDepth + 1`;
- full margin: envelope 1..65536, constrained to less than capacity;
- empty threshold: envelope 0..65536, constrained to less than capacity;
- clock/configuration timeouts: 32..16777216.

Large native integer formals use identity-authenticated compact declarations in
the existing symbolic expression authority. There is no exhaustive table for
these domains. Native declarations mint authority from the declaration object;
public expression copies cannot inherit it. The previous external frontend
permit path remains separate. Zero-based compact domains are supported.

This does not grant arbitrary structural enumeration or branch projection over
large domains. Consumers needing exact exhaustive evidence keep their existing
limits and diagnostics. Packed parameter representation, width safety, lexical
ownership and emitted legality checks remain enforced.

Validation is recorded separately in `configuration-domain-inference.json`.
Application Scala and delivery RTL are not edited by this increment. Fresh local
artifacts are generated outside the application checkout. No full CI or full
qualification is required by LWR-28; no such result is claimed here.

## Fresh local validation — 17 October 2026

Tested source starts from `2daae06ab2382e1e0cc4364e88910e4bd6bf133d` with the
source hashes and plugin hashes in the adjacent receipt. This is fresh working
source evidence, not a claim that the unchanged starting commit contains the fix.
The final publication receipt records the sealed commit/tree and verifies source
equality against these runs.

- Scala 2.12.18 and 2.13.12: 172 frontend and 18 native tests each, **380 passed**.
- HDL tools: 172 recorded invocations; 146 successes and 26 intentionally failing
  simulations that confirm illegal overrides hit the emitted guards.
- Icarus Verilog-2001 compilation/simulation, Verilator lint and Yosys synthesis
  and no-latch checks passed. Simulation-only guards are excluded for lint with
  `SYNTHESIS`, as in existing focused tests.
- All 62 migrated application Scala sources compile. The production
  `ClockResetCdcConfigChecks.check()` also passes, including its rejection cases.
- The generic CDC-domain RTL fixture contains the actual production configuration
  contract, checked byte-for-byte after fixture class-name substitution. Its
  symbolic `C_*` formals, derived geometry, overrides and deterministic emission
  are tested. This is not full product RTL generation or qualification.
- Oversized physical ports and unauthenticated public expression summaries remain
  rejected. Existing derived/nested/typed/recursive configuration tests pass.

Commands, source/artifact hashes, test XML, simulator logs and tool versions are
retained under
`/home/kartik/.local/state/morphhdl/lightweight-scala-rtl/lwr28-20261017`.
The final runs are `lwr28-frontend3-{212,213}`, `lwr28-runtime3-{212,213}` and
`lwr28-app-compile2`; earlier exploratory runs are not passing evidence.
Questa is unavailable. No CI, full qualification or new formal campaign was run.
