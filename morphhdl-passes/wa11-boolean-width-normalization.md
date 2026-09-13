# WA-11 symbolic Boolean/integer width normalization

Status: implementation and qualification completed at
`d997cdb8a93ad7c5b3ca60835ff36c81328584d4`.
The completion-only commit must repeat the applicable checks before merge.

Baseline: `086cb4c642d0182e33bd86fd391f46fc7c0eb2a8` on
`parameterized-verilog`. The user-provided reproduction was executed unchanged
using the local source build and both repository compiler plugins:

```bash
sbt "morph/Test/runMain ReproduceBooleanWidth /tmp/boolean-width-repro"
```

Source: `morphhdl/src/test/scala/nativeapplication/ReproduceBooleanWidth.scala`.
The source uses the production generator:

```scala
class BooleanWidthExample(width: ElabInt) extends Component {
  setDefinitionName("BooleanWidthExample")
  val dataIn  = in Bits(width bits)
  val dataOut = out Bits(width bits)
  dataOut := dataIn
}

val report = MorphVerilog(config) {
  val width =
    HdlBool.param("PPC4", default = false).asElabBool.toElabInt * 3 + 1
  new BooleanWidthExample(width)
}
```

The committed runnable source also includes the imports, `SpinalConfig` and
command-line entry point. The baseline run on Scala 2.12.18 / Java 17.0.20
succeeded. Actual generated RTL:

```verilog
module BooleanWidthExample #(
  parameter integer PPC4 = 0
) (
  input  wire [(((((((((PPC4 == 1)) ? (1) : (0))) == (1))) ? 1 : 0) * 3) + 1)-1:0] dataIn,
  output wire [(((((((((PPC4 == 1)) ? (1) : (0))) == (1))) ? 1 : 0) * 3) + 1)-1:0] dataOut
);

  assign dataOut = dataIn;

endmodule
```

## Cause and implementation

`HdlBool.asElabBool` used an integer `select(predicate, 1, 0)` as its
analyzer-authenticated bridge, then compared that integer to one.
`StructuralExpressionBridge` rendered a Boolean parameter as `PPC4 == 1`.
`ElabBool.toElabInt` added another signed integer ternary. Width emission
retained that expression. The hardware Boolean-ternary pass never sees this
elaboration expression.

WA-11 retains the existing authenticated ingress and validation, and normalizes
conversion construction before symbolic expressions reach widths, geometry,
control or hierarchy bindings. This is canonical normalization, active even
when hardware wire-assignment passes are disabled. No emitted-text parsing or
substitution is involved.

A complete authoritative identity proof over a declared `{0,1}` parameter
allows its `parameter integer` value to replace the conversion. Both have
signed 32-bit Verilog integer semantics. General predicates retain `? 1 : 0`
to preserve integer sizing and signed arithmetic. Native conversion origins
are retained privately on typed values; a round-trip comparison still derives
and validates fresh exact-domain/projection evidence instead of returning a
saved source with broader authority.

## Actual normalized reproduction

The same source now emits:

```verilog
module BooleanWidthExample #(
  parameter integer PPC4 = 0
) (
  input  wire [((PPC4 * 3) + 1)-1:0] dataIn,
  output wire [((PPC4 * 3) + 1)-1:0] dataOut
);

  assign dataOut = dataIn;

endmodule
```

## Validation

Initial Scala 2.12.18 checks passed: 24 frontend tests (six new) and ten new
native typed normalization tests. The earlier run also passed 44 inherited
integer/domain tests. JVM API comparison against the actual unmodified
baseline classes passed for both core and frontend.

Both Scala 2.12.18 and 2.13.12 passed all 263 frontend tests and 58 focused
native tests, with no failure or skipped test. Their 64 generated artifacts
(including the known unsupported helper controls) and the exact reproduction
match byte-for-byte.

The complete Scala 2.12 production check passed 62 artifact compilations
(30 normalized candidates, 30 unchanged-compiler references and both exact
reproductions), 156 parameter override instances and 2,496 input-pattern
checks. The same source compiler at the pinned baseline generated every
reference. These counts are overlapping qualification views, not a sum of
independent feature tests. Repeat and hardware-pass-toggle byte checks passed.
Four helper compilation rejections are reported separately and provide no
successful simulation credit.

