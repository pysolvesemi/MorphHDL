# Increment 53e typed native-change manifest

The approved typed-elaboration architecture permits the following reviewed
native changes for Increment 53e. The existing native FIFO algorithm remains
the only implementation.

| File | Classification | Change | Algorithm impact |
|---|---|---|---|
| `core/src/main/scala/spinal/core/ElaborationExactDomain.scala` | `typed-helper` | adds identity-rooted exhaustive evidence and branch-local construction projection for bounded typed expressions | none; metadata and validation only |
| `core/src/main/scala/spinal/core/ElaborationProjectionRegistry.scala` | `typed-helper` | retains private expression-identity provenance for an exact branch projection so final native declaration and memory ownership can be certified | none; metadata and validation only |
| `core/src/main/scala/spinal/core/ElabInt.scala` | `typed-helper` | propagates exact evidence through arithmetic, comparison, Boolean, `log2Up`, the `isPow2` predicate, Boolean-to-integer, `2 ^ x` (`pow2`), width, and finite-range adapters | none; typed carrier semantics only |
| `core/src/main/scala/spinal/core/Mem.scala` | `typed-formal-or-overload` | adds the `Mem(HardType, ElabInt)` overload and attaches the projected typed depth to the ordinary native memory | none; native `Mem` construction remains authoritative |
| `core/src/main/scala/spinal/core/Misc.scala` | `typed-formal-or-overload` | adds typed `log2Up` and `isPow2` overloads | none; concrete overloads are unchanged |
| `core/src/main/scala/spinal/core/ParameterizedMemory.scala` | `typed-helper` | validates and retains a projected typed memory depth | none; metadata and native-depth delegation only |
| `core/src/main/scala/spinal/core/ParameterizedWidth.scala` | `typed-helper` | lets integer and Boolean expressions retain optional exact single-root evidence and marks an exact typed resize carrier for identity-only pre-normalization capture | none; the capture-only marker is removed before native width inference |
| `core/src/main/scala/spinal/core/Vec.scala` | `typed-formal-or-overload` | adds typed `Vec`/`Vec.fill` overloads that require an exact active size and fail closed when it varies | none; the existing `Vec` builder remains authoritative |
| `lib/src/main/scala/spinal/lib/Stream.scala` | `typed-mechanical-propagation` | changes the internal FIFO depth to `ElabInt`, preserves the legacy source/JVM constructor, companion/default-getter and `depth(): Int` API surface, and carries typed geometry through the existing FIFO implementation | none; ready/valid, storage, pointer, flush, occupancy, and availability behavior remain the native algorithm |

CI additionally forbids a second FIFO implementation, a `StreamFifo` or
`Stream.scala` compiler recognizer, native-`Int` shadow capture in the FIFO
path, emitted-name discovery, implicit typed-to-concrete conversion, and silent
default-witness unrolling for varying ranges or vectors.
