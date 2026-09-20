# Increment 37 — Parameterized StreamFifo depth

## Objective

Carry one bounded public `DEPTH` parameter through the ordinary Spinal
`StreamFifo` source path. The same logical FIFO definition must elaborate at
its concrete witness and compile as strict Verilog-2001 at depths 1, 3, 5 and
8 without regeneration or a separately implemented ParamRTL FIFO.

## Native-source contract

The overload accepts `ParameterizedMemoryDepth`, constructs the existing
`StreamFifo` at that object's concrete witness, and retains the bounded depth
on the FIFO's one native `Mem`. Existing `Int` constructors and concrete
`SpinalVerilog` behavior are unchanged.

A non-power-of-two witness selects the existing terminal-count pointer-wrap
path. MorphHDL rewrites only witness-derived geometry and terminal constants in
the normally emitted FIFO:

- native storage depth and its guarded address domain;
- push/pop pointer and memory-address widths as `clog2(DEPTH, 1)`;
- pointer terminal count as `DEPTH - 1`;
- occupancy and availability widths as `clog2(DEPTH + 1, 1)`;
- full-capacity comparisons and availability arithmetic as `DEPTH`.

The normal Stream valid/ready/payload, flush, synchronous read arbitration,
read-first collision policy, pointer updates and occupancy update statements
remain authoritative. No FIFO algorithm is emitted from ParamRTL or a
component-specific replacement implementation.

## Supported boundary

This increment supports the ordinary default `StreamFifo` option set that
elaborates exactly one native `Mem`. Alternative option combinations selecting
another storage representation fail explicitly instead of silently losing the
symbolic depth. The declared domain must be finite, positive and Int-sized,
and its default must match the concrete witness.

## Validation

`ParameterizedStreamFifoDepthTests` reuses the reviewed Increment 36 harness,
checks public parameter propagation and symbolic storage/pointer/occupancy
geometry, preserves concrete-default output, and compiles the same generated
strict Verilog-2001 source with `DEPTH` overridden to 1, 3, 5 and 8. The focused
suite and existing parameterized core regressions run on Scala 2.12.18 and
2.13.12.
