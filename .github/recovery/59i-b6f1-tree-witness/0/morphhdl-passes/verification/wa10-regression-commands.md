# WA-10 production artifact regression

The artifact writers invoke production `MorphVerilog` with the local compiler
projects and their existing SBT plugins. Neither writer invokes `SpinalVerilog`
or modifies generated Verilog text.

From the repository root, generate and validate both fixtures:

```sh
bash morphhdl-passes/scripts/run-wa10-regression.sh
```

The runner defaults to Scala 2.12.18. `SCALA_VERSION=2.13.12` selects the other
supported Scala build; `SBT=/path/to/sbt` selects an existing SBT launcher.
Artifacts, tool versions, exact command argument lists, simulation traces,
mutation failures, formal scripts, proof logs, and `checks/results.json` are
saved under `morphhdl-passes/build/wa10`.

The exact generation commands, useful with an already running SBT session, are:

```sh
sbt -batch '++2.12.18' \
  "morph / Test / runMain TimingExpressionArtifactWriter $PWD/morphhdl-passes/build/wa10/timing" \
  "morph / Test / runMain morphhdl.examples.GeneralExpressionInliningArtifactWriter $PWD/morphhdl-passes/build/wa10/general"
bash morphhdl-passes/scripts/run-wa10-regression.sh --artifacts morphhdl-passes/build/wa10
```

Both writers emit `first/` and `repeat/` rounds, each containing `default/`,
`enabled/`, and `disabled/`. Each directory contains the same native
parameterized module, with the same `PPC4` parameter identity. The validator
compares repeated output bytes, default versus explicitly enabled bytes, and
all mode interfaces. It also checks the actual receiver assignments in final
Verilog and verifies removal of the old literal/extension carrier declarations.

To capture pristine baseline semantics before editing the compiler, run the
same writers against that checkout and use:

```sh
python3 morphhdl-passes/scripts/validate-wa10-artifacts.py /absolute/baseline --semantics-only
```

After generating the fixed artifacts, additionally verify exact explicitly
disabled legacy bytes against that baseline:

```sh
python3 morphhdl-passes/scripts/validate-wa10-artifacts.py /absolute/fixed \
  --baseline /absolute/baseline
```

## Captured local qualification commands

The pristine compiler checkout was `/tmp/wa10-baseline`, at commit
`086cb4c642d0182e33bd86fd391f46fc7c0eb2a8`. The reproduction sources were copied
into its existing `morph` test source project before generation. Compiler
sources in that checkout remained at the baseline commit. The local launcher
was `/tmp/wa10-sbt`; a normal installed `sbt` can replace that launcher when
rerunning these commands. It uses SBT 1.10.0 and the existing checked-out
MorphHDL/Spinal compiler plugins.

The following commands name the captured baseline and final directories.
Absolute output directories are intentional: `morph/Test/runMain` may resolve
a relative argument from the `morphhdl/` project directory.

```sh
wa10_repo="$(git rev-parse --show-toplevel)"
wa10_out="${wa10_repo}/target/wa10-evidence"

(cd /tmp/wa10-baseline && /tmp/wa10-sbt -batch '++2.12.18' \
  "morph/Test/runMain TimingExpressionArtifactWriter ${wa10_out}/baseline/timing" \
  "morph/Test/runMain morphhdl.examples.GeneralExpressionInliningArtifactWriter ${wa10_out}/baseline/general")
python3 morphhdl-passes/scripts/validate-wa10-artifacts.py \
  "${wa10_out}/baseline" --semantics-only

/tmp/wa10-sbt -batch '++2.12.18' \
  "morph/Test/runMain TimingExpressionArtifactWriter ${wa10_out}/final/timing" \
  "morph/Test/runMain morphhdl.examples.GeneralExpressionInliningArtifactWriter ${wa10_out}/final/general"
python3 morphhdl-passes/scripts/validate-wa10-artifacts.py \
  "${wa10_out}/final" --baseline "${wa10_out}/baseline"
```

`target/wa10-evidence/current` contains the earlier working iteration; it is
not the final evidence source. Qualification results are reported by the
validator's `checks/results.json` for the exact artifact hashes it validated.
The report includes full assignment inventories; this command document does
not freeze temporary counts while compiler implementation is changing.

To run only the user's reduced entry point:

```sh
sbt "morph/Test/runMain GenerateTimingExpressionExample /tmp/timing-expression-repro"
```

## Separate native-boundary diagnostics

`NativeExpressionDiagnosticsWriter` is an observer fixture. It replaces the
production phase in its own diagnostic configuration with the same six native
slots, recording candidate provenance, driver/use contexts, rejection reports,
and pre-emission declarations. Ordinary artifact writers above do not install
this hook; final-Verilog acceptance uses their unmodified production pipeline.

