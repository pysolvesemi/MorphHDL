# Increment 18: bounded synchronous enabled register

Increment 18 adds intentional hold behavior to the first bounded sequential
state model. One positive-edge process owns the module's sole output. An
active-high synchronous reset clears it to zero, otherwise an active-high
enable captures one direct same-typed input; when enable is low the register
retains its previous value.

The guarded frontend shape is:

```scala
captureItems {
  emitSynchronousEnabledRegister(
    label = "p_sync_enabled_register",
    clock = ref("clk"),
    reset = ref("reset"),
    enable = ref("enable"),
    assignment = proceduralAssign("data_out", ref("data_in"))
  )
}
```

Clock, reset and enable are guarded direct references. The existing
`ProceduralAssign` supplies the sole registered output and its direct data
input. Positive-edge clocking, active-high synchronous reset-to-zero,
active-high enable and disabled hold are fixed semantics rather than
caller-selectable syntax. Capture remains atomic and retains source origin and
provenance for every reference.

## Canonical IR and validation

ParamRTL records
`ModuleItem.SynchronousEnabledRegister(label, clock, reset, enable,
assignment)`. The node records sequential ownership, reset priority, capture
and hold intent without encoding `always`, `reg`, a sensitivity list or
assignment spelling.

Clock, reset, enable and data must be distinct input roles. The three controls
are exact unsigned one-bit input ports. The input data and sole output target
have exactly equivalent packed widths and signedness over the complete legal
parameter domain. Reset has priority over enable; enable high captures and
enable low leaves the state unchanged. A sibling process, continuous driver,
instance, generate region or second register is rejected before emission.

The atomic restrictions make mixed ownership and internal multi-clock
crossings unrepresentable. They do not approve multiple registers or clocks,
external input-domain provenance, arbitrary clocked expressions, nested
parameterized processes or memories.

## Morph orchestration and strict Verilog-2001

The concrete Spinal witness uses a rising-edge clock domain with active-high
`SYNC` reset and an initialized eight-bit register updated inside
`when(enable)`. It therefore witnesses reset priority, enabled capture and
disabled state retention. Every inherited concrete phase still runs, while
the symbolic design separately passes ParamRTL and target capability gates
before default shape is compared or output is published.

The backend emits exactly:

```verilog
always @(posedge clk) begin : p_sync_enabled_register
  if (reset == 1'b1) begin
    data_out <= {WIDTH{1'b0}};
  end else if (enable == 1'b1) begin
    data_out <= data_in;
  end
end
```

The absence of a final `else` is intentional sequential hold behavior, not a
combinational latch. Reset appears before enable and both state-changing paths
use nonblocking assignment. Falling-edge clocking, reset-to-ones, enable-first
priority, disabled capture and SystemVerilog `always_ff` remain forbidden.

## Public executable contract

`synchronous_enabled_register.v` is the fourteenth artifact generated through
`MorphContractFixtureGenerator` and `MorphVerilog`.
`SynchronousEnabledRegister` has `WIDTH=8`, one-bit `clk`, `reset` and `enable`
inputs, one `WIDTH`-bit data input and one registered output.

CI requires byte-identical normal/reverse fourteen-file generation and exact
reviewed goldens. Verilator lints default and five-bit widths in strict
1364-2001 mode. Icarus proves reset priority while enable is high, enabled
capture, disabled hold, later capture, and that synchronous reset does not act
away from a positive edge in simultaneous eight- and five-bit instances.
Yosys proves exactly one positive-edge, active-high enabled register with
active-high synchronous zero reset, exact direct bindings, no latch and exact
pre/post-synthesis widths. Negative netlists must reject disabled capture,
enable-before-reset priority, active-low enable, a falling edge and
reset-to-ones.

## Deferred by design

This increment does not add asynchronous reset plus enable, selectable
polarity, multiple registers or clocks, arbitrary expressions, sequential
processes under hierarchy/generate regions, CDC structures or memories.

## Recommended next increment

Increment 19 should add the parallel bounded asynchronous-reset enabled
register. It is the smallest clean tranche: it reuses Increment 17's
asynchronous reset and Increment 18's enable/hold proof while completing the
four-way reset-timing/enable matrix. The backend should emit one named
`always @(posedge clk or posedge reset)` process with reset first, then enable,
and implicit disabled hold. Behavioral and Yosys mutation gates should prove
immediate reset, reset priority, active-high enable, capture and hold at
default and awkward widths.

A bounded parameterized single-port memory is more important to v1 completion,
but it requires separate decisions for synchronous versus asynchronous read,
read-during-write behavior, initialization and mask policy. It should begin in
Increment 20 rather than mix those policies into this last small register
control tranche. Multiple clocks/registers and CDC remain deferred.
