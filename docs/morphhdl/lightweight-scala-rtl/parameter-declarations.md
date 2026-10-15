# Inferred parameter declarations (development)

This extends the existing ElabInt/ElabBool proof machinery. It does not introduce
an implicit conversion to Scala Int or Boolean. The compiler plugin resolves an
explicit `Param[T]` declaration before ordinary symbolic control and recursion.

```scala
import spinal.core._
val COUNT: Param[Int] = Param(8)
val ENABLED: Param[Boolean] = Param(true)
require(COUNT >= 1 && COUNT <= 17)
val nextCount = (COUNT + 1) / 2
```

COUNT and ENABLED are module parameters; nextCount is a derived expression.
Names come from the declaration, regardless of capitalization. `Param(8,
"TERM_COUNT")` on a typed declaration overrides the HDL name. Integer and Boolean
parameters retain the legacy HDL integer representation (Boolean domain 0..1).
A constructor argument annotated `Param[Int]` is a typed actual; a component's
`val COUNT: Param[Int] = Param(actual)` declares its own formal.

The current inference recognizes unconditional component-body `require`
conjunctions with constant integer bounds, including constant arithmetic and immutable integer constructor specialization limits. Constructor limits are checked for a finite domain at elaboration. It
never uses the default to infer a bound. Unsupported or unbounded integer domains
fail with `MORPHDL-PARAM-DOMAIN-UNPROVEN`; users must express genuine legal limits.
Conditional requirements and shadowed user methods named require do not grant
domain authority. Existing dependent symbolic requirements remain checked by the
native requirement machinery; they are not a substitute for missing finite bounds.

Packed parameters preserve representation width separately from semantic bounds:

```scala
val COUNT: Param[UInt] = Param(U(8, 19 bits))
require(COUNT >= 1 && COUNT <= 17)
val FLAGS: Param[Bits] = Param(B(5, 8 bits))
val OFFSET: Param[SInt] = Param(S(-3, 8 bits))
require(OFFSET >= -8 && OFFSET <= 7)
```

The intended declarations are `parameter [18:0] COUNT = 19'd8`,
`parameter [7:0] FLAGS = 8'd5`, and
`parameter signed [7:0] OFFSET = -8'sd3`. A representation contributes its numeric
limits; explicit requirements may narrow them. The exhaustive domain limit is
65,536 values. A wide representation does not make an unbounded geometry legal.
Packed literals must have an explicit width and known bits. Hardware inputs,
registers, poison values and inferred-width literals cannot become parameter
actuals. Representation participates in definition identity, so incompatible
packed declarations cannot share a module solely because their defaults match.

Arithmetic on parameters is checked integer arithmetic, not silently wrapping
packed arithmetic. Materialization into hardware (`U(value, width bits)`,
`B(value, width bits)`, `S(value, width bits)`) checks the complete value domain.
Unsigned, Bits and signed materialization use the authenticated value registry.
Signed values retain signed literals and sign extension; unsigned values zero
extend. Packed representation widths from 1 through 4096 bits are accepted,
with semantic values limited to the existing checked Scala Int domain.
Unsupported compositions fail explicitly, rather than truncate a representative.

Ordinary imported user types named Param retain their Scala meaning. In source
also using TileLink's existing protocol object Param, disambiguate the imports
(e.g. `import spinal.core.{Param => _, _}` for protocol-only source). This is the
usual Scala wildcard-name collision, not a protocol API rename.

## Readable mask selection

`((mask >> level) & 1) != 0` uses authenticated division/power/remainder arithmetic.
The shift distance must be proven in 0..30, the value nonnegative, and the mask a
contiguous nonnegative low-bit mask. Noncontiguous masks are explicitly rejected.
No Scala shift-distance masking or witness arithmetic is used. Correlated
logarithm differences are not automatically proven nonnegative by the current
product enclosure; the reduction library selects at its proven concrete bridge
levels instead. This limitation remains separate from simple bounded shifts.

## Evidence

Implementation and tests are in progress. `InferredParameterSyntaxTests`,
`InferredParameterTests`, `ParameterMaskTests` and `PackedParameterTests` are the
focused probes. Do not treat this document as a passing result for a new source
snapshot; completion receipts must identify the tested source and actual outcomes.

## Optional declarations and reduction policy

An optional actual has explicit presence. In ordinary source:

```scala
class Policy(actual: Option[Param[Int]]) extends Component {
  val PIPELINE_MASK: Param[Int] = Param.optional(actual, 0)
  require(PIPELINE_MASK >= 0 && PIPELINE_MASK <= 7)
  // Use PIPELINE_MASK in the component's hardware or structural branches.
}
```

`None` creates an authenticated constant and omits the formal. `Some(Param(0))`
creates a formal whose default is zero and whose non-default overrides remain
live. The absent value must belong to the authored legal domain. A null Option
or Some(null) is invalid. Legacy nullable ElabInt APIs remain compatibility
entry points; new call sites should use Option.

For a native Vec with retained count and scalar width, no separate capacity or
width factory is needed at the call site:

```scala
val result = spinal.lib.BalancedTree.reduce(terms, "UnsignedSum",
  pipelineMask = Some(Param(0)))((a: UInt, b: UInt) => a + b)
```

The capacity comes from the input's authenticated count domain. The result keeps
the input element width; addition wraps at that width. Widen terms before the
call if the sum must not overflow. Operation-specific module names remain
explicit. The optional `levelBridge` receives a concrete Int level and is applied
to odd tails as well as paired results. An explicitly supplied identity callback
still requests a bridge policy; its presence is not guessed from object identity
or a sampled callback result.

`BalancedTree.define(HardType(...), capacity, name)` retains symbolic UInt, SInt
and Bits widths automatically. `defineWidth` remains for compatibility and
explicit factories. Composite payloads retain their existing fixed geometry
contract; this is not arbitrary symbolic Bundle reconstruction.
