# WA-10: production timing source validation

The unchanged production timing component was regenerated through **MorphVerilog**.
All three exact temporary patterns reported in the request disappear with the final
enabled compiler. Explicitly disabled output remains byte-identical to the pristine
disabled compiler. This summary contains aggregate evidence, hashes and commands;
private application source, HDL and stimulus are not included in the compiler repo.

## Source and tool identity

| Item | Identity |
| --- | --- |
| Application repository/ref | `pysolvesemi/display_controller`, `dev` resolved to `d81ede9164fdd9b6e5fc994661c99d85ec49a817` |
| Production component | `display-controller-morphhdl/hw/spinal/displaycontroller/DisplayControllerProgressiveTimingGenerator.scala` |
| Component Git blob | `2d975cf69b8fe15620ab42d3d2ea46415358c401` |
| Original generator | `display-controller-morphhdl/hw/gen/displaycontroller/GenerateProgressiveTiming.scala` |
| Generator Git blob | `88b05ec12e876313363010444516b3f97d384a5a` |
| Pristine compiler | `086cb4c642d0182e33bd86fd391f46fc7c0eb2a8` |
| Candidate compiler | WA-10 source worktree based on that pristine revision |
| SBT / Scala | `1.10.0` / `2.12.18` |
| Java | OpenJDK `11.0.32+9-post-1ubuntu1-24.04-Ubuntu` |
| HDL compiler/runtime | Icarus Verilog / VVP `12.0 (stable)` |
| Evidence helper runtime | Python `3.12.14` |
| Private trace fixture SHA-256 | `442bedd9a272bf0ca3a480dc728b9045152c6f5a1f537d5e05ae1d48bc6dc71f` |

The connected GitHub API supplied both files at the exact application commit;
`git hash-object` verified both downloaded files against the blob identities above.
The application repository and compiler pin were not modified. The isolated SBT
project uses local `morph`/`lib` source projects and both required `idsl-plugin` and
`morphhdl` compiler plugins. Its separate disabled diagnostic driver invokes the
existing `MorphWireAssignmentPasses(config, enabled = false)` API.

Final regeneration reused the unchanged, plugin-compiled application classes with
freshly compiled local compiler class directories ahead of SBT's exported runtime
jars. The original main still calls production MorphVerilog. This avoids an
unnecessary repeated SBT build; it does not substitute SpinalVerilog or alter HDL.
The later `Seq` qualification for Scala 2.13 compatibility is type-only and does not
change the Scala 2.12 production behavior measured here.

## Actual boundary diagnosis

| Pattern or boundary | Executed observation | Final treatment |
| --- | --- | --- |
| Reported literal carrier | No corresponding native declaration. The backend's separate expression-size splitter introduced two literal leaf carriers after the typed wrapping decision. | Both disappear. The splitter keeps its expression-size safeguards but no longer adds these pointless leaf carriers in enabled mode. |
| Reported sync zero extension | The exact extension carrier was absent from native declarations and added by emission. Four genuine unnamed 18-bit sync arithmetic carriers were also rejected by the old native pass with `WA07-NATIVE-NON-DIRECT-RECEIVER`. | All four native carriers are substituted into conditional register resize RHS expressions. Their avoidable emitted extension children disappear. |
| Reported final-group subtraction | No corresponding native declaration; its same-width unsigned comparison operand was wrapped during emission. | The reported subtraction carrier disappears through typed structured emission. |
| Native conditional register uses | Baseline: four unnamed expression candidates, zero eliminations, four direct-receiver rejections. | Final: four eliminations and four procedural receiver rewrites; register scopes and clocked assignment behavior remain. |
| Parameter identity | One native `PPC4` parameterized artifact, elaboration domain `0..1`. | Both parameter values compile and simulate; existing Boolean/integer normalization spelling is unchanged. |

The native diagnostic observer executed the same six native slots and captured
actual candidate reports and pre-emission declarations. Its Verilog was
byte-identical to ordinary enabled generation. An emitted `_zz_` spelling was used
only for artifact inspection, never as evidence of native unnamed provenance.

## Final emitted carrier counts and retained reasons

| Artifact | Literal carriers | Zero-extension carriers | Subtraction carriers | All diagnostic `_zz_` continuous drivers |
| --- | ---: | ---: | ---: | ---: |
| Pristine enabled | 2 | 26 | 20 | 56 |
| Pristine disabled | 2 | 34 | 20 | 66 |
| Final enabled | 0 | 16 | 8 | 32 |
| Final disabled | 2 | 34 | 20 | 66 |

All **32** remaining final enabled drivers have an identified reason:

