# Increment 31: generic expressions and connections

Increment 31 extends the single-source parameterized-Verilog path from direct
shape-preserving wires to ordinary SpinalHDL expression graphs. The existing
Spinal Verilog emitter remains authoritative for expression and process syntax;
MorphHDL adds bounded symbolic result-width analysis and rewrites only the
module parameter header and packed declaration ranges.

## Supported ordinary SpinalHDL surface

The reviewed surface covers:

- whole-leaf and fixed partial assignments;
- binary `Mux` expressions whose branches have compatible symbolic widths;
- same-width bitwise and arithmetic operators;
- carry-expanding arithmetic such as `UInt.+^`;
- concatenation-derived packed widths;
- fixed slices proven in range for the complete public parameter domain;
- fixed narrowing resize operations that remain narrowing for every legal
  parameter value; and
- the normal `spinal.lib.Stream.m2sPipe()` implementation as the first library
  reuse proof.

No component-specific ParamRTL adapter or emitter is introduced. A component is
first offered to the Increment 30 direct-assignment path. If that narrow gate is
exceeded by an eligible ordinary expression graph, MorphHDL asks the normal
Spinal emitter for concrete-witness Verilog and applies a target-neutral width
substitution backed by the retained public parameter schemas.

## Symbolic result-width rules

Result widths are derived from the ordinary Spinal expression AST. Direct
parameter leaves retain their public identifier. Equal-width assignment,
bitwise operators, muxes and ordinary addition/subtraction preserve the shared
width. Concatenation and carry-expanding arithmetic add operand widths. Fixed
slices and domain-invariant narrowing resize produce concrete result widths.
Every declaration is checked against its concrete witness and its full
minimum/maximum interval before Verilog is published. Canonical commutative
width expressions use explicit field comparison so Scala 2.12 and Scala 2.13
produce the same deterministic operand ordering.

Unsupported or unsafe cases fail with stable diagnostics. In particular, a
slice must be valid at the minimum legal source width, a resize may not switch
between widening and narrowing across the parameter domain, and a derived width
may not exceed `SpinalConfig.bitVectorWidthMax`.

## Native Stream library proof

The executable contract constructs ordinary `spinal.lib.Stream` signals and
calls the real default `m2sPipe()` implementation. The parameterized output is
required to become byte-identical to the ordinary concrete native output after
substituting the default width and removing the public parameter header. The
same test compares the ready/valid/payload, clock and reset behavioral markers
with the reviewed Increment 28 atomic oracle.

This proof intentionally permits only the reviewed one-entry m2s register
shape: one false-initialized valid register plus one or more uninitialized
symbolic payload registers on one rising-edge clock with active-high
synchronous reset. Generic sequential-process lowering remains Increment 34.

## Deferred

Hierarchy and parent/child parameter binding remain Increment 32. Symbolic
structural loops remain Increment 33. General combinational and sequential
processes remain Increment 34. Native memories remain Increment 35. Widening or
domain-crossing resize, floating symbolic slices, variable shifts with derived
widths and other unreviewed expression-width rules continue to fail closed.
