# WA-11 symbolic Boolean/integer width normalization

Status: implementation and qualification in progress; not complete or merged.

Baseline: `086cb4c642d0182e33bd86fd391f46fc7c0eb2a8` on
`parameterized-verilog`. The user-provided reproduction was executed unchanged
using the local source build and both repository compiler plugins:

```bash
sbt "morph/Test/runMain ReproduceBooleanWidth /tmp/boolean-width-repro"
```

Source: `morphhdl/src/test/scala/nativeapplication/ReproduceBooleanWidth.scala`.
The baseline run on Scala 2.12.18 / Java 17.0.20 succeeded. Actual generated RTL:

```verilog
module BooleanWidthExample #(
  parameter integer PPC4 = 0
) (
  input  wire [(((((((((PPC4 == 1)) ? (1) : (0))) == (1))) ? 1 : 0) * 3) + 1)-1:0] dataIn,
  output wire [(((((((((PPC4 == 1)) ? (1) : (0))) == (1))) ? 1 : 0) * 3) + 1)-1:0] dataOut
);

  assign dataOut = dataIn;

endmodule
```

## Cause and implementation

`HdlBool.asElabBool` used an integer `select(predicate, 1, 0)` as its
analyzer-authenticated bridge, then compared that integer to one.
`StructuralExpressionBridge` rendered a Boolean parameter as `PPC4 == 1`.
`ElabBool.toElabInt` added another signed integer ternary. Width emission
retained that expression. The hardware Boolean-ternary pass never sees this
elaboration expression.

WA-11 retains the existing authenticated ingress and validation, and normalizes
conversion construction before symbolic expressions reach widths, geometry,
control or hierarchy bindings. This is canonical normalization, active even
when hardware wire-assignment passes are disabled. No emitted-text parsing or
substitution is involved.

A complete authoritative identity proof over a declared `{0,1}` parameter
allows its `parameter integer` value to replace the conversion. Both have
signed 32-bit Verilog integer semantics. General predicates retain `? 1 : 0`
to preserve integer sizing and signed arithmetic. Native conversion origins
are retained privately on typed values; a round-trip comparison still derives
and validates fresh exact-domain/projection evidence instead of returning a
saved source with broader authority.

## Actual normalized reproduction

The same source now emits:

```verilog
module BooleanWidthExample #(
  parameter integer PPC4 = 0
) (
  input  wire [((PPC4 * 3) + 1)-1:0] dataIn,
  output wire [((PPC4 * 3) + 1)-1:0] dataOut
);

  assign dataOut = dataIn;

endmodule
```

## Validation

Initial Scala 2.12.18 checks passed: 24 frontend tests (six new) and ten new
native typed normalization tests. The earlier run also passed 44 inherited
integer/domain tests. JVM API comparison against the actual unmodified
baseline classes passed for both core and frontend.

Both Scala 2.12.18 and 2.13.12 passed all 263 frontend tests and 58 focused
native tests, with no failure or skipped test. Their 64 generated artifacts
(including the known unsupported helper controls) and the exact reproduction
match byte-for-byte.

The complete Scala 2.12 production check passed 62 artifact compilations
(30 normalized candidates, 30 unchanged-compiler references and both exact
reproductions), 156 parameter override instances and 2,496 input-pattern
checks. The same source compiler at the pinned baseline generated every
reference. These counts are overlapping qualification views, not a sum of
independent feature tests. Repeat and hardware-pass-toggle byte checks passed.
Four helper compilation rejections are reported separately and provide no
successful simulation credit.

Final source audits and final-head CI remain in progress. No formal or merge
success is claimed here.

## Preserved limits

Typed general elaboration retains its existing single-root exact-domain
contract, projection restrictions and local-parameter diagnostics. Production
native width schemas still reject negative parameter minima; typed integer
negative-domain and signed-boundary tests remain independent of that existing
publication restriction. Integer domains `0..3` exercise the production
non-Boolean rejection controls.

An additional helper fixture discovered an existing publication defect:
`HdlInt.addressWidth.hdlEq(...).asElabBool` can emit a `clog2` call without
its function definition. The same source fails strict compilation with the
unchanged baseline compiler. This is retained as an explicit known-unsupported
negative fixture, not counted as successful production coverage. WA-11
normalizes its Boolean conversions but does not extend helper publication.
