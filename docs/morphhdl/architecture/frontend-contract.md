# Frontend and elaboration contract

MorphHDL has two explicit elaboration modes. The existing concrete mode is the
compatibility reference; parameterized mode captures parameter intent before
ordinary Scala evaluation can erase it.

## Entry points

Concrete generation remains unchanged:

```scala
SpinalVerilog(new DisplayController(DisplayConfig(laneCount = 4)))
```

Parameterized Verilog uses a separate entry point and explicit public
parameters in top-level glue. Increment 7 exposes the bounded entry point as
two re-entrant factories because the current frontend cannot yet derive both a
Spinal `Component` and ParamRTL from one constructor:

```scala
MorphVerilog(SpinalConfig(targetDirectory = "rtl")) {
  MorphProgram(
    concreteWitness = new DisplayController(DisplayConfig(laneCount = 4)),
    parameterizedDesign = DisplayControllerParamRtl.design(
      laneCount = HdlInt.param("LANES", default = 4, min = 1)
    )
  )
}
```

Both arguments are by-name factories. The concrete factory may be replayed by
Spinal's source-location diagnostic pass, and the symbolic factory is invoked
exactly once after concrete validation succeeds. Before emission,
`MorphVerilog` also requires their default top name and every reachable
module's flat port directions, signedness and widths and recursive child-module
multiplicities to agree. This guards the bounded dual-factory association but
is not a complete behavioral equivalence proof. A future frontend tranche may collapse this surface to
`MorphVerilog { new DisplayController(...) }` only after one constructor can
honestly supply both representations.

The names are part of the v1 source contract:

- `MorphVerilog`: parameter-aware Verilog-2001 generation entry point.
- `MorphProgram`: explicit concrete-witness and symbolic-design factories used
  by the Increment 7 entry point.
- `HdlInt`: dual-valued integer carrying a concrete witness and a symbolic
  parameter expression.
- `HdlBool`: dual-valued Boolean parameter expression.
- `GenIndex`: compile-time generate index; neither a Scala `Int` nor an RTL
  signal.

An `Int` may be converted to `HdlInt`, allowing ordinary construction such as
`DisplayConfig(laneCount = 4)`. The reverse conversion is forbidden.

## Structural control flow

The preferred homogeneous-loop syntax is:

```scala
for (lane <- 0 until config.laneCount) {
  // Concrete mode: ordinary elaboration iterations.
  // Parameterized mode: one captured GenerateFor body.
}
```

This syntax is valid only when the upper bound is `HdlInt`; the range yields a
`GenIndex`. Increment 6 proves this spelling on Scala 2.12.18 and 2.13.12 with
an `Int.until(HdlInt)` extension. The standard `Int.until(Int)` remains the
selected method for ordinary Scala ranges. No explicit `generateFor(...)`
fallback and no `HdlInt => Int` conversion are used.

The symbolic range deliberately exposes only `foreach`. It is not a Scala
collection and has no `map`, `flatMap`, `withFilter`, iterator or indexing
surface. Concrete mode executes the body once per witness index. Parameterized
mode executes it exactly once, records one scoped `GenIndex` body and lowers it
to the existing zero-based, unit-stride ParamRTL `GenerateFor`.

Runtime `foreach` cannot portably recover a Scala lambda variable name on both
supported compilers. Bare loops therefore derive deterministic labels and
index names from their source file and line, independent of construction
order. A reviewed output contract may name both explicitly:

```scala
for (lane <- (0 until config.laneCount).named(
  label = "g_lane",
  index = "lane"
)) {
  // one captured body
}
```

The explicit names affect emitted identifiers only; they do not weaken index
scope or permit general loop starts, strides or nesting.

Structural conditions are explicit:

```scala
generateIf(config.enableFeature) {
  // parameterized structure
} otherwise {
  // alternate structure
}
```

An ordinary Scala `if`, `match`, collection size, recursion or class selection
continues to execute during elaboration and therefore may depend only on static
Scala values.

## Static and public configuration

Only fields that must remain downstream HDL parameters use `HdlInt` or
`HdlBool`. Architectural selections that change the public protocol, port
presence, clock-domain schema or component class remain static `Int`,
`Boolean`, sealed traits or enums.

Parameterized library APIs must either:

1. accept the symbolic type without losing its expression, or
2. reject the call and identify the required adapter.

Calling an existing `Int` API with the concrete witness of an `HdlInt` is not a
valid parameterized implementation.

## Dual-valued execution

Every public parameter carries:

- a default concrete witness used for ordinary object construction and
  differential validation;
- a typed symbolic expression used to build ParamRTL;
- declared constraints such as minimum, maximum, divisibility or relations to
  other parameters;
- source and logical-name metadata.

Each declaration also carries an opaque identity token. Frontend module
lowering accepts a symbolic reference only when that exact token is declared
by the module; a separately constructed, same-named `HdlInt.param` is not an
alias. Identity is checked per module, so independent modules may each declare
their own parameter named `WIDTH`.

The concrete witness is validation data, not a fallback. If symbolic capture
cannot represent an operation, elaboration fails even when the default witness
could execute it.

Increment 6 implements the first bounded integer slice: public integer
parameters with inclusive minimum/maximum constraints, integer literals,
`HdlInt * HdlInt`, and `GenIndex * HdlInt` for indexed part-select offsets.
Generate-index-dependent widths and child parameter bindings remain unsupported
and fail before ParamRTL construction. If `HdlInt.param` omits `max`, its
bounded default is `Int.MaxValue`.

## Required diagnostics

Parameterized elaboration must reject:

- implicit or explicit symbolic-to-Scala conversion outside an audited escape;
- parameter-dependent Scala `if`, `match`, recursion or collection length;
- indexing a Scala collection with `GenIndex`;
- parameter-dependent port presence or direction;
- zero or negative widths not disproved by constraints;
- unsupported library calls receiving a symbolic value;
- raw HDL fragments in strict mode;
- any fallback that silently specializes to the default value.

Generate-index values and expressions carry an opaque lexical scope token.
Using one after its loop, combining different scopes, nesting symbolic loops or
passing an index-derived expression to an unsupported consumer is a frontend
error. Guarded frontend expressions retain that token until final item
emission, so raw `GenerateIndexRef` values cannot bypass the scope check.
Capture state is restored on every exit and is isolated per thread. An
`HdlInt` loop requires an explicit concrete or parameterized frontend session,
so a loop dispatched to another thread fails closed instead of falling back to
its concrete witness.

Scala `==`, `!=`, hashing and numeric conversion on a statically typed `HdlInt`
or `GenIndex` fail closed. The values themselves reject forward comparisons at
runtime; the inherited IDSL compiler plugin rejects reverse `==`, `!=`,
`equals`, `eq` and `ne` calls before elaboration. In particular, both
`lane == 0` and `BigInt(0) == lane` are rejected instead of silently
specializing Scala control flow. Upcasting either value to `Any` is outside the
supported frontend surface.

Each error must report the parameter/expression, source location, unsupported
consumer and a suggested static or parameter-aware replacement.
