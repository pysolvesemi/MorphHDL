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

Integer and Boolean local parameters form one acyclic dependency graph.
Validation permits forward references within and across kinds, rejects every
integer, Boolean or mixed cycle, and produces a deterministic dependency-first
order with lexical tie-breaking. Target capability passes then prove that every
expression subtree can be represented by that target; for the Verilog-2001
backend, both kinds currently use the signed 32-bit `integer` domain after
Boolean legalization.

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

Increment 4 implements the non-generate hierarchy tranche: named instances,
named child-parameter expressions, named child-port bindings, recursive-module
cycle rejection and deterministic dependency-first module order. Increment 5
adds a bounded homogeneous `GenerateFor`: its count is a positive constant
expression, its zero-based unit-stride `GenIndex` is visible only in the body,
and indexed part-selects preserve symbolic offsets and widths. This tranche
permits `GenIndex` only in indexed part-select offsets; index-dependent child
parameter bindings and other body expressions fail closed. The initial driver
proof accepts only a canonical complete, nonoverlapping partition of a packed
output. Nested generate regions and conditional generate forms remain planned
separately and fail closed.

Increment 9 adds a distinct `BooleanParameter` declaration, `BoolExpr`
literals/references/negation/conjunction/disjunction and one non-nested
`GenerateIf` per module. The conditional owns mandatory, explicitly labeled
true and false blocks. Validation resolves every Boolean reference, validates
the complete hierarchy and bindings in both blocks regardless of the default,
and proves output drivers independently on each mutually exclusive path.
At Increment 9, conditional public ports, multiple or nested generate regions,
Boolean child bindings and `GenerateCase` remained unsupported; Increment 12
implements the Boolean-binding item while retaining the other restrictions.

Increment 10 adds six distinct typed `BoolExpr` comparisons over `IntExpr`:
less-than, less-than-or-equal, greater-than, greater-than-or-equal, equality and
inequality. Default evaluation receives the current instance's public and local
integer facts, while validation eagerly analyzes both operands over their legal
domains. Comparison semantics are mathematical arbitrary-precision integers;
Verilog width and signedness behavior enters only during target legalization.
Increment 11 adds `IntExpr.Select(condition, whenTrue, whenFalse)`. Its exact
default follows the typed Boolean condition in the current module-instance
context. Whole-domain analysis eagerly validates the condition and both value
branches, then uses the unconditional hull of the two branch intervals. It
does not use a default choice as proof that the inactive branch is safe, and
it does not yet refine either branch from correlations implied by the guard.
This conservative rule applies wherever an integer constant expression is
legal, including local parameters, packed widths, child bindings and generate
counts. Conditional expressions compose recursively with the normal expression
precedence rules. At Increment 11, Boolean child bindings, Boolean local
parameters and nested or multiple structural predicates were separate
tranches; Increment 12 implements the first item only.

Increment 12 adds `BooleanParameterBinding(parameterName, value)` as a typed
named association separate from the existing integer `ParameterBinding`.
Validation resolves the target only in the child Boolean namespace and checks
the value expression in the parent public-Boolean, public-integer and
local-integer scope. It analyzes the full expression even when the default is
already decided by a Boolean operand. Morph default-shape recursion replaces
the child declaration default with that per-instance result before evaluating
child locals, widths and selected hierarchy. Integer/Boolean binding-kind
mismatches, duplicate Boolean bindings and bindings hidden in inactive
generate branches remain whole-design errors. At Increment 12, Boolean local
parameters, nested structural predicates and `GenerateCase` remained separate
tranches; Increment 13 implements the Boolean-local item below.

