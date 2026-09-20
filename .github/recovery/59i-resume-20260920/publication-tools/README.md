# Exact 59i repair publication preparation

This is temporary controller tooling for the existing recovery branch
`recovery/increment-59i-history-20260914`. It does not modify the development
checkout, create a development branch, update a feature ref, or dispatch CI.
The generated recovery workflow stages exact Git objects only after actual
source qualification. A separate reviewed connector action may subsequently
fast-forward the feature ref.

## Immutable scope

- Published predecessor: `b6f1fefb531ca4cb5aca266628dc29093f6bbafe`.
- Live target guard and bundle prerequisite: `bbae646ba43e6189c69feb308f8decb9b677b15f`.
- Live target tree: `c2f6e2abd588a131c5e6909173659935c62e77b7`.
- Documentation checkpoint: `771b02d9c5669f7e3a3cc3732b393dd083947ea6`.
- Checkpoint tree: `8eab276de28956f977fe4089e00318ea924813f3`.
- Checkpoint parents, in order: published predecessor, live target.
- Source must directly follow that checkpoint; seal must directly follow source.
- Transport inventory must be exactly checkpoint, source, seal. No unpublished
  abandoned repair chains are included.
- Historical runtime reference is real committed-head run `35491000802`,
  attempt 1, on the published predecessor. Its exact four job identities and
  successful results are checked. This evidence grants no new-head CI credit.
- All 1,847 runtime/build/hardware-checker files and the pinned gitlink must
  remain byte-identical to the published predecessor.

The live target identity is a publication guard. It does not replace historical
immutable source-audit target pins inside the feature's reviewed certificate.

## Offline control validation

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B /workspace/scratch/8270be305ad2/repair-publication/validate.py \
  --repo-root /workspace/scratch/8270be305ad2/MorphHDL
```

This validates syntax, actual raw-commit round trips, docs checkpoint identities,
10 ref/PR rejection controls, 9 reference-run/job rejection controls, and 5
forbidden remote-operation controls. It is not source or hardware qualification.

## Build when the final source and seal exist

The source checkout must be clean and at the exact final seal. Replace the two
capitalized SHA arguments with actual reviewed commit SHAs; the output directory
must not already exist.

```sh
PYTHONDONTWRITEBYTECODE=1 python3 -B /workspace/scratch/8270be305ad2/repair-publication/build.py \
  --repo-root /workspace/scratch/8270be305ad2/MorphHDL \
  --source FINAL_SOURCE_SHA \
  --seal FINAL_SEAL_SHA \
  --output /workspace/scratch/8270be305ad2/repair-publication-payload
```

The builder performs exact topology, clean-source, runtime, seal-delta and real
production-source checks. It creates a compressed, base64-sharded Git bundle,
raw commit metadata, exact tree requests, and a receipt. The generated stage.py
pins the actual normalized helper hash. Build output marks new-head qualification
false and contains no credentials. No network requests are made by the builder.

Publish only `controller_files` from build-receipt.json beneath the existing
`.github/recovery/59i-local-enable-publication/` directory. Publish the generated
stage.yml as `.github/workflows/increment-59i-local-enable-publication-stage.yml`.
Do not publish this unrendered template stage.py, which intentionally contains
`SOURCE_HELPER_SHA256 = 'REQUIRED_AT_BUILD'`.

## Remote sequence after separate publication

1. Workflow reconstructs the exact source, authenticates the historical runtime
   reference, initializes its pinned submodule, and executes the original 25
   source gates with unchanged command identities and gate budgets.
2. It uploads original source logs and exact tree requests in
   `increment-59i-local-enable-source-and-tree-requests-ATTEMPT`.
3. Independently verify that artifact's API digest, source receipts, all zero
   return codes and log hashes, raw commits, bundle identity, trees and parents.
4. Create the listed trees with the existing connector and verify every SHA.
   If Actions cannot yet see an otherwise-created tree, use the established
   recovery-only tree-witness pattern with complete nested .gitmodules metadata.
5. Workflow creates exact Git commits using author and committer timestamps and
   original timezone offsets. Returned SHAs and ordered parents must match.
6. It uploads `increment-59i-local-enable-exact-commit-staging-ATTEMPT`.
7. After independent verification and fresh live ref checks, a separate connector
   action can non-force advance the existing feature from b6f1 to the final seal.
   Dispatch only the currently diagnosed failed workflows and necessary new-head
   targeted qualification through separately reviewed dispatch tooling.

The workflow grants `contents: write` and `actions: read`; the controller denies
ref writes and all workflow dispatches. Its only writes are exact allowlisted
blob and commit POSTs. Credentials stay within the Actions environment and HTTP
Authorization header. Git checkout persists no credentials.

## Differences requiring review versus the proven controller

- Updated base, live target and fixed documentation checkpoint.
- Parameterized final source/seal; exactly three unpublished commits required.
- Reference changed from older two-lane development diagnostic to the actual
  four-job committed b6f1 qualification, explicitly historical only.
- Exact source helper hash is bound at local build time and must be a real digest
  before source reconstruction is accepted.
- PR head/base repository identities are additionally checked.
- Removed CI-dispatch implementation and its workflow phase; reduced actions
  permission from write to read.
- Original 25 source gate commands, parent/source verification, raw commit
  metadata preservation, no-force/no-ref-mutation behavior, and receipt checks
  remain in place.
