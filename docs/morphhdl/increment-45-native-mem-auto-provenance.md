# Increment 45 — automatic native `Mem` symbolic-depth provenance

## Resulting source surface

Increment 45 adds one MorphHDL-owned implicit extension to the untouched native `spinal.core.Mem` companion:

```scala
import spinal.core._
import morphhdl.frontend.{HdlInt, NativeMemFactoryOps}

val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 8)
val memory = spinal.core.Mem(HardType(Bits(8 bits)), depth)
```

The Increment 43 `morphhdl.frontend.Mem` factory remains available for compatibility, but it is no longer required for symbolic native-memory depth construction.

A literal call continues to resolve directly to native SpinalHDL:

```scala
val memory = spinal.core.Mem(HardType(Bits(8 bits)), 5)
```

The native `Int` overload is already applicable, so no MorphHDL extension is selected and no depth metadata is created.

## Construction-time handoff

`NativeMemFactoryOps.apply` delegates to `NativeMemAutoProvenance.create`.

The handoff is deliberately ordered:

1. capture the deterministic use-site `sourcecode.File` and `sourcecode.Line`;
2. validate the supplied `HdlInt` through the existing bounded memory-depth bridge;
3. derive a structural signature from the retained expression;
4. call the untouched native `Mem` factory with only `depth.value`;
5. immediately associate the complete retained depth with the exact returned native object;
6. return that native object unchanged to user code.

There is no period in which a later discovery phase must guess which concrete integer belonged to which symbolic value.

## Provenance token

Each retained record contains a `NativeMemCallSiteToken` with:

- the native `Mem` use site;
- the originating `HdlInt` source location;
- the exact rendered expression;
- its default, minimum and maximum;
- its referenced parameter schemas;
- any generate-index marker, which the existing memory-depth validation rejects.

The concrete witness is intentionally excluded from the token. Two values both equal to `5` therefore do not become related merely because their witnesses match.

The token is deterministic: it contains only source locations and immutable structural expression facts. It contains no allocation counter, iteration order, random value or identity hash.

## Exact native-object identity

`NativeMemAutoProvenance` stores records in a weak identity map:

- the key caches `System.identityHashCode`;
- equality uses reference equality (`eq`) on live native `Mem` objects;
- a `ReferenceQueue` removes unreachable keys;
- no `Int` or `BigInt` key exists;
- no scan searches for a matching `wordCount`.

The existing `ExternalParameterizedMemoryRegistry.attach` remains the authoritative geometry association consumed by MorphHDL emission. Increment 45 adds the call-site record before publication while reusing Increment 43's exact-object metadata path.

## Conflict behavior

A second identical association to the same object is idempotent. A different token or retained depth for the same exact native object fails with:

`MORPH-FRONTEND-NATIVE-MEM-PROVENANCE-CONFLICT`

Null word type, null symbolic depth and null native object paths fail before publication. Existing bounded-domain and native-memory geometry errors remain authoritative for invalid expressions.

Because association is immediate and object-specific, repeated source calls that construct different native memories are not ambiguous. Each returned object receives its own record. Equal witnesses from `DEPTH_A` and `DEPTH_B` remain separate because their structural signatures and parameter schemas differ.

## Parameter grouping

Increment 45 does not add a new grouping rule. The existing backend collects the parameter schemas referenced by each retained expression:

- two memories using `DEPTH` publish one compatible `DEPTH` formal;
- memories using `DEPTH_A` and `DEPTH_B`, even when both default to `5`, publish two formals;
- incompatible schemas with the same name still fail through the existing schema-conflict validation.

## Increment 46 composability

This increment does not create or depend on formal-parameter identity. Its record keeps the complete structural signature and exact native-object association. Increment 46 can add canonical declaration identity beside that record without changing the witness handoff, native `Mem` call or object-identity key.

## Preservation boundary

The implementation changes only MorphHDL-owned frontend, tests, workflow, scripts and documentation. It does not edit:

- `core/src/main/scala/spinal/core/Mem.scala`;
- native memory ports or algorithms;
- native elaboration phases;
- the compiler plugin.

`morphhdl/scripts/check-external-memory-boundary.sh` verifies the native hashes, the companion extension, exact-identity association and absence of known concrete-value lookup forms.

## Validation matrix

`NativeMemAutoProvenanceTests` covers:

| Contract | Evidence |
|---|---|
| automatic direct depth | `DEPTH` memory range and guard |
| concrete literal fallback | `[0:4]`, no depth parameter |
| compound expression | `(BASE + 1)` retained in range and guard |
| same-expression grouping | one `DEPTH` declaration for two memories |
| equal-witness separation | `DEPTH_A` and `DEPTH_B` remain distinct |
| exact-object provenance | records inspected by native object identity |
| conflicting association | explicit frontend failure |
| invalid paths | null and nonpositive-domain failures |
| legacy concrete emission | ordinary `SpinalVerilog` has no parameters |
| deterministic replay | byte-identical repeated output |

The external-memory workflow runs this suite together with all Increment 43 memory and inherited hierarchy/library contracts on Scala 2.12.18 and Scala 2.13.12.
