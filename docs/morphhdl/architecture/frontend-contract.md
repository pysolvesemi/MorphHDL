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
parameters in top-level glue:

```scala
case class DisplayConfig(
  laneCount: HdlInt = 4,
  dataWidth: HdlInt = 64
)

MorphVerilog {
  new DisplayController(
    DisplayConfig(
      laneCount = HdlInt.param("LANES", default = 4, min = 1),
      dataWidth = HdlInt.param("DATA_WIDTH", default = 64, min = 1)
    )
  )
}
```

The names are part of the v1 source contract:

- `MorphVerilog`: parameter-aware Verilog-2001 generation entry point.
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
`GenIndex`. Increment 6 must prove that this spelling is safe. If Scala method
resolution or symbolic body capture makes it unsound, the only permitted
fallback is an explicit `generateFor(...)` combinator approved by an
architecture review. It is never permitted to make the loop compile by adding
an implicit `HdlInt => Int` conversion.

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

The concrete witness is validation data, not a fallback. If symbolic capture
cannot represent an operation, elaboration fails even when the default witness
could execute it.

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

Each error must report the parameter/expression, source location, unsupported
consumer and a suggested static or parameter-aware replacement.
