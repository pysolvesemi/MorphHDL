# ParamRTL contract

ParamRTL is MorphHDL's canonical, target-neutral semantic IR. It is owned by
MorphHDL and must not expose Verilog syntax, CIRCT operations or concrete-only
Spinal internal types in its public model.

## Expression domains

ParamRTL keeps compile-time and runtime expressions separate.

### Parameter expressions

Parameter expressions are evaluated by the downstream HDL elaborator. The v1
algebra contains:

- typed integer and Boolean constants;
- public parameter references;
- local parameter references;
- generate-index references;
- arithmetic, comparison and Boolean operations;
- conditional selection;
- `clog2`, minimum and maximum semantic operations;
- explicit signedness and arbitrary-precision constants;
- constraints over parameter expressions.

Their canonical meaning is signed, arbitrary-precision mathematical integer
arithmetic, independent of a target language's implicit sizing rules. Integer
division truncates toward zero; modulo follows the dividend's sign and obeys
`a == (a / b) * b + (a % b)`. Validation analyzes the entire declared legal
domain separately from the default value. A valid default is never accepted as
proof that all legal overrides have positive widths, nonzero divisors or safe
expression ranges.

Local parameters form an acyclic dependency graph. Validation permits forward
references in ParamRTL, rejects dependency cycles and produces a deterministic
dependency-first order with lexical tie-breaking. Target capability passes then
prove that every expression subtree can be represented by that target; for the
Verilog-2001 backend, this currently means the signed 32-bit `integer` domain.

A parameter expression is not an RTL value. If a design needs a parameter as
runtime data, an explicit conversion node creates an RTL constant of a declared
width and signedness.

### Runtime RTL expressions

Runtime expressions represent signals and ordinary combinational logic. They
include references, literals, arithmetic, comparisons, concatenation, slices,
resizes, casts and muxes. Their types may contain parameter expressions.

## Type model

The canonical type model includes:

- scalar bit, clock and reset semantics;
- packed bits with symbolic width and explicit signedness;
- logical vectors/arrays with symbolic element count;
- logical records with ordered fields and directions;
- enum intent and encoding metadata;
- memory element type and symbolic depth.

Logical records and vectors survive in ParamRTL. A target legalization pass may
flatten them, but flattening must not destroy the canonical metadata.

## Structural model

The v1 structural model contains:

- module definitions with public parameters, ports and local parameters;
- stable logical module identity independent of parameter defaults;
- instances with named parameter and port bindings;
- continuous assignments;
- combinational and clocked semantic processes;
- registers, memories and supported memory ports;
- `GenerateFor`, `GenerateIf` and `GenerateCase` regions;
- stable names, source locations and provenance metadata.

`GenerateFor` owns a `GenIndex` scoped to its body. It represents one reusable
structural template, not a collection of concretely elaborated iterations.

## Required invariants

- Every reference resolves within its lexical parameter, generate or module
  scope.
- Every public parameter has a type, literal default and constraints.
- Parameter bindings are legal constant expressions in the parent scope.
- Widths and depths are provably positive under declared constraints.
- Signedness is explicit; Verilog unsized-literal behavior is not inherited by
  the IR.
- A logical module has exactly one definition in an emitted design.
- Port names and directions are invariant across legal parameter values.
- Driver ownership, clock/reset semantics and aggregate layout are preserved.
- Source order does not affect deterministic names or output.

## Pipeline boundary

The canonical pipeline is:

```text
Morph frontend
  -> ParamRTL validation
  -> target-neutral normalization
  -> target capability verification
  -> Verilog-2001 legalization
  -> deterministic Verilog emitter
```

A future CIRCT adapter lowers validated ParamRTL into appropriate CIRCT
dialects. CIRCT is not the canonical source of truth and is not required for
the Verilog v1 release.

A future SystemVerilog backend consumes the same ParamRTL. SystemVerilog-only
features may add semantic nodes later, guarded by target capabilities; they do
not justify a second core IR.
