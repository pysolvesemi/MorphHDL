# Packed field overrides and finite row copies (LWR-40)

Ordinary constant loops retain Scala elaboration when an enclosing packed Bits
carrier also has scalar field patches. This preserves the whole-vector default
and source-order priority of subsequent overrides. Inline `setName`,
`dontSimplifyIt`, `addAttribute` and `addTag` decorators do not change this
ownership decision. A patch in the final expression of a method/block is included.
The compiler does not promise a retained generate loop for this topology.

Explicit typed loops still require their existing default/coverage/owner proofs.
This change does not admit partial or competing generated drivers by weakening
`TypedFinitePackedDefaults`.

Finite reads of a statically selected nested Vec row now project through the
published enclosing Vec. A row is not a separate declaration: its identity and
every native leaf must match one exact published root. Publication verifies the
field path, type, symbolic width, dimensions and coordinates before substituting
the finite selector. Foreign storage and missing publication identities remain
errors. Signed reads keep the existing signed-cast semantics.

An exhaustive nested producer can feed these sibling row-copy loops. The proof
follows the issued index tokens to their loops even when both selections were
captured in the inner body. Every dimension must have complete coverage; the
proven loop chain must match the lexical path, and its enclosing scope must
dominate each reader. Ordinary partial/conditional writers do not acquire this
authority. Existing assignment and missing-driver checks remain enforced.

For example, with a retained `terms` array containing rows of UInt values:

```scala
val ports: ElabInt = ...
val live = Vec(UInt(36 bits), ports)
for (port <- 0 until ports) {
  live(port) := terms(counter)(port) // counter is a Scala row index
}
```

This uses the existing finite-index domain checks, including a proven subrange
when the source row has more elements than `ports`. It does not need a packed
`terms(counter).asBits.resize(...)` adapter. Packed and unpacked publication keep
the same least-significant-first ordering.

Validation and publication evidence are recorded separately; no CI or full
qualification is part of this increment.

## Focused results (19 October 2026)

Scala 2.12.18 and 2.13.12 builds pass. Each version passes 51 native/library
and 199 frontend tests (500 executions total). Coverage includes packed/unpacked
and named-field layouts, nested exhaustive producers, widths 1/5/36, count
overrides 1/8/16, signed copies, default/overlap priority, X/Z data, and rejection
of foreign storage, unsafe defaults and a selector legal domain wider than its
source row. Existing nested-loop, ordinary-loop and balanced-tree tests pass.

Isolated copies of the real `DisplayControllerStatusCounterInterrupt` and
`DisplayControllerStableStatus` compile and generate with direct copies for the
error vector and seven counter rows. Both artifacts reproduce byte-for-byte.
Neither status artifact contains an anonymous `_zz_typed_vec_*` row-packing
carrier; readers reference the emitted array directly. The unchanged application
unit bench passes 10,823 checks; its profile bench passes 3,935 checks at each of
1, 3, 8 and 16 event ports. Strict Verilog-2001 compilation, Verilator lint and
Yosys hierarchy/driver/no-latch checks pass for both leaf and real parent. The
parent uses the original Dan FIFO/value RTL, not substitute models.

The [receipt](packed-fields-and-row-copies.json) binds exact commands, tested
source/plugin hashes, artifacts and tool logs. Historical failures are archived
separately and are not passing evidence. Tests ran from baseline
`a8ef138179648af1cb365033bae1aa11bb9922a9` plus the recorded source changes;
the publication receipt binds the same source bytes to the final commit/tree.
Evidence is retained under
`/home/kartik/.local/state/morphhdl/lightweight-scala-rtl/lwr40-20261019/`.
Questa is unavailable. No CI or full qualification was run.
