# Increment 49 — native `Int` symbolic provenance propagation

Increment 49 extends the Increment 47 external formalization boundary with a
bounded shadow record for selected ordinary Scala `Int` values. The native
constructor and local variables remain normal `Int` values; MorphHDL stores a
separate immutable provenance record containing the unchanged witness and the
corresponding definition-side and parent-side symbolic expressions.

This increment establishes direct-alias propagation only. It does not interpret
native arithmetic, comparisons, helper calls or Scala control flow. Those
operations remain fail-closed until Increments 50 and 51.

## Selection API

A `formalComponent` or `formalRegion` boundary automatically selects the direct
constructor argument. Native code may explicitly retain a direct local alias
with `shadowInt`:

```scala
import morphhdl.frontend.shadowInt

final class NativeLeaf(width: Int) extends Component {
  val selectedWidth = shadowInt(width, "selectedWidth")
  val din = in(UInt(selectedWidth bits))
  val dout = out(UInt(selectedWidth bits))
  dout := din
}
```

`shadowInt` returns the input `Int` unchanged. It records only the deterministic
slot name, source location and witness while the boundary is active. A selected
value whose witness differs from the boundary witness is rejected because
arithmetic provenance is outside Increment 49.

`NativeIntShadow.captureArgument` and `NativeIntShadow.captureLocal` expose
source-aware runtime hooks for later compiler instrumentation. Outside an
active formalization boundary these instrumentation hooks are no-ops, preserving
ordinary native execution. The explicit `shadowInt` API requires a boundary and
fails when used elsewhere.

## Component and region records

When the untouched constructor returns, `ExternalNativeIntShadowRegistry`
attaches the completed capture to the exact returned `Component` or `Data`
identity. A component slot contains:

- the unchanged Scala `Int` witness;
- a definition-side expression such as `WIDTH`;
- the parent-instance actual such as `LEFT_WIDTH`;
- the deterministic slot kind, name and source location; and
- the current boundary token plus its optional parent boundary token.

For `formalComponent`, the Increment 46/47 binding remaps every direct slot from
the parent actual to the canonical child formal. For `formalRegion`, the
retained expression remains the definition and actual unless a formal binding
already exists.

Equal numeric witnesses never establish provenance. Two child instances whose
actuals both default to `8` remain isolated by exact component identity, and an
unselected eight-bit object receives no record.

## Nested boundaries and re-elaboration

An active boundary is held on a thread-local stack only while its untouched
constructor executes. Nested `formalComponent` and `formalRegion` calls record
their parent boundary token and restore the outer scope in strict stack order.
The completed record is immutable and is attached only after construction.
Repeated Spinal elaboration therefore creates new native identities and new,
deterministically equivalent records without relying on stale global `Int`
keys.

## Lifetime and cleanup

Component and region records use weak identity keys:

- `System.identityHashCode` supplies a stable hash while the referent is live;
- equality compares live referents with `eq`;
- `ReferenceQueue` draining removes unreachable entries; and
- records never retain their native `Component` or `Data` key strongly.

The public read-only lookup and live-count methods drain their queues before
reporting. No map is keyed by `Int`, `BigInt`, emitted RTL names or source-level
component names.

## Fail-closed diagnostics

Increment 49 rejects:

- explicit local selection without an active formalization boundary;
- null expressions, captures, components, data or bindings;
- invalid or nonpositive `Int` domains;
- a selected value whose witness differs from the boundary witness;
- duplicate slot identities with conflicting source provenance;
- attaching a capture to a different returned object;
- component/formal owner mismatch;
- definition/actual default disagreement; and
- conflicting records on one exact component, formal slot or data region.

A derived value such as `width + 1` reports
`MORPH-FRONTEND-NATIVE-INT-SHADOW-EXPRESSION-DEFERRED`. Increment 50 will add
bounded expression and predicate propagation rather than guessing from the
concrete result.

## Proof matrix

`ExternalNativeIntShadowProvenanceTests` proves:

| Contract | Evidence |
|---|---|
| constructor argument | the unchanged native `Int` is retained automatically |
| selected local | `shadowInt` returns the same value and records one local slot |
| formal/actual separation | child slots retain `WIDTH` and the correct parent actual independently |
| exact identity | equal witnesses on parallel children never alias |
| nested scopes | inner records point to the outer boundary and the outer scope resumes |
| region propagation | an exact `Data` result retains argument and local provenance |
| concrete parity | ordinary `SpinalVerilog` and native runtime values remain concrete |
| deterministic replay | repeated elaboration produces identical provenance signatures |
| deferred arithmetic | changed witnesses fail with the Increment 50 boundary diagnostic |
| conflict handling | duplicate source identities and orphan selections fail explicitly |
| weak lifetime | records are keyed only by weak exact component/data identities |

The permanent `MorphHDL native Int shadow provenance` workflow runs the focused
suite, the complete Increment 47 formalization suite, formal identity/hierarchy
regressions and the native-source preservation guard on Scala 2.12.18 and
Scala 2.13.12.
