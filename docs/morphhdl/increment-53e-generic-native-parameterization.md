# Increment 53e generic native parameterization contract

Increment 53e uses `spinal.lib.StreamFifoCC` as a concrete witness for generic
MorphHDL mechanisms. The production implementation is not permitted to
recognize that component, another selected library component, an emitted signal
name, or a native source filename.

## Production boundary

- SpinalHDL `core/src/main`, `lib/src/main`, and `idslplugin/src/main` remain
  unchanged by Increment 53e.
- MorphHDL does not define, copy, subclass, or reconstruct `StreamFifoCC` or its
  CDC logic.
- The exact upstream `spinal.lib.StreamFifoCC` constructor remains authoritative
  for hardware behavior, clock domains, Gray pointers, synchronizers, memory,
  reset behavior, occupancy, and native legality checks.

## Generic capture rule

Native constructor instrumentation is selected from compiler-visible type and
shape information. A component is eligible for implicit single-formal capture
only when it is a typed `spinal.core.Component` subclass with exactly one Scala
`Int` constructor dimension. Components with no such dimension or more than one
remain untouched unless a later explicit formal mapping identifies the intended
argument. Instrumented hooks are inert outside an active MorphHDL formalization
boundary, preserving ordinary SpinalHDL elaboration.

## Generic width-equivalence rule

Two different symbolic width formulas may be treated as equal only when all of
the following hold:

1. each expression carries exact compiler-retained native-`Int` AST provenance;
2. their formal declarations have one compatible finite schema;
3. each formula is evaluated through its own captured AST for every legal value
   in the complete declared domain;
4. every evaluation is defined, positive, and equal;
5. the domain stays within the shared bounded-proof limit.

Rendered Verilog text, component names, source locations, emitted signal names,
and equal concrete defaults are never sufficient evidence. Missing provenance,
unsupported arithmetic, ambiguous roots, schema conflicts, non-positive widths,
or oversized domains fail closed.

## Required verification

The closure gate applies the generic engine to a native `StreamFifoCC` witness
and separately tests the engine without a FIFO component. It requires both
Scala 2.12.18 and 2.13.12, complete MorphHDL regressions, depths 4, 8, and 16,
both native pop-reset-buffer modes, independent push/pop clocks, ordered-data
simulation, strict Verilog-2001 compilation, Yosys synthesis, deterministic
cross-Scala RTL, and sequential equivalence to independently elaborated native
`SpinalVerilog` witnesses. A deliberately wrong-depth reference must fail the
equivalence gate.
