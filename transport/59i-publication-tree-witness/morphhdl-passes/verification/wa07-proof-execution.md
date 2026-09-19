# WA-07 proof execution and closure

WA-07 remains unchecked until the permanent exact-head workflow passes. Proof
activation is not roadmap completion. The previous harness rejected generated
WA-07 candidates while WA-07 was unchecked, creating a circular closure gate.

The verification-only `--prove-pending WA-07` argument now requires every
manifest slot associated with WA-07, in addition to every completed increment's
slots. It does not edit the roadmap, disable inherited proofs, or change the
public one-flag pass API. Unknown, duplicate and slotless requests fail. Missing
required candidates and unexpected unrequested candidates fail before costly
proof work. The argument also remains valid after WA-07 is checked, so the same
workflow validates the implementation, closure commit and merged tree.

`--formal-jobs 4` bounds independent binding workers. The default is one worker.
Every binding has a distinct evidence directory, and results are returned in
manifest order. A failed worker fails the gate. All existing proof engines,
reset assumptions, comparison guards, strict compilation, lint, synthesis,
simulations, mutation controls and complete Cartesian domains remain unchanged.
The permanent workflow still reruns the entire suite for determinism.

The shared witness retains five candidate slots, each with WIDTH=1..64 and
DEPTH=1..8 (512 bindings): direct unnamed, direct named, their historical
combination, unnamed expressions, and the complete three-pass pipeline. Each
candidate is compared directly with the same captured pre-pass reference.
Per-pass evidence records ordered per-binding results, candidate and reference
hashes, and full-domain completion. Proof inputs are checked for changes during
each candidate's run. Missing proof results cannot become a successful verdict.

Run scheduler and activation regressions without EDA tools:

```sh
python3 morphhdl-passes/scripts/test_wire_assignment_equivalence.py -v
```

These tests include mocked 512-binding traversal and serial/parallel artifact
comparison. Mock results are only harness unit tests, never RTL equivalence
evidence. The permanent tool-backed workflow must separately pass.

After native candidate generation, run the full gate:

```sh
python3 morphhdl-passes/scripts/validate_wire_assignment_equivalence.py \
  --shared-witness morphhdl-passes/build/formal/wire_assignment_ir/generated/parameterized_stream_fifo.v \
  --output morphhdl-passes/build/formal/wire_assignment_ir/evidence \
  --prove-pending WA-07 --formal-jobs 4 --check-determinism
```

This repair does not change the pass's slice handling, width/sign fences,
procedural exclusions, unnamed provenance rules, or production handoff scope.
WA-08 remains blocked until WA-07 has actual exact-head closure evidence.
