# Increment 53 — Native StreamFifo parameter structure without source edits

## Objective

Apply the generic native-`Int` formalization, expression-provenance and nested
symbolic-control-flow path from Increments 46 through 52 to the real
`spinal.lib.StreamFifo` implementation. One ordinary native FIFO definition
must cover depths 1, 3, 5 and 8 while `lib/src/main/scala/spinal/lib/Stream.scala`
remains byte-for-byte unchanged.

## Architecture

The MorphHDL compiler plugin is enabled while compiling the native `lib`
module. It selects only the exact `StreamFifo` class and its ordinary `depth:
Int` constructor argument. The source tree is instrumented in memory; the
source file is not patched.

The existing native-`Int` shadow machinery then retains:

- depth arithmetic and comparisons;
- `log2Up` and `isPow2` results;
- Boolean combinations and Boolean-to-integer pointer-width terms;
- native Boolean `generate` calls normalized to witness-equivalent Scala
  conditionals;
- exhaustive Boolean matches normalized to conditionals;
- nested depth-one, power-of-two and non-power-of-two alternatives.

A small MorphHDL runtime module is shared by `lib` and `frontend` so
compiler-inserted hooks are available without a `lib -> frontend -> lib`
dependency cycle. The runtime reuses the same formal, shadow, memory and
structural registries established by Increments 45 through 52; it does not
contain a FIFO implementation.

When a symbolic Scala integer enters an ordinary native `UInt` operation, the
compiler creates an exact identity-retained UInt carrier. Publication rewrites
only that carrier's concrete witness assignment using its final emitted name
obtained from the retained object identity. No StreamFifo module, port or user
signal name is used for discovery.

### Scalar component formal boundary

`DEPTH` controls storage and structural alternatives, but it is not the packed
width of `io.occupancy` or `io.availability`. Those ports use the derived width
`log2Up(DEPTH + 1)`. Treating either port as a direct `DEPTH` region therefore
fails correctly for the witness depth 5, where the port width is 3.

`formalComponent.parameter` retains the exact component-level formal-to-actual
binding without attaching `DEPTH` to a packed child port. Definition-side proof
still comes only from the compiler shadow plus memory, value, structural and
process registries. Hierarchy lowering resolves the scalar formal from exact
component identity and canonical declaration identity, while the existing
packed-width `formalComponent` path remains unchanged for parameters that are
actually exposed on packed leaves.

## Removed Increment 37 compatibility path

Increment 53 removes:

- `lib/src/main/scala/spinal/lib/ParameterizedStreamFifoDepth.scala`;
- `rewriteParameterizedStreamFifoDepth`;
- the emitted `io_push_*` / `io_pop_*` / occupancy / availability recognizer.

The public MorphHDL adapter now enters the ordinary native constructor through
`formalComponent.parameter`. The legacy direct `ParameterizedMemoryDepth`
overload is retained as compatibility sugar; compound depth expressions use
`HdlInt`.

## Proof boundary

The dedicated contract proves on Scala 2.12.18 and 2.13.12 that:

- there is one parameterized native `StreamFifo` definition;
- the definition retains depth-one, power-of-two and non-power-of-two structure;
- the same definition simulates and synthesizes at depths 1, 3, 5 and 8;
- concrete `SpinalVerilog` remains concrete;
- inherited native memory, formalization, provenance, expression and nested
  control-flow tests remain green;
- native `Stream.scala` is unchanged and the sidecar/rewrite cannot return.
