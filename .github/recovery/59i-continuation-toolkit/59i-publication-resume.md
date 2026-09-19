# Increment 59i publication checkpoint

Continue PR #177 in `pysolvesemi/MorphHDL`, feature
`agent/increment-59i-combined-reduction-closure`, target `parameterized-verilog`.
Keep the PR draft and the roadmap unchecked until all required final-head gates pass.
Standing authorization covers implementation, publication, failed-first CI, and
merge after qualification. Preserve history and use non-force ref updates.

## Verified fresh diagnostic

Run **35449602915**, attempt 1, completed successfully on both Scala versions.
Controller `c1d7534b1c62469a0d1138fa35d50ab09d974ecc`.
Actual source `545134a42dd200c5cee679db5dde0fea0acb15c9`, source tree
`cab84063b2933dd146f2b5ff37aab42f7ea0b191`.
Independent inspection completed using `verify-local-enable-diagnostic-successor-v2.py`
and `controller-development-v6` in success mode. Receipt:
`evidence/545134a4-fresh-success-verification.json`.

- Scala 2.12.18 artifact 10587873861, ZIP SHA-256
  `8a91b4300bf8fcf41671b48efb89277fd2645de440624f7cdcc0d8d5abd5a0db`.
- Scala 2.13.12 artifact 10588187358, ZIP SHA-256
  `58d64eb204ab22d0ee085d5c57f451fe4e68f2639699870a7e87e8511b3b36a9`.
- Each lane: 138 passing tests in 10 suites; 10,619 retained files with complete
  hash coverage, including hidden records; 124 original RTL files unchanged.
- Each lane: 96 main native cases / 192 comparisons, 32 bounded 18-step cases,
  1,024 output-bit obligations plus 16 normalized-baseline obligations,
  two real main RTL mutations. No unbounded induction claim.
- Each lane: 6 supplemental cases / 24 comparisons / one real mutation.
  Supplemental formal explicitly reports `not-run`.
- All test identities and original generated RTL agree across Scala lanes.

## Exact local seal

Seal **58fb59773a2deebba0251b5b626c19a22453f0a4**, tree
`5ae0ef04c0dc730e49cbb499f2eca6d65b4bf60f`, sole parent the source above.
The seal changes exactly the production successor manifest and its helper hash
slot. Catalogue SHA-256:
`846260ee4f99965c38224fe32a426a5ac474c67cb561f499b8097fbe3c8a9863`.
322 source records and 93 target records; no historical record removed.
All 11 previously unpublished commits plus this seal remain exact, including
the side history, ordered merge parents, identities and timezone offsets.
Every commit contains `[skip ci]` so source publication does not launch broad CI.

The source checkout is `/workspace/scratch/68d458e0e937/59i-dev`.
The publication bundle/controller is `publication-controller-ready-v2/`.
The first build passed the seal verifier but stopped before payload creation:
Git refuses a bundle advertised only by a bare SHA. The builder now rechecks
the clean exact seal and advertises `HEAD`, then verifies its advertised SHA.
This packaging repair changes no source, seal, runtime, or production gate.
The corrected bundle passed an actual offline reconstruction in a fresh worktree,
including the original 308-file predecessor audit, all 12 raw commit identities,
the exact seal tree, 1,423 runtime file identities, the pinned gitlink, and a clean
restored checkout. The actual seal verifier separately passed 322 records.
Read `publication-controller/README.md` for the three bounded handoffs:
source-check receipt -> connector creates exact trees -> exact commit staging
receipt -> connector fast-forwards feature -> controller dispatches only
`increment-59i-local-enable-committed-head.yml`.
The controller never updates refs. Check source receipts and actual returned
SHAs before the corresponding connector mutations.

At seal creation PR #177 is still draft/open at
`90b7fc8f13f2c53dbb6f7f8ab51f4e43cd486be6`, target
`e0e9f1d7089d3aa513677a2b94c63eb4a7a7791d`, recovery ref
`c1d7534b1c62469a0d1138fa35d50ab09d974ecc`.
Refresh live refs, attempts, and comments before writes; preserve newer work.
The seal is local until a verified publication handoff advances the feature.

## Remaining qualification

Fresh diagnostic success is not committed-head qualification. Execute all 21
source checks during staging, then the four-job mandatory local-enable workflow
on this exact seal. Only after its verified success may the full fleet run.
The refreshed local full-CI manifest and evidence plan cover 52 applicable
workflows, 149 required jobs, two allowed skipped publishers, and 94 expected
artifacts. These are requirements, not executed passes. Preserve all 11 earlier
failed-first replacements as evidence for their historical sources only.
Use missing-only dispatch and explicit diagnosed failed-jobs retries; do not
duplicate active/successful runs. Verify actual XML, RTL, proof and mutation
contents before completion and merge. Avoid duplicate broad post-merge CI.

Hourly continuation automation: `6aae834192c8819195f25e56cdc525f7`.
Keep its latest prompt aligned with the actual controller/run and publication
phase. The source staging controller has 60-minute connector handoff windows;
perform those handoffs during an active continuation, without waiting another
hour after the artifacts become available.
