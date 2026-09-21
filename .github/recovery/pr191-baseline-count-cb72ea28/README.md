# PR191 baseline exact-count repair controller

This one-shot controller qualifies only `morphhdl-baseline.yml` on sealed
source `cb72ea2851b529fbe216c1a56a0de68d68770400`. It authenticates the failed
current-head baseline run, exact workflow bytes, live feature and target refs,
and the cumulative CDC source seal before recording a durable dispatch intent.

The unique workflow refuses retries and prior executions. Existing active or
successful exact-head work is reused; duplicates, failures, skips, ref movement,
or ambiguous responses stop the controller for manual reconciliation.
