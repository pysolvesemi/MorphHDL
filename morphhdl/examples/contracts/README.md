# Executable architecture fixtures

These files are executable output contracts for the parameterized backend.

- `parameterized_wire.v` is owned by Increments 2, 8 and 29. Increment 29
  migrates it to the single-source bridge: one ordinary SpinalHDL component
  consumes a direct public `HdlInt` through `UInt(width bits)`, connects the
  two ports with a normal assignment and generates this parameterized module
  without a separately authored ParamRTL `Design`.
- `symbolic_data_shapes.v` is owned by Increment 30. One ordinary component
  carries `WIDTH` through native Bits/UInt/SInt leaves, cloneOf, HardType,
  Bundle, Vec, Stream and Flow payloads, an internal wire and one unconditional
  uninitialized register path. Increment 53f retains the Vec's logical
  two-element, three-leaf shape while publishing `vec_in` and `vec_out` as
  single `6 * WIDTH` packed Verilog-2001 ports. Concrete Bool clock and
  protocol controls stay one bit; the rest of the aggregate ABI remains
  deterministically flattened by leaf.
- `derived_width.v` is owned by Increments 3, 23 and 24. Its two frontend public
  parameters feed an acyclic local-parameter expression graph; Increment 23
  clamps parameter-derived padding with typed `Min` and floors the final width
  with typed `Max`. Increment 24 derives `LANE_INDEX_WIDTH` with mathematical
  `CeilLog2`, including a zero result for one lane, and adds it to the packed
  port width in the single public module definition.
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
- `single_port_memory.v` is owned by Increments 20 through 22 and 24. Its independent
  positive `WIDTH`/`DEPTH` parameters feed one guarded positive-edge
  synchronous read-first whole-word port; Increment 21 derives the packed
  address width from `DEPTH`, and Increment 22 adds active-high read enable
  with disabled output hold while writes remain independent. Increment 24
  replaces the address-width threshold chain with the same module-local
  constant function used by `derived_width.v`, retaining the one-bit floor.
  Surplus enabled reads still return zero and surplus writes remain ignored.
- `parameterized_counter.v` is owned by Increment 25. Its direct positive
  public `LIMIT` parameter controls both the `addressWidth` count port and the
  terminal `LIMIT - 1` comparison. One positive-edge process gives active-high
  synchronous reset priority, active-high enabled increment, disabled hold and
  terminal wrap to zero. The concrete witness reuses `spinal.lib.Counter`.
- `simple_dual_port_memory.v` is owned by Increment 26. One shared positive-edge
  clock serves independent `addressWidth(DEPTH)` read and write addresses and
  independent active-high enables. Enabled reads are synchronous, disabled
  reads hold and surplus reads return zero; surplus writes are ignored.
  Simultaneous valid reads and writes both proceed, with deterministic
  read-first behavior when their addresses match. The concrete witness combines
  SpinalHDL `Mem.readSync(..., readUnderWrite = readFirst)` and `Mem.write`.
- `synchronous_stream_fifo.v` is owned by Increment 27. One active-high
  synchronous reset controls an atomic single-clock ready/valid FIFO with an
  exact public `DEPTH`, synchronous-read storage and a registered pop stage.
  Empty input does not bypass, full rejects a same-edge push, middle push/pop
  proceeds together, stalled output holds and pointers wrap in FIFO order. The
  default-depth concrete witness reuses latency-two `spinal.lib.StreamFifo`;
  parameter overrides, including the uniform `DEPTH=1` rejection policy, are
  governed by this artifact and its testbench.
- `synchronous_stream_m2s_pipe.v` is owned by Increment 28. One active-high
  synchronous reset controls a capacity-one registered ready/valid stage
  matching default `Stream.m2sPipe()`. Ready is high while empty or when the
  current output is consumed. Full stall holds, full pop plus push replaces
  without a bubble, and payload captures whenever ready even when invalid or
  reset clears valid. The concrete witness uses the pinned Spinal primitive;
  widths 1, 5, 8 and 32 are governed by this artifact and its testbench.
