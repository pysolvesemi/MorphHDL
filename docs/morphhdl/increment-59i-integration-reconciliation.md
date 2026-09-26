# Increment 59i — Integration-source reconciliation

Status: development checkpoint. Increment 59i remains incomplete, unchecked and
unmerged into `parameterized-verilog`. This change merges the actual integration
state `64e8fddc432e859b6b532540bee96c5608d46efa` into the 59i development branch;
it does not mark the full join complete or waive any hardware or CI gate.

## Preserve both source reviews

The two branches changed the same register-source reviewer. One added the exact
59i successor view; the other added the independently qualified WA-07b pass
compatibility view. Selecting either whole file would discard the other audit.

The combined reviewer restores 59i first, WA-07b second, and then the original
register and nested-owner views. Both complete production inventories remain
checked. The WA-07b checker can unwrap only an exact, separately reviewed 59i
checker successor before its existing adapter digest and span validation. It
cannot authorize arbitrary pass sources, remove index/HEAD checks or accept an
unknown source mutation.

The complete existing 59i manifest remains byte-identical, including all its
previous production spans. A small separately sealed integration manifest
re-anchors only the two overlapping checker files to the actual merged dependency
state. Its schema and exact source spans are checked before use. The original
WA-07b and historical register/owner manifests are unchanged. Synthetic WA-07b
controls receive the exact restored historical adapter bytes; current-source
integration controls still run against the real checkout and real Git worktrees.

No Scala source, candidate/reference callback, parameter domain, generated RTL,
solver command, timeout, initialization assumption or existing mutation control
changes in this reconciliation. The current increment continuation does not
change generated Verilog. The actual examples remain in the nested-result and
scoped-composite implementation records.

## Verified dedicated CI before this merge

Run `34226847838` completed successfully at functional source
`d442a867893356de8378578ba354dd4d9bb60aad`, tree
`cda6c6d8bbd371a6c44a906b00a058a34dd984e5`. Both clean Scala jobs and the
cross-Scala determinism job succeeded. Their complete artifact ZIP digests were
verified against GitHub metadata before inspecting source, JUnit and evidence.

| Gate | Result on each Scala lane, 2.12.18 and 2.13.12 |
| --- | --- |
| Dedicated reduction/owner tests | 322 tests / 28 suites; zero failures, errors or skips |
| Registered record compatibility | 96 cases, all 12 profiles per case; 25,648 simulation cycles |
| Registered record formal | 96 combinational, 96 arbitrary-state reset-entry, 96 zero-state unbounded induction proofs |
| Registered record RTL mutations | All seven produced actual `bad=1` counterexamples |
| Nested independent-Vec results | 96 cases, all four profiles per case; 28,872 simulation vectors and 96 SAT proofs |
| Nested-result RTL mutations | All four produced actual `bad=1` counterexamples |
| Inherited 59b publication | 32 cases; 5,586 simulation cycles, retained sequential proofs and both original mutations |
| Original RTL determinism | 108 combined, 100 nested-result and 33 inherited files match across Scala versions; A/B gates passed in CI |

Scala 2.12 artifact `10060151403` ZIP SHA-256:
`c26b19083951e735812a4283f969c8713ef9df9063c02dcbab75a2e14cfc07d0`.
Scala 2.13 artifact `10060030426` ZIP SHA-256:
`a31087b4a27e9049fca4fc40b37bec577de7d8d3fc879951ae9a578c66596409`.
Both complete source archives and all three hardware evidence JSONs also match.
These are results for the stated source, not a claim of final merge-head CI.

## Reconciliation checks

Sixteen new real-worktree source-view tests passed locally. They cover layered
restoration, unchanged historical manifests, unchanged production-review spans,
unknown adapter/helper mutations, missing evidence, executable/symlinked review
files and unauthorized production paths. The 59i, 59g and 59h snapshot controls
passed with 71, 244 and 113 rejected mutations respectively. Current 59i and 59g
source spans also restore exactly. Python compilation and whitespace checks pass.

The local source bundle did not include the entire unmerged WA-07b qualification
history, so full ancestry-dependent WA-07b/inherited gates are not claimed as
local passes. Those checks remain mandatory in the dedicated workflow, including
the complete existing WA-07b synthetic and current-source integration controls.
No check was weakened to accommodate that local checkout limitation.

## Still open

The full 59i implementation still needs widening/captured composite graphs,
expanded register/hierarchy combinations, all required mechanism pairs and
end-to-end/native-library cases, complete mutation coverage and final-head
compatibility/source/Scala/formal/baseline/Mill gates. Passing either existing
hardware slice does not set `complete_59i_join` to true.
