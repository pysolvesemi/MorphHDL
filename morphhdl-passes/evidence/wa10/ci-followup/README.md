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
commit `68a87e060134a22f17c2da56e5a16a2f3d5a68dd`: 101 reviewed files,
117 rejected overlay mutations, preserved native manifest and inherited source
audits. The additional WA-10 checks passed all 12 scope and 116 safety controls.
`qualification.json` records these results and hashes the full local audit
log. Final-head GitHub CI remains required before completion and merge.
