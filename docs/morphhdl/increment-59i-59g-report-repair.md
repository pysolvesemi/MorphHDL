# 59i failed-first: retained 59g report inventory

The failed-only candidate `ddf61ef25f927d646027cebbcaca6d724ad8a5fa`
passed baseline, Mill, Increment 61 publication and independent-parameter CI.
Its 59g run `35257977113` passed all 383 reduction tests on both Scala versions
and generated two byte-identical bridge artifact sets per lane, but its XML
report gate still required 20 closed-graph tests. The source and reports have
27: the original 20 cases plus seven conditional/saturation closure controls.
The failed report gate prevented the hardware proof step; those proofs are
not claimed as passed.

This follow-up changes that one expected count from 20 to 27, preserves all
other report assertions and workflow commands, and adds 19 executable
regression tests. They run the actual inline XML checker rather than a
replacement checker. They also compare the workflow and checker AST with the
immutable preceding workflow, and preserve the names of all twenty historical
cases. The repaired gate was replayed successfully against both original
failed-run XML artifacts; this is evidence of the report-driver repair, not a
fresh execution of the Scala tests or hardware proofs.

The new source is a direct descendant of the exact `ddf61ef2` seal (first
parent), with the same `27af65ab` target as second parent. Its verifier pins
that prior source/seal/manifest/helper and uses the unchanged schema-3 lifecycle
to execute the original prior verifier in its immutable checkout. The original
59i, Increment 61, native and signedness certificates remain transitively
checked. Importer pins are updated only to the reviewed verifier bytes. The
existing continuation regression checks the new `ddf61ef2` link and retains its
original `14dc0d2b` assertion against the exact preceding certificate. No
Scala source, native manifest, hardware proof, runtime timeout, prior negative
case or completion checkbox changes. The seal changes only the source review
contract and the verifier's manifest-hash slot.

Only still-failed workflows may be dispatched during this stage. Full CI is
blocked until all originally failed gates pass on the resulting repair head.
The missing composite-local-enable deliverables remain an implementation
requirement and must not be skipped or replaced with a source-only pass.
