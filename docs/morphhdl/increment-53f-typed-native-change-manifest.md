# Increment 53f typed native-change manifest

This reviewed manifest covers the native source paths under `core`,
`idslplugin` and `lib` that Increment 53f changes. It must be kept synchronized
with the native portion of the final exact source inventory and the cumulative
typed native-source overlay. Runtime, frontend, backend, test, documentation
and workflow paths remain governed by that exact source inventory, not by this
native-change manifest. A native path not listed here is not implicitly
approved.

| File | Classification | Change | Algorithm impact |
|---|---|---|---|
| `core/src/main/scala/spinal/core/Bits.scala` | `typed-formal-or-overload` | routes concrete `ElabInt` resize widths back through the authoritative native `Int` overload and retains metadata only for symbolic widths | none; concrete resize graph shape, inference state and RTL remain native-authoritative |
| `core/src/main/scala/spinal/core/Data.scala` | `typed-mechanical-propagation` | preserves exact typed width and Vec metadata across the existing native hierarchy-pull clone path | none; native pull traversal, direction handling, cache ownership and assignment remain authoritative |
| `core/src/main/scala/spinal/core/ElabInt.scala` | `typed-helper` | adds the positive typed address-width helper and the shared raw-before-projection authority checks for integer/Boolean sources, derivations, canonical concrete values and exact typed primitive domains | none; bounded expression metadata, identity/projection validation and trusted concrete normalization only |
| `core/src/main/scala/spinal/core/ElaborationExactDomain.scala` | `typed-helper` | binds each declaration root to its exact schema identity and exposes shared complete-coverage checks that reject duplicate, missing or replacement evidence before typed primitive projection | none; exact-domain evidence remains identity-rooted and exhaustively bounded |
| `core/src/main/scala/spinal/core/ExternalCompilerPermit.scala` | `typed-helper` | introduces one-use, exact-expression analyzer capabilities for the external typed frontend boundary | none; the permit authorizes already-analyzed metadata and cannot construct or replace native algorithms |
| `core/src/main/scala/spinal/core/ExternalStructuralPredicatePermit.scala` | `typed-helper` | introduces a one-use exact-identity capability for publishing analyzer-exhaustive structural Boolean predicates and their captured native alternatives | none; the permit authenticates already-analyzed predicate and branch identities without replacing structural control algorithms |
| `core/src/main/scala/spinal/core/Mem.scala` | `typed-formal-or-overload` | exposes exact typed address geometry, delegates literal depths directly to the `Int` factory and uses one reviewed witness only for symbolic native port normalization | none; native unpacked memory construction, literal graph shape and port algorithms remain authoritative |
| `core/src/main/scala/spinal/core/ParameterizedMemory.scala` | `typed-helper` | validates public typed depth metadata and derives typed depth/address geometry from complete identity-retained native memory evidence | none; memory storage remains unpacked and symbolic depth remains authoritative |
| `core/src/main/scala/spinal/core/ParameterizedVec.scala` | `typed-helper` | adds identity-retained logical Vec depth, recursive element shape, finite carrier audit and exact native-operation evidence | none; metadata and publication evidence beside the native Vec |
| `core/src/main/scala/spinal/core/ParameterizedWidth.scala` | `typed-helper` | owns declaration-root/schema identity state, validates public typed width metadata, routes typed Vec convenience factories through the shared native typed-Vec registry and preserves clone geometry | none; existing packed-data factories remain authoritative |
| `core/src/main/scala/spinal/core/SInt.scala` | `typed-formal-or-overload` | routes concrete `ElabInt` resize widths back through the authoritative native `Int` overload and retains metadata only for symbolic widths | none; concrete resize graph shape, inference state and RTL remain native-authoritative |
| `core/src/main/scala/spinal/core/UInt.scala` | `typed-formal-or-overload` | routes concrete `ElabInt` resize widths back through the authoritative native `Int` overload and retains metadata only for symbolic widths | none; concrete resize graph shape, inference state and RTL remain native-authoritative |
| `core/src/main/scala/spinal/core/Vec.scala` | `typed-mechanical-propagation` | adds explicit `ElabInt` builders and records typed shape through native access, assignment, packed conversion, cloning and connection methods | none; the existing Vec methods remain the only logical collection algorithm |
| `lib/src/main/scala/spinal/lib/Counter.scala` | `typed-mechanical-propagation` | adds typed state-count/limit overloads and carries exact bounds through the existing binary Counter control and arithmetic | none; concrete BigInt construction and Counter behavior remain authoritative |
| `lib/src/main/scala/spinal/lib/Stream.scala` | `typed-mechanical-propagation` | retains exact StreamFifo branch owners, preserves the symbolic storage pop-address resize in a target-owned weak-named native auto-resize carrier, gives symbolic Vec-storage read/write/index carriers stable diagnostic names, keeps the selected write target on its exact prior value with `io.push.fire` as the sole payload override, and routes symbolic formal inspection through shared finite range/fold helpers plus target-owned native address-width normalization carriers | none; the ordinary Vec access/decoder, pointer, pop-address low-bit selection, push-fire acceptance predicate, selected-register semantics, storage and formal-helper algorithms remain authoritative |

The reviewed classifications permit typed overloads, exact metadata and
mechanical propagation only. They do not permit a parameterized replacement
component, native-`Int` shadow reconstruction, source-position inference,
component or emitted-signal recognition, default-witness-only geometry, a
packed replacement for `Mem`, or SystemVerilog-only Vec publication.
