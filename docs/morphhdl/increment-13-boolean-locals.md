# Increment 13: identity-bearing Boolean local parameters

Increment 13 adds typed Boolean derived constants without collapsing them to
Scala defaults. A Boolean local is created and declared through the guarded
frontend in the same way as an integer local:

```scala
val effectiveWidth = localParam("EFFECTIVE_WIDTH", width)
val widthOk = localParam("WIDTH_OK", effectiveWidth >= limit)
val routeHigh = localParam("ROUTE_HIGH", enable && widthOk)
val routeCode = localParam("ROUTE_CODE", routeHigh.select(1, 0))

moduleDef(
  // ...
  localParameters = Vector(
    integerLocalParameter(effectiveWidth),
    integerLocalParameter(routeCode)
  ),
  booleanLocalParameters = Vector(
    booleanLocalParameter(widthOk),
    booleanLocalParameter(routeHigh)
  )
)
```

Every local handle has an opaque identity and must be declared by the same
module-definition boundary that consumes it. Re-entrant symbolic factories
must create fresh handles on every evaluation; retaining a handle outside the
factory and reusing it fails closed even when its name and expression match.

## Combined dependency graph

`IntegerLocalParameter` and `BooleanLocalParameter` declarations share one
dependency graph. The graph permits forward references across kinds, rejects
integer-only, Boolean-only and mixed-kind cycles, and produces one stable
dependency-first order with lexical tie-breaking. The order is semantic rather
than source-order dependent. An integer local may use a Boolean local through
`HdlBool.select`, and a Boolean local may compare integer locals or reference
other Boolean locals.

Validation analyzes every expression subtree over the complete declared legal
domain. A false default cannot hide an unresolved reference, an unsafe divisor
or a cycle in an inactive operand. Integer and Boolean public parameters and
locals remain distinct namespaces; a same-name declaration across kinds is a
kind collision, not an alias.

Morph default-shape agreement evaluates the same combined dependency-first
order for the top and for every reachable child instance. Child public integer
and Boolean bindings are evaluated in the exact parent context first. The
child's integer and Boolean locals are then recomputed together before its port
widths, generate counts, generate-if selection or further child bindings are
examined. Sibling instances never share evaluated local facts.

## Strict Verilog-2001 legalization

ParamRTL retains `BoolExpr.LocalParameterRef` and
`BooleanLocalParameter(name, value)` as typed nodes. The Verilog-2001 backend
legalizes each Boolean local to `localparam integer` with a canonical `1`/`0`
value. References are emitted as explicit `NAME == 1` predicates, including in
Boolean child bindings and conditional integer expressions. Mixed locals are
emitted in the validated combined dependency-first order, so Verilog never
depends on forward local declarations.

## Public contract fixture

`boolean_locals.v` is the ninth artifact generated through
`MorphContractFixtureGenerator` and `MorphVerilog`. Its fixed eight-bit
`BooleanLocals` top derives this deliberately mixed chain:

```text
WIDTH -> EFFECTIVE_WIDTH (integer)
      -> WIDTH_OK (Boolean)
ENABLE + WIDTH_OK -> ROUTE_HIGH (Boolean)
ROUTE_HIGH -> ROUTE_CODE (integer select)
ROUTE_CODE == 1 -> child SELECT binding
```

The child uses one generate-if to select distinct high and low leaf schemas.
CI requires exact normal/reverse nine-file inventories and byte identity with
the reviewed golden. Verilator checks the default, disabled, below-limit and
inclusive equality configurations. Icarus simulates all four in strict
`-g2001` mode. Yosys proves the exact selected hierarchy, direct fixed-width
bindings and top ports for every configuration.

## Deferred by design

This increment does not add multiple or nested generate regions,
generate-if/for nesting, `GenerateCase`, runtime processes, memories or
guard-sensitive interval refinement. Boolean local parameters are compile-time
constants and cannot change public port presence or direction.

## Recommended next increment

Increment 14 should implement the first bounded `GenerateCase` vertical slice:
one non-nested case region over a validated integer selector, explicit unique
literal choices plus a mandatory default, full-branch validation and driver
coverage, default-shape selection, deterministic Verilog-2001 emission and a
public parse/simulation/synthesis fixture. Nested structural control flow and
runtime processes should remain later increments.
