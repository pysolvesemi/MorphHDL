# Increment 51 — symbolic native-`Int` branch capture

Increment 51 joins the explicit symbolic-conditional path from Increment 48
with the exact shadow-native `Int` provenance and predicates from Increments 49
and 50. Ordinary Scala `if`, `else if`, and `else` syntax is transformed only
when its Boolean condition carries one compiler-proven native predicate
reference.

## Dual graph contract

The source still evaluates an ordinary Scala `Boolean` witness. Normal
SpinalHDL elaboration therefore keeps only the witness-selected alternative in
its concrete graph. During MorphVerilog structural capture, the same predicate
reference is resolved in the active Increment 47 formalization boundary and
all source alternatives are captured as MorphHDL-owned structural regions.

A native child component uses two independently retained predicate scopes:

- the canonical child-definition predicate rooted at the formal name, such as
  `WIDTH > 16`; and
- the parent-instance predicate rooted at the actual expression, such as
  `LEFT_WIDTH > 16`.

`formalComponent` supplies a provisional definition root before the untouched
native constructor runs. Final component attachment proves that root is exactly
identical to the canonical formal binding. Provenance is never inferred from a
concrete integer, Boolean value, generated signal, component name, or emitted
Verilog text.

## Source forms

The compiler accepts:

```scala
val root = NativeIntShadow.captureArgument(width, "root")
val medium = root > 8

if (root > 16) widePath()
else if (medium) mediumPath()
else narrowPath()
```

Consecutive proven predicates are retained as one source-ordered structural
chain. A final ordinary Scala conditional may remain inside the concrete else
body. Direct comparisons, immutable predicate aliases, and the Increment 50
`isPow2` predicate are supported.

An ordinary Boolean conditional with no shadow provenance remains untouched,
even in a source file that also contains selected native integers.

## Safety boundary

Increment 51 deliberately accepts one top-level native symbolic conditional or
one source-level `else if` chain. Nested native symbolic conditionals remain
fail closed with
`MORPH-FRONTEND-NATIVE-INT-SYMBOLIC-CONDITIONAL-NESTED-DEFERRED`; Increment 52
adds the complete nested side-effect contract.

The implementation also rejects:

- missing or foreign predicate references;
- Boolean witness/default disagreement;
- definition-root mismatch at final component attachment;
- compound or otherwise unsupported Boolean predicates that contain retained
  native provenance; and
- `return` or `throw` inside a captured source alternative.

The compiler does not transform unrelated Scala control flow and does not add
an implicit native `Boolean`/`HdlBool` conversion.

## Lowering

`NativeIntSymbolicConditional` resolves the canonical definition predicate and
reuses `NativeStructuralFrontend` and `ParameterizedStructure`. The existing
generic structural lowering therefore emits legal Verilog-2001 generate
regions without a component-specific RTL implementation or emitted-name
rewrite.

The witness-selected path remains authoritative for ordinary concrete
`SpinalVerilog`; no parameterized generate region is recorded when structural
capture is disabled.

## Proof matrix

`NativeIntSymbolicConditionalTests` proves:

| Contract | Evidence |
|---|---|
| simple and chained conditions | direct and aliased native predicates emit one source-ordered `if / else if / else` chain |
| canonical hierarchy | equal witnesses with distinct parent actuals reuse one child definition and named formal bindings |
| predicate scope | child structural conditions use `WIDTH`, while retained instance predicates use `LEFT_WIDTH` and `RIGHT_WIDTH` |
| power-of-two | `isPow2` retains and lowers both alternatives |
| ordinary Scala | an unrelated Boolean conditional remains concrete |
| concrete parity | ordinary `SpinalVerilog` elaborates only the witness-selected branch |
| deferred nesting | nested native symbolic control flow fails with the Increment 52 boundary diagnostic |
| unsupported predicates | compound retained Boolean expressions fail closed instead of collapsing |
| determinism | repeated elaboration produces identical Verilog |
| compatibility | Increment 48 explicit conditionals and Increments 49–50 provenance/expression suites remain green |

The permanent `MorphHDL native Int symbolic conditionals` workflow executes the
new contract and inherited conditional, provenance, formalization, hierarchy,
and concrete-baseline suites on Scala 2.12.18 and Scala 2.13.12. The repository
baseline continues to provide strict Verilog-2001, deterministic generation,
and inherited semantic validation.
