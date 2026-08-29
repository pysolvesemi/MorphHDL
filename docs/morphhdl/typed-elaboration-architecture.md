# Typed elaboration architecture

**Status:** Approved production architecture from Increment 53d onward  
**Decision date:** 2026-08-29

## Decision

MorphHDL will preserve parameter-sensitive elaboration values as typed objects
through SpinalHDL algorithms. It will no longer make zero modification of every
SpinalHDL source file a higher priority than correctness, simplicity and
maintainability.

Small reviewed changes to native `core` and `lib` are allowed when they are
limited to parameter-sensitive types, overloads, helper functions and
mechanical metadata propagation. The existing SpinalHDL algorithms remain the
only production implementations. MorphHDL must not replace StreamFifo,
StreamWidthAdapter, Counter, Mem, Stream/Flow or another library primitive with
a separately authored algorithm.

## Why the previous architecture is superseded

An ordinary Scala `Int` contains only its concrete value. Once a parameter such
as `DEPTH` is converted to `Int(5)`, its symbolic origin, bounds and expression
identity are erased. Recovering them later required parser-wide instrumentation,
source-position references, thread-local constructor scopes, object-identity
registries and replay of branches that native Scala did not execute. That made
build-tool behavior and native graph normalization part of semantic recovery
and repeatedly exposed witness-width and branch-ownership failures.

The typed architecture removes that recovery problem. A value is either a
concrete literal or a typed elaboration expression before the native algorithm
starts, and it remains so until parameterized RTL is captured.

## Neutral low-level types

The low-level carriers live in `spinal.core` or another dependency-neutral
module below both the SpinalHDL library and the MorphHDL frontend:

- `ElabInt`: concrete witness, bounded integer expression, source location and
  exact parameter schemas;
- `ElabBool`: concrete witness, symbolic predicate and provable truth/falsity
  over its complete admitted domain;
- typed bit-count, depth, slice and finite-range adapters derived from those
  carriers.

User-facing `HdlInt` and `HdlBool` may create or bind these carriers. Native
`core` and `lib` must not depend on the high-level frontend or ParamRTL builder.

## Concrete compatibility

Existing concrete overloads remain authoritative:

```scala
StreamFifo(payloadType, 5)          // ordinary Int, parameter-free RTL
Counter(8)                          // ordinary concrete Counter
```

Typed overloads are selected only when a parameter-sensitive value is passed:

```scala
StreamFifo(payloadType, depthParam) // ElabInt, parameterized RTL
Counter(stateCountParam)            // ElabInt
```

`Int` and `Boolean` may be lifted to typed literals when a typed API explicitly
requires them. There must be no implicit `ElabInt => Int` or
`ElabBool => Boolean` conversion. Any concrete witness access is explicit,
internal and audited.

## Operators and helpers

`ElabInt` supplies typed arithmetic, comparisons, equality/inequality, min/max,
address/logarithm helpers and domain validation. `ElabBool` supplies typed
Boolean operations. Helpers such as `elabWidthOf`, typed bit counts, resize,
Mem/Vec depths, Counter limits and finite ranges preserve the expression instead
of returning only a witness.

An operation must fail closed when its complete bounded domain is illegal or
when independent roots cannot yet be represented unambiguously. It must never
infer identity from equal concrete witnesses.

## Natural Scala syntax bridge

Scala requires `Boolean` in a raw `if`, and universal `==` normally returns
`Boolean`. A small pre-typer compiler phase may therefore lower natural source
syntax only when operands are statically proven typed elaboration values:

```scala
if (depth == 1) oneStage else generalStage
```

becomes a call to a typed structural helper using `ElabBool`. The same bridge
may cover `else if`, `require`, Boolean match and finite typed ranges. It is a
syntax transformation, not provenance reconstruction.

The bridge must not:

- instrument arbitrary Scala `Int` or `Boolean` code;
- scan a complete source file merely because it belongs to one component;
- identify StreamFifo, StreamWidthAdapter or another class by name;
- use source positions as the primary symbolic identity;
- infer provenance from equal values;
- recognize emitted Verilog module, port, instance or signal names.

## Symbolic alternatives and validation

A domain-constant condition is folded before native structural capture, so
impossible alternatives do not enter the concrete witness graph. A genuinely
symbolic alternative is captured into parameterized structural IR and validated
in the parameter domain narrowed by its predicate. It must not be validated as
though every alternative used the default witness geometry.

This rule prevents, for example, a `DEPTH == 1` StreamFifo assignment from being
checked against a default `DEPTH == 5` width merely because both source branches
are retained for Verilog generation.

## Approved native changes

Native modifications are allowed only through a reviewed manifest and should be
limited to:

1. adding neutral typed carriers;
2. adding concrete-preserving overloads;
3. changing a parameter-sensitive formal from `Int` to the typed internal form
   while retaining an `Int` entry point;
4. changing a helper call from a concrete-returning helper to its typed
   counterpart;
5. mechanically propagating typed width/depth/range metadata through native
   construction, cloning, resize and hierarchy;
6. adding no-op concrete delegation needed for compatibility.

Algorithm duplication, component-specific lowering and unrelated native edits
remain prohibited.

## Migration policy

The native-`Int` shadow implementation is frozen as historical scaffolding. New
features must use the typed path. Components migrate incrementally, beginning
with StreamWidthAdapter and StreamFifo. After typed parity covers all supported
uses, native-`Int` shadow propagation, source-file special cases and component-
specific recognizers are removed from production.

Every migration requires dual-Scala compilation, concrete parity,
deterministic parameterized generation, strict Verilog-2001 lint/synthesis,
simulation where applicable, formal equivalence against independently generated
concrete witnesses and a negative mutation or ambiguity control.
