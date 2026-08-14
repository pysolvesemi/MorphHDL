# Executable architecture fixtures

These files are executable output contracts for the parameterized backend.

- `parameterized_wire.v` is owned by Increment 2. Increment 8 authors its
  symbolic width through the guarded frontend and generates it through the
  public `MorphVerilog` orchestration.
- `derived_width.v` is owned by Increment 3. Its two frontend public parameters feed an
  acyclic local-parameter expression graph and its packed port width remains
  symbolic in the single public module definition.
- `parameter_forwarding.v` is owned by Increment 4. Its Morph program generates its one leaf
  and one parent definition together, checks a derived named child-parameter
  binding and proves the exact elaborated hierarchy before synthesis.
- `lane_array.v` is owned by Increments 5 and 6. Increment 5 established its
  ParamRTL/backend contract; Increment 6 replaces the manually assembled loop
  fixture with frontend-authored Scala using `0 until HdlInt`. CI still
  generates one `PixelLane` and one `LaneArray` definition with a named
  generate-for loop, a scoped symbolic index and parameterized indexed
  part-select bindings. Its pre-synthesis structural gate resolves Yosys child
  types dynamically, so it does not depend on implementation-specific
  `$paramod` names or generated-cell names.
- `conditional_forwarding.v` is owned by Increment 9. Its typed Boolean
  `ENABLE` parameter selects one of two explicitly labeled generate-if blocks.
  Both blocks retain the same named, width-forwarding `ConditionalLeaf`
  instance, while the default concrete witness contains exactly one child.
- `comparison_routing.v` is owned by Increment 10. Its bounded integer
  `SELECT >= THRESHOLD` condition selects distinct `HighRoute` and `LowRoute`
  children while preserving one fixed eight-bit public interface.
- `conditional_width.v` is owned by Increment 11. Its typed Boolean `WIDE`
  parameter selects `WIDE_WIDTH` or `NARROW_WIDTH` into the derived
  `ACTIVE_WIDTH` local parameter, which controls both packed ports.
- `boolean_forwarding.v` is owned by Increment 12. Its parent forwards the
  typed predicate `ENABLE && EFFECTIVE_WIDTH >= LIMIT` into child Boolean
  parameter `SELECT`; the child selects distinct high/low leaf types while all
  public interfaces remain fixed at eight bits.
- `boolean_locals.v` is owned by Increment 13. Its combined local graph flows
  from integer `EFFECTIVE_WIDTH` through Boolean `WIDTH_OK` and `ROUTE_HIGH`,
  back into integer `ROUTE_CODE`, and then into a typed Boolean child binding.
  The child selects distinct high/low leaf schemas behind a fixed eight-bit
  interface.
- `case_routing.v` is owned by Increment 14. Its integer local
  `SELECTOR = MODE + OFFSET` selects explicit zero/one route schemas or one
  mandatory default route behind a fixed eight-bit interface.
- `runtime_mux.v` is owned by Increment 15. Its one-bit runtime `sel`
  drives a complete two-path combinational process over two `WIDTH`-bit input
  ports and one process-owned output.
- `synchronous_register.v` is owned by Increment 16. Its one-bit `clk` and
  active-high synchronous `reset` drive one positive-edge process that clears
  or captures a `WIDTH`-bit input into its sole registered output.
- `asynchronous_register.v` is owned by Increment 17. Its one-bit `clk` and
  active-high asynchronous `reset` drive one positive-edge process whose reset
  assertion immediately clears its sole registered output.
- `synchronous_enabled_register.v` is owned by Increment 18. Its active-high
  synchronous reset has priority over active-high enabled capture, while a low
  enable intentionally holds the sole registered output.
- `asynchronous_enabled_register.v` is owned by Increment 19. Its active-high
  asynchronous reset asserts immediately and has priority over active-high
  enabled capture, while a low enable intentionally holds state.
- `single_port_memory.v` is owned by Increment 20. Its independent positive
  `WIDTH`/`DEPTH` parameters feed one guarded positive-edge synchronous
  read-first whole-word port; surplus addresses read zero and ignore writes.
- Increment 8 routes the first four artifacts through one production fixture
  source and `MorphVerilog`; Increments 9 and 10 extend that same path to all
  six. Increment 11 extends it to all seven, Increment 12 to all eight, and
  Increment 13 to all nine, Increment 14 to all ten, and Increment 15 to all
  eleven. Increment 16 extends it to all twelve, and Increment 17 to all
  thirteen, Increment 18 to all fourteen, Increment 19 to all fifteen, and
  Increment 20 to all sixteen. CI
  performs a normal and reverse-construction run, requires an exact
  sixteen-file inventory, checks
  byte identity with these goldens, and gives that unmodified directory to the
  external tool gates.
