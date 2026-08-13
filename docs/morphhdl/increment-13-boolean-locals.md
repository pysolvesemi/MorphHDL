# Increment 13: identity-bearing Boolean local parameters

Increment 13 adds named Boolean constants derived inside a module without
exposing them as public override points:

```scala
val effectiveWidth = localParam("EFFECTIVE_WIDTH", width + offset)
val routeHigh = localParam("ROUTE_HIGH", enable && (effectiveWidth >= limit))

generateIf(routeHigh, "g_local_high", "g_local_low") {
  // ...
} otherwise {
  // ...
}
```

## Implemented surface

ParamRTL represents the declaration as
`BooleanLocalParameter(name, value)` and represents its uses with the distinct
`BoolExpr.LocalParameterRef` node. Public and local Boolean names are separate
typed namespaces. They cannot be silently interchanged, shadowed across kinds
or mistaken for integer locals.

Integer and Boolean locals share one dependency graph. Validation permits
forward references in canonical ParamRTL, analyzes every expression regardless
of its default value, rejects missing and wrong-kind references, and rejects
cycles that cross either local kind. The successful graph produces one stable
dependency-first order with lexical tie-breaking. Exact Boolean-local defaults
are retained separately from public Boolean defaults in validated module and
instance facts.

The frontend overloads `localParam` for `HdlBool` and requires the exact returned
identity in `booleanLocalParameter`. Provenance includes all public Boolean,
public integer, integer-local and Boolean-local dependencies. Foreign-session,
forged, duplicate, loop-variant and wrong-kind declaration paths fail before
ParamRTL construction.

Morph default-shape recursion first evaluates public integer and Boolean child
bindings. It then recomputes integer and Boolean locals together in the
validated dependency order for that exact child instance. Packed widths,
conditional integer values, generate counts, generate-if selection and further
Boolean child bindings all consume this recomputed context. A parent binding
can therefore make a child Boolean local differ from the child declaration's
standalone default, and the substituted result propagates across later
hierarchy levels.

The strict Verilog-2001 backend emits every Boolean local as a dependency-ordered
`localparam integer`. Literal values are `1` or `0`; expressions are normalized
to `(<predicate>) ? 1 : 0`. A reference is tested as `NAME == 1`, including when
used in generate-if or normalized for a named Boolean child association. No
SystemVerilog Boolean type or configuration-specialized module is introduced.

## Public contract fixture

`boolean_local_routing.v` is the ninth artifact produced by
`MorphContractFixtureGenerator` through `MorphVerilog`. Its fixed eight-bit
interface derives integer local `EFFECTIVE_WIDTH`, then Boolean local
`ROUTE_HIGH = ENABLE && EFFECTIVE_WIDTH >= LIMIT`. `ROUTE_HIGH` selects the
top generate-if branch and is also forwarded to child Boolean parameter
`SELECT`; the child selects a distinct high or low route leaf.

CI generates all nine artifacts in normal and reverse construction order,
requires an exact nine-file inventory and compares each byte with the reviewed
golden. Verilator lints the default, disabled, below-limit and inclusive-limit
configurations. Icarus simulates all four in strict 2001 mode. Yosys proves the
fixed top ports, selected top branch, exact forwarded route instance and exact
selected leaf and bindings for each configuration.

Morph orchestration tests cover true and false Boolean-local defaults, a child
whose parent binding changes its Boolean local from the declaration default,
multi-level forwarding of the recomputed local, and eager failure of an unsafe
dependency hidden behind a false Boolean operand.

## Deferred by design

This increment does not add multiple or nested generate-if regions within one
module, generate-if/for nesting, `GenerateCase`, runtime combinational or
sequential processes, memories or guard-sensitive interval refinement. Boolean
locals remain compile-time constants and cannot change public port presence or
direction.

## Recommended next increment

Increment 14 should add bounded `GenerateCase`: one non-nested case region over
an integer parameter expression, unique literal choices, one mandatory default
block, path-aware driver validation and deterministic strict Verilog-2001
emission. Nested structural control flow and runtime processes should remain
separate later increments.
