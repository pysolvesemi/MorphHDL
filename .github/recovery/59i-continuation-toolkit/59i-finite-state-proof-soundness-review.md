# Increment 59i finite state proof review

Independent read-only review of the proposed proof repair, 2026-09-19.
This review does not claim that the new proof has run or passed in Yosys.

Final reviewed checker SHA-256:
`e522d80a10040cc59e9c484e56573b15b85ff8d4041b4fe62d4720cf0b6f3a40`.
The structural receipt records the earlier source hash before the final
log-parser-only tightening. The final delta was read and independently checked
to reject the exact Yosys timeout, skipped-base, missing-horizon, and premature
terminal-marker forms while accepting all 17 successful base markers.

## Pinned solver semantics

The source reviewed is Yosys 0.41, retrieved through GitHub's repository-content
API after the public web fetch failed:

- https://github.com/YosysHQ/yosys/blob/yosys-0.41/passes/sat/sat.cc
- https://github.com/YosysHQ/yosys/blob/yosys-0.41/kernel/satgen.cc
- https://github.com/YosysHQ/yosys/blob/yosys-0.41/kernel/satgen.h

Use this finite command for each strengthened positive output-bit obligation:

```
sat -seq 1 -tempinduct-baseonly -maxsteps 17 -set-def-inputs -set-at 1 reset <active-reset> -set-at 1 enable <active-enable> -prove bad 0 -timeout 120 -verify
```

In sat.cc, lines 1424–1426 create the one-step prefix; lines 1451–1460 iterate
induction lengths 1 through 17 and query the property at prefix length plus
induction length. Thus the queried times are exactly 2 through 18, matching
the previous 18-step query with its first property check skipped. The input
constraints at time 1 establish the native enabled reset. There are no
time-specific constraints after that prefix. `-prove-skip` is incompatible
with temporal-induction modes and must be absent (sat.cc 1344–1345).
`-tempinduct-skip` must also be absent: it would assume unproved base cases.

Each base query asks whether its property can fail. Only after a successful
unsatisfiability result and the timeout check does Yosys add that already
proved property to the continuing solver (sat.cc 1473–1495). This is finite
reuse of proved facts, not an assertion that corresponding DUT states were
equal initially. No unbounded-induction result is claimed.

Accept only a zero tool exit, the following base marker once for every k from
1 through 17 in order, and the final bounded marker:

```
Base case for induction length k proven.
Reached maximum number of time steps -> proved base case for 17 steps: SUCCESS!
```

Reject errors, failures, timeout diagnostics, skipped-base diagnostics, missing
markers, duplicate markers, reordered markers, and alternate solver commands.
The usual non-inductive SAT success marker is not produced by this mode.

## Repeated-state pruning

Yosys automatically excludes repeated complete states in its base-case loop
(sat.cc 437–442 and 1463–1464). This fact must not be omitted from the proof
description. The complete state is the SAT engine's set of FF Q signals;
satgen.cc 1206–1227 imports every retained FF and records its Q at time 1.
With undefined-value modeling, equality includes the defined/undefined status
and the value when defined (satgen.h 202–226).

Pruning preserves this bounded safety claim. If a counterexample at a queried
time has two equal states at times at least 2, remove the intervening cycle
and retain the suffix inputs. The shortened trace retains the original reset
prefix, has the same endpoint state and endpoint input, and still fails the
same time-independent property. Every input is free after time 1, so the
shortened trace remains admissible. Repeating this operation produces a
shorter nonrepeating counterexample within the already checked horizon.
This argument would not automatically apply if later input times were fixed,
if hidden state were omitted, or if the property depended on absolute time.
The accepted cell set and exact script must continue excluding those changes.

For a COUNT=1 combinational cone there is no state. The first post-prefix
query already covers every possible input assignment; later repeated-state
pruning can close the remaining queries vacuously without weakening the
original claim. Do not interpret those later queries as additional distinct
behavioral executions.

## Actual retained model and strengthening

