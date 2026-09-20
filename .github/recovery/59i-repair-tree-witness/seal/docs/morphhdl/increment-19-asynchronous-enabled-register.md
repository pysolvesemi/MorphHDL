# Increment 19: bounded asynchronous enabled register

Increment 19 completes the bounded reset-timing/enable matrix with one atomic
asynchronous-reset enabled register. It preserves the fixed-interface,
fail-closed restrictions established by Increments 16 through 18.

## Frontend contract

The symbolic frontend exposes one guarded operation:

```scala
emitAsynchronousEnabledRegister(
  label = "p_async_enabled_register",
  clock = ref("clk"),
  reset = ref("reset"),
  enable = ref("enable"),
  assignment = proceduralAssign("data_out", ref("data_in"))
)
```

Clock, reset and enable are guarded direct port references. The assignment has
one direct output target and one direct input value. The operation retains
source origins, session ownership and thread provenance, and publishes one
process item only after every argument passes validation. Escaped, foreign,
cross-thread, null or non-reference handles fail before ParamRTL construction.

## ParamRTL semantics

The canonical node is
`ModuleItem.AsynchronousEnabledRegister(label, clock, reset, enable,
assignment)`. It fixes these semantics:

- positive-edge clocking;
- active-high asynchronous reset-to-zero with immediate assertion;
- reset priority over enable;
- active-high enabled data capture;
- intentional state retention while enable is low.

Clock, reset, enable and data must be distinct input roles. All controls are
exact unsigned one-bit inputs. Data and the sole output have exactly equivalent
packed type, width and signedness across the complete legal parameter domain.
The process is the sole module item and sole owner of its output; sibling
processes, continuous drivers, hierarchy and generate regions fail closed.

## Concrete witness and strict Verilog-2001

The concrete Spinal witness uses a rising-edge clock domain with active-high
`ASYNC` reset and an initialized register updated only inside `when(enable)`.
Every inherited validation phase still runs on that witness. ParamRTL and the
target capability pass independently validate the parameterized design before
default shape comparison and atomic public output.

The backend emits exactly:

```verilog
always @(posedge clk or posedge reset) begin : p_async_enabled_register
  if (reset == 1'b1) begin
    data_out <= {WIDTH{1'b0}};
  end else if (enable == 1'b1) begin
    data_out <= data_in;
  end
end
```

The missing final `else` is deliberate sequential hold, not incomplete
combinational assignment. Both state-changing paths use nonblocking
assignment. Reset sensitivity and reset-first ordering are part of the
semantic contract, not caller-selected syntax.

## Public executable contract

`asynchronous_enabled_register.v` is the fifteenth artifact generated through
`MorphContractFixtureGenerator` and `MorphVerilog`. It declares `WIDTH=8`,
one-bit `clk`, `reset` and `enable` inputs, one `WIDTH`-bit data input and one
registered output.

CI requires byte-identical normal and reverse-construction fifteen-file
generation with an exact sorted inventory and reviewed goldens. Verilator
lints default and awkward five-bit elaborations in strict 1364-2001 mode.
Icarus proves immediate reset without a clock edge, reset priority while enable
is high, no capture on reset deassertion, enabled capture, disabled hold and
later capture in simultaneous eight- and five-bit instances.

After `proc; opt_dff; opt_clean`, Yosys must find exactly one `$adffe` with
positive clock, active-high enable, active-high asynchronous zero reset and
exact direct port connections. Negative synthesized netlists must reject a
disabled capture path, enable-before-reset control swap, active-low enable,
falling-edge clock, synchronous reset and reset-to-ones.

## Deferred by design

This increment does not add selectable edge/reset/enable polarity, arbitrary
hold values, multiple registers or clocks, clock-domain crossings, general
runtime expressions, parameterized process nesting or memories.

## Recommended next increment

Increment 20 should begin a bounded parameterized single-port memory vertical
slice. Before implementation, lock explicit policies for synchronous versus
asynchronous read, read-during-write behavior, initialization and byte/write
mask support. A narrow first tranche should choose one clock, parameterized
positive width/depth, one write port and one documented read mode, then prove
default and awkward dimensions with simulation and synthesized memory-shape
checks. Multiple clocks, dual-port RAM, masks and unspecified collision
behavior should remain deferred until each policy has its own executable gate.
