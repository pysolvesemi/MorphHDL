# Lightweight Scala RTL migration

Use `import spinal.core._` and both compiler plugins. The public parameter
carriers are `ElabInt` and `ElabBool`; there is no implicit conversion to a host
integer or Boolean. Keep explicit parameter type annotations at public boundaries.

| Advanced source | Ordinary source |
| --- | --- |
| `ElabFormalParameter(actual, "COUNT", 1, 64)` | `RtlParam.int("COUNT", actual, 1, 64)` |
| Boolean formal encoded manually as 0/1 | `RtlParam.bool("ENABLED", actual)` |
| `ElabControl.selectSymbolic(predicate, ...)(yes)(no)` | `if (predicate) { yes } else { no }` |
| `ElabFiniteRange.foreach(count, ...)(body)` | `for (i <- 0 until count) { body }` |
| `i(lanes)` | `lanes(i)` |
| `i.affine(lanes, 2, 1)` under a matching guard | `lanes(2*i+1)` under `if (2*i+1 < count)` |
| `i.packed(words, width, width bits)` | `words(i*width, width bits)` |
| Explicit positional recursive port/formal factory | `@rtlRecursive` and direct guarded `new SameComponent(...)` |
| Parent connection helpers | `child.io.input := value`; `result := child.io.output` |

`require(count <= capacity)` with symbolic operands retains an elaboration-domain
requirement. It is not a witness-only Scala assertion. Declarations still need
finite integer bounds. Use native `when` for runtime hardware `Bool` conditions.
Ordinary concrete Scala loops continue to unroll; an explicitly typed local such as `val lanes: ElabInt = ElabInt.literal(4)`
expresses retained-loop intent with `for (i <- 0 until lanes)`. Inline factory
calls are not inferred by the pre-typer staging pass; bind them first. Unsupported reverse/non-unit traversal
rejects instead of silently changing behavior.

A typical shift chain keeps its reset and attributes:

```scala
val depth: ElabInt = RtlParam.int("DEPTH", actualDepth, 2, 8)
val stages = Vec.fill(depth)((Reg(Bool()) init(False)).addAttribute("ASYNC_REG", "TRUE"))
stages(0) := input
for (i <- 0 until (depth - 1)) stages(i + 1) := stages(i)
output := stages(depth - 1)
```

For reductions, import `spinal.lib.BalancedTree` and retain the existing call shape:

```scala
val sum = BalancedTree.reduceBalancedTree(
  terms, moduleName = "StatusCounterDeltaSum", pipelineMask = null
)(_ + _)
```

The library owns adjacent-pair recursion, odd-tail forwarding and bridge placement.
Payload widths must match the supported typed operation contract. A null mask
omits built-in pipeline selection; explicit zero retains a parameterized mask.
Use `define`/`defineWidth` to share an operation among multiple roots. Existing
native `Vec.reduceBalancedTree` behavior is unchanged. See `balanced-tree.md`
for the supported callback, width and pipeline contracts.

Verilog-2001 uses packed aggregate ports and least-significant-lane-first ordering.
Internal arrays may remain unpacked. Direct wiring still checks substituted child
geometry, direction and ownership; it is not permission to reference foreign
internals. Naming/keep requests and explicit clock domains remain meaningful.

## Local completion policy for this work

The user waived remote CI and full qualification on 15 October 2026, requiring
focused tests/checks instead, and authorized application migration afterward.
The user also required merging into `work/remaining-parameterized-increments`
and keeping the application compiler on that branch. Historical qualification
records remain unchanged. Local success must not be described as remote CI,
all-profile application qualification, or an unavailable simulator result.

Migration sequence: retain the baseline, compile the new sources, generate twice,
check native RTL before cleanup, simulate affected behavior and lint/synthesize
representative profiles. Remove an obsolete helper only after its callers use
validated replacements. Record exact compiler/source identities and commands.
Rollback means restoring the application source and compiler pin together; do not
combine old generated RTL or test receipts with the changed source.