The inspected input is
`evidence/86f6-checker-verified/priority-obligation/normalized-netlist.json`,
SHA-256 `96137a5ea6fa91d3202799da8ed988b2cb87c5c6d4d33c4e1cd5ed8c958aa8ab`.
It contains 657 cells: 142 positive-edge FFs, 328 muxes, 107 XORs, 52 ANDs,
and 28 ORs. There are no initial-value attributes.

An independent execution of the proposed correspondence routine finds 71
pairs covering all 142 FFs exactly once, with disjoint left/right identities.
The recursive matcher checks actual driver types, parameters, port direction,
port position, all input connections, and feedback using a visited-pair set.
It starts from the original output mismatch. Structural matching only proposes
lemmas: it does not establish or assume the equalities. A bad or overly strong
pairing can make the solver fail, but cannot make the original output property
pass falsely when the original property remains in the conjunction.

The strengthening adds each Q mismatch to the original output mismatch through
fresh XOR/OR gates. It preserves every original cell and connection, all input
ports, and all initial-state metadata. The original output mismatch is a
term of the strengthened bad signal, so proving strengthened bad=0 implies
the original output equality at every accepted time. No register value, clock,
transition, reset, or enable is replaced. All added relations are proof goals.

The first independent check found a fresh-bit allocation issue: original
netname IDs extend to 898, while cell/port IDs end at 741. Allocating above
only cell/port IDs would reuse existing netname IDs. The implementer was
instructed to allocate above all netnames as well. The final independent
structural check confirms the correction: added output IDs are 899 through
1040 and are disjoint from every original netname ID. The receipt is
`evidence/59i-finite-state-independent-structural-review.json`; it also checks
the exact added XOR/OR conjunction and rejection of an introduced FF init
attribute. This receipt is a structural review, not a solver result.

The solver interprets Q at time 1 as independent initial state, then uses D
at time t-1 for Q at time t (satgen.cc 1214–1227 and 1270–1280). Keeping both
FF sets separate and rejecting initial attributes therefore preserves the
original arbitrary independent initial-state semantics. The active native
reset and enable at time 1 establish the first checked state at time 2.
`async2sync` remains the previously declared active-edge model; unchanged raw
RTL simulation remains responsible for behavior between active edges.

## Remaining validation

Run the repaired COUNT=5 priority obligation first. On success, execute all
original positive output-bit obligations and genuine emitted-RTL mutation
checks. Preserve each unstrengthened and strengthened model, exact scripts,
pairing evidence, and solver log. The independent artifact verifier must
reconstruct the strengthened model and also independently assert original
cell/input/metadata preservation, exact conjunction, fresh IDs, complete FF
pairing, fixed prefix only, full finite horizon, and terminal completion.
No source sealing or qualification completion follows from this review alone.

## Independent verifier follow-up review

Reviewed `verify-local-enable-diagnostic-v5.py`, the narrow
`verify-local-enable-checker-diagnostic-v3.py`, and the binding wrapper
`verify-local-enable-diagnostic-successor-v2.py` after the source repair was
committed as `545134a42` (short identity; use the controller's exact full SHA).

The verifiers require the exact reviewed source/tree/checker identity, exact
extraction and finite-proof scripts, the full 2–18 horizon, ordered complete
base-case logs, matching proof/model hashes, and all success receipt files.
Full success still requires both Scala lanes; narrow success remains diagnostic
only and cannot claim compilation, RTL regeneration, sealing, or qualification.

The direct additive-strengthening check now independently walks the original
bad plus every state XOR/OR term, verifies the final bad output, requires every
original FF exactly once across disjoint pairs, binds each Q to its actual FF,
rejects initial-state attributes, preserves every original cell/input/metadata
item, and rejects temporary signals that alias original IDs. These checks are
in addition to deterministic reconstruction by the separately pinned checker.
This resolves the initial review observation that exact conjunction and pair
coverage relied only on reconstruction through the source checker.

The narrow priority receipt is bound to the exact COUNT=5 unsigned bit 0 case
and requires the complete proof evidence set. Failed early extraction can be
reported as incomplete evidence, but cannot produce a successful diagnostic.
Read-only follow-up review found no remaining publication blocker.
