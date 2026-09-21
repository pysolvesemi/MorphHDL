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

The exact baseline was built and passed both Scala lanes in run 35620756320.
Actual Verilog, complete candidate trace and artifact provenance are archived in
`baseline/`. See `docs/morphhdl/cdc-wire-publication-cleanup.md` for the diagnosis,
repair, permanent regression commands and current qualification status.

Local experiments now use recovered JVM/HDL tooling. Cached compiler classes are
mixed historical binaries, so those experiments are diagnostic only. Required
qualification builds the exact sealed source on the remote runners. The permanent
acceptance never overrides unmanagedSources or excludes inherited tests.
