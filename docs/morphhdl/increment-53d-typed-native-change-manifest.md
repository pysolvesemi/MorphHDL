# Increment 53d typed native-change manifest

The approved typed-elaboration architecture permits the following reviewed
native change. The original algorithm remains authoritative.

| File | Classification | Change | Algorithm impact |
|---|---|---|---|
| `core/src/main/scala/spinal/core/BitVector.scala` | `typed-formal-or-overload` | declares the typed `resize(ElabInt)` entry point on the authoritative native bit-vector API | none; the existing resize implementation remains authoritative |
| `core/src/main/scala/spinal/core/Bits.scala` | `typed-formal-or-overload` | the `ParameterizedBitCount` overload attaches typed width metadata to the result of the virtual `Bits()` factory instead of constructing a separate value | none; preserves native `in`/`out`/`master`/`slave` direction dispatch |
| `core/src/main/scala/spinal/core/ElabInt.scala` | `typed-helper` | owns the neutral `ElabInt`/`ElabBool` carriers, bounded operators, exact declaration-root identity and fail-closed domain checks | none; this is carrier metadata below the native algorithms |
| `core/src/main/scala/spinal/core/ParameterizedMemory.scala` | `typed-helper` | preserves exact declaration-root provenance when typed depth and element-width expressions are retained by native memory metadata | none; native memory construction and algorithms remain authoritative |
| `core/src/main/scala/spinal/core/ParameterizedWidth.scala` | `typed-helper` | retains typed packed-width and exact native `Resize` identity metadata, including parameter declaration roots | none; native data construction and resize nodes remain authoritative |
| `core/src/main/scala/spinal/core/SInt.scala` | `typed-formal-or-overload` | the `ParameterizedBitCount` overload attaches typed width metadata to the result of the virtual `SInt()` factory instead of constructing a separate value | none; preserves native `in`/`out` direction dispatch |
| `core/src/main/scala/spinal/core/UInt.scala` | `typed-formal-or-overload` | the `ParameterizedBitCount` overload attaches typed width metadata to the result of the virtual `UInt()` factory instead of constructing a separate value | none; preserves native `in`/`out` direction dispatch |
| `core/src/main/scala/spinal/core/core.scala` | `typed-helper` | adds `widthOfExpr` so native algorithms can obtain packed geometry without erasing a retained expression to `Int` | none; delegates to the existing packed-width traversal |
| `lib/src/main/scala/spinal/lib/Stream.scala` | `typed-mechanical-propagation` | `StreamWidthAdapter` obtains payload geometry with `widthOfExpr`, keeps width arithmetic typed, and extracts only domain-proven constant factors/chunk widths for existing host-side collection and counter APIs | none; equal-width, downsize, upsize, ordering, buffering and ready/valid logic are unchanged |

Forbidden changes checked by CI:

- no separately authored adapter;
- no module, port, signal or emitted-text recognition;
- no native-`Int` shadow capture in the typed adapter path;
- no algorithm change outside the parameter-sensitive width statements above;
- no bypass of virtual packed-data factories, so native I/O direction decoration remains authoritative.

The adapter checks that its input and output geometry contain at most one
symbolic parameter root before any host-side factor or slice count is extracted.
This is a typed expression-domain rule, not a component-name heuristic. The
primary simulation, lint and synthesis fixture now passes `ElabInt` widths
directly and contains no `NativeIntShadow` capture.

The packed-factory regression covers typed `Bits`, `UInt`, and `SInt` inputs and
outputs through the native short-form declarations. It prevents a future typed
overload from bypassing `IODirection` virtual dispatch.
