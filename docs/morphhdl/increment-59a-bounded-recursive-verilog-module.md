# Increment 59a — Bounded recursive Verilog module generation and proof

## Status

Implementation target for the `parameterized-verilog` branch. The roadmap
checkbox remains open until the exact final branch head passes every gate in the
Increment 59a workflow.

## Goal

Validate one ordinary typed MorphHDL/SpinalHDL component which emits one
strict-Verilog-2001 module definition that recursively instantiates that same
module with a smaller elaboration parameter.

The representative component computes an unsigned modular power:

- `N == 0`: `y = 1`
- `N > 0`: instantiate the same module with `.N(N - 1)` and compute
  `y = x * recursive_y`
- input and output width: 8 bits
- arithmetic semantics: multiplication is truncated to 8 bits, therefore the
  result is `x^N mod 2^8`

The qualified domain is `0 <= N <= 8`. It covers the base case, odd exponents,
even exponents, non-power-of-two exponents and the maximum admitted depth.
The explicit base case defines `0^0` as 1 for this hardware contract.
This is an 8-bit modular arithmetic example, not an arbitrary-precision power
implementation or a variable-cycle runtime exponentiation engine.

## Synthesizability boundary

This feature is elaboration-time structural recursion. It is not a runtime
recursive operation and it does not create a combinational feedback cycle.

A recursive self-reference is accepted only when all of the following are
proved before publication:

1. The recursive instance is a direct child BlackBox whose exact definition
   name equals the owning emitted module name.
2. There is exactly one direct recursive self-reference in the owner.
3. The self-reference has no inline implementation and no external RTL path. It
   is only an identity-bearing reference to the owning emitted definition.
4. At least one integer generic retains exact typed symbolic metadata from the
   Increment 59 BlackBox binding path.
5. Exactly one generic is the recursion metric.
6. The metric formal name equals its authoritative declaration parameter name.
7. The parameter domain starts at zero and has at least one positive value.
8. The exact surviving child is captured only under the positive values of
   its metric root. Its final structural-owner evaluation covers all those
   values, not just the source default or a provisional expression table.
9. For every positive root value, the recursive actual is non-negative and
   strictly smaller than the root value.
10. Any other symbolic integer generic preserves its root value exactly.
11. Every owning module parameter is bound explicitly, exactly once. Untyped,
    implicit and Boolean recursive bindings are outside this increment.
12. The reference has exactly the owner's port names, directions, types and
    widths. Port geometry must not depend on the decreasing metric.

These conditions prove that every legal specialization reaches the `N == 0`
base branch after a finite number of elaboration steps.

The implementation deliberately does not claim support for:

- unbounded recursion;
- non-decreasing recursive actuals;
- negative recursion domains;
- runtime recursion;
- cyclic hardware;
- mutual recursion;
- multiple self-references per module;
- multiple candidate recursion metrics;
- same-name BlackBoxes that carry a separate implementation;
- synthesis tools outside the workflow's explicit tool matrix.

## Independent synthesis preflight

Before extending the capture boundary, workflow run `33945476208` on September
5, 2026 qualified an independently authored minimal recursive Verilog module in
`ghcr.io/spinalhdl/docker:v1.2.0`. Yosys 0.41 completed full synthesis and SAT
equivalence for every exponent from 0 through 8. Icarus and Verilator accepted
the same strict Verilog-2001 source. Each synthesized top contained only a
finite mapped primitive netlist and no recursive or unresolved cells.

This establishes tool feasibility, not MorphHDL feature completion. The
Increment 59a canonical workflow must separately apply the same checks to the
actual Scala-generated candidate and independently generated SpinalVerilog
oracles on both Scala 2.12.18 and 2.13.12.

## Source architecture

### Ordinary component source

`BoundedRecursivePowerFixture.ParameterizedPower` is an ordinary SpinalHDL
`Component`. It uses:

- one `HdlInt` parameter named `N`;
- the typed symbolic equality `N == 0` through `hdlEq`;
- the existing structural `generateIf(...).otherwise` path;
- ordinary `UInt` ports and multiplication;
- one same-name BlackBox in the recursive branch, with typed
  `addGeneric("N", exponent - 1)`.

The BlackBox does not contain Verilog, does not add an RTL file and does not
implement the arithmetic. Its only purpose is to express a self-reference to
the exact module being emitted. The arithmetic remains in the ordinary
component source.

### Exact validation

`BoundedRecursiveModuleValidation` runs in the MorphHDL-owned publication path
after the elaborated component graph has been captured and before Verilog is
rewritten.

The validator consumes the exact BlackBox object and exact
`ParameterizedBlackBoxGenericRegistry` records. It authenticates the retained
`ElaborationIntegerExpression` using the existing exact-domain authority. It
does not recover a parameter from an integer witness, source filename,
component class name, emitted instance name or textual expression pattern.

Both structural capture and final publication retain the child by JVM identity.
Final-owner projection is checked before the expression is authenticated inside
that exact admitted domain. This is necessary because `N - 1` is legal for the
step branch but negative at the excluded `N = 0` point. Moving a projected
expression to an unconditional child does not borrow the earlier branch's
proof. The generic hierarchy binder applies the same ownership rule.

