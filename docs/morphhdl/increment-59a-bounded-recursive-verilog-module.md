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
even exponents, a non-power-of-two exponent and the maximum admitted depth.

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
8. Exact-domain evidence covers every positive value in the parameter domain.
9. For every positive root value, the recursive actual is non-negative and
   strictly smaller than the root value.
10. Any other symbolic integer generic preserves its root value exactly.

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
0, 1, 2, 3, 5, 8
```

The tool-backed proof performs:

1. strict Verilog-2001 compilation and exhaustive simulation with Icarus
   Verilog for every 8-bit input and all six exponents;
2. Verilator strict Verilog-2001 lint of a top containing all specializations;
3. Yosys hierarchy elaboration, flattening, checking and synthesis of the
   recursively instantiated module;
4. Yosys SAT equivalence between each recursive specialization and its
   independent flat oracle;
5. a live mutation control which flips one result bit and must produce a SAT
   counterexample instead of a false proof success;
6. focused unit tests, deterministic generation and rejection diagnostics on
   both supported Scala versions;
7. inherited source-preservation, retirement, compatibility, SBT and Mill
   gates.

Increment 59a is complete only when this exact final-head matrix is green.

## Rejection contracts

The focused tests require deterministic diagnostics for at least:

- a self-reference whose metric is unchanged (`N` instead of `N - 1`);
- a recursion parameter whose admitted domain includes a negative value;
- a self-reference with no typed symbolic metric;
- multiple direct self-references;
- more than one candidate decreasing metric;
- a self-reference that attempts to carry inline or external RTL.

The current executable negative fixtures cover the unchanged metric and the
negative domain. The static source contract seals the remaining fail-closed
rules in the production validator.

## Increment 59b feasibility

Parameterized `reduceBalancedTree` is feasible as a separate increment, but it
is not implemented here. The current authoritative helper recursively decides
its topology from a concrete Scala `Seq.length`. A symbolic Vec element count
therefore needs a typed generated-stage representation and exact odd-tail and
level-bridge semantics; merely passing the current concrete witness would lose
the parameterized topology. Increment 59b remains unchecked and depends on the
merged completion of Increment 59a.
