# Increment 56 — native-looking typed library-call surface

## Status and scope

Increment 56 supplies the application-facing call surface above the typed
native primitives completed in Increment 53f and audited in Increment 55.
Application RTL can now import ordinary `spinal.core._` and `spinal.lib._`
factories and pass MorphHDL parameters directly:

```scala
import morphhdl.frontend.{formalParam, HdlBool, HdlInt}
import spinal.core._
import spinal.lib._

val counter: Counter = Counter(depth, increment)
val stream: Stream[Bits] = Stream(Bits(width bits))
val flow: Flow[Bits] = Flow(Bits(width bits))
val memory: Mem[Bits] = Mem(HardType(Bits(width bits)), depth)
val vector: Vec[Bits] = Vec(Bits(width bits), depth)
val child: Child = new Child(width)
```

No MorphHDL-prefixed hardware constructor participates in that source. The
only MorphHDL imports are parameter declarations and the explicit
definition-side child-formal declaration. Broad migration and retirement of
the remaining compatibility adapters stay assigned to Increments 57 and 58.

## Target-directed typed ingress

`HdlInt` and `HdlBool` now provide companion-scope, one-way adaptations to
`spinal.core.ElabInt` and `spinal.core.ElabBool`. Each adaptation is guarded by
an exact `=:=` target witness. This restricts the conversion result to the
corresponding native typed carrier while preserving overload resolution:

- a call whose applicable overload explicitly expects `ElabInt` or `ElabBool`
  may select the typed lane;
- the integer conversion lives in an inherited low-priority trait, so the
  established `width bits` companion extension wins during member lookup and
  ordinary `8 bits` remains unambiguous in both supported Scala versions.

Scala does not chain the existing `Int => HdlInt` lift through the new typed
adaptation. A literal such as `Counter(5)` therefore continues to select the
ordinary concrete overload. An exact `HdlInt` overload also remains more
specific than the typed adaptation, preserving existing frontend source
behavior.

There is deliberately no reverse `HdlInt => Int`, `ElabInt => Int`,
`HdlBool => Boolean`, or `ElabBool => Boolean` conversion. The concrete
witnesses on the neutral carriers remain inaccessible to application source.

## Authenticated Boolean evidence

`HdlBool.asElabBool` uses two closed paths:

1. A parameter-free frontend Boolean becomes the canonical native typed
   literal.
2. A symbolic Boolean is encoded by the frontend AST as an integer select of
   one or zero. The existing analyzer-sealed `HdlInt` publication path
   authenticates its exact root and exhaustive table, after which native typed
   equality derives the `ElabBool`.

The frontend single-root evaluator now admits exactly one Boolean declaration
root. It evaluates that exact token over the canonical integer schema values
zero and one. The root comes from token identity and the expression comes from
the typed frontend AST; rendered names, concrete witnesses, component names,
source paths and emitted RTL names never reconstruct authority. Independent
or local roots do not receive a single-root permit and fail when a typed
consumer requires exact evidence.

No raw public `ElabBool.fromExpression` constructor, compiler phase, call-site
token or runtime provenance registry was added.

## Native application contract

`NativeTypedLibraryCallSurfaceFixture.scala` is intentionally outside both the
`morphhdl` and `spinal` package trees. Its native result annotations make the
contract explicit:

| Family | Required application spelling | Retained typed behavior |
| --- | --- | --- |
| Counter | `Counter(depth, increment)` | exact symbolic state count and address width |
| Stream | `Stream(Bits(width bits))` plus ordinary pipe methods | symbolic payload ports and registers |
| Flow | `Flow(Bits(width bits)).m2sPipe()` | symbolic payload ports and registers |
| Mem | `Mem(HardType(Bits(width bits)), depth)` | symbolic word width and unpacked depth |
| Vec | `Vec(Bits(width bits), depth)` | packed symbolic element width and depth |
| Hierarchy | `new Child(width)` | one canonical child definition with `.WIDTH(PARENT_WIDTH)` |

The child explicitly calls `formalParam(actualWidth, "WIDTH", ...)` inside its
definition. That is the public, deterministic declaration of the child formal;
the parent construction itself stays an ordinary Scala `new Child(width)`.
This preserves the distinction between the child definition's fresh formal and
the parent instance's exact actual without constructor rewriting. The existing
one-typed-scalar-formal-per-component limit is unchanged.

The same fixture contains paired `Int`/`ElabInt` and
`Boolean`/`ElabBool` probes. Raw literals select the concrete lanes, while
`HdlInt.param` and `HdlBool.param` select the typed lanes. Boolean library
algorithm flags that still accept only `Boolean` are not widened here; that
broader native library migration belongs to Increment 57.

## Negative source boundary

`check-native-typed-library-surface-boundary.sh` seals the external fixture and
rejects:

- wildcard frontend imports or frontend `Bits`, `UInt`, `SInt`, `HardType`,
  `Reg`, `Vec`, `Mem`, `Stream`, `Flow`, `StreamFifo` and `cloneOf` factories;
- `MorphCounter`, `MorphStream` and `MorphFlow` aliases;
- explicit `.asElabInt`, `.asElabBool`, witness or constant extraction at the
  application call sites;
- retired native-Int shadow machinery and component, source-file or emitted-
  name matching; and
- an implicit symbolic-to-`Int` or symbolic-to-`Boolean` conversion.

The checker also requires each ordinary constructor spelling, native result
annotation, concrete/typed overload pair and authenticated frontend bridge. Its
isolated mutation self-test proves the stable rejection diagnostic.

`NativeLibraryReuseTests` now uses ordinary native `Bits`, `UInt`, `HardType`,
`Stream` and `Flow` imports as well. Compatibility factory deletion is not part
of this increment.

## Acceptance evidence

The focused tests prove:

- exact root, bounds and schema retention for integer and Boolean ingress;
- concrete, typed and legacy-overload selection in Scala 2.12.18 and 2.13.12;
- compile-time rejection of every reverse conversion and witness access;
- null and independent-root fail-closed diagnostics;
- deterministic parameterized RTL containing `PARENT_WIDTH`, `DEPTH` and `ENABLE`;
- typed Counter width, Stream/Flow payload registers, unpacked Mem depth,
  packed Vec shape and one canonical child definition/binding; and
- deterministic raw-literal RTL with no public parameters.

The Increment 56 workflow retains the Increment 55 approved-native-source
audit, production-retirement and typed-layering guards, JVM/source/concrete
compatibility, dual-Scala SBT and Mill suites, strict Verilog-2001 tool checks,
formal equivalence with mutation, determinism and the downstream boundary
self-test. The approved native manifest remains unchanged because Increment 56
does not edit a native production root.

## Closure record

The roadmap checkbox is intentionally not changed by the implementation
revision. It is the final evidence-only transition after all checks pass for
the exact committed source scope; together with WA-06 it unlocks the WA-07
boundary.
