# Increment 27: bounded synchronous Stream FIFO

Increment 27 adds the first parameterized ready/valid library adapter. It is
one atomic ParamRTL node rather than a composition of sibling memory and
counter nodes, because the current runtime ownership contract deliberately
permits one complete stateful item per module.

## Public meaning of depth

`DEPTH` is the complete externally observable FIFO capacity: the number of
accepted pushes that have not yet been popped. It includes a word resident in
the registered pop stage and is independent of the backend's physical storage
organization. `DEPTH=1` is legal and means exactly one transaction, not one RAM
word plus an extra hidden slot.

The public fixture declares `WIDTH=8` and `DEPTH=5`, with legal fixture domains
`WIDTH=1..32` and `DEPTH=1..8`. The general target requires a direct public
positive depth whose finite maximum does not exceed `2147483646`, because
`DEPTH + 1` must remain representable by the strict Verilog-2001 `integer`
capability used to derive occupancy width.

## Frontend contract

The symbolic frontend exposes one atomic helper:

```scala
emitSynchronousStreamFifo(
  label = "p_fifo",
  memoryName = "memory",
  clock = ref("clk"),
  reset = ref("reset"),
  pushValid = ref("push_valid"),
  pushReady = ref("push_ready"),
  pushData = ref("push_data"),
  popValid = ref("pop_valid"),
  popReady = ref("pop_ready"),
  popData = ref("pop_data"),
  elementType = elementType,
  depth = depth
)
```

All eight runtime references are direct, guarded and pairwise distinct. Clock,
reset and ready/valid controls are exact unsigned one-bit ports. Push and pop
data exactly match one owned positive packed element type. `depth` must be the
same module's unmodified public `HdlInt.param`, rather than a literal, local,
copied expression or arithmetic wrapper. Names, ownership and arguments are
validated transactionally before the single item is published. Sibling or
nested runtime/structural items fail closed.

## Target-neutral semantics

`ModuleItem.SynchronousStreamFifo` fixes one policy:

- one positive-edge clock and active-high synchronous reset;
- one ready/valid push interface and one ready/valid pop interface;
- synchronous-read storage with one registered pop stage and no bypass;
- exact public capacity `DEPTH`, including the staged word;
- FIFO order, whole-word writes and exact wrap at `DEPTH - 1`;
- stalled `pop_valid` holds both valid and payload;
- reset clears read/write pointers, occupancy and `pop_valid` only;
- memory and `pop_data` remain unspecified while `pop_valid` is zero.

An empty FIFO accepts a ready push, but that word cannot be popped on the same
edge and appears only after a later synchronous fetch edge. A full FIFO rejects
a push even when `pop_ready` consumes the current word on that edge; space is
advertised on the following cycle. Away from those boundaries, simultaneous
accepted push and pop preserve occupancy and FIFO order. At occupancy one with
`DEPTH>1`, simultaneous push and pop are both accepted, but the replacement
word requires one invalid refill cycle. For `DEPTH=1`, occupancy one is full,
so the push is rejected uniformly.

Ready/valid transfers are contractually meaningful only while `reset` is low.
`push_ready` is a combinational view of registered occupancy and can therefore
be high while reset is asserted, but reset has priority and suppresses every
write and state transition on that edge.

No flush, fall-through/bypass, occupancy/availability port, selectable
latency, asynchronous read/reset, initialization, mask, packet policy,
independent clock or CDC behavior is implied.

## Concrete library witness

The default concrete witness is an anonymous top-level subclass of the
existing latency-two `spinal.lib.StreamFifo` with constructor depth five. Its
external occupancy/full tracker includes the registered output stage, so its
accepted outstanding capacity is exactly five. The six Stream signals are
renamed to the reviewed flat ABI;
the optional flush and informational occupancy/availability signals are kept
internal, tagged as permitted directionless IO and pruned, with flush tied
inactive. The fixture generator selects an active-high synchronous default
clock-domain configuration for this artifact. Generation proves exactly the
eight intended public ports and zero child modules.

Making the library FIFO itself the top avoids introducing a concrete child
module that would disagree with the atomic symbolic hierarchy. MorphVerilog
still runs the complete inherited concrete validation plan and compares the
same eight-port default flat shape before publishing only ParamRTL output.

