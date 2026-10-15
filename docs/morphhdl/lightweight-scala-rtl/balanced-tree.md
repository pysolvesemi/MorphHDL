# Lightweight balanced-tree library (development candidate)

This opt-in component preserves the existing `Vec.reduceBalancedTree`
implementation. Application migration is authorized and tracked separately in
the application roadmap. Focused local checks are the current closure policy.
The body lives in `lib/src/main/scala/spinal/lib/BalancedTree.scala`: ordinary
parameter declarations, `if`, `for`, Vec indexing and direct self-construction
implement adjacent-pair recursion. It is not a wrapper around a low-level tree.

```scala
import spinal.core._
import spinal.lib.BalancedTree

val result = BalancedTree.reduce(
  inputs, moduleName = "StatusCounterDeltaSum", pipelineMask = None
)((a: UInt, b: UInt) => a + b)
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

`None` omits PIPELINE_MASK entirely. `Some(Param(0))` retains the
formal and its selectable register branches. Bit zero refers to the first
reduction stage. INITIAL_COUNT exists only when a bridge or mask needs level
metadata; direct HDL instantiations must bind it to the root count. A recursive
child retains INITIAL_COUNT while reducing COUNT. Do not mix incompatible
specializations (including omitted/present mask interfaces) under one explicit
module name.

Legacy nullable-mask calls remain supported. New calls use explicit Option
presence for both the mask and bridge; an explicitly supplied identity bridge
is still a selected bridge policy. No reference comparison determines presence.

Native Verilog-2001 uses a packed aggregate port with the first lane least
significant. The frontend and backend retain typed aggregate identities through
child connections. No production Verilog is authored or repaired as text.

The progress record and parameter API receipt list the checks actually run.
They do not claim arbitrary Scala callback purity, widening operators, or every
possible geometry/pipeline composition. See the application migration receipt
for the separately tested call sites.

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
formal regressions retained. See progress.json and parameter-api-receipt.json for exact evidence locations;
historical results retain their original source identities.

## Generic source and modern parameter declarations

The executable generic example is the compiler-owned `BalancedTree[T]` itself,
not a second application algorithm. Its declaration block uses `Param[Int]`,
`Param.optional` and unconditional bounds; its body uses direct self-construction,
ordinary adjacent pair/tail loops and typed `nextLevel.io.terms := nextData`.
`COUNT` is reduced as `(count + 1) / 2`; the odd lane takes the same bridge and
optional register path as paired lanes. `INITIAL_COUNT` remains unchanged.
The concrete level loop specializes callbacks without changing their Int API.

The public call infers the count domain and scalar payload geometry from `inputs`.
For reuse across several roots, `define(HardType(UInt(width bits)), capacity, name)`
now retains the exact native symbolic width automatically. The older explicit
`defineWidth` factory is a compatibility path; symbolic arbitrary Bundle geometry
is still outside this inference contract.

For an optional bridge, supply `levelBridge = Some((value: UInt, level: Int) =>
value + U(level, value.getWidth bits))` with a fixed-width payload. Width-changing
bridges are rejected. `None` supplies no bridge; `Some(identityBridge[UInt])`
intentionally retains level metadata. Tests cover all three policies, odd tails,
non-associative operations and registered bridges.

A smaller standalone authoring example is
[`CompactReduction[T]`](../../../morphhdl/src/test/scala/morphhdl/CompactReductionExampleTests.scala).
It uses `Param[Int]` constructor actuals, inferred formal names, optional mask and
bridge policies, direct self-construction and a typed `io` Bundle. Its finite
count limit is five, with three possible bridge levels. The example deliberately
uses fixed payload geometry and geometry-preserving callbacks; it is a compiler
fixture, not another production reduction library. Its tests use defaults one
and five, count overrides one/three/five, optional pipeline override one,
subtraction, a level-dependent bridge, reset, random inputs and unknown inputs.
