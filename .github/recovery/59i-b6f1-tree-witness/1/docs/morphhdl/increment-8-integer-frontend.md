# Increment 8: integer frontend closure

Increment 8 closes the existing integer-expression and hierarchy fixtures
through the guarded Scala frontend and the public `MorphVerilog` entry point.
It does not add Boolean or conditional structure.

## Dual-valued integer algebra

`HdlInt` now retains both a `BigInt` concrete witness and the corresponding
ParamRTL expression for:

- literals and public or local integer-parameter references;
- addition, subtraction, multiplication, division and modulo;
- unary negation; and
- the existing scoped `GenIndex * HdlInt` offset expression.

Operations preserve their source expression tree rather than folding the
default witness into public RTL. Division and modulo reject a zero concrete
divisor with a stable frontend diagnostic. A nonzero default is not accepted
as a proof for the whole legal domain: if the divisor constraints still admit
zero, ParamRTL reports `PRTL-DIVISOR-MAY-BE-ZERO` before emission.

The executable operator inventory now records separate evidence for frontend
capture, ParamRTL validation and strict Verilog-2001 emission. Signed division
truncates toward zero and modulo keeps the dividend sign, matching the existing
ParamRTL and Verilog contract.

## Guarded local parameters

The package-scoped lowering facade provides `localParam(name, value)`, which
returns an identity-bearing `HdlInt` reference, and
`integerLocalParameter(value)`, which explicitly declares that exact handle at
the final `moduleDef` boundary. The final local-parameter argument is defaulted
so existing frontend sources remain source-compatible.

Opaque public- and local-parameter tokens travel through arithmetic, packed
widths, child bindings, indexed selections and captured module items. A module
rejects an undeclared token, a separately constructed same-named token, a
duplicate declaration, a token already owned by another module, or a local
definition whose public/local dependencies are not declared by that module.
Generate-index-dependent local definitions remain unsupported.
Each symbolic factory evaluation creates fresh local handles. Once a handle is
claimed by one module-definition boundary it cannot be replayed into another,
including a second construction with the same logical module name.

ParamRTL still owns whole-domain expression analysis, cycle detection,
positive-width proof, portable signed 32-bit target bounds and dependency-first
topological emission. Reversing declaration construction order therefore does
not alter output order.

## Four-fixture public path

One Morph fixture source now supplies both re-entrant factories for each
reviewed contract:

| Artifact | Frontend feature exercised | Default witness |
|---|---|---:|
| `parameterized_wire.v` | public integer width | 8 bits |
| `derived_width.v` | multiplication, addition and dependent locals | 35 bits |
| `parameter_forwarding.v` | derived local passed by a named child binding | 32 bits |
| `lane_array.v` | symbolic generate-for and indexed part selections | 4 lanes × 8 bits |

CI generates all four files directly through `MorphVerilog` twice, with the
second source reversing independent construction order. It requires the two
directories and the reviewed goldens to be byte-identical. The exact generated
directory then passes strict Verilator, default and non-default Icarus
simulation, and Yosys synthesis and structural checks. No backend-built fixture
is copied over a Morph output.

The inherited concrete witness still runs the shared phase plan. The symbolic
leg independently performs ParamRTL validation, Verilog-2001 capability checks
and binding-aware default-shape agreement. `PhaseInferWidth` remains partial:
this increment closes the current integer frontend, not the remaining v1
Boolean and conditional expression families.

## Deliberately deferred

- `HdlBool`, comparisons, conditional selection and `GenerateIf`/`GenerateCase`.
- `min`, `max`, `clog2` and nested generate regions.
- Processes, registers, memories, aggregates and broader library adapters.
- A one-constructor lowering path that derives both Morph legs automatically.
