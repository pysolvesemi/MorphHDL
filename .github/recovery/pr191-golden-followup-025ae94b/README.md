# PR191 golden-repair targeted continuation

This separately reviewed one-shot continuation targets only the three workflows
whose full-regression lanes on `e26eceae4eec3466133543f1261f5493a631d183`
failed the already-repaired disabled-golden assertion:

- baseline workflow 332279971, run 35558424029: both Scala jobs failed only
  `one ordinary component retains symbolic native and aggregate data shapes`;
- lane/when workflow 357516223, run 35558433092: both Mill and both SBT full
  lanes failed only that test; qualification, ABI and cross-Scala lanes passed;
- 60f workflow 351327055, run 35558435512: each inherited regression lane
  reported 1239/1240 passing. The downloaded Scala 2.13 JUnit XML names that
  same sole failing test; both signedness-closure lanes passed.

The repaired Mill workflow has already passed both Scala versions on sealed
head `025ae94b61a8620c5b75af967425898e579f4829`, tree
`a220ead65e21edb038159e6c920a7193ad9aaaaf`. WA-08, 60g, focused CDC and
sequential targeted workflows also passed their retained runs. Compiler and
target-workflow bytes are unchanged from the locally verified repair. No new
compiler defect or source edit is indicated.

The controller reuses the previously reviewed dispatch implementation only
after authenticating its exact SHA-256. It retains exact source/tree/workflow
hashes, live feature/base checks, historical failed-run and failed-job
authentication, whole-batch preflight, duplicate guards, fsynced per-request
intent, and no-retry/no-prior-controller-run rules. Ten local safety tests
cover three-request scope, partial reuse, stale refs, historical failure,
terminal non-success, duplicates, uncertain POST, and write scope.

The original eight-workflow and both Mill-only controllers remain unchanged.
No passing workflow is relaunched, no run is cancelled, and no full CI is
started. Dispatch is not qualification. The older formal aggregate remains
active and is left untouched.
