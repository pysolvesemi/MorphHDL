# Increment 53b — Native StreamWidthAdapter relational-width parameterization

## Objective

Prove that MorphHDL can generate parameterized Verilog from an ordinary
SpinalHDL component that invokes the existing native
`spinal.lib.StreamWidthAdapter`, while every upstream-owned SpinalHDL source
file remains unchanged.

## Non-negotiable boundary

- The authoritative conversion algorithm is the existing
  `StreamWidthAdapter` in native `spinal.lib.Stream.scala`.
- MorphHDL may retain symbolic provenance and lower the resulting native graph,
  but must not implement a replacement adapter, duplicate its RTL, recognize
  emitted module/port/signal names, or patch any upstream-owned `core`, `lib`,
  or `idslplugin` source.
- The application fixture consists only of ordinary SpinalHDL components and
  exact MorphHDL formalization metadata. Each leaf directly calls
  `spinal.lib.StreamWidthAdapter`.
- Concrete `SpinalVerilog` continues to elaborate those same components and
  must remain parameter-free.

## Bounded contract

One ordinary top component instantiates three ordinary leaves, each of which
invokes the real native adapter:

1. equal width: input and output use the same `WIDTH` formal over `[1, 32]`;
2. downsize: `INPUT_WIDTH` is in `[9, 16]` and the output is fixed at 8 bits;
3. upsize: the input is fixed at 8 bits and `OUTPUT_WIDTH` is in `[9, 16]`.

The parent exposes public `EQ_WIDTH`, `DOWN_WIDTH`, and `UP_WIDTH` parameters
and binds them by name to the three native leaf formals. The adapter uses
`LITTLE` endianness and `padding = true`. Each downsize/upsize domain retains a
constant native factor of two, so the untouched native `Counter`, register,
resize, concatenate, and slice logic remains valid for every admitted override.
The three native relational alternatives are therefore proven without a
MorphHDL-authored conversion algorithm.

A native invocation that introduces a second, independently rooted symbolic
payload width must fail closed. Equal concrete witness values and equal emitted
text are never accepted as provenance.

## Required evidence

- native source preservation manifest passes with no exception;
- the fixture source calls `spinal.lib.StreamWidthAdapter` directly;
- no production MorphHDL replacement adapter or component-specific RTL exists;
- one deterministic parameterized Verilog file contains one top and three
  native application leaf definitions;
- the top exposes `EQ_WIDTH`, `DOWN_WIDTH`, and `UP_WIDTH`, and the leaves expose
  `WIDTH`, `INPUT_WIDTH`, and `OUTPUT_WIDTH` respectively;
- overrides `(5, 9, 9)`, `(8, 12, 12)`, and `(16, 16, 16)` compile, simulate,
  lint, and synthesize;
- bit order and Stream ready/valid behavior are preserved under backpressure;
- concrete native generation remains parameter-free at the default witness;
- Scala 2.12.18 and 2.13.12 both pass.
