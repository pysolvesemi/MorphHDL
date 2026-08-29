# Increment 53e — Generic native parameterization contract

Increment 53e uses `spinal.lib.StreamFifoCC` as a concrete regression witness,
but none of its compiler, graph-analysis, hierarchy, memory, resize, or width
proof rules may recognize that component by source file, Scala class name,
instance name, signal name, or emitted Verilog text.

## Native-source boundary

`core/src/main`, `lib/src/main`, and `idslplugin/src/main` remain byte-identical
to the selected upstream SpinalHDL base. MorphHDL may instrument typed compiler
trees externally, retain provenance in MorphHDL-owned registries, and rewrite
the already emitted Verilog only after graph-backed validation. A MorphHDL FIFO,
subclass, copied CDC algorithm, or generated-name heuristic is not permitted.

## Generic constructor selection

The compiler selector is structural. It requires an upstream SpinalHDL
production source, `spinal.core.Component` ancestry, and a supported Scala
`Int` constructor shape. The active formal boundary supplies the selected
argument name and the first exact compiler reference claims it. Equal numeric
witnesses are never provenance keys, and nested constructors cannot steal an
already claimed boundary.

This selection mechanism is reusable by thin frontend adapters for other native
SpinalHDL Components. A frontend adapter may name the native library component
that it constructs, but it must not reproduce that component's implementation.

## Generic symbolic-width proof

When two assignment widths have different symbolic syntax, MorphHDL accepts
them only when all of the following hold:

1. each symbolic leaf has exact compiler-retained native-expression provenance;
2. each side resolves to one bounded formal root;
3. both roots have identical parameter schemas and domains;
4. both expressions have the same concrete witness;
5. exhaustive evaluation over the complete admitted domain produces the same
   positive width at every value; and
6. the domain stays within the reviewed bounded-evaluation limit.

No algebraic expression spelling, component identity, or emitted name is used.
Unsupported, ambiguous, unbounded, multi-root, or unevaluable expressions fail
closed.

## StreamFifoCC witness

The Increment 53e witness must construct the exact native
`spinal.lib.StreamFifoCC`, retain its native Gray-pointer CDC topology,
`BufferCC` synchronizers, dual clock domains, RAM, reset options, and occupancy
interfaces, and validate legal power-of-two depths 4, 8, and 16. This witness is
a regression consumer of the generic mechanisms above, not a special backend
mode.
