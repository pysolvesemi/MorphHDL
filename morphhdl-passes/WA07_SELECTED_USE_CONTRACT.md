# WA-07 selected-use substitution contract

WA-07 removes an unnamed internal combinational temporary only when every use
can be rewritten without changing packed-width, signedness, or strict
Verilog-2001 selection semantics. Candidate discovery remains identity- and
provenance-based; `_zz_*` spelling is never used to identify a temporary.

## Source slice followed by receiver slice

Given:

```verilog
wire [7:0] temporary;
assign temporary = source[9:2];
assign result = temporary[4:1];
```

WA-07 composes both ranges and emits one source selection:

```verilog
assign result = source[6:3];
```

It must not form the nested and non-portable expression
`source[9:2][4:1]`. Composition is allowed only when the right-hand side is a
direct source part-select spanning the complete temporary width and the
receiver range is statically proven in range. The composed selected value uses
unsigned selection semantics and retains an explicit receiver-width fence.

## Partial selection of an arbitrary expression

Given:

```verilog
wire [7:0] temporary;
assign temporary = a + b;
assign result = temporary[3:0];
```

WA-07 retains the temporary and both assignments. It must not emit
`(a + b)[3:0]`, and it must not rewrite only some receivers. One unsafe selected
use rejects elimination of that temporary atomically. No declaration, driver,
or receiver of that candidate is changed when this rejection occurs.

This conservative rule also covers partial or dynamic selections from muxes,
casts, resizes, concatenations, nested expressions, or another selected
expression unless a dedicated canonical rewrite proves an equivalent legal
form.

## Full-width selection exception

A zero-offset selection whose width is exactly the complete temporary width is
a whole-object use. For example:

```verilog
wire [3:0] temporary;
assign temporary = a + b;
assign result = temporary[3:0];
```

may be rewritten as the width-fenced whole expression:

```verilog
assign result = a + b;
```

The canonical IR keeps the assignment boundary explicitly. A full-width
part-select is unsigned even when the eliminated whole object is signed, while
a plain whole-object receiver retains the temporary's original signedness. A
one-bit `temporary[0]` use is equivalent to a whole-object use only when the
temporary is proven one bit wide.

## Procedural exclusion

A temporary assignment or any receiver represented by
`DriverKind.Procedural` is retained. WA-07 therefore does not move, duplicate,
or remove assignments that emit inside `always` blocks.

## Required regression coverage

`UnnamedWireExpressionSelectionSafetySpec` covers:

- rejection of a partial arithmetic-expression selection;
- flattening of a receiver slice through a direct source slice;
- full-width receiver selection collapse;
- rejection of a multi-bit arithmetic bit-select;
- the one-bit whole-object exception;
- rejection of selection through another expression node;
- unsigned semantics for full-width and composed selected uses of signed
  temporaries; and
- signed semantics for a plain whole-object receiver.

The normal WA-07 workflow runs these cases on Scala 2.12.18 and 2.13.12 before
native Verilog legality, simulation, synthesis, mutation, determinism, and
complete-domain formal-equivalence closure.