# Increment 5: bounded generate-for and indexed slices

Increment 5 turns `lane_array.v` from a reviewed future-output sketch into an
artifact generated from ParamRTL. The emitted source retains one readable
definition per logical module; it never unrolls lanes into configuration-
specialized module definitions.

## Implemented generate tranche

- A target-neutral `GenerateFor` module item with a symbolic positive count.
- A zero-based, unit-stride `GenerateIndexRef` scoped to the loop body.
- Parameterized indexed part-selects represented independently of Verilog
  spelling and emitted as `base[offset +: width]` in strict Verilog-2001.
- Named child instances inside a generate body with symbolic parameter and
  port bindings.
- Recursive dependency discovery through generate bodies, preserving
  dependency-first deterministic module order.
- Fail-closed checks for invalid generate names, unresolved or escaped indices,
  nested generate regions, nonpositive counts, mismatched slice widths and
  unsupported driver shapes.
- A canonical output-partition proof: the supported loop must cover each packed
  output bit exactly once with `index * sliceWidth` offsets and no competing
  whole-output driver.

The canonical loop deliberately fixes the start at zero and step at one. The
IR carries a count rather than general Verilog loop syntax, keeping the first
semantic contract small and allowing exact driver and type proofs.
In this increment, the generate index is accepted only in indexed part-select
offsets. Generate-index-dependent child parameter bindings and other body
expressions remain unsupported and fail closed.

## Executable vertical slice

The generated `LaneArray` keeps `LANES` and `DATA_WIDTH` symbolic:

```verilog
genvar lane;
generate
  for (lane = 0; lane < LANES; lane = lane + 1) begin : g_lane
    PixelLane #(
      .DATA_WIDTH(DATA_WIDTH)
    ) lane_inst (
      .data_in(data_in[lane * DATA_WIDTH +: DATA_WIDTH]),
      .data_out(data_out[lane * DATA_WIDTH +: DATA_WIDTH])
    );
  end
endgenerate
```

CI generates the complete four-file artifact directory twice, requires byte
identity with the reviewed goldens and validates the actual downloaded
`lane_array.v`. Five configurations exercise default, minimum, awkward and
one-parameter-only overrides:

| Configuration | `LANES` | `DATA_WIDTH` | Flat width | Generated cells |
|---|---:|---:|---:|---:|
| Default | 4 | 8 | 32 | 4 |
| Minimum | 1 | 1 | 1 | 1 |
| Awkward mixed | 3 | 5 | 15 | 3 |
| `LANES` only | 3 | 8 | 24 | 3 |
| `DATA_WIDTH` only | 4 | 5 | 20 | 4 |

For each configuration, strict Verilator lint uses IEEE 1364-2001 mode,
Icarus simulates all five instances together, and Yosys performs hierarchy and
consistency checks plus fresh synthesis. A pre-synthesis JSON checker proves
the exact generated cell count and matches every child input/output connection
to one corresponding packed top-port slice without relying on Yosys `$paramod`
or generated-instance names.

## Validation parity

The scoped hierarchy and indexed-width proofs extend the partial
`PhaseCheckHierarchy` and `PhaseInferWidth` adaptations. Exact canonical slice
partitioning advances `PhaseCheck_noLatchNoOverride` from `planned` to
`partial`. All three remain incomplete until the frontend runs the shared
inherited phase plan on its concrete witness; future conditional generate and
process semantics also require predicate-aware coverage.

## Deliberately deferred

- `GenerateIf`, `GenerateCase` and nested generate regions.
- General loop start, comparison and stride expressions.
- Boolean, comparison, conditional, minimum, maximum and `clog2` expressions.
- The public `HdlInt`, `HdlBool` and `MorphVerilog` frontend.
- Connection to the shared inherited SpinalHDL phase plan.
