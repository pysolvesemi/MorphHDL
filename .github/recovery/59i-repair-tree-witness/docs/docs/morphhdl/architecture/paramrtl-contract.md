# ParamRTL contract

ParamRTL is MorphHDL's canonical, target-neutral semantic IR for explicitly
authored compatibility designs. It is owned by MorphHDL and must not expose
Verilog syntax, CIRCT operations or concrete-only Spinal internal types in its
public model.

Increment 29 does not manufacture a second ParamRTL `Design` from or beside an
ordinary component merely to claim single-source support. Its bounded bridge
retains direct symbolic-width metadata on the native Spinal graph and emits
that graph through the explicit Morph path. ParamRTL remains the regression
oracle for the other reviewed fixtures until their ordinary component sources
are migrated or a generic native-to-semantic lowering is justified.

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
- positive `AddressWidth` with a minimum one-bit result;
- mathematical `Min` and `Max` operations;
- mathematical `CeilLog2` over a proven-positive operand, with zero at one;
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

Increment 21 implements `IntExpr.AddressWidth(value)`. Its mathematical result
is `max(1, ceil(log2(value)))`; target-independent ParamRTL validation requires
every possible operand to be positive. A target capability separately proves
its representable ceiling; Verilog-2001 requires a signed 32-bit operand.
Analysis preserves exact defaults and maps an interval monotonically through
the same function. Equivalence and substitution retain the node, including
public, local and parent-bound contexts. Memory validation recognizes an
address width derived from that same depth expression, so exact correlation
proves capacity without replacing the width by an unrelated interval minimum.

Increment 23 implements `IntExpr.Min(left, right)` and
`IntExpr.Max(left, right)`. Validation, exact evaluation, substitution,
equivalence and dependency discovery recurse through both operands. For
intervals `[a, b]` and `[c, d]`, minimum produces
`[min(a, c), min(b, d)]` and maximum produces
`[max(a, c), max(b, d)]`. This conservative whole-domain result does not use
the default witness to discard either operand. Literal pairs and identical
operands normalize after validation, and equivalence recognizes swapped
operands; broader constraint-based dominance rewriting remains deferred.
Target capability passes remain responsible for proving that the chosen
lowering is finite and representable.

Increment 24 implements `IntExpr.CeilLog2(value)`. Target-independent
validation requires the complete operand domain to be positive. Exact
evaluation uses zero for one and `(value - 1).bitLength` for larger positive
integers; monotonic interval analysis maps both endpoints through that same
function. Substitution, equivalence, dependency discovery and local-parameter
ordering retain the semantic node. The zero result is intentional and keeps
this operation distinct from `AddressWidth`, whose memory-address contract
applies a one-bit minimum. Target backends may choose different equivalent
spellings without changing ParamRTL.

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

Increment 19 adds
`ModuleItem.AsynchronousEnabledRegister(label, clock: RtlExpr.Ref,
reset: RtlExpr.Ref, enable: RtlExpr.Ref, assignment: ProceduralAssign)`. Its
roles, whole-domain type proof, sole ownership and disabled hold match the
synchronous enabled node, but active-high reset-to-zero asserts asynchronously
and has priority over enabled capture. Sibling processes, continuous drivers,
instances and generate regions remain rejected. This node still does not
model arbitrary hold values, multiple registers/clocks, external domain
provenance or memories.

Increment 20 adds
`ModuleItem.SynchronousReadFirstSinglePortMemory(label, memoryName, clock,
readEnable, writeEnable, address, writeData, readData, elementType: PackedBits,
depth: IntExpr)`. It represents one positive-edge synchronous read/write port
with read-first same-address collision semantics. Validation requires distinct
direct input roles, an exact unsigned one-bit clock and read/write enables, one
unsigned packed address, exact element/write/read packed-type equivalence and
a positive bounded depth. The address capacity must cover the maximum legal
depth across the whole parameter domain. With read enable high, an in-range
address updates the read output from the pre-write memory value and a surplus
address updates it to zero. With read enable low, the output holds. A valid
write remains controlled only by write enable and the in-range guard, including
while reads are disabled. A surplus address cannot write.
The node is the sole module item and sole owner of its read output and memory
name. It carries no reset, initialization, write mask, additional
port, selectable collision policy or external clock-domain provenance.

Increment 21 does not change the memory node or its runtime policy. It changes
the public memory address type to
`PackedBits(IntExpr.AddressWidth(depth), Unsigned)`, making the static packed
ABI track each legal depth while keeping at least one address bit for
`DEPTH=1`. Exact expression correlation proves the address capacity; fixed or
unrelated widths remain subject to the conservative whole-domain capacity
check.

