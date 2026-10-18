# Whole configuration parameters (LWR-37)

A native component can opt into symbolic leaves of an immutable configuration
without changing that configuration's ordinary Scala API:

```scala
case class Video(numBytes: Int = 8, enabled: Boolean = true)
case class Config(video: Video = Video(), timeout: Int = 1024) {
  val doubled: Int = video.numBytes * 2
}
class Example(c: Param[Config]) extends Component {
  require(c.video.numBytes >= 1 && c.video.numBytes <= 16)
  require(c.timeout >= 1 && c.timeout <= 65535)
  val input = in Bits(c.video.numBytes bits)
  val output = out Bits(c.doubled bits)
  val timeout = out UInt(16 bits)
  timeout := U(c.timeout, 16 bits)
  if (c.video.enabled) output := input.resize(c.doubled) else output := 0
}
val example = new Example(Config())
```

The leaves become `C_VIDEO_NUM_BYTES`, `C_VIDEO_ENABLED`, and `C_TIMEOUT`.
`doubled` remains a symbolic expression, not an independent parameter. Use an
explicit symbolic resize when converting between symbolic widths. Existing
parameter liveness rules still apply: unused leaves may be omitted from RTL.
Verilog parameter declaration ordering follows the existing deterministic emitter.
`@rtlParamName` overrides and normalized-name collision checks apply unchanged.

The compiler discovers source declarations, creates a separate symbolic schema,
and converts ordinary actuals by reading authenticated public immutable fields.
It does not inspect objects with reflection or infer legal domains from defaults.
An unannotated `c: Config` retains its ordinary semantics. Configurations already
containing explicit `Param[Int]` or `Param[Boolean]` fields retain their existing
wrapper semantics; whole-object promotion adds only their ordinary eligible
leaves. Repeated fields remain independent even when passed the same object.

Within a component, forward `c` directly to another `Param[Config]` constructor.
For changed actuals, use the symbolic configuration's case-class copy:

```scala
val child = new RecursiveComponent(c.copy(count = c.count - 1))
```

Existing guarded recursion and strict-decrease checks still apply. Reconstructing
an ordinary `Config` with symbolic values is not supported: its constructor still
accepts host `Int`/`Boolean` values. A plain public-val class supports forwarding,
but has no generated case-class `copy` method.

Legal domains come from authored requirements on the component or configuration.
Boolean leaves use 0..1. Existing cross-field contracts and compact-domain checks
are reused, without introducing Cartesian-product schema discovery. Derived
integer fields and source-owned pure arithmetic helpers use the existing symbolic
expression grammar. Ordinary loops and branches use the existing control lowering.

Initial boundaries:

- Schemas must be in this compiler invocation, at package or object scope, with
  one constructor parameter list and immutable public constructor fields.
- Supported leaves are `Int`, `Boolean`, their existing explicit `Param` forms,
  and nested source-owned configurations. Derived fields are integer expressions.
- Generic, binary-only, method-local, non-static inner-class, cyclic, mutable,
  lazy, inherited or effectful schemas are rejected. Arbitrary getters, methods,
  collections and optional/packed leaves are not whole-object schema leaves.
- Synthetic schema classes are a compiler implementation detail, not a stable
  binary interface. Compile schema and consuming components together. Ordinary
  configuration classes retain their source API.

Focused checks and exact evidence identities are recorded in the adjacent JSON
receipt. No CI or full qualification is required by LWR-37.

## Local validation (18 October 2026)

Both compiler-plugin builds and all 162 test executions passed on Scala 2.12.18
and 2.13.12 (81 tests each). Seven runtime feature tests and fourteen new frontend
tests cover whole-object promotion; the remaining tests retain explicit-leaf,
packed/optional, nested, derived, recursive and large-domain behavior.

Each Scala run recorded 61 Icarus Verilog-2001 compilations, 61 simulations,
three Verilator lint checks and three Yosys checks. Expected invalid-override
simulation failures are asserted by their tests and retained in the tool ledger.
Questa was unavailable. No remote CI or full qualification was launched.

The focused runner archives exact commands, compiler-plugin hashes, source
hashes, generated artifacts and tool inputs/logs under
`~/.local/state/morphhdl/lightweight-scala-rtl/lwr37-20261018/`.
The baseline is `2f8b5d52ec0d8c27d51c6590f3d64613857be1d9`; source hashes in the
JSON distinguish this implementation from that baseline. The publication receipt
records the final commit/tree and source-integrity checks separately.
