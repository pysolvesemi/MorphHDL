# Increment 15: bounded runtime combinational mux

Increment 15 adds the first runtime RTL process to the MorphHDL parameterized
path. It is deliberately narrow: one stateless two-way process uses a one-bit
input condition, direct input-port values and complete output assignments.
Parameter-dependent structure remains separate from runtime selection.

The guarded frontend shape is:

```scala
val whenTrue = proceduralAssign("result", ref("data_true"))
val whenFalse = proceduralAssign("result", ref("data_false"))

captureItems {
  emitCombinationalIf(
    label = "p_runtime_mux",
    condition = ref("sel"),
    whenTrue = Vector(whenTrue),
    whenFalse = Vector(whenFalse)
  )
}
```

`proceduralAssign` accepts only a direct target name and a guarded direct
runtime reference. `emitCombinationalIf` consumes one explicit stable label,
one direct condition reference and mandatory assignment vectors for both
paths. Source origins and reference provenance remain guarded until module
capture. Null, non-reference, escaped and foreign values fail at the frontend
boundary. No mutable statement-by-statement builder is exposed; empty or
duplicate branch vectors are retained atomically for deterministic ParamRTL
diagnostics.

## Canonical IR and validation

ParamRTL records `ProceduralAssign(target: RtlExpr.Ref, value: RtlExpr.Ref)`
and `ModuleItem.CombinationalIf(label, condition, whenTrue, whenFalse)`. This is
runtime process intent, not a constant `BoolExpr` or structural `GenerateIf`.
The IR does not encode Verilog `reg`, `always` or assignment spelling.

Validation checks the condition is a one-bit input, every value is an input,
every target is an output and target/value packed types match across the full
legal parameter domain. Both branches must be non-empty and must assign the
same complete target set exactly once. An output owned by the process cannot
also be driven continuously, by a child instance or through parameterized
generate structure. At most one process is accepted per module in this
tranche, and sibling hierarchy or generate items are rejected.

These rules prove no latch and no override for the bounded process without
using a runtime default witness. Both paths always validate. Direct
input-to-output references also make a combinational feedback edge
unrepresentable; a general runtime-expression dependency graph remains future
work.

## Morph orchestration and strict Verilog-2001

The process does not change ports or child hierarchy, so `MorphVerilog` needs
no new default-shape recursion. The concrete Spinal witness still runs the
shared inherited width, hierarchy, no-latch/no-override and combinational-loop
phases. ParamRTL validation and target capability verification independently
prove the symbolic process before public output is written.

The strict backend infers a process-owned output and emits it as `output reg`.
It legalizes the process to one deterministic named block:

```verilog
always @* begin : p_runtime_mux
  if (sel == 1'b1) begin
    result = data_true;
  end else begin
    result = data_false;
  end
end
```

Assignments are blocking. SystemVerilog `logic`/`always_comb`, nonblocking
combinational assignments and inferred latches remain forbidden.

## Public executable contract

`runtime_mux.v` is the eleventh artifact generated through
`MorphContractFixtureGenerator` and `MorphVerilog`. `RuntimeMux` has public
integer parameter `WIDTH=8`, one one-bit `sel`, two `WIDTH`-bit data inputs
and one `WIDTH`-bit process output. The concrete witness selects between two
eight-bit inputs with Spinal's normal combinational `when`/`otherwise` logic.

CI requires exact normal/reverse eleven-file inventories and byte identity
with reviewed goldens. Verilator lints default and five-bit widths in strict
1364-2001 mode. Icarus simulates select false and true at both widths. Yosys
converts the process, proves exactly one mux with bit-for-bit A/B/S/Y bindings,
rejects storage through structural checks and confirms post-synthesis port
widths.

## Deferred by design

This increment does not add arbitrary runtime expression trees, partial or
nested statements, multiple processes, runtime case statements, sequential
state, clock/reset semantics, memories or interaction with parameterized
generate regions.

## Recommended next increment

Increment 16 should add one bounded sequential-register process: one rising
edge clock, one static active-high synchronous reset, one full-width data input
and one registered output with complete reset/data assignments. The backend
should emit deterministic `always @(posedge clk)` with nonblocking assignments,
and the twelfth fixture should prove reset and capture at default plus an
awkward width. Clock enables, asynchronous reset, multiple clocks, memories and
nested runtime control should remain separate later increments.
