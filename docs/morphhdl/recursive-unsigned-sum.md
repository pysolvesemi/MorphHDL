# Explicit recursive unsigned sum

`spinal.lib.RecursiveUnsignedSum` is an opt-in combinational MorphHDL component.
It publishes one Verilog-2001 definition with two guarded references to itself.
It does not change `Vec.reduceBalancedTree`: that API keeps its existing native
operator order, odd-tail handling, `levelBridge` calls and register placement.

## Scala example

```scala
import spinal.core._
import spinal.lib.RecursiveUnsignedSum
import morphhdl.MorphVerilog

MorphVerilog(SpinalConfig(targetDirectory = "rtl")) {
  new RecursiveUnsignedSum(
    countActual = ElabInt.literal(16),
    termWidthActual = ElabInt.literal(32),
    sumWidthActual = ElabInt.literal(36),
    maximumCount = 17,
    maximumTermWidth = 32,
    maximumSumWidth = 36
  )
}
```

The native formal names are `COUNT`, `TERM_WIDTH` and `SUM_WIDTH`. The packed
`terms` input has `COUNT * TERM_WIDTH` bits; term zero occupies its least
significant `TERM_WIDTH` bits. `sum` is unsigned and has `SUM_WIDTH` bits.
Actuals may be typed parent parameters or literals inside the declared domains.
The default upper bounds are 64, 64 and 128 respectively. Every lower bound is
one; `maximumCount` must be at least two so both recursive cases can be captured.

The generated example is in [recursive-unsigned-sum.v](recursive-unsigned-sum.v). Its source defaults are
16/32/36, but the same artifact accepts legal parameter overrides, including
`COUNT=1`. A source default of one also retains the complete step branch.

## Arithmetic and structure

For `COUNT=1`, the single term is zero extended to `SUM_WIDTH`, or truncated to
its low `SUM_WIDTH` bits when narrower output is requested. For `COUNT>1`, the
left child receives terms `[0, COUNT/2)` and the right child receives the rest.
Both children retain `TERM_WIDTH` and `SUM_WIDTH`; their results are added at
`SUM_WIDTH`. The result is the unsigned mathematical sum modulo `2^SUM_WIDTH`.
For 16 unsigned 32-bit terms, 36 result bits hold every possible sum without
overflow: the maximum is `16 * (2^32 - 1) = 2^36 - 16`.

There are no clocks, state elements or latency. Recursive module elaboration
terminates at count one; recursive calls are not runtime hardware operations.
The maximum number of addition levels is `ceil(log2(COUNT))`. Odd counts use
floor and remainder halves without padding or dropping a term.

Four-state behavior follows Verilog unsigned conversion and addition. A
singleton can preserve X/Z bits that survive conversion. With multiple terms,
unknown bits consumed by addition propagate according to Verilog arithmetic.
The behavioral gate checks X/Z against an independently authored flat reference;
the SMT equivalence check is explicitly a two-state proof.

## Authentication and limits

The recursive validator checks each exact direct child independently: one
metric strictly decreases into its declared legal domain on every active
recursive branch, and the other formals are preserved. It substitutes the
actual expressions into the exact owning port widths and proves the child's
widths agree. The finite substitution proof has a 65,536-point budget per port;
unsupported larger proof domains are rejected, not sampled at a default.

The private packed-partition operation constructs both disjoint slices together
and retains exact native source, result, width and child-connection identities.
Publication rejects mutated or missing edges. It does not infer slices from
printed Verilog. Per-component publication excludes only validated external
self-reference identities from definition allocation; instances retain their
real module name throughout native comparison and emission.

This API supports unsigned, fixed-result-width addition only. It does not accept
arbitrary callbacks, signed reductions, widening operators, register bridges,
mutual recursion or non-local recursion. Existing generic reductions continue
to use their existing stage implementation. Conflicting definitions, escaped
expressions, invalid bindings and competing drivers remain errors.

## Reproducible qualification

```sh
SBT_TEST_PARALLEL=0 sbt \
  'morph/testOnly morphhdl.RecursiveUnsignedSumTests spinal.core.RecursivePartitionSafetyTests morphhdl.BoundedRecursivePowerTests morphhdl.BoundedRecursiveSafetyTests morphhdl.ReduceBalancedTreeNativeContractTests' \
  'morph/Test/runMain morphhdl.RecursiveUnsignedSumArtifactWriter /tmp/recursive-sum/rtl'
for default in 1 16; do
  python3 morphhdl/scripts/check-recursive-unsigned-sum.py \
    --rtl /tmp/recursive-sum/rtl/default-$default-split-false/RecursiveUnsignedSum.v \
    --output /tmp/recursive-sum/checks-default-$default
done
```

Repeat with `++2.13.12` before the sbt tasks. The affected structural/process CI
workflow runs both supported Scala versions and retains its artifacts. Its
runtime matrix tests counts 1, 2, 3, 5, 7, 8, 15, 16 and 17 with width pairs
1/1, 3/2, 3/8, 8/8 and 32/36. Each profile runs Icarus in Verilog-2001 mode,
Verilator lint, Yosys complete elaboration/synthesis/latch checks, and a Yosys/Z3
flat-reference proof. Stimuli include zero, all ones, every one-hot position,
seeded random inputs and X/Z. Invalid parameter overrides must reject rather
than time out. Nonpositive packed-width overrides are rejected by strict lint
before simulation; invalid source dimensions are also rejected by the Scala API. Compiler negatives also cover malformed recursive children and
partition lineage.

Each hardware run records commands, return codes, elapsed times, tool paths and
the exact RTL SHA-256 in `results.json`; logs and formal models are retained.
Local repair evidence and historical evidence do not qualify a new source head.
Exact candidate targeted CI must pass before full CI under the repository policy.
Questa results must be reported separately when that tool is available.

The module uses standard `$display`/`$finish` domain guards in simulation.
Verilog-2001 does not define a portable nonzero exit task; validation therefore
requires the positive completion marker and treats an early domain diagnostic
as rejection even when the simulator exits zero. Existing clients retain
their established fatal-guard policy. Guards are excluded during synthesis;
only overrides within the declared legal domain are supported.
