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
