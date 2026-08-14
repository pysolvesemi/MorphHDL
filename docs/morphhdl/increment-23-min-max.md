# Increment 23: bounded parameterized minimum and maximum

Increment 23 extends the target-neutral parameter-expression algebra with
typed mathematical minimum and maximum operations. It does not add generated
comments, attributes or raw HDL.

## Frontend contract

`HdlInt` now exposes two binary operations:

```scala
val padding = dataWidth.min(HdlInt.literal(3))
val safeWidth = candidateWidth.max(HdlInt.literal(4))
```

Both return another `HdlInt`. The concrete witness is the exact mathematical
minimum or maximum of the two `BigInt` witnesses. The symbolic result retains
both complete operand trees, public/local declaration identities, source
origins and lexical-scope provenance in `IntExpr.Min` or `IntExpr.Max`.

Neither operation short-circuits symbolic capture. A literal or interval that
determines the result does not hide an unresolved, foreign, scoped or
target-unsafe expression in the other operand. Generate-index-dependent
operands follow the existing binary-expression scope rules: same-scope values
retain their generate scope, while incompatible scopes fail at capture. Each
consumer still enforces whether scoped expressions are legal in its context.

## ParamRTL semantics

For independently bounded operand intervals `L = [Lmin, Lmax]` and
`R = [Rmin, Rmax]`, whole-domain analysis computes:

- `Min(L, R) = [min(Lmin, Rmin), min(Lmax, Rmax)]`;
- `Max(L, R) = [max(Lmin, Rmin), max(Lmax, Rmax)]`.

Exact evaluation, parameter substitution, dependency discovery and expression
equivalence recurse through both operands. Local-parameter dependency ordering
therefore remains deterministic, and width/depth positivity is proved over all
legal overrides rather than only the default.

Normalization folds two literal operands and identical operands after the
complete source expressions have been captured and checked. Equivalence also
recognizes the commutative swapped-operand form. It does not apply broader
constraint-based dominance rewrites in this tranche.

## Strict Verilog-2001 lowering

Dynamic operations lower without a SystemVerilog helper or target-specific
IR node:

```verilog
(left < right) ? left : right
(left > right) ? left : right
```

The first form is `Min`; the second is `Max`. The comparator and branch order
are canonical even though either equal operand has the same mathematical
value. Parentheses preserve the exact expression tree under Verilog-2001
precedence.

Ternary lowering repeats both operands. The capability verifier and emitter
therefore share a conservative expansion estimate and reject an expression
whose rendered Min/Max expansion would exceed 4096 syntax nodes. This target
cap does not alter the canonical ParamRTL expression or specialize it using a
default parameter value. Rejection uses the stable capability diagnostic
`V2001-MIN-MAX-EXPANSION-TOO-LARGE`.

## Public fixture and executable gates

The existing `derived_width.v` artifact now uses both operations:

```scala
val clampedPadding =
  localParam("CLAMPED_PADDING", dataWidth.min(HdlInt.literal(3)))
val paddedWidth = localParam(
  "PADDED_WIDTH",
  (totalWidth + clampedPadding).max(HdlInt.literal(4))
)
```

At Increment 23, normal/reverse fixture generation and the exact public
inventory remained at sixteen files. The same emitted `DerivedWidth`
definition was instantiated at widths 35, 4, 18, 27, 23 and 6. These
configurations exercise the left and
right choices of `Min`, the lower floor from `Max`, default parameters, mixed
overrides and a dynamic two-by-two case without regenerating RTL.

The release gate additionally requires:

- frontend, ParamRTL analysis/validation and backend tests on Scala 2.12 and
  2.13;
- exact canonical Min/Max locals in the reviewed generated artifact;
- Verilator parsing and Icarus simulation for all six public shapes;
- Yosys synthesis with exact packed-port widths for every override;
- negative mutations for comparator reversal, branch reversal and
  default-specialized constants;
- byte-identical normal/reverse generation and the unchanged sixteen-file
  inventory.

No inherited concrete SpinalHDL validation phase is bypassed. The concrete
witness still runs the shared phase plan, while ParamRTL and the strict target
gate independently prove the symbolic expression domain.

## Recommended next increment

Increment 24 should add target-neutral `HdlInt.ceilLog2` and
`IntExpr.CeilLog2` with a positive-input contract and exact
`ceilLog2(1) = 0` semantics. It should reuse one module-local Verilog-2001
constant function and replace the address-width threshold chain with the same
helper called with a one-bit minimum. Native `$clog2` was added in Verilog-2005
and should remain forbidden by the 2001 baseline. General logarithm bases,
power/exponent expressions and generated comments should remain deferred.

Increment 24 implements this recommendation with the shared two-argument
`clog2` constant function, preserving the Verilog-2001 baseline
and the distinct zero and one minimum-result semantics. Its added lane-index
term updates the current fixture widths to 37, 4, 20, 29, 25 and 7.
