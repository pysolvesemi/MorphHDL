# Increment 47 — external formalization boundary for native `Int` APIs

Increment 47 introduces explicit MorphHDL-owned adapters for ordinary
SpinalHDL constructors whose geometry argument remains a Scala `Int`. The
adapters hand only a checked concrete witness to the untouched constructor,
then retain the originating bounded `HdlInt` expression beside the exact
returned graph objects.

This increment establishes identity and lifetime. It does not shadow arbitrary
native `Int` arithmetic and it does not recover Scala control-flow alternatives
that were not selected by the concrete witness. Those capabilities remain the
scope of Increments 49 through 52.

## Public adapters

### `formalRegion`

`formalRegion` wraps one native `Int => Data` construction:

```scala
import morphhdl.frontend.{formalRegion, HdlInt}
import spinal.core._

val width = HdlInt.param("WIDTH", default = 8, min = 1, max = 32)
val payload = in(formalRegion(width)(value => UInt(value bits)))
```

The constructor receives `8`, the checked default witness. After the untouched
`UInt(Int bits)` construction returns, MorphHDL attaches the complete retained
`WIDTH` expression to that exact `Data` region and its packed leaves. A
same-width object constructed elsewhere is not associated.

### `formalComponent`

`formalComponent` wraps one native child constructor and an explicit selection
of the child IO geometry controlled by that argument:

```scala
final class NativeLeaf(width: Int) extends Component {
  val din = in(UInt(width bits))
  val dout = out(UInt(width bits))
  val status = out(UInt(8 bits))
}

val child = formalComponent(width, "WIDTH", minimum = 1, maximum = 32) {
  value => new NativeLeaf(value)
} {
  leaf => Vector(leaf.din, leaf.dout)
}
```

Only `din` and `dout` become definition-side `WIDTH` geometry. The equal-width
or fixed-width `status` signal remains concrete because it was not selected by
exact identity. Parallel children of the same class retain one canonical module
definition and independent named actual bindings.

## Identity and lifetime model

`ExternalNativeIntFormalizationRegistry` records two kinds of weak keys:

- the exact `Data` object returned or selected by an adapter; and
- the exact child `Component` returned by `formalComponent`.

Each key caches `System.identityHashCode`, compares live referents with `eq`, and
is removed through a `ReferenceQueue` after its native graph object becomes
unreachable. Stored records contain only immutable source tokens, symbolic
expressions and formal bindings; they do not retain the native object strongly.

The registry deliberately has no `Int` or `BigInt` lookup key, no scan for a
matching bit width, and no emitted module, instance or port-name discovery.
Concrete witness equality is validation only. Two expressions with default `8`,
for example `LEFT_WIDTH` and `RIGHT_WIDTH`, remain unrelated unless the caller
selects their exact objects separately.

## Formal and actual separation

For a native child:

1. the parent-side `HdlInt` is validated as a finite positive `Int` domain;
2. only its concrete default is passed into the untouched constructor;
3. the returned child identity supplies the component-definition owner;
4. the explicit formal name and domain create the canonical child formal;
5. the original expression remains the parent-instance actual; and
6. only selected exact child IO leaves receive the canonical formal geometry.

The existing Increment 46 formal registry remains authoritative for declaration
compatibility, canonical module identity, named instance bindings, clone
recovery and conflict diagnostics. Increment 47 adds the post-construction
identity handoff needed when the native constructor accepted only `Int`.

The registry rejects two different actual expressions for the same declaration
key on one exact component identity as soon as the adapter attaches them. The
failure occurs before either the native or external Verilog hierarchy publisher
can reinterpret an invalid instance; callers must not depend on a later
publisher-specific diagnostic.

The external hierarchy publisher is deliberately idempotent with the inherited
native parameterized path. It recognizes both a plain native instance and an
already parameterized native instance, removes only that exact instance's old
header, and emits one canonical named-parameter header. If later graph
transformations remove formal metadata from every selected port, the publisher
may recover the slot only from the exact canonical/actual component identities;
partial port-metadata loss remains a fail-closed layout conflict.

## Fail-closed boundary

The adapters reject:

- use without an active owner or parent component;
- null constructors, results, selectors or selected regions;
- empty or duplicate component-geometry selections;
- selected data owned by a different component;
- selected child data that is not an exact IO port;
- witness widths that disagree with the selected native packed leaves;
- invalid, unbounded or nonpositive native geometry domains; and
- conflicting metadata on one exact region or component identity.

No component-specific RTL is reconstructed. Native SpinalHDL validation still
runs on the witness-selected concrete graph, and ordinary `SpinalVerilog`
ignores the external records and remains concrete.

## Proof matrix

`ExternalNativeIntFormalizationTests` proves:

| Contract | Evidence |
|---|---|
| native region geometry | ordinary `UInt(Int bits)` ports emit retained parent parameters |
| native hierarchy | untouched `NativeLeaf(width: Int)` emits one canonical parameterized definition |
| per-instance actuals | `.WIDTH(LEFT_WIDTH)` and `.WIDTH(RIGHT_WIDTH)` remain independent |
| equal-witness isolation | unrelated 8-bit objects remain concrete and have no registry record |
| exact identity | component and region records are retrieved only through their native objects |
| literal binding | a parameterized child can bind `.WIDTH(8)` without creating a parent parameter |
| concrete parity | ordinary `SpinalVerilog` emits the unchanged concrete design |
| determinism | repeated MorphHDL publication is byte-identical |
| diagnostics | width mismatch, empty selection and foreign-owner selection fail explicitly |
| inherited publication compatibility | plain and already parameterized native instances canonicalize identically |
| conflicting actuals | one exact component cannot retain two actuals for the same formal declaration key |

The permanent `MorphHDL external native Int formalization` workflow runs this
suite and the inherited formal-identity, clone, hierarchy, single-source and
external-boundary regressions on Scala 2.12.18 and Scala 2.13.12. Its source
boundary also executes the complete native-source preservation guard.
