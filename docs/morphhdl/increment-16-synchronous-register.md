# Increment 16: bounded synchronous register

Increment 16 adds the first sequential state to the MorphHDL parameterized
path. The tranche is deliberately atomic: one positive-edge process owns the
module's sole output, captures one same-typed input and clears to zero under one
active-high synchronous reset. Parameter-dependent structure and hierarchy
remain separate from the process.

The guarded frontend shape is:

```scala
captureItems {
  emitSynchronousRegister(
    label = "p_sync_register",
    clock = ref("clk"),
    reset = ref("reset"),
    assignment = proceduralAssign("data_out", ref("data_in"))
  )
}
```

`emitSynchronousRegister` consumes direct clock and reset references plus the
existing direct-reference `ProceduralAssign`. The edge, reset polarity and
reset value are semantic properties of this bounded operation rather than
caller-selectable Verilog syntax. The frontend retains source origins and
reference provenance, emits the process atomically and rejects null,
non-reference, escaped, nested, mixed or multiple process construction.

## Canonical IR and validation

ParamRTL records
`ModuleItem.SynchronousRegister(label, clock, reset, assignment)`. The node
means a positive-edge register with active-high synchronous reset-to-zero. It
does not encode `always`, `reg`, nonblocking assignment or replication syntax.

Validation requires distinct clock, reset and data roles. Clock and reset must
resolve to exact unsigned one-bit input ports. Data must be an input, the
target must be the module's sole output, and their packed widths and signedness
must be exactly equivalent across the complete legal parameter domain. At most
one synchronous register is accepted per module. Any sibling continuous
assignment, combinational process, instance, generate region or second
sequential process fails before emission.

Those constraints prove complete reset and data paths plus unique register
ownership for this atomic process. They advance
`PhaseCheck_noRegisterAsLatch` only to `partial`: enables and hold behavior,
multiple registers, general clocked statements and processes under
parameterized branches are not modeled. `PhaseCheckCrossClock` also advances
only to `partial`: one validated clock and the no-sibling rule make an internal
multi-domain crossing unrepresentable, but external input-domain provenance,
multiple clocks and CDC structures are not modeled or approved.

## Morph orchestration and strict Verilog-2001

A register changes neither the public port schema nor reachable child
hierarchy, so `MorphVerilog` needs no process-specific default-shape recursion.
The concrete Spinal witness still runs the shared inherited width, register,
clock-domain and global-data phases. Independently, the complete symbolic
ParamRTL design is validated and target-checked before default shape is
compared or public output is written. An invalid symbolic clock therefore
fails at `ParamRtlValidation` even when the concrete default witness is valid.

The strict backend infers the process-owned output as `output reg` and emits:

```verilog
always @(posedge clk) begin : p_sync_register
  if (reset == 1'b1) begin
    data_out <= {WIDTH{1'b0}};
  end else begin
    data_out <= data_in;
  end
end
```

Both assignments are nonblocking. The reset is inside the positive-edge block,
so it is synchronous. SystemVerilog `always_ff`, asynchronous sensitivity-list
reset, inferred latch behavior and mixed blocking/nonblocking ownership remain
forbidden.

## Public executable contract

`synchronous_register.v` is the twelfth artifact generated through
`MorphContractFixtureGenerator` and `MorphVerilog`. `SynchronousRegister` has
public integer parameter `WIDTH=8`, one-bit `clk` and `reset` inputs, one
`WIDTH`-bit data input and one `WIDTH`-bit registered output. The concrete
Spinal witness uses a positive-edge clock domain with active-high synchronous
reset and an eight-bit initialized register.

CI requires exact normal/reverse twelve-file inventories and byte identity
with reviewed goldens. Verilator lints default and five-bit widths in strict
1364-2001 mode. Icarus proves that reset and data do not alter the output away
from a positive edge, then proves synchronous clear and later data capture in
simultaneous eight- and five-bit instances. Yosys proves exactly one
positive-edge flip-flop, no latch, active-high synchronous zero reset, direct
data/output bindings and exact pre/post-synthesis widths for both parameter
values.

## Deferred by design

This increment does not add clock enable or hold semantics, asynchronous
reset, selectable edges or polarities, multiple registers or clocks, arbitrary
clocked expressions, sequential processes inside hierarchy/generate regions,
CDC structures or memories.

## Recommended next increment

Increment 17 should add one bounded active-high asynchronous-reset register
variant with the same one-clock, direct full-width data and sole-output
restrictions. The backend should emit deterministic
`always @(posedge clk or posedge reset)` with reset-to-zero priority and
nonblocking assignments. The thirteenth fixture should prove that reset clears
without a clock edge and that later positive edges capture data at default and
awkward widths. This completes the bounded v1 synchronous/asynchronous reset
axis without first adding hold semantics. Clock enables, multiple registers or
clocks, generated clocks, CDC, hierarchy/process mixing and memories should
remain deferred.
