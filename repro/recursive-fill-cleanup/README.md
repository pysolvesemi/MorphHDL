# CDC-WIRE-01 publication follow-up

Baseline: `155df6eb0e38ecce04a37de2067b0794702fcb83`, tree
`2dee374f6f77359f3b4845f9ae9172ac97e7c957`.

This is the investigation checkpoint for the attached recursive occupancy cleanup
and resize naming request; it is not a completed fix or qualification receipt.
The two supplied fixtures differ only by a read-only diagnostic phase installer.
`requirements.md` retains the full acceptance requirements. Historical CDC-WIRE-01
evidence is unchanged. Current feature: `agent/cdc-wire-native-publication-cleanup`.

The trace inventories every native declaration before/after the production
cleanup and after normal name allocation. It invokes the actual private
candidate/proof functions using test-only reflection, and prints the provenance,
identity, driver/reference identities, width, protection, source-intent, scope
and type-node state for declarations excluded before proof. No emitted spelling
is used to infer provenance. This probe does not implement any optimization.

The initial remote run compiles and inspects the exact baseline with both native
compiler plugins enabled. Its temporary test-source override is reproduction
only; it is not a substitute for inherited tests or final acceptance. The local
runtime has Java but no usable Scala dependency cache or HDL tools; Maven access
is unavailable and both connected devboxes are offline. Use a narrowly scoped
read-only diagnostic Actions runner, then perform the required targeted-before-
full qualification after implementing and reviewing the repair.

Next: inspect the actual baseline outputs and all candidate dispositions, then
implement the generic safe transport/boundary repair and normal resize naming.
Add independent port-driven two/four-state oracles, enabled/disabled equivalence,
parameter overrides 2,3,4,8,16, negative controls and determinism before closure.