Validated self-references enter only the exact instance-relocation map. They
never enter the emitted-module inventory, and the backend does not synthesize
a second BlackBox implementation.

Native output processes may contain both base-case and step assignments before
relocation. The serializer can retain a direct whole-target unsigned literal
only when its native target type, literal type and fixed width match, and the
complete emitted occurrence is unique at that same explicit width. Selected,
resized, signed, poisoned, unsized and coincident wider literals cannot provide
this ownership evidence. Existing native assignment capacities and structural
exclusivity checks remain mandatory.

A constant-only `always @(*)` block has no triggering event in Verilog-2001.
When a complete shared-process family consists of one independent whole
blocking assignment per output and branch, and every exact native driver is
accounted for, the serializer emits continuous assignments and changes only
the corresponding native output declarations to `wire`. It does not convert
clocked processes, conditional runtime trees, partial assignments, repeated
writers or expressions that depend on another target in the same family.

For the accepted power component, the exact active recursive-branch table is:

| Root `N` | Recursive actual |
|---:|---:|
| 1 | 0 |
| 2 | 1 |
| 3 | 2 |
| 4 | 3 |
| 5 | 4 |
| 6 | 5 |
| 7 | 6 |
| 8 | 7 |

Every result is non-negative and smaller than its root. The excluded root value
zero is handled only by the base generate branch.

## Generated Verilog contract

The published artifact must contain:

- exactly one `BoundedRecursivePower` module definition;
- one public integer parameter `N`, defaulting to 5;
- one generated `g_base` branch;
- one generated `g_step` branch;
- exactly one self-instance in the source definition;
- an exact named parameter binding equivalent to `.N(N - 1)`;
- no separately generated recursive implementation module;
- deterministic byte-identical output across repeated emission.

A legal specialization expands conceptually as:

```text
N=3
  x * Power(N=2)
        x * Power(N=1)
              x * Power(N=0)
                    1
```

The resulting hardware is a finite multiplication chain selected entirely at
elaboration time.

## Independent oracle and proof matrix

`ConcretePower` is an independent, parameter-free SpinalHDL implementation. It
uses a flat Scala loop to construct a multiplication chain and does not use the
recursive self-reference, the parameterized generate branch or the recursion
validator.

The workflow generates recursive and flat modules for exponents:

```text
0, 1, 2, 3, 4, 5, 6, 7, 8
```

The tool-backed proof performs:

1. strict Verilog-2001 compilation and exhaustive simulation with Icarus
   Verilog for every 8-bit input and all nine exponents;
2. Verilator strict Verilog-2001 lint of a top containing all specializations;
3. Yosys deferred parameter elaboration, `hierarchy -simcheck`, full
   `synth -flatten` and `check -assert`; the serialized JSON must contain one
   flattened module and only mapped primitive cells, with no unresolved
   BlackBoxes or recursive instances;
4. Yosys SAT equivalence between each recursive specialization and its
   independent flat oracle;
5. a live structural mutation which changes the recursive actual from `N - 1`
   to zero; the altered module must still synthesize, but its `N = 5` result
   must produce a genuine SAT counterexample with a retained trace; parse
   failure, missing modules, timeout and tool failure are not mutation success;
6. focused unit tests, deterministic generation and rejection diagnostics on
   both supported Scala versions;
7. inherited source-preservation, retirement, report ABI and concrete
   compatibility, SBT and Mill gates, typed BlackBox lint/simulation/formal,
   native-library and primitive equivalence, all 64 StreamFifoCC width/depth
   configurations per Scala lane, determinism and read-only pass-adapter tests.

Increment 59a is complete only when this exact final-head matrix is green.

## Rejection contracts

The focused tests require deterministic diagnostics for at least:

- a self-reference whose metric is unchanged (`N` instead of `N - 1`);
- a recursion parameter whose admitted domain includes a negative value;
- a self-reference with no typed symbolic metric;
- multiple direct self-references;
- more than one candidate decreasing metric;
- a self-reference that attempts to carry inline or external RTL.

The executable safety fixtures additionally cover an increasing metric,
unconditional recursion, a wrong base branch, a wrong formal name, extra
untyped generics, mismatched port names/widths and unrelated structural
BlackBoxes. Positive fixtures change module, parameter and port names and use
addition instead of multiplication, ensuring that the production path is not
specific to the power example. Source defaults 0, 1 and 8 must retain both
generate branches.

Multiple-metric, non-local reference and exact-identity mutation cases remain
subject to the production fail-closed checks; executable coverage must be
reported separately from static source-contract coverage.

## Increment 59b feasibility

Parameterized `reduceBalancedTree` is feasible as a separate increment, but it
is not implemented here. The current authoritative helper recursively decides
its topology from a concrete Scala `Seq.length`. A symbolic Vec element count
therefore needs a typed generated-stage representation and exact odd-tail and
level-bridge semantics; merely passing the current concrete witness would lose
the parameterized topology. Increment 59b remains unchecked and depends on the
merged completion of Increment 59a.
