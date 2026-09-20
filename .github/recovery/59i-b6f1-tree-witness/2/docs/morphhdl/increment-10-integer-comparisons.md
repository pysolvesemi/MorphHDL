# Increment 10: integer comparisons

Increment 10 closes the first structural consumer of the existing integer
expression algebra. Exact `HdlInt` comparisons now produce typed `HdlBool`
conditions without converting either operand to a Scala value.

## Implemented surface

The frontend implements `<`, `<=`, `>`, `>=`, `hdlEq` and `hdlNe` on `HdlInt`.
The named equality operations are deliberate: ordinary Scala `==`, `!=`,
`equals`, hashing and symbolic-to-Scala conversions remain fail-closed. Each
comparison preserves its operands' public-parameter identities, local-parameter
identities, exact default witness and call-site origin. A comparison that
depends on a `GenIndex` is rejected because conditional/loop nesting remains
outside this bounded tranche.

ParamRTL represents the six operations as distinct `BoolExpr` nodes. Their
semantics are mathematical arbitrary-precision integer comparison. Default
evaluation analyzes both operands eagerly in the current public/local facts,
so unsafe division, modulo or unresolved references cannot be hidden by a
default Boolean result. Whole-domain validation and the Verilog-2001 capability
pass still prove every integer operand subtree, including signed 32-bit target
representability.

The backend emits `<`, `<=`, `>`, `>=`, `==` and `!=` with deterministic
parenthesization. This is target legalization only; the canonical IR does not
inherit Verilog sizing or signedness rules.

## Public contract fixture

`comparison_routing.v` is the sixth artifact generated through
`MorphContractFixtureGenerator` and `MorphVerilog`. `ComparisonRouting` exposes
bounded integer `SELECT` and `THRESHOLD` parameters. Its condition
`SELECT >= THRESHOLD` chooses a `HighRoute` child in `g_high` or a `LowRoute`
child in `g_low`, while keeping a fixed eight-bit public interface.

The default `SELECT=8`, `THRESHOLD=5` selects `HighRoute`; setting `SELECT=3`
selects `LowRoute` without rerunning MorphHDL. The equality boundary selects
`HighRoute`. CI requires normal and reverse-construction generation to be
byte-identical, compares the exact six-file artifact to reviewed goldens,
simulates all three cases under Icarus, lints default and overrides under
Verilator, and asks Yosys to prove exactly one child of the expected type and
binding in each elaborated hierarchy.

`MorphVerilog` default-shape agreement now evaluates comparison conditions
against each reachable instance's integer and local-parameter facts. Unit tests
pin both true and false default selections. Symbolic validation still checks
the inactive branch before shape agreement.

## Deferred by design

This increment does not add conditional integer values, multiple or nested
conditional regions, conditional/loop nesting, `GenerateCase`, `min`, `max`,
`clog2`, Boolean child bindings or Boolean locals. Runtime combinational and
sequential processes and memories also remain deferred.

## Recommended next increment

Increment 11 should add one bounded conditional integer-expression node:
`HdlBool.select(whenTrue, whenFalse): HdlInt`. It should preserve provenance
from all three operands, analyze both value branches over the complete legal
domain, legalize to a deterministic Verilog-2001 ternary expression and add a
fixture where the selected integer expression controls a derived local
parameter or packed width. Structural nesting and Boolean forwarding should
remain separate later tranches.
