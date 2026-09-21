# Increment 59i exact-head targeted qualification

This recovery-only controller dispatches exactly the 16 failed-first or directly
affected workflows on seal `9d6d738d32c71ff4359384ae9d87f715bf20ec55`.
It first authenticates the successful 25-command source artifact, the exact
target-documentation reconciliation artifact, the original 16-run outcome
ledger, all workflow IDs and bytes, and the live draft PR/feature/target refs.

Every POST is limited to an allowlisted workflow dispatch on the existing
feature branch. An intent is durably journaled before each POST. Existing
active or successful same-head runs are reused; same-head failure or uncertain
dispatch state stops the controller for diagnosis. Full CI, ref updates, PR
updates, cancellation, reruns, merge, and roadmap changes are unsupported.