Increment 22 adds the required active-high `readEnable` role without changing
the depth-derived address ABI. Disabled reads intentionally retain the last
read output; enabled simultaneous read/write remains read-first, and read
enable never gates the existing valid whole-word write path.

Increment 25 adds
`ModuleItem.SynchronousCounter(label, clock, reset, enable, count, limit)`.
The direct `limit` expression must resolve to the same module's public integer
parameter with a finite lower bound of at least one. Clock, reset and enable
are distinct exact unsigned one-bit inputs; count is a distinct sole output of
exact type `PackedBits(AddressWidth(limit), Unsigned)`. On each positive edge,
active-high synchronous reset clears count, otherwise active-high enable wraps
`LIMIT - 1` to zero or increments by one; enable low holds. The node is the
sole module item and forbids sibling drivers, hierarchy, generates, memories
or other processes. `LIMIT=1` is legal and retains one zero state bit.

Increment 26 adds
`ModuleItem.SynchronousReadFirstSimpleDualPortMemory(label, memoryName, clock,
readEnable, writeEnable, readAddress, writeAddress, writeData, readData,
elementType, depth)`. It represents one shared positive-edge clock, one
synchronous read port and one whole-word write port with independent direct
addresses and active-high enables. Both address ports must be unsigned and
exactly type-equivalent, and each independently proves enough capacity for the
same positive bounded depth across its complete legal domain. The public
fixture selects `PackedBits(AddressWidth(depth), Unsigned)` for both addresses,
but an independently sufficient fixed or symbolic width is also valid. Data
ports exactly match the element type; the node is the sole owner of its read
output and memory name.

An enabled in-range read updates the output from the pre-write array value at
the edge, while a disabled read holds and an enabled surplus read updates the
output to zero. A valid write depends only on write enable and its own in-range
address guard. Thus different-address read/write operations proceed together,
and a same-address collision is deterministically read-first before the write
commits. Surplus writes are inert and unwritten in-range values are unspecified.
The node carries no reset, initialization, mask, independent clock, additional
port or selectable collision policy.

Increment 27 adds
`ModuleItem.SynchronousStreamFifo(label, memoryName, clock, reset, pushValid,
pushReady, pushData, popValid, popReady, popData, elementType, depth)`. It is
one atomic single-clock ready/valid FIFO with a synchronous-read array and one
registered pop stage. The direct public positive `depth` denotes the exact
number of accepted, unconsumed transactions across both storage locations.
Validation requires eight distinct direct roles, exact one-bit unsigned
clock/reset/handshake controls, exact packed push/pop data types, a finite
target-safe depth, sole ownership of all three outputs and the memory name,
and no sibling module item.

Active-high reset synchronously clears read/write pointers, occupancy and
`popValid`; it does not initialize memory or `popData`, which is unspecified
whenever invalid. Empty input cannot bypass directly to pop. Full rejects a
push even when a pop is ready on the same edge. Away from those boundaries,
simultaneous accepted push/pop preserves occupancy and order. A stalled valid
pop holds its payload. When occupancy one is simultaneously pushed and popped
at a non-full depth, the new word is retained but the synchronous refill adds
one invalid cycle. Pointer wrap is exact at `depth - 1`. Bypass, flush,
occupancy/availability ports, alternate latency, initialization, masks,
asynchronous reset/read and multiple clocks remain separate policies.

Increment 28 adds
`ModuleItem.SynchronousStreamM2sPipe(label, clock, reset, pushValid,
pushReady, pushData, popValid, popReady, popData, elementType)`. It is one
atomic single-clock capacity-one ready/valid stage. Validation requires eight
distinct direct roles, exact unsigned one-bit controls, symmetric packed data
types, sole ownership of all three outputs and no sibling item. `pushReady` is
true when the registered output is empty or being consumed. An accepted input
updates registered valid and payload after one edge; full pop plus push
replaces without a bubble, while full stall holds both. Active-high synchronous
reset clears only valid. Payload captures whenever ready, including invalid or
reset cycles, and is unspecified whenever valid is low. Selectable
`collapsBubble`/`holdPayload`, bypass storage, status, asynchronous reset and
multiple clocks remain separate policies.

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

## Compatibility pipeline boundary

The explicitly authored ParamRTL compatibility pipeline is:

```text
Morph frontend
  -> ParamRTL validation
  -> target-neutral normalization
  -> target capability verification
  -> Verilog-2001 legalization
  -> deterministic Verilog emitter
```

A future compatibility CIRCT adapter may lower validated ParamRTL into
appropriate CIRCT dialects. Neither CIRCT nor ParamRTL is the production
single-source canonical handoff.

A future production SystemVerilog backend consumes the validated
`morphhdl.ir.v1` handoff. A compatibility backend may continue to consume
explicitly authored ParamRTL designs and their target capabilities, without
turning ParamRTL into a second production IR.
