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

### Final target advance incorporated without source changes

Immediately before publication, the target advanced again to
`3b547ae5622ae212c17f6e67cc96924127a46a41`. Its source commit
`23ce1f0d56bb8f3505fcca14068a23914a45f6ca` independently applied the same
59b/59d current-positive 600-second budgets and shared workflow path triggers.
Python AST comparison proves both target caller implementations are identical
to this repair; workflow byte comparison after removing only this repair's
additional regression-test lines and exact repair-branch clause matches both
target workflows. No target source behavior or workflow trigger is discarded.

Merge `a852bbabb97d6f3c270fc0a957fe77e119b8632a` incorporates that target with
the **identical full Git tree** as documentation head `7a16ec51`:
`2433e64f10bb439ec755ecbf8400d2bd7f201d1e`. Therefore the reviewed source
`2be698ca` and seal `dfd654df` remain unchanged, valid ancestors; all governed
bytes are exactly those exercised by the complete local replays above. The
latest target and both of its new commits are preserved in HEAD ancestry.
This final documentation-only record does not alter the source seal.

The publication diff against latest target `3b547ae5` still contains exactly
12 repair paths; the expected 51-workflow inventory above is unchanged. Fresh
CI must qualify the actual final publication SHA before ordinary merge.

## Native proof diagnostic capture follow-up (2026-09-14)

