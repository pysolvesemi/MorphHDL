# Active 59i integration after the PR190 target movement

This checkpoint supersedes publication instructions that still name target
`e0e9f1d7089d3aa513677a2b94c63eb4a7a7791d` as the live branch head.

Current unsealed development merge:
`ccec54986c70077e361f291cd322f0aa547aee15`, tree
`daa5f39b8f6f94a89073f0fad5a160b69df3ebc5`, exact ordered parents:
`58fb59773a2deebba0251b5b626c19a22453f0a4` and
`4b8a86e25f5a1a3f0cb4c37dc537a8dd8aa7b097`.
The second parent is the merged PR190 target, tree
`ebe59eecbc8f550d265e78c717fb093603329055`; common base is the older e0e9 target.

PR177 remains draft/open on feature
`agent/increment-59i-combined-reduction-closure` at
`90b7fc8f13f2c53dbb6f7f8ab51f4e43cd486be6` until legitimate source publication.
Its API base SHA remained stale at e0e9 even after the target moved; always
read the actual branch ref before deciding a target is unchanged.

The earlier source545134a4 and seal58fb remain preserved and independently
verified checkpoints. Fresh diagnostic35449602915 passed both Scala versions
and all retained evidence checks. Publication staging35456999564 failed at
the live target guard before the 21 source checks, blob uploads, tree/commit
staging, feature update, or runtime dispatch. Artifact10587729745 has ZIP hash
`e684c9e1468ca59ea72fd80a0fdcbddd1d8ced6865c1a5ea24ea57382c515029`; its three
files are the authenticated bundle, successful original predecessor audit,
and exact reconstruction receipt. No failed source gate was waived.

The integration preserves all compiler/test bytes from the exact Git merge.
Independent local review checked 1,426 Scala/build source identities. Four
conflicts were resolved in CI/review metadata, not compiler/test bodies.
The complete inventory now includes PR190's 37 added tests: 2,307 expected
testcases in 230 suites, with all 590 test-source hashes checked. All 30
inventory controls passed. These checks are not current Scala/RTL execution.
The source helper remains explicitly UNSEALED and retains seal58fb's manifest.

## New targeted development diagnostic

Builder: `build-local-enable-pr190-diagnostic.py`.
Templates: `controller-development-pr190-templates/`.
Ready controller: `controller-development-pr190-v1/`.
Bundle: 391,730 bytes, SHA-256
`37f68cc82a38eec320c2177408e871c7cf8affa6dc1f90d65e3be533bab4bf98`;
compressed SHA-256
`491ed813ef30aed63211e8679f7e74e54171a9bf5b0f5796df4cd0a05c37c9d4`.
It preserves 42 commits beyond published90b and 72 changed paths.
An actual clean reconstruction passed both frozen predecessor audits
(308 records at90b and322 at58fb), all exact history/tree checks, and explicit
target merge binding. Negative controls reject wrong target, parent tree,
common base, or missing history. No HDL tool ran in these local checks.

The diagnostic retains the original 138-case/10-suite failed local-enable
slice on both Scala versions, fresh A/B main+supplemental RTL, strict tools,
native comparisons, all bounded proofs, and actual mutants. Complete hidden
file retention remains required. It is not full CI or source qualification.

After actual CI completion use `verify-local-enable-pr190-diagnostic.py` with
`verify-local-enable-diagnostic-v5.py`, the exact controller payload and suite
inventory, both actual ZIPs, and independently read GitHub SHA-256 digests.
Use unique extraction/output paths. Success requires both Scala lanes, all
original proof/mutation/RTL checks, and the extra target-integration identity
and preserved schema4 audit receipts. Do not use the older checker-only
successor wrapper: it correctly rejects the new target/runtime delta.

## Source review still required

Extend the production successor lifecycle for this exact new merge while
preserving and replaying the complete old schema4 certificate. Preserve all
old negative controls and exact old schema1/2/3/4 semantics. Review the exact
PR190 target inventory, compose target/predecessor projections, and update
the dependent checker hashes and current-source routing. New native/CI/source
tests must cover this current merge; frozen target checks alone are historical
evidence. Do not merely change target constants or relax the source gate.

The two-file successor seal can follow a reviewed audit-only descendant of the
diagnostic source, provided all runtime/build/oracle/hardware-checker bytes
remain identical. Otherwise require a changed-source diagnostic again.
New publication must preserve all existing ordered history. The old schema4
seal builder/publication controller/full-CI dispatcher are not ready for the
new target. Refresh their exact source/target/lifecycle inputs only after the
new review is complete; keep all 21 source obligations and add new target
review controls. Refresh the full-CI inventory for current workflows, the
sequential obligations, and 2,307 expected tests / 230 suites.

Only after the legitimate committed-head local-enable qualification passes
may full final-head CI run. Keep the roadmap unchecked and PR draft until all
required gates and actual retained evidence pass, then complete and merge
under standing authorization. Keep the existing hourly continuation updated;
never duplicate runs or blindly retry the stale staging controller.
