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