- Increment 8 routes the first four artifacts through one production fixture
  source and `MorphVerilog`; Increments 9 and 10 extend that same path to all
  six. Increment 11 extends it to all seven, Increment 12 to all eight, and
  Increment 13 to all nine, Increment 14 to all ten, and Increment 15 to all
  eleven. Increment 16 extends it to all twelve, and Increment 17 to all
  thirteen, Increment 18 to all fourteen, Increment 19 to all fifteen, and
  Increment 20 to all sixteen; Increments 21 and 22 upgrade the sixteenth
  artifact without changing that inventory. Increment 23 upgrades
  `derived_width.v` without changing that inventory, and Increment 24 upgrades
  both `derived_width.v` and `single_port_memory.v` with the shared
  constant-function lowering. Increment 25 extends the path to seventeen
  files with `parameterized_counter.v`, Increment 26 extends it to eighteen
  with `simple_dual_port_memory.v`, Increment 27 extends it to nineteen with
  `synchronous_stream_fifo.v`, and Increment 28 extends it to twenty with
  `synchronous_stream_m2s_pipe.v`. Increment 29 migrates the first artifact to
  the native single-source width bridge without changing that inventory.
  Increment 30 extends the path to twenty-one files with
  `symbolic_data_shapes.v`; Increment 53f upgrades that artifact's Vec boundary
  without changing the twenty-one-file inventory. CI
  performs a normal and reverse-construction run, requires an exact
  twenty-one-file inventory, checks
  byte identity with these goldens, and gives that unmodified directory to the
  external tool gates.
- The generated-fixture testbenches cover default, minimum, awkward and mixed
  overrides. `ParameterizedWireTb` checks the direct native width bridge at
  widths 8, 1, 13 and 64 in four simultaneous instances; Yosys rejects separate
  mutations that freeze either symbolic port at eight bits under the 64-bit
  override. `SymbolicDataShapesTb` checks all direct, aggregate, Vec, Stream,
  Flow and register paths at widths 1, 8, 13 and 64. Yosys proves the complete
  flattened ABI, packed `6 * WIDTH` Vec ports and internal/register widths and
  rejects fixed-width mutations at the 64-bit override. The memory fixtures
  separately retain unpacked `reg [WIDTH-1:0] memory [0:DEPTH-1]` storage.
  `DerivedWidthTb` checks widths 37, 4, 20, 29, 25 and 7 in six
  simultaneous instances of the same emitted module while exercising lane
  counts one, two, three and four. `ParameterForwardingTb`
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
  simultaneously with derived three-, two- and one-bit address ports. It
  proves synchronous output timing, valid last addresses, disabled read hold,
  writes while reads are disabled, disabled writes, read-first collision,
  later write visibility, zero reads for surplus addresses and ignored surplus
  writes. Yosys proves one exact retained memory, active-high enabled
  positive-edge output state, the memory-or-zero read path and an independent
  active-high in-range whole-word write guard, then rejects
  write-first, swapped-read-branch, falling-edge, unconditional-write,
  nonzero-surplus, inverted-address-guard, signed-address-guard,
  initialized-memory and off-by-one-depth source mutations. Direct JSON
  checker mutations also reject read-transparency, collision-X and
  write-port-priority metadata, read/write wide continuation, empty or
  truncated memory initialization, empty read enable/clock and widened reset
  connections, plus a short or signed comparator right-hand side. A separate
  depth-two elaboration covers the shared one-bit side of the first power-of-two
  boundary and rejects disabled/falling-edge or reset-enabled absorbed reads;
  a fixed three-bit source mutation proves the address derivation cannot be
  bypassed.
  `ParameterizedCounterTb` instantiates limits 1, 2, 3, 5 and 8 together and
  proves reset priority and synchronous timing, disabled hold, eight enabled
  transitions, modulo rollover and absence of an out-of-range state. Yosys
  proves the exact positive-edge enabled synchronous-reset state and
  increment/terminal-wrap path, and rejects off-by-one terminal, decrement,
  active-low enable, reversed-priority, falling-edge and nonzero-reset
  mutations.
  `SimpleDualPortMemoryTb` covers default 8x5, awkward 5x3, minimum 1x1 and a
  full-domain 4x8 shape with two independent depth-derived addresses.
  It proves one-cycle read timing, disabled hold, valid and surplus accesses,
  writes during disabled or surplus reads, disabled and surplus writes,
  different-address simultaneous operation, read-first same-address collision
  and later write visibility. Yosys proves one exact `1R1W` memory, distinct
  direct read/write addresses, separate unsigned range guards, independent
  active-high enables, positive-edge read state, whole-word writes, all-X
  initial contents and read-first metadata. Source and JSON mutations reject
  address collapse or swap, cross-gated controls, write-first bypass,
  unconditional writes, guard/edge/initialization/reset drift, fixed address
  widths and extra memory ports.
  `SynchronousStreamM2sPipeTb` instantiates widths 8, 1 and 5 together. It
  proves valid-only synchronous reset, no combinational valid bypass,
  registered capture, full stall stability, two consecutive bubble-free
  replacements, pop without replacement, refill and ready-enabled payload
  capture during reset. Verilator also lints width 32. Yosys proves one
  positive-edge synchronously reset ready-enabled valid register, one unreset
  ready-enabled payload register and the exact ready inverter/OR network.
  Source and JSON mutations reject wrong ready, lost replacement, payload
  valid-gating/reset, state/control misconnection and edge/reset drift.

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
