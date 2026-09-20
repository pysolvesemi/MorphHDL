# Increment 26: synchronous read-first simple-dual-port memory

Increment 26 adds the bounded single-clock `1R1W` storage primitive required
before a FIFO can perform simultaneous enqueue and dequeue. It is additive:
the Increment 20--22 single-port node and its sixteenth artifact retain their
existing shared-address contract.

## Frontend contract

The symbolic frontend exposes one atomic helper:

```scala
emitSynchronousReadFirstSimpleDualPortMemory(
  label = "p_memory",
  memoryName = "memory",
  clock = ref("clk"),
  readEnable = ref("read_enable"),
  writeEnable = ref("write_enable"),
  readAddress = ref("read_address"),
  writeAddress = ref("write_address"),
  writeData = ref("write_data"),
  readData = ref("read_data"),
  elementType = elementType,
  depth = depth
)
```

All seven runtime operands are guarded direct references and pairwise
distinct. Both address ports must be unsigned packed values with exactly
equivalent types, and each must independently prove enough capacity for the
same positive, finitely bounded depth over its complete legal domain. A
correlated `AddressWidth(depth)` is accepted, as is an independently sufficient
fixed or symbolic width. Clock and both enables are exact unsigned one-bit
inputs; write data and the sole read output exactly match the element type.

The helper validates every argument before publishing one item. A failed
capture neither leaves a partial memory nor reserves its label or memory name.
Capture is top-level and atomic: nesting, a second process or memory, and
sibling hierarchy, generate or driver items fail closed.

## ParamRTL semantics

The target-neutral node is
`ModuleItem.SynchronousReadFirstSimpleDualPortMemory`. It fixes one policy:

- one positive-edge clock shared by one synchronous read port and one
  whole-word write port;
- independent read and write enables and independent read and write addresses;
- an enabled in-range read updates `readData` one clock edge later;
- a disabled read holds the previous `readData`, including while a write
  commits;
- an enabled surplus-address read synchronously updates `readData` to zero;
- a surplus-address write is ignored independently of the read path;
- simultaneous valid reads and writes to different addresses both proceed;
- a simultaneous valid read and write to the same address returns the
  pre-write value and then commits the write.

The last rule is deterministic read-first behavior. Nonblocking assignments
make the read observe the old array value at the active edge; a later enabled
read observes the newly written value. Unwritten in-range locations remain
unspecified.

## Concrete library witness

The default concrete witness uses SpinalHDL's existing `Mem` APIs rather than
reimplementing storage in the fixture. It combines
`readSync(..., readUnderWrite = readFirst)` with a separate `write(...)` on the
same clock domain. Explicit range guards, delayed read enable/range state and
held output state reproduce the bounded surplus-zero and disabled-read-hold
policy at the default `WIDTH=8`, `DEPTH=5` shape.

The witness API is present at the pinned upstream commit. It continues to run
the complete inherited `SpinalVerilog` validation plan. Default-shape
comparison proves the concrete and symbolic legs expose the same seven-port
flat interface; the executable Verilog gates below protect the parameterized
sequential behavior.

## Strict Verilog-2001 lowering

The backend emits one unpacked array and one canonical process:

```verilog
reg [WIDTH-1:0] memory [0:DEPTH-1];

always @(posedge clk) begin : p_memory
  if (read_address < DEPTH) begin
    if (read_enable == 1'b1) begin
      read_data <= memory[read_address];
    end
  end else if (read_enable == 1'b1) begin
    read_data <= {WIDTH{1'b0}};
  end
  if (write_address < DEPTH) begin
    if (write_enable == 1'b1) begin
      memory[write_address] <= write_data;
    end
  end
end
```

The two sibling paths retain independent address comparators and enables.
Their ordering does not create write-first forwarding because all state
updates are nonblocking. Artifact18's two exact `AddressWidth(DEPTH)`
declarations use the Increment 24 module-local
`clog2(DEPTH, 1)` constant function. Other accepted capacity-safe,
mutually type-equivalent address widths retain their own canonical lowering.
No `$clog2`, specialized module copy or overrideable helper-width parameter is
emitted.

## Public executable contract

`simple_dual_port_memory.v` is the eighteenth reviewed artifact. It has
independent positive `WIDTH` and `DEPTH` parameters with defaults 8 and 5 and
fixture domains `WIDTH=1..32` and `DEPTH=1..8`. This public fixture chooses two
exact `AddressWidth(DEPTH)` ports even though the general node also accepts
other capacity-proven, mutually type-equivalent unsigned address widths.
Normal and reverse-construction generation must be byte-identical and match
the reviewed golden.

The Verilog-2001 testbench exercises default 8x5, awkward 5x3, minimum 1x1
and full-domain 4x8 configurations in one simulation. It proves
one-cycle read timing, disabled hold, last-valid and surplus addresses,
independent writes during disabled or surplus reads, disabled and surplus
writes, different-address simultaneous operation, read-first same-address
collisions and later write visibility. It never assumes a value for an
unwritten in-range location.

Verilator parses and lints representative parameter overrides. Icarus runs
the behavioral testbench. Yosys proves one retained `1R1W` memory, distinct
direct address paths, two unsigned range guards, independent active-high
enables, one positive-edge read state, whole-word writes, all-unknown initial
contents and read-first collision metadata. The structural checker admits
only the audited equivalent output-register forms produced at surplus-code and
full-address-domain depths. Source and JSON mutations reject address
collapse/swap, cross-gated enables, write-first bypass, unconditional writes,
guard polarity/signedness errors, falling-edge state, initialization, reset or
collision-metadata drift, fixed address widths and extra memory ports.

## Validation parity

The concrete witness still executes every inherited validation phase.
ParamRTL separately strengthens the partial width, driver, register-as-latch
and cross-clock adaptations by proving two type-equivalent independently
capacity-safe addresses, sole read-output and memory ownership, intentional
disabled-read hold, independent write ownership and one exact shared clock.
Direct roles and sole-item ownership make combinational feedback
unrepresentable for this node, but the general `PhaseCheckCombinationalLoops`
adaptation remains planned. The validation-parity manifest records the
corresponding focused frontend, validator, emitter, simulation and synthesis
evidence without claiming general multi-port or multi-clock memory support.

## Deferred by design

This tranche does not add independent clocks, asynchronous reads, multiple
read or write ports, byte enables, masks, reset, initialization, selectable
collision behavior, write-first/no-change modes, arbitrary memory statements
or a vendor-primitive inference guarantee. These policies require distinct IR
nodes or later bounded adapters rather than flags that weaken this node's
deterministic semantics.

## Recommended next increment

Increment 27 should add an atomic bounded single-clock synchronous FIFO with a
real `spinal.lib.StreamFifo` witness. ParamRTL must represent the complete FIFO
as one atomic node because current sole-runtime-item ownership prohibits
assembling sibling counter and memory nodes. Its public `DEPTH` must mean total
externally observable FIFO capacity, independent of internal RAM words or an
output staging register. The tranche should explicitly freeze empty/full,
ready/valid, simultaneous push/pop, wrap and boundary-collision behavior before
emission.
