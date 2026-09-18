# PR190 synchronization with merged PR189

The synchronized branch retains both actual histories: the qualified sequential
wire cleanup at `28b5a04621ed6207b374f37b88fa4f5b4030c0d4` and the normal PR189
merge at `e0e9f1d7089d3aa513677a2b94c63eb4a7a7791d` (qualified PR189 source
`a754a1f2f84b31544a619f8c0456dc23a27e7b88`). Their common ancestor remains
`f5049ae2abfe5a47cd1fac3574ea08d630bd183f`.

No compiler, Scala fixture, application source, parameter default, native
optimization selection or emitted HDL is altered by this integration. Compiler
and test changes are disjoint: every non-review file must be either its exact
qualified parent identity or the conflict-free three-way textual merge. Neither
parent's compiler or tests can be silently dropped. Gitlinks and modes are part
of this proof. Explicitly listed review infrastructure is protected by the
unchanged complete WA08 source-seal algorithm, bound to an actual committed
source checkpoint.

Five textual conflicts were in source-review metadata and checker pins. The
native approval policy is the conflict-free combination of both reviews; its
canonical manifest is regenerated and compared. The formal registry retains
all 98 identities and checks their hashes against the actual combined source.
Both standalone reproducer roots are scanned as high-level sources; neither is
a low-level exception. The original layering-checker algorithm is unchanged.

The qualified PR189, PR190 and lane/Increment61 reviewers retain their original
fallback implementations. Their combined profile first validates the complete
current HEAD, index and working tree; only then can the original predecessor
audits run on their unchanged historical checkouts. Historical success is not
current compiler qualification.

Targeted validation runs the combined source and mutation checks, native
approval/canonical-manifest reproduction, both layering roots, original
regression-inventory and boundary controls, inherited WA08 qualification and the
public sequential regression matrix. CDC/compact-timeout regression commands
are also run on the synchronized source. The original complete workflows remain
required for final qualification. No skipped job is counted as a pass.

This document records the integration design, not a claim that a particular CI
run passed. Consult the exact-head workflow artifacts for execution results.
