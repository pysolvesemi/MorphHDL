# WA-08 production handoff and Increment 62 source compatibility

`MorphVerilog(config) { ... }` enables wire-assignment passes by default on the
single-source production path. `MorphWireAssignmentPasses(config)` also defaults
to enabled; pass `enabled = false` to select legacy generation explicitly.
Enabled generation runs the existing five canonical passes to a fixed
point before Verilog name allocation. The pass algorithms stay under
`morphhdl-passes/`; SBT and Mill compile those shared source files directly.

The production handoff requires `PureWireExpressionsV1` and its
`PureExpressions` facet. Explicit legacy fixtures can still request
`SimpleWireAssignmentsV1` and reject expressions outside that older profile.

The earlier CI failures were source-audit rejections of intentional adapter
changes and two old-profile assertions. Increment 62 introduces one exact
overlay manifest, with immutable baseline and final source anchors, SHA-256
hashes, file modes and HEAD/index/worktree checks. Historical reviewers see
only verified baseline bytes. Their original inventories and semantic checks
remain active. Live mutation tests cover source changes, partial rollout,
unknown and ignored source files, linked files and directories, executable
bits, hidden staged changes and manifest/helper corruption. A mutation outside
the overlay remains visible to historical review.

Qualification uses the public flag on a generic Boolean fixture and a
parameterized FIFO. It checks default-on identity with explicit enable,
explicit opt-out byte identity with the legacy fixtures, repeated generation,
four-state simulation, strict Verilog-2001 tools, synthesis, formal equivalence
and a failing functional mutation. The actual production FIFO must match the
independently generated five-pass candidate byte for byte. The pass workflow
proves that candidate against the common pre-pass reference over all 512
`WIDTH=1..64`, `DEPTH=1..8` bindings. Both Scala lanes and their exact output
comparison are required, together with inherited baseline and Mill CI.

WA-08 and Increment 62 are complete in
[PR #178](https://github.com/pysolvesemi/MorphHDL/pull/178). Qualification
includes 39 live overlay attacks, all 22 current 59g and 28 current 59h negative
controls, and unchanged historical mutation suites. The full CI set must pass
on the completion head before merge. The exact reviewed inventory is in
[`increment-62-wa08-source-overlay.json`](../morphhdl/contracts/increment-62-wa08-source-overlay.json).
Temporary publisher workflows and stale success markers have been removed.
Increment 62 changes qualification only; it does not change generated Verilog.

The subsequent default-on update was requested directly on
`parameterized-verilog`, with CI skipped. The original PR qualification above
applies to its original default-off revision. The updated production fixture
covers ten deterministic files, both default entry paths, explicit opt-out,
repeated configuration and enable/disable transitions. Historical proof
witnesses explicitly opt out of the production default and retain their own
independent pass selections. No new qualification result is claimed here.

The default-on revision also exposed a production candidate-discovery defect:
the named-wire native adapter required a tag attached only by the FIFO fixture.
Its repair belongs to WA-08 and uses ordinary elaboration naming metadata.
See [named-alias repair evidence](wa08-named-alias-fix.md) for the preserved
eligibility limits, public-path regressions and actual before/after Verilog.


## Public flag example

The generic production artifact uses the same public flag:

```scala
val config = MorphWireAssignmentPasses(SpinalConfig())
SpinalVerilog(config) {
  new Component {
    val a, b = in Bool()
    val y0, y1 = out Bool()
    y0 := (a === b) & True
    y1 := (a === b) & False
  }
}
```

The qualified eight-output fixture contains those expressions. Its actual
generated Verilog includes:

```verilog
assign y0 = (a == b);
assign y1 = 1'b0;
```

The full fixture, additional four-state expressions and artifact generator are
in `morphhdl/src/test/scala/nativeapplication/WireAssignmentProductionArtifactWriter.scala`.
For normal parameterized generation, no wrapper is needed:

```scala
MorphVerilog(SpinalConfig()) { new MyComponent() }
```

To turn all five passes off:

```scala
val config = MorphWireAssignmentPasses(SpinalConfig(), enabled = false)
MorphVerilog(config) { new MyComponent() }
```

Configuration changes copy the caller's mutable collections. Explicit opt-out
survives config copies, and repeated enabling installs the pipeline only once.
Ordinary `SpinalVerilog` requires the wrapper to enable these MorphHDL passes.
