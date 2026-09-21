# PR191 Mill golden-repair continuation

This separately reviewed one-shot continuation targets only workflow 332476711
(MorphHDL Mill), whose two Scala jobs in run 35558421879 failed the same disabled
golden comparison on e26eceae4eec3466133543f1261f5493a631d183. It dispatches the
unchanged Mill workflow on sealed repair b1176dde22d195648aa6f82776f262a9c7b3f9d7, tree c5239012170b83a8e9a77f49781faa72de37a342.
Compiler and target-workflow bytes are unchanged. The source repair separates
exact enabled and disabled goldens and preserves all simulation/formal gates.

The original eight-workflow controller and its files remain unchanged. No
other workflow is launched or cancelled: five of the original targeted
workflows and the older pass workspace were still active in the snapshot.
Their eventual failed jobs must be inspected before further targeted dispatch.

The same source-seal authentication, immutable source/workflow identities,
live feature/base checks, preflight failure authentication, duplicate guards,
fsynced per-request intent and no-retry/no-prior-controller-run rules apply.
Ten local safety tests cover active/success reuse, stale refs, missing genuine
failure, terminal non-success, duplicates, uncertain POST and write scope.
No full CI is launched. Dispatch is not qualification.
