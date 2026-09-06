# WA-07a qualification record

The implemented constant-operand pass and complete four-stage standalone
pipeline passed the implementation qualification below. This record names the
proved revision explicitly; a later integration/completion commit does not inherit its
exact-commit qualification.

| Evidence | Verified identity or result |
| --- | --- |
| Implementation source | `df3ab1c5259c08437844665f6f567ac7086b040d` |
| Qualification workflow | [Exact-source CI](https://github.com/pysolvesemi/MorphHDL/actions/runs/34033787413) |
| Workflow run ID | `34033787413` |
| Aggregate artifact | `morphhdl-wa07a-equivalence-evidence`, ID `9994105067` |
| Aggregate result | `gate-status.json`: `PASS`, `complete_domain: true` |
| Reference SHA-256 | `8cc216c904ea78bf8eac0971d02c1f19cf0970fb88d578aaeac2b3304ee08ef6` |
| Manifest SHA-256 | `fde8503c76327d1e59e75482c6841178a91001c94ef7c378a34d2c6d64027568` |
| Signature registry SHA-256 | `97ad58762a3c4c5a9834dcdba83a61aa8cebf05fd8542b24d274b0e805e698e9` |

`ConstantOperandSimplificationPass` operates on canonical expression trees and
is generic over components. Its bounded rules cover bitwise and logical
operations, safe double negation, zero-distance shifts and constant-condition
muxes. For a one-bit comparison result `p`, the rules include `p & 1 -> p` and
`p & 0 -> 0`. Width, signedness, symbolic parameters, context sizing, cast/resize
boundaries and X/Z semantics remain protected. A numeric one is not treated as
a multi-bit all-ones mask. Rewrites are reported separately from removed aliases,
and failed validation rolls the pipeline back to its original input.

The all-or-none pipeline runs unnamed aliases, named aliases, unnamed continuous
expressions and constant operands in that order until a checked fixed point.
The native test bridge captures and writes back actual expression trees; it
contains no component recognizer or generated-Verilog rewriting mechanism.

The retained qualification covers:

- All 123 Scala tests on both Scala 2.12.18 and 2.13.12, including positive and
  negative expression rules, preservation, determinism and idempotence.
- The four-state oracle over 1,024 input patterns and the native four-state
  fixture; the unsafe raw-Z identity mutation is detected. These simulation
  results provide the X/Z evidence separately from two-state formal proof.
- Strict Verilog-2001 compilation, lint, synthesis, representative simulation,
  repeated native emission and all WA pass static/source/signature contracts.
- All seven historical and new candidates against the same unchanged reference
  captured immediately before the entire passes phase, for every
  `WIDTH=1..64` and `DEPTH=1..8` binding, in two independent runs.
- The exact union of all 16 shards: **7 × 512 × 2 = 7,168 complete binding
  qualifications**, each covering every output property, plus **7,168 actual
  comparison-reachability proofs** with retained cover traces.
- Output-comparison corruption controls for every candidate at `WIDTH=1, DEPTH=1`,
  plus independent generic combinational and sequential controls, with retained
  counterexample traces. These controls mutate the miter, not candidate RTL.
- Matching deterministic `.json`, `.sby`, `.v`, `.ys` and `.args` artifacts across
  both runs, excluding `proof`, `reachability` and `obj_dir` working directories.
  Raw solver logs and traces are not required to be byte-identical.
  Aggregation separately validates source/input hashes, prepared models, miters,
  clocks, property coverage, attempted searches, logs and verified invariants.
  A successful shard alone is not full-domain qualification.

Payload equality is guarded by both `io_pop_valid` outputs; those valid outputs
are themselves compared. The reachability cover establishes the comparison
phase, not separate coverage of every payload-valid guard. Aggregation checks
the integrity and consistency of retained solver evidence; it does not rerun
the solvers independently.

## Completion and merge gate

The implementation revision above qualified before the completion checkbox was
changed. The completion head, including integration of the qualified base, must
receive its own full required CI, including fresh native generation, both Scala
lanes, static checks,
all 16 proof shards and successful aggregation. Merge is permitted only after
those jobs pass on the exact expected PR head; the earlier run does not establish
that a newer source SHA has passed.

WA-08 remains unchecked. Its `READY` status identifies the successor after this
WA-07a completion is merged into `parameterized-verilog`. Its dependencies are
not satisfied by an open PR. Production publication, execution and writeback
remain WA-08 work.
