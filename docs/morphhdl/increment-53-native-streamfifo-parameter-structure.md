# Increment 53 — Native StreamFifo parameter structure

## Objective

Apply the generic native-`Int` formalization, expression-provenance and nested
symbolic-control-flow path from Increments 46 through 52 to the real
`spinal.lib.StreamFifo` implementation. One ordinary native FIFO definition
must cover depths 1, 3, 5 and 8.

Increment 53 intentionally restores the minimal Increment 37 native source
surface required by the roadmap: the `ParameterizedMemoryDepth` companion
object overload and the pointer-width-safe assignments in `Stream.scala`. It
does not restore the old FIFO sidecar or any emitted-name recognizer.

## Architecture

The public call enters the real native companion overload:

```scala
spinal.lib.StreamFifo(dataType, depth: ParameterizedMemoryDepth)
```

That overload delegates only the scalar formal boundary to the generic
`ExternalNativeIntFormalComponent.parameter` runtime helper and then executes
the ordinary `new StreamFifo(dataType, witness)` constructor. The helper is not
FIFO-specific and does not treat the scalar as a packed child-port width.

The MorphHDL compiler plugin is enabled while compiling the native `lib`
module. It discovers a native constructor boundary from the generic
`ExternalNativeIntFormalComponent.parameter(...)(witness => new Child(...))`
shape, proves that the witness maps to exactly one primary-constructor `Int`
slot, and instruments that slot without knowing the child class, source path or
argument names. The existing native-`Int` shadow machinery then retains:

- depth arithmetic and comparisons;
- `log2Up` and `isPow2` results;
- Boolean combinations and Boolean-to-integer pointer-width terms;
- native Boolean `generate` calls retained as one-sided structural
  generate-if regions, including witness-false but reachable bodies;
- exhaustive Boolean matches normalized to conditionals;
- nested depth-one, power-of-two and non-power-of-two alternatives.

Independent native generate regions carry an exact, capture-identity-bound
truth-domain proof when their predicates reduce to supported affine
comparisons, Boolean composition or `isPow2` over the canonical constructor
argument. Closed intervals keep the large native domain analytic rather than
enumerated. The same fail-closed proof is used by graph validation and Verilog
relocation: disjoint alternatives may own the same native target, overlapping
or unsupported alternatives may not. A generate body is skipped only when its
true domain is proven empty; concrete-witness specialization alone never drops
a reachable alternative.

Native `when` and `switch` process trees are captured as exact ownership trees.
Body execution and validation form one transaction: failures restore statement
scope/order links, assignment and memory-port containers, child and I/O lists,
and structural pending/label state before elaboration continues.

A small MorphHDL runtime module is shared by `lib` and `frontend` so
compiler-inserted hooks are available without a `lib -> frontend -> lib`
dependency cycle. It reuses the formal, shadow, memory, value and structural
registries established by Increments 45 through 52; it contains no FIFO RTL
implementation.

When a symbolic Scala integer enters an ordinary native `UInt` operation, the
compiler creates an exact identity-retained UInt carrier. Publication rewrites
only that carrier's concrete witness assignment using its retained object
identity. No StreamFifo module, port or user signal name is used for discovery.

### Scalar component formal boundary

`DEPTH` controls storage and structural alternatives, but it is not the packed
width of `io.occupancy` or `io.availability`. Those ports use the derived width
`log2Up(DEPTH + 1)`. The generic scalar component boundary therefore retains
the exact formal-to-actual hierarchy binding on component identity without
attaching `DEPTH` to either packed port.

Definition-side proof still comes from compiler shadow plus memory, value,
structural and process registries. Hierarchy lowering resolves the scalar
formal from exact component identity and canonical declaration identity.

## Removed compatibility path

Increment 53 removes:

- `lib/src/main/scala/spinal/lib/ParameterizedStreamFifoDepth.scala`;
- `frontend/src/main/scala/spinal/lib/ExternalParameterizedStreamFifoDepthRegistry.scala`;
- `rewriteParameterizedStreamFifoDepth`;
- the emitted `io_push_*`, `io_pop_*`, occupancy and availability recognizer;
- the `HdlInt.fromParameterizedMemoryDepth` round-trip conversion.

The MorphHDL frontend adapter delegates directly to the real native overload.
Both a direct bounded parameter and a compound bounded `HdlInt` expression
cross the same `ParameterizedMemoryDepth` contract. Parent actuals retain their
exact bounded domains, while the canonical child `DEPTH` formal has the stable
native domain `[1, Int.MaxValue - 1]`. This lets callers with different legal
domains share one child definition and excludes the one value for which native
`depth + 1` geometry would overflow Scala `Int`.

## Proof boundary

The dedicated contract proves on Scala 2.12.18 and 2.13.12 that:

- the test instantiates the real `spinal.lib.StreamFifo` overload;
- there is one parameterized native `StreamFifo` definition;
- the definition retains depth-one, power-of-two and non-power-of-two structure;
- the same definition simulates and synthesizes at depths 1, 3, 5 and 8;
- concrete `SpinalVerilog` remains concrete;
- inherited native memory, formalization, provenance, expression and nested
  control-flow tests remain green;
- equal-witness callers with different parent domains retain one canonical
  child definition and named formal-to-actual bindings;
- equal concrete widths cannot borrow another value's symbolic shape provenance;
- independent non-FIFO fixtures prove disjoint, overlapping, unreachable and
  witness-false native generate behavior without class or signal-name rules;
- rejected nested process capture leaves the ordinary native graph reusable,
  including initializer, assignment, memory-port and structural-label state;
- the minimal reviewed `Stream.scala` edits are present and the sidecar/rewrite
  cannot return.