Increment 13 adds `BoolExpr.LocalParameterRef(name)` and
`BooleanLocalParameter(name, value)`. Boolean and integer locals are validated,
ordered and instantiated through one combined dependency graph. This permits
Boolean locals to compare integer locals and permits integer locals to consume
Boolean locals through `IntExpr.Select`, including forward cross-kind
references. Each module-instance context begins with its bound public integer
and Boolean values, then evaluates that combined order exactly once before
widths, generate counts, conditions and child bindings consume the local
facts. Same-name cross-kind declarations, unresolved or wrong-kind references,
and mixed cycles are deterministic validation errors. At Increment 13,
multiple or nested structural predicates and `GenerateCase` remained separate
tranches; Increment 14 implements the bounded case item below.

Increment 14 adds `GenerateCaseChoice(value, block)` and
`ModuleItem.GenerateCase(selector, choices, default)`. The selector is an
`IntExpr`; explicit choice values are unique mathematical integers and the
default block is mandatory. Validation checks all explicit blocks and the
default regardless of the selector default, proves output drivers on each
exclusive path and orders choices by ascending numeric value. Morph default
shape evaluates the selector in each exact public/bound/mixed-local instance
context, choosing an equal literal or the default. This tranche permits one
conditional region total per module (`GenerateIf` or `GenerateCase`) and
rejects a second sibling conditional. A sibling `GenerateFor` remains legal;
nesting if/for/case regions in any direction remains rejected.

Increment 15 adds `ProceduralAssign(target: RtlExpr.Ref, value: RtlExpr.Ref)`
and `ModuleItem.CombinationalIf(label, condition, whenTrue, whenFalse)`. The
condition and values are direct runtime port references in this first tranche.
Validation requires a one-bit input condition, input values type-compatible
with their output targets, non-empty complete branches and exactly one
procedural driver for every process-owned output on both paths. Process targets
cannot also have continuous, instance or structural drivers. Every reference
and both branches validate regardless of runtime activity. This tranche permits
at most one `CombinationalIf` per module and no sibling instance or parameter
generate region. Multiple processes, nested statements, general runtime
expressions, sequential state and memories remained separate later tranches;
Increments 16 and 17 implement the first sequential items below.

Increment 16 adds
`ModuleItem.SynchronousRegister(label, clock: RtlExpr.Ref,
reset: RtlExpr.Ref, assignment: ProceduralAssign)`. It has fixed positive-edge
clocking and active-high synchronous reset-to-zero semantics. Validation
requires distinct exact unsigned one-bit input clock/reset ports, a direct
input data reference, a sole output target and exact packed type equivalence
across the legal parameter domain. The process owns that output completely and
may not have any sibling continuous assignment, combinational process,
instance, generate region or second synchronous register. This restriction
makes partial reset/data coverage, mixed drivers and internal multi-domain
crossings unrepresentable for the bounded node. It does not model clock
enables, asynchronous reset, multiple registers or clocks, external
input-domain provenance, CDC structures, general clocked expressions or
memories.

Increment 17 adds
`ModuleItem.AsynchronousRegister(label, clock: RtlExpr.Ref,
reset: RtlExpr.Ref, assignment: ProceduralAssign)`. Its clock edge, reset
polarity and reset value match the synchronous variant, while reset assertion
is asynchronous and has priority over data capture. Validation applies the
same distinct-role, exact-control-width, direct-data, sole-output and
whole-domain type-equivalence proof. An asynchronous register may not have a
sibling continuous assignment, process, instance or generate region. Clock
enables/hold behavior, reset polarity selection, multiple registers or clocks,
external input-domain provenance, CDC structures and memories remain outside
the bounded node.

Increment 18 adds
`ModuleItem.SynchronousEnabledRegister(label, clock: RtlExpr.Ref,
reset: RtlExpr.Ref, enable: RtlExpr.Ref, assignment: ProceduralAssign)`. It
extends the synchronous register with a distinct exact unsigned one-bit input
enable. Reset-to-zero has priority, enable high performs the direct full-width
capture, and enable low intentionally preserves the prior state. Validation
applies the same sole-output ownership and whole-domain type-equivalence proof
and rejects all sibling process, continuous, instance and generate items. The
node does not generalize to arbitrary hold values, multiple registers/clocks,
external domain provenance or memories.

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