The repaired candidate `d997cdb8a93ad7c5b3ca60835ff36c81328584d4`
(tree `0e195028ba7e3488f240d19055ec5458c166ca29`) passed every applicable
workflow. Its source anchor remains
`ef5283859c9dfc158fa121004adaf14e98da77b8`. All 40 PR workflows reported
success where active, plus the duplicate WA-11 push workflow; 15 historical
scoped workflows remained skipped. Eight of those PR successes only route
historical revisions and provide no test or proof credit.

Observed evidence on that candidate:

| Gate | Actual result |
| --- | --- |
| [WA-11 production](https://github.com/pysolvesemi/MorphHDL/actions/runs/34690370818) | Both Scala lanes: 82 tests in seven suites, zero failures/skips; 62 compilations, 156 overrides and 2,496 data patterns per lane; all 131 Verilog files byte-identical across Scala |
| [Complete inherited regressions](https://github.com/pysolvesemi/MorphHDL/actions/runs/34690370815) | Both lanes: 1,998 tests in 196 actual JUnit reports, zero failures/errors/skips, including all 16 new WA-11 tests; 213 deterministic signedness files |
| [Pass workspace](https://github.com/pysolvesemi/MorphHDL/actions/runs/34690370918) | All 21 jobs passed; 159 tests per Scala lane; all 16 proof shards aggregated over 11 pass identities and 512 bindings in both repeated runs |
| [External symbolic widths](https://github.com/pysolvesemi/MorphHDL/actions/runs/34690371009) | Both lanes: 109 tests, zero failures/skips |
| [Native register bridges](https://github.com/pysolvesemi/MorphHDL/actions/runs/34690370999) | Both lanes: 74 required tests, 222 parameterized specializations, 24 clock/enable profiles and four functional mutation counterexamples |

The pass proof contains 11,264 equivalence checks and corresponding
reachability checks over `DEPTH=1..8` and `WIDTH=1..64`, with actual solver
evidence and repeat determinism required by the aggregate gate. The separate
inherited regression artifacts retain 88 successful solver statuses and eight
expected failing mutation controls per lane; those eight are not successful
proofs or failed regressions. Source audits, ABI checks, the 96-file sealed
overlay and its 112 live mutation controls also passed.

The exact reproduction was compiled with both `PPC4=0` and `PPC4=1` from the
same generated artifact. Its one-bit and four-bit ports propagated all 16
input patterns per override. Exact before SHA-256:
`9a11aba9e023ed39ed24896cab93a1e9f78643e5197243847736eed0b00650f1`;
exact after SHA-256:
`a8d59aebc55d6f11245d76a9e7e90eefd0d173843dba70e2bf2a0ff835646785`.

The first inherited CI attempt exposed three stale formal-registry digests
for the reviewed WA-11 boundary checker, boundary tests and regression catalog.
Their expected hashes are refreshed without changing the 85-file inventory or
any proof behavior. The formal validator self-test, 146 existing equivalence,
shard and cone tests, and three independent source-mutation rejection controls
passed after that repair. Two other failed jobs stopped before tests because
Maven reset their dependency-download connections. Existing concurrency rules
superseded their old-head retries when the repair was pushed. The fresh jobs
on the repaired candidate above passed; no cancelled retry is credited.

## Preserved limits

Typed general elaboration retains its existing single-root exact-domain
contract, projection restrictions and local-parameter diagnostics. Production
native width schemas still reject negative parameter minima; typed integer
negative-domain and signed-boundary tests remain independent of that existing
publication restriction. Integer domains `0..3` exercise the production
non-Boolean rejection controls.

An additional helper fixture discovered an existing publication defect:
`HdlInt.addressWidth.hdlEq(...).asElabBool` can emit a `clog2` call without
its function definition. The same source fails strict compilation with the
unchanged baseline compiler. This is retained as an explicit known-unsupported
negative fixture, not counted as successful production coverage. WA-11
normalizes its Boolean conversions but does not extend helper publication.
