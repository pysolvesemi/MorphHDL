# Increment 36 — Native library reuse

Increment 36 proves that selected ordinary SpinalHDL library primitives can
consume the single-source symbolic-width bridge directly. The implementation
extends shared core/library representations only; it does not add a
component-specific ParamRTL model or reimplement any library algorithm in the
parameterized backend.

## Supported source paths

### Counter

`Counter(width bits)`, `Counter(width bits, incrementEnable)` and
`CounterFreeRun(width bits)` now accept the same bounded
`ParameterizedBitCount` produced by an `HdlInt`.

The concrete witness still selects the normal full unsigned range during Scala
elaboration. The existing `Counter` control, register, arithmetic, clear,
completion and `toFlow()` logic remains authoritative. Its state `UInt` and
cloned register retain the symbolic packed width, and the upper-bound test uses
a reduction over the state bits so it remains correct for every legal width
override.

This increment deliberately covers a full-range bit-count counter. Arbitrary
symbolic start/end/state-count ranges, Scala-side `stateCount` decisions and
symbolic range policies are not admitted by this contract.

### Stream and Flow pipelines

The ordinary payload cloning already shared by `Stream` and `Flow` now serves
as the implementation path for:

- `Stream.m2sPipe()`;
- `Stream.s2mPipe()`;
- `Stream.halfPipe()`; and
- `Flow.m2sPipe`.

Their existing valid/ready/register algorithms are unchanged. The proof chains
the three Stream stages and separately registers a Flow while retaining one
public symbolic payload-width schema through ports, internal payloads and
registers.

### StreamFifo with static depth

An ordinary fixed-depth `Mem(wordType, depth: Int)` now retains symbolic element
geometry when its `HardType` contains parameterized leaves. Concrete memories
without symbolic element leaves are left untagged and continue through the
ordinary path unchanged.

`StreamFifo(Bits(width bits), depth = 4, latency = 2)` therefore reuses the
existing FIFO component, pointer/occupancy logic, Stream arbitration and native
memory storage. The depth, pointer widths, occupancy widths and depth-dependent
Scala branches remain static in Increment 36. The existing synchronous FIFO
read path now states its required `readFirst` collision policy explicitly, so
the shared native-memory lowering can preserve the queued word on a same-entry
pop/push collision.

The generated hierarchy contains one parameterized FIFO definition, one named
payload-width binding from the wrapper, and one fixed-depth array whose packed
element width is symbolic. Parameterizing FIFO depth is reserved for Increment
37.

## Validation contract

`NativeLibraryReuseTests` checks both supported Scala versions through the
repository test matrix and proves that:

1. the ordinary Counter source emits a symbolic state and completion path while
   matching its concrete native witness;
2. ordinary Stream/Flow pipeline primitives retain the same symbolic payload
   shape and match concrete native emission at the default witness;
3. ordinary StreamFifo emits one reusable child definition, keeps depth static,
   and rewrites only the native memory element geometry;
4. strict Verilog-2001 elaboration succeeds after overriding `WIDTH` from the
   default 8 to 5 for Counter, the pipeline chain and StreamFifo; and
5. generated RTL contains no ParamRTL adapter or component-specific library
   reimplementation.

The normal `SpinalVerilog` path still emits concrete RTL and does not publish a
Verilog parameter. Existing inherited SpinalHDL validation remains active for
all three library paths.
