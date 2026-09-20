The companion `toolkit.tar.xz` contains the scripts, reviewed evidence summaries, refreshed CI map/manifest/plan, and an internal per-file `manifest.json`. Verify archive SHA-256 `8abbd81f4bfb5bf83c9d9dc6b113457d4afb43f2f56d01ec1faec4638fe604f7`, extract to a new local directory, and verify every internal manifest hash before running the scripts. Relative tool paths in the continuation below refer to that extracted directory.

# Increment 59i continuation — 20 September 2026

Continue existing pysolvesemi/MorphHDL PR177, feature
agent/increment-59i-combined-reduction-closure, target parameterized-verilog.
Do not start another increment or feature branch, force push, weaken gates,
duplicate CI, or message other people. Preserve current concurrent work.
Refresh actual refs, current run attempts and jobs before action.

## Current source and run

- Published PR head/seal: b6f1fefb531ca4cb5aca266628dc29093f6bbafe.
- Seal tree: 3da204d0bc088d904c7d3583d38a695a2ebc35b2.
- Sole source parent: 1bea280f891717660ca4f332ec6e08754ef59fbd.
- Source tree: a626f5f558da12cdc2df72361d641d584bea00bb.
- Current target: 4b8a86e25f5a1a3f0cb4c37dc537a8dd8aa7b097.
- Target tree: ebe59eecbc8f550d265e78c717fb093603329055.
- Current targeted committed-head qualification: 35491000802, attempt 1.
- This run was active at handoff; no passing result is claimed for b6f1 yet.
- PR stays draft and roadmap remains unchecked. Full CI has not started.

The source-audit repair was preserved in four exact commits, in order:
12a2f48878fd91e2a2fb4b90cfb702138e6bd4b4,
46a2f635b7232930d047b34b25aea22fed2094cb,
1bea280f891717660ca4f332ec6e08754ef59fbd,
b6f1fefb531ca4cb5aca266628dc29093f6bbafe.
Feature advanced without force from 474480ba3890ed1c6dc8b8ab330086a87a962eb9.
The repair corrects the historical PR189 router digest and keeps the actual
historical merge parents separate from the changing successor certificate.
It adds an independent Git-parent assertion and updates dependent reviewer
hashes and legitimate source seals. There is no generated-Verilog effect.

## Resolved failures and publication evidence

Staging 35473599529 failed its final source command with the exact exception
`RuntimeError: PR189 historical router changed`. Original artifact10594750091
ZIP SHA256 c5e6e90ca36d099c4c1d7d2a2f3bbdc01b93b5b792e8333ef9001109892e6c62
was downloaded and checked; the failing log is retained here.

Newer staging35482688853, controller e476a601, passed source preparation but
timed out waiting for tree c93a386b1ec1bb69d336eed2d240d0778e02f1b6.
Its terminal job106003216642 confirms the one-hour publication handoff timeout.
Do not rerun either superseded controller.

Replacement staging35482814228 on immutable controller
af5af23455a7d0be29f0dca01eefcdad1889d752 PASSED all25 source commands and both
publication handoffs. Exact source/commit/dispatch receipts were independently
checked, with every command identity, zero return code and log hash, exact bundle,
four raw commits, trees, ordered parents and target identity preserved.
The complete18 historical PR189 tests ran successfully as part of command25.
All1847 runtime/build/resource/hardware-checker files and one gitlink match the
successful diagnostic ccec54986 and previously qualified PR head474480ba.

Artifact IDs and independently fetched ZIP SHA256:

- Source10598831677: 659dc079640ebd3fbe9206eba48ba1e7aef7dbf152f3390667dbf5260c8f17f5.
- Commits10598782892: 35490719414bcd0fe4b486ae37af3d650c610c38da05a6d86c987728f9d75d7c.
- Dispatch10599052308: 45d2e03e12afa2fd850d746189f66b58f295b090b8a6b8c376d2cc0c9df5300f.

Connector-created Git objects became visible to Actions after publishing
recovery witness commit66e7b25a55c385e356fdcdf28c50b5f669b122b7. Each embedded
tree's gitlink had a corresponding .gitmodules entry, avoiding the earlier
checkout failure. This continuation commit removes the completed witnesses and
restores the original .gitmodules; exact objects remain reachable in history.
Recovery transport commits must never be merged into feature/target.

