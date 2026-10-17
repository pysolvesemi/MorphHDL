# Vec word publication (CA-029–CA-031)

With `preserveConstantVecs = true`, a constant nested `Vec.tabulate` retains
its unpacked dimensions and aligned whole-word accesses. For example, a
three-by-five UInt(36 bits) array uses `terms[1][1]`, rather than 36 bit
selections. Literal coordinates are folded. Symbolic geometry is factored only
by its exact retained scalar width; no parameter default supplies a coordinate
or alignment proof. Unaligned accesses retain their existing bit-level path.
The outer coordinate is never wrapped to conceal an invalid address.

A symbolic-width Vec element can directly feed an explicitly parameterized
child input. The compiler proves the parent carrier width against the actual
binding before omitting a redundant second slice. Unpacked output uses
`.delta(deltas[0])`; packed output uses one indexed packed slice. Helpers are
lowered after array geometry publication so both sides use the same arithmetic
representation. Parent parameter/localparam ownership is unchanged.

Already-consumed owner-local nodes may be grouped without copying them:

```scala
val nodes = (0 until 3).map(i => (x + U(i, 36 bits)).dontSimplifyIt())
val sum = nodes(0) + nodes(1) + nodes(2)
val grouped = Vec.tabulate(3)(nodes(_))
y := sum + grouped(2) + nodes(0)
```

Tabulation records the exact element and flattened scalar identities. Both
pre-existing and subsequent consumers use those same nodes; ordinary pruning
remains enabled. The residual-carrier guard still rejects unrecorded raw access.
This construction requires distinct nodes owned by the component. For repeated
or foreign nodes, create fresh parent-owned signals and explicitly assign them
before grouping. Construction metadata is not transferred to arbitrary clones.

`VecWordPublicationTests` covers both layouts, deterministic emission, alias
fanout with/without simplification retention, rejection of repeated/foreign
identities, and symbolic child widths (inline and named parent localparam) for every PORTS override 1..16. It runs
Icarus Verilog-2001 simulation (zero, all-one, one-hot, random, X/Z), Verilator
lint, and Yosys hierarchy/latch/synthesis checks against independent references.
`UnpackedDirectReadTests` adds five-bit and 36-bit words, constant sum offsets,
symbolic inner dimensions, unaligned windows and invalid outer coordinates.
The existing Vec, hierarchy, signedness and identity-negative suites remain
applicable. These fixtures do not replace application qualification.

Yosys 0.41 can assert internally when `hierarchy -chparam` derives this unpacked
child hierarchy. The fixture instead applies `chparam`, then checks hierarchy
and synthesizes its selected top (whose generated name may change). All override,
no-latch and `check -assert` gates remain required. Tool logs retain exact commands.
Questa is not available on the local compiler test PATH; no Questa pass is claimed.

Application Scala, delivered RTL, cleanup scripts and compiler pins are unchanged.
Existing `reduceBalancedTree` behavior and the explicit recursive reduction API
are unchanged. Remote targeted qualification precedes full qualification; local
success alone does not close the issues' qualification status.
