# Increment 39 — External elaboration and publication boundary

## Objective

Prove that a MorphHDL-owned entry point can invoke normal SpinalHDL Verilog
elaboration, inherited validation, graph inspection and native publication
without depending on MorphHDL patches to:

- `Spinal.scala`;
- `Phase.scala`;
- `PhaseVerilog.scala`; or
- `ComponentEmitterVerilog.scala`.

This increment establishes the boundary only. It does not yet move symbolic
width, hierarchy, structure, process or memory lowering across that boundary;
those migrations remain assigned to Increments 40 through 45.

## External boundary

`morphhdl.integration.ExternalSpinalVerilog` uses only facilities present in the
recorded Increment 0 SpinalHDL source:

1. `SpinalConfig.generateVerilog` invokes the ordinary native elaboration and
   Verilog publication path;
2. a cloned `SpinalConfig.phasesInserters` list appends one MorphHDL-owned
   `PhaseMisc` observer after the configured native phase list;
3. the observer receives the normally transformed `PhaseContext.topLevel` and
   runs a caller-supplied read-only inspector;
4. the normal call returns only after inherited phase checks and final global
   validation complete; and
5. the returned boundary report combines the native `SpinalReport`, immutable
   inspection result, executed phase-class inventory and published source paths.

The caller's mutable configuration collections are cloned before the observer
is installed. Existing transformation phases, memory blackboxers, scope
properties and phase inserters remain active and the original configuration is
not modified.

## Graph inspection

The default inspector builds an immutable hierarchy snapshot containing native
component definition/instance names and flattened port direction, width and
signedness. `ExternalSpinalVerilog.inspect` also accepts a caller-supplied
function over the real elaborated top-level `Component`, allowing later
MorphHDL increments to retain object identity for graph-backed analysis.

The inspector is explicitly read-only. Increment 39 does not authorize graph
mutation or replacement of native validation and emission.

## Baseline proof

`morphhdl/scripts/check-external-spinal-boundary.sh` creates a detached worktree
at the recorded Increment 0 commit
`8c4241396cd718a36227dcd89a2e6a29d9077f11`. It copies only the MorphHDL-owned
boundary implementation and focused tests into that worktree's test sources,
then compiles and runs them against the actual baseline native files.

The dedicated `MorphHDL external SpinalHDL boundary` workflow performs this
proof on Scala 2.12.18 and 2.13.12. Compilation against the baseline is the
executable guarantee that the boundary does not reference later MorphHDL-only
members such as `parameterizedVerilog`, phase-plan report fields or patched
native emitter APIs.

## Validation contract

The focused suite proves that:

- ordinary hierarchy elaboration completes once and is visible to the external
  inspector;
- native Verilog is published and reported by the normal `SpinalReport`;
- hierarchy, latch and combinational-loop checks remain in the executed phase
  sequence before publication;
- a deliberately cyclic design is rejected by inherited native validation;
- configured transformation phases and phase inserters still execute exactly
  once; and
- the caller's configuration collections are not changed by MorphHDL.

The normal MorphHDL Mill, baseline, strict Verilog-2001 and native-source guard
workflows remain applicable. Increment 39 changes no upstream-owned native
source file.

## Boundary of this increment

The existing parameterized single-source implementation still uses the current
forked native integration until the later preservation increments migrate each
symbolic capability. Increment 39 only proves that the required normal native
lifecycle can be driven and observed externally. No claim is made yet that
parameterized Verilog can be produced after restoring all native files.
