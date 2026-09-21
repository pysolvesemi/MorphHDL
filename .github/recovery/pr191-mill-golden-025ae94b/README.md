# PR191 Mill golden-repair continuation

This separately reviewed one-shot continuation targets only workflow 332476711
(MorphHDL Mill), whose two Scala jobs in run 35558421879 failed the same disabled
golden comparison on e26eceae4eec3466133543f1261f5493a631d183. It dispatches the
unchanged Mill workflow on sealed repair 025ae94b61a8620c5b75af967425898e579f4829, tree a220ead65e21edb038159e6c920a7193ad9aaaaf.
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

The preceding Mill-only controller run 35562094042 stopped in refs_guard before
authenticate_failures or the dispatching phase because the target moved. Its
actual failed-job log was inspected: no POST or dispatch intent occurred, and
the exact b1176dde head had zero runs/checks/statuses in reconciliation. Do not
rerun that controller. The current feature and this control branch incorporate
ce4a02c11b5ec19777c3d900e7fdc06ebbf6d7cd through ordinary merge ancestry. That
target change adds AGENTS.md only. Feature source review and seal explicitly
authenticate the instruction file. Compiler, test, golden and workflow bytes
are unchanged from the locally tested b1176dde tree. Original ref guards,
dispatch scope and duplicate protections remain unchanged.
