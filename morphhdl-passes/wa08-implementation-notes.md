# WA-08 production handoff and Increment 62 source compatibility

WA-08 adds `MorphWireAssignmentPasses(config, enabled = true)`. The default is
false. Enabled generation runs the existing five canonical passes to a fixed
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
parameterized FIFO. It checks default-off byte identity, repeated generation,
four-state simulation, strict Verilog-2001 tools, synthesis, formal equivalence
and a failing functional mutation. The actual production FIFO must match the
independently generated five-pass candidate byte for byte. The pass workflow
proves that candidate against the common pre-pass reference over all 512
`WIDTH=1..64`, `DEPTH=1..8` bindings. Both Scala lanes and their exact output
comparison are required, together with inherited baseline and Mill CI.

WA-08 and Increment 62 remain unchecked until that complete CI set passes on
the final head. Temporary publisher workflows and stale success markers have
been removed. Increment 62 itself changes qualification only; it does not
change generated Verilog.
