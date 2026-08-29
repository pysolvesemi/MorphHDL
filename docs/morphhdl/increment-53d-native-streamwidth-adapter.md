# Increment 53d — Native StreamWidthAdapter relational-width parameterization

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

## Exact receiver provenance

Unsupported native-`Int` method handling is classified from the method's exact
receiver and explicit arguments, not from any symbolic integer found somewhere
inside the complete syntax subtree. Consequently:

- a direct symbolic call such as `root.abs` remains fail-closed;
- a derived symbolic receiver such as `(root + 1).abs` remains fail-closed;
- a static call such as `math.abs(root)` remains fail-closed; and
- an ordinary collection operation such as
  `Vector(root, root + 1).reverse` remains ordinary Scala even though the
  collection elements contain symbolic integer expressions.

This distinction is required by the untouched native downsize and upsize paths,
which call `.reverse` on the `IndexedSeq[Bits]` produced by `subdivideIn` while
its slice count is derived from a symbolic width. MorphHDL must preserve that
collection operation rather than rewriting it as native `Int.reverse`.

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
- collection `.reverse` with nested symbolic values remains ordinary Scala,
  while direct, derived-receiver and static unsupported native-`Int` calls all
  fail closed;
- concrete native generation remains parameter-free at the default witness;
- Scala 2.12.18 and 2.13.12 both pass.

## Closure repair

Native normalization can retain an exact internal `Resize` expression after its
weak-clone result object has been removed. MorphHDL therefore binds the symbolic
target width to that exact surviving `Resize` node by JVM identity. During
post-publication lowering, only a whole-target continuous assignment proven
from that graph identity may replace its concrete default-witness least-
significant-bit range with the retained symbolic range. For example, the native
12-bit witness slice `[11:0]` becomes `[OUTPUT_WIDTH-1:0]` for the proven upsize
output assignment.

This repair is generic: production code contains no StreamWidthAdapter module,
port, instance or signal-name recognition. Conflicting provenance, missing
final names, witness mismatches and non-unique continuous-assignment mappings
all fail closed. The formerly failing `UP_WIDTH=16` backpressure witness now
preserves both bytes (`16'hB2A1`), while the source-preservation boundary remains
unchanged. The branch is synchronized with the current `parameterized-verilog`
head before the final dual-Scala validation matrix.

The final regression repair confines bounded-domain constant-predicate folding
to an active `nativeWidthFunction` boundary. Existing constructor boundaries,
including the native StreamFifo formalization path, continue to retain their
established symbolic alternatives even when a narrowed caller domain makes a
predicate constant. This prevents a helper driver's structural owner from
diverging from a module-scope consumer and is checked by the compound-depth
StreamFifo regression on both supported Scala versions.

## Generic named-definition lifetime closure

Native symbolic capture belongs to the exact Scala definition that opens
the runtime boundary. Every non-constructor `DefDef` reached while an
enclosing constructor or native width-function boundary is active now
snapshots and clears the complete native runtime-capture state, then
independently discovers any direct `widthOf(Data)` roots owned by that
definition and opens a fresh boundary only when required. Function
literals remain inside their owning definition because they execute as
part of that definition body.

This is a compiler-wide lexical rule, not a `StreamFifo`, `StreamFifoCC`,
or `StreamWidthAdapter` exception. The production transform contains no
component-name condition for named-definition isolation. Native StreamFifo
compound-depth and native StreamWidthAdapter suites remain functional
witnesses, while a dedicated dual-Scala architecture regression prevents
the generic rule from regressing. Any present or future SpinalHDL component
that enters the same native constructor or `widthOf` capture machinery
receives the same ownership and dominance protection automatically.
