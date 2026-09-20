# Increment 42 — External structural and process capture

Increment 42 moves the Increment 33 structural-region registry and the Increment
34 procedural-loop registry out of the native `core` source tree without adding
an upstream SpinalHDL hook.

## Ownership boundary

- `frontend/src/main/scala/spinal/core/ParameterizedStructure.scala` owns
  structural capture, generate-for/if/case metadata, symbolic slices and Vec
  selections.
- `frontend/src/main/scala/spinal/core/ParameterizedProcess.scala` owns range
  classification and safe procedural-loop metadata.
- `morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala`
  and `ParameterizedVerilogProcesses.scala` own publication lowering.
- `PhaseVerilog.scala` retains only the pending Increment 35 memory rewrite.
  The normal native emitter, driver/latch checks, clock/reset phases and module
  deduplication remain authoritative.

The moved sources deliberately retain their `spinal.core` package names. Scala
package visibility therefore provides the same read-only access to native AST
objects from MorphHDL-owned modules; no native API or package-private hook is
added.

`ElaborationIntegerExpression` and `ElaborationBooleanExpression` are general
facts shared by width, hierarchy, structure, process and memory code. They are
kept temporarily in the existing `ParameterizedWidth.scala` sidecar because the
native-core memory/width sidecars compile before the MorphHDL frontend module.
Their final module extraction remains part of Increment 46 and is not structural
capture ownership.

## Publication order

Parameterized single-source generation now preserves the established order:

1. ordinary native SpinalHDL elaboration and validation;
2. native Verilog emission plus the still-pending memory rewrite;
3. external procedural-loop replacement;
4. external structural generate-for/if/case relocation;
5. external expression, declaration, connection and hierarchy lowering;
6. atomic publication of the final Verilog-2001 artifact.

Structure-only modules skip the later hierarchy text pass after relocation, as
before, so generated helper connections cannot be mistaken for ordinary
parent/child bindings.

## Validation

The dedicated boundary gate proves the four Increment 33/34 source files are
absent from native `core`, present in MorphHDL-owned modules, and no longer
referenced by `PhaseVerilog.scala`. It then runs the structural, procedural,
hierarchy, expression and single-source regressions on Scala 2.12.18 and
2.13.12. The normal baseline, Mill, source-preservation, strict Verilog-2001,
simulation, lint and synthesis workflows remain mandatory before merge.