| Count | Retained class | Evidence-backed reason |
| ---: | --- | --- |
| 24 | 16 lane zero extensions and 8 lane subtractions | Their receiver vectors carry the compiler's `noBackendCombMerge` metadata tag. Diagnostics confirmed fixed disjoint bit targets `0..3`, width 4, no parameter-width authority, and that tag. The emitter's conservative annotated-target fence is preserved. No new metadata whitelist was added. |
| 4 | 18-bit sync arithmetic select bases | Explicit narrowing uses 13-/12-bit selections. The structured Verilog-2001 lowering retains a legal reference base for those selects while inlining eligible children. |
| 2 | Total-value aliases | Actual native rejection `WA04-NATIVE-USE-CONTEXT`: the existing alias bridge does not admit these conditional register RHS uses. These are conservative retained aliases, not claimed to be mathematically necessary. |
| 2 | Counter-update mux carriers | Their register targets have multiple data assignments in distinct procedural scopes. The emitter's single-assignment target safeguard retains them. |

These limitations are distinct from the three reported temporary patterns, which
are all absent. Protected and parameter-dependent production boundaries remain.

## Executed commands

The isolated validation directory is
`/workspace/scratch/6b630b856865/wa10-production-display`.

```sh
cd /workspace/scratch/6b630b856865/wa10-production-display
/tmp/wa10-sbt -Dwa10.morph.root=/tmp/wa10-baseline 'runMain displaycontroller.GenerateProgressiveTiming artifacts/baseline-enabled' 'runMain displaycontroller.GenerateProgressiveTimingDisabled artifacts/baseline-disabled'
/tmp/wa10-sbt -Dwa10.morph.root=/tmp/wa10-baseline 'runMain displaycontroller.GenerateProgressiveTimingDiagnostic artifacts/baseline-diagnostic'
python3 regenerate_from_local_classes.py
python3 inspect_artifacts.py artifacts/final-enabled/DisplayControllerProgressiveTimingGenerator.v artifacts/final-disabled/DisplayControllerProgressiveTimingGenerator.v
python3 run_trace_comparison.py baseline-enabled baseline-disabled final-enabled final-disabled
cmp artifacts/final-enabled/DisplayControllerProgressiveTimingGenerator.v artifacts/final-repeat/DisplayControllerProgressiveTimingGenerator.v
cmp artifacts/final-enabled/DisplayControllerProgressiveTimingGenerator.v artifacts/final-diagnostic/DisplayControllerProgressiveTimingGenerator.v
cmp artifacts/baseline-disabled/DisplayControllerProgressiveTimingGenerator.v artifacts/final-disabled/DisplayControllerProgressiveTimingGenerator.v
```

The regeneration helper writes the full exact Java invocations to
`final-java-commands.sh` and the ordered class directories, exported jars and command
arrays to `final-classpath.json` in that private directory. Its SHA-256 is
`e190cee2173a3db3a072380e618cc9a1ac3f0567811605b88745997693ff5c63`.
The trace helper's SHA-256 is
`a9d840e9e13f47155f53a0ceafbe1e97215f89ed2f850d091eacca361592b713`.

For every artifact/parameter combination, the trace helper executes this exact
command form and runs the resulting untouched artifact independently:

```sh
iverilog -g2001 -s production_trace_tb -Pproduction_trace_tb.PPC4=0 -o artifacts/final-enabled/ppc4-0.vvp production_trace_tb.v artifacts/final-enabled/DisplayControllerProgressiveTimingGenerator.v
vvp artifacts/final-enabled/ppc4-0.vvp
```

The same commands ran with `PPC4=1` and for `baseline-enabled`, `baseline-disabled`
and `final-disabled`: **8 successful strict Verilog-2001 compilations and runs**.
There is no generated-module renaming or HDL text rewriting.

## Validation results and hashes

Each simulation sampled **2,136 clock cycles**. Stimulus covers reset/release, tiny
legal geometries with repeated line/frame wrap, maximum active/total limits,
zero/all-maximum input fields, invalid boundary geometry and 2,000 deterministic
randomized load/control cycles. All four traces match for each `PPC4` value.
This is a directed/random simulation comparison, not an exhaustive formal proof of
the production component; compiler standalone regressions provide separate formal
and arithmetic coverage.

| Evidence | SHA-256 / result |
| --- | --- |
| Pristine enabled HDL | `35e1f626ec80557a7d2314f31af031e9efc0e77daef63a5a31614a7c9c749441` |
| Pristine and final disabled HDL | `7cb8df6e527851459b4000ad5fe6f43d15e45c9bcaaf265904a303e990e1347c` |
| Final enabled, repeated and diagnostic HDL | `0c5cc31259b04253244eb6e12ad575599507771adf815a232efaecaf065733a8` |
| All four `PPC4=0` traces | `9915c57dec22bc9a992d8a657fe782ce29b7716da56f51eff70e7a1f21df5b1f` |
| All four `PPC4=1` traces | `e1939c82879eeb698b0668a505ce97397cf0fda649596fa697fa694f9dc730d9` |
| Repeated enabled generation | Byte-identical: passed |
| Observed versus ordinary native pipeline | Byte-identical: passed |
| Explicit disable versus pristine disable | Byte-identical: passed |
| Three exact reported temporary patterns | Absent from final enabled HDL |
