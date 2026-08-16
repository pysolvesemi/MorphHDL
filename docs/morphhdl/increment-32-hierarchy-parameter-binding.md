# Increment 32 — hierarchy and parameter binding

Increment 32 extends the ordinary single-source SpinalHDL path across a
bounded `Component` hierarchy. The normal SpinalHDL emitter still decides
component construction, canonical definition reuse, instance names, port
ordering, connection syntax and concrete witness checks. MorphHDL analyzes
those same native assignments and adds only the symbolic information that
concrete Verilog emission cannot retain.

## Accepted contract

- A child is an ordinary `Component`, not a `BlackBox`.
- Every child public width parameter is attached directly to at least one
  packed `Bits`, `UInt` or `SInt` leaf port.
- Parent-to-child and child-to-parent boundaries are full direct packed-leaf
  connections. Slices, indexing, casts and expression-wrapped boundaries are
  deferred to Increment 33.
- A parent binding is either one directly tagged parent width parameter or a
  concrete parent port width.
- The parent binding's complete domain must fit inside the child parameter's
  complete domain, and both must share the concrete elaboration witness.
- Concrete-equivalent child instances may share one logical definition only
  when their parameter names, defaults and domains are identical.

## Emission

A reusable child parameter such as `LEAF_WIDTH` is inferred independently
for each instance from its ordinary connections:

```verilog
NativeHierarchyLeaf #(
  .LEAF_WIDTH(LEFT_WIDTH)
) left (
  .din  (leftIn[LEFT_WIDTH-1:0]   ),
  .dout (left_dout[LEFT_WIDTH-1:0])
);
```

The parent declares every referenced parent parameter, emitted proxy wires
retain the inferred symbolic width, and the canonical child definition is
emitted exactly once. A parent with no symbolic width of its own may bind a
child parameter to a literal without gaining an unnecessary parameter list.

## Diagnostics

Stable failures distinguish unresolved bindings, conflicting connection
constraints, concrete-witness mismatches, out-of-domain bindings, canonical
schema conflicts, unsupported boundary expressions, missing emitted
instances/connections, inout ports and BlackBox children.

Existing inherited SpinalHDL hierarchy, driver and width checks continue to
run against the concrete witness before the symbolic rewrite. No
component-specific ParamRTL adapter or specialized module copy is created.
