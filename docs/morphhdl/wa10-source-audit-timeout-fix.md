# WA-10 post-merge inherited audit timeout correction

This is a repair to the completed WA-10 integration, not a new roadmap increment.
The compiler, generated RTL, formal domains, immutable historical reviews and
the existing 1,996-test / 195-suite catalog are unchanged.

## Reproduced CI failure

Both [widening run 34741926201](https://github.com/pysolvesemi/MorphHDL/actions/runs/34741926201)
and [nested-owner run 34741926187](https://github.com/pysolvesemi/MorphHDL/actions/runs/34741926187)
checked out merge `f06d9c412924b99cf2c76422549c375a571757dc`. Both Scala lanes
failed before compilation in:

```text
python3 morphhdl/scripts/test-increment-59b-inherited-source-scope.py
checked_current -> check-increment-60f-equivalence-closure.py:source_scope
subprocess.TimeoutExpired: ... timed out after 120 seconds
```

The same complete current-source audit had passed earlier in each nested-owner
job through the existing 60f caller's 600-second budget. Logged positive audit
intervals were approximately 140.3 and 135.6 seconds. This is a stale caller
wall-clock budget, not evidence of a Scala, RTL, synthesis or equivalence failure.
The failed jobs did not execute their downstream test/formal stages.

The widening workflow calls `test-increment-59d-inherited-60f-scope.py` next.
Inspection found the same complete current-source positive restricted to 120
seconds there. Both callers therefore need the same bounded correction.

Local follow-up on sealed source `731e44d514b4206b007ef76b0ee8c94b3e614388`
also reproduced `TimeoutExpired` in the nested-owner wrapper's complete current
positive at 180 seconds. That command did not pass, and its subsequent commands
were not executed. The same narrow positive-only budget correction is therefore
applied to this third caller; its historical/mutation default remains 180 seconds.

## Exact correction and preserved controls

- Add an optional timeout argument preserving each wrapper's original default:
  120 seconds for 59b/59d and 180 seconds for 59h.
- Pass 600 only at their full current-descendant positive entry points, matching
  the existing 59c/59f/60f policy. Historical positives, mutation cases, Git
  operations and historical orchestration retain their prior budgets.
- Retain required exit codes, exact PASS/rejection diagnostics and propagation
  of `TimeoutExpired`. No timeout is converted into a successful result.
- Add executable caller regression tests, including the current entry points,
  short historical/mutation budgets, positive and negative failure handling,
  timeout propagation, Git budgets, legacy signedness checks and workflow routing.
- Both workflows run those tests and include the shared audit/regression files
  in push and PR path filters. Both workflow job conditions explicitly include
  only `agent/wa-10-inherited-audit-timeout`, so all four widening/nested-owner
  Scala lanes run on the repair PR. The existing pass-workspace WA-10 boundary
  and branch policy remain unchanged, preserving its full-domain proof lanes.
- Refresh only the current WA-10 exact path inventory and outer byte seal;
  preserve original historical review bytes, source ancestry and all production
  source identities. The source anchor must be an authoritative fetched GitHub
  commit before sealing, never a locally invented or unpublished identity.

The earlier skipped workflow classification applied to the WA-10 PR branch;
these workflows are active on `parameterized-verilog`. Pre-merge skips do not
qualify their post-merge entry paths.

## Validation commands

```sh
python3 morphhdl/scripts/test-inherited-source-audit-timeouts.py
python3 morphhdl/scripts/test-increment-59b-inherited-source-scope.py
python3 morphhdl/scripts/test-increment-59d-inherited-60f-scope.py
python3 morphhdl/scripts/test-increment-59h-inherited-source-scope.py
python3 morphhdl/scripts/test-increment-60f-inherited-source-scope.py
python3 morphhdl/scripts/check-increment-62-wa08-source-overlay.py
python3 morphhdl/scripts/test-increment-62-wa08-source-overlay.py
python3 morphhdl/scripts/test-wa07b-inherited-review.py
python3 morphhdl/scripts/check-wa10-source-scope.py
python3 morphhdl/scripts/check-wa10-source-scope.py --self-test
python3 morphhdl-passes/scripts/check-wa10-general-expression.py
python3 morphhdl-passes/scripts/check-wa10-general-expression.py --self-test
```

The original 143 WA-10 evidence-file hashes remain unchanged. Caller unit tests
supplement, not replace, the actual complete current/historical source audits.
Fresh exact-head CI must execute both Scala lanes and all downstream simulation,
synthesis, equivalence and mutation work before this repair is merged.

## Local qualification recorded on 2026-09-13

Reviewed source: `14621a6e7ceaa0af3ac8fbbff1c36f62a5412034`.
Authoritative source seal and complete three-caller replay head:
`8f0f203ab8321686227a720e5a02078f097d710a`.
Both were fetched from GitHub and their complete Git trees verified before use.
The following documentation-only publication commit preserves those identities.

| Check | Result on sealed head `8f0f203a` |
| --- | --- |
| Caller regression tests | 14 passed; real wrappers with mocked execution/orchestration |
| 59b full replay | Current complete audit passed; frozen controls: 6 positives, 19 exact rejections; 5m02.440s |
| 59d full replay | Current complete audit passed; frozen controls: 7 positives, 26 exact rejections; 5m49.816s |
| 59h full replay | 2 positives, 28 exact rejections; unchanged 59c and pre-rollout 59h replays passed; 9m28.075s |
| Source overlay | 106 reviewed files; 122 rejected live mutations |
| WA-10 exact scope | 191 paths; 12 rejected scope mutations |
| Current WA-10 safety contract | Positive passed; 116 rejected mutations |
| Actual pass-workspace branch boundary | Accepted all 10 changed paths using the real WA-10 branch and merged target base |
| Native manifest regeneration | Both workflow outputs byte-identical to the immutable manifest; SHA-256 `4b43dedd430b349153a60522b6cd7cd48a564e12ad1ee9de63510d2fdf834767` |
| 59h source-review self-test | 18 reviewed-snapshot reversals; 113 rejected mutations |
| 59h matrix-validator self-test | 516 required specializations; 519 rejected invalid matrix controls |
| Existing WA-10 evidence | All 143 file hashes preserved and verified |

The unchanged 60f full replay also passed on the earlier repair seal
`731e44d514b4206b007ef76b0ee8c94b3e614388`: 2 positives and 16 exact
negative cases (4m08.425s). That same seal passed the full WA07b composition test
with 86 mutations, plus 60g/59c/59g source reviews. These are correctly identified
as earlier-seal results, not fresh completion-head CI. The final three caller
replays above re-exercised complete current source auditing on `8f0f203a`.

Compiler, Scala test-suite source, immutable historical manifests, the 60f
catalog and all original WA-10 evidence are byte-identical to merged
`f06d9c412924b99cf2c76422549c375a571757dc`. No production compilation or HDL
proof rerun is claimed from these local source-audit checks.

Publication status: local repair validation passed. Fresh exact-head GitHub CI
must still complete before merge, including both affected Scala matrices and
the existing WA-10 production/full-domain proof/aggregation workflow. Expected
PR trigger inventory is 50 workflows with separately justified branch/path
conditions; job matrices must be enumerated dynamically. Use an ordinary merge
commit and verified expected head SHA only after every applicable gate passes.

## Complete final sealed-head caller logs

These logs are from the exact `8f0f203a` checkout. Each command exited zero.
Timings include the complete unchanged historical replays, not just the current
positive phase. Caller unit tests do not replace these real replay commands.

<details>
<summary>test-inherited-source-audit-timeouts.py</summary>

Command: `python3 morphhdl/scripts/test-inherited-source-audit-timeouts.py`

```text
test_59h_historical_and_mutation_defaults_keep_180_seconds (__main__.AuditTimeoutTests.test_59h_historical_and_mutation_defaults_keep_180_seconds) ... ok
test_59h_main_selects_600_seconds_only_for_complete_positive (__main__.AuditTimeoutTests.test_59h_main_selects_600_seconds_only_for_complete_positive) ... ok
test_59h_pass_and_rejection_require_exact_outcomes (__main__.AuditTimeoutTests.test_59h_pass_and_rejection_require_exact_outcomes) ... ok
test_59h_timeout_is_never_a_pass_or_expected_rejection (__main__.AuditTimeoutTests.test_59h_timeout_is_never_a_pass_or_expected_rejection) ... ok
test_both_workflows_trigger_on_shared_audit_changes (__main__.AuditTimeoutTests.test_both_workflows_trigger_on_shared_audit_changes) ... ok
test_current_entrypoint_selects_600_seconds_only_for_complete_positive (__main__.AuditTimeoutTests.test_current_entrypoint_selects_600_seconds_only_for_complete_positive) ... ok
test_exact_wa10_repair_branch_runs_both_inherited_workflows (__main__.AuditTimeoutTests.test_exact_wa10_repair_branch_runs_both_inherited_workflows) ... ok
test_expected_mutation_keeps_120_seconds (__main__.AuditTimeoutTests.test_expected_mutation_keeps_120_seconds) ... ok
test_git_commands_keep_120_seconds (__main__.AuditTimeoutTests.test_git_commands_keep_120_seconds) ... ok
test_historical_positive_keeps_120_seconds (__main__.AuditTimeoutTests.test_historical_positive_keeps_120_seconds) ... ok
test_legacy_59b_checks_keep_120_seconds (__main__.AuditTimeoutTests.test_legacy_59b_checks_keep_120_seconds) ... ok
test_mutation_requires_nonzero_exit_and_exact_diagnostic (__main__.AuditTimeoutTests.test_mutation_requires_nonzero_exit_and_exact_diagnostic) ... ok
test_positive_requires_zero_exit_and_pass_marker (__main__.AuditTimeoutTests.test_positive_requires_zero_exit_and_pass_marker) ... ok
test_timeout_is_never_accepted_as_a_pass_or_mutation (__main__.AuditTimeoutTests.test_timeout_is_never_accepted_as_a_pass_or_mutation) ... ok

----------------------------------------------------------------------
Ran 14 tests in 0.027s

OK

real	0m0.137s
user	0m0.115s
sys	0m0.022s
```

</details>

<details>
<summary>test-increment-59b-inherited-source-scope.py</summary>

Command: `python3 morphhdl/scripts/test-increment-59b-inherited-source-scope.py`

```text
PASS: current descendant through complete 59c and inherited source audits [accepted]
PASS: current complete source audits; separately scoped historical contract controls
PASS: current descendant with qualified history and approved native source [accepted]
PASS: historical [accepted]
PASS: historical-60d [accepted]
PASS: historical-60e [accepted]
PASS: historical-59b [accepted]
PASS: historical-combined-60f [accepted]
PASS: changed-hook [native signed declaration/cast hooks changed after their frozen qualification]
PASS: changed-printer [native signed declaration/cast hooks changed after their frozen qualification]
PASS: unapproved-path [MORPH-NATIVE-AUDIT-UNAPPROVED-PATH]
PASS: dirty-extension [MORPH-NATIVE-AUDIT-DIRTY-WORKTREE]
PASS: changed-boundary-printer [native signed declaration/cast hooks changed after their frozen qualification]
PASS: changed-vec [unreviewed source change outside 60e spans]
PASS: current-changed-hook [native signed declaration/cast hooks changed after their frozen qualification]
PASS: current-changed-printer [native signed declaration/cast hooks changed after their frozen qualification]
PASS: current-unapproved-path [MORPH-NATIVE-AUDIT-UNAPPROVED-PATH]
PASS: current-dirty-extension [MORPH-NATIVE-AUDIT-DIRTY-WORKTREE]
PASS: current-changed-boundary-printer [native signed declaration/cast hooks changed after their frozen qualification]
PASS: current-changed-width-fallback-hook [missing/duplicate reviewed 59d restoration span]
PASS: current-changed-width-fallback-resize [missing/duplicate reviewed 59d restoration span]
PASS: current-changed-width-fallback-domain [missing/duplicate reviewed 59d restoration span]
PASS: current-changed-width-fallback-session [missing/duplicate reviewed 59d restoration span]
PASS: current-changed-width-fallback-single-driver [missing/duplicate reviewed 59d restoration span]
PASS: current-changed-width-fallback-publication-width [missing/duplicate reviewed 59d restoration span]
PASS: current-changed-width-fallback-width-matcher [missing/duplicate reviewed 59d restoration span]
PASS: current-changed-width-fallback-outside [unreviewed source change outside 59d/59e publisher spans]
PASS: 6 positive and 19 exact negative inherited source-scope cases

real	5m2.440s
user	3m14.360s
sys	1m38.402s
```

</details>

<details>
<summary>test-increment-59d-inherited-60f-scope.py</summary>

Command: `python3 morphhdl/scripts/test-increment-59d-inherited-60f-scope.py`

```text
PASS: current descendant through complete 59c and inherited source audits [accepted]
PASS: current complete source audits; separately scoped historical contract controls
PASS: working reviewed descendant [accepted]
PASS: historical-60f [accepted]
PASS: historical-reviewed-59d [accepted]
PASS: historical-reviewed-59f [accepted]
PASS: historical-reviewed-59e [accepted]
PASS: historical-profile-aware-60f [accepted]
PASS: committed-reviewed-descendant [accepted]
PASS: partial-wa07a-production [incomplete reviewed WA-07a production delta]
PASS: ignored-untracked-production [untracked production sources]
PASS: forged-59d-absorbs-wa07a [59d reviewed production inventory differs]
PASS: forged-59d-absorbs-59f-only [59d reviewed production inventory differs]
PASS: forged-59d-absorbs-59e-only [59d reviewed production inventory differs]
PASS: changed-59e-only-production [reviewed production source hash differs]
PASS: changed-59f-only-production [reviewed production source hash differs]
PASS: changed-integration-certificate [59d/59f reviewed integration source changed]
PASS: changed-integration-replay [59d/59f reviewed integration source changed]
PASS: changed-integration-manifest [59d/59f reviewed integration manifest changed]
PASS: paired-integration-source-and-manifest [59d/59f reviewed integration manifest changed]
PASS: removed-integration-manifest [missing regular 59d/59f integration review]
PASS: unreviewed-production [59d reviewed production inventory differs]
PASS: changed-reviewed-production [59d reviewed production bytes changed]
PASS: dirty-reviewed-production [59d reviewed production bytes changed]
PASS: staged-reviewed-source-hidden-by-worktree [staged production sources]
PASS: unstaged-reviewed-source-with-updated-review [unstaged production sources]
PASS: removed-reviewed-production [59d reviewed production bytes changed]
PASS: changed-independent-oracle [sealed writer/checker changed]
PASS: changed-checker-restoration [missing/duplicate reviewed 59d checker restoration span]
PASS: changed-checker-outside [unreviewed source change outside 59f spans]
PASS: changed-signed-width-proof [missing/duplicate 59d signed-width span]
PASS: changed-signed-width-outside [sealed oracle/authority changed]
PASS: changed-signed-width-contract [59d signed-width restoration exceeds its five exact authority seams]
PASS: changed-60d-signed-width-restoration [missing/duplicate reviewed 59d checker restoration span]
PASS: changed-60e-signed-width-restoration [missing/duplicate reviewed 59d checker restoration span]
PASS: 7 positive and 26 exact negative inherited 60f source-scope cases

real	5m49.816s
user	3m46.612s
sys	1m53.707s
```

</details>

<details>
<summary>test-increment-59h-inherited-source-scope.py</summary>

Command: `python3 morphhdl/scripts/test-increment-59h-inherited-source-scope.py`

```text
PASS: current exact 59h delta and all inherited audits [accepted]
PASS: unreviewed suffix core/src/main/scala/spinal/core/Vec.scala [unreviewed source change outside 59h spans]
PASS: unreviewed suffix frontend/src/main/scala/morphhdl/frontend/NativeStructuralFrontend.scala [unreviewed source change outside 59h spans]
PASS: unreviewed suffix frontend/src/main/scala/spinal/core/ExternalAnalyzedStructuralPublisher.scala [unreviewed source change outside 59h spans]
PASS: unreviewed suffix morphhdl/contracts/increment-55-native-change-review.json [WA-08 source overlay: unreviewed bytes cannot enter historical projection: morphhdl/contracts/increment-55-native-change-review.json]
PASS: unreviewed suffix morphhdl/contracts/native-source-preservation.json [WA-08 source overlay: unreviewed bytes cannot enter historical projection: morphhdl/contracts/native-source-preservation.json]
PASS: unreviewed suffix morphhdl/scripts/check-increment-59c-source-review.py [or 60g publication spans: morphhdl/scripts/check-increment-59c-source-review.py]
PASS: unreviewed suffix morphhdl/scripts/check-increment-59f-source-scope.py [or 60g publication spans: morphhdl/scripts/check-increment-59f-source-scope.py]
PASS: unreviewed suffix morphhdl/scripts/check-increment-60f-artifacts.py [WA-08 source overlay: unreviewed bytes cannot enter historical projection: morphhdl/scripts/check-increment-60f-artifacts.py]
PASS: unreviewed suffix morphhdl/scripts/check-increment-60f-equivalence-closure.py [WA-08 source overlay: unreviewed bytes cannot enter historical projection: morphhdl/scripts/check-increment-60f-equivalence-closure.py]
PASS: unreviewed suffix morphhdl/scripts/test-increment-59c-inherited-source-scope.py [WA-08 source overlay: unreviewed bytes cannot enter historical projection: morphhdl/scripts/test-increment-59c-inherited-source-scope.py]
PASS: unreviewed suffix morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala [WA-08 source overlay: unreviewed bytes cannot enter historical projection: morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala]
PASS: unreviewed suffix morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala [WA-08 source overlay: unreviewed bytes cannot enter historical projection: morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala]
PASS: unreviewed suffix morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogVecs.scala [WA-08 source overlay: unreviewed bytes cannot enter historical projection: morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogVecs.scala]
PASS: unreviewed suffix morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala [unreviewed source change outside 59h spans]
PASS: unreviewed suffix morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCapture.scala [unreviewed source change outside 59h spans]
PASS: unreviewed suffix morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeCallbackPolicy.scala [unreviewed source change outside 59h spans]
PASS: unreviewed suffix morphruntime/src/main/scala/spinal/core/ElabFiniteRange.scala [unreviewed source change outside 59h spans]
PASS: unreviewed suffix morphruntime/src/main/scala/spinal/core/ParameterizedStructure.scala [unreviewed source change outside 59h spans]
PASS: changed reviewed owner span [missing/changed 59h reviewed source span]
PASS: missing owner implementation [59h reviewed source is missing]
PASS: removed review [59h source-review checker or contract is missing]
PASS: paired production and review mutation [59h reviewed source manifest changed]
PASS: unreviewed production root [WA-08 source overlay: staged, unstaged or untracked governed content: ['foreign/src/main/Unreviewed.scala']]
PASS: staged hidden owner change [WA-08 source overlay: staged, unstaged or untracked governed content: ['morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala']]
PASS: changed native printer [WA-08 source overlay: staged, unstaged or untracked governed content: ['core/src/main/scala/spinal/core/internals/VerilogBase.scala']]
PASS: changed sealed oracle [WA-08 source overlay: unreviewed bytes cannot enter historical projection: morphhdl/src/test/scala/nativeapplication/SIntSignedVerilogBaselineFixture.scala]
PASS: changed inherited 59e source [WA-08 source overlay: staged, unstaged or untracked governed content: ['morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala']]
PASS: completed 59c without 59h exceptions [accepted]
PASS: historical 59d source remains frozen [59d reviewed production bytes changed]
PASS: current complete 59h source audits; separately scoped unchanged 59c controls
PASS: current 59c production and reviewed metadata [accepted]
PASS: qualified 60f without successor exceptions [accepted]
PASS: historical unreviewed new production root [untracked production sources]
PASS: isolated exact reviewed successor [accepted]
PASS: current 59b source audit and separately recorded frozen historical contract controls
PASS: paired-named-source-and-review [59c reviewed source manifest changed]
PASS: changed-60e-restore-branch [missing/changed 59c reviewed source span]
PASS: changed-59f-restore-branch [missing/changed 59c reviewed source span]
PASS: changed-60f-restore-branch [missing/changed 59c reviewed source span]
PASS: changed-60f-inventory-branch [missing/changed 59c reviewed source span]
PASS: changed-inherited-59d-production [59d reviewed production bytes changed]
PASS: changed-inherited-59e-production [reviewed production source hash differs]
PASS: changed-inherited-59f-production [reviewed production source hash differs]
PASS: changed-core [unreviewed source change outside 60e spans]
PASS: changed-added-helper [unreviewed source change outside 60e spans]
PASS: changed-in-span [missing/changed 59c reviewed source span]
PASS: unreviewed-production [native signed declaration/cast hooks changed after their frozen qualification]
PASS: new-production-root [unreviewed production delta]
PASS: untracked-production [untracked production sources]
PASS: removed-production [59c reviewed source is missing]
PASS: changed-60e-adapter [unreviewed source change outside 60e spans]
PASS: changed-sealed-oracle [sealed writer/checker changed]
PASS: changed-sealed-checker [sealed writer/checker changed]
PASS: removed-review [59c source-review checker or contract is missing]
PASS: forged-added-baseline [59c source-review changed its explicit added-file inventory]
59c current-source controls PASS: two positives and 20 exact rejections; historical 60f/59b controls separately scoped
PASS: unchanged completed 59h controls independently replayed at 64e8fddc432e859b6b532540bee96c5608d46efa
59h current-source controls PASS: two positives and 28 exact rejections; unchanged 59c historical controls separately scoped

real	9m28.075s
user	5m31.750s
sys	3m47.749s
```

</details>

## Target integration and refreshed qualification on 2026-09-14

The published repair head `53513dce76061a9d10a044c470cd8a71494f0620`
completed its applicable GitHub CI. Every workflow page, actual job page and
check-run page was enumerated for that exact head: 50 workflows (41 successes,
nine historical branch skips) and 224 jobs/checks (117 successes, 107 justified
skips). There were no failures, unfinished jobs or source/run-ID mismatches.
The skips comprise 42 jobs in historical branch-excluded workflows, 64
descendants of eight historical routing jobs, and one push-only baseline
publication. Routing successes do not provide compilation or proof credit.

Both widening and nested-owner Scala lanes passed. The pass aggregate
[job 103773004814](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750264946/job/103773004814)
recorded `WA07A_FULL_DOMAIN_PASS: 11 passes, 512 bindings each, both repeated runs`
at 2026-09-13 20:15:21 UTC after all 16 shards passed.

Before merge, the target advanced to
`3d582c281975732b32fbf6fafef9afb5549223d9` (merged WA-11). Its normalization,
production source, native review manifests, exact 2,012-test / 197-suite combined
catalog, disabled-output successor assertions and boundary restrictions are
preserved byte-for-byte. The earlier 1,996/195 catalog statements above describe
the original WA-10-only repair qualification; they do not replace the combined
WA-10/WA-11 obligations.

The combined source merge is
`2be698ca8a4d80c2d918b154b9bd13221195cff3`, with parents `53513dce` and
`3d582c28`. Its authoritative fetched source seal is
`dfd654dfa7a68db4d5b5d3d40364575881fc0471`. The cumulative overlay authenticates
116 files. Its manifest SHA-256 is
`42a35e01a0d3ebb82b58a000a952a6c95519b1c605045743f28ec50c3569a8bc`.
The overlay helper behavior and immutable baseline are unchanged.

WA-11 had correctly frozen the original WA-10 path inventory at `dab690bf`.
This repair retains that original anchor and adds the exact reviewed repair
anchor `14621a6e`, requiring the ancestry chain predecessor → original source →
repair source → HEAD. Its unchanged 191-path repair inventory is compared to
the exact repair anchor; the outer seal verifies the entire current combined
tree. The existing signature-recording script refreshed only the scope-helper
digest, preserving all 98 formal-registry paths and other file digests.

The PR ancestry review referred to a different `ad3ab74f` commit. Actual Git
checks show `14621a6e` and `dab690bf` are ancestors of both the published
`53513dce` and the new `dfd654df` seal. No ancestry requirement was removed.
Final integration must still use an ordinary merge, preserving these commits.

### Complete qualified old-head workflow inventory

These results qualify only `53513dce`, not the combined publication head.

| Workflow | Conclusion | Successful jobs | Justified skips |
| --- | --- | ---: | ---: |
| [Increment 53d typed StreamWidthAdapter](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750264951) | skipped | 0 | 6 |
| [Increment 53e typed StreamFifo](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265068) | skipped | 0 | 8 |
| [Increment 53f typed primitive closure](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265059) | skipped | 0 | 8 |
| [Increment 53g production retirement](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265082) | skipped | 0 | 8 |
| [Increment 54 typed layering and canonical IR](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265081) | skipped | 0 | 8 |
| [Increment 55 concrete compatibility and approved-native-change audit](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265043) | success | 1 | 9 |
| [Increment 56 native typed library-call surface](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265049) | success | 1 | 8 |
| [Increment 57 broad native library migration and proof](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750264984) | success | 1 | 8 |
| [Increment 57a typed native StreamFifoCC depth and CDC proof](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265036) | success | 1 | 8 |
| [Increment 57b StreamFifoCC payload-width formal proof](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265026) | success | 1 | 3 |
| [Increment 58 legacy adapter and shadow-path retirement](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265053) | success | 1 | 9 |
| [Increment 59 typed BlackBox parameter and generic binding](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750264991) | success | 1 | 9 |
| [Increment 59a bounded recursive Verilog module](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750264954) | success | 1 | 10 |
| [Increment 59b inherited source-scope controls](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265077) | skipped | 0 | 1 |
| [Increment 59b native operator replay formal proof](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265086) | skipped | 0 | 1 |
| [Increment 59b native replay and oracle checkpoint](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265124) | skipped | 0 | 1 |
| [Increment 59c named field vectors](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265063) | success | 2 | 0 |
| [Increment 59d generic scalar width and widening qualification](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265054) | success | 2 | 0 |
| [Increment 59e composite balanced reductions](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265018) | success | 2 | 0 |
| [Increment 59g native register bridge qualification](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265013) | success | 2 | 0 |
| [Increment 59h nested typed reduction owners](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265017) | success | 2 | 0 |
| [Increment 60a SInt baseline capture](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750264963) | success | 2 | 1 |
| [Increment 60b typed signedness authority](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265012) | skipped | 0 | 1 |
| [Increment 60c signed declarations](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750264969) | success | 2 | 0 |
| [Increment 60d pure SInt casts](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265024) | success | 2 | 0 |
| [Increment 60e signedness boundaries](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265027) | success | 2 | 0 |
| [Increment 60f equivalence closure](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750264966) | success | 5 | 0 |
| [Increment 60g default signed Verilog](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265096) | success | 4 | 0 |
| [Increment 62 WA-08 inherited workflow closure](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265095) | success | 4 | 0 |
| [MorphHDL IR pass workspace](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750264946) | success | 24 | 0 |
| [MorphHDL Mill](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750264996) | success | 2 | 0 |
| [MorphHDL SCREAMING_SNAKE_CASE module-local SpinalEnum parameters and formal equivalence](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750264964) | success | 3 | 0 |
| [MorphHDL StreamFifoCC CDC and formal proof](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265051) | success | 2 | 0 |
| [MorphHDL baseline](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750264976) | success | 3 | 0 |
| [MorphHDL external SpinalHDL boundary](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750264948) | success | 2 | 0 |
| [MorphHDL external native Int formalization](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265040) | success | 3 | 0 |
| [MorphHDL external native memory](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750264959) | success | 3 | 0 |
| [MorphHDL external structural and process capture](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265047) | success | 3 | 0 |
| [MorphHDL external symbolic width](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750264985) | success | 2 | 0 |
| [MorphHDL native AXI4 Slave Factory formal equivalence](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750264994) | success | 3 | 0 |
| [MorphHDL native AXI4 Slave Factory parameterized offsets](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265041) | success | 3 | 0 |
| [MorphHDL native Int nested symbolic control flow](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265057) | success | 3 | 0 |
| [MorphHDL native Int shadow expressions](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750264998) | success | 3 | 0 |
| [MorphHDL native Int shadow provenance](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265056) | success | 3 | 0 |
| [MorphHDL native Int symbolic conditionals](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265011) | success | 3 | 0 |
| [MorphHDL native StreamFifo formal equivalence](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265094) | success | 3 | 0 |
| [MorphHDL native StreamFifo parameter structure](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265087) | success | 3 | 0 |
| [MorphHDL native StreamWidthAdapter parameterization](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750265046) | success | 3 | 0 |
| [MorphHDL native source guard](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750264928) | success | 1 | 0 |
| [MorphHDL typed elaboration values](https://github.com/pysolvesemi/MorphHDL/actions/runs/34750264990) | success | 3 | 0 |

### Combined-head required workflow inventory

The actual 12-path repair diff against incorporated target `3d582c28` triggers
51 PR workflows: 34 qualification workflows, eight historical routing
workflows and nine historical branch skips. The new WA-11 workflow runs both
Scala normalization lanes and its cross-Scala artifact check. All matrices
must still be enumerated dynamically for the final published head.

| Expected workflow file | Classification |
| --- | --- |
| `.github/workflows/increment-53d-typed-streamwidthadapter.yml` | historical branch skip |
| `.github/workflows/increment-53e-typed-streamfifo.yml` | historical branch skip |
| `.github/workflows/increment-53f-typed-primitives.yml` | historical branch skip |
| `.github/workflows/increment-53g-production-retirement.yml` | historical branch skip |
| `.github/workflows/increment-54-typed-layering-ir.yml` | historical branch skip |
| `.github/workflows/increment-55-concrete-compatibility-audit.yml` | historical route only |
| `.github/workflows/increment-56-native-typed-library-surface.yml` | historical route only |
| `.github/workflows/increment-57-broad-native-library-migration.yml` | historical route only |
| `.github/workflows/increment-57a-typed-streamfifocc.yml` | historical route only |
| `.github/workflows/increment-57b-streamfifocc-payload-width-formal.yml` | historical route only |
| `.github/workflows/increment-58-legacy-retirement.yml` | historical route only |
| `.github/workflows/increment-59-typed-blackbox-generics.yml` | historical route only |
| `.github/workflows/increment-59a-recursive-verilog-module.yml` | historical route only |
| `.github/workflows/increment-59b-inherited-source-scope.yml` | historical branch skip |
| `.github/workflows/increment-59b-operator-replay.yml` | historical branch skip |
| `.github/workflows/increment-59b-reduce-balanced-tree.yml` | historical branch skip |
| `.github/workflows/increment-59c-named-field-vectors.yml` | required qualification |
| `.github/workflows/increment-59d-widening.yml` | required qualification |
| `.github/workflows/increment-59e-composite-reduction.yml` | required qualification |
| `.github/workflows/increment-59g-register-bridges.yml` | required qualification |
| `.github/workflows/increment-59h-nested-owners.yml` | required qualification |
| `.github/workflows/increment-60a-sint-baseline.yml` | required qualification |
| `.github/workflows/increment-60b-signedness-authority.yml` | historical branch skip |
| `.github/workflows/increment-60c-signed-declarations.yml` | required qualification |
| `.github/workflows/increment-60d-pure-sint-casts.yml` | required qualification |
| `.github/workflows/increment-60e-signedness-boundaries.yml` | required qualification |
| `.github/workflows/increment-60f-equivalence-closure.yml` | required qualification |
| `.github/workflows/increment-60g-default-signed-verilog.yml` | required qualification |
| `.github/workflows/increment-62-wa08-source-overlay.yml` | required qualification |
| `.github/workflows/morphhdl-baseline.yml` | required qualification |
| `.github/workflows/morphhdl-enum-localparams.yml` | required qualification |
| `.github/workflows/morphhdl-external-boundary.yml` | required qualification |
| `.github/workflows/morphhdl-external-formalization.yml` | required qualification |
| `.github/workflows/morphhdl-external-memory.yml` | required qualification |
| `.github/workflows/morphhdl-external-structural-process.yml` | required qualification |
| `.github/workflows/morphhdl-external-symbolic-width.yml` | required qualification |
| `.github/workflows/morphhdl-mill.yml` | required qualification |
| `.github/workflows/morphhdl-native-axi4-slave-factory-formal-equivalence.yml` | required qualification |
| `.github/workflows/morphhdl-native-axi4-slave-factory-offsets.yml` | required qualification |
| `.github/workflows/morphhdl-native-int-nested-control-flow.yml` | required qualification |
| `.github/workflows/morphhdl-native-int-shadow-expressions.yml` | required qualification |
| `.github/workflows/morphhdl-native-int-shadow-provenance.yml` | required qualification |
| `.github/workflows/morphhdl-native-int-symbolic-conditionals.yml` | required qualification |
| `.github/workflows/morphhdl-native-source-guard.yml` | required qualification |
| `.github/workflows/morphhdl-native-stream-width-adapter.yml` | required qualification |
| `.github/workflows/morphhdl-native-streamfifo-formal-equivalence.yml` | required qualification |
| `.github/workflows/morphhdl-native-streamfifo-structure.yml` | required qualification |
| `.github/workflows/morphhdl-passes.yml` | required qualification |
| `.github/workflows/morphhdl-streamfifocc-proof.yml` | required qualification |
| `.github/workflows/morphhdl-typed-elaboration-values.yml` | required qualification |
| `.github/workflows/wa11-symbolic-boolean-width.yml` | required qualification |

### Local verification on the combined source seal

Every command below ran against exact clean seal `dfd654df` and exited zero.
The full inherited replays retain their historical fixtures and exact rejection
requirements. No production compilation or HDL proof is claimed from the local
source/validator checks. All 144 currently tracked WA-10 evidence files have
identical SHA-256 values to qualified repair head `53513dce`; all 131 entries
in the original artifact manifests and four saved-log identity hashes pass.

| Check | Result | Elapsed seconds |
| --- | --- | ---: |
| Caller regressions | 14 passed | 0.025 (unit test time) |
| Outer overlay | 116 files; 132 live mutation rejections | 6.985 (mutations) |
| WA-10 exact scope | 191 paths; 12 mutation controls | 0.032 (mutations) |
| WA-10 safety | Positive and 116 mutation controls passed | 1.172 (mutations) |
| Repair boundary | All 12 changed paths accepted | 1.171 |
| Boundary regression guard | All accepted/rejected branch cases passed | 19.013 |
| Formal signature validator | All 98 entries and validator controls passed | 0.117 |
| 60f exact catalog | 2,012 cases / 197 suites; 4,089 rejection controls | 135.991 |
| Full 59b replay | Current audit plus 6 historical positives / 19 exact rejections | 169.172 |
| Full 59d replay | Current audit plus 7 historical positives / 26 exact rejections | 183.773 |
| Full 59h replay | 2 positives / 28 exact rejections; unchanged 59c and pre-rollout 59h replays | 307.080 |

<details>
<summary>scope-self-test</summary>

Command: `python3 morphhdl/scripts/check-wa10-source-scope.py --self-test`

Log SHA-256: `fd587833e4e845d132e2f6759bf2b632007ef13ce857e866360639740ed2d9f3`.

```text
WA10_SCOPE_MUTATIONS_PASS controls=12
```

</details>

<details>
<summary>wa10-safety-self-test</summary>

Command: `python3 morphhdl-passes/scripts/check-wa10-general-expression.py --self-test`

Log SHA-256: `f3e4a791c93c7c398b84e9e14f2e43fd3b3f813a78a0d38a5cf881e18e409c40`.

```text
WA10_CURRENT_CONTRACT_MUTATIONS_PASS controls=116
```

</details>

<details>
<summary>repair-boundary</summary>

Command: `MORPHDL_PASSES_HEAD_REF=agent/wa-10-inherited-audit-timeout MORPHDL_PASSES_BASE_SHA=3d582c281975732b32fbf6fafef9afb5549223d9 bash morphhdl-passes/scripts/check-boundary.sh`

Log SHA-256: `4e2a7295ea4d8033fc806d54bacdff0afc95b83abf280f604faefe7703292a6c`.

```text
WA08_SOURCE_OVERLAY_PASS files=116
MorphHDL pass boundary accepted 12 changed path(s).
```

</details>

<details>
<summary>boundary-guard</summary>

Command: `bash morphhdl-passes/scripts/test-boundary-guard.sh`

Log SHA-256: `6230a4c23f752355401e18fec7b42edf07becfbb850114e8085dd7ce44a4fbe4`.

```text
MorphHDL pass boundary self-tests passed.
```

</details>

<details>
<summary>signature-self-test</summary>

Command: `python3 morphhdl-passes/scripts/validate_wire_assignment_equivalence.py --self-test`

Log SHA-256: `ef5dbf0d8b539e4b2da28b6435196bf7f04077adb797b26fa22ee7b9b8fa47de`.

```text
WA-03 equivalence validator self-tests passed.
```

</details>

<details>
<summary>60f-catalog-self-test</summary>

Command: `python3 morphhdl/scripts/check-increment-60f-artifacts.py self-test`

Log SHA-256: `106b64edb7c0ab1692719d73b60fba9182fdf6f2c23227750ea02e07bc3aa0d6`.

```text
60g/59h combined inventory retains nested owners and exact rollout test counts PASS
60g/59g/59h combined inventory preserves every register, nested and rollout obligation PASS
WA-08 inventory retains every inherited suite and requires its exact three-case verified addition PASS
WA-09 inventory requires exactly 1982 cases / 194 suites: 1115 Morph, 159 pass, 21 core cases PASS
WA-10 successor inventory requires exactly 1996 cases / 195 suites: 1119 Morph, 160 pass, 30 core cases PASS
Combined WA-10/WA-11 inventory requires exactly 2012 cases / 197 suites with every predecessor and successor case PASS
60f inventory self-test: inherited exact source profiles, named/register/nested suite extensions and 4089 rejection controls PASS
```

</details>

<details>
<summary>morphhdl/scripts/test-increment-62-wa08-source-overlay.py</summary>

Command: `python3 morphhdl/scripts/test-increment-62-wa08-source-overlay.py`

Log SHA-256: `72454e9c29a42a0b49e7159ad2587e81bbf329377a4282bed669435effd6ceb4`.

```text
WA08_OVERLAY_MUTATIONS_PASS rejected=132
```

</details>

<details>
<summary>morphhdl/scripts/test-increment-59b-inherited-source-scope.py</summary>

Command: `python3 morphhdl/scripts/test-increment-59b-inherited-source-scope.py`

Log SHA-256: `a264194007fa6e4c368d18386344f9f51db4074e5bed154ca8a3e463bf930704`.

```text
PASS: current descendant through complete 59c and inherited source audits [accepted]
PASS: current complete source audits; separately scoped historical contract controls
PASS: current descendant with qualified history and approved native source [accepted]
PASS: historical [accepted]
PASS: historical-60d [accepted]
PASS: historical-60e [accepted]
PASS: historical-59b [accepted]
PASS: historical-combined-60f [accepted]
PASS: changed-hook [native signed declaration/cast hooks changed after their frozen qualification]
PASS: changed-printer [native signed declaration/cast hooks changed after their frozen qualification]
PASS: unapproved-path [MORPH-NATIVE-AUDIT-UNAPPROVED-PATH]
PASS: dirty-extension [MORPH-NATIVE-AUDIT-DIRTY-WORKTREE]
PASS: changed-boundary-printer [native signed declaration/cast hooks changed after their frozen qualification]
PASS: changed-vec [unreviewed source change outside 60e spans]
PASS: current-changed-hook [native signed declaration/cast hooks changed after their frozen qualification]
PASS: current-changed-printer [native signed declaration/cast hooks changed after their frozen qualification]
PASS: current-unapproved-path [MORPH-NATIVE-AUDIT-UNAPPROVED-PATH]
PASS: current-dirty-extension [MORPH-NATIVE-AUDIT-DIRTY-WORKTREE]
PASS: current-changed-boundary-printer [native signed declaration/cast hooks changed after their frozen qualification]
PASS: current-changed-width-fallback-hook [missing/duplicate reviewed 59d restoration span]
PASS: current-changed-width-fallback-resize [missing/duplicate reviewed 59d restoration span]
PASS: current-changed-width-fallback-domain [missing/duplicate reviewed 59d restoration span]
PASS: current-changed-width-fallback-session [missing/duplicate reviewed 59d restoration span]
PASS: current-changed-width-fallback-single-driver [missing/duplicate reviewed 59d restoration span]
PASS: current-changed-width-fallback-publication-width [missing/duplicate reviewed 59d restoration span]
PASS: current-changed-width-fallback-width-matcher [missing/duplicate reviewed 59d restoration span]
PASS: current-changed-width-fallback-outside [unreviewed source change outside 59d/59e publisher spans]
PASS: 6 positive and 19 exact negative inherited source-scope cases
```

</details>

<details>
<summary>morphhdl/scripts/test-increment-59d-inherited-60f-scope.py</summary>

Command: `python3 morphhdl/scripts/test-increment-59d-inherited-60f-scope.py`

Log SHA-256: `b135c96aec560029e66e78c0d143efe13f551c491fc485437779ee6c40b15069`.

```text
PASS: current descendant through complete 59c and inherited source audits [accepted]
PASS: current complete source audits; separately scoped historical contract controls
PASS: working reviewed descendant [accepted]
PASS: historical-60f [accepted]
PASS: historical-reviewed-59d [accepted]
PASS: historical-reviewed-59f [accepted]
PASS: historical-reviewed-59e [accepted]
PASS: historical-profile-aware-60f [accepted]
PASS: committed-reviewed-descendant [accepted]
PASS: partial-wa07a-production [incomplete reviewed WA-07a production delta]
PASS: ignored-untracked-production [untracked production sources]
PASS: forged-59d-absorbs-wa07a [59d reviewed production inventory differs]
PASS: forged-59d-absorbs-59f-only [59d reviewed production inventory differs]
PASS: forged-59d-absorbs-59e-only [59d reviewed production inventory differs]
PASS: changed-59e-only-production [reviewed production source hash differs]
PASS: changed-59f-only-production [reviewed production source hash differs]
PASS: changed-integration-certificate [59d/59f reviewed integration source changed]
PASS: changed-integration-replay [59d/59f reviewed integration source changed]
PASS: changed-integration-manifest [59d/59f reviewed integration manifest changed]
PASS: paired-integration-source-and-manifest [59d/59f reviewed integration manifest changed]
PASS: removed-integration-manifest [missing regular 59d/59f integration review]
PASS: unreviewed-production [59d reviewed production inventory differs]
PASS: changed-reviewed-production [59d reviewed production bytes changed]
PASS: dirty-reviewed-production [59d reviewed production bytes changed]
PASS: staged-reviewed-source-hidden-by-worktree [staged production sources]
PASS: unstaged-reviewed-source-with-updated-review [unstaged production sources]
PASS: removed-reviewed-production [59d reviewed production bytes changed]
PASS: changed-independent-oracle [sealed writer/checker changed]
PASS: changed-checker-restoration [missing/duplicate reviewed 59d checker restoration span]
PASS: changed-checker-outside [unreviewed source change outside 59f spans]
PASS: changed-signed-width-proof [missing/duplicate 59d signed-width span]
PASS: changed-signed-width-outside [sealed oracle/authority changed]
PASS: changed-signed-width-contract [59d signed-width restoration exceeds its five exact authority seams]
PASS: changed-60d-signed-width-restoration [missing/duplicate reviewed 59d checker restoration span]
PASS: changed-60e-signed-width-restoration [missing/duplicate reviewed 59d checker restoration span]
PASS: 7 positive and 26 exact negative inherited 60f source-scope cases
```

</details>

<details>
<summary>morphhdl/scripts/test-increment-59h-inherited-source-scope.py</summary>

Command: `python3 morphhdl/scripts/test-increment-59h-inherited-source-scope.py`

Log SHA-256: `eb2d13b3e53018df6f44f8720825da556fe953fb970e0d1c43f89e531a159440`.

```text
PASS: current exact 59h delta and all inherited audits [accepted]
PASS: unreviewed suffix core/src/main/scala/spinal/core/Vec.scala [unreviewed source change outside 59h spans]
PASS: unreviewed suffix frontend/src/main/scala/morphhdl/frontend/NativeStructuralFrontend.scala [unreviewed source change outside 59h spans]
PASS: unreviewed suffix frontend/src/main/scala/spinal/core/ExternalAnalyzedStructuralPublisher.scala [unreviewed source change outside 59h spans]
PASS: unreviewed suffix morphhdl/contracts/increment-55-native-change-review.json [WA-08 source overlay: unreviewed bytes cannot enter historical projection: morphhdl/contracts/increment-55-native-change-review.json]
PASS: unreviewed suffix morphhdl/contracts/native-source-preservation.json [WA-08 source overlay: unreviewed bytes cannot enter historical projection: morphhdl/contracts/native-source-preservation.json]
PASS: unreviewed suffix morphhdl/scripts/check-increment-59c-source-review.py [or 60g publication spans: morphhdl/scripts/check-increment-59c-source-review.py]
PASS: unreviewed suffix morphhdl/scripts/check-increment-59f-source-scope.py [or 60g publication spans: morphhdl/scripts/check-increment-59f-source-scope.py]
PASS: unreviewed suffix morphhdl/scripts/check-increment-60f-artifacts.py [WA-08 source overlay: unreviewed bytes cannot enter historical projection: morphhdl/scripts/check-increment-60f-artifacts.py]
PASS: unreviewed suffix morphhdl/scripts/check-increment-60f-equivalence-closure.py [WA-08 source overlay: unreviewed bytes cannot enter historical projection: morphhdl/scripts/check-increment-60f-equivalence-closure.py]
PASS: unreviewed suffix morphhdl/scripts/test-increment-59c-inherited-source-scope.py [WA-08 source overlay: unreviewed bytes cannot enter historical projection: morphhdl/scripts/test-increment-59c-inherited-source-scope.py]
PASS: unreviewed suffix morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala [WA-08 source overlay: unreviewed bytes cannot enter historical projection: morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala]
PASS: unreviewed suffix morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala [WA-08 source overlay: unreviewed bytes cannot enter historical projection: morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala]
PASS: unreviewed suffix morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogVecs.scala [WA-08 source overlay: unreviewed bytes cannot enter historical projection: morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogVecs.scala]
PASS: unreviewed suffix morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala [unreviewed source change outside 59h spans]
PASS: unreviewed suffix morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCapture.scala [unreviewed source change outside 59h spans]
PASS: unreviewed suffix morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeCallbackPolicy.scala [unreviewed source change outside 59h spans]
PASS: unreviewed suffix morphruntime/src/main/scala/spinal/core/ElabFiniteRange.scala [unreviewed source change outside 59h spans]
PASS: unreviewed suffix morphruntime/src/main/scala/spinal/core/ParameterizedStructure.scala [unreviewed source change outside 59h spans]
PASS: changed reviewed owner span [missing/changed 59h reviewed source span]
PASS: missing owner implementation [59h reviewed source is missing]
PASS: removed review [59h source-review checker or contract is missing]
PASS: paired production and review mutation [59h reviewed source manifest changed]
PASS: unreviewed production root [WA-08 source overlay: staged, unstaged or untracked governed content: ['foreign/src/main/Unreviewed.scala']]
PASS: staged hidden owner change [WA-08 source overlay: staged, unstaged or untracked governed content: ['morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionBackend.scala']]
PASS: changed native printer [WA-08 source overlay: staged, unstaged or untracked governed content: ['core/src/main/scala/spinal/core/internals/VerilogBase.scala']]
PASS: changed sealed oracle [WA-08 source overlay: unreviewed bytes cannot enter historical projection: morphhdl/src/test/scala/nativeapplication/SIntSignedVerilogBaselineFixture.scala]
PASS: changed inherited 59e source [WA-08 source overlay: staged, unstaged or untracked governed content: ['morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCompositeReplay.scala']]
PASS: completed 59c without 59h exceptions [accepted]
PASS: historical 59d source remains frozen [59d reviewed production bytes changed]
PASS: current complete 59h source audits; separately scoped unchanged 59c controls
PASS: current 59c production and reviewed metadata [accepted]
PASS: qualified 60f without successor exceptions [accepted]
PASS: historical unreviewed new production root [untracked production sources]
PASS: isolated exact reviewed successor [accepted]
PASS: current 59b source audit and separately recorded frozen historical contract controls
PASS: paired-named-source-and-review [59c reviewed source manifest changed]
PASS: changed-60e-restore-branch [missing/changed 59c reviewed source span]
PASS: changed-59f-restore-branch [missing/changed 59c reviewed source span]
PASS: changed-60f-restore-branch [missing/changed 59c reviewed source span]
PASS: changed-60f-inventory-branch [missing/changed 59c reviewed source span]
PASS: changed-inherited-59d-production [59d reviewed production bytes changed]
PASS: changed-inherited-59e-production [reviewed production source hash differs]
PASS: changed-inherited-59f-production [reviewed production source hash differs]
PASS: changed-core [unreviewed source change outside 60e spans]
PASS: changed-added-helper [unreviewed source change outside 60e spans]
PASS: changed-in-span [missing/changed 59c reviewed source span]
PASS: unreviewed-production [native signed declaration/cast hooks changed after their frozen qualification]
PASS: new-production-root [unreviewed production delta]
PASS: untracked-production [untracked production sources]
PASS: removed-production [59c reviewed source is missing]
PASS: changed-60e-adapter [unreviewed source change outside 60e spans]
PASS: changed-sealed-oracle [sealed writer/checker changed]
PASS: changed-sealed-checker [sealed writer/checker changed]
PASS: removed-review [59c source-review checker or contract is missing]
PASS: forged-added-baseline [59c source-review changed its explicit added-file inventory]
59c current-source controls PASS: two positives and 20 exact rejections; historical 60f/59b controls separately scoped
PASS: unchanged completed 59h controls independently replayed at 64e8fddc432e859b6b532540bee96c5608d46efa
59h current-source controls PASS: two positives and 28 exact rejections; unchanged 59c historical controls separately scoped
```

</details>

The documentation-only publication commit following this seal records these
results without changing source anchors or governed bytes. Its new exact-head
CI must pass all applicable workflow and job gates before ordinary merge.
Do not reuse the successful old-head `53513dce` results as new-head passes.
