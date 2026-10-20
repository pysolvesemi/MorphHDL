# Ordered masked priority selection (CA-056)

`ElabProcess.prioritySelect` retains one bounded combinational procedural loop.
It selects coherent fields using a masked predicate word, with zero defaults:

```scala
val fields = ElabProcess.prioritySelect(
  count, errors.asBits, selectedMask,
  Seq(locations -> wordWidth, contexts -> wordWidth),
  descending = true)
selectedLocation := fields(0)
selectedContext := fields(1) & contextMask
```

Each source contains exactly `count` packed words, least-significant word first.
Predicate width comes from the mask; each result field declares its own positive
word width. The source must belong to the current component. Exact retained
parameter-domain geometry establishes widths, including independent COUNT/WIDTH
parameters. This API accepts authenticated Vec packed views without a Scala
witness conversion. It does not infer an array or loop by rewriting HDL text.

Ascending traversal selects the highest matching index. Descending traversal
selects the lowest. Every selected field comes from the same iteration. A match
uses Verilog `if ((predicateWord & mask) != 0)` four-state semantics; an unknown
condition does not assign. No match yields zero. Results are combinational, with
no registers, clocks or added latency.

The existing `ElabProcess.outputsLoop` also accepts `descending = true` for
custom scoped predicates and coherent writes. Its owning integer loop index
counts from `COUNT - 1` down through zero; it is signed and terminates at -1.
Nested loops retain their existing ascending order. Ordinary concrete Scala
loops still intentionally unroll. This change does not infer arbitrary Scala
side effects, callbacks or array recurrences as procedural reductions.

The focused regression compares parameter overrides COUNT 1/2/3/5/8 and WIDTH
1/5/16, both traversal orders, packed/unpacked sources, nested conditions,
multiple outputs, no/single/multiple matches, randomized masks/data and X/Z
predicates against an independent ordered reference. Existing process regressions
cover escaped handles, illegal accesses and ownership. Local Icarus execution is
recorded separately from unavailable Questa validation and full qualification.
