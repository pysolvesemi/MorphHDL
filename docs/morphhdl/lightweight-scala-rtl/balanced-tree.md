# Lightweight balanced-tree library (development candidate)

This implementation is under qualification. It does not change the existing
`Vec.reduceBalancedTree` implementation or authorize application pin migration.
The body lives in `lib/src/main/scala/spinal/lib/BalancedTree.scala`: ordinary
parameter declarations, `if`, `for`, Vec indexing and direct self-construction
implement adjacent-pair recursion. It is not a wrapper around a low-level tree.

```scala
import spinal.core._
import spinal.lib.BalancedTree

val result = BalancedTree.reduceBalancedTree(
  inputs, moduleName = "StatusCounterDeltaSum", pipelineMask = null
)(_ + _)
```

Payload type and callbacks specialize the definition. COUNT remains a bounded
HDL parameter. Every operation must produce the original payload geometry;
this API does not infer widening operators. UInt arithmetic therefore has the
ordinary unsigned modulo-width behavior, and SInt operations retain their signed
type. To sum sixteen unsigned 32-bit terms without overflow, supply 36-bit
extended terms and a 36-bit operation.

`define(...)` retains one operation description for repeated roots.
`defineWidth(factory, width, capacity, moduleName)` additionally exposes DATA_WIDTH
for scalar BitVector payloads; the factory must use its supplied typed width.
`Operation.component(...)` exposes the typed component with explicit clock, reset,
soft-reset and enable ports. The `reduceBalancedTree` adapter connects these to
the current clock domain. It does not infer arbitrary CDC/multi-domain callbacks.

For every non-leaf stage, the ordering is:

1. Combine adjacent lanes left to right. An odd last lane bypasses the operation.
2. Apply `levelBridge(value, level)` to every lane, including the odd tail.
3. Apply the built-in register if the corresponding pipeline-mask bit is set.
4. Pass that stage to the single recursive child.

The bridge level is the existing concrete Scala Int contract, starting at zero.
The library internally specializes level-dependent callbacks. Callback registers
and mask registers compose in that order; neither replaces the other. Reset
polarity/kind and soft-reset/enable priority follow the supplied ClockDomainConfig.
COUNT=1 bypasses operations, bridges and stage registers.

A null pipeline mask omits PIPELINE_MASK entirely. An explicit zero retains the
formal and its selectable register branches. Bit zero refers to the first
reduction stage. INITIAL_COUNT exists only when a bridge or mask needs level
metadata; direct HDL instantiations must bind it to the root count. A recursive
child retains INITIAL_COUNT while reducing COUNT. Do not mix incompatible
specializations (including omitted/present mask interfaces) under one explicit
module name.

Native Verilog-2001 uses a packed aggregate port with the first lane least
significant. The frontend and backend retain typed aggregate identities through
child connections. No production Verilog is authored or repaired as text.

The progress record lists the checks actually run. Generic callback capture,
all geometry/pipeline combinations, application migration, cleaned RTL and remote
qualification remain separate acceptance obligations; this document does not
claim them complete.

## Symbolic scalar adapters

The convenient adapter now retains the exact native Vec payload-width expression
for UInt, SInt and Bits, forwarding it to DATA_WIDTH and constructing the parent
result at that width. It uses an authenticated typed Vec; a plain Scala sequence
of symbolic-width values is rejected instead of borrowing its first element's
witness geometry. Concrete sequence support is unchanged. Scalar subclasses and
symbolic composite factories are not inferred by this adapter.

`Operation.component(..., payloadWidth = width)` forwards an explicit typed
actual for an operation created with `defineWidth`. Existing calls retain their
default width. An authored factory that ignores DATA_WIDTH remains rejected.

Local cross-version evidence combines source COUNT defaults 1 and 5, count
overrides 1/2/3/5/7/8/15/16/17, widths 1/8/32/36/64, omitted masks and mask overrides
0/1/7/31. An independent adjacent-pair reference checks unsigned/signed subtraction
and bitwise XOR, asynchronous reset, soft reset, enable stalls and X/Z inputs.
Lint and complete recursive synthesis pass, with the existing safety and reduction
formal regressions retained. Remote qualification and application migration remain
pending; see progress.json for exact evidence locations.
