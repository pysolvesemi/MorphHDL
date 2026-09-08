# Increment 59g — Native register bridges

Status: implemented, reviewed and qualified on both supported Scala versions.
The completion checkbox is backed by the exact implementation-head evidence
below. Final documentation-head checks, merge and post-merge results are
recorded in [PR #169](https://github.com/pysolvesemi/MorphHDL/pull/169); they
must not be inferred from the earlier implementation runs.

The ordinary `Vec(...).reduceBalancedTree(op, levelBridge)` source remains the
entry point. Bridge certification records the native register graph and replays
native register declarations, assignments, initialization and `when` conditions.
SpinalHDL still emits the clocked processes.

## Supported bridge forms

The scalar profile covers `UInt`, `SInt`, `Bits` and `Bool`:

- Identity and transparent local aliases.
- `RegNext`, inferred native registers, and chains of native registers.
- Register depth selected by the native zero-based bridge level.
- No initializer, zero, and typed nonzero constant initializers that fit every
  active width in the declared domain. Signed constants retain their sign.
- `RegNextWhen` with a closed local Boolean predicate of that register's exact
  data input: a Bool input, fixed bit, MSB, Boolean NOT, AND, OR or XOR.

The host callback contract remains capture-free and rejects unknown calls,
mutable host state and witness-width queries before execution. Arbitrary
external enable captures, feedback, partial assignments, false-arm updates,
soft reset, BOOT initialization and mixed clock domains are outside this
qualified bridge profile. These restrictions concern bridge certification;
they do not change ordinary native SpinalHDL register APIs.

Every pair result and odd tail passes through its native bridge. COUNT=1
bypasses all operators and bridges, including their callbacks, and adds no
register latency. With unconditional registers, latency is the sum of the
register counts at active levels, measured in enabled clock edges. A local
enable can stall its own register indefinitely; it does not promise fixed
transaction latency or a global pipeline stall.

## Clock and initialization contract

The clock matrix contains synchronous and asynchronous reset, active-high and
active-low reset, rising and falling clock edges, and active-high, active-low
or absent clock enable: 24 configurations. Native reset/enable precedence is
preserved. In particular, a synchronous reset under the native clock enable
is sampled on an enabled edge; asynchronous reset can affect initialized
registers between sampling edges.

Registers without an initializer start unconstrained. The `regResult` oracle
becomes comparable after its full pipeline depth of enabled sampling edges.
Reset does not initialize these registers. The proof does not force DUT state
to zero. Resettable results are checked after legal reset entry and under
arbitrary later inputs, enables and in-flight resets.

## Qualification matrix

One reusable singleton-default candidate is generated for each clock profile.
A second candidate with different WIDTH/COUNT defaults is generated for the
representative synchronous, active-high, rising-edge, active-high-enable
profile. All candidates retain WIDTH in 1..32 and COUNT in 1..17; candidates
are never regenerated for individual overrides.

The matrix includes the common WIDTH `{1,5,8,32}` by COUNT
`{1,2,3,5,8,9,16,17}` boundary cases. Clock configurations with an explicit
enable additionally cover WIDTH `{5,8}` by COUNT `{1,2,3,5,9}`; absent-enable
configurations cover WIDTH=5, COUNT=3. There are 25 candidate files, 190
independently elaborated native references and 222 candidate/reference
specialization comparisons, each with 11 independent outputs.

Both Scala 2.12.18 and 2.13.12 must pass graph and public API tests, repeated
byte-deterministic generation, strict Verilog-2001 parsing and lint, Icarus
simulation, full Yosys synthesis, sequential equivalence and functional
mutation controls. The checker exercises reset entry, enabled and stalled
edges, reset during a stall, asynchronous assertion between edges, and
in-flight reset. Added/removed stages, wrong initialization and changed
reset/enable precedence must produce actual failing output waveforms.

The native reference lives in
`morphhdl/src/test/scala/nativeapplication/BalancedBridgeNativeOracle.scala`.
Its concrete native reductions do not use candidate graph replay. The Python
cycle model provides an additional independent check of pairing, odd tails,
per-register state and enables. Passing this finite matrix is not a claim of
universal formal quantification over all parameter values.

Widening/composite/nested-owner combinations remain Increment 59i integration
gates. The inherited 59b, 59c, 59d, 59e and 59f checks remain in force.

## Example

```scala
val dataIn = in Vec(UInt(width bits), count)
val localEnableResult = out UInt(width bits)
localEnableResult := dataIn.reduceBalancedTree(
  (a: UInt, b: UInt) => a + b,
  (value: UInt, _: Int) => RegNextWhen(value, value.msb) init U(1)
)
```

This is the actual emitted first-level register process from the synchronous,
active-high clock-enable candidate. Its condition is the MSB of the native
pair sum; the same bridge also applies to an odd tail. The reset literal
retains WIDTH instead of the Scala construction default.

```verilog
always @(posedge clk) begin
  if(enable) begin
    if(reset) begin
    _zz_morphhdl_balanced_7_l0_pair_result <= {{(WIDTH - 1){1'b0}}, 1'b1};
    end else begin
      if(when_TypedBalancedReductionBridgeReplay_l120) begin
        _zz_morphhdl_balanced_7_l0_pair_result <= morphhdl_high_bit_source;
      end
    end
  end
end
```

## Completion evidence — September 7, 2026

The qualified implementation is
`a327dd01b7e3003370827580083e56ce7992d2a7`, complete tree
`fd68d4f6210d70e51abf17bdcd85f9865428bf74`. It incorporates merged
`parameterized-verilog` through `0e614d5f0642770c3114b42488bdaf7a9e8b785d`,
including 59h, WA-07a and the WA-07b roadmap-only change. All applicable PR
workflows on this implementation head completed successfully before this
completion record was prepared. Historical branch-filtered workflow skips
are not counted as passing tests; the full inherited regression has no skipped
cases, and the dedicated 59h gate explicitly runs on the 59g branch.

| Qualification | Scala 2.12.18 | Scala 2.13.12 | Actions run |
| --- | --- | --- | --- |
| Dedicated balanced-reduction tests | 252 tests / 21 suites; no failures, errors or skips | Same | [34123977985](https://github.com/pysolvesemi/MorphHDL/actions/runs/34123977985) |
| Register-bridge hardware matrix | 222 specializations; 24 clock/reset/enable profiles; 11 outputs | Same | [34123977985](https://github.com/pysolvesemi/MorphHDL/actions/runs/34123977985) |
| Full inherited regression, including isolated passes | 1,895 tests / 187 suites; no failures, errors or skips | Same | [34123978108](https://github.com/pysolvesemi/MorphHDL/actions/runs/34123978108) |
| Inherited nested-owner qualification | 405 tests / 32 suites; 105 nested-owner and 32 inherited publication specializations | Same | [34123977968](https://github.com/pysolvesemi/MorphHDL/actions/runs/34123977968) |

The dedicated, nested-owner and full-regression test sets overlap; their test
counts must not be added together. The full-regression totals above are from
all archived XML reports across eight project groups, not partial console
progress or a count of only the `morph` project.

For each bridge lane, the retained artifacts contain 222 successful reset-entry
solver logs and 2,442 successful per-output temporal-induction logs, together
with native simulation, strict Verilog-2001 compilation/lint and full synthesis
logs. All four required mutations (`added-stage`, `removed-stage`,
`wrong-initializer`, `reset-enable-precedence`) produce solver failures and
retained counterexample waveforms that actually assert `bad=1`.

All 25 reusable candidates and 190 independently elaborated native references
are byte-identical across repeated generation and both Scala lanes: 215 unique
RTL files, without per-override candidate regeneration. The two source archives
are also identical. This audit reads already executed CI evidence; it is not a
claim of additional local hardware-tool execution.

The independent references retain separate native state. Validity-gated
intermediate-state relations are proved as part of the failure condition,
never assumed. There is no forced-zero DUT initialization or assumption that
the two designs start with equal state. Async assertion between edges is
covered by native simulation; formal retains the documented `async2sync`
sampling-edge semantics. These remain finite parameter-specialization proofs,
not universal quantification over all WIDTH/COUNT values.

### Retained artifact identity

| Artifact | ID | SHA-256 |
| --- | --- | --- |
| 59g bridges, Scala 2.12.18 | `10023915031` | `af52e075204174be5e50eaa6dd8f26ea027188fd6946455ac02eef3956c1806d` |
| 59g bridges, Scala 2.13.12 | `10025184109` | `6f1005392296b9fe139d347a5c5fd1246d0b78d76e05706a74d8f9a52ced7de0` |
| Full regression, Scala 2.12.18 | `10021701190` | `56fb48cf0f9f661eb0f36b56d0a1199be4b2d83f434f9ebab43acf8a7c5747df` |
| Full regression, Scala 2.13.12 | `10021977384` | `5dda07cce3d90fe7efbbc0711bae1a7b5b643da829235320049c39220eb4d422` |

The common bridge source archive SHA-256 is
`079a4e5ff4e733f7d5e4706a3f168ceaa305d8f1a39e6b4205dcaa127afe8f9f`.
The native source guard also passed at the same implementation head in
[run 34123978080](https://github.com/pysolvesemi/MorphHDL/actions/runs/34123978080).
The 60f signedness-closure jobs and the remaining applicable inherited
workflows are green on that head as well.

### Compatibility and review closure

The previously failing zero-initialized legacy register remains a positive
compatibility test. The exception admits only the exact, unprojected legacy
parameter declaration and an exact-width, same-kind invariant zero. Nonzero
initializers, copied metadata, foreign roots, derived widths and erased typed
owner evidence do not acquire that exception. `NativePublicationWidth` retains
its strict typed declaration-owner checks.

The retained-constant initializer guard requires exact native target identity,
poison-free typed literals and equality between authorized graph edges and
emitted assignments. The composed 59c/59h/59g audits recognize already-merged
WA-07a source without exempting subsequent changes to it. Current-source
mutation controls continue to reject altered, removed, extra and staged-hidden
production source. Temporary repair/integration workflows are absent from the
published implementation tree. This documentation closeout changes no source,
test, oracle, manifest, workflow, generated RTL or tool pin.

Implementation and tests are generic scalar-graph certification and replay,
not recognizers for this fixture or a library component. The qualified surface
and exclusions above are unchanged. Mixed widening, composite, symbolic-clone
and nested-owner bridge combinations still require Increment 59i; completion
of this scalar bridge increment does not claim that integration work.
