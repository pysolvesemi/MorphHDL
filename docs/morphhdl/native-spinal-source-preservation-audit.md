# Native SpinalHDL source-preservation audit

## Purpose

This audit reviews the parameterized-Verilog work through Increment 37 and
identifies where MorphHDL currently depends on changes inside SpinalHDL-owned
source files. It defines the preservation target for the corrective increments
that follow Increment 37.

This document is an architecture and planning record only. It does not approve
or implement any further modification to native SpinalHDL source.

## Preservation target

The target architecture is:

1. upstream SpinalHDL component, library and compiler source remains unchanged;
2. ordinary SpinalHDL algorithms remain authoritative for concrete elaboration,
   validation, naming, hierarchy, memories, processes and library behavior;
3. MorphHDL retains symbolic configuration in MorphHDL-owned metadata and
   lowering structures outside upstream-owned source files;
4. parameter-dependent native alternatives lower to generic Verilog-2001
   structure, including `generate` regions where a Scala witness previously
   selected only one native branch;
5. component names and emitted signal names are not used to recognize or repair
   a particular library component; and
6. the old ParamRTL and reviewed handwritten contracts remain compatibility and
   regression oracles rather than the production implementation.

The repository baseline used by this audit is commit
`8c4241396cd718a36227dcd89a2e6a29d9077f11`. The reviewed state through
Increment 37 is commit `022166700647666786e113564dbad88068b40798`.

## Classification

The audit separates three kinds of coupling.

### A. Existing upstream-owned files modified by MorphHDL

These files existed in the repository baseline and currently contain MorphHDL
changes:

- `core/src/main/scala/spinal/core/BaseType.scala`
- `core/src/main/scala/spinal/core/Bits.scala`
- `core/src/main/scala/spinal/core/Mem.scala`
- `core/src/main/scala/spinal/core/SInt.scala`
- `core/src/main/scala/spinal/core/Spinal.scala`
- `core/src/main/scala/spinal/core/UInt.scala`
- `core/src/main/scala/spinal/core/internals/ComponentEmitterVerilog.scala`
- `core/src/main/scala/spinal/core/internals/Phase.scala`
- `core/src/main/scala/spinal/core/internals/PhaseVerilog.scala`
- `idslplugin/src/main/scala/spinal/idslplugin/components/MainTransformer.scala`
- `lib/src/main/scala/spinal/lib/Counter.scala`
- `lib/src/main/scala/spinal/lib/Stream.scala`

The corrective roadmap must restore these files to the selected upstream
baseline or a later explicitly selected upstream snapshot. No new exception is
implicit.

### B. MorphHDL-specific files placed inside upstream source trees

The following are MorphHDL additions, but their current location couples the
feature to the forked `core` or `lib` implementation:

- `core/src/main/scala/spinal/core/ParameterizedMemory.scala`
- `core/src/main/scala/spinal/core/ParameterizedProcess.scala`
- `core/src/main/scala/spinal/core/ParameterizedStructure.scala`
- `core/src/main/scala/spinal/core/ParameterizedWidth.scala`
- `core/src/main/scala/spinal/core/internals/ParameterizedVerilogHierarchy.scala`
- `core/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala`
- `core/src/main/scala/spinal/core/internals/ParameterizedVerilogNativeFallback.scala`
- `core/src/main/scala/spinal/core/internals/ParameterizedVerilogProcesses.scala`
- `core/src/main/scala/spinal/core/internals/ParameterizedVerilogStructural.scala`
- `core/src/main/scala/spinal/core/internals/SpinalVerilogPhasePlan.scala`
- `lib/src/main/scala/spinal/lib/ParameterizedStreamFifoDepth.scala`

These files are not native algorithms, but they must be moved to
MorphHDL-owned modules or replaced by an external integration mechanism where
package-private access currently forces their location.

### C. Post-emission or component-specific reconstruction

Several validated paths currently rewrite native Verilog after concrete
elaboration. The most important corrective case is
`rewriteParameterizedStreamFifoDepth`, which recognizes FIFO ports and emitted
signal-name patterns, then substitutes witness-derived widths and constants.
That implementation proves behavior for the bounded Increment 37 contract, but
it is not the final architecture because it reconstructs parameter dependence
after Scala has discarded unselected native branches.

Other hierarchy, declaration, process and memory post-passes must also be
reviewed to distinguish generic AST-backed lowering from text recognition.
Generic textual publication may remain only when it is structurally anchored,
name-independent and proven equivalent; component-specific recognition must be
removed from the production path.

## Increment-by-increment findings

### Earlier platform work through Increment 28

The initial integration added the MorphHDL entry path, validation-phase
inventory and parameterized backend. The cumulative baseline comparison shows
changes in native phase planning and compiler-plugin integration, notably
`Phase.scala`, `SpinalVerilogPhasePlan.scala` and `MainTransformer.scala`.
These changes predate the single-source corrective roadmap but are included in
the zero-diff review because the requested boundary applies to all native
SpinalHDL source, not only library components.

### Increment 29 — Single-source symbolic-width bridge

