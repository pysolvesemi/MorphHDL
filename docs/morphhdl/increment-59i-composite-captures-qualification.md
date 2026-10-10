# Increment 59i — Composite capture qualification record

## Published-source qualification closeout — September 8, 2026

The sixteen-file implementation is published at
`6938d46a910f23b456e368c2d0c9603328742b38`, tree
`7dbc2a932da6206b2a979759df55a5bb915191c2`. Exact-source application run
`34260913867` passed all patch identities, native/59i/59g/59h source reviews,
their mutation controls, capture tests, integration-composition tests, WA-07b
inherited controls and the historical owner/source controls, then pushed to the
unchanged development branch without force. No permissions were changed.
The artifact ZIP digest matches GitHub metadata, all 2,200 archived Git blobs
match the independently reconstructed published tree, and all sixteen changed
files match the locally tested source fingerprints.

The completed broader local run passed **332 tests in 29 suites on each Scala
version**, with no failures, errors or skips. The initial concurrent Scala 2.12
process stopped without a final report; its complete isolated rerun passed all
332 tests. That interrupted execution is retained separately and is not counted
as a successful initial run. Scala 2.13 passed its full run. The focused
36-test results overlap this inventory and are not added to the 332 total.

The local frozen-owner source audit was also rerun in full after a documentation
commit interrupted its HEAD-stability check. The stable rerun passed both
current-owner positives, all 32 exact rejections and the unchanged historical
59c controls (two positives, 20 exact rejections). The independent CI source
application then passed those same gates on its unchanged implementation commit.
Neither rerun weakened an assertion or changed a hardware proof assumption.

The dedicated workflow now explicitly requires the ten new capture tests and
at least the complete 332-test inventory. It generates and checks independent
A/B capture artifacts and cross-Scala identity alongside every existing
registered-record, nested-result and inherited-59b gate. A source-application
pass is not a clean Scala build or a hardware proof; the fresh exact-head
qualification workflow must pass separately. The previously completed clean
run `34245783928` qualified reconciliation commit `073ee78c`, not this new
capture implementation. No current-head clean-CI success is claimed here.

The controlling full 59i checkbox remains unchecked, PR #177 stays draft, and
nothing from this unfinished increment is merged into `parameterized-verilog`.
