# WA-10 post-merge inherited audit timeout correction

This is a repair to the completed WA-10 integration, not a new roadmap increment.
The compiler, generated RTL, formal domains, immutable historical reviews and
the existing 1,996-test / 195-suite catalog are unchanged.

## Reproduced CI failure

Both [widening run 34741926201](https://github.com/pysolvesemi/MorphHDL/actions/runs/34741926201)
and [nested-owner run 34741926187](https://github.com/pysolvesemi/MorphHDL/actions/runs/34741926187)
checked out merge `f06d9c412924b99cf2c76422549c375a571757dc`. Both Scala lanes
failed before compilation in:

```text
python3 morphhdl/scripts/test-increment-59b-inherited-source-scope.py
checked_current -> check-increment-60f-equivalence-closure.py:source_scope
subprocess.TimeoutExpired: ... timed out after 120 seconds
```

The same complete current-source audit had passed earlier in each nested-owner
job through the existing 60f caller's 600-second budget. Logged positive audit
intervals were approximately 140.3 and 135.6 seconds. This is a stale caller
wall-clock budget, not evidence of a Scala, RTL, synthesis or equivalence failure.
The failed jobs did not execute their downstream test/formal stages.

The widening workflow calls `test-increment-59d-inherited-60f-scope.py` next.
Inspection found the same complete current-source positive restricted to 120
seconds there. Both callers therefore need the same bounded correction.

## Exact correction and preserved controls

- Add an optional timeout argument defaulting to 120 seconds to the two wrappers.
- Pass 600 only at their full current-descendant positive entry points, matching
  the existing 59c/59f/60f policy. Historical positives, mutation cases, Git
  operations and historical orchestration retain their prior budgets.
- Retain required exit codes, exact PASS/rejection diagnostics and propagation
  of `TimeoutExpired`. No timeout is converted into a successful result.
- Add nine executable caller regression tests, including both entry points,
  short historical/mutation budgets, positive and negative failure handling,
  timeout propagation, Git budgets, legacy signedness checks and workflow routing.
- Both workflows run those tests and include the shared audit/regression files
  in push and PR path filters. The fix branch uses their existing `59g` routing
  so the full widening and nested-owner lanes run before merge.
- Refresh only the current WA-10 exact path inventory and outer byte seal;
  preserve original historical review bytes, source ancestry and all production
  source identities. The source anchor must be an authoritative fetched GitHub
  commit before sealing, never a locally invented or unpublished identity.

The earlier skipped workflow classification applied to the WA-10 PR branch;
these workflows are active on `parameterized-verilog`. Pre-merge skips do not
qualify their post-merge entry paths.

## Validation commands

```sh
python3 morphhdl/scripts/test-inherited-source-audit-timeouts.py
python3 morphhdl/scripts/test-increment-59b-inherited-source-scope.py
python3 morphhdl/scripts/test-increment-59d-inherited-60f-scope.py
python3 morphhdl/scripts/test-increment-59h-inherited-source-scope.py
python3 morphhdl/scripts/test-increment-60f-inherited-source-scope.py
python3 morphhdl/scripts/check-increment-62-wa08-source-overlay.py
python3 morphhdl/scripts/test-increment-62-wa08-source-overlay.py
python3 morphhdl/scripts/test-wa07b-inherited-review.py
python3 morphhdl/scripts/check-wa10-source-scope.py
python3 morphhdl/scripts/check-wa10-source-scope.py --self-test
python3 morphhdl-passes/scripts/check-wa10-general-expression.py
python3 morphhdl-passes/scripts/check-wa10-general-expression.py --self-test
```

The original 143 WA-10 evidence-file hashes remain unchanged. Caller unit tests
supplement, not replace, the actual complete current/historical source audits.
Fresh exact-head CI must execute both Scala lanes and all downstream simulation,
synthesis, equivalence and mutation work before this repair is merged.

Qualification status: nine caller regression tests pass locally. Authoritative
sealed-source replay and fresh GitHub qualification are pending publication.