```sh
wa10_repo="$(git rev-parse --show-toplevel)"
wa10_out="${wa10_repo}/target/wa10-evidence"

(cd /tmp/wa10-baseline && /tmp/wa10-sbt -batch '++2.12.18' \
  "morph/Test/runMain NativeExpressionDiagnosticsWriter timing ${wa10_out}/baseline/diagnostics/timing" \
  "morph/Test/runMain NativeExpressionDiagnosticsWriter general ${wa10_out}/baseline/diagnostics/general")
/tmp/wa10-sbt -batch '++2.12.18' \
  "morph/Test/runMain NativeExpressionDiagnosticsWriter timing ${wa10_out}/final/diagnostics/timing" \
  "morph/Test/runMain NativeExpressionDiagnosticsWriter general ${wa10_out}/final/diagnostics/general"
```

The baseline diagnostic run also needs the observer source in the baseline
checkout's test source directory. Each run produces `native.tsv` and
`diagnostic.v`. Check diagnostic Verilog against the corresponding ordinary
enabled artifact before using its reports to attribute an emitted temporary
to the native boundary or the emitter boundary. A generated identifier's
spelling alone does not establish native provenance.

## Toolchain and CI scope

The local baseline and first production qualification used:

| Tool | Captured version |
| --- | --- |
| SBT | 1.10.0 |
| JDK | Ubuntu OpenJDK 11.0.32 |
| Scala for production artifacts | 2.12.18 |
| Icarus Verilog / VVP | 12.0 stable |
| Yosys | 0.33, git `2584903a060` |

Tool output is retained in `checks/*-version.log`, and every simulator/proof
invocation has an adjacent `.command.json` containing the exact argument
list. Availability of Verilator or another tool does not imply this runner
used it; this runner's compile gate is Icarus `-g2001`.

The `wa10_production` job in `.github/workflows/morphhdl-passes.yml` first runs
`NativePureExpressionCopyTests` and `VerilogEmitterExpressionInliningTests` in
the core project, plus `NativeWireExpressionCodecTests` and
`MorphVerilogExpressionInliningTests` in the morph project. It generates both
fixtures in all modes and repeats on Scala 2.12.18 and 2.13.12, emits the
separate native diagnostics, then runs the same artifact validator, including
both `PPC4` bindings. When diagnostics are present the validator requires their
Verilog to equal ordinary enabled production output. The job separately
checks disabled legacy bytes against the committed baseline and authenticates
those baseline bytes against their manifest hashes. Its artifact uploads are
`wa10-production-2.12.18` and `wa10-production-2.13.12`. The dependent
`wa10_cross_scala` job compares all six first-round Verilog files across Scala
versions; each matrix leg independently verifies its repeat files.
Scala XML test reports are uploaded separately as `wa10-test-reports-*`.
The canonical pass test project also has a two-version Scala test matrix.
These workflow definitions describe required CI gates; a configured gate is
not a claim that its current run has passed. Executed results and remaining
gates belong in the WA-10 qualification record.

## Semantic checks

`GeneralExpressionInlining` deliberately creates shared unnamed literal,
zero-extension, and subtraction declarations. `@dontName` prevents reflection
from inventing source naming provenance; two distinct receivers ensure that
ordinary single-use cleanup does not erase the reproduction before the
wire-assignment passes. Additional cases exercise eight-bit modular overflow
and underflow before nine-bit extension, signed subtraction and sign extension,
mixed signed/unsigned arithmetic, selected expressions, final truncation,
ternaries, conditional register updates, and `keep`/vital declarations.

The Icarus Verilog-2001 tests independently compute explicitly sized expected
values. They cover all 256 eight-bit values, arithmetic and timing threshold
neighborhoods, 512 deterministic random samples per fixture, full and partial
X/Z operands, X/Z ternary selectors, unknown register enables, and reset/state
updates. Each generated module is compiled separately with `PPC4=0` and
`PPC4=1`; no HDL source is renamed or rewritten. Output traces must agree
exactly across all three optimization modes. The general fixture runs 784
samples and the timing fixture 848 samples per compilation; the validator
requires those exact counts so a prematurely shortened test cannot pass by
printing only the success marker. Across all modes and bindings this is
12 simulations and 9,792 samples per qualification run.

For each fixture and parameter binding, Yosys parses the original disabled and
enabled artifacts, hides internal names, and proves port equivalence using
`equiv_simple` and `equiv_induct`. Hiding internal names prevents accidentally
equating unrelated expressions with the same generated identifier. Both
designs receive the same `async2sync` lowering; the simulation tests separately
exercise actual generated reset and four-state behavior.

Four negative controls deliberately use a wrong widened-underflow oracle at
the zero-input boundary. Each must terminate with the expected mutation
failure. A successful build or an absent test result is never accepted as an
equivalence result. Formal proofs cover two-state input valuations; simulation
provides the explicitly listed four-state witnesses rather than an exhaustive
four-state proof.

## Executed inherited production qualification

The existing WA-09 generic native witness exposed an extra same-width resize
fence in a whole-RHS XOR assignment. Its inherited final-Verilog assertion
failed while its 256-pattern four-state simulation and Yosys miter proof
passed. The compiler correction preserves the receiver's existing packed
boundary for that exact direct-assignment case; the inherited direct-XOR
assertion remains unchanged.

