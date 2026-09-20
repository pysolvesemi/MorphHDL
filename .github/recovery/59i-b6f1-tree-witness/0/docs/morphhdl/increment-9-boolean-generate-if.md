# Increment 9: Boolean parameters and bounded generate-if

Increment 9 adds the first typed Boolean and conditional-structure vertical
slice. It carries one `HdlBool` predicate from the guarded Scala frontend,
through ParamRTL validation and `MorphVerilog`, into strict Verilog-2001. It
does not add parameter-dependent Scala control flow or general conditional RTL.

## Typed Boolean expressions

ParamRTL now has a distinct `BooleanParameter` declaration and `BoolExpr`
algebra for literals, public-parameter references, negation, conjunction and
disjunction. Boolean intent therefore remains target-neutral inside the IR.
The Verilog-2001 backend alone legalizes declarations to
`parameter integer NAME = 1` or `0` and renders references explicitly as
`NAME == 1`.

`HdlBool` pairs the concrete default witness with that symbolic expression and
the exact opaque identities of every referenced Boolean declaration. The
frontend implements `!`, `&&` and `||`, preserves operand correlation and
retains source provenance. A Scala `Boolean` may become an `HdlBool`; the
reverse conversion is unavailable. Forward and reverse equality, hashing, an
ordinary Scala `if` and escaped or same-named/different-token identities fail
closed rather than specializing the public design to the default witness.

Boolean and integer public parameters occupy distinct namespaces in the type
model but must still have unique emitted names. Cross-kind collisions,
undeclared identities, duplicate declarations and type mismatches are rejected
at the guarded module boundary.

## One exact conditional region

The supported frontend spelling is:

```scala
generateIf(enable, "g_enabled", "g_disabled") {
  // true branch
} otherwise {
  // false branch
}
```

Source-derived deterministic labels remain available for non-contract code.
The reviewed fixture names both blocks explicitly so unrelated source-line
changes cannot alter its ABI.

One module-item capture permits exactly one top-level `generateIf`, and every
conditional requires exactly one `otherwise`. Parameterized mode captures each
branch once; concrete mode executes only the branch selected by the default
witness. A builder completed twice, omitted, replayed outside its session,
moved to another thread or collector, or nested with `GenerateIf` or
`GenerateFor` reports a source-located frontend diagnostic. If either branch
throws, its partial items and names are discarded and the same capture can
recover safely.

ParamRTL represents both named blocks explicitly and validates both regardless
of the default or whether Boolean simplification could make one unreachable.
Unknown modules, recursive dependencies, invalid bindings and invalid drivers
in the default-unselected branch are therefore fatal. For every packed output,
the unconditional items plus the true path must provide exactly one driver,
and the unconditional items plus the false path must independently provide
exactly one driver. Opposite branches are mutually exclusive and are not
summed as simultaneous drivers.

Nested conditional/loop regions, multiple generate-if predicates in one
module, conditional ports, Boolean child-parameter bindings and `GenerateCase`
remain unsupported.

## Morph default-shape agreement

`MorphVerilog` first validates the complete symbolic design. Its binding-aware
default-instance fingerprint then evaluates each Boolean condition from typed
parameter defaults and traverses only the selected block. Modules reachable
only through the other block remain validated and emitted, but do not appear
as simultaneous children in the concrete default hierarchy.

The fifth public fixture, `conditional_forwarding.v`, exercises this rule. Its
`ConditionalForwarding` top exposes Boolean `ENABLE` and integer `WIDTH`, and
both named branches instantiate the same parameterized `ConditionalLeaf` under
the same lexical instance name. The default concrete witness contains exactly
one child, catching an implementation that incorrectly counts both blocks.

CI generates all five reviewed files twice through `MorphVerilog`, including a
reverse-construction run, and requires exact byte identity with the goldens.
The unmodified generated directory then passes strict Verilator, Icarus
default/false/awkward-width simulations and Yosys hierarchy, cell-count and
port-width checks without enabling SystemVerilog.

## Deliberately deferred

- Integer comparisons that produce `HdlBool` and conditional value selection.
- Boolean local parameters and Boolean child-parameter forwarding.
- Multiple or nested conditional regions and `GenerateCase`.
- `min`, `max`, `clog2`, processes, registers, memories and aggregates.
- A proof that arbitrary dual factories are behaviorally equivalent beyond
  the existing default-shape association contract.
