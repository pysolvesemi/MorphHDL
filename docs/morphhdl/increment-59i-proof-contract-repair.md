# Increment 59i — Scoped-composite proof-contract repair

Status: development checkpoint. Increment 59i remains incomplete and unchecked;
PR #177 is not ready to merge. This repair changes the qualification harness,
not the native reduction, clocked process, candidate or independent Scala
reference. It does not extend the currently qualified feature scope.

## Correct the synchronous reset / clock-enable model

The first checker model incorrectly reset its pipeline whenever reset was high,
even with clock enable low. Its first simulated edge also had reset high and
enable low while the model assumed zero state. That is not an initialization
event for the fixture's native synchronous-reset clock domain.

The native contract for this exact profile is:

```text
When enable is low: hold every register, including when reset is high.
When enable is high and reset is high: reset every register.
When enable is high and reset is low: sample the preceding stage.
COUNT=1: return the input without a register or callback.
```

The same clock-enable-outside-reset ordering is visible in the actual generated
`BalancedNestedRegisteredLoop.v` retained in the qualified 59h artifact at
`04fa8344ceb50607baf2150831dfcbece853eb65`. This is prior native evidence, not a
claim that fresh 59i RTL has been generated or proved.

The checker now requires an enabled reset before observing registered state.
Each simulation explicitly initializes the native registers, fills the entire
pipeline with nonzero records, applies reset while stalled, checks that state
holds, and then applies an enabled reset. It checks both before and after each
subsequent edge. Random and independent field/lane stimulus is retained.

A regression reproduces the original bug: after a five-input tree holds record
`(1, 5, 6, 7)`, a stalled reset incorrectly changed the old model to zero. The
corrected model holds that exact record until an enabled reset.

## Preserve independent proof and mutation contracts

The independent native references, wiring-only adapters, all 96 parameter cases,
all 12 layout/signedness/default profiles, solver timeouts, reset-entry proof
assumptions and zero-state induction setup are unchanged.

Two additional actual-RTL mutations are required. `reset-overrides-enable`
changes `if(enable)` to `if(enable || reset)` on this active-high synchronous
profile, changing reset precedence while retaining ordinary stalls.
`bypass-latency` reconnects every delayed record leaf to its combinational
selected counterpart. Missing anchors are errors. Together with the five
original controls, all seven must produce real `bad=1` counterexamples.

The checker also rejects candidate/reference identity overlap, Boolean values
masquerading as integer defaults, escaping case paths, reuse of one directory
or file as both A/B outputs, and reused canonical RTL paths. A failed rerun or
focused diagnosis removes any previous full-slice `evidence.json` before it
can be mistaken for current successful qualification.

## Executed local checks

| Check | Result |
| --- | --- |
| New reset-stall regression on original checker | Failed as expected: `(0,0,0,0)` instead of `(1,5,6,7)` |
| Checker regression suite after repair | 16 tests passed |
| Independent transaction-history comparison | All eight COUNT witnesses, both selection modes, 160 cycles each: 2,560 model edges |
| Existing matrix/checker self-test | 96 cases, 12 profiles, 117 negative controls passed |
| Python bytecode compilation | Passed |

These are Python model, harness and rejection tests, not HDL simulation or
formal proofs. The revised dual-Scala workflow runs the regression suite and
requires the exact seven-control inventory. Scala compilation, generation,
all hardware proofs, exact-source successor review, and the remaining full
59i combinations are still required before any completion checkbox or merge.
