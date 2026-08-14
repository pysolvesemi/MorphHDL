# Increment 25: bounded parameterized synchronous counter

Increment 25 adds the first parameterized state machine whose transition
depends directly on a public integer parameter. The tranche deliberately
models the common `spinal.lib.Counter` policy rather than introducing a general
clocked-statement language.

## Frontend contract

The symbolic frontend exposes one guarded operation:

```scala
emitSynchronousCounter(
  label = "p_counter",
  clock = ref("clk"),
  reset = ref("reset"),
  enable = ref("enable"),
  count = ref("count"),
  limit = limit
)
```

`limit` must be the exact unmodified `HdlInt.param` handle declared as a public
integer parameter of the same module. It may not be a local, literal, copied
expression, child parameter or arithmetic wrapper. Its complete finite domain
must have a lower bound of at least one. The concrete witness must also be
positive.

Clock, reset, enable and count must be direct references. The helper validates
all arguments before publishing one item, so a failed capture neither leaves a
partial counter nor reserves its label. Counter capture is top-level and
atomic: nesting, sibling items and a second counter are rejected.

## ParamRTL semantics

The target-neutral node is
`ModuleItem.SynchronousCounter(label, clock, reset, enable, count, limit)`.
It fixes one policy:

- positive-edge clocking;
- active-high synchronous reset-to-zero with reset priority;
- active-high enable and disabled hold;
- increment by one when enabled and nonterminal;
- wrap to zero when `count == LIMIT - 1`;
- no reachable value greater than or equal to `LIMIT`;
- one-bit state at `LIMIT=1`, which remains zero.

The clock, reset and enable ports are distinct exact unsigned one-bit inputs.
The count port is the sole procedurally driven output and must be exactly
`PackedBits(AddressWidth(LIMIT), Unsigned)`. All four runtime names are
pairwise distinct. The direct public parameter requirement keeps the terminal
comparison, output width and legal state domain tied to the same symbol over
every permitted override.

## Concrete library witness

The default concrete witness uses SpinalHDL's existing `spinal.lib.Counter(5)`
inside a rising-edge, active-high synchronous-reset clocking area. Enable calls
the library counter's `increment()` operation and its value drives the
three-bit output. MorphHDL therefore continues to exercise the inherited
library and all inherited validation phases instead of duplicating that
concrete counter implementation in the fixture.

The symbolic leg represents the parameterized policy because ordinary Scala
construction has already specialized the concrete library object to five
states. Default-shape comparison proves that both legs expose the same module,
parameter-independent port names/directions and three-bit default output.

## Strict Verilog-2001 lowering

The backend reuses the Increment 24 module-local constant function and emits
one canonical process:

```verilog
always @(posedge clk) begin : p_counter
  if (reset == 1'b1) begin
    count <= {clog2(LIMIT, 1){1'b0}};
  end else if (enable == 1'b1) begin
    if (count == LIMIT - 1) begin
      count <= {clog2(LIMIT, 1){1'b0}};
    end else begin
      count <= count + 1'b1;
    end
  end
end
```

The helper's minimum result of one gives legal packed and replication widths
at `LIMIT=1`. It is an elaboration-time constant function; synthesis retains
the intended comparator, incrementer, wrap mux and enabled synchronous-reset
state, but no runtime shift implementation. `$clog2` remains forbidden because
the public target is IEEE 1364-2001.

## Public executable contract

`parameterized_counter.v` is the seventeenth reviewed artifact. It has one
public `LIMIT` parameter with default 5 and a supported fixture domain from 1
through 8. Normal and reverse-construction generation must be byte-identical
and match the reviewed golden.

The Verilog-2001 testbench instantiates limits 1, 2, 3, 5 and 8 simultaneously.
It proves synchronous reset timing and priority, disabled hold, every enabled
transition through eight edges, modulo rollover and absence of an out-of-range
state. Verilator parses and lints all five elaborations. Yosys proves the
default and representative non-power-of-two/power-of-two structures, exact
port widths, positive-edge state, active-high reset/enable, terminal comparison
and increment/wrap data path. Mutations reject an off-by-one terminal,
decrement, active-low enable, reversed reset priority, falling-edge clock and
nonzero reset.

## Validation parity

This increment strengthens the existing partial width, driver,
register-as-latch and cross-clock adaptations. The concrete `Counter` witness
still executes the complete inherited `SpinalVerilog` plan. Symbolic
validation separately proves the exact counter width and complete positive
limit domain, sole state ownership and fixed one-clock policy. It does not
claim support for general state machines, arbitrary next-state expressions or
multiple clocks.

## Deferred by design

This tranche does not add down-counting, saturation, a load input, a rollover
pulse, asynchronous reset, selectable polarity, step values, multiple
counters or counter/process composition. Those policies require distinct IR
nodes or a later general sequential representation rather than hidden mode
flags on this node.

## Recommended next increment

Increment 26 should add a bounded single-clock synchronous simple-dual-port
memory with one read address and one write address (`1R1W`). It should support
simultaneous read and write, retain depth-derived address widths, and freeze
same-address collision, read-enable hold and surplus-address behavior with
parser, simulation and synthesis evidence. That topology is required before a
FIFO can safely compose the new counter state with simultaneous push/pop
storage access. A bounded synchronous FIFO should follow in Increment 27.
