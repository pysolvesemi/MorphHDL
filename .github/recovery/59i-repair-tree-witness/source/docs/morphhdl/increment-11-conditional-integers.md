# Increment 11: conditional integer values

Increment 11 adds one bounded compile-time selection operation without adding
another structural control-flow form:

```scala
condition.select(whenTrue, whenFalse): HdlInt
```

The operation chooses an integer constant expression. It is distinct from
`generateIf`, which chooses module items and hierarchy.

## Implemented surface

The frontend retains the `HdlBool` condition, both `HdlInt` branches, their
exact default witnesses, declaration identities and source origin. The witness
uses the condition's default, but symbolic capture never discards the inactive
branch. Conditional expressions may compose recursively, but conditions and
branches that depend on a `GenIndex` remain rejected by this bounded tranche.

ParamRTL represents the operation as
`IntExpr.Select(condition, whenTrue, whenFalse)`. Default evaluation first
checks the condition and both value expressions, then returns the exact value
selected in the current Boolean/public-integer/local-integer fact context.
MorphVerilog carries that context through reachable instances: parent integer
bindings are evaluated before child locals are recomputed, and each module's
Boolean defaults are used for its local expressions, port widths, child
bindings and generate counts.

Whole-domain analysis is deliberately conservative. It analyzes both value
branches independently and uses their unconditional interval hull. A true
default cannot hide an unsafe divisor, unresolved reference, non-positive width
or out-of-profile value in the false branch. Increment 11 does not infer guard
correlations or use the condition to narrow either branch's domain.

The strict Verilog-2001 backend emits a ternary expression with an explicitly
parenthesized Boolean condition. Normal expression precedence preserves the
canonical tree, including conditional values nested beneath arithmetic in the
surrounding expression. Both branches must pass signed 32-bit target capability
checks before emission.

## Public contract fixture

`conditional_width.v` is the seventh artifact generated through
`MorphContractFixtureGenerator` and `MorphVerilog`. `ConditionalWidth` declares
Boolean `WIDE`, bounded `NARROW_WIDTH` and bounded `WIDE_WIDTH` public
parameters, then derives:

```verilog
localparam integer ACTIVE_WIDTH = (WIDE == 1) ? WIDE_WIDTH : NARROW_WIDTH;
```

Both packed pass-through ports use `ACTIVE_WIDTH`. The default selects 12 bits;
`WIDE=0` selects the four-bit narrow default. CI also checks a 15-bit custom
wide configuration and a seven-bit custom narrow configuration. Normal and
reverse-construction runs must produce an exact, byte-identical seven-file
inventory. Verilator lints every configuration, Icarus simulates all four in
strict Verilog-2001 mode, and Yosys proves each elaborated packed-port width.

Morph orchestration tests additionally pin true and false default selection,
selection through a recomputed local in a recursively integer-bound child, and
whole-design rejection of an invalid inactive branch.

## Deferred by design

This increment does not add Boolean child parameter bindings, Boolean local
parameters, guard-sensitive interval refinement, multiple or nested structural
predicates, conditional/loop nesting, `min`, `max`, `clog2` or `GenerateCase`.
Runtime combinational and sequential processes and memories also remain
deferred.

## Recommended next increment

Increment 12 should add named Boolean child-parameter forwarding. ParamRTL
should use a typed Boolean binding node, validate its expression in the parent
scope and reject integer/Boolean kind mismatches. The frontend should preserve
both declaration identities, and strict Verilog-2001 legalization should emit a
named integer-encoded Boolean association without changing the canonical type.
`MorphVerilog` must replace the child's declared Boolean default with the exact
instance-bound Boolean fact before recomputing child locals, widths and selected
hierarchy.

A new eighth fixture such as `boolean_forwarding.v` should keep a fixed
eight-bit parent/child interface while a parent Boolean is forwarded to a child
whose generate-if selects distinct high/low leaf types. CI should prove both
parent overrides and exact selected child hierarchy without regenerating RTL.
Boolean locals and guard-sensitive interval refinement should remain separate
later tranches.
