# Increment 59 — Typed BlackBox parameter and generic binding

## Purpose

Increment 59 extends the ordinary SpinalHDL `BlackBox.addGeneric` surface so an
application can pass `ElabInt` and `ElabBool` values without erasing their
parameter expression. Existing `Int`, `Boolean`, `String`, `Double`,
`TimeNumber`, `VerilogValues` and `BaseType` generic values keep their native
behavior.

A typical external wrapper remains ordinary SpinalHDL source:

```scala
final class ExternalLeaf(width: ElabInt, enabled: ElabBool)
    extends BlackBox {
  setBlackBoxName("ExternalLeaf")
  addGeneric("WIDTH", width)
  addGeneric("ENABLED", enabled)

  val din = in(Bits(width bits))
  val dout = out(Bits(width bits))
}
```

The generated parent owns the public MorphHDL parameters and binds the exact
external instance:

```verilog
module Parent #(
  parameter integer ENABLE = 1,
  parameter integer WIDTH = 8
) (
  input  wire [WIDTH-1:0] din,
  output wire [WIDTH-1:0] dout
);

  ExternalLeaf #(
    .WIDTH(WIDTH),
    .ENABLED(((ENABLE) == (1)))
  ) external_leaf (
    .din(din[WIDTH-1:0]),
    .dout(dout[WIDTH-1:0])
  );
endmodule
```

MorphHDL does not emit an `ExternalLeaf` definition. Its implementation remains
owned by the external RTL source supplied to simulation, synthesis and formal
tools.

## Retention and publication boundary

`BlackBox.addGeneric` validates a typed value and gives the inherited native
emitter only its concrete `Int` or `Boolean` witness. The exact typed expression
is retained in the BlackBox component's `userCache`, keyed by a private object
identity. The cache entry records the exact BlackBox object, generic name,
expression, declaration-root authority and witness.

The normal Verilog emitter therefore remains authoritative for the instance,
all concrete generic kinds, port order, attributes, comments and formatting.
After inherited elaboration and validation, the existing external hierarchy
rewriter locates the one exact native instance by its graph identity and stable
instance name. It then:

1. declares every referenced root in the generated parent module header;
2. replaces only the right-hand side of retained typed named generic
   associations;
3. rewrites packed BlackBox port slices and their parent declarations from the
   same retained typed width expression; and
4. preserves all unrelated generic associations in their native order.

This is not witness-value inference. The concrete witness is checked only
against the exact cache record and native association that were created by the
same `addGeneric` call.

## Concrete and VHDL compatibility

Literal `Int` and `Boolean` calls are unchanged. A typed value with no symbolic
parameter also remains parameter-free and is emitted as its ordinary witness.

Native VHDL emission continues to consume the validated concrete witnesses.
MorphHDL's single-source symbolic publication target remains strict
Verilog-2001; Increment 59 does not introduce a symbolic VHDL rewriting path.

## Supported surface

Increment 59 supports:

- typed integer and Boolean BlackBox generic actuals;
- direct or derived single-root expressions such as `WIDTH + 1`, `WIDTH * 2`
  and typed Boolean predicates;
- multiple instances of the same external module with different actual
  expressions;
- BlackBox-only parameters that do not appear on a generated parent port;
- symbolic packed widths on direct portable BlackBox port connections; and
- mixed concrete, string and typed generic lists without reordering.

The implementation fails closed for duplicate generic names, missing native
associations, invalid or reserved names, witness mismatches, unsupported or
ambiguous instance text, non-direct packed port connections, missing port
names, non-positive width domains, independent same-named roots and copied or
inexact projection evidence.

## Proof gates

The Increment 59 workflow requires both Scala 2.12 and Scala 2.13 focused tests,
ordinary concrete BlackBox parity, deterministic replay, native VHDL witness
compatibility and the inherited source-preservation and production-retirement
audits.

The generated Verilog is compiled with independent external module stubs under
strict Verilog-2001, simulated with Icarus Verilog, linted with Verilator and
synthesized with Yosys. A specialized typed candidate is formally compared with
an independently elaborated concrete SpinalHDL witness. A deliberate Boolean
generic-binding mutation must produce a real equivalence failure.

## Non-goals

Increment 59 does not:

- generate, parse or reconstruct the external module implementation;
- recover a symbolic expression from an equal concrete witness;
- recognize a source filename, component class name or emitted signal name;
- introduce a second BlackBox-specific RTL emitter;
- add symbolic SystemVerilog or VHDL publication; or
- support dynamic expressions that lack exact typed domain authority.
