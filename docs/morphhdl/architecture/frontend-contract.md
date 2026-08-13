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
`MorphVerilog` also requires their default top name and every reachable module
instance's binding-aware flat port directions, signedness and widths and
recursive child-module multiplicities to agree. This guards the bounded
dual-factory association but is not a complete behavioral equivalence proof. A
future frontend tranche may collapse this surface to
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
generateIf(config.enableFeature, "g_enabled", "g_disabled") {
  // parameterized structure
} otherwise {
  // alternate structure
}
```

Increment 9 implements one top-level conditional per module-item capture. Both
branches are mandatory, are captured exactly once in parameterized mode and
are validated regardless of the default value. Concrete mode executes only the
default-selected branch. Explicit labels provide stable contract names; an
unlabeled overload derives deterministic names from source location.

The `otherwise` builder must be completed exactly once in the lexical session
that created it. Missing, duplicate, escaped, cross-thread or foreign-collector
completion fails with a source-located diagnostic. Branch capture is
transactional: a branch exception emits no partial `GenerateIf` and releases
its names. Conditional/loop nesting and a second `GenerateIf` in the same
capture are rejected in this tranche.

Increment 14 adds one integer-selected conditional region:

```scala
generateCase(selector)
  .choice(BigInt(0), "g_zero") { /* choice body */ }
  .choice(BigInt(1), "g_one") { /* choice body */ }
  .default("g_default") { /* mandatory fallback */ }
