# Increment 50 — shadow native `Int` expressions and predicates

Increment 50 extends the exact-source native-`Int` provenance established by
Increment 49. An explicitly selected ordinary Scala `Int` keeps its unchanged
runtime witness while a MorphHDL compiler phase propagates a parallel,
bounded symbolic expression through the supported native operations.

The implementation remains external to upstream-owned SpinalHDL sources. It
does not add an `HdlInt`-to-`Int` conversion, replace native constructor APIs,
or recover provenance by comparing concrete integer values.

## Provenance model

The compiler phase starts only from an explicit Increment 49 selection:

```scala
val root = NativeIntShadow.captureArgument(width, "root")
val selected = shadowInt(root, "selected")
```

Each selected value receives a deterministic source reference derived from its
source file and position. A derived local contains:

- the ordinary Scala `Int` witness;
- a relative symbolic expression rooted at the active formalization boundary;
- a deterministic exact-source reference; and
- its source location and selected local name.

The relative expression is lowered independently against the canonical child
formal and the parent instance actual. For example, `root + 2` retains
`(WIDTH + 2)` in the child definition and `(TOP_WIDTH + 2)` for the instance
actual while its ordinary Scala value remains `10` when the default is `8`.
Definition-side interval bounds therefore follow the complete child-formal
domain, while actual-side bounds follow the complete parent-actual domain. The
proofs check both domains independently rather than applying the tighter parent
bounds to the canonical child definition.

No registry is keyed by `Int`, `BigInt`, emitted RTL names, or inferred signal
names. Equal witnesses with different source references remain distinct.

## Supported expressions

The bounded operation set is:

- addition, subtraction and multiplication;
- division and remainder;
- unary negation;
- minimum and maximum;
- address width, ceiling log2 / `log2Up`, and floor `log2Down`; and
- direct local aliases and explicitly selected aliases.

Every operation computes an exact concrete witness and a conservative complete
symbolic interval. The default symbolic result must agree with the native
witness, and the full interval must remain inside the Scala `Int` domain.
Division and remainder additionally require a divisor domain that excludes
zero. Address/log2 helpers require a strictly positive complete input domain.

## Supported predicates

The compiler retains ordinary Boolean witnesses plus symbolic predicates for:

- `<`, `<=`, `>`, `>=`, `==`, and `!=`; and
- the power-of-two predicate `isPow2`.

These predicates remain metadata in Increment 50. Ordinary Scala control flow
still follows the concrete witness. Increment 51 will consume the retained
predicate reference to capture every source branch.

## Fail-closed boundary

The compiler and runtime reject:

- arithmetic with an unproven nonliteral operand;
- complete-domain overflow;
- division or remainder whose divisor domain admits zero;
- nonpositive address/log2 helper domains;
- unsupported native integer calls;
- boxing or generic-container escape;
- mutable-variable or assignment escape;
- stale, foreign, missing, or conflicting source references; and
- concrete/symbolic default disagreement.

A mutable declaration is rejected against an already retained source
reference before its by-name native right-hand side is evaluated. This prevents
a rejected declaration from manufacturing an alias/result reference that was
never retained and preserves the stable mutable-escape diagnostic.

Outside an active formalization boundary, compiler-inserted hooks preserve the
ordinary native operation result. Concrete `SpinalVerilog` therefore remains
unchanged.

## Compiler boundary

`MorphHdlNativeIntShadowExpressionComponent` runs after the Scala parser and
before the Increment 48 symbolic-conditional phase. It instruments only source
units containing an explicit native shadow marker. Unrelated Scala `Int` code
is left untouched. MorphHDL implementation sources in `frontend` and
`morphplugin` are excluded with path normalization that works for both absolute
and repository-relative compiler source paths. String emptiness predicates use
explicit JVM `length` checks so the parser plugin compiles without ambiguous
name conversions on both Scala 2.12 and Scala 2.13. The MorphHDL plugin
entrypoint registers the expression phase before the natural
symbolic-conditional phase on both supported Scala versions.

The parser-phase transformation is intentionally bounded. It recognizes only
the reviewed operation set and safe direct aliases. It does not reinterpret
arbitrary method calls or infer provenance from type-erased values.

## Proof matrix

`ExternalNativeIntShadowExpressionTests` proves:

| Contract | Evidence |
|---|---|
| arithmetic | every supported operator retains witness, child formal expression, parent actual and independently bounded complete intervals |
| helpers | address/log2 helpers retain monotonic bounded expressions and positive-domain validation |
| predicates | all comparisons and `isPow2` retain witness plus definition/actual predicates |
| aliases | exact source references propagate through direct and explicitly selected locals |
| identity | equal concrete values are never used to discover an operand |
| safety | overflow, zero-divisor domains and invalid helper domains fail before publication |
| escape handling | unsupported calls, boxing, mutable state and unproven operands fail with stable codes |
| parity | ordinary concrete SpinalHDL generation and native runtime values remain unchanged |
| determinism | repeated elaboration produces identical expression and predicate signatures, including both interval domains |
| compatibility | Increment 49 formalization, hierarchy, replay and weak-identity suites remain green |

The permanent `MorphHDL native Int shadow expressions` workflow runs the new
proofs and inherited Increment 49/formalization contracts on Scala 2.12.18 and
Scala 2.13.12. The repository baseline workflow continues to provide strict
Verilog-2001, inherited semantic, deterministic generation and full backend
coverage.
