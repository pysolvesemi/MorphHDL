# Increment 3: parameter expressions and local parameters

Increment 3 extends the ParamRTL vertical slice from direct public-parameter
widths to derived symbolic constants. The generated `DerivedWidth` module keeps
one public definition that downstream Verilog tools may specialize repeatedly.

## Implemented expression tranche

- Public and local integer-parameter references.
- Addition, subtraction, multiplication, division, modulo and unary negation.
- Explicit signed integer semantics and arbitrary-precision analysis values.
- Nonzero-divisor proof for every legal division and modulo value.
- Range propagation through every expression node before lowering to the
  portable signed 32-bit Verilog `integer` domain.
- Acyclic local-parameter dependency validation and deterministic topological
  emission. Names break ties only between dependency-independent declarations.
- Positive-width proof through local-parameter references.

The executable operator inventory is
`morphhdl/contracts/parameter-operators.tsv`. Its checker requires every listed
operator either to validate and emit with test evidence or to reject through a
stable diagnostic; silent specialization is not permitted.

## Executable vertical slice

`DerivedWidth` declares:

```verilog
localparam integer TOTAL_WIDTH = LANES * DATA_WIDTH;
localparam integer PADDED_WIDTH = TOTAL_WIDTH + 3;
```

Both ports use `PADDED_WIDTH`. `DATA_WIDTH` defaults to 8 and has the legal
domain 1 through 1024. `LANES` defaults to 4 and has the legal domain 1 through
64. Constraints are retained in ParamRTL even though strict Verilog-2001
cannot encode them on the public parameter declaration.

CI generates the module twice, compares both directories byte for byte and
then compares the artifact with the reviewed golden. The exact downloaded
artifact passes:

- strict IEEE 1364-2001 Verilator lint;
- one Icarus simulation containing all five legal configurations;
- fresh Yosys `read_verilog -noautowire`, hierarchy, consistency and full
  synthesis runs for each configuration;
- exact post-synthesis `din` and `dout` width checks.

| Configuration | `LANES` | `DATA_WIDTH` | `PADDED_WIDTH` |
|---|---:|---:|---:|
| Default | 4 | 8 | 35 |
| Minimum | 1 | 1 | 4 |
| Awkward mixed | 3 | 5 | 18 |
| `LANES` only | 3 | 8 | 27 |
| `DATA_WIDTH` only | 4 | 5 | 23 |

Parameter constraints remain compiler metadata; plain Verilog-2001 parameter
declarations cannot enforce them. External tools therefore exercise legal
values, while ParamRTL tests own invalid-domain and expression-analysis
diagnostics.

## Deliberately deferred

- Boolean expressions, comparisons and conditional selection.
- Semantic minimum, maximum and `clog2` operations.
- The public `HdlInt` and `MorphVerilog` frontend.
- Instances, parameter forwarding and generate regions.
- Connection to the shared inherited SpinalHDL phase plan.

`PhaseInferWidth` remains `partial` in the validation-parity inventory until
the complete v1 expression algebra and shared phase-plan integration exist.
