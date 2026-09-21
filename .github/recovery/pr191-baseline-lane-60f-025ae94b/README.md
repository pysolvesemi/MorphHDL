# PR191 baseline, lane and 60f golden-repair continuation

This separately reviewed one-shot controller targets only workflow 332279971
(MorphHDL baseline), workflow 357516223 (lane/when qualification) and workflow
351327055 (60f equivalence closure). Their substantive Scala lanes in runs
35558424029, 35558433092 and 35558435512 failed the same disabled-output golden
comparison on `e26eceae4eec3466133543f1261f5493a631d183`.

The sealed repair head is `025ae94b61a8620c5b75af967425898e579f4829`,
tree `a220ead65e21edb038159e6c920a7193ad9aaaaf`. It separates the enabled
public golden from the historical cleanup-disabled literal. Compiler and
target-workflow bytes are unchanged. Local dual-Scala execution, strict
compilation, four-state simulation, Yosys equivalence and source controls passed.

Exact-head Mill run 35562488070 already passed and is not in the allowlist.
Previously successful workflows, older active lanes and the pass workspace are
neither relaunched nor cancelled. No full CI is launched.

The earlier controller run 35563053389 failed closed before any dispatch while
the lane workflow's terminal aggregate result had not yet materialized. This is
a new controller identity. It authenticates the now-terminal historical runs
and every failed job against per-workflow source provenance, and it prohibits
retries of either controller.

The controller authenticates the open PR, live feature and target refs, source
tree and seal, exact workflow bytes, and all three historical failed runs/jobs.
It enumerates exact-head runs before any write and immediately before each
request, reuses only active or successful matching runs, records and fsyncs
intent before POST, never retries an uncertain request, and permits only the
three exact workflow-dispatch endpoints with the exact feature-branch payload.

Ten local safety tests cover the complete allowlist, active/success reuse, stale
refs, missing genuine failure, terminal non-success, duplicates, uncertain POST
and write scope. Dispatch is not qualification; the hourly monitor must inspect
every materialized job and matrix lane.
