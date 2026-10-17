# LWR-27: derived configuration fields

An immutable configuration may compute integer fields from its `Param[Int]`
constructor fields. Each consuming component still owns its public formals.
Rebuilding the configuration with those formals recomputes every derived value;
a literal actual supplies the default, not a frozen derived width.

```scala
object RecordLayout {
  def width(data: ElabInt, lanes: ElabInt, generation: ElabInt): ElabInt =
    data + 7 + generation + lanes + 16 + 1
}
case class Video(depth: Param[Int], data: Param[Int], lanes: Param[Int],
                 generation: Param[Int]) {
  require(depth >= 1 && depth <= 4)
  require(data >= 1 && data <= 32)
  require(lanes >= 1 && lanes <= 4)
  require(generation >= 1 && generation <= 8)
  val capacity = depth.pow2 + 1
  val countBits = depth + 2
  val recordBits = RecordLayout.width(data, lanes, generation)
}
case class Config(video: Video) {
  val total = video.recordBits * 2 + video.countBits
}
class Channel(c: Config) extends Component {
  val source = in Bits(c.video.recordBits bits)
  val result = out Bits(c.video.recordBits bits)
  result := ~source
}
```

The port width remains an expression of `C_VIDEO_DATA`,
`C_VIDEO_GENERATION` and `C_VIDEO_LANES`. `capacity`, `countBits`, `recordBits`
and `total` are not new public parameters. Port declarations use inline expressions. Named/reused body values use the
existing localparam publisher with author-path hints such as
`DERIVED_C_VIDEO_CAPACITY` and `DERIVED_C_TOTAL`; deterministic suffixes separate
internal aliases and native scope allocation reserves existing formal/signal
names. It introduces no separate naming namespace or collision policy. Normalized public
formal-name collisions remain errors, including explicit name overrides.

Configuration requirements may supply integer domain bounds as well as
cross-field legality conditions. The frontend copies authenticated constraints
onto each consumer's owned roots and retains runtime/publication checks. Messages
must be literals. Unbounded domains, false requirements and widths that are valid
only at the default remain errors. A requirement is not permission to bypass
structural full-domain width checks.

## Supported grammar and authentication

Supported derived computations are immutable integer fields, integer literals,
`+`, `-`, `*`, `/`, `%`, unary minus, `pow2`, `log2Up`, and `addressWidth`.
Nested field paths and dependent derived fields are supported. Dependencies are
expanded with cycle detection, so forward references cannot read Scala's
uninitialized field defaults. Integer requirements additionally support ordered
comparisons, equality/inequality, conjunction and disjunction.

Helpers must have a unique source-owned object method, one explicit argument
list, native integer argument/result types (`Int` or `ElabInt`, or an inferred
result), and a body in this arithmetic grammar. Blocks may introduce immutable
local arithmetic values. The compiler validates and inlines the body; it does
not execute the helper object or discover expressions by invoking getters.
Imports, aliases, lexical shadows and root-qualified ownership are checked.
Helper objects with effectful initialization or user inheritance are rejected.

Arbitrary getter calls, mutable/lazy members, effectful blocks, recursive or
overloaded helpers, compiled-only helper bodies, foreign integer types and
unsupported computations fail explicitly. This is not arbitrary Scala function
execution. Existing configurations without `Param` constructor fields are not
promoted into symbolic configurations. Constructor fields of ordinary Scala types
and literal constant body fields remain specialization values; literal body
fields keep their Scala type and per-operation `Int` overflow semantics. Derived arithmetic uses the existing authenticated
`ElabInt` arithmetic/domain contract rather than Scala witness evaluation or
wrapping host `Int` arithmetic. Packed width positivity, representability and
full-domain bounds are checked by the existing native consumers.

Shared configuration objects are rebuilt independently for each declaration
path. Parent-to-child and recursive forwarding retain original root identities
and explicit bindings; derived values are recomputed from each child's formals.
The existing recursive specializations and termination checks remain unchanged.
Derived accesses also receive authenticated component-local typed aliases so
ordinary loop bounds such as `0 until c.capacity` retain their symbolic geometry.

## Focused validation

[The companion JSON receipt](derived-configuration-fields.json) records the tested source overlay, exact commands,
plugin hashes, test reports and HDL tool inputs/logs. Runtime build banners may
retain an earlier build-base SHA; the recorded source overlay and plugin hashes
identify the tested compiler, rather than the banner alone. Fresh checks cover both
supported Scala versions, independent shared paths, parent/child and recursive
bindings, non-default overrides, cross-field and invalid-domain rejection,
deterministic emission, helper effects/cycles/shadows, literal specialization,
Verilog-2001 compilation, simulation, synthesis and lint.

Lint/synthesis use `SYNTHESIS` where needed to exclude simulation-only domain
`$fatal` guards; simulation retains those guards and checks invalid overrides.
No remote CI, full qualification or new formal campaign is claimed. Application
Scala and delivery RTL remain unchanged. Historical LWR-26 evidence is not reused
as a passing result for this increment.

Fresh results on 17 October 2026: 167 frontend tests and 14 RTL/runtime tests
passed on each of Scala 2.12.18 and 2.13.12 (362 total, no skipped/pending cases).
The runtime suites recorded 108 HDL tool invocations: 102 successful commands
and six expected `$fatal` exits checking invalid parameter overrides. Earlier
exploratory failures remain separate from the final `frontend12`/`runtime12`
receipts. Artifacts, commands and hashes are retained under
`/home/kartik/.local/state/morphhdl/lightweight-scala-rtl/lwr27-20261017`.