## Earlier real qualification, distinct from the current head

Committed-head run35474311922 PASSED on474480ba, with source, both Scala lanes
and cross-Scala jobs. Three original artifact ZIPs were downloaded and hashed;
verify-local-enable-committed.py was independently scoped to that exact head,
treebc0e7eb3d014f6fdce83e2506dc7ad9bf7c52438 and parent7babffa1.
The compact receipt and fresh run/jobs/artifact metadata are retained here.
It verifies138 exact passing XML cases/10suites per Scala,124 original RTL files,
10617 retained file hashes per lane,192 main and24 supplemental comparisons,
32 bounded18-step cases/1024 output-bit plus16 baseline obligations,
2main+1supplemental actual RTL mutations, and cross-Scala identity.
Supplemental formal remains not-run; no unbounded induction is claimed.
Never count this historical result as b6f1 qualification.

## Next action: resolve targeted35491000802, then full CI

Do not dispatch another local-enable run while35491000802 is active. Monitor
hourly through automation6aae834192c8819195f25e56cdc525f7. If it fails, inspect
actual logs and retained evidence; fix only the diagnosed cause and preserve
all tests, proof bounds, mutations, source guards and original certificates.

After all four jobs pass, fetch fresh raw run/jobs/artifact JSON and download
the source and both Scala ZIPs. Verify each ZIP against its API digest, then run
the included verify-local-enable-committed.py against the exact source repository
with --run-json FILE --jobs-json FILE --source-artifact ZIP SHA256 and two
--artifact SCALA ZIP SHA256 arguments. Use a NEW --extract-root and --output.
This copy pins b6f1/source1bea/tree3da2; it rejects earlier-head/diagnostic evidence.
It reuses only pinned hardware-validation functions from the unchanged
verify-local-enable-diagnostic-v5.py. No evidence may be rewritten to fit it.

The full-CI package was refreshed OFFLINE for b6f1:
workflow-map.json, dispatch-manifest.json and evidence-plan.json retain exactly
53 applicable workflows,150 required jobs,2 intentional publisher skips,
95 expected artifacts and2307 expected tests/230suites. There were zero network
requests or dispatches while generating this plan. The dispatcher and monitor
code are unchanged from the reviewed transport toolkit. Earlier README review
history is background; this continuation defines current source/run identities.

Only after current-head local-enable evidence passes, refresh actual PR/target
refs, existing same-head runs and attempts, and every workflow hash. The prepared
full-ci-controller-transport/dispatch.py must validate all11 historical failed-first
replacement identities plus the exact current local-enable pass. It retains
the authoritative target-ref check and exact feature/source/draft-PR guards.

Publish the existing full-CI workflow template on the EXISTING recovery branch,
initially mode=plan, with source1bea, sealb6f1 and local-enable-run35491000802.
Use .github/recovery/59i-full-ci/dispatch.py and dispatch-manifest.json and the
existing .github/workflows/increment-59i-full-ci-controller.yml path. Review the
plan, then dispatch only missing applicable workflows, reusing successful or
active same-head runs. Never duplicate the passed local-enable workflow.
Preserve dispatch journals before retries; ambiguous POST outcomes need live
reconciliation. Retry only concretely diagnosed failed jobs.

Inspect full-CI results with full-ci-monitor-transport/monitor.py and the prepared
evidence-plan.json. Required source/tests/proof steps and every expected artifact
must pass on the exact final head. Historical results do not become final-head
passes. Complete TODO and merge only after the required final qualification.
Report actual Scala/generated-Verilog examples at completion. Avoid duplicate
broad post-merge CI. Pause hourly automation after verified completion.

## Reproducibility

manifest.json hashes all other files in this directory. Verify it before reuse.
The local source checkout at this handoff was
/workspace/scratch/b64c9bb7f729/MorphHDL at b6f1, clean. Local controller checkout
/workspace/scratch/68d458e0e937/59i-controller preserves af5af234 unchanged.
Recover canonical Git history from the existing remote branches if scratch is
pruned; do not reconstruct source from conversational fragments.
