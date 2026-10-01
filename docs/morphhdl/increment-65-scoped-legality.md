# Increment 65 — Structural activation and native legality

Implementation is on `work/remaining-parameterized-increments`, independently
of unmerged 59i. Remote qualification and merge remain deferred under the user's
batch instruction. Roadmap completion boxes remain unchecked.

## Supported surface

Native legality records belong to one exact Component. Runtime capture supplies
an issued lexical owner and the native emitter validates its finished structural
path before rendering a diagnostic. Parameter inventories include activation-only
roots. Nested alternatives preserve every enclosing predicate; else paths retain
negated prior alternatives. Finite generate loops retain the exact genvar and
count, including zero iterations. Child diagnostics remain inside their child
module and instance activation stays at the parent instance.

Capture is transactional across both alternatives: rejected bodies restore native
statements, children, obligation records, region registries and issued identities.
Capture ordinals remain monotonic so a stale handle cannot become valid on retry.
Component-local storage rejects transfer to a different component.

Ordinary typed `if` and exhaustive Boolean matches use the same capture. Boolean
matches support two unguarded literal alternatives, in either order, or a literal
followed by a wildcard. Guarded and incomplete symbolic matches fail explicitly.
Ordinary concrete Boolean matches retain their original behavior.

Requirements already proved true emit no diagnostic. A requirement proved false
at top level still rejects immediately. A false requirement inside an optional
owner remains guarded, allowing an inactive alternative to elaborate safely.
Safe invalid parameter defaults are preserved and fail at simulation time when
active; no default is silently repaired. Concrete requirements remain immediate.

Bounded independent-root structural predicates retain private exact tuple evidence
and exact condition identity. The existing 65,536-tuple cap and per-root limits
are unchanged. The implementation can classify a requirement under those exact
activation predicates without granting integer-domain authority. The relational
fixture `if (dataBits >= lanes)` instantiates a child receiving both independent
actuals. Hierarchy validation checks exact captured-child ancestry, root/schema
identities, native port widths and parent connections. This does not authorize
arbitrary remapping, equal-default substitution or fabricated width evidence.

## Boundaries

A deferred requirement is never an assumption for unsafe slicing or invalid
geometry. Compact or oversized joint structural products remain unsupported;
symbolic publication still has its separate less restrictive contract. Existing
host-effect rejection, exact width proofs and driver/latch validation remain.
Requirements under manually restricted domains without an issued capture owner
remain rejected. This increment does not implement the retired wire-pass roadmap.

Native diagnostics use `initial $fatal(1, "%s", message)` under `ifndef SYNTHESIS`.
They retain literal message escaping and nonzero simulator termination. Hardware
is outside that guard. Suppressing diagnostics for synthesis does not make an
invalid parameter tuple legal.

## Reproduction and validation

The runnable acceptance source is
`morphhdl/src/test/scala/morphhdl/examples/ScopedLegalityArtifactWriter.scala`:

```sh
sbt 'morph/Test/runMain morphhdl.examples.ScopedLegalityArtifactWriter target/increment65-artifacts'
```

`NativeScopedLegalityTests` overrides each generated OptionalPipeline at both
USE_PIPELINE values and WIDTH in `{1,4,7,8,16,64}`, with both publication modes
and both pipeline defaults. It checks exact diagnostics, one-cycle pipeline
versus zero-cycle bypass and X/Z propagation. Valid specializations pass strict
Verilog-2001 hardware parsing, separate diagnostic-mode Verilator lint, synthesis
and Yosys equivalence against independently elaborated concrete SpinalVerilog.
Power-up register contents are unconstrained; sequential equivalence establishes
agreement after a clock edge, without introducing reset or initial state.

Additional tests cover nested else priority, Boolean match, finite loops,
transactional retries, repeated canonical children with distinct actuals and
inputs, relational child bindings, copied proof rejection, unsafe geometry and
repeated generation. Actual RTL mutations exercise dropped/inverted/hoisted
activation, lost else priority, stale loop/child bindings, duplicate diagnostic
sites and changed pipeline latency. Parser errors are never accepted as mutation
detection. Two-state equivalence and four-state simulation are separate evidence.

Final source-bound local receipts will be recorded after the source checkpoint;
this document does not claim remote qualification or merge.