This concrete witness proves inherited validation and the default `DEPTH=5`
flat shape; it is not a promise of library equivalence for every parameter
override. The pinned Spinal library special-cases constructor depth one as an
`m2sPipe`, which permits full-pop replacement. Morph deliberately keeps its
uniform `DEPTH=1` policy: a full pop rejects a simultaneous push and refill is
synchronous. The reviewed parameterized-Verilog testbench is authoritative for
that override.

## Strict Verilog-2001 lowering

The backend emits the natural module-local `clog2` constant function, then two
readable derived widths:

```verilog
localparam integer POINTER_WIDTH = clog2(DEPTH, 1);
localparam integer OCCUPANCY_WIDTH = clog2(DEPTH + 1, 1);
```

If any visible module identifier already occupies `clog2`, either local name,
or an internal FIFO name, the emitter deterministically selects the first free
numeric suffix and uses it consistently. Separate modules may safely reuse the
same natural local names.

This rename is generated-text-only and backend-private: the Scala and ParamRTL
APIs and ceiling-log2 semantics do not change. Every helper-consuming Verilog
golden and its content hash does change. An external hierarchical reference to
the former `morphhdl$ceil_log2` spelling will break, but helper functions are
not part of the generated-module ABI and such references were unsupported.

The generated module contains one `reg [WIDTH-1:0] memory [0:DEPTH-1]`, wrapped
read/write pointer registers, bounded occupancy, internal accepted-transfer
signals, combinational `push_ready`, registered `pop_valid`/`pop_data`, and one
named `always @(posedge clk)` block. Nonblocking assignments preserve
synchronous memory reads and state ordering. The canonical form contains one
common registered fetch before the write for readability. The Yosys gate folds
the payload hold mux into a read enable before output-register absorption,
retaining nontransparent read-first memory metadata even though valid FIFO
transfers cannot collide on the same storage address. No `$clog2`,
SystemVerilog syntax, runtime logarithm hardware, initial block, payload reset
or memory reset is emitted.

The Yosys gate normalizes procedural hold muxes before memory-register
absorption. It requires nontransparent read-first metadata. At `DEPTH=1`,
Yosys additionally marks collision data as don't-care because the exact fetch
and write enables are mutually exclusive; this does not change an observable
FIFO behavior or permit a write-first bypass.

## Public executable contract

`synchronous_stream_fifo.v` is the nineteenth reviewed artifact. Normal and
reverse-construction fixture generation must be byte-identical and match the
golden. Its strict Verilog-2001 testbench instantiates default 8x5, awkward 5x3,
minimum 1x1 and power-of-two 4x8 configurations. It proves reset timing,
reset priority, no-bypass empty behavior, exact full capacity, full-pop push rejection,
middle simultaneous transfer, occupancy-one refill, stalled payload stability,
order and repeated pointer wrap. It never observes memory or pop payload while
invalid.

Verilator parses and lints all four shapes. Icarus runs the behavioral
testbench. Yosys proves the exact public ports, array depth/width, one
positive-edge domain, synchronous read state, whole-word writes, pointer and
occupancy widths, ready comparator, wrapped updates, reset scope and absence of
initialization. Source and JSON mutations reject fall-through, wrong capacity,
full-boundary replacement, unconditional or partial writes, pointer/count
direction or wrap drift, falling-edge/asynchronous reset, payload/memory
initialization, extra ports/state and fixed-width specialization.

## Validation parity

The concrete witness continues to execute every inherited validation phase.
ParamRTL separately extends the partial width, driver, register-as-latch and
cross-clock adaptations with direct finite-depth proof, exact role/type checks,
sole ownership, complete ready/valid state policy and one shared clock. The
atomic node makes sibling-driver and combinational-feedback construction
unrepresentable without claiming general Stream graphs, arbitrary sequential
statements, alternate FIFOs or CDC support.

## Deferred by design

This increment does not add Flow adaptation, fall-through/low-latency FIFO,
flush, occupancy, availability, almost-full/empty thresholds, packet framing,
show-ahead asynchronous read, configurable reset/polarity, initialization,
multi-clock or asynchronous FIFO support.

## Recommended next increment

Increment 28 should add the first bounded `Flow`/`Stream` adapter around an
existing library primitive needed by the DisplayController path, without
generalizing the atomic FIFO into arbitrary statement composition. A useful
next target is a one-entry ready/valid pipeline stage (`Stream.m2sPipe`) with
explicit active-high synchronous reset, stall stability and one-cycle latency;
it exercises reusable Stream interface intent without adding another memory
policy.
