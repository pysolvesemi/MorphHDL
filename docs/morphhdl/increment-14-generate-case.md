# Increment 14: bounded generate-case routing

Increment 14 adds the first integer-selected `GenerateCase` vertical slice.
The guarded frontend syntax requires explicit stable labels and a mandatory
default:

```scala
generateCase(selector)
  .choice(BigInt(0), "g_zero") {
    emitInstance("selected_inst", "ZeroRoute", portConnections = connections)
  }
  .choice(BigInt(1), "g_one") {
    emitInstance("selected_inst", "OneRoute", portConnections = connections)
  }
  .default("g_default") {
    emitInstance("selected_inst", "DefaultRoute", portConnections = connections)
  }
```

`choice` returns the same lexical builder. `default` finalizes the region and
must be called exactly once. A missing, duplicate, escaped, cross-thread or
foreign-builder completion fails with a source-located frontend diagnostic.
Choice values and labels must be unique. ParamRTL accepts source-order choices
but validation and emission use ascending numeric choice order, making output
independent of construction order.

## Canonical IR and validation

ParamRTL represents each explicit alternative as
`GenerateCaseChoice(value: BigInt, block: GenerateBlock)` and the region as
`ModuleItem.GenerateCase(selector, choices, default)`. The selector is a typed
`IntExpr` evaluated over the complete public/local integer and public/local
Boolean constant context. Choice literals retain arbitrary-precision
mathematical integer intent until target capability checking proves they fit
strict Verilog-2001 `integer` semantics.

Validation eagerly checks every explicit choice and the mandatory default,
including branches unreachable under the declaration defaults. Unresolved
modules, invalid bindings, unsafe expressions and incomplete or conflicting
drivers cannot hide in an inactive branch. Each mutually exclusive path must
provide the same complete output-driver coverage as a standalone legal module.
Identical instance names are permitted in distinct labeled exclusive blocks.

This bounded tranche permits one conditional structural region total in a
module: either one `GenerateIf` or one `GenerateCase`. A second sibling
conditional is rejected. Nesting `GenerateIf`, `GenerateCase` or `GenerateFor`
inside any conditional region, or placing a conditional inside another
generate region, remains rejected in every direction.

## Default-shape agreement

`MorphVerilog` evaluates the selector from the exact reachable module-instance
context. Public integer and Boolean child bindings are applied first, then the
combined dependency-first integer/Boolean local graph is recomputed. An exact
choice-literal match selects that block; otherwise the mandatory default is
selected. Only the selected default branch contributes to concrete-witness
shape comparison, while ParamRTL has already validated every branch. Sibling
instances retain independent selector and local facts.

## Strict Verilog-2001 and public fixture

The backend emits one named `generate`/`case` region, ascending explicit
signed-decimal literals and one final `default` block. `case_routing.v` is the
tenth artifact generated through `MorphContractFixtureGenerator` and
`MorphVerilog`. Its fixed eight-bit `CaseRouting` interface derives
`SELECTOR = MODE + OFFSET`; choices `0` and `1` select distinct `CaseZeroRoute`
and `CaseOneRoute` schemas, while every other value selects the mandatory
`CaseDefaultRoute` schema.

CI requires exact normal/reverse ten-file inventories and byte identity with
the reviewed goldens. Verilator checks the default zero choice, explicit one
choice, offset-derived one choice and unmatched default. Icarus simulates the
same four configurations in strict `-g2001` mode. Yosys proves the exact
selected child type, labeled hierarchy, direct eight-bit bindings and fixed top
ports in each configuration.

## Deferred by design

This increment does not add multiple or nested conditional regions,
generate-if/for/case nesting, runtime combinational or sequential processes,
memories or guard-sensitive interval refinement. Generate-case cannot alter
public port presence, direction or protocol shape.

## Recommended next increment

Increment 15 should implement the first bounded runtime combinational process:
target-neutral assignment semantics, complete driver/latch proof, `always @*`
Verilog-2001 legalization, concrete differential checks and one public fixture.
Sequential processes, memories and nested structural control flow should
remain separate later increments.
