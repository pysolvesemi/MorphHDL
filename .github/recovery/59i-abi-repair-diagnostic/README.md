# 59i failed-job repair diagnostic — 20 September 2026

This is a proposed, unsealed repair on the existing recovery branch. It does
not advance the feature, qualify source or hardware, dispatch full CI, or merge.
The published feature remains 883c5d8f088a0e2eab35592cf171d87792d30bf4.

## Actual failures

- Run35527122356: both Scala jobs fail test-increment-59g-report-inventory.py
  because its whole-workflow comparison still expects timeout120 rather than
  the already reviewed timeout240. All other18 tests passed.
- Run35527089532: jobs106121030587/106121030639 fail the original exact-baseline
  JVM ABI check, with13 missing/changed signatures in each Scala lane. Upstream
  idslplugin/core/lib ABI checks passed; the morph report package failed.
  This is not transient and must not be blindly rerun.
- Original ABI artifacts10610292938/10610233257 were downloaded and verified
  against their API ZIP digests and actual contents. Their source-identity.txt
  binds883c5d8f08 and treef2219f49. ZIP SHA256 respectively:
  1e30d76f011140815cf4f1fc0ac221b117355040a804bdddfe4ac07f5dbeac43
  0bc13bf05f6c9e6cb8efa9c22c7d4e1fb383fee07b1f98aaebb393cd29a9e1c3

The original dispatcher ZIP10610077923 was independently downloaded and
digest-verified again. Every one of its16 requested intents maps one-to-one to
an actual candidate workflow_dispatch run. reconciliation.json records these
bindings and the observed job states. It is a snapshot, not a pass receipt.
Do not rerun the dispatcher or overwrite its original artifact journal.

## Proposed repair

repair.patch SHA256:
e664c4b0f35764cdc5e037925b3a3a171213536de62a3cf245e4b830f93ae258

Eight changed paths: seven runtime source files and the59g inventory test.
The ABI checker, baseline7f355a85, workflows, existing hardware tests and
immutable source certificates are unchanged.

- Restore old JVM overloads and auxiliary constructors using the original
  no-capture/no-local-enable semantics; retain all newer entry points.
- Restore three Scala-mangled nested-class accessor descriptors with delegates
  to the checked implementations. Composite entry performs callback admission.
- Give operationKey its former Vector return descriptor. Both current branches
  already return vectors; their elements and equality semantics are unchanged.
-59g's exact comparison reverses exactly the reviewed120-to240 budget change
  alongside the original count/enrollment edits. No commands are dropped or
  normalized. New controls reject wrong/duplicate budgets and unrelated changes.

Local59g inventory:22 tests passed. git diff --check and exact reverse patch
application passed. Scala compilation/ABI/runtime tests have NOT yet passed.
Do not claim generated-Verilog equivalence from this source inspection.

The first diagnostic run35530953278 failed during Morph compilation in both
Scala lanes. The new five-argument compatibility overload made the existing
capture callback's final `ArrayBuffer.+=` expression ambiguous: its inferred
return type was `observed.type`, not `Unit`. The successor patch adds an
explicit terminal `()` to that existing callback. This is the sole change from
the first proposed patch; the two-lane diagnostic must still prove compilation,
the unchanged exact ABI check and all affected replay suites.

## Diagnostic execution

The recovery-only workflow applies the digest-pinned patch to a clean detached
883c5d8f08 checkout, verifies exactly8 paths, runs59g's22 tests, builds the
unchanged7f355a85 baseline and patched Morph package in both Scala versions,
then runs the UNCHANGED ABI checker and9 affected replay suites (113 tests).
It retains command return codes/log digests, exact patch, JAR hashes and XML
reports. Every receipt explicitly denies source/hardware/merge qualification.
It cannot write the repository or dispatch other workflows.

Do not upload build products from the temporary baseline worktree. The artifact
list retains only diagnostic evidence. Check live runs before another recovery
update: do not duplicate an active successful diagnostic.

## Next source-publication obligations

If the probe fails, fix only its demonstrated issue and retry affected work.
If it succeeds, first review the actual artifacts and API digests. This patch
changes runtime code, unlike the previous CI-only repair. Schema5 intentionally
forbids runtime changes and must not be broadened into a runtime allowlist.
Prepare a separately reviewed, bounded successor lifecycle retaining the exact
883c5d8f08 certificate, every predecessor/target audit and precise reversible
source spans. Add positive/negative controls for that lifecycle, reseal, and
run all mandatory source gates before non-force feature publication. Follow
the existing exact-object staging protocol for the NEW source, not the old one.

All existing883c5d8f08 CI remains useful diagnosis only for the new source. Do
not transfer pass credit to a changed head. Qualification must again be on the
actual sealed final head, failed-first then full, with retained evidence and
expected SHA merge. Keep PR draft/TODO unchecked and the existing monitor active.