- The generated-fixture testbenches cover default, minimum, awkward and mixed
  overrides. `DerivedWidthTb` checks widths 35, 4, 18, 27 and 23 in five
  simultaneous instances of the same emitted module. `ParameterForwardingTb`
  checks forwarded widths 32, 1, 15, 24 and 20 through five simultaneous
  instances of one emitted hierarchy. `LaneArrayTb` exercises the same five
  flattened widths while the structural gate proves 4x8, 1x1, 3x5, 3x8 and
  4x5 exact lane partitions. `ConditionalForwardingTb` covers the true default,
  `ENABLE=0`, enabled width 5 and disabled width 13; Yosys independently proves
  that each configuration retains exactly one selected leaf with the expected
  port width. `ComparisonRoutingTb` covers the high default, a below-threshold low override
  and the inclusive equality boundary; Yosys proves exactly one child of the
  expected route type and exact port bindings for all three configurations.
  `ConditionalWidthTb` covers the wide default, the narrow choice and awkward
  overrides of each selected branch. Yosys independently proves exact 12-,
  4-, 15- and 7-bit packed interfaces after parameter elaboration.
  `BooleanForwardingTb` covers the true default, an explicit Boolean disable,
  a below-limit comparison and the inclusive equality boundary. Yosys proves
  the exact forwarded route instance and selected high/low leaf for all four
  configurations.
  `BooleanLocalsTb` covers the high default, an explicit disable, a
  below-limit low route and the inclusive equality high route. Yosys proves
  the exact selected leaf and direct fixed-width port bindings in all four
  elaborated hierarchies.
  `CaseRoutingTb` covers the zero default, direct choice one, an offset-derived
  choice one and an unmatched mandatory-default selection. Yosys proves the
  exact labeled branch, route type and direct fixed-width bindings in all four
  elaborated hierarchies.
  `RuntimeMuxTb` covers select false and true at both the default eight-bit
  width and an awkward five-bit override. Yosys proves exact mux connections,
  port widths and absence of latch or flip-flop storage for both widths.
  `SynchronousRegisterTb` proves that reset and data do not change state away
  from a positive edge, then checks synchronous zeroing and later capture in
  simultaneous default eight-bit and awkward five-bit instances. Yosys proves
  one positive-edge flip-flop, no latch, active-high synchronous zero reset and
  exact data/output bindings and widths for both parameter values.
  `AsynchronousRegisterTb` proves reset assertion without a positive edge,
  reset priority, no capture on deassertion and later positive-edge capture in
  simultaneous default eight-bit and awkward five-bit instances. Yosys proves
  one positive-edge flip-flop with active-high asynchronous zero reset and
  rejects synchronous-reset, falling-edge-clock and reset-to-ones mutations.
  `SynchronousEnabledRegisterTb` proves reset priority, enabled capture,
  disabled hold, later capture and synchronous reset timing in simultaneous
  default eight-bit and awkward five-bit instances. Yosys proves one
  positive-edge active-high enabled flip-flop with active-high synchronous
  zero reset, and rejects disabled-capture, reversed-priority,
  active-low-enable, falling-edge and reset-to-ones mutations.
  `AsynchronousEnabledRegisterTb` proves immediate reset without a clock edge,
  reset priority while enable is high, no capture on reset deassertion,
  enabled capture, disabled hold and later capture in simultaneous default
  eight-bit and awkward five-bit instances. Yosys proves one positive-edge
  active-high enabled flip-flop with active-high asynchronous zero reset and
  rejects disabled-capture, reversed-priority, active-low-enable,
  falling-edge, synchronous-reset and reset-to-ones mutations.
  `SinglePortMemoryTb` covers default 8x5, awkward 5x3 and minimum 1x1 shapes
  simultaneously. It proves synchronous output timing, valid last addresses,
  disabled writes, read-first collision, later write visibility, zero reads
  for surplus addresses and ignored surplus writes. Yosys proves one exact
  retained memory, positive-edge output state, the memory-or-zero read path
  and the active-high in-range whole-word write guard, then rejects
  write-first, swapped-read-branch, falling-edge, unconditional-write,
  nonzero-surplus, inverted-address-guard, signed-address-guard,
  initialized-memory and off-by-one-depth source mutations. Direct JSON
  checker mutations also reject read-transparency, collision-X and
  write-port-priority metadata.

Run:

```bash
sbt -batch ++2.12.18 \
  "core/Test/runMain spinal.core.internals.ValidationParityInventoryWriter --output target/morphhdl/validation-phase-ids.txt"
./morphhdl/scripts/check-contracts.sh \
  --require-tools \
  --live-phase-ids target/morphhdl/validation-phase-ids.txt
```

The required mode needs Verilator, Icarus Verilog, `vvp` and Yosys. Without the
flag, the script still performs structural, parity-manifest and
forbidden-SystemVerilog checks and reports missing external tools as skipped.
With no generated directory argument it checks the reviewed goldens. CI passes
the actual emitter output with `--generated-dir`.
