# WA-10 CI follow-up

The user-reported [run 34684811098](https://github.com/pysolvesemi/MorphHDL/actions/runs/34684811098)
checked source commit `1163ad3a8f6af4243a029f49dbd19f8f6aa7d66c`. It failed with:

```text
WA-08 source overlay: unreviewed production delta: current reviewed bytes differ: morphhdl-passes/examples/NamedWireExpressionNativeBridge.scala
```

The source change preceded its refreshed hash seal. Commit
`43169985ec6886441c95d47d35c8ad4fd866e4c8` already corrected the seal. The
corresponding [run 34684853063](https://github.com/pysolvesemi/MorphHDL/actions/runs/34684853063)
passed all four jobs on 2026-09-12: source-overlay at 09:36:12 UTC, production
Scala 2.12 at 10:07:46, production Scala 2.13 at 10:09:01, cross-Scala at
10:13:31. The source step reports `WA08_SOURCE_OVERLAY_PASS files=100` and
116 rejected live mutation controls.

The subsequent SBT baseline and Mill Scala 2.13 failures share one obsolete
shape assertion in `spinal.core.TypedPrimitiveClosureTests`: it requires the
unprotected combinational `stream_s2m_payload` mux carrier. The test is updated
in place, preserving the exact 28-case suite inventory. It now checks both
optimization modes, all retained symbolic payload widths, the protected
resize, fixed slice, payload register declarations, reset, ready condition,
and the exact guarded nonblocking assignment. Disabled mode must retain the
original carrier; enabled mode must substitute its mux at the same register
boundary. Compiler implementation and semantic/formal tests are unchanged.

## Executed focused regression

```sh
sbt -batch '++2.13.12' 'morph/testOnly spinal.core.TypedPrimitiveClosureTests'
sbt -batch '++2.12.18' 'morph/testOnly spinal.core.TypedPrimitiveClosureTests'
```

| Run | Executed result |
| --- | --- |
| Original test, Scala 2.13.12 | 27 passed, 1 failed: missing `stream_s2m_payload` |
| Corrected test, Scala 2.13.12 | 28 passed, 0 failed, 0 canceled |
| Corrected test, Scala 2.12.18 | 28 passed, 0 failed, 0 canceled |

The local executable was `/tmp/wa10-sbt`, the existing SBT 1.10.0/JDK 11
bootstrap; it invokes the repository's actual SBT projects with their required
plugins. Full focused output is retained in `typed-primitive-ci-before.log`
and `typed-primitive-ci-after.log`. `log-identities.json` records both original
and saved SHA-256 values; only trailing line whitespace is normalized.

These are structural checks, not a substitute for semantic equivalence. The
current-source inherited 60f Scala 2.13 job `103529957982` ran 1,119 tests with
1,118 passing, zero canceled, and only the same typed shape suite failing;
all eight mandatory formal tests executed successfully. New final-head CI
must still pass before WA-10 is marked complete or merged.

## Final-head inherited-inventory correction

Final-head [run 34689891226](https://github.com/pysolvesemi/MorphHDL/actions/runs/34689891226),
job `103543196609`, executed the complete Scala 2.13 inherited regression set
and then rejected the results with:

```text
RuntimeError: changed exact reviewed test inventory: spinal.core.MorphVerilogExpressionInliningTests: 11
```

The compiler, SBT tests and mandatory formal cases had executed; the failure
was the immutable 60f catalog correctly refusing an unauthenticated successor
count. Comparing all 195 XML reports from artifact `10297729227` against the
WA-09 catalog found the complete WA-10 delta, rather than changing only the
first suite named by the failure:

| Project / suite | WA-09 | WA-10 |
| --- | ---: | ---: |
| `core`: `spinal.core.internals.VerilogEmitterExpressionInliningTests` | 16 | 25 |
| `morphhdl`: `morphhdl.examples.NativeWireExpressionCodecTests` | absent | 3 |
| `morphhdl`: `spinal.core.MorphVerilogExpressionInliningTests` | 10 | 11 |
| `morphhdl-passes`: `morphhdl.passes.transform.UnnamedWireExpressionEliminationPassSpec` | 12 | 13 |

`check-increment-60f-artifacts.py` now keeps the historical WA-09 catalog
exactly at 1,982 tests / 194 suites and selects an exact 1,996-test / 195-suite
WA-10 successor only when its source-scope contract and all four suite sources
have their reviewed added/inherited identities. Its self-test rejects a
missing predecessor, contract, source, case or changed baseline identity.

The downloaded job reports were replayed without changing them:

```sh
python3 morphhdl/scripts/check-increment-60f-artifacts.py \
  regressions . target/wa10-ci-followup-inventory-final.json \
  > target/wa10-ci-followup-inventory-final.log 2>&1
```

The command exited zero. The project totals were `paramrtl` 234/23,
`frontend` 257/22, `backends/verilog` 148/21, `morphhdl` 1119/105,
`morphir` 32/2, `morphplugin` 16/2, `core` 30/2, and `morphhdl-passes`
160/18 (tests/suites). The raw replay log and JSON SHA-256 values are
`83b501dd2cbb32c5099975c59fb26e8ceb146e5a444e8b7b8803bdf51ccc90b1`
and `0c552c5d4efac711cc76f2154fe87552295918e7e095fa529dae751a70fb8d79`.
The reviewed correction is commit
`dab690bf94920d18ab8e47dedda69d3984519778`; source seal
`650f75f0217340fead8a04107fd9170fcfea1c8b` authenticates it.

## Additional executed qualification

The full local Scala 2.13 run completed with 1,110 passed, one failed and eight
canceled tests. Its sole failure was an unavailable `sby` executable; the
shape assertion passed. The chained Scala 2.12 full run consequently did not
start. This command is not reported as a passing full-suite run:

```sh
/tmp/wa10-sbt '++2.13.12' morph/test '++2.12.18' morph/test
```

After installing the CI-pinned tools, both the adapter formal suite and the
primitive-closure formal suite passed: four tests, zero failures/cancellations,
including required genuine counterexample mutations. An intermediate attempt
with Yosys 0.33 and SBY 0.41 had an ABC witness-schema mismatch; the final run
uses actual Yosys 0.41, its matching ABC, SBY and Yices. Versions, pins and
binary hashes are in `formal-tools.json`; no tool check or test was weakened.

The exact successful launch reused the Scala 2.13 test classpath compiled by
the preceding SBT run. Compiler and formal-test sources are unchanged by the
shape-test correction:

```sh
wa10_formal_cp="$(cat morphhdl/target/streams/test/fullClasspath/_global/streams/export)"
PATH="/tmp/wa10-formal-tools/bin:$PATH" \
MORPHDL_RUN_TYPED_PRIMITIVE_CLOSURE_FORMAL_EQUIVALENCE=1 \
MORPHDL_TYPED_PRIMITIVE_FORMAL_WORKSPACE="$PWD/target/wa10-evidence/ci-typed-primitive-formal-final" \
/usr/lib/jvm/java-11-openjdk-amd64/bin/java -Xmx3G -XX:ActiveProcessorCount=2 \
  -cp "$wa10_formal_cp" org.scalatest.tools.Runner \
  -R morphhdl/target/scala-2.13/test-classes \
  -s morphhdl.TypedStreamWidthAdapterFormalEquivalenceTests \
  -s morphhdl.TypedPrimitiveClosureFormalEquivalenceTests -oW
```

Primitive closure proves four existing `(WIDTH, DEPTH)` witnesses: `(5,1)`,
`(8,3)`, `(13,5)`, `(16,8)`, and rejects the deliberate `(8,3)` mutation.
Its combined fixture uses the same pipe algorithms with Bits payloads and
synchronous reset; it is complementary coverage, not an exhaustive proof of
the separate UInt/asynchronous-reset shape fixture at every width.

The exact source-overlay workflow command sequence also passed on sealed
commit `650f75f0217340fead8a04107fd9170fcfea1c8b`: 101 reviewed files,
117 rejected overlay mutations, preserved native manifest and inherited source
audits. The additional WA-10 checks passed its 184-path scope, all 12 scope
mutations and 116 safety controls. The exact 60f report replay also passed.
`qualification.json` records these results and hashes the full local audit
log.

## Qualified implementation head

Implementation candidate
[`548a67131c929cb2e050b0c62447bc72ed4bf0fd`](https://github.com/pysolvesemi/MorphHDL/commit/548a67131c929cb2e050b0c62447bc72ed4bf0fd)
completed its exact-head GitHub Actions inventory on 2026-09-13 UTC. All 54
workflow pages and their latest-attempt job pages were enumerated. The 39
applicable workflows passed; 15 explicitly historical branch-limited workflows
were skipped. Across 226 jobs, 113 passed and 113 were justified skips. No job
was queued, in progress, failed, canceled or associated with a different run ID.

The [MorphHDL IR pass workspace](https://github.com/pysolvesemi/MorphHDL/actions/runs/34697709439)
passed all 24 jobs: both production Scala lanes, cross-Scala byte identity, all
16 full-domain proof shards and the final aggregate. The aggregate retained all
11 inherited pass identities over all 512 `WIDTH=1..64` by `DEPTH=1..8`
bindings in two runs, including equivalence, reachability and mutation controls.
The exact 1,996-test/195-suite successor catalog passed in
[the inherited 60f workflow](https://github.com/pysolvesemi/MorphHDL/actions/runs/34697709487).
The Scala 2.12 dependency-download failure in
[workflow 34697709555](https://github.com/pysolvesemi/MorphHDL/actions/runs/34697709555)
was superseded by successful retry job `103584794370`; no test or source
assertion failed in the original attempt.

The target `parameterized-verilog` head remained
`086cb4c642d0182e33bd86fd391f46fc7c0eb2a8` and was already an ancestor of the
candidate, so no target merge commit was required before the completion update.
The completion-only roadmap/evidence head must pass the identical applicable
workflow inventory before PR #185 is merged.
