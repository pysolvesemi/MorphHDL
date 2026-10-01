# Increment 66 local implementation checkpoint

This increment is unfinished and unqualified. Keep the roadmap unchecked.
Work branch: `work/remaining-parameterized-increments`; integration target remains
`parameterized-verilog` at `db54d01e5b21c7664f7a0de3795f061d77a3d259`.
No push or remote CI has been launched for this batch. Increment 59i and the old
wire-assignment-pass roadmap are excluded. No application sources were changed.

This checkpoint records implementation progress, not qualification. Exact source
identity and final validation receipts will be recorded in the evidence README.

All five internal feature steps are implemented under local validation:

- Native assignment publication simplifies proven resizes and supports explicitly
  sized `U(ElabInt, BitCount)` values, including nested expression contexts.
- Explicit native ElabInt child formals, independent declarations, scalar/zero
  HdlInt retention, exact composed width proofs and simulation domain guards.
- Digital CDC fixtures cover 36 profiles, 512 cycles/profile, reset, latency,
  Gray conversion and X/Z inputs. Numeric and X/Z invalid overrides are rejected.
- Explicit HdlRange `whenSelected` loops preserve defaults/priority, packed slice
  writes, process partitioning and four-state behavior. Unsupported bodies fail.
- Immutable `TypedLocalUInt` handles retain native packed localparams and named
  case keys in custom decoders and both AXI4/AXI-Lite factories. Eight factory
  profiles pass independent sequential equivalence and directed real protocol
  simulations including stalls, resets, strobes, bursts/IDs and event counts.

Combined runs passed 107/107 tests on each Scala version (2.12.18 and 2.13.12),
plus 18 compiler-plugin tests on 2.13. A subsequent broader compatibility run
found five legacy derived-child-width regressions. Repairs preserve inferred
carriers, unused outputs and exact frontend declaration roots. The repaired run
passed 188 runtime tests, 35 native copy/expression tests and 18 plugin tests.
The final additions still require validation against committed source.
Additional loop assertions cover ordinary unrolling, name collisions, protected
signals, distinct native output processes and absence of latches.

Temporary logs are `/tmp/morphhdl-increment66-*.log`; they are not final receipts.
The supported surface and limits are documented in
`../../increment-66-native-parameters.md`.

Still required: finish compatibility repairs, both Scala lanes on exact committed
source, affected cleanup/copy/generate/width/factory regressions, native audit and
negative controls, retained after-RTL and tool/input receipts. Then qualify the
ready batch with targeted CI before full CI, preserving the integration pause
until 59i merges. No completion claim or remote monitoring is implied here.