```

The builder is lexical and transactional like `GenerateIf`: `choice` retains
the same builder, while `default` finalizes it exactly once. Choice values and
labels are unique; labels are explicit in this tranche. Missing or duplicate
default completion, escaped builders, foreign collectors and cross-thread use
fail with retained source origins. Every branch is captured once in symbolic
mode, while concrete mode executes the exact default-selected choice or the
mandatory default.

The bounded frontend permits one conditional structural region total per
module capture: either one `GenerateIf` or one `GenerateCase`. A second sibling
conditional fails closed. `GenerateIf`, `GenerateCase` and `GenerateFor`
nesting remains rejected in every direction; an ordinary Scala `match` does
not preserve parameterized structure.

Increment 15 adds one stateless runtime process helper. `proceduralAssign`
captures a direct output-port target and direct input-port value reference;
`emitCombinationalIf(label, condition, whenTrue, whenFalse)` consumes one
one-bit input reference plus mandatory assignment vectors for both runtime
branches. The frontend retains source origins and port-reference provenance,
rejects null, non-reference and escaped inputs, and emits the process atomically
inside one module-item capture. This bounded API does not accept arbitrary
Scala `if`, partial branches, expression trees, nested runtime control or
sequential state.

Increment 16 adds one atomic sequential helper:
`emitSynchronousRegister(label, clock, reset, assignment)`. Clock and reset
must be guarded direct references; the existing `proceduralAssign` supplies one
direct output target and direct input data value. The helper fixes positive-edge
clocking and active-high synchronous reset-to-zero semantics, retains every
origin and reference dependency, and emits one `SynchronousRegister` only
after all inputs pass frontend checks. A second or nested process, mixing with
combinational/hierarchy/generate items, arbitrary expressions, selectable edge
or reset semantics, enable/hold behavior and escaped or cross-thread handles
fail closed in this tranche.

Increment 17 adds the parallel
`emitAsynchronousRegister(label, clock, reset, assignment)` helper. It retains
the same guarded direct-reference and atomic-capture rules, but fixes the reset
as active-high asynchronous reset-to-zero. The operation remains distinct from
`emitSynchronousRegister`, so reset timing cannot be selected or silently
changed by caller-provided syntax. A module may contain one supported runtime
process kind only; synchronous/asynchronous mixing, enables, multiple
processes and arbitrary clocked statements remain rejected.

Increment 18 adds
`emitSynchronousEnabledRegister(label, clock, reset, enable, assignment)`.
Enable is a third guarded direct control reference. The helper fixes
active-high enable semantics: reset has priority, enable high captures and
enable low retains state. The process is emitted atomically only after every
reference and assignment passes the same ownership, origin and cross-thread
checks. Arbitrary hold expressions, selectable control polarities, multiple or
mixed processes and nesting remain rejected.

Increment 19 adds the parallel
`emitAsynchronousEnabledRegister(label, clock, reset, enable, assignment)`.
It preserves the same atomic provenance and distinct-control rules while
fixing reset as active-high asynchronous reset-to-zero. Reset assertion is
immediate and retains priority over active-high capture; enable low retains
state. Selectable timing or polarity, arbitrary hold expressions, multiple or
mixed processes and nesting remain rejected.

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

Each public or local declaration also carries an opaque identity token.
Frontend module lowering accepts a symbolic reference only when that exact
token is declared by the module; a separately constructed, same-named value is
not an alias. Identity is checked per module, so independent modules may each
declare their own parameter named `WIDTH`.

The concrete witness is validation data, not a fallback. If symbolic capture
cannot represent an operation, elaboration fails even when the default witness
could execute it.

Increment 8 implements the current bounded integer slice: public and local
integer parameters, integer literals, `+`, `-`, `*`, `/`, `%`, unary `-`, and
the existing `GenIndex * HdlInt` indexed part-select offset. Every operation
retains its exact `BigInt` witness, ParamRTL expression, identity provenance,
generate scope and call-site origin. If `HdlInt.param` omits `max`, its bounded
default is `Int.MaxValue`.

The guarded lowering facade remains `private[morphhdl]`; `MorphVerilog` is the
public generation entry point. Inside that bounded integration surface,
`localParam(name, value)` creates an identity-bearing reference and
`integerLocalParameter(handle)` explicitly declares it in the final defaulted
`moduleDef` local-parameter vector. Public and local dependencies must be
declared by the same module. Duplicate, undeclared, same-named/different-token
and foreign-module tokens fail with source-located diagnostics. ParamRTL then
checks dependency cycles and emits locals in deterministic dependency-first
order. Local handles are owned by one module-definition boundary and must be
created afresh inside each re-entrant symbolic factory evaluation; reusing a
captured handle, even for a second same-named module definition, fails closed.

Increment 9 adds distinct public Boolean declarations and `HdlBool` literals,
references, `!`, `&&` and `||`. The frontend preserves the exact Boolean AST,
default witness and opaque declaration identities. The final defaulted
`moduleDef` Boolean-parameter vector must discharge those exact identities;
integer/Boolean name collisions, kind mismatches, undeclared tokens and
same-named distinct tokens fail at their retained source origin. ParamRTL keeps
the Boolean type; only the Verilog backend maps it to integer `1`/`0`.

Boolean operators do not short-circuit symbolic validation: every referenced
declaration remains part of the expression provenance even if a literal fixes
the default result. `GenerateIf` validates both structural paths and proves
each path's output-driver coverage separately. At Increment 9, Boolean local
parameters, child bindings and conditional runtime values remained outside the
bounded surface; Increment 12 implements child bindings while retaining the
other restrictions.

Increment 10 adds `<`, `<=`, `>`, `>=`, `hdlEq` and `hdlNe` on `HdlInt`, each
producing `HdlBool`. Both integer operands keep their exact `BigInt` default,
public/local identity provenance and expression AST; `HdlBool` carries that
provenance into `GenerateIf` and final module declaration checks. The named
equality methods avoid Scala equality semantics. Comparisons may consume public
and local integer expressions but not a `GenIndex` in this non-nested tranche.

Increment 11 adds one typed conditional integer operation:
`condition.select(whenTrue, whenFalse): HdlInt`. The concrete witness selects
the matching integer witness, while the symbolic value retains the exact
`BoolExpr`, both `IntExpr` branches, every Boolean/integer/local declaration
identity and the call-site origin. Neither value branch is evaluated away for
symbolic validation. Select expressions may compose like the other integer
operators, but both branches must be loop-invariant; `GenIndex`-dependent
conditions or values fail closed in this tranche.

Increment 12 adds a typed `parameterBinding(name, value: HdlBool)` overload and
a dedicated `booleanParameterBindings` input on `emitInstance`. The binding
retains the Boolean expression plus all parent Boolean, public-integer and
local-integer identities. Module-boundary validation rejects a binding whose
dependencies are not declared by that parent, while ParamRTL separately
resolves its name only against the target child's Boolean parameters. The
dedicated collection prevents the shared Verilog-2001 integer spelling from
weakening the frontend kind distinction. At Increment 12, Boolean local
parameters and generate-index-dependent bindings remained unsupported;
Increment 13 implements the Boolean-local item below.

Increment 13 adds `localParam(name, value: HdlBool)` and the corresponding
`booleanLocalParameter(handle)` declaration node. The returned `HdlBool`
retains a distinct local identity and lowers references to
`BoolExpr.LocalParameterRef`; it is never represented as a public Boolean
parameter or as a Scala default. Integer and Boolean local declarations are
checked at one module boundary and participate in one combined dependency
graph, allowing cross-kind chains through comparisons and `HdlBool.select`.
Mixed-kind name collisions, missing or foreign tokens, same-named distinct
tokens and mixed cycles fail closed at retained source origins.

As with integer locals, every re-entrant symbolic factory evaluation must
create fresh Boolean-local handles. Capturing a handle outside the factory or
reusing it for another module definition is invalid even if the later module
declares an identical name and expression. Boolean locals remain loop
invariant in this tranche; generate-index-dependent local values are rejected.

A zero concrete divisor fails immediately instead of leaking a Scala arithmetic
exception. A nonzero default does not prove the divisor safe: ParamRTL must
prove zero absent from its complete legal interval. Generate-index-dependent
local definitions, widths and child parameter bindings remain unsupported and
fail before raw ParamRTL construction.

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

Scala `==`, `!=`, hashing and reverse conversion on a statically typed
`HdlInt`, `HdlBool` or `GenIndex` fail closed. `HdlInt.hdlEq` and
`HdlInt.hdlNe` are the only supported symbolic equality operations. The values
themselves reject Scala equality at runtime; the inherited IDSL compiler plugin rejects
reverse `==`, `!=`, `equals`, `eq` and `ne` calls before elaboration. In
particular, both `lane == 0` and `BigInt(0) == lane`, or `true == enable`, are
rejected instead of silently specializing Scala control flow. Upcasting a
symbolic value to `Any` is outside the supported frontend surface.

Each error must report the parameter/expression, source location, unsupported
consumer and a suggested static or parameter-aware replacement.
