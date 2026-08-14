# Increment 20: bounded synchronous read-first single-port memory

Increment 20 adds the first parameterized memory vertical slice without making
collision, invalid-address or initialization behavior tool-dependent. It keeps
one static flat interface and one atomic memory policy.

## Frontend contract

The symbolic frontend exposes one guarded operation:

```scala
emitSynchronousReadFirstSinglePortMemory(
  label = "p_memory",
  memoryName = "memory",
  clock = ref("clk"),
  writeEnable = ref("write_enable"),
  address = ref("address"),
  writeData = ref("write_data"),
  readData = ref("read_data"),
  elementType = packedBits(width),
  depth = depth
)
```

All five runtime operands are guarded direct port references. The element type
and depth retain their symbolic dependencies and source origins. The helper
publishes one memory item only after null, kind, ownership, session and thread
checks pass, so failed capture cannot leave a partial item or reserve a name.

## ParamRTL semantics

The canonical node is
`ModuleItem.SynchronousReadFirstSinglePortMemory(label, memoryName, clock,
writeEnable, address, writeData, readData, elementType, depth)`. It fixes:

- one positive-edge clock and one active-high whole-word write enable;
- one unsigned address shared by read and write;
- one-cycle synchronous read output;
- read-first behavior for a same-address read/write edge;
- no reset, initialization, read enable or write mask;
- unspecified reads from unwritten in-range locations;
- synchronous zero reads and ignored writes when `address >= DEPTH`.

Clock and write enable are distinct exact unsigned one-bit inputs. Address is
an unsigned packed input. Write data, read output and memory element type must
be exactly equivalent in width and signedness over the complete legal
parameter domain. Width and depth are positive, and the address capacity must
cover the maximum possible depth. The memory is the sole module item and sole
owner of its output and internal memory name; sibling processes, drivers,
hierarchy and generate regions fail closed.

The public fixture keeps `WIDTH` and `DEPTH` independent. `WIDTH` is bounded
from 1 through 32 and `DEPTH` from 1 through 5. Its fixed three-bit address has
eight encodings, so ParamRTL proves `8 >= max(DEPTH)` once for the whole legal
domain. The explicit surplus policy is still required because independent
non-power-of-two depths leave legal address encodings outside the selected
memory range.

## Concrete witness and strict Verilog-2001

The concrete Spinal witness uses a five-element, eight-bit synchronous memory
port under one rising-edge clock domain. Every inherited validation phase still
runs on that witness. ParamRTL and the target capability pass independently
validate the parameterized memory before default shape comparison and atomic
public output.

The backend emits exactly:

```verilog
reg [WIDTH-1:0] memory [0:DEPTH-1];

always @(posedge clk) begin : p_memory
  if (address < DEPTH) begin
    read_data <= memory[address];
    if (write_enable == 1'b1) begin
      memory[address] <= write_data;
    end
  end else begin
    read_data <= {WIDTH{1'b0}};
  end
end
```

Both state updates use nonblocking assignment. Consequently `read_data`
receives the pre-edge memory value when a write collides at the same address.
The outer guard defines every surplus-address result and prevents an
out-of-range array write. No `initial` or reset syntax silently assigns memory
contents.

## Public executable contract

`single_port_memory.v` is the sixteenth artifact generated through
`MorphContractFixtureGenerator` and `MorphVerilog`. Its defaults are
`WIDTH=8`, `DEPTH=5`; the address remains a fixed three-bit input.

CI requires byte-identical normal and reverse-construction sixteen-file
generation, an exact sorted inventory and reviewed goldens. Verilator lints
the default 8x5, awkward 5x3 and minimum 1x1 elaborations in strict 1364-2001
mode. The memory fixture alone suppresses `WIDTHEXPAND` for comparing the
three-bit address against Verilog's 32-bit `integer DEPTH`, and `WIDTHTRUNC`
for indexing a deliberately non-power-of-two memory through that guarded
address. Those suppressions are confined to the three `SinglePortMemory` lint
invocations; exact golden, simulation and Yosys structural/port-width gates
continue to enforce its port and data widths, while all other fixtures retain
their width diagnostics.

Icarus proves synchronous timing, valid boundary addresses, disabled writes,
read-first collision, later visibility of the written value, synchronous zero
for surplus addresses and ignored surplus writes in all three simultaneous
configurations. Yosys retains exactly one memory with the requested
width/depth, one read and one positive-edge whole-word write port, and one
positive-edge output register. Its structural gate proves the exact
in-range-memory/else-zero read path, `address < DEPTH` predicate and
`write_enable && in_range` write guard. Negative synthesized netlists reject a
write-first bypass, swapped read branches, falling-edge clock, unconditional
write, nonzero surplus read, inverted or signed in-range guard, initialized
storage and an off-by-one memory depth. Direct checker-negative JSON mutations
also reject read transparency, collision-to-X and write-port-priority metadata.

## Validation parity

This tranche extends the existing partial width, driver, register and
cross-clock adaptations. It does not mark a new inherited phase implemented.
The concrete witness continues to execute the shared `SpinalVerilog` phase
plan; symbolic validation separately proves the complete positive width/depth
domain, memory ownership, clock/control roles and address capacity. General
memory topology, external domain provenance and broader sequential statements
remain visible gaps.

## Deferred by design

This increment does not add arbitrary address-width derivation, multiple
ports, dual clocks, asynchronous reads, write-first/no-change modes, reset,
initialization, read enable, partial/byte writes or masks. It does not promise
a particular FPGA RAM primitive; the canonical behavioral memory and its
synthesized structure are the contract.

## Recommended next increment

Increment 21 should close arbitrary address geometry as its own bounded
tranche. Add a typed portable ceiling-log2/address-width expression without
emitting Verilog-2005 `$clog2`, prove its complete domain including
`DEPTH=1`, and use it to derive the parameter-dependent packed address ABI
width for larger positive independent depths while retaining the same explicit
surplus-address policy. Keep masks and additional ports deferred until that
geometry has separate parser, simulation and synthesis evidence.
