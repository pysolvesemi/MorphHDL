# WA-07b qualification record

The generic recursive Boolean-ternary pass and five-stage standalone pipeline
passed qualification at integrated source **`62e62146`**. This record preserves
that exact revision's evidence. The documentation completion commit requires
its own applicable CI before merge; the recorded source results are not a
claim that a later commit has passed. Production publication and writeback
remain **WA-08**, and an open completion PR does not unblock that work.

## Qualified source and retained evidence

| Item | Verified identity or result |
| --- | --- |
| Repository / implementation PR | `pysolvesemi/MorphHDL`, [PR #173](https://github.com/pysolvesemi/MorphHDL/pull/173) |
| Integrated implementation | `62e62146c0366b2db37f2243017d32232d5a981d` |
| Integrated target / compatibility repair | `64e8fddc432e859b6b532540bee96c5608d46efa`, merged PR #176 |
| Pass qualification | [Workflow 34200640436](https://github.com/pysolvesemi/MorphHDL/actions/runs/34200640436), attempt 2: PASS |
| Inherited/signedness closure | [Workflow 34200640264](https://github.com/pysolvesemi/MorphHDL/actions/runs/34200640264): all five jobs PASS |
| Final aggregation job | `102159582418`: PASS |
| Aggregate artifact | `morphhdl-wa07a-equivalence-evidence` (historical name), ID `10069343481` |
| Aggregate ZIP SHA-256 | `8b2a50fc92b3b52b0d552faf36f9f13ab9e8b28d83066d2d4877b1678d771b4a` |
| Common pre-ALL-passes reference SHA-256 | `3f1689bc5fa3edb4c325291bc215622b37f53b669ac973e11612e724199088bc` |
| Manifest SHA-256 | `7fc800115077dfaea58df5e81298261e6154ae3c8800f72e2138130fddc40e96` |
| Signature registry SHA-256 | `5280dd98cf796151c8ceb5ef5dd4f6ec337f6a517bd37af091edf53c3d752b41` |
| Native artifact | ID `10049358259`, SHA-256 `54596123dc61b353e66ee15c6a30c06f889e07afa43713406d7a0c46666d3c12` |

The downloaded aggregate ZIP matches GitHub's artifact digest. Its
`gate-status.json` identifies the exact integration commit, reference, manifest
and registry above and reports `PASS`, `complete_domain: true`,
`exact_disjoint_coverage: true`, `determinism_checked: true` and
`all_mutations_detected: true`. All sixteen embedded shard-determinism records
and their separately retained JSON files report identical repeated runs.
The aggregate's nine candidate hashes match the source-bound native inputs.

The completion review read the actual aggregate and native/test artifacts; it
was not another local solver execution. The large shard archives were not
independently replayed locally. The successful aggregation job validated the
retained per-binding solver evidence before accepting the complete domain.

## Implemented scope

`BooleanTernarySimplificationPass` works on canonical `RtlExpr`, not Verilog
text. It recursively visits all represented children of eligible pure
continuous-assignment right-hand sides. A nonmatching outer expression does
not hide a matching descendant. Only opposite unsigned one-bit constants, or
proven identity unsigned type fences around those constants, are admitted.
Unproven wider, signed or parameter-dependent branch types are retained.

A self-determined one-bit comparison/logical truth producer replaces the
positive ternary directly. Other supported conditions retain `!!condition`;
the inverse uses `!condition`. Raw-Z-to-X truth conversion, one-bit results in
wider contexts, signedness, symbolic parameters, type fences, scopes and
observability remain protected. Procedural and protected drivers are excluded;
invalid input or output returns the original design.

The one common all-or-none flag selects unnamed aliases, named aliases,
unnamed expression temporaries, constant operands and Boolean ternaries in
that fixed order, with checked fixed-point progress and atomic rollback.
Historical stage selections remain private regression fixtures. No component
recognizer, emitted-name matching, signal renaming, procedural/state rewrite
or production writeback was added.

## Qualification results

| Gate | Qualified result |
| --- | --- |
| Pass boundary, static contracts and source signatures | PASS |
| Pass Scala 2.12.18 and 2.13.12 | 144 tests / 17 suites per lane, no failures, errors or skips |
| Full inherited regressions, each Scala lane | 1,916 tests / 190 suites, no failures, errors or skips; includes the 144 pass tests |
| Independent four-state rule oracle, each lane | 16,384 patterns across 55 expression cases; standalone B and all-five |
| Unsafe four-state rewrites | All five detected: polarity, raw Z, raw vector, vector complement and widened complement |
| Independent symbolic-width rule proofs, each lane | All 32 WIDTH=1..8 x signed/unsigned x standalone/all-five cases PASS |
| Generic native rewrite coverage | Five positive and four inverse rewrites per candidate; zero procedural receiver rewrites |
| Native four-state simulation | `WA07B_NATIVE_PASS patterns=16 outputs=8 candidates=2` |
| Native combinational SAT, Verilog-2001 compile, lint and synthesis/check | PASS |
| Repeated native output | All nine FIFO candidates/reports and generic native outputs/reports byte-identical |
| Inherited source-composition controls | 75 source mutations rejected |
| Cross-Scala signedness/compatibility output | All 213 manifest-listed RTL files byte-identical |
| Complete sequential FIFO equivalence | 9 candidates x 512 bindings x 2 runs = **9,216** binding qualifications |
| Comparison-phase reachability | **9,216** successful proof results with retained cover evidence |
| Exact disjoint-domain aggregation | All 16 shards accepted; every WIDTH=1..64 x DEPTH=1..8 binding covered twice for every candidate |

Both new FIFO candidates also passed compile/lint/synthesis/simulation at
`(WIDTH,DEPTH)=(1,1),(1,8),(3,3),(8,5),(16,5),(64,8)`. These representative tool
checks are separate from the complete 512-binding sequential formal matrix.
Each candidate is compared directly with the same snapshot before **ALL**
passes, never just the preceding stage. Six independently generated references
match that common capture. Symbolic WIDTH and DEPTH remain in the emitted RTL.

Standalone WA-07b is explicitly a no-op on the shared StreamFifo; its emitted
candidate hash equals the reference. The all-five FIFO output equals the
historical four-stage output. Independent canonical and generic native
fixtures provide the actual positive/inverse rewrite coverage. The no-op
FIFO result is not presented as evidence that a ternary rewrite occurred.

### Proof contract and limits

FIFO payload equality is guarded by both `io_pop_valid` outputs; the valid
outputs themselves are compared. Reachability establishes the comparison
phase, not a separate cover for every payload-valid guard. Output-comparison
corruption controls at WIDTH=1, DEPTH=1 exercise every candidate, alongside
independent generic combinational and sequential controls. These shared-FIFO
controls mutate the miter, not candidate RTL; the independent rule-oracle
mutations separately exercise unsafe expression replacements.

Repeated-run determinism covers the committed harness's selected `.json`,
`.sby`, `.v`, `.ys` and `.args` artifacts, excluding its `proof`, `reachability`
and `obj_dir` workspaces. Raw solver logs and traces are not required to be
byte-identical. Aggregation separately checks source/input identities,
prepared models, miters, clock configurations, complete property coverage,
attempted searches, solver records and verified invariants. It validates
retained evidence; it does not independently rerun the solvers.

Two-state formal results do not establish X/Z preservation. The independent
four-state simulator results above supply that separate evidence. No FPGA or
ASIC technology-mapped area/timing improvement is claimed by this increment.

## Actual emitted demonstration

Ordinary SpinalHDL source in `BooleanTernaryGenericNativeWitness`:

```scala
y0 := Mux(a === b, True, False)
y1 := Mux(a =/= b, False, True)
y2 := Mux(a, True, False)
```

Actual native reference emitted at the qualified integration revision:

```verilog
assign _zz_y0 = ((a == b) ? 1'b1 : 1'b0);
assign _zz_y1 = ((a != b) ? 1'b0 : 1'b1);
assign _zz_y2 = (a ? 1'b1 : 1'b0);
```

Actual standalone WA-07b and all-five native candidates:

```verilog
assign _zz_y0 = (a == b);
assign _zz_y1 = (! (a != b));
assign _zz_y2 = (! (! a));
```

The unrelated `assign _zz_y7 = (a ? a : b);` remains unchanged. These excerpts
come from the actual native artifact above, not handwritten replacement RTL.
The native bridge is test-only; enabling this in the production generation
path remains WA-08 work.

## Evidence-upload recovery

The first attempt passed all sixteen shards' proof and packaging steps, but
shard index 3 failed while uploading its evidence (`Upload progress stalled.`).
The final job correctly refused incomplete upstream results. A failed-jobs-only
retry kept the same source revision and retained the other fifteen successful
shards. It reran the entire affected shard, not just the upload.

Replacement job `102112507425` passed proof, packaging and upload. Its final
artifact is `10067596818`, SHA-256
`b09318dfc6110c67c4d5f20195615d3e62938161dddc6d14c93269c4e45e9333`.
The downstream aggregation then completed and retained the successful summary
identified above. No implementation, workflow, parameter domain, solver
assumption or acceptance rule was changed to recover that transfer failure.

## Completion-head and merge gate

The implementation qualified before this documentation-only completion update.
The roadmap's `[x] COMPLETED` records that qualified implementation; WA-08's
`READY` status identifies its successor **after this PR is merged**. It is not
permission to begin WA-08 from an open PR.

This completion commit changes only documentation. Pass implementation/tests,
native witnesses, proof scripts, manifest, source-signature registry, workflow,
reference and candidate logic remain unchanged. Nevertheless, the completion
head must receive its own required CI, including both Scala lanes, native
checks and the complete formal aggregation, before merge. Earlier SHA-specific
results are not relabeled as a successful run on that later head. The live PR
and workflow statuses record that final gate and any subsequent merge result.