The inherited WA-08 31-artifact production writer was also executed. Its first
local SBT invocation failed before generating HDL with
`NoClassDefFoundError: spinal/lib/slave$`. Loading the same already compiled
entry point with the SBT-exported `morph/Test/fullClasspath` succeeded. The
working fallback command was equivalent to the following argument list;
`target/wa10-evidence/inherited-direct-java-command.json` retains the exact
executed Java arguments and complete classpath. For a fresh checkout, prepare
that exported class-directory classpath with
`sbt -batch '++2.12.18' 'export morph/Test/fullClasspath'` before the Java call:

```sh
wa10_repo="$(git rev-parse --show-toplevel)"
wa10_cp="$(cat morphhdl/target/streams/test/fullClasspath/_global/streams/export)"
/usr/lib/jvm/java-11-openjdk-amd64/bin/java -Xmx5G -XX:ActiveProcessorCount=4 \
  -cp "${wa10_cp}" morphhdl.examples.WireAssignmentProductionArtifactWriter \
  "${wa10_repo}/target/wa10-evidence/inherited/wa08-production"
python3 morphhdl/scripts/check-wa08-production-artifacts.py \
  target/wa10-evidence/inherited/wa08-production
```

Both invocation forms call the same production `MorphVerilog` writer and use
the plugin-compiled local sources. The first successful qualification used
the direct Java invocation; the failed SBT attempt is recorded separately.
Subsequent inspection found the local Scala 2.12 library JAR used by SBT's
`fullClasspathAsJars` was truncated and lacked its ZIP central directory.
The class-directory export contained valid classes. This local generated
artifact problem is recorded in `inherited-jar-diagnosis.log`. After removing
only that validated corrupt generated JAR, the following original SBT path
and complete checker both passed:

```sh
wa10_repo="$(git rev-parse --show-toplevel)"
sbt -batch '++2.12.18' 'lib/packageBin' \
  "morph/Test/runMain morphhdl.examples.WireAssignmentProductionArtifactWriter ${wa10_repo}/target/wa10-evidence/inherited-sbt/wa08-production"
python3 morphhdl/scripts/check-wa08-production-artifacts.py \
  target/wa10-evidence/inherited-sbt/wa08-production
```

The regenerated JAR is valid and contains `spinal/lib/slave$.class`.
All 31 first-round Verilog files from the repaired SBT invocation are
byte-identical to the direct Java artifacts. The successful repack, original
SBT generation, and full qualification are recorded in
`target/wa10-evidence/inherited-sbt-repack.log`. This closes the local launch
failure without changing application logic, compiler behavior, or CI flags.

After the direct-boundary compiler correction, the focused suites passed on
both Scala 2.12.18 and 2.13.12: 27 core tests and 14 native/public MorphVerilog
tests, 41 per Scala version. The targeted invocation is:

```sh
sbt -batch '++2.12.18' \
  'core/testOnly *NativePureExpressionCopyTests *VerilogEmitterExpressionInliningTests' \
  'morph/testOnly *NativeWireExpressionCodecTests *MorphVerilogExpressionInliningTests'
sbt -batch '++2.13.12' \
  'core/testOnly *NativePureExpressionCopyTests *VerilogEmitterExpressionInliningTests' \
  'morph/testOnly *NativeWireExpressionCodecTests *MorphVerilogExpressionInliningTests'
```

That original production checker initially required `sampledAlias` to survive
solely because its receiver is a register. Actual WA-10 output removes this
ordinary expression alias and preserves the `WIDTH`-wide `sampled` register,
its nonblocking assignment `sampled <= (a ^ b)`, and the output reference to
that register. The current checker requires this exact successor behavior,
requires the original alias and nonblocking source in disabled output, and
retains every existing metadata/control/hierarchy preservation check. The
existing functional equivalence proofs were unchanged.

The complete revised checker passed on all 31 artifacts and their repeats:
6,672 four-state cases, 51 formal equivalence cases, and four rejected
functional mutations. These results are in
`target/wa10-evidence/inherited/wa08-production/qualification.json`; the initial
failures, complete generation output, and successful checker result are in
`target/wa10-evidence/inherited-runtime-check.log`. The independent successful
original-SBT rerun has the same qualification counts in
`target/wa10-evidence/inherited-sbt/wa08-production/qualification.json`.

For the CI-discovered typed Stream payload shape assertion, run the unchanged
28-case suite with its corrected enabled/disabled expectations:

```sh
sbt -batch '++2.13.12' 'morph/testOnly spinal.core.TypedPrimitiveClosureTests'
sbt -batch '++2.12.18' 'morph/testOnly spinal.core.TypedPrimitiveClosureTests'
```

Both invocations passed all 28 cases. The original assertion failure and exact
successful output are retained in
[`evidence/wa10/ci-followup`](../evidence/wa10/ci-followup). The successor checks
the symbolic payload register and guarded NBA, while disabled optimization
still requires the original combinational mux carrier. It does not alter the
compiler or reduce the inherited test/formal inventory.