The user reported [workflow 34801491957](https://github.com/pysolvesemi/MorphHDL/actions/runs/34801491957)
on the actual PR head `48759afadd3f03e8166be89f85355426bbaaba02`.
Its source-boundary and Scala 2.12.18 jobs passed. The Scala 2.13.12
[job 103848075437](https://github.com/pysolvesemi/MorphHDL/actions/runs/34801491957/job/103848075437)
failed in the existing `GenericExpressionAndStreamTests` mutation control,
after the normal native/legacy equivalence checks passed. This was a source
bug in diagnostic capture, not a download failure or permission to ignore a
failed proof gate.

The deliberately changed `connected := right` candidate was rejected by
Yosys 0.41: all eight `connected` bits remained unproven, while the other
57 equivalence cells proved. The test correctly requires the resulting
exception to contain `equiv_status -assert`. That diagnostic was corrupted:
the retrieved job log contains 92 NUL bytes, and the stderr suffix was torn
into an unrelated stdout line. Relevant unmodified log text is:

```text
Presumably equivalent wires: connected_gold (\left_gold), connected_gate (\right_gate) -> connected
Proved 57 previously unproven $equiv cells.
Found 8 unproven $equiv cells in module equiv:_status -assert'.
16. Executing EQUIV_STATUS pass.
" did not contain "equiv_status -assert"
```

`NativeWireCompatibility.run` previously appended stdout and stderr through
two `ProcessLogger` reader threads into one unsynchronized Scala
`StringBuilder`. The repair synchronizes each complete line plus newline.
The nonzero-exit requirement, exact real Yosys diagnostic assertion,
equivalence commands, simulation comparisons and four-state controls are
unchanged. No compiler, native algorithm, generated HDL or formal obligation
changed.

The same existing test case now also drives a real subprocess with parallel
stdout/stderr writers. Each stream writes 4,096 uniquely indexed, padded
lines, once with exit zero and once with exit seven. It checks every line
exactly once and in stream order, no NUL bytes, intact trailing newlines,
and the complete command diagnostic on failure. There are still exactly
nine cases in the suite and 2,012 tests / 197 suites in the combined catalog.
This subprocess regression is diagnostic evidence, not formal proof credit.

The exact regression method was extracted for standalone compilation of
the real helper on both Scala versions. The old helper from `48759af`
(visibility widened only for invocation) compiled but failed with NUL
corruption on both versions. The synchronized helper compiled and passed
all 16,384 expected lines across successful and rejected invocations:

```text
Scala 2.12.18 old: AssertionError: concurrent process output contains NULs for exit 0
Scala 2.12.18 fixed: CONCURRENT_CAPTURE_PASS statuses=0,7 streams=stdout,stderr lines_per_stream=4096 exact_total_lines=16384 seconds=0.309
Scala 2.13.12 old: AssertionError: concurrent process output contains NULs for exit 0
Scala 2.13.12 fixed: CONCURRENT_CAPTURE_PASS statuses=0,7 streams=stdout,stderr lines_per_stream=4096 exact_total_lines=16384 seconds=0.296
```

The two existing test files were already in the 116-file outer overlay but
outside the immutable 191-path WA-10 request inventory. The explicitly
authorized follow-up therefore admits exactly those two paths on
`agent/wa-10-inherited-audit-timeout`, only after the existing overlay,
scope and dependency checks succeed. Six added boundary controls retain
rejection of unrelated branches, an unenumerated test file and absent
source checks. The original 191 paths and both immutable source anchors
remain byte-for-byte unchanged. The 98-entry signature registry was refreshed
with the existing `check-wa09-named-expression.py --record-source-signatures`
command; only the two boundary-script digests changed, with all other
96 digests retained.

Reviewed repair source: `d73f78a3745f265484cc508789ac89c3a3fec994`.
Exact source tree: `38ff82c0562a1b9d9209b35a43f4605577e5e00b`.
Source seal: `b88e84169f226218f8af7815dda76872f8ed7605`.
Sealed tree: `8afc46617f07fa6137aebca0ad60690e25e3aecf`.
The outer manifest still contains 116 files and has SHA-256
`933bdb973a0b346a4385a767ce177ec96d399456775b5d9694301e9feae584af`.
Both source and seal were created as authoritative GitHub objects, fetched
and tree-verified before any branch publication. All earlier source anchors
and latest target `3b547ae5` remain ancestors. All 144 tracked WA-10 evidence
files remain byte-identical to `48759af`.

Before this repair, complete pagination found 51 workflows and 226
materialized jobs/checks for `48759af`: 99 successful jobs, 107 justified
skips, 14 queued, five running and this single failure. Every job head and
run ID matched and the check-run ID set exactly matched the jobs; commit
statuses were empty. The 107 skips comprise 42 historical branch jobs,
64 downstream historical-routing jobs and one push-only publication job.
Eight router successes earn no proof credit. The pending proof aggregation
had not yet materialized, so 226 is not a final expected job count.

The final publication head requires a fresh complete current-head inventory,
including both repaired Scala lanes, WA-10 and WA-11 production/cross-Scala
lanes, all inherited gates, all 16 full-domain proof shards and aggregation.
The 11 pass identities, 512 parameter bindings and two actual proof runs,
equivalence/reachability and mutation controls remain mandatory. No merge
is authorized by the old-head results.

### Diagnostic-capture repair: sealed local validation

All eleven commands below returned exit code zero on the authoritative sealed tree `b88e84169f226218f8af7815dda76872f8ed7605` on 2026-09-14. Durations are measured elapsed wall time for each invocation; independent local checks ran concurrently. The source, scope, catalog and validator controls are local audit evidence. Fresh production Scala, simulation and formal qualification remains required for the final published head.

| Check | Exact command | Result | Seconds | Complete log SHA-256 |
| --- | --- | --- | ---: | --- |
| overlay-positive | `python3 morphhdl/scripts/check-increment-62-wa08-source-overlay.py` | PASS: 116 governed files authenticated | 0.592 | `409715bc2e779def3ba41dbce6c49fa916f0370c8698b90b58cc536b6e3c08ff` |
| overlay-mutations | `python3 morphhdl/scripts/test-increment-62-wa08-source-overlay.py` | PASS: 132 live mutation rejections | 6.996 | `72454e9c29a42a0b49e7159ad2587e81bbf329377a4282bed669435effd6ceb4` |
| scope-positive | `python3 morphhdl/scripts/check-wa10-source-scope.py` | PASS: 191 immutable scope paths | 0.589 | `2bf40b32b77dc2d34f3d09505bf9435160651e9edf2b46ec230112eef91096cc` |
| scope-self-test | `python3 morphhdl/scripts/check-wa10-source-scope.py --self-test` | PASS: 12 rejection controls | 0.033 | `fd587833e4e845d132e2f6759bf2b632007ef13ce857e866360639740ed2d9f3` |
| wa10-safety-positive | `python3 morphhdl-passes/scripts/check-wa10-general-expression.py` | PASS: Current WA-10 contract | 0.617 | `b46aaddf459e839bc5a282dda6b5a81a9ea8d7c16b075238733670a02726dfb4` |
| wa10-safety-self-test | `python3 morphhdl-passes/scripts/check-wa10-general-expression.py --self-test` | PASS: 116 rejection controls | 1.270 | `f3e4a791c93c7c398b84e9e14f2e43fd3b3f813a78a0d38a5cf881e18e409c40` |
| boundary-guard | `bash morphhdl-passes/scripts/test-boundary-guard.sh` | PASS: Full guard, including six diagnostic-repair admission controls | 26.599 | `6230a4c23f752355401e18fec7b42edf07becfbb850114e8085dd7ce44a4fbe4` |
| actual-boundary | `MORPHDL_PASSES_HEAD_REF=agent/wa-10-inherited-audit-timeout MORPHDL_PASSES_BASE_SHA=3b547ae5622ae212c17f6e67cc96924127a46a41 bash morphhdl-passes/scripts/check-boundary.sh` | PASS: 16 changed paths against incorporated target | 1.230 | `eb373fb65a9192255c2c344af69fe48478d7b197a26cc38083f38536b5ee8b56` |
| equivalence-self-test | `python3 morphhdl-passes/scripts/validate_wire_assignment_equivalence.py --self-test` | PASS: Validator self-tests and 98 current source signatures | 0.080 | `ef5dbf0d8b539e4b2da28b6435196bf7f04077adb797b26fa22ee7b9b8fa47de` |
| caller-regressions | `python3 morphhdl/scripts/test-inherited-source-audit-timeouts.py` | PASS: 14 caller tests; timeout/default/failure semantics retained | 0.116 | `8034e52e7b337849591a48167fef8caa2b94c0bc9bb155eac385552136c52b47` |
| catalog-self-test | `python3 morphhdl/scripts/check-increment-60f-artifacts.py self-test` | PASS: 4,089 rejection controls; combined 2,012 cases / 197 suites | 143.279 | `106b64edb7c0ab1692719d73b60fba9182fdf6f2c23227750ea02e07bc3aa0d6` |

The catalog still authenticates three distinct profiles: historical WA-09 at 1,982 cases / 194 suites, historical WA-10 at 1,996 / 195, and combined WA-10/WA-11 at 2,012 / 197. The diagnostic regression extends an existing case and does not add a suite or change these required totals.

Comparison against publication predecessor `48759afadd3f03e8166be89f85355426bbaaba02` independently verifies:

- The complete 191-path `wa10-source-scope.json` is byte-identical.
- The formal signature registry contains the same 98 paths. Exactly the `check-boundary.sh` and `test-boundary-guard.sh` digests changed; the other 96 digests are unchanged, and all 98 match the current files.
- All 144 tracked `morphhdl-passes/evidence/wa10/` files are byte-identical, verified by matching full Git tree entries.
- The source change remains confined to the two existing diagnostic-test sources, two boundary scripts, registry and outer seal. No production compiler source or production HDL fixture changes are included.

#### Complete local gate logs

<details>
<summary>overlay-positive</summary>

Command: `python3 morphhdl/scripts/check-increment-62-wa08-source-overlay.py`

Elapsed: 0.592 seconds. Exit: 0. Log SHA-256: `409715bc2e779def3ba41dbce6c49fa916f0370c8698b90b58cc536b6e3c08ff`.

```text
WA08_SOURCE_OVERLAY_PASS files=116
```

</details>

<details>
<summary>overlay-mutations</summary>

Command: `python3 morphhdl/scripts/test-increment-62-wa08-source-overlay.py`

Elapsed: 6.996 seconds. Exit: 0. Log SHA-256: `72454e9c29a42a0b49e7159ad2587e81bbf329377a4282bed669435effd6ceb4`.

```text
WA08_OVERLAY_MUTATIONS_PASS rejected=132
```

</details>

<details>
<summary>scope-positive</summary>

Command: `python3 morphhdl/scripts/check-wa10-source-scope.py`

Elapsed: 0.589 seconds. Exit: 0. Log SHA-256: `2bf40b32b77dc2d34f3d09505bf9435160651e9edf2b46ec230112eef91096cc`.

```text
WA10_SOURCE_SCOPE_PASS paths=191
```

</details>

<details>
<summary>scope-self-test</summary>

Command: `python3 morphhdl/scripts/check-wa10-source-scope.py --self-test`

Elapsed: 0.033 seconds. Exit: 0. Log SHA-256: `fd587833e4e845d132e2f6759bf2b632007ef13ce857e866360639740ed2d9f3`.

```text
WA10_SCOPE_MUTATIONS_PASS controls=12
```

</details>

<details>
<summary>wa10-safety-positive</summary>

Command: `python3 morphhdl-passes/scripts/check-wa10-general-expression.py`

Elapsed: 0.617 seconds. Exit: 0. Log SHA-256: `b46aaddf459e839bc5a282dda6b5a81a9ea8d7c16b075238733670a02726dfb4`.

```text
WA10_CURRENT_CONTRACT_PASS
```

</details>

<details>
<summary>wa10-safety-self-test</summary>

Command: `python3 morphhdl-passes/scripts/check-wa10-general-expression.py --self-test`

Elapsed: 1.270 seconds. Exit: 0. Log SHA-256: `f3e4a791c93c7c398b84e9e14f2e43fd3b3f813a78a0d38a5cf881e18e409c40`.

```text
WA10_CURRENT_CONTRACT_MUTATIONS_PASS controls=116
```

</details>

<details>
<summary>boundary-guard</summary>

Command: `bash morphhdl-passes/scripts/test-boundary-guard.sh`

Elapsed: 26.599 seconds. Exit: 0. Log SHA-256: `6230a4c23f752355401e18fec7b42edf07becfbb850114e8085dd7ce44a4fbe4`.

```text
MorphHDL pass boundary self-tests passed.
```

</details>

<details>
<summary>actual-boundary</summary>

Command: `MORPHDL_PASSES_HEAD_REF=agent/wa-10-inherited-audit-timeout MORPHDL_PASSES_BASE_SHA=3b547ae5622ae212c17f6e67cc96924127a46a41 bash morphhdl-passes/scripts/check-boundary.sh`

Elapsed: 1.230 seconds. Exit: 0. Log SHA-256: `eb373fb65a9192255c2c344af69fe48478d7b197a26cc38083f38536b5ee8b56`.

```text
WA08_SOURCE_OVERLAY_PASS files=116
MorphHDL pass boundary accepted 16 changed path(s).
```

</details>

<details>
<summary>equivalence-self-test</summary>

Command: `python3 morphhdl-passes/scripts/validate_wire_assignment_equivalence.py --self-test`

Elapsed: 0.080 seconds. Exit: 0. Log SHA-256: `ef5dbf0d8b539e4b2da28b6435196bf7f04077adb797b26fa22ee7b9b8fa47de`.

```text
WA-03 equivalence validator self-tests passed.
```

</details>

<details>
<summary>caller-regressions</summary>

Command: `python3 morphhdl/scripts/test-inherited-source-audit-timeouts.py`

Elapsed: 0.116 seconds. Exit: 0. Log SHA-256: `8034e52e7b337849591a48167fef8caa2b94c0bc9bb155eac385552136c52b47`.

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
Ran 14 tests in 0.022s

OK
```

</details>

<details>
<summary>catalog-self-test</summary>

Command: `python3 morphhdl/scripts/check-increment-60f-artifacts.py self-test`

Elapsed: 143.279 seconds. Exit: 0. Log SHA-256: `106b64edb7c0ab1692719d73b60fba9182fdf6f2c23227750ea02e07bc3aa0d6`.

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

#### Complete inherited source-audit replays

The complete current-source audits and separately frozen historical controls also passed on the same sealed tree. These timings include each entire inherited replay; historical positives and exact diagnostic rejection obligations remain intact.

| Replay | Exact command | Seconds | Complete log SHA-256 |
| --- | --- | ---: | --- |
| 59b | `python3 morphhdl/scripts/test-increment-59b-inherited-source-scope.py` | 169.663 | `a264194007fa6e4c368d18386344f9f51db4074e5bed154ca8a3e463bf930704` |
| 59d | `python3 morphhdl/scripts/test-increment-59d-inherited-60f-scope.py` | 178.532 | `b135c96aec560029e66e78c0d143efe13f551c491fc485437779ee6c40b15069` |
| 59h | `python3 morphhdl/scripts/test-increment-59h-inherited-source-scope.py` | 321.433 | `eb2d13b3e53018df6f44f8720825da556fe953fb970e0d1c43f89e531a159440` |

<details>
<summary>Diagnostic repair sealed replay: 59b</summary>

Command: `python3 morphhdl/scripts/test-increment-59b-inherited-source-scope.py`

Elapsed: 169.663 seconds. Exit: 0. Log SHA-256: `a264194007fa6e4c368d18386344f9f51db4074e5bed154ca8a3e463bf930704`.

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
<summary>Diagnostic repair sealed replay: 59d</summary>

Command: `python3 morphhdl/scripts/test-increment-59d-inherited-60f-scope.py`

Elapsed: 178.532 seconds. Exit: 0. Log SHA-256: `b135c96aec560029e66e78c0d143efe13f551c491fc485437779ee6c40b15069`.

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
<summary>Diagnostic repair sealed replay: 59h</summary>

Command: `python3 morphhdl/scripts/test-increment-59h-inherited-source-scope.py`

Elapsed: 321.433 seconds. Exit: 0. Log SHA-256: `eb2d13b3e53018df6f44f8720825da556fe953fb970e0d1c43f89e531a159440`.

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

### Diagnostic-capture repair: expected publication workflow inventory

The actual `pull_request` target, path and activity filters in all 66 workflow YAML files were evaluated for `agent/wa-10-inherited-audit-timeout` targeting `parameterized-verilog`, using the 16-path repair delta against incorporated target `3b547ae5622ae212c17f6e67cc96924127a46a41`. This produces **55 expected triggered workflows: 34 required qualifications, eight historical route-only workflows, and 13 historical branch skips**.

The two existing Scala test changes match `morphhdl/src/**` and add four triggered historical workflows relative to the previous 51-workflow inventory: 59b closed graph, 59b publication, 59b stage replay, and 59f callback graphs. Each has explicit job conditions excluding this exact repair branch. Their skips give no qualification credit. Historical routing workflows find their workflow files already present at the immutable PR base and emit `run=false`; the eight successful routing jobs likewise give no test or proof credit.

The remaining eleven workflow files have no applicable pull-request trigger: eight are push/release-only or reusable workflow-call definitions, and three require target branch `dev`. All 34 applicable qualification workflows remain mandatory, including both repaired 59d/59h Scala lanes, WA-10 and WA-11 dual-Scala/cross-Scala gates, full inherited gates, all 16 pass-proof shards and aggregation. Every actual job/check page must be enumerated for the final published SHA; queued, missing, cancelled, failed or unexecuted applicable work is not a pass. The table below is a trigger expectation, not a fresh CI result.

| Workflow path | Workflow name | Classification |
| --- | --- | --- |
| `.github/workflows/increment-53d-typed-streamwidthadapter.yml` | Increment 53d typed StreamWidthAdapter | Historical branch skip |
| `.github/workflows/increment-53e-typed-streamfifo.yml` | Increment 53e typed StreamFifo | Historical branch skip |
| `.github/workflows/increment-53f-typed-primitives.yml` | Increment 53f typed primitive closure | Historical branch skip |
| `.github/workflows/increment-53g-production-retirement.yml` | Increment 53g production retirement | Historical branch skip |
| `.github/workflows/increment-54-typed-layering-ir.yml` | Increment 54 typed layering and canonical IR | Historical branch skip |
| `.github/workflows/increment-55-concrete-compatibility-audit.yml` | Increment 55 concrete compatibility and approved-native-change audit | Historical route only |
| `.github/workflows/increment-56-native-typed-library-surface.yml` | Increment 56 native typed library-call surface | Historical route only |
| `.github/workflows/increment-57-broad-native-library-migration.yml` | Increment 57 broad native library migration and proof | Historical route only |
| `.github/workflows/increment-57a-typed-streamfifocc.yml` | Increment 57a typed native StreamFifoCC depth and CDC proof | Historical route only |
| `.github/workflows/increment-57b-streamfifocc-payload-width-formal.yml` | Increment 57b StreamFifoCC payload-width formal proof | Historical route only |
| `.github/workflows/increment-58-legacy-retirement.yml` | Increment 58 legacy adapter and shadow-path retirement | Historical route only |
| `.github/workflows/increment-59-typed-blackbox-generics.yml` | Increment 59 typed BlackBox parameter and generic binding | Historical route only |
| `.github/workflows/increment-59a-recursive-verilog-module.yml` | Increment 59a bounded recursive Verilog module | Historical route only |
| `.github/workflows/increment-59b-closed-graph.yml` | Increment 59b closed callback graphs | Historical branch skip |
| `.github/workflows/increment-59b-inherited-source-scope.yml` | Increment 59b inherited source-scope controls | Historical branch skip |
| `.github/workflows/increment-59b-operator-replay.yml` | Increment 59b native operator replay formal proof | Historical branch skip |
| `.github/workflows/increment-59b-publication.yml` | Increment 59b parameterized balanced publication | Historical branch skip |
| `.github/workflows/increment-59b-reduce-balanced-tree.yml` | Increment 59b native replay and oracle checkpoint | Historical branch skip |
| `.github/workflows/increment-59b-stage-replay.yml` | Increment 59b whole-stage native replay | Historical branch skip |
| `.github/workflows/increment-59c-named-field-vectors.yml` | Increment 59c named field vectors | Required qualification |
| `.github/workflows/increment-59d-widening.yml` | Increment 59d generic scalar width and widening qualification | Required qualification |
| `.github/workflows/increment-59e-composite-reduction.yml` | Increment 59e composite balanced reductions | Required qualification |
| `.github/workflows/increment-59f-callback-graphs.yml` | Increment 59f certified callback graphs | Historical branch skip |
| `.github/workflows/increment-59g-register-bridges.yml` | Increment 59g native register bridge qualification | Required qualification |
| `.github/workflows/increment-59h-nested-owners.yml` | Increment 59h nested typed reduction owners | Required qualification |
| `.github/workflows/increment-60a-sint-baseline.yml` | Increment 60a SInt baseline capture | Required qualification |
| `.github/workflows/increment-60b-signedness-authority.yml` | Increment 60b typed signedness authority | Historical branch skip |
| `.github/workflows/increment-60c-signed-declarations.yml` | Increment 60c signed declarations | Required qualification |
| `.github/workflows/increment-60d-pure-sint-casts.yml` | Increment 60d pure SInt casts | Required qualification |
| `.github/workflows/increment-60e-signedness-boundaries.yml` | Increment 60e signedness boundaries | Required qualification |
| `.github/workflows/increment-60f-equivalence-closure.yml` | Increment 60f equivalence closure | Required qualification |
| `.github/workflows/increment-60g-default-signed-verilog.yml` | Increment 60g default signed Verilog | Required qualification |
| `.github/workflows/increment-62-wa08-source-overlay.yml` | Increment 62 WA-08 inherited workflow closure | Required qualification |
| `.github/workflows/morphhdl-baseline.yml` | MorphHDL baseline | Required qualification |
| `.github/workflows/morphhdl-enum-localparams.yml` | MorphHDL SCREAMING_SNAKE_CASE module-local SpinalEnum parameters and formal equivalence | Required qualification |
| `.github/workflows/morphhdl-external-boundary.yml` | MorphHDL external SpinalHDL boundary | Required qualification |
| `.github/workflows/morphhdl-external-formalization.yml` | MorphHDL external native Int formalization | Required qualification |
| `.github/workflows/morphhdl-external-memory.yml` | MorphHDL external native memory | Required qualification |
| `.github/workflows/morphhdl-external-structural-process.yml` | MorphHDL external structural and process capture | Required qualification |
| `.github/workflows/morphhdl-external-symbolic-width.yml` | MorphHDL external symbolic width | Required qualification |
| `.github/workflows/morphhdl-mill.yml` | MorphHDL Mill | Required qualification |
| `.github/workflows/morphhdl-native-axi4-slave-factory-formal-equivalence.yml` | MorphHDL native AXI4 Slave Factory formal equivalence | Required qualification |
| `.github/workflows/morphhdl-native-axi4-slave-factory-offsets.yml` | MorphHDL native AXI4 Slave Factory parameterized offsets | Required qualification |
| `.github/workflows/morphhdl-native-int-nested-control-flow.yml` | MorphHDL native Int nested symbolic control flow | Required qualification |
| `.github/workflows/morphhdl-native-int-shadow-expressions.yml` | MorphHDL native Int shadow expressions | Required qualification |
| `.github/workflows/morphhdl-native-int-shadow-provenance.yml` | MorphHDL native Int shadow provenance | Required qualification |
| `.github/workflows/morphhdl-native-int-symbolic-conditionals.yml` | MorphHDL native Int symbolic conditionals | Required qualification |
| `.github/workflows/morphhdl-native-source-guard.yml` | MorphHDL native source guard | Required qualification |
| `.github/workflows/morphhdl-native-stream-width-adapter.yml` | MorphHDL native StreamWidthAdapter parameterization | Required qualification |
| `.github/workflows/morphhdl-native-streamfifo-formal-equivalence.yml` | MorphHDL native StreamFifo formal equivalence | Required qualification |
| `.github/workflows/morphhdl-native-streamfifo-structure.yml` | MorphHDL native StreamFifo parameter structure | Required qualification |
| `.github/workflows/morphhdl-passes.yml` | MorphHDL IR pass workspace | Required qualification |
| `.github/workflows/morphhdl-streamfifocc-proof.yml` | MorphHDL StreamFifoCC CDC and formal proof | Required qualification |
| `.github/workflows/morphhdl-typed-elaboration-values.yml` | MorphHDL typed elaboration values | Required qualification |
| `.github/workflows/wa11-symbolic-boolean-width.yml` | WA-11 symbolic Boolean integer normalization | Required qualification |

### Diagnostic-capture repair: actual dual-Scala proof suite

The complete existing `GenericExpressionAndStreamTests` suite passed on the
authoritative sealed source `b88e84169f226218f8af7815dda76872f8ed7605` with both
Scala 2.13.12 and 2.12.18. Each run executed all nine cases with zero failed,
aborted, cancelled, ignored, or pending tests. The concurrent-output regression
runs inside the existing first case, so the authenticated suite count is
unchanged.

Each Scala run completed twelve real native/legacy equivalence and simulation
comparisons: GenericExpressions native WIDTH=8 and legacy WIDTH=4/8/32;
StreamM2s native WIDTH=8 and legacy WIDTH=1/8/32; and AutoResizedIncrement native
WIDTH=3 and legacy WIDTH=1/3/8. Each run also passed the existing deliberate
wrong-connection mutation check: actual Yosys rejected the mutation, and the
unchanged assertion found `equiv_status -assert` in the captured exception.
Neither a synthetic proof result nor a relaxed diagnostic assertion substitutes
for this check.

The commands used SBT 1.10.0, real Icarus Verilog, and Yosys 0.41 built from the
CI-pinned source `c1ad37779eea6211866a466026322416fb3a832f` with matching ABC
`237d81397`. The local Yosys compiler differs from CI's compiler; the source
revision and proof commands match. The wrapper below configures the environment's
outbound proxy, system Java trust store, Maven Central mirror, and supported SBT
server flags. It changes no source, test, or proof options. Earlier local Mill
bootstrap attempts stopped in the runtime's `PidLock` initialization before
compilation; they are not counted as passes. The successful SBT runs below
qualify the affected complete suite locally, not the full inherited CI command
or all 197 suites. Fresh exact-publication-head SBT/Mill and formal CI remain
mandatory before merge.

The following generated record contains the exact commands, SHA-256 identities,
wrapper, and complete saved output of both successful SBT commands. Hashes apply
to the raw log bytes inside each fenced block, excluding the Markdown fence.

```json
[
  {
    "scala": "2.13.12",
    "command": "PATH=/tmp/pr186-logfix-tools/bin:$PATH python3 /tmp/pr186-logfix-tools/bin/sbt-proxy '++2.13.12' 'morph/testOnly morphhdl.GenericExpressionAndStreamTests'",
    "log": "/tmp/pr186-logfix-sbt-2.13.log",
    "sha256": "9045f2ed4753d93a11e9e850fa0fdf542facc94f0c1a90b0895c17e8db4bc65d",
    "test_cases": 9,
    "native_proof_simulation_comparisons": 12,
    "genuine_yosys_mutation_rejection": 1
  },
  {
    "scala": "2.12.18",
    "command": "PATH=/tmp/pr186-logfix-tools/bin:$PATH python3 /tmp/pr186-logfix-tools/bin/sbt-proxy '++2.12.18' 'morph/testOnly morphhdl.GenericExpressionAndStreamTests'",
    "log": "/tmp/pr186-logfix-sbt-2.12.log",
    "sha256": "335a90fdcadeea0c4292e7beec85a719a95910d6bf727f98607dfc3173d9849e",
    "test_cases": 9,
    "native_proof_simulation_comparisons": 12,
    "genuine_yosys_mutation_rejection": 1
  }
]
```

The local Maven repository file was:

```text
[repositories]
local
maven-central: https://repo.maven.apache.org/maven2
```

The complete local `sbt-proxy` wrapper was:

```python
#!/usr/bin/env python3
import os
import sys
from urllib.parse import urlparse

proxy = urlparse(os.environ.get("HTTPS_PROXY", ""))
args = ["java", "-XX:ActiveProcessorCount=4", "-Xmx4g",
        "-Djavax.net.ssl.trustStore=/etc/ssl/certs/java/cacerts",
        "-Dsbt.override.build.repos=true",
        "-Dsbt.repository.config=/tmp/pr186-logfix-tools/repositories",
        "-Dsbt.log.noformat=true", "-Dsbt.supershell=false",
        "-Dsbt.server.forcestart=true", "-Dsbt.server.autostart=false"]
if proxy.hostname:
    for scheme in ("http", "https"):
        args.extend([f"-D{scheme}.proxyHost={proxy.hostname}",
                     f"-D{scheme}.proxyPort={proxy.port or 80}"])
os.environ["COURSIER_REPOSITORIES"] = "https://repo.maven.apache.org/maven2"
os.execvp("java", args + ["-jar", "/tmp/pr186-logfix-tools/sbt-launch.jar"] + sys.argv[1:])
```

<details>
<summary>Scala 2.13.12: complete successful SBT output</summary>

```text
[warn] [launcher] could not parse ftp_proxy setting: java.net.MalformedURLException: unknown protocol: socks5h
[info] welcome to sbt 1.10.0 (Ubuntu Java 17.0.20)
[info] loading settings for project morphhdl-pr186-logfix-build from plugin.sbt ...
[info] loading project definition from /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/project
[info] compiling 1 Scala source to /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/project/target/scala-2.12/sbt-1.0/classes ...
[info] Non-compiled module 'compiler-bridge_2.12' for Scala 2.12.19. Compiling...
[info]   Compilation completed in 6.547s.
[info] done compiling
/workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/build.sbt:200: warning: method sonatypeRepo in class ResolverFunctions is deprecated (since 1.7.0): Use sonatypeOssRepos instead e.g. `resolvers ++= Resolver.sonatypeOssRepos("snapshots")`
    resolvers += Resolver.sonatypeRepo("public"),
                          ^
[info] loading settings for project all from build.sbt ...
[info] resolving key references (13791 settings) ...
[info] set current project to SpinalHDL-all (in build file:/workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/)
[info] Setting Scala version to 2.13.12 on 14 projects.
[info] Reapplying settings...
[info] set current project to SpinalHDL-all (in build file:/workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/)
[info] compiling 4 Scala sources to /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphplugin/target/scala-2.13/classes ...
[info] compiling 5 Scala sources to /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphir/target/scala-2.13/classes ...
[info] compiling 2 Scala sources to /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/idslpayload/target/scala-2.13/classes ...
[info] compiling 17 Scala sources and 10 Java sources to /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/sim/target/scala-2.13/classes ...
[warn] 1 deprecation
[warn] 3 deprecations (since 2.11.0)
[warn] 4 deprecations in total; re-run with -deprecation for details
[warn] three warnings found
[info] done compiling
[info] compiling 6 Scala sources to /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/paramrtl/target/scala-2.13/classes ...
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphplugin/src/main/scala/morphhdl/compiler/MorphHdlTypedElaborationControlComponent.scala:34:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class ClassifiedTrees(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphplugin/src/main/scala/morphhdl/compiler/MorphHdlTypedElaborationControlComponent.scala:41:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class WildcardMembers(
[warn]                            ^
[warn] 1 deprecation; re-run with -deprecation for details
[warn] three warnings found
[info] done compiling
[info] compiling 2 Scala sources to /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/idslplugin/target/scala-2.13/classes ...
[warn] 1 deprecation; re-run with -deprecation for details
[warn] one warning found
[info] done compiling
[warn] 1 deprecation
[warn] 23 deprecations (since 2.13.0)
[warn] 24 deprecations in total; re-run with -deprecation for details
[warn] three warnings found
[info] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/sim/src/main/java/spinal/sim/DynamicCompiler.java: DynamicCompiler.java uses or overrides a deprecated API.
[info] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/sim/src/main/java/spinal/sim/DynamicCompiler.java: Recompile with -Xlint:deprecation for details.
[info] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/sim/src/main/java/spinal/sim/DynamicCompiler.java: DynamicCompiler.java uses unchecked or unsafe operations.
[info] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/sim/src/main/java/spinal/sim/DynamicCompiler.java: Recompile with -Xlint:unchecked for details.
[info] done compiling
[info] compiling 87 Scala sources and 2 Java sources to /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/core/target/scala-2.13/classes ...
[warn] 1 deprecation; re-run with -deprecation for details
[warn] one warning found
[info] done compiling
[warn] 1 deprecation; re-run with -deprecation for details
[warn] one warning found
[info] done compiling
[info] compiling 2 Scala sources to /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/backends/verilog/target/scala-2.13/classes ...
[warn] 1 deprecation; re-run with -deprecation for details
[warn] one warning found
[info] done compiling
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/core/src/main/scala/spinal/core/Union.scala:8:16: Implicit definition should have explicit type (inferred T) [quickfixable]
[warn]   implicit def wrapped[T <: Data](ue: UnionElement[T]) = ue.get()
[warn]                ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/core/src/main/scala/spinal/core/core.scala:379:48: fruitless type test: a value of type String cannot also be a List[Any] (the underlying of List[_])
[warn]         if (pi.hasNext && !pi.next.isInstanceOf[List[_]]) bldr append pi.next
[warn]                                                ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/core/src/main/scala/spinal/core/internals/Phase.scala:3228:25: non-variable type argument spinal.core.SpinalEnum in type pattern spinal.core.SpinalEnumCraft[spinal.core.SpinalEnum] is unchecked since it is eliminated by erasure
[warn]                 case d: SpinalEnumCraft[SpinalEnum] => d.init(d.spinalEnum.elements(0))
[warn]                         ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/core/src/main/scala/spinal/core/sim/package.scala:735:9: method without a parameter list overrides method getSim in trait SimEquiv defined with a single empty parameter list [quickfixable]
[warn]     def getSim = bt.encoding.getElement(getBigInt(bt), bt.spinalEnum).asInstanceOf[SpinalEnumElement[T]]
[warn]         ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/core/src/main/scala/spinal/core/AFix.scala:231:5: match may not be exhaustive.
[warn] It would fail on the following input: SCRAP
[warn]     roundType match {
[warn]     ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/core/src/main/scala/spinal/core/Data.scala:390:29: match may not be exhaustive.
[warn] It would fail on the following inputs: inWithNull, outWithNull
[warn]   def dirString(): String = dir match {
[warn]                             ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/core/src/main/scala/spinal/core/Data.scala:581:16: match may not be exhaustive.
[warn] It would fail on the following inputs: inWithNull, outWithNull
[warn]           that.dir match {
[warn]                ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/core/src/main/scala/spinal/core/Data.scala:593:9: match may not be exhaustive.
[warn] It would fail on the following inputs: inWithNull, outWithNull
[warn]         d match {
[warn]         ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/core/src/main/scala/spinal/core/SInt.scala:397:5: match may not be exhaustive.
[warn] It would fail on the following input: SCRAP
[warn]     roundType match {
[warn]     ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/core/src/main/scala/spinal/core/UInt.scala:279:5: match may not be exhaustive.
[warn] It would fail on the following input: SCRAP
[warn]     roundType match{
[warn]     ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/core/src/main/scala/spinal/core/fiber/AsyncThread.scala:81:5: match may not be exhaustive.
[warn] It would fail on the following input: false
[warn]     done match {
[warn]     ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/core/src/main/scala/spinal/core/internals/Phase.scala:1700:31: unreachable code
[warn]           case e: MemReadSync =>
[warn]                               ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/core/src/main/scala/spinal/core/internals/Phase.scala:3114:12: match may not be exhaustive.
[warn] It would fail on the following inputs: inWithNull, inout, outWithNull
[warn]         io.dir match {
[warn]            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/core/src/main/scala/spinal/core/internals/PhaseVerilog.scala:483:23: match may not be exhaustive.
[warn] It would fail on the following input: Nil
[warn]         val newName = IFlist match {
[warn]                       ^
[warn] 4 deprecations
[warn] 83 deprecations (since 2.13.0)
[warn] 1 deprecation (since 2.13.11)
[warn] 1 deprecation (since 2.13.2)
[warn] 70 deprecations (since 2.13.3)
[warn] 2 deprecations (since 2.13.4)
[warn] 1 deprecation (since 9)
[warn] 162 deprecations in total; re-run with -deprecation for details
[warn] 3 feature warnings; re-run with -feature for details
[warn] 23 warnings found
[info] done compiling
[info] compiling 10 Scala sources to /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphruntime/target/scala-2.13/classes ...
[warn] 1 deprecation; re-run with -deprecation for details
[warn] one warning found
[info] done compiling
[info] compiling 563 Scala sources to /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/target/scala-2.13/classes ...
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib.scala:75:16: Implicit definition should have explicit type (inferred spinal.core.DataPimper[spinal.lib.Fragment[T]]) [quickfixable]
[warn]   implicit def fragmentFixer[T <: Data](_data: Fragment[T]) = new DataPimper(_data)
[warn]                ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/CrossClock.scala:162:15: non-variable type argument spinal.core.Data in type pattern spinal.lib.BufferCC[spinal.core.Data] is unchecked since it is eliminated by erasure
[warn]       case c: BufferCC[Data] => {
[warn]               ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/Utils.scala:713:19: abstract type pattern T is unchecked since it is eliminated by erasure
[warn]         case bt : T => solveCombDriver(bt)
[warn]                   ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/regif/Block/RegInst.scala:94:19: non-variable type argument spinal.core.SpinalEnum in type pattern spinal.core.SpinalEnumCraft[spinal.core.SpinalEnum] is unchecked since it is eliminated by erasure
[warn]           case t: SpinalEnumCraft[SpinalEnum] => t.init(t.spinalEnum.elements(resetValue.toInt))
[warn]                   ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/tilelink/coherent/Cache.scala:781:25: reference to ctrl is ambiguous;
[warn] it is both defined in the enclosing class Cache and inherited in the enclosing anonymous class as method ctrl (defined in class StageCtrlPipeline)
[warn] In Scala 2, symbols inherited from a superclass shadow symbols defined in an outer scope.
[warn] Such references are ambiguous in Scala 3. To continue using the inherited symbol, write `this.ctrl`.
[warn] Or use `-Wconf:msg=legacy-binding:s` to silence this warning. [quickfixable]
[warn]     val inserterStage = ctrl(0)
[warn]                         ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/tilelink/coherent/Cache.scala:782:24: reference to ctrl is ambiguous;
[warn] it is both defined in the enclosing class Cache and inherited in the enclosing anonymous class as method ctrl (defined in class StageCtrlPipeline)
[warn] In Scala 2, symbols inherited from a superclass shadow symbols defined in an outer scope.
[warn] Such references are ambiguous in Scala 3. To continue using the inherited symbol, write `this.ctrl`.
[warn] Or use `-Wconf:msg=legacy-binding:s` to silence this warning. [quickfixable]
[warn]     val addressStage = ctrl(0)
[warn]                        ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/tilelink/coherent/Cache.scala:783:21: reference to ctrl is ambiguous;
[warn] it is both defined in the enclosing class Cache and inherited in the enclosing anonymous class as method ctrl (defined in class StageCtrlPipeline)
[warn] In Scala 2, symbols inherited from a superclass shadow symbols defined in an outer scope.
[warn] Such references are ambiguous in Scala 3. To continue using the inherited symbol, write `this.ctrl`.
[warn] Or use `-Wconf:msg=legacy-binding:s` to silence this warning. [quickfixable]
[warn]     val dataStage = ctrl(1)
[warn]                     ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/tilelink/coherent/Cache.scala:784:20: reference to ctrl is ambiguous;
[warn] it is both defined in the enclosing class Cache and inherited in the enclosing anonymous class as method ctrl (defined in class StageCtrlPipeline)
[warn] In Scala 2, symbols inherited from a superclass shadow symbols defined in an outer scope.
[warn] Such references are ambiguous in Scala 3. To continue using the inherited symbol, write `this.ctrl`.
[warn] Or use `-Wconf:msg=legacy-binding:s` to silence this warning. [quickfixable]
[warn]     val tagStage = ctrl(1)
[warn]                    ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/tilelink/coherent/Cache.scala:785:21: reference to ctrl is ambiguous;
[warn] it is both defined in the enclosing class Cache and inherited in the enclosing anonymous class as method ctrl (defined in class StageCtrlPipeline)
[warn] In Scala 2, symbols inherited from a superclass shadow symbols defined in an outer scope.
[warn] Such references are ambiguous in Scala 3. To continue using the inherited symbol, write `this.ctrl`.
[warn] Or use `-Wconf:msg=legacy-binding:s` to silence this warning. [quickfixable]
[warn]     val prepStage = ctrl(1)
[warn]                     ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/tilelink/coherent/Cache.scala:786:24: reference to ctrl is ambiguous;
[warn] it is both defined in the enclosing class Cache and inherited in the enclosing anonymous class as method ctrl (defined in class StageCtrlPipeline)
[warn] In Scala 2, symbols inherited from a superclass shadow symbols defined in an outer scope.
[warn] Such references are ambiguous in Scala 3. To continue using the inherited symbol, write `this.ctrl`.
[warn] Or use `-Wconf:msg=legacy-binding:s` to silence this warning. [quickfixable]
[warn]     val processStage = ctrl(2)
[warn]                        ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/tilelink/coherent/Cache.scala:1428:25: reference to ctrl is ambiguous;
[warn] it is both defined in the enclosing class Cache and inherited in the enclosing anonymous class as method ctrl (defined in class StageCtrlPipeline)
[warn] In Scala 2, symbols inherited from a superclass shadow symbols defined in an outer scope.
[warn] Such references are ambiguous in Scala 3. To continue using the inherited symbol, write `this.ctrl`.
[warn] Or use `-Wconf:msg=legacy-binding:s` to silence this warning. [quickfixable]
[warn]     val inserterStage = ctrl(0)
[warn]                         ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/tilelink/coherent/Cache.scala:1430:21: reference to ctrl is ambiguous;
[warn] it is both defined in the enclosing class Cache and inherited in the enclosing anonymous class as method ctrl (defined in class StageCtrlPipeline)
[warn] In Scala 2, symbols inherited from a superclass shadow symbols defined in an outer scope.
[warn] Such references are ambiguous in Scala 3. To continue using the inherited symbol, write `this.ctrl`.
[warn] Or use `-Wconf:msg=legacy-binding:s` to silence this warning. [quickfixable]
[warn]     val readStage = ctrl(1)
[warn]                     ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/tilelink/coherent/Cache.scala:1431:24: reference to ctrl is ambiguous;
[warn] it is both defined in the enclosing class Cache and inherited in the enclosing anonymous class as method ctrl (defined in class StageCtrlPipeline)
[warn] In Scala 2, symbols inherited from a superclass shadow symbols defined in an outer scope.
[warn] Such references are ambiguous in Scala 3. To continue using the inherited symbol, write `this.ctrl`.
[warn] Or use `-Wconf:msg=legacy-binding:s` to silence this warning. [quickfixable]
[warn]     val processStage = ctrl(p.readProcessAt)
[warn]                        ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/tilelink/coherent/Cache.scala:1527:25: reference to ctrl is ambiguous;
[warn] it is both defined in the enclosing class Cache and inherited in the enclosing anonymous class as method ctrl (defined in class StageCtrlPipeline)
[warn] In Scala 2, symbols inherited from a superclass shadow symbols defined in an outer scope.
[warn] Such references are ambiguous in Scala 3. To continue using the inherited symbol, write `this.ctrl`.
[warn] Or use `-Wconf:msg=legacy-binding:s` to silence this warning. [quickfixable]
[warn]     val inserterStage = ctrl(0)
[warn]                         ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/tilelink/coherent/Cache.scala:1528:22: reference to ctrl is ambiguous;
[warn] it is both defined in the enclosing class Cache and inherited in the enclosing anonymous class as method ctrl (defined in class StageCtrlPipeline)
[warn] In Scala 2, symbols inherited from a superclass shadow symbols defined in an outer scope.
[warn] Such references are ambiguous in Scala 3. To continue using the inherited symbol, write `this.ctrl`.
[warn] Or use `-Wconf:msg=legacy-binding:s` to silence this warning. [quickfixable]
[warn]     val fetchStage = ctrl(0)
[warn]                      ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/tilelink/coherent/Cache.scala:1529:21: reference to ctrl is ambiguous;
[warn] it is both defined in the enclosing class Cache and inherited in the enclosing anonymous class as method ctrl (defined in class StageCtrlPipeline)
[warn] In Scala 2, symbols inherited from a superclass shadow symbols defined in an outer scope.
[warn] Such references are ambiguous in Scala 3. To continue using the inherited symbol, write `this.ctrl`.
[warn] Or use `-Wconf:msg=legacy-binding:s` to silence this warning. [quickfixable]
[warn]     val readStage = ctrl(1)
[warn]                     ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/tilelink/coherent/Cache.scala:1530:24: reference to ctrl is ambiguous;
[warn] it is both defined in the enclosing class Cache and inherited in the enclosing anonymous class as method ctrl (defined in class StageCtrlPipeline)
[warn] In Scala 2, symbols inherited from a superclass shadow symbols defined in an outer scope.
[warn] Such references are ambiguous in Scala 3. To continue using the inherited symbol, write `this.ctrl`.
[warn] Or use `-Wconf:msg=legacy-binding:s` to silence this warning. [quickfixable]
[warn]     val processStage = ctrl(2)
[warn]                        ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/tilelink/coherent/Cache.scala:1714:25: reference to ctrl is ambiguous;
[warn] it is both defined in the enclosing class Cache and inherited in the enclosing anonymous class as method ctrl (defined in class StageCtrlPipeline)
[warn] In Scala 2, symbols inherited from a superclass shadow symbols defined in an outer scope.
[warn] Such references are ambiguous in Scala 3. To continue using the inherited symbol, write `this.ctrl`.
[warn] Or use `-Wconf:msg=legacy-binding:s` to silence this warning. [quickfixable]
[warn]     val inserterStage = ctrl(0)
[warn]                         ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/tilelink/coherent/Cache.scala:1715:22: reference to ctrl is ambiguous;
[warn] it is both defined in the enclosing class Cache and inherited in the enclosing anonymous class as method ctrl (defined in class StageCtrlPipeline)
[warn] In Scala 2, symbols inherited from a superclass shadow symbols defined in an outer scope.
[warn] Such references are ambiguous in Scala 3. To continue using the inherited symbol, write `this.ctrl`.
[warn] Or use `-Wconf:msg=legacy-binding:s` to silence this warning. [quickfixable]
[warn]     val fetchStage = ctrl(0)
[warn]                      ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/tilelink/coherent/Cache.scala:1716:21: reference to ctrl is ambiguous;
[warn] it is both defined in the enclosing class Cache and inherited in the enclosing anonymous class as method ctrl (defined in class StageCtrlPipeline)
[warn] In Scala 2, symbols inherited from a superclass shadow symbols defined in an outer scope.
[warn] Such references are ambiguous in Scala 3. To continue using the inherited symbol, write `this.ctrl`.
[warn] Or use `-Wconf:msg=legacy-binding:s` to silence this warning. [quickfixable]
[warn]     val readStage = ctrl(1)
[warn]                     ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/tilelink/coherent/Cache.scala:1717:27: reference to ctrl is ambiguous;
[warn] it is both defined in the enclosing class Cache and inherited in the enclosing anonymous class as method ctrl (defined in class StageCtrlPipeline)
[warn] In Scala 2, symbols inherited from a superclass shadow symbols defined in an outer scope.
[warn] Such references are ambiguous in Scala 3. To continue using the inherited symbol, write `this.ctrl`.
[warn] Or use `-Wconf:msg=legacy-binding:s` to silence this warning. [quickfixable]
[warn]     val preprocessStage = ctrl(1)
[warn]                           ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/tilelink/coherent/Cache.scala:1718:24: reference to ctrl is ambiguous;
[warn] it is both defined in the enclosing class Cache and inherited in the enclosing anonymous class as method ctrl (defined in class StageCtrlPipeline)
[warn] In Scala 2, symbols inherited from a superclass shadow symbols defined in an outer scope.
[warn] Such references are ambiguous in Scala 3. To continue using the inherited symbol, write `this.ctrl`.
[warn] Or use `-Wconf:msg=legacy-binding:s` to silence this warning. [quickfixable]
[warn]     val processStage = ctrl(2)
[warn]                        ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/tilelink/sim/BridgeTestbench.scala:11:16: Implicit definition should have explicit type (inferred spinal.lib.bus.tilelink.sim.IdAllocator) [quickfixable]
[warn]   implicit val idAllocator = new IdAllocator(DebugId.width)
[warn]                ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/tilelink/sim/BridgeTestbench.scala:12:16: Implicit definition should have explicit type (inferred spinal.lib.bus.tilelink.sim.IdCallback) [quickfixable]
[warn]   implicit val idCallback = new IdCallback
[warn]                ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/tilelink/sim/MemoryAgent.scala:19:16: Pattern definition introduces Unit-valued member of MemoryAgent; consider wrapping it in `locally { ... }`.
[warn]   implicit val _ = sm
[warn]                ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/cpu/riscv/impl/ICache.scala:258:18: Implicit definition should have explicit type (inferred spinal.lib.cpu.riscv.impl.InstructionCacheConfig) [quickfixable]
[warn]     implicit val p = InstructionCacheConfig(
[warn]                  ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/cpu/riscv/impl/bench/CoreUut.scala:11:18: Implicit definition should have explicit type (inferred spinal.lib.cpu.riscv.impl.RiscvCoreConfig) [quickfixable]
[warn]     implicit val toto = p
[warn]                  ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/misc/pipeline/CtrlLink.scala:147:16: Implicit definition should have explicit type (inferred CtrlApi.this.BundlePimper[T])
[warn]   implicit def bundlePimper[T <: Bundle](stageable: Payload[T]) = new BundlePimper[T](this (stageable))
[warn]                ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/misc/pipeline/Node.scala:65:16: Implicit definition should have explicit type (inferred NodeBaseApi.this.BundlePimper[T])
[warn]   implicit def bundlePimper[T <: Bundle](stageable: Payload[T]) = new  BundlePimper[T](this(stageable))
[warn]                ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/misc/test/MultithreadedTester.scala:57:16: Implicit definition should have explicit type (inferred scala.concurrent.ExecutionContextExecutorService) [quickfixable]
[warn]   implicit val ec = ExecutionContext.fromExecutorService(
[warn]                ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/pipeline/Play.scala:112:20: Implicit definition should have explicit type (inferred spinal.lib.pipeline.Pipeline) [quickfixable]
[warn]       implicit val pip = new Pipeline
[warn]                    ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/pipeline/Stage.scala:137:16: Implicit definition should have explicit type (inferred T)
[warn]   implicit def stageablePiped[T <: Data](stageable: Stageable[T])(implicit key : StageableOffset = StageableOffsetNone) = Stage.this(stageable, key.value)
[warn]                ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/pipeline/Stage.scala:138:16: Implicit definition should have explicit type (inferred AnyRef{def of(key: Any): T})
[warn]   implicit def stageablePiped2[T <: Data](stageable: Stageable[T]) = new {
[warn]                ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/pipeline/Stage.scala:142:16: Implicit definition should have explicit type (inferred T)
[warn]   implicit def stageablePiped3[T <: Data](key: Tuple2[Stageable[T], Any]) = Stage.this(key._1, key._2)
[warn]                ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/pipeline/Stage.scala:145:16: Implicit definition should have explicit type (inferred AnyRef{def of(key: Any): spinal.core.Vec[T]})
[warn]   implicit def stageablePipedVec2[T <: Data](stageable: Stageable[Vec[T]]) = new {
[warn]                ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/pipeline/Stage.scala:148:16: Implicit definition should have explicit type (inferred spinal.core.Vec[T])
[warn]   implicit def stageablePipedVec3[T <: Data](key: Tuple2[Stageable[Vec[T]], Any]) = Stage.this(key._1, key._2)
[warn]                ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/sim/Stream.scala:90:16: Pattern definition introduces Unit-valued member of StreamDriver; consider wrapping it in `locally { ... }`.
[warn]   implicit val _ = sm
[warn]                ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/sim/Stream.scala:256:16: Pattern definition introduces Unit-valued member of StreamDriverOoo; consider wrapping it in `locally { ... }`.
[warn]   implicit val _ = sm
[warn]                ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/bmb/BmbGenerators.scala:184:16: method with a single empty parameter list overrides method getBus in trait InterruptCtrlGeneratorI defined without a parameter list [quickfixable]
[warn]   override def getBus(): Handle[Nameable] = ctrl
[warn]                ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/regif/Block/RamInst.scala:28:16: method without a parameter list overrides method readBits in class RegSlice defined with a single empty parameter list [quickfixable]
[warn]   override def readBits: Bits = bus.rdat
[warn]                ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/regif/Block/RdFifoInst.scala:18:16: method without a parameter list overrides method readBits in class RegSlice defined with a single empty parameter list [quickfixable]
[warn]   override def readBits: Bits = bus.payload
[warn]                ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/regif/Block/RegBase.scala:23:7: method without a parameter list overrides method readBits in class RegSlice defined with a single empty parameter list [quickfixable]
[warn]   def readBits: Bits = {
[warn]       ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/regif/Block/VirtualRegInst.scala:7:16: method without a parameter list overrides method readBits in class RegBase defined with a single empty parameter list [quickfixable]
[warn]   override def readBits: Bits = busif.defaultReadBits
[warn]                ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/regif/Block/WrFifoInst.scala:20:16: method without a parameter list overrides method readBits in class RegSlice defined with a single empty parameter list [quickfixable]
[warn]   override def readBits: Bits = bi.defaultReadBits
[warn]                ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/com/usb/ohci/UsbOhciTilelinkFiber.scala:46:17: comparing values of types spinal.core.ClockDomain and spinal.core.fiber.Handle[spinal.core.ClockDomain] using `==` will always yield false
[warn]       if(ctrlCd == phyCd) {
[warn]                 ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/generator/ClockDomainGenerator.scala:272:5: multiline expressions might require enclosing parentheses; a value can be silently discarded when Unit is expected
[warn]     generator
[warn]     ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/Utils.scala:702:32: unreachable code
[warn]     case e: MemReadSync => body(e)
[warn]                                ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/bmb/Bmb.scala:561:38: match may not be exhaustive.
[warn]   def resize(dataWidth : Int): Bmb = this match {
[warn]                                      ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/fabric/MappedConnection.scala:24:55: match may not be exhaustive.
[warn] It would fail on the following input: Some((x: Any forSome x not in (BigInt, DefaultMapping, spinal.lib.bus.misc.AddressMapping)))
[warn]     override def transformers = MappedConnection.this.userMapping match {
[warn]                                                       ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/fabric/MappedConnection.scala:34:29: match may not be exhaustive.
[warn] It would fail on the following input: Some((x: Any forSome x not in (BigInt, DefaultMapping, spinal.lib.bus.misc.AddressMapping)))
[warn]       MappedConnection.this.userMapping match {
[warn]                             ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/fabric/MappedConnection.scala:48:5: match may not be exhaustive.
[warn] It would fail on the following input: Some((x: Any forSome x not in (BigInt, DefaultMapping, spinal.lib.bus.misc.AddressMapping)))
[warn]     userMapping match {
[warn]     ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/fabric/MappedConnection.scala:57:5: match may not be exhaustive.
[warn] It would fail on the following input: Some((x: Any forSome x not in (BigInt, DefaultMapping, spinal.lib.bus.misc.AddressMapping)))
[warn]     userMapping match {
[warn]     ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/regif/Block/RegBase.scala:325:7: Exhaustivity analysis reached max recursion depth, not all missing cases are reported.
[warn] (Please try with scalac -Ypatmat-exhaust-depth 40 or -Ypatmat-exhaust-depth off.)
[warn]       accType match {
[warn]       ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/regif/Block/RegBase.scala:356:7: Exhaustivity analysis reached max recursion depth, not all missing cases are reported.
[warn] (Please try with scalac -Ypatmat-exhaust-depth 40 or -Ypatmat-exhaust-depth off.)
[warn]       accType match {
[warn]       ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/regif/Block/RegInst.scala:320:21: match may not be exhaustive.
[warn] It would fail on the following inputs: W1CHS, W1I, W1SHS
[warn]     val ret: Bits = acc match {
[warn]                     ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/regif/Block/RegSlice.scala:35:13: match may not be exhaustive.
[warn] It would fail on the following input: Some((x: spinal.lib.bus.regif.Secure forSome x not in (spinal.lib.bus.regif.Secure.CS, spinal.lib.bus.regif.Secure.MS)))
[warn]       Option(sec) match {
[warn]             ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/tilelink/sim/Checker.scala:223:25: match may not be exhaustive.
[warn] It would fail on the following input: None
[warn]         inflightC.remove(d.source -> d.address) match {
[warn]                         ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/tilelink/sim/Checker.scala:239:21: match may not be exhaustive.
[warn] It would fail on the following input: None
[warn]     inflightD.remove(e.sink) match {
[warn]                     ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/dsptool/FixData.scala:30:26: match may not be exhaustive.
[warn] It would fail on the following input: SCRAP
[warn]       val rounded = this.roundType match {
[warn]                          ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/eda/TimingExtractor.scala:143:49: match may not be exhaustive.
[warn] It would fail on the following input: None
[warn]     case bt : BaseType if bt.isComb => bt.getTag(classOf[ClockDomainTag]) match {
[warn]                                                 ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/io/InOutVecToBits.scala:22:7: match may not be exhaustive.
[warn] It would fail on the following inputs: inWithNull, inout, outWithNull
[warn]     e.getDirection match {
[warn]       ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/io/InOutVecToBits.scala:36:13: match may not be exhaustive.
[warn] It would fail on the following inputs: inWithNull, inout, outWithNull
[warn]         key.getDirection match {
[warn]             ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/pipeline/Pipeline.scala:171:22: match may not be exhaustive.
[warn] It would fail on the following input: Some((x: Any forSome x not in Pipeline.this.ConnectionModel))
[warn]       stageDriver.get(stage) match {
[warn]                      ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/system/dma/sg/MemoryCore.scala:112:7: match may not be exhaustive.
[warn] It would fail on the following input: (true, true)
[warn]       (p.writes(self).absolutePriority, p.writes(other).absolutePriority) match {
[warn]       ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/system/dma/sg/MemoryCore.scala:198:7: match may not be exhaustive.
[warn] It would fail on the following input: (true, true)
[warn]       (p.reads(self).absolutePriority, p.reads(other).absolutePriority) match {
[warn]       ^
[warn] 28 deprecations
[warn] 3 deprecations (since 2.13)
[warn] 372 deprecations (since 2.13.0)
[warn] 23 deprecations (since 2.13.2)
[warn] 439 deprecations (since 2.13.3)
[warn] 1 deprecation (since 2022-12-31)
[warn] 1 deprecation (since 2026.12.30)
[warn] 867 deprecations in total; re-run with -deprecation for details
[warn] 6 feature warnings; re-run with -feature for details
[warn] 74 warnings found
[info] done compiling
[info] compiling 25 Scala sources to /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/frontend/target/scala-2.13/classes ...
[warn] 1 deprecation; re-run with -deprecation for details
[warn] 2 feature warnings; re-run with -feature for details
[warn] two warnings found
[info] done compiling
[info] compiling 69 Scala sources to /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/target/scala-2.13/classes ...
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala:3422:41: match may not be exhaustive.
[warn] It would fail on the following inputs: Vector0, Vector1(), Vector2(), Vector3(), Vector4(), Vector5(), Vector6()
[warn]                 val conditionalOwners = nestedOwners match {
[warn]                                         ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl-passes/examples/NamedWireAliasNativeBridge.scala:90:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class ForwardRewrite(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl-passes/examples/NamedWireAliasNativeBridge.scala:94:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class PreferredSourceRewrite(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl-passes/examples/NamedWireAliasNativeBridge.scala:180:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class NativeCandidate(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl-passes/examples/NamedWireAliasNativeBridge.scala:190:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class NativeProof(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl-passes/examples/NamedWireExpressionNativeBridge.scala:141:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class NativeCandidate(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl-passes/examples/NamedWireExpressionNativeBridge.scala:154:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class NativeProof(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl-passes/examples/NamedWireExpressionNativeBridge.scala:395:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class CanonicalSnapshot(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl-passes/examples/UnnamedWireAliasNativeBridge.scala:114:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class NativeCandidate(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl-passes/examples/UnnamedWireAliasNativeBridge.scala:122:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class NativeProof(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala:2563:30: The outer reference in this type test cannot be checked at run time.
[warn]     private final case class ModularUIntFacts(
[warn]                              ^
[warn] 1 deprecation
[warn] 24 deprecations (since 2.13.0)
[warn] 4 deprecations (since 2.13.3)
[warn] 40 deprecations (since Increment 58)
[warn] 69 deprecations in total; re-run with -deprecation for details
[warn] 1 feature warning; re-run with -feature for details
[warn] 17 warnings found
[info] done compiling
[info] compiling 167 Scala sources and 2 Java sources to /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/target/scala-2.13/test-classes ...
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/TypedElaborationControlTests.scala:112:11: comparing values of types spinal.core.ElabInt and String using `==` will always yield false
[warn]     width == "WIDTH"
[warn]           ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/NativeAxi4SlaveFactoryFormalEquivalenceTests.scala:71:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class GeneratedDuts(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/NativeAxi4SlaveFactoryFormalEquivalenceTests.scala:76:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class PreparedDuts(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/NativeLibraryMigrationFormalEquivalenceTests.scala:78:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class GeneratedDuts(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/NativeLibraryMigrationFormalEquivalenceTests.scala:83:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class PreparedDuts(candidate: Path, concrete: Path)
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/NativeStreamFifoCCCdcProofTests.scala:81:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class ClockSchedule(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/NativeStreamFifoCCFormalEquivalenceTests.scala:138:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class ClockRatio(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/NativeStreamFifoCCFormalEquivalenceTests.scala:143:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class Configuration(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/NativeStreamFifoCCFormalEquivalenceTests.scala:149:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class GeneratedDuts(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/NativeStreamFifoCCFormalEquivalenceTests.scala:153:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class PreparedDuts(candidate: Path, reference: Path)
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/NativeStreamFifoFormalEquivalenceTests.scala:162:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class GeneratedDuts(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/NativeStreamFifoFormalEquivalenceTests.scala:167:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class PreparedDuts(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/NativeStreamFifoFormalEquivalenceTests.scala:172:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class GeneratedFormalHelperDuts(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/NativeStreamFifoFormalEquivalenceTests.scala:177:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class PreparedFormalHelperDuts(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/TypedParameterizedVecFormalEquivalenceTests.scala:80:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class Witness(width: Int, depth: Int)
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/TypedParameterizedVecFormalEquivalenceTests.scala:81:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class GeneratedDuts(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/TypedParameterizedVecFormalEquivalenceTests.scala:85:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class PreparedDuts(candidate: Path, reference: Path)
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/TypedParameterizedVecTests.scala:456:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class PortExpectation(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/TypedParameterizedVecTests.scala:1550:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class AlgebraAtom(value: String) extends Algebra
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/TypedParameterizedVecTests.scala:1551:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class AlgebraSum(terms: Vector[Algebra]) extends Algebra
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/TypedParameterizedVecTests.scala:1552:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class AlgebraProduct(factors: Vector[Algebra]) extends Algebra
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/TypedParameterizedVecTests.scala:1553:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class AlgebraDifference(left: Algebra, right: Algebra) extends Algebra
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/TypedPrimitiveClosureFormalEquivalenceTests.scala:291:30: The outer reference in this type test cannot be checked at run time.
[warn]     private final case class GeneratedDuts(
[warn]                              ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/TypedPrimitiveClosureFormalEquivalenceTests.scala:296:30: The outer reference in this type test cannot be checked at run time.
[warn]     private final case class PreparedDuts(candidate: Path, concrete: Path)
[warn]                              ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/TypedStreamWidthAdapterFormalEquivalenceTests.scala:74:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class Witness(equal: Int, down: Int, up: Int)
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/spinal/lib/CounterSingleAuthorityParityTests.scala:148:30: The outer reference in this type test cannot be checked at run time.
[warn]     private final case class BranchDriver(target: String, arithmetic: String)
[warn]                              ^
[warn] 1 deprecation
[warn] 84 deprecations (since 2.13.0)
[warn] 3 deprecations (since 2.13.3)
[warn] 1295 deprecations (since Increment 58)
[warn] 1383 deprecations in total; re-run with -deprecation for details
[warn] 1 feature warning; re-run with -feature for details
[warn] 32 warnings found
[info] done compiling
[info] GenericExpressionAndStreamTests:
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:21:12
[Progress] at 0.000 : Elaborate components
[Progress] at 0.331 : Checks and transforms
[Progress] at 0.578 : Generate Verilog to /tmp/morphhdl-single-source-2150708286148221722
[Done] at 0.789
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:21:13
[Progress] at 0.802 : Elaborate components
[Progress] at 0.822 : Checks and transforms
[Progress] at 0.829 : Generate Verilog to /tmp/morphhdl-single-source-2749219817304862896
[Done] at 0.851
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:21:13
[Progress] at 0.855 : Elaborate components
[Progress] at 0.871 : Checks and transforms
[Progress] at 0.876 : Generate Verilog to /tmp/morphhdl-generic-expression-test-14027580711584890272/concrete
[Done] at 0.882
NATIVE_WIRE_COMPATIBILITY_PASS NativeGenericExpressions_native (WIDTH,8)
NATIVE_WIRE_COMPATIBILITY_PASS NativeGenericExpressions_legacy_4 (WIDTH,4)
NATIVE_WIRE_COMPATIBILITY_PASS NativeGenericExpressions_legacy_8 (WIDTH,8)
NATIVE_WIRE_COMPATIBILITY_PASS NativeGenericExpressions_legacy_32 (WIDTH,32)
[info] - ordinary assignments muxes arithmetic concatenation slicing and resize reuse native Verilog emission
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:21:13
[Progress] at 1.221 : Elaborate components
[Progress] at 1.247 : Checks and transforms
[Progress] at 1.323 : Generate Verilog to /tmp/morphhdl-single-source-1327019026899716318
[Done] at 1.343
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:21:13
[Progress] at 1.346 : Elaborate components
[Progress] at 1.353 : Checks and transforms
[Progress] at 1.358 : Generate Verilog to /tmp/morphhdl-single-source-18102772738422657928
[Done] at 1.373
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:21:13
[Progress] at 1.376 : Elaborate components
[Progress] at 1.394 : Checks and transforms
[Progress] at 1.399 : Generate Verilog to /tmp/morphhdl-generic-expression-test-1028017334805921831/concrete
[Done] at 1.405
NATIVE_WIRE_COMPATIBILITY_PASS NativeStreamM2sPipe_native (WIDTH,8)
NATIVE_WIRE_COMPATIBILITY_PASS NativeStreamM2sPipe_legacy_1 (WIDTH,1)
NATIVE_WIRE_COMPATIBILITY_PASS NativeStreamM2sPipe_legacy_8 (WIDTH,8)
NATIVE_WIRE_COMPATIBILITY_PASS NativeStreamM2sPipe_legacy_32 (WIDTH,32)
[info] - the real Stream.m2sPipe path emits one parameterized module and matches its concrete native witness
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:21:13
[Progress] at 1.664 : Elaborate components
[Progress] at 1.684 : Checks and transforms
[Progress] at 1.698 : Generate Verilog to /tmp/morphhdl-single-source-13622679203575007521
[Done] at 1.713
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:21:13
[Progress] at 1.715 : Elaborate components
[Progress] at 1.720 : Checks and transforms
[Progress] at 1.721 : Generate Verilog to /tmp/morphhdl-single-source-1906927528728212819
[Done] at 1.729
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:21:14
[Progress] at 1.731 : Elaborate components
[Progress] at 1.735 : Checks and transforms
[Progress] at 1.737 : Generate Verilog to /tmp/morphhdl-generic-expression-test-2379166218283310962/concrete
[Done] at 1.740
NATIVE_WIRE_COMPATIBILITY_PASS NativeAutoResizedIncrement_native (WIDTH,3)
NATIVE_WIRE_COMPATIBILITY_PASS NativeAutoResizedIncrement_legacy_1 (WIDTH,1)
NATIVE_WIRE_COMPATIBILITY_PASS NativeAutoResizedIncrement_legacy_3 (WIDTH,3)
NATIVE_WIRE_COMPATIBILITY_PASS NativeAutoResizedIncrement_legacy_8 (WIDTH,8)
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:21:14
[Progress] at 1.876 : Elaborate components
[Progress] at 1.883 : Checks and transforms
[Progress] at 1.886 : Generate Verilog to /tmp/morphhdl-single-source-8821073382951506804
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:21:14
[Progress] at 1.899 : Elaborate components
[Progress] at 1.910 : Checks and transforms
[Progress] at 1.914 : Generate Verilog to /tmp/morphhdl-single-source-8849861446840389320
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:21:14
[Progress] at 1.928 : Elaborate components
[Progress] at 1.936 : Checks and transforms
[Progress] at 1.940 : Generate Verilog to /tmp/morphhdl-single-source-11447424434291616843
[info] - native UInt auto-resize provenance is exact and generation-local
[info] - native auto-resize provenance is captured before unnamed intermediates are removed
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:21:14
[Progress] at 1.954 : Elaborate components
[Progress] at 1.993 : Checks and transforms
[Progress] at 2.001 : Generate Verilog to /tmp/morphhdl-single-source-9749811238815540601
[Done] at 2.094
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:21:14
[Progress] at 2.098 : Elaborate components
[Progress] at 2.114 : Checks and transforms
[Progress] at 2.121 : Generate Verilog to /tmp/morphhdl-single-source-8378416035843352181
[info] - witness-inactive auto-resize provenance retains exact one-use ownership
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:21:14
[Progress] at 2.143 : Elaborate components
[Progress] at 2.157 : Checks and transforms
[Progress] at 2.160 : Generate Verilog to /tmp/morphhdl-single-source-1164689481168143492
[Done] at 2.171
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:21:14
[Progress] at 2.175 : Elaborate components
[Progress] at 2.181 : Checks and transforms
[Progress] at 2.183 : Generate Verilog to /tmp/morphhdl-single-source-2989317218602850318
[Done] at 2.198
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:21:14
[Progress] at 2.200 : Elaborate components
[Progress] at 2.206 : Checks and transforms
[Progress] at 2.208 : Generate Verilog to /tmp/morphhdl-single-source-1083287169691002874
[info] - materialized native auto-resize proves witness-equal narrowing over the complete domain
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:21:14
[Progress] at 2.218 : Elaborate components
[Progress] at 2.223 : Checks and transforms
[Progress] at 2.226 : Generate Verilog to /tmp/morphhdl-single-source-14353864102225092139
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:21:14
[Progress] at 2.235 : Elaborate components
[Progress] at 2.246 : Checks and transforms
[Progress] at 2.248 : Generate Verilog to /tmp/morphhdl-single-source-4359852782822878041
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:21:14
[Progress] at 2.255 : Elaborate components
[Progress] at 2.260 : Checks and transforms
[Progress] at 2.262 : Generate Verilog to /tmp/morphhdl-single-source-8910594766856485724
[info] - materialized native auto-resize rejects stale edges and post-capture reuse
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:21:14
[Progress] at 2.272 : Elaborate components
[Progress] at 2.383 : Checks and transforms
[Progress] at 2.385 : Generate Verilog to /tmp/morphhdl-single-source-4286619794243572331
[info] - derived packed widths are proven over the complete parameter domain
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:21:14
[Progress] at 2.414 : Elaborate components
[Progress] at 2.427 : Checks and transforms
[Progress] at 2.429 : Generate Verilog to /tmp/morphhdl-single-source-9899462686154187533
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:21:14
[Progress] at 2.437 : Elaborate components
[Progress] at 2.450 : Checks and transforms
[Progress] at 2.454 : Generate Verilog to /tmp/morphhdl-single-source-12916482943384890514
[Done] at 2.463
[info] - fixed slices reject invalid domains while explicit resize supports width crossings
[info] Run completed in 3 seconds, 464 milliseconds.
[info] Total number of tests run: 9
[info] Suites: completed 1, aborted 0
[info] Tests: succeeded 9, failed 0, canceled 0, ignored 0, pending 0
[info] All tests passed.
[success] Total time: 145 s (02:25), completed Sep 14, 2026, 6:21:14 AM
```

</details>

<details>
<summary>Scala 2.12.18: complete successful SBT output</summary>

```text
[warn] [launcher] could not parse ftp_proxy setting: java.net.MalformedURLException: unknown protocol: socks5h
[info] welcome to sbt 1.10.0 (Ubuntu Java 17.0.20)
[info] loading settings for project morphhdl-pr186-logfix-build from plugin.sbt ...
[info] loading project definition from /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/project
[info] loading settings for project all from build.sbt ...
[info] resolving key references (13791 settings) ...
[info] set current project to SpinalHDL-all (in build file:/workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/)
[info] Setting Scala version to 2.12.18 on 14 projects.
[info] Reapplying settings...
[info] set current project to SpinalHDL-all (in build file:/workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/)
[info] compiling 4 Scala sources to /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphplugin/target/scala-2.12/classes ...
[info] Non-compiled module 'compiler-bridge_2.12' for Scala 2.12.18. Compiling...
[info] compiling 2 Scala sources to /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/idslpayload/target/scala-2.12/classes ...
[info] compiling 5 Scala sources to /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphir/target/scala-2.12/classes ...
[info] compiling 6 Scala sources to /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/paramrtl/target/scala-2.12/classes ...
[info]   Compilation completed in 6.845s.
[warn] three deprecations (since 2.11.0); re-run with -deprecation for details
[warn] one warning found
[info] done compiling
[info] compiling 17 Scala sources and 10 Java sources to /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/sim/target/scala-2.12/classes ...
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphplugin/src/main/scala/morphhdl/compiler/MorphHdlTypedElaborationControlComponent.scala:34:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class ClassifiedTrees(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphplugin/src/main/scala/morphhdl/compiler/MorphHdlTypedElaborationControlComponent.scala:41:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class WildcardMembers(
[warn]                            ^
[warn] two warnings found
[info] done compiling
[info] compiling 2 Scala sources to /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/idslplugin/target/scala-2.12/classes ...
[info] done compiling
[info] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/sim/src/main/java/spinal/sim/DynamicCompiler.java: DynamicCompiler.java uses or overrides a deprecated API.
[info] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/sim/src/main/java/spinal/sim/DynamicCompiler.java: Recompile with -Xlint:deprecation for details.
[info] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/sim/src/main/java/spinal/sim/DynamicCompiler.java: DynamicCompiler.java uses unchecked or unsafe operations.
[info] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/sim/src/main/java/spinal/sim/DynamicCompiler.java: Recompile with -Xlint:unchecked for details.
[info] done compiling
[info] compiling 87 Scala sources and 2 Java sources to /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/core/target/scala-2.12/classes ...
[info] done compiling
[info] done compiling
[info] compiling 2 Scala sources to /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/backends/verilog/target/scala-2.12/classes ...
[info] done compiling
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/core/src/main/scala/spinal/core/internals/Phase.scala:3228:25: non-variable type argument spinal.core.SpinalEnum in type pattern spinal.core.SpinalEnumCraft[spinal.core.SpinalEnum] is unchecked since it is eliminated by erasure
[warn]                 case d: SpinalEnumCraft[SpinalEnum] => d.init(d.spinalEnum.elements(0))
[warn]                         ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/core/src/main/scala/spinal/core/AFix.scala:231:5: match may not be exhaustive.
[warn] It would fail on the following input: SCRAP
[warn]     roundType match {
[warn]     ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/core/src/main/scala/spinal/core/Data.scala:390:29: match may not be exhaustive.
[warn] It would fail on the following inputs: inWithNull, outWithNull
[warn]   def dirString(): String = dir match {
[warn]                             ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/core/src/main/scala/spinal/core/Data.scala:581:16: match may not be exhaustive.
[warn] It would fail on the following inputs: inWithNull, outWithNull
[warn]           that.dir match {
[warn]                ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/core/src/main/scala/spinal/core/SInt.scala:397:5: match may not be exhaustive.
[warn] It would fail on the following input: SCRAP
[warn]     roundType match {
[warn]     ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/core/src/main/scala/spinal/core/UInt.scala:279:5: match may not be exhaustive.
[warn] It would fail on the following input: SCRAP
[warn]     roundType match{
[warn]     ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/core/src/main/scala/spinal/core/fiber/AsyncThread.scala:81:5: match may not be exhaustive.
[warn] It would fail on the following input: false
[warn]     done match {
[warn]     ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/core/src/main/scala/spinal/core/internals/Phase.scala:1700:31: unreachable code
[warn]           case e: MemReadSync =>
[warn]                               ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/core/src/main/scala/spinal/core/internals/Phase.scala:3114:12: match may not be exhaustive.
[warn] It would fail on the following inputs: inWithNull, inout, outWithNull
[warn]         io.dir match {
[warn]            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/core/src/main/scala/spinal/core/internals/PhaseVerilog.scala:483:23: match may not be exhaustive.
[warn] It would fail on the following input: Nil
[warn]         val newName = IFlist match {
[warn]                       ^
[warn] four deprecations
[warn] two deprecations (since 2.12.0)
[warn] 6 deprecations in total; re-run with -deprecation for details
[warn] three feature warnings; re-run with -feature for details
[warn] 14 warnings found
[info] done compiling
[info] compiling 10 Scala sources to /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphruntime/target/scala-2.12/classes ...
[info] done compiling
[info] compiling 563 Scala sources to /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/target/scala-2.12/classes ...
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/CrossClock.scala:162:15: non-variable type argument spinal.core.Data in type pattern spinal.lib.BufferCC[spinal.core.Data] is unchecked since it is eliminated by erasure
[warn]       case c: BufferCC[Data] => {
[warn]               ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/Utils.scala:713:19: abstract type pattern T is unchecked since it is eliminated by erasure
[warn]         case bt : T => solveCombDriver(bt)
[warn]                   ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/regif/Block/RegInst.scala:94:19: non-variable type argument spinal.core.SpinalEnum in type pattern spinal.core.SpinalEnumCraft[spinal.core.SpinalEnum] is unchecked since it is eliminated by erasure
[warn]           case t: SpinalEnumCraft[SpinalEnum] => t.init(t.spinalEnum.elements(resetValue.toInt))
[warn]                   ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/Utils.scala:702:32: unreachable code
[warn]     case e: MemReadSync => body(e)
[warn]                                ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/fabric/MappedConnection.scala:24:55: match may not be exhaustive.
[warn] It would fail on the following input: Some((x: Any forSome x not in (BigInt, DefaultMapping, spinal.lib.bus.misc.AddressMapping)))
[warn]     override def transformers = MappedConnection.this.userMapping match {
[warn]                                                       ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/fabric/MappedConnection.scala:34:29: match may not be exhaustive.
[warn] It would fail on the following input: Some((x: Any forSome x not in (BigInt, DefaultMapping, spinal.lib.bus.misc.AddressMapping)))
[warn]       MappedConnection.this.userMapping match {
[warn]                             ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/fabric/MappedConnection.scala:48:5: match may not be exhaustive.
[warn] It would fail on the following input: Some((x: Any forSome x not in (BigInt, DefaultMapping, spinal.lib.bus.misc.AddressMapping)))
[warn]     userMapping match {
[warn]     ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/fabric/MappedConnection.scala:57:5: match may not be exhaustive.
[warn] It would fail on the following input: Some((x: Any forSome x not in (BigInt, DefaultMapping, spinal.lib.bus.misc.AddressMapping)))
[warn]     userMapping match {
[warn]     ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/regif/Block/RegBase.scala:325:7: Exhaustivity analysis reached max recursion depth, not all missing cases are reported.
[warn] (Please try with scalac -Ypatmat-exhaust-depth 40 or -Ypatmat-exhaust-depth off.)
[warn]       accType match {
[warn]       ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/regif/Block/RegBase.scala:356:7: Exhaustivity analysis reached max recursion depth, not all missing cases are reported.
[warn] (Please try with scalac -Ypatmat-exhaust-depth 40 or -Ypatmat-exhaust-depth off.)
[warn]       accType match {
[warn]       ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/regif/Block/RegInst.scala:320:21: match may not be exhaustive.
[warn] It would fail on the following inputs: W1CHS, W1I, W1SHS
[warn]     val ret: Bits = acc match {
[warn]                     ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/regif/Block/RegSlice.scala:35:13: match may not be exhaustive.
[warn] It would fail on the following input: Some((x: spinal.lib.bus.regif.Secure forSome x not in (spinal.lib.bus.regif.Secure.CS, spinal.lib.bus.regif.Secure.MS)))
[warn]       Option(sec) match {
[warn]             ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/tilelink/sim/Checker.scala:223:25: match may not be exhaustive.
[warn] It would fail on the following input: None
[warn]         inflightC.remove(d.source -> d.address) match {
[warn]                         ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/bus/tilelink/sim/Checker.scala:239:21: match may not be exhaustive.
[warn] It would fail on the following input: None
[warn]     inflightD.remove(e.sink) match {
[warn]                     ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/dsptool/FixData.scala:30:26: match may not be exhaustive.
[warn] It would fail on the following input: SCRAP
[warn]       val rounded = this.roundType match {
[warn]                          ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/eda/TimingExtractor.scala:143:49: match may not be exhaustive.
[warn] It would fail on the following input: None
[warn]     case bt : BaseType if bt.isComb => bt.getTag(classOf[ClockDomainTag]) match {
[warn]                                                 ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/io/InOutVecToBits.scala:22:7: match may not be exhaustive.
[warn] It would fail on the following inputs: inWithNull, inout, outWithNull
[warn]     e.getDirection match {
[warn]       ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/io/InOutVecToBits.scala:36:13: match may not be exhaustive.
[warn] It would fail on the following inputs: inWithNull, inout, outWithNull
[warn]         key.getDirection match {
[warn]             ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/pipeline/Pipeline.scala:171:22: match may not be exhaustive.
[warn] It would fail on the following input: Some((x: Any forSome x not in Pipeline.this.ConnectionModel))
[warn]       stageDriver.get(stage) match {
[warn]                      ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/system/dma/sg/MemoryCore.scala:112:7: match may not be exhaustive.
[warn] It would fail on the following input: (true, true)
[warn]       (p.writes(self).absolutePriority, p.writes(other).absolutePriority) match {
[warn]       ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/system/dma/sg/MemoryCore.scala:198:7: match may not be exhaustive.
[warn] It would fail on the following input: (true, true)
[warn]       (p.reads(self).absolutePriority, p.reads(other).absolutePriority) match {
[warn]       ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/com/usb/ohci/UsbOhciTilelinkFiber.scala:46:17: comparing values of types spinal.core.ClockDomain and spinal.core.fiber.Handle[spinal.core.ClockDomain] using `==` will always yield false
[warn]       if(ctrlCd == phyCd) {
[warn]                 ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/lib/src/main/scala/spinal/lib/generator/ClockDomainGenerator.scala:272:5: multiline expressions might require enclosing parentheses; a value can be silently discarded when Unit is expected
[warn]     generator
[warn]     ^
[warn] 23 deprecations
[warn] two deprecations (since 2.12.0)
[warn] one deprecation (since 2022-12-31)
[warn] one deprecation (since 2026.12.30)
[warn] 27 deprecations in total; re-run with -deprecation for details
[warn] 6 feature warnings; re-run with -feature for details
[warn] 29 warnings found
[info] done compiling
[info] compiling 25 Scala sources to /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/frontend/target/scala-2.12/classes ...
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/frontend/src/main/scala/morphhdl/frontend/Memory.scala:3:27: imported `HardType` is permanently hidden by definition of method HardType in package frontend
[warn] import spinal.core.{Data, HardType, Mem => SpinalMem}
[warn]                           ^
[warn] two feature warnings; re-run with -feature for details
[warn] two warnings found
[info] done compiling
[info] compiling 69 Scala sources to /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/target/scala-2.12/classes ...
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/main/scala/spinal/core/internals/TypedBalancedReductionCertifiedCallbackPolicy.scala:4:26: imported `Modifier` is permanently hidden by definition of class Modifier in package internals
[warn] import java.lang.reflect.Modifier
[warn]                          ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl-passes/examples/NamedWireAliasNativeBridge.scala:90:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class ForwardRewrite(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl-passes/examples/NamedWireAliasNativeBridge.scala:94:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class PreferredSourceRewrite(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl-passes/examples/NamedWireAliasNativeBridge.scala:180:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class NativeCandidate(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl-passes/examples/NamedWireAliasNativeBridge.scala:190:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class NativeProof(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl-passes/examples/NamedWireExpressionNativeBridge.scala:141:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class NativeCandidate(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl-passes/examples/NamedWireExpressionNativeBridge.scala:154:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class NativeProof(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl-passes/examples/NamedWireExpressionNativeBridge.scala:395:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class CanonicalSnapshot(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl-passes/examples/UnnamedWireAliasNativeBridge.scala:114:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class NativeCandidate(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl-passes/examples/UnnamedWireAliasNativeBridge.scala:122:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class NativeProof(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala:2563:30: The outer reference in this type test cannot be checked at run time.
[warn]     private final case class ModularUIntFacts(
[warn]                              ^
[warn] 38 deprecations (since Increment 58); re-run with -deprecation for details
[warn] one feature warning; re-run with -feature for details
[warn] 13 warnings found
[info] done compiling
[info] compiling 167 Scala sources and 2 Java sources to /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/target/scala-2.12/test-classes ...
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/TypedElaborationControlTests.scala:112:11: comparing values of types spinal.core.ElabInt and String using `==` will always yield false
[warn]     width == "WIDTH"
[warn]           ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/NativeAxi4SlaveFactoryFormalEquivalenceTests.scala:71:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class GeneratedDuts(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/NativeAxi4SlaveFactoryFormalEquivalenceTests.scala:76:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class PreparedDuts(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/NativeLibraryMigrationFormalEquivalenceTests.scala:78:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class GeneratedDuts(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/NativeLibraryMigrationFormalEquivalenceTests.scala:83:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class PreparedDuts(candidate: Path, concrete: Path)
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/NativeStreamFifoCCCdcProofTests.scala:81:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class ClockSchedule(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/NativeStreamFifoCCFormalEquivalenceTests.scala:138:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class ClockRatio(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/NativeStreamFifoCCFormalEquivalenceTests.scala:143:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class Configuration(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/NativeStreamFifoCCFormalEquivalenceTests.scala:149:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class GeneratedDuts(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/NativeStreamFifoCCFormalEquivalenceTests.scala:153:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class PreparedDuts(candidate: Path, reference: Path)
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/NativeStreamFifoFormalEquivalenceTests.scala:162:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class GeneratedDuts(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/NativeStreamFifoFormalEquivalenceTests.scala:167:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class PreparedDuts(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/NativeStreamFifoFormalEquivalenceTests.scala:172:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class GeneratedFormalHelperDuts(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/NativeStreamFifoFormalEquivalenceTests.scala:177:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class PreparedFormalHelperDuts(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/TypedParameterizedVecFormalEquivalenceTests.scala:80:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class Witness(width: Int, depth: Int)
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/TypedParameterizedVecFormalEquivalenceTests.scala:81:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class GeneratedDuts(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/TypedParameterizedVecFormalEquivalenceTests.scala:85:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class PreparedDuts(candidate: Path, reference: Path)
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/TypedParameterizedVecTests.scala:456:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class PortExpectation(
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/TypedParameterizedVecTests.scala:1550:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class AlgebraAtom(value: String) extends Algebra
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/TypedParameterizedVecTests.scala:1551:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class AlgebraSum(terms: Vector[Algebra]) extends Algebra
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/TypedParameterizedVecTests.scala:1552:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class AlgebraProduct(factors: Vector[Algebra]) extends Algebra
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/TypedParameterizedVecTests.scala:1553:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class AlgebraDifference(left: Algebra, right: Algebra) extends Algebra
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/TypedPrimitiveClosureFormalEquivalenceTests.scala:291:30: The outer reference in this type test cannot be checked at run time.
[warn]     private final case class GeneratedDuts(
[warn]                              ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/TypedPrimitiveClosureFormalEquivalenceTests.scala:296:30: The outer reference in this type test cannot be checked at run time.
[warn]     private final case class PreparedDuts(candidate: Path, concrete: Path)
[warn]                              ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/morphhdl/TypedStreamWidthAdapterFormalEquivalenceTests.scala:74:28: The outer reference in this type test cannot be checked at run time.
[warn]   private final case class Witness(equal: Int, down: Int, up: Int)
[warn]                            ^
[warn] /workspace/scratch/300cb3041d1c/MorphHDL-pr186-logfix/morphhdl/src/test/scala/spinal/lib/CounterSingleAuthorityParityTests.scala:148:30: The outer reference in this type test cannot be checked at run time.
[warn]     private final case class BranchDriver(target: String, arithmetic: String)
[warn]                              ^
[warn] 1263 deprecations (since Increment 58); re-run with -deprecation for details
[warn] one feature warning; re-run with -feature for details
[warn] 28 warnings found
[info] done compiling
[info] GenericExpressionAndStreamTests:
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:24:31
[Progress] at 0.000 : Elaborate components
[Progress] at 0.347 : Checks and transforms
[Progress] at 0.605 : Generate Verilog to /tmp/morphhdl-single-source-12602008519961311885
[Done] at 0.820
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:24:32
[Progress] at 0.835 : Elaborate components
[Progress] at 0.861 : Checks and transforms
[Progress] at 0.868 : Generate Verilog to /tmp/morphhdl-single-source-4299501466554046461
[Done] at 0.888
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:24:32
[Progress] at 0.891 : Elaborate components
[Progress] at 0.910 : Checks and transforms
[Progress] at 0.915 : Generate Verilog to /tmp/morphhdl-generic-expression-test-6694382343359920386/concrete
[Done] at 0.921
NATIVE_WIRE_COMPATIBILITY_PASS NativeGenericExpressions_native (WIDTH,8)
NATIVE_WIRE_COMPATIBILITY_PASS NativeGenericExpressions_legacy_4 (WIDTH,4)
NATIVE_WIRE_COMPATIBILITY_PASS NativeGenericExpressions_legacy_8 (WIDTH,8)
NATIVE_WIRE_COMPATIBILITY_PASS NativeGenericExpressions_legacy_32 (WIDTH,32)
[info] - ordinary assignments muxes arithmetic concatenation slicing and resize reuse native Verilog emission
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:24:32
[Progress] at 1.267 : Elaborate components
[Progress] at 1.287 : Checks and transforms
[Progress] at 1.362 : Generate Verilog to /tmp/morphhdl-single-source-7370980543335489301
[Done] at 1.384
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:24:32
[Progress] at 1.387 : Elaborate components
[Progress] at 1.403 : Checks and transforms
[Progress] at 1.410 : Generate Verilog to /tmp/morphhdl-single-source-2675151218778512485
[Done] at 1.430
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:24:32
[Progress] at 1.433 : Elaborate components
[Progress] at 1.451 : Checks and transforms
[Progress] at 1.456 : Generate Verilog to /tmp/morphhdl-generic-expression-test-9068458614213238584/concrete
[Done] at 1.461
NATIVE_WIRE_COMPATIBILITY_PASS NativeStreamM2sPipe_native (WIDTH,8)
NATIVE_WIRE_COMPATIBILITY_PASS NativeStreamM2sPipe_legacy_1 (WIDTH,1)
NATIVE_WIRE_COMPATIBILITY_PASS NativeStreamM2sPipe_legacy_8 (WIDTH,8)
NATIVE_WIRE_COMPATIBILITY_PASS NativeStreamM2sPipe_legacy_32 (WIDTH,32)
[info] - the real Stream.m2sPipe path emits one parameterized module and matches its concrete native witness
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:24:32
[Progress] at 1.705 : Elaborate components
[Progress] at 1.717 : Checks and transforms
[Progress] at 1.733 : Generate Verilog to /tmp/morphhdl-single-source-311019672255444436
[Done] at 1.753
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:24:32
[Progress] at 1.755 : Elaborate components
[Progress] at 1.761 : Checks and transforms
[Progress] at 1.764 : Generate Verilog to /tmp/morphhdl-single-source-9567494346292802464
[Done] at 1.772
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:24:32
[Progress] at 1.774 : Elaborate components
[Progress] at 1.779 : Checks and transforms
[Progress] at 1.781 : Generate Verilog to /tmp/morphhdl-generic-expression-test-10385308891600031146/concrete
[Done] at 1.784
NATIVE_WIRE_COMPATIBILITY_PASS NativeAutoResizedIncrement_native (WIDTH,3)
NATIVE_WIRE_COMPATIBILITY_PASS NativeAutoResizedIncrement_legacy_1 (WIDTH,1)
NATIVE_WIRE_COMPATIBILITY_PASS NativeAutoResizedIncrement_legacy_3 (WIDTH,3)
NATIVE_WIRE_COMPATIBILITY_PASS NativeAutoResizedIncrement_legacy_8 (WIDTH,8)
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:24:33
[Progress] at 1.932 : Elaborate components
[Progress] at 1.938 : Checks and transforms
[Progress] at 1.940 : Generate Verilog to /tmp/morphhdl-single-source-237574120312089478
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:24:33
[Progress] at 1.952 : Elaborate components
[Progress] at 1.963 : Checks and transforms
[Progress] at 1.970 : Generate Verilog to /tmp/morphhdl-single-source-17008553095591935823
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:24:33
[Progress] at 1.983 : Elaborate components
[Progress] at 1.989 : Checks and transforms
[Progress] at 1.992 : Generate Verilog to /tmp/morphhdl-single-source-4834082258652024895
[info] - native UInt auto-resize provenance is exact and generation-local
[info] - native auto-resize provenance is captured before unnamed intermediates are removed
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:24:33
[Progress] at 2.007 : Elaborate components
[Progress] at 2.037 : Checks and transforms
[Progress] at 2.049 : Generate Verilog to /tmp/morphhdl-single-source-14352174783857028625
[Done] at 2.129
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:24:33
[Progress] at 2.132 : Elaborate components
[Progress] at 2.140 : Checks and transforms
[Progress] at 2.143 : Generate Verilog to /tmp/morphhdl-single-source-13432479079055749197
[info] - witness-inactive auto-resize provenance retains exact one-use ownership
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:24:33
[Progress] at 2.161 : Elaborate components
[Progress] at 2.176 : Checks and transforms
[Progress] at 2.182 : Generate Verilog to /tmp/morphhdl-single-source-11521260070711434324
[Done] at 2.198
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:24:33
[Progress] at 2.202 : Elaborate components
[Progress] at 2.217 : Checks and transforms
[Progress] at 2.220 : Generate Verilog to /tmp/morphhdl-single-source-6236664092626758085
[Done] at 2.237
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:24:33
[Progress] at 2.240 : Elaborate components
[Progress] at 2.246 : Checks and transforms
[Progress] at 2.249 : Generate Verilog to /tmp/morphhdl-single-source-17461811234167223528
[info] - materialized native auto-resize proves witness-equal narrowing over the complete domain
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:24:33
[Progress] at 2.260 : Elaborate components
[Progress] at 2.267 : Checks and transforms
[Progress] at 2.270 : Generate Verilog to /tmp/morphhdl-single-source-8988894226179873801
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:24:33
[Progress] at 2.280 : Elaborate components
[Progress] at 2.292 : Checks and transforms
[Progress] at 2.295 : Generate Verilog to /tmp/morphhdl-single-source-16945890322555524220
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:24:33
[Progress] at 2.307 : Elaborate components
[Progress] at 2.324 : Checks and transforms
[Progress] at 2.326 : Generate Verilog to /tmp/morphhdl-single-source-6675590047263333224
[info] - materialized native auto-resize rejects stale edges and post-capture reuse
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:24:33
[Progress] at 2.336 : Elaborate components
[Progress] at 2.474 : Checks and transforms
[Progress] at 2.476 : Generate Verilog to /tmp/morphhdl-single-source-15480081070018871830
[info] - derived packed widths are proven over the complete parameter domain
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:24:33
[Progress] at 2.507 : Elaborate components
[Progress] at 2.524 : Checks and transforms
[Progress] at 2.526 : Generate Verilog to /tmp/morphhdl-single-source-5773791562254369202
[Runtime] SpinalHDL dev    git head : b88e84169f226218f8af7815dda76872f8ed7605
[Runtime] JVM max memory : 5120.0MiB
[Runtime] Current date : 2026.09.14 06:24:33
[Progress] at 2.533 : Elaborate components
[Progress] at 2.537 : Checks and transforms
[Progress] at 2.540 : Generate Verilog to /tmp/morphhdl-single-source-12381478503094506136
[Done] at 2.548
[info] - fixed slices reject invalid domains while explicit resize supports width crossings
[info] Run completed in 3 seconds, 569 milliseconds.
[info] Total number of tests run: 9
[info] Suites: completed 1, aborted 0
[info] Tests: succeeded 9, failed 0, canceled 0, ignored 0, pending 0
[info] All tests passed.
[success] Total time: 155 s (02:35), completed Sep 14, 2026, 6:24:33 AM
```

</details>
