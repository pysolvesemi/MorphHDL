# Parameter-constant Vec selection

Use `ElabVec.select` when the selected element depends only on elaboration
parameters. It supports scalar `Bool`, `Bits`, `UInt` and `SInt` Vec elements
and preserves their native type, signedness and symbolic width.

```scala
import spinal.core._

// resetStages is an authenticated ElabInt with domain 2..8.
val stages = Vec.fill(resetStages)(Reg(Bool()) init False)
// Assign the register chain using the typed loop API.
val released = ElabVec.select(stages, resetStages - 1)
```

The compiler proves `0 <= index < depth` over the declaration's admitted domain.
A valid default alone is insufficient. First, last and interior expressions are
supported, as are independent index parameters whose full domain fits a literal
depth. The Vec must belong to the component authoring the selection. The API is
a read; subsequent assignments must not overwrite its retained read carrier.

Unpacked publication emits an array selection with the parameter expression or
an equivalent parameter-only localparam. Packed publication emits the corresponding
indexed packed slice. Neither form creates a hardware index, runtime bounds mux
or index-width conversion. Ordinary `vec(hardwareUInt)` indexing retains its
existing behavior. Compiler-generated selection aliases may remain; they do not
change the selector into a hardware input.

The API requires the MorphHDL runtime on the compiler classpath. Existing Scala
`vec(Int)` accesses and ordinary Scala loops retain their current semantics.
