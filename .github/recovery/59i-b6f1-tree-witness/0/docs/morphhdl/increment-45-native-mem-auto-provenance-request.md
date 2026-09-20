# Increment 45 request — automatic native `Mem` symbolic-depth provenance

## Status

Implementation request accepted on branch `agent/increment-45-native-mem-auto-provenance`, based on `parameterized-verilog` commit `fe1072fb419fb5d442787a32dfd04a30f631f69c`.

## Objective

Allow ordinary native-looking construction to accept a dual-valued MorphHDL depth without requiring the compatibility factory `morphhdl.frontend.Mem`:

```scala
import spinal.core._
import morphhdl.frontend.{HdlInt, NativeMemFactoryOps}

val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
val memory = spinal.core.Mem(HardType(Bits(8 bits)), depth)
```

Only the checked concrete witness may enter the untouched SpinalHDL `Mem` constructor. The complete symbolic expression must remain externally associated with the exact returned native `Mem` object.

## Required contracts

1. `core/src/main/scala/spinal/core/Mem.scala` remains byte-for-byte unchanged.
2. `Mem(HardType(...), 5)` remains an ordinary concrete native call.
3. `spinal.core.Mem(HardType(...), DEPTH)` emits a memory range using `DEPTH` under `MorphVerilog`.
4. Direct and compound `HdlInt` expressions retain their exact bounded expression, not merely their current integer witness.
5. Provenance uses a deterministic source/call-site token and exact native-object identity.
6. No registry or discovery path may infer provenance by matching `Int` or `BigInt` values.
7. Equal concrete witnesses with different symbolic expressions remain distinct.
8. Repeated use of the same symbolic expression groups the referenced formal parameter normally.
9. Null, invalid-domain, ambiguous and conflicting provenance paths fail explicitly.
10. Ordinary `SpinalVerilog` remains concrete.
11. Existing Increment 43 native-memory contracts remain green.
12. Replay is byte deterministic on Scala 2.12.18 and Scala 2.13.12.
13. The retained record must remain composable with Increment 46 formal-parameter identity without depending on that future API.

## Non-goals

- No edit to the native `Mem` companion or class.
- No compiler-plugin rewrite.
- No generalized `HdlInt => Int` conversion.
- No change to native read/write-port construction, clocking, enables, masks, collision policy or initialization.
- No formal-parameter identity semantics from Increment 46.
- No removal of the Increment 43 compatibility factory.

## Required evidence

- direct symbolic-depth emission;
- literal concrete fallback;
- inlined compound expression emission;
- same-expression grouping;
- equal-witness/different-origin separation;
- exact-object conflict rejection;
- invalid/null negative paths;
- ordinary `SpinalVerilog` fallback;
- deterministic replay;
- native source-boundary guard;
- both supported Scala versions.
