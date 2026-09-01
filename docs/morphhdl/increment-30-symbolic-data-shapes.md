# Increment 30: native symbolic data shapes

Increment 30 extends the single-source bridge from one top-level `UInt` wire
to the ordinary SpinalHDL data-shape construction and cloning paths. It remains
a deliberately bounded shape-retention increment; generic expression and
process lowering are separate roadmap items.

## Ordinary component source

One direct public `HdlInt` can now be consumed through each core packed type:

```scala
final case class Payload(width: HdlInt) extends Bundle {
  val bits = Bits(width bits)
  val uint = UInt(width bits)
  val sint = SInt(width bits)
}

val payloadType = HardType(Payload(config.width))
val input = slave Stream(payloadType)
val output = master Stream(payloadType)
```

The retained parameter schema is attached to the ordinary `BaseType` leaves.
Normal `cloneOf` and `HardType` cloning preserve it, so the same rule applies
recursively to a `Bundle`, a statically sized `Vec`, and `Stream` and `Flow`
payloads. The symbolic width also survives an internal wire and an ordinary
uninitialized register. No component-specific ParamRTL design, adapter or
emitter is involved.

Concrete `Bool` leaves remain legal for a clock and for `Stream`/`Flow`
control. They are emitted as one-bit signals and do not create a public width
parameter. A Vec length remains a Scala constant in this increment; only its
packed leaf widths are symbolic.

## Bounded native logic

The native parameterized path accepts only:

- root-scope assignments between equal-type leaves carrying the identical
  parameter schema;
- direct assignments between concrete one-bit control leaves; and
- one unconditional, uninitialized register assignment on a direct concrete
  clock, again between equal-schema leaves.

This is enough to prove declarations and cloning without claiming generic RTL
lowering. Derived widths, arithmetic, concatenation, slicing, resize, muxes,
conditional assignments, reset/init/enable policies, partial aggregate
assignments, symbolic Vec length, hierarchy, loops and library algorithms such
as `Stream.m2sPipe()` still fail closed. They remain assigned to later roadmap
increments, including generic processes in Increment 34.

## Domain and witness validation

Every tagged leaf refers to one complete public parameter schema. Generation
requires a finite non-empty domain whose minimum is at least one and whose
maximum does not exceed `SpinalConfig.bitVectorWidthMax`. The concrete packed
width of every port, internal wire and register leaf must equal the declared
default. Same-named declarations must have identical default/minimum/maximum
schemas.

Cloning never substitutes the default for the symbolic schema. A missing tag,
conflicting clone, incompatible assignment, unsupported statement or
non-positive domain produces a stable source-located failure before the public
artifact is replaced.

## Public contract fixture

`symbolic_data_shapes.v` is the twenty-first reviewed contract. Its one normal
`SymbolicDataShapes` component exercises:

- direct `Bits`, `UInt` and `SInt` ports, with the native Scala AST retaining
  the three distinct leaf types;
- explicit `cloneOf` and reusable `HardType` construction;
- a `Payload` Bundle containing all three packed leaf types;
- two statically allocated Vec elements;
- flattened Stream and Flow payloads with concrete one-bit controls;
- an internal Bundle wire; and
- one uninitialized unconditional Bundle register clocked by `clk`.

Increment 30 originally emitted strict IEEE 1364-2001 with one `WIDTH=8`
public parameter and `[WIDTH-1:0]` on every packed payload leaf and internal
declaration. Increment 53f upgrades the reviewed artifact's two-element Vec
boundary to one `6 * WIDTH` packed input and one packed output while retaining
the logical Vec shape during elaboration; the other leaves remain unchanged.
Normal and reverse construction runs must be byte-identical and match the
reviewed golden. Ordinary `SpinalVerilog` still emits the concrete eight-bit
witness and no `WIDTH` parameter.

The Verilog text intentionally follows native Spinal style and does not add a
`signed` declaration keyword for `SInt`; native signed arithmetic uses explicit
signed expression handling. Increment 30 proves `SInt` identity in Scala tests
and its symbolic packed range without changing concrete emitter semantics.

Icarus exercises minimum 1, default 8, awkward 13 and maximum 64 widths in one
run. Verilator parses and lints the same four elaborations in Verilog-2001
mode. Yosys synthesizes each elaboration and checks every flattened port plus
the internal/register shapes; width-freezing mutations must fail the maximum-
width structural gate.

## Recommended next increment

The first unchecked roadmap item after this work is Increment 31, generic
expressions and connections. It should preserve symbolic widths through normal
assignments, muxes, arithmetic result widths, concatenation, slicing and resize,
then exercise the real `Stream.m2sPipe()` implementation instead of a parallel
component-specific model.
