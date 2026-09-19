# Increment 6: symbolic generate-for frontend

Increment 6 proves that MorphHDL can preserve a parameterized homogeneous loop
before ordinary Scala elaboration erases it. The existing ParamRTL and strict
Verilog-2001 contracts remain unchanged; this increment adds the bounded Scala
frontend that authors their `GenerateFor` node.

## Implemented frontend tranche

- A dual-valued `HdlInt` with a `BigInt` concrete witness, symbolic `IntExpr`,
  public-parameter declaration and inclusive minimum/maximum constraints.
- One-way implicit conversion from `Int` to `HdlInt`. There is no implicit or
  public `HdlInt`-to-`Int` conversion.
- A scoped `GenIndex` that is neither a Scala integer nor an RTL signal.
- `HdlInt * HdlInt` for parameterized packed widths and `GenIndex * HdlInt`
  for the existing canonical indexed part-select offset.
- A native Scala range extension supporting:

  ```scala
  for (lane <- 0 until config.laneCount) {
    // concrete iterations or one symbolic capture
  }
  ```

- Thread-local, dynamically scoped capture with guaranteed restoration after
  normal completion or exceptions.
- A guarded ParamRTL lowering facade for public integer declarations, packed
  widths, child parameter bindings, indexed part-selects, port connections and
  module-instance capture. The facade remains package-scoped until the
  `MorphVerilog` entry point lands.

The exact loop spelling compiles on Scala 2.12.18 and 2.13.12, including next
to inherited `spinal.core` imports. When the upper bound is an ordinary `Int`,
Scala continues to select its standard `Range` implementation.

## Dual execution contract

Concrete mode privately checks that the witness fits a Scala `Int` and invokes
the body for indices `0` through `count - 1`. This conversion is an internal
loop mechanism and is not exposed as an `HdlInt` or `GenIndex` API.

Parameterized mode invokes the body exactly once. It assigns an opaque scope
token to the `GenIndex`, captures emitted module items in a child collector and
appends one existing ParamRTL `GenerateFor` to the parent collector. The count
remains the symbolic expression; the default witness is never used to unroll or
specialize emitted RTL.

Bare loops receive deterministic names derived from source file and line,
which stay stable when unrelated construction order changes. Because runtime
lambdas cannot portably reveal the source binder name, output contracts that
require reviewed identifiers use `.named(label = ..., index = ...)`. The
LaneArray fixture uses this form to retain `g_lane` and `lane` byte-for-byte.

## Fail-closed safety

The frontend rejects:

- nonzero loop starts, concrete witnesses outside the positive Scala `Int`
  range and nested symbolic ranges;
- escaped generate indices and expressions, cross-scope combinations and
  generate-index-dependent widths or child parameter bindings;
- direct Scala collection indexing with `GenIndex` and symbolic-to-`Int`
  conversion at compile time;
- Scala equality, inequality, hashing and numeric conversion on statically
  typed frontend values in either operand order, using the inherited compiler
  plugin to reject reverse comparisons before elaboration;
- range guards and yields because the symbolic range has no collection
  operations beyond `foreach`; multiple-generator comprehensions lower to
  nested `foreach` calls and fail with the nested-generate diagnostic;
- module-item emission outside parameterized capture.

Opaque parameter identities are retained through arithmetic and checked at a
module-local boundary. Guarded indexed expressions also retain their loop
token through port connection and instance emission; neither same-named
parameter aliases nor raw/same-named generate-index references can bypass
frontend validation.

Capture collectors, name registries and active scope tokens are isolated per
thread. Loops require an explicit concrete or parameterized session, so a loop
dispatched from another thread is rejected rather than silently executing its
witness. A failed body closes its token, discards its partial loop and restores
the previous context before the exception propagates. Frontend errors include
a stable code, retained caller source location and suggested replacement.

## Executable vertical slice

`LaneArrayFixture` now authors its loop through the frontend instead of
constructing `GenerateFor` or `GenerateIndexRef` directly. CI exercises the
frontend tests on both Scala versions, generates the complete four-file
contract directory twice and requires `lane_array.v` to remain byte-identical
to the reviewed golden.

The existing strict gates still test default, minimum, awkward and single-
parameter overrides with Verilator, Icarus and Yosys. Existing
`AttributeEmitTests` and `ChecksTester` regressions remain mandatory.

## Deliberately deferred

- The public `MorphVerilog` entry point and shared inherited SpinalHDL phase
  plan for the concrete witness.
- `HdlBool`, `GenerateIf`, `GenerateCase` and nested generate regions.
- General loop starts, strides, comparisons or Scala collection semantics.
- Processes, registers, memories and broader library adapters.
- Generate-index-dependent child parameters, widths or expressions outside
  the canonical indexed part-select offset.
