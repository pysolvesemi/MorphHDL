# Ordinary-loop capture compatibility (LWR-26)

Ordinary zero-based hardware loops keep their existing automatic-capture policy.
Packed `Bits` accesses can now use affine inclusive ranges on either side of an
assignment:

```scala
for (i <- 0 until count) {
  output(i * width + width - 1 downto i * width) :=
    input(i * width + width - 1 downto i * width)
}
```

The two bounds must belong to the same exact finite-loop index and have equal
strides. The selected width must be positive, the base nonnegative, and the entire
range must fit the source throughout its authenticated parameter domain. This is
a static packed selection; it does not convert an index to a runtime address.
The selected value retains `Bits` semantics and existing explicit signed/unsigned
casts. `Int`, ordinary Scala ranges and existing explicitly typed loop APIs retain
their previous meanings.

Subword writes retain disjoint bit intervals for native driver and latch checks.
Fixed geometry uses the exact intervals. Symbolic geometry additionally requires
an authenticated complete partition of each word in the same assignment scope.
A bounding interval cannot stand in for a set containing holes. Existing native
assignment order and overlap checks remain in effect.

An index-dependent `if` expression can return `Bool`, as well as the existing
statement-only `Unit` form. Boolean expressions are combinational and their
consumers retain surrounding runtime enables; they do not create storage.
Branches may construct local expression logic but cannot write existing or foreign
signals. Arbitrary result objects and side-effecting callbacks are not supported
by this adapter.

## Host topology

Automatic capture conservatively preserves ordinary Scala elaboration for:

- Views constructed with `Vec(existingScalarA, existingScalarB, ...)`, whose
  members retain independent scalar-port ownership rather than becoming a new
  aggregate interface.
- Recognized Scala `Array`, `Seq`, `Vector` and `List` accesses, including their
  supported collection transformations.
- Nested filtered host traversal, index-derived explicit names, and updates to
  a shared scalar rather than an iteration-owned target.
- Shifted lookahead recurrences with multiple conditional writes to a carrier.
- Local packed buffers with additional indexed assignments outside the loop;
  their authored defaults and scalar patch priority remain in the host process.
- Uninitialized register collections and register arrays sharing writers with a
  host-collection loop. All ordinary writers of such shared arrays retain one
  host topology, preserving reset and update priority.

This classification only opts out of automatic capture. It does not authorize
symbolic geometry based on syntax. An explicitly typed bound combined with an
unsupported host-collection topology receives
`MORPHDL-TYPED-RANGE-HOST-TOPOLOGY`; no parameter witness is substituted for its
bound. Independent supported loops still emit generate loops without a global
`ordinary-loops=host` option.

Shared module controls retain their authored placement when they depend on an
already authenticated, module-visible packed loop result. Native scalar wrappers
are retained only after matching their exact registered operator and operand
identities to one emitted declaration and driver. Loop-local declarations and
unproven escapes still go through the existing ownership/dominance checks.

Focused evidence is recorded separately from historical failing attempts. No
application source, delivery RTL or cleanup changes are necessary for these APIs.

## Focused validation — 17 October 2026

[Machine-readable evidence](ordinary-loop-compatibility.json) records the exact
commands, source/plugin hashes, generated artifacts, tool logs and adaptations.
The tested compiler was built over base commit
`f9be9a2cc39386aac55ff6e0b6ac152e0e2f21cc` with the source overlay authenticated
by those hashes. Runtime banners retain that build-base identity; they are not
claims of an untouched-base pass. The publication receipt and application roadmap
record the final commit whose compiler/test sources match this evidence.

- Scala 2.12.18 and 2.13.12: 121 runtime tests and 148 frontend tests each,
  with no failed, skipped or pending tests (538 total).
- Runtime tool receipts: 434 successful commands per Scala version, including
  Icarus compilation/simulation, Verilator lint, Yosys checks and bounded mutation
  controls. These include negative generation/ownership/domain cases and X/Z
  simulation inputs.
- All 62 unchanged application Scala sources compile with both required plugins.
  Eleven generation profiles pass: product top, CSR, DPI PPC1/PPC4, live PPC1/PPC4,
  status, 422, CDC, timing and standalone selector.
- Nine unit configurations pass: CSR, DPI PPC1/PPC4, live PPC1, status, 422,
  selector and timing PPC1/PPC4. Selector and timing use compiled Verilator with
  the original benches, stimuli, assertions and timeout/profile settings. Their
  long Icarus attempts exceeded the existing 180-second limit and remain recorded
  as incomplete attempts, not passes. Timing validates geometry/logic, not
  physical clock/rate or final customer DPI acceptance.
- Generated top/selector files pass strict Verilog-2001 parsing separately from
  external modules. Complete hierarchy compilation and lint use SystemVerilog
  because unchanged vendor IP uses `$clog2` and parameter-port `localparam`.
  Other selected generated modules pass their recorded Verilog-2001 checks.
- Application lint uses `-Wno-fatal`; warnings are retained. DPI's `ranks` array
  has a forward-only prefix chain that triggers `UNOPTFLAT`; both profiles pass
  Yosys zero-SCC, driver and no-latch checks as well as simulation. Vendor
  timescale/width diagnostics remain visible.

The DPI bench requires a temporary copy declaring two existing output ports for
its wildcard connection; the source/adapted hashes are retained, with no stimulus
or assertion change. The existing live-pipeline bench is PPC1-only: PPC4 has
compilation/generation/lint evidence, not an application simulation claim.

Artifacts and runners are archived under
`/home/kartik/.local/state/morphhdl/lightweight-scala-rtl/lwr26-20261017`.
Earlier failing exploration logs remain distinct. No application Scala, delivery
RTL or cleanup was edited. Questa is unavailable; no CI, full qualification or
new formal campaign is claimed.
