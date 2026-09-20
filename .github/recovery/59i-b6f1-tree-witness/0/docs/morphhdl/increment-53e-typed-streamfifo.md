# Increment 53e — Typed StreamFifo depth and branch-local geometry

Increment 53e migrates the authoritative native `spinal.lib.StreamFifo`
implementation from an erased symbolic `Int` depth to a retained `ElabInt`
depth. The public `Int` constructor and companion entry point remain available
and generate parameter-free RTL. There is still one FIFO algorithm and one
native `StreamFifo` definition; typed carriers change how its geometry and
alternatives are represented, not its handshake or storage behavior.

## Typed depth boundary

The two-argument typed companion entry point accepts an `ElabInt` and uses the
ordinary default FIFO configuration. A concrete typed literal delegates
directly to the native implementation. During parameterized capture, a
symbolic value creates one fresh definition-side `DEPTH` formal through
`ElabFormalComponent`; the parent actual may be a compound single-root
expression such as `BASE + 1`. Outside parameterized capture, the same symbolic
call delegates to its concrete witness so ordinary `SpinalVerilog` remains
parameter-free. Formal and actual identity is retained by the generic
formal-parameter registry, without native-`Int` capture.

A symbolic actual must be positive, fit Scala `Int`, contain no generate index,
and retain exact single-root evidence. Exhaustive evidence is capped at 65,536
root values. The child definition deliberately declares `DEPTH` over
`[1, actual.maximum]`, even when a compound caller has a higher minimum, so a
call-site bound cannot fold away definition-local FIFO alternatives. Concrete
`Int` and typed-literal depth zero remain supported by the legacy concrete
bypass case. Inexact or ambiguous active-root evidence fails closed.

The native class stores the typed depth as its authoritative geometry value.
Its legacy public `depth: Int` accessor exposes only the construction witness
for source and JVM compatibility; FIFO logic does not recover symbolic meaning
from that witness.

## Exact branch domains

One bounded typed integer or Boolean expression may carry exhaustive evidence
for one exact parameter-root object. The evidence is derived from the frontend
AST and declaration identity, never from rendered Verilog, a parameter name,
source position, or an equal witness.

`ElaborationDomainContext` intersects this evidence with the currently captured
alternative. It selects the original default when the branch admits it and a
deterministic admitted representative otherwise. Therefore all alternatives
are constructed and validated with legal local geometry. For `DEPTH` in
`1 ... 8`, the important regions are:

| Region | Admitted values |
|---|---|
| `DEPTH == 1` | `1` |
| `DEPTH > 1` | `2, 3, 4, 5, 6, 7, 8` |
| `DEPTH > 1 && isPow2(DEPTH)` | `2, 4, 8` |
| `DEPTH > 1 && !isPow2(DEPTH)` | `3, 5, 6, 7` |

Typed `if` and `generate` capture those exact truth sets in the structural IR.
An absent generate alternative uses a compiler-owned synthetic empty block.
Nested predicates reuse the exact root identity so their domains intersect.
Typed requirements and width-equivalence checks use the active intersection
and fail closed when evidence is missing or ambiguous.

Every narrowed integer expression also carries private provenance on that
exact expression object. Lowering rechecks the final native declaration,
value, memory, or child owner by JVM identity: its structural path must be a
subset of the recorded projection, all owner-domain evaluations must fit the
retained bounds, and the native construction witness must equal the exact
result at the final owner's deterministic representative. A case-class copy
does not copy this provenance, so reconstructed or escaped branch geometry
fails closed instead of being correlated by schema, spelling, or witness.

## Primitive adapters

The migration adds the neutral primitives needed by the unchanged FIFO
algorithm:

- numeric typed `log2Up`, including `log2Up(1) == 0`;
- typed `isPow2` and Boolean-to-integer encoding;
- typed `2 ^ x` (`pow2`) for the native FMax counters;
- typed `Mem` depth and explicit retained integer-to-`UInt` values;
- typed `Vec` and finite ranges when the active domain proves one exact size;
- fail-closed rejection of varying witness-unrolled `Vec` or range geometry;
- captured-domain width comparison between symbolic and concrete expressions;
- typed child-formal binding for direct and compound depth actuals.

The FIFO's explicit typed `UInt` pointer-to-address resize is captured by exact
carrier, assignment, and target-expression identity before a witness-sized
native `Resize` can be normalized away. The capture marker is private to the
MorphHDL phase and is removed before native width inference, so it cannot turn
an explicit resize into native `.resized` behavior. Equal default witnesses do
not authorize a different consumer width. General witness-no-op capture for
typed `Bits` and `SInt` resize publication remains part of Increment 53f's
broader resize closure.

True symbolic `Vec` and general finite-range lowering remain part of Increment
53f. Increment 53e never silently unrolls the global default when the active
size varies.

At the Increment 53e closure boundary, the existing FIFO formal-inspection
helpers still required a concrete depth because they enumerated storage
locations in Scala. Increment 53f closes that documented deferral for
`formalCheckLastPush`, `formalCheckRam`, both `formalContains` and `formalCount`
overloads, and `formalFullToEmpty`. The generic typed finite-range and fold
machinery retains the symbolic depth, while exact branch-owner tokens extend
the already captured one-stage or storage alternative. This later closure does
not change what 53e proved: no helper may recover geometry from the default
witness, an emitted name, a source position, or a component recognizer.

## Legacy shadow removal for StreamFifo

The native-`Int` shadow plugin remains historical scaffolding for unmigrated
components, but it no longer makes `Stream.scala` eligible, recognizes the
`StreamFifo` class, captures a constructor argument named `depth`, or contains
FIFO-specific memory, stream, Boolean, generate, match, or mixed-`UInt`
rewrites. The old `ParameterizedMemoryDepth` FIFO overload is removed. The
typed FIFO path remains functional when the legacy shadow plugin component is
unregistered completely.

## Compatibility boundary

The legacy JVM constructor descriptor, companion `apply` descriptor and
default getters, reordered named/default source calls, and public
`depth(): Int` witness accessor remain covered explicitly. At depths 1, 3, 5,
and 8, the legacy constructor, legacy companion and concrete typed-literal
entry points produce byte-identical parameter-free RTL on the migrated head.
The historically supported concrete depth-zero bypass also remains
parameter-free and byte-identical across those entry points; the existing
native FIFO simulation suite continues to exercise its ready/valid passthrough.

## Required closure

Increment 53e is complete only when the exact pull-request head establishes all
of the following on Scala 2.12.18 and 2.13.12:

- complete production and test compilation plus the full MorphHDL suite;
- exact-domain, primitive, compatibility, width-proof, and typed FIFO tests;
- deterministic single-definition Verilog at depths 1, 3, 5, and 8;
- strict Verilog-2001 lint, simulation, and synthesis at every required depth;
- independent generation of native concrete-`Int` witnesses and sequential
  equivalence against matching specializations of the one parameterized FIFO
  definition at every required depth, plus a deliberate mutation
  counterexample;
- byte-identical parameter-free RTL across the legacy constructor, legacy
  companion, and concrete typed-literal entry points;
- operation with native-`Int` shadow registration unregistered;
- exact source scope, reviewed native overlay, no-component-recognizer, and
  no-temporary-artifact contracts.

The roadmap checkbox is the final source change. It remains unchecked until the
implementation head passes every canonical gate, and the checked head must pass
the same gates before merge.
