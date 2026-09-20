# Increment 34 — Generic combinational and sequential processes

Increment 34 extends the single-source parameterized-Verilog path from ordinary expressions and structural generate control into normal SpinalHDL processes.

## Supported native process surface

The normal SpinalHDL AST and Verilog emitter remain authoritative for:

- combinational `when`, `elsewhen`, `otherwise`, `switch`, and nested conditional statements;
- clocked register assignments, initialization, clock enable, clock edge, reset kind, and reset polarity;
- symbolic packed widths used by process targets and sources; and
- inherited driver, assignment-overlap, latch, clock-domain, and reset validation.

Parameterized lowering rewrites retained parameter declarations and packed ranges around the native process text. It does not replace normal processes with separately authored ParamRTL mux or register nodes.

## Structural versus procedural loop classification

A parameter-bounded `HdlRange.foreach` body is elaborated once in the caller's real SpinalHDL scope and classified by the hardware it creates:

- declarations, ordinary child Components, and concurrent structural connections remain Verilog generate regions;
- one direct indexed packed assignment to an existing signal may become a procedural Verilog `for` inside the native combinational or clocked process that owns it; and
- mixed structural/process bodies, nested parameterized process loops, unsafe or non-contiguous slices, Scala-only side effects, and unsupported process consumers fail with explicit diagnostics.

The procedural-loop classifier retains the concrete witness graph long enough for all inherited SpinalHDL validation phases to run. The post-pass replaces only the marked witness assignment inside its existing `always` block, preserving surrounding runtime conditions, reset handling, and assignment kind (`=` or `<=`).

## Executable contract

`morphhdl.GenericProcessLoweringTests` covers native combinational and sequential witness equivalence, procedural-loop Verilog-2001 emission and simulation, structural-versus-procedural classification, and inherited no-driver/latch validation.

`spinal.core.internals.ParameterizedDataShapeTests` retains the lower-level native bridge contracts for initialized/reset registers, conditional register assignments, rising/falling clock edges, missing drivers, and overlapping drivers.
