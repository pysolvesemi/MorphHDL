# Symbolic word packing (CA-055)

A retained finite index accepts a parameter-constant positive stride and a typed
word width. Both widths and counts retain their declaration identities:

```scala
val source = in Bits((count * width) bits)
val result = out Bits((count * width) bits)
ElabFiniteRange.foreach(count, "words") { i =>
  i.packed(result, width, width bits) := i.packed(source, width, width bits)
}
```

The compiler checks the last slice against the source width over the complete
legal parameter domain. Writes additionally require disjoint slices. A full
contiguous write participates in native definite-assignment checking; it does
not exempt the target from latch, overlap, ownership or escaped-index checks.
The existing `Int`/`BitCount` overload remains supported.

For unpacked native Vec publication, aligned whole-word pack/unpack operations
use one iteration per element and an indexed part select. Partial or unaligned
operations retain the existing bit-level implementation. Signed elements retain
their declared type; a packed `Bits` transport carries the unchanged bit pattern.

After native packing lineage and width validation, an exclusive internal
carrier/result pair may be replaced by direct connections to its authored
packed destination. The compiler retains resized, explicitly named, attributed, documented or
shared intermediates where this proof is unavailable. It never removes an alias
based on its spelling. Unpack RHS capture remains mandatory unless an exact
validated packed-assignment source supplies the complete width.

Direct packing can join an existing retained loop when its exact selector token,
Vec identity, full count and captured declaration owner match. Otherwise it uses
a separate whole-word packing loop. Nested array axes keep their own complete
word traversal rather than joining an outer-only loop. This does not merge processes or change
arithmetic order, pipeline placement, reset, enable or soft-reset behavior.

For example, a retained pair-combine loop can contain:

```verilog
for (i = 0; i < ((COUNT + 1) / 2); i = i + 1) begin : combine
  assign nextTerms[i * DATA_WIDTH +: DATA_WIDTH] = combined[i];
  // Existing guarded pair computation remains here.
end
```

`SymbolicPackedWordsTests`, `WidthCompositionTests`, `UnpackedDirectReadTests`
and `CorrelatedVecPublicationTests` cover this path and its existing fixed-width
controls. Qualification results and exact source identities belong in the
increment evidence; these examples alone are not qualification evidence.

Local repair validation on 14 October 2026 passes 55 focused tests on each of
Scala 2.12.18 and 2.13.12, with Icarus simulation, Verilator lint and Yosys
synthesis checks. The unchanged application width harness passes 190,400
comparisons for each of native and cleaned RTL across eight operation/pipeline
combinations; all eight native outputs repeat byte-for-byte and omit the packing
aliases and extra packing loop. Application sources, pins and cleanup are unchanged.
Questa is unavailable locally. These results are local repair evidence; targeted
and full exact-candidate CI, including the complete publication golden corpus,
remain required before qualification closure.