Direct native changes were introduced in `Spinal.scala`, `UInt.scala` and
`ComponentEmitterVerilog.scala`. MorphHDL also added the symbolic-width sidecar
inside `spinal.core`. The replacement must retain the Increment 29 port-width
contract through an external MorphHDL entrypoint and registry without changing
the native `UInt` front door or native emitter.

### Increment 30 — Symbolic data shapes

Direct native changes were introduced in `BaseType.scala`, `Bits.scala`,
`SInt.scala`, `UInt.scala` and `ComponentEmitterVerilog.scala`, mainly to attach
or copy symbolic width metadata through constructors and clones. The replacement
must prove equivalent propagation through `HardType`, `Bundle`, `Vec`, `Stream`
and `Flow` without changing those native constructors or clone methods.

### Increment 31 — Generic expressions and connections

The increment added a MorphHDL native-fallback lowerer and changed
`PhaseVerilog.scala` to invoke it. The ordinary expression algorithm was not
reimplemented, but the integration still patches the native Verilog phase. The
replacement must invoke the same validation and native emission from an
external MorphHDL-controlled phase boundary.

### Increment 32 — Hierarchy and parameter binding

The hierarchy analysis is MorphHDL-specific, while `PhaseVerilog.scala` was
again changed for routing. The replacement must preserve canonical module
identity and parameter binding without a native phase patch and without relying
on unstable emitted instance formatting.

### Increment 33 — Structural loops and generate control

The increment added structural-capture metadata and structural Verilog lowering
inside the `core` source tree, with further `PhaseVerilog.scala` integration.
The replacement must move capture and lowering ownership outside native core and
retain symbolic structural alternatives until Verilog generation.

### Increment 34 — Generic combinational and sequential processes

The increment added process-capture and process-lowering machinery inside the
`core` source tree and changed `PhaseVerilog.scala`. The replacement must keep
ordinary Spinal driver, latch, clock and reset validation authoritative while
moving parameterized procedural-loop capture outside native core.

### Increment 35 — Native symbolic memories

Direct native changes were introduced in `Mem.scala` to accept and attach
`ParameterizedMemoryDepth`; the native Verilog phase was also extended.
MorphHDL memory metadata and lowering were added inside `core`. The replacement
must discover ordinary `Mem`, `readSync` and `write` structure externally,
preserve all reviewed collision, enable and bounds policies, and restore
`Mem.scala` unchanged.

### Increment 36 — Native library reuse

This increment contains the largest direct library coupling:

- ordinary `Mem.apply(Int)` and `Mem.fill(Int)` were changed to attach symbolic
  element metadata automatically;
- `Counter.scala` gained a `ParameterizedBitCount` path and altered counter
  state construction, overflow tests, literals and arithmetic for that path;
- `Stream.scala` changed the synchronous FIFO memory-read call to state an
  explicit collision policy.

The replacement must use the unmodified native Counter, Stream, Flow and
static-depth StreamFifo algorithms and infer their retained symbolic geometry
externally. Any behavioral correction that is independently valid for upstream
SpinalHDL must be separated from MorphHDL parameterization and discussed before
being proposed as a native change.

### Increment 37 — Parameterized StreamFifo depth

Direct native changes were introduced in `Stream.scala` for a
`ParameterizedMemoryDepth` overload and resized pointer increments. A
MorphHDL-specific helper was added in the `lib` source tree. The backend then
recognizes emitted FIFO ports and signal-name families to generalize a
non-power-of-two witness implementation across depths 1, 3, 5 and 8.

The final architecture must restore the unmodified StreamFifo source, retain the
native depth-dependent alternatives, and emit parameter-controlled Verilog
structure from those alternatives. It must not identify StreamFifo by module,
port or signal names, and it must not substitute a separately authored FIFO
algorithm.

## Approval gate for any native change

No corrective increment may modify an upstream-owned SpinalHDL file merely
because it is convenient or because package-private access is unavailable.

If an increment cannot meet its contract with the native source unchanged, work
must stop before such a modification is made. The proposed architecture
decision must be presented for explicit approval and must include:

1. the exact upstream-owned file and minimal proposed diff;
2. the capability unavailable through existing public, plugin or phase APIs;
3. alternatives investigated and why each is insufficient;
4. concrete-mode and upstream-sync compatibility impact;
5. whether the hook is generic or parameterized-Verilog-specific; and
6. a removal or upstreaming strategy.

Until that approval is given, the increment remains unchecked and no native
source exception is allowed.

## Validation retained during correction

The corrective work must preserve the existing dual-Scala test matrix and all
applicable contracts from Increments 29–37, including:

- concrete `SpinalVerilog` parity at the witness configuration;
- strict Verilog-2001 compilation;
- public parameter override tests;
- hierarchy uniqueness and parameter binding;
- structural generate and procedural-loop behavior;
- memory bounds, enable and collision policy;
- Counter, Stream and Flow behavior;
- StreamFifo depths 1, 3, 5 and 8;
- simulation, lint, synthesis, mutation and deterministic generation gates; and
- inherited SpinalHDL validation-phase coverage.

The controlling sequence is recorded in
[`parameterized-verilog-todo.md`](parameterized-verilog-todo.md).