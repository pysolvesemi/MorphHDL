# Increment 17: bounded asynchronous-reset register

Increment 17 completes the first bounded reset axis for MorphHDL sequential
state. It adds one positive-edge process whose active-high reset clears the
module's sole output immediately, without waiting for a clock edge. The data
path remains one direct, same-typed input and the reset value remains zero.

The guarded frontend shape is:

```scala
captureItems {
  emitAsynchronousRegister(
    label = "p_async_register",
    clock = ref("clk"),
    reset = ref("reset"),
    assignment = proceduralAssign("data_out", ref("data_in"))
  )
}
```

`emitAsynchronousRegister` consumes guarded direct clock/reset references and
the existing `ProceduralAssign`. Positive-edge clocking, active-high
asynchronous reset and reset-to-zero are fixed semantics, not selectable
Verilog syntax. Source origins and reference provenance are retained, and a
null, escaped, nested, mixed or second process fails during frontend capture.

## Canonical IR and validation

ParamRTL records
`ModuleItem.AsynchronousRegister(label, clock, reset, assignment)`. The node
does not encode a sensitivity list, `reg`, nonblocking assignment or Verilog
replication syntax.

Clock, reset and data must be distinct input roles. Clock and reset are exact
unsigned one-bit input ports. The data input and sole output target have
exactly equivalent packed widths and signedness over the complete legal
parameter domain. The process owns the output completely; sibling continuous
assignments, combinational or synchronous processes, hierarchy, generate
regions and a second asynchronous process are rejected before emission.

The same atomic restrictions used for the synchronous variant make incomplete
reset/data coverage, mixed drivers and internal multi-clock crossings
unrepresentable. They do not approve external input-domain provenance,
multiple clocks, clock enables, general clocked statements or memories.

## Morph orchestration and strict Verilog-2001

The concrete Spinal witness uses a rising-edge clock domain with active-high
`ASYNC` reset and an initialized eight-bit register. It still runs the shared
inherited width, register, clock-domain and global-data phases. Independently,
the symbolic design passes ParamRTL validation and target capability checks
before default shape is compared or public output is written. An invalid
symbolic reference therefore fails closed at `ParamRtlValidation`, even when
the concrete default witness is valid.

The backend emits one deterministic process:

```verilog
always @(posedge clk or posedge reset) begin : p_async_register
  if (reset == 1'b1) begin
    data_out <= {WIDTH{1'b0}};
  end else begin
    data_out <= data_in;
  end
end
```

Reset is in the sensitivity list and has priority in the process body. Both
paths use nonblocking assignment. SystemVerilog `always_ff`, falling-edge
clocking, synchronous-reset substitution, reset-to-ones and mixed assignment
styles remain forbidden.

## Public executable contract

`asynchronous_register.v` is the thirteenth artifact generated through
`MorphContractFixtureGenerator` and `MorphVerilog`. `AsynchronousRegister` has
public integer parameter `WIDTH=8`, one-bit `clk` and `reset` inputs, one
`WIDTH`-bit data input and one `WIDTH`-bit registered output.

CI requires exact normal/reverse thirteen-file inventories and byte identity
with reviewed goldens. Verilator lints default and five-bit widths in strict
1364-2001 mode. Icarus proves asynchronous assertion without a positive edge,
reset priority at a positive edge, no capture on reset deassertion and later
data capture in simultaneous eight- and five-bit instances. Yosys proves
exactly one positive-edge flip-flop, no latch, active-high asynchronous zero
reset, direct data/output bindings and exact pre/post-synthesis widths. Three
negative netlist checks require the structural checker to reject a
synchronous-reset mutation, a falling-edge-clock mutation and reset-to-ones.

## Deferred by design

This increment does not add clock enable or hold behavior, selectable reset
polarity, multiple registers or clocks, arbitrary clocked expressions,
sequential processes inside hierarchy/generate regions, CDC structures or
memories.

## Recommended next increment

Increment 18 should add one bounded clock-enabled register using the existing
positive-edge, active-high synchronous reset-to-zero contract. A distinct
one-bit `enable` input should capture data when high and retain state when low;
reset must retain priority. This is the smallest next semantic step because it
adds intentional hold behavior and advances the remaining register-coverage
parity gap without simultaneously expanding process count, hierarchy or clock
domains. The strict backend should emit one named process with reset, then
enable, and no final assignment branch. A four-state test should prove reset,
capture, hold and later capture at default and awkward widths, while Yosys
proves one positive-edge enabled register and rejects priority/hold mutations.

Parameterized memories and the synchronous FIFO remain v1 requirements, but
they introduce depth, read latency, write-mask and collision policies. They
should follow this smaller enable/hold tranche rather than be combined with
it. Multiple registers/clocks, generated clocks and CDC remain deferred.
