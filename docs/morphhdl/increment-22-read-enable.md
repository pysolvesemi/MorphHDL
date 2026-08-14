# Increment 22: active-high single-port-memory read enable

Increment 22 extends the existing bounded synchronous read-first single-port
memory with one whole-word active-high read enable. It changes no memory
topology, address geometry, write policy or collision mode.

## Frontend contract

The atomic helper now requires a direct `readEnable` reference:

```scala
emitSynchronousReadFirstSinglePortMemory(
  label = "p_memory",
  memoryName = "memory",
  clock = ref("clk"),
  readEnable = ref("read_enable"),
  writeEnable = ref("write_enable"),
  address = ref("address"),
  writeData = ref("write_data"),
  readData = ref("read_data"),
  elementType = packedBits(width),
  depth = depth
)
```

`readEnable` retains its source origin and any declarative or lexical-scope
dependencies in the same way as every existing direct-reference operand. A
null, non-reference, escaped scoped or invalidly named value fails before the
memory item is published. Capture state remains thread-local, while the owned
element type and depth retain their existing session and thread checks. The
complete call remains atomic and exception safe.

## ParamRTL semantics

`ModuleItem.SynchronousReadFirstSinglePortMemory` now carries distinct
`readEnable` and `writeEnable` references. Both must resolve to different
unsigned one-bit input ports, and neither may alias the clock, address, data or
output roles.

At each positive clock edge the behavior is:

| Address | Read enable | Write enable | Read output | Storage |
|---|---:|---:|---|---|
| In range | 0 | 0 | Hold | Hold |
| In range | 0 | 1 | Hold | Write new word |
| In range | 1 | 0 | Capture old word | Hold |
| In range | 1 | 1 | Capture old word | Write new word |
| Surplus | 0 | 0 or 1 | Hold | Hold |
| Surplus | 1 | 0 or 1 | Capture zero | Hold |

The simultaneous enabled read/write row remains read-first. A disabled read
does not force zero and does not prevent a valid write. Unwritten in-range
storage remains unspecified.

## Strict Verilog-2001 lowering

The existing one-process address-first form becomes:

```verilog
always @(posedge clk) begin : p_memory
  if (address < DEPTH) begin
    if (read_enable == 1'b1) begin
      read_data <= memory[address];
    end
    if (write_enable == 1'b1) begin
      memory[address] <= write_data;
    end
  end else if (read_enable == 1'b1) begin
    read_data <= {WIDTH{1'b0}};
  end
end
```

There is deliberately no final assignment branch. That absence is sequential
hold, not a combinational latch. The read and write conditions are siblings,
so read enable cannot gate a write. Nonblocking assignments retain the
pre-write memory value during a same-address collision.

The derived address semantics introduced in Increment 21 remain unchanged.
Increment 24 later replaces the original target conditional chain with the
module-local `morphhdl$ceil_log2(DEPTH, 1)` constant-function call, without
native `$clog2` or an overrideable helper parameter.

## Concrete witness and inherited validation

The default 8x5 Spinal witness adds one one-bit `read_enable` input. Its single
read/write memory port is enabled for either a requested valid read or a valid
write; a guarded output state exposes a new memory result only for a requested
read and otherwise holds the last read result. Surplus enabled reads still
produce zero. The witness continues to execute the shared inherited Verilog
phase plan before any parameterized output is published.

## Executable evidence

The public artifact inventory remains exactly sixteen files. Normal and
reverse construction must generate byte-identical directories, and the
upgraded `single_port_memory.v` must match its reviewed golden exactly.

The gate requires:

- frontend, ParamRTL and Verilog-emitter tests for the new required role,
  provenance, diagnostics, one-bit type and role separation;
- strict Verilog-2001 lint at depths 5, 3, 2 and 1;
- Icarus coverage of valid and surplus disabled-read hold, write while read is
  disabled, later enabled visibility, enabled surplus zero and simultaneous
  enabled read-first collision behavior;
- Yosys proof that read output state has an active-high read enable while the
  memory write enable remains exactly the independent active-high write enable
  combined with the existing in-range guard;
- negative source and JSON mutations for bypassed or inverted read enable,
  read-enable-gated writes, write-first behavior, malformed read controls,
  reset/initialization changes and the existing depth/address invariants.

## Validation parity

This tranche strengthens the partial driver, register and cross-clock
adaptations but does not mark another inherited phase fully implemented. Low
read enable is an intentional sequential hold proved by ParamRTL and the
external simulation/synthesis gates. The concrete witness still runs every
shared inherited phase; the parameterized artifact remains independently
checked across non-default legal shapes.

Reset, initialization, masks, additional ports or clocks, alternate collision
modes and selectable read-enable polarity remain outside Increment 22.

## Recommended next increment

Increment 23 should extend parameterized integer expressions with typed
`Min` and `Max` operations across `HdlInt`, ParamRTL validation and strict
Verilog-2001 emission. The tranche should prove exact operand domains,
preserve parameter/local dependencies and correlation, constant-fold exact
cases, and lower dynamic cases to deterministic conditional expressions.
Comments, synthesis attributes and raw/verbatim HDL remain deferred.
