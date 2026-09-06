# Strict Verilog-2001 target profile

The first MorphHDL target is synthesizable IEEE 1364-2001 Verilog. Output must
be accepted without enabling a SystemVerilog parser.

## Required mappings

| Semantic construct | Verilog-2001 representation |
|---|---|
| Integer public parameter | `parameter integer` with a literal default |
| Native direct symbolic `UInt` width (implemented) | One deduplicated `parameter integer` declaration and `[NAME-1:0]` top-level input/output ranges |
| Boolean public parameter | `parameter integer` with canonical default `1` or `0` |
| Integer derived constant | `localparam integer` |
| Boolean derived constant | `localparam integer` with canonical `1`/`0` value |
| Named module instance | `Child #(.PARAM(expr)) instance (.port(signal))` |
| Named Boolean child binding | `.PARAM((boolean_expr) ? 1 : 0)` after integer-Boolean legalization; literals use `.PARAM(1)` or `.PARAM(0)` |
| Packed unsigned signal | `wire` or `reg [WIDTH-1:0]` |
| Native scalar `SInt` (60g single-source default) | `wire signed [WIDTH-1:0]` or `reg signed [WIDTH-1:0]`; retain casts at unproven unsigned boundaries |
| Combinational process | `always @*` |
| Clocked process | Edge-sensitive `always` |
| Structural loop | Named `generate`/`for` with `genvar` |
| Structural condition (implemented) | Named `generate`/`if` with explicit `== 1` Boolean references |
| Integer comparison (implemented) | `<`, `<=`, `>`, `>=`, `==` or `!=` after operand capability proof |
| Conditional integer value (implemented) | Parenthesized Boolean condition with Verilog-2001 `condition ? when_true : when_false` |
| Integer minimum/maximum (implemented) | Canonical `(left < right) ? left : right` / `(left > right) ? left : right` conditional expressions |
| Mathematical ceiling-log2 (implemented) | One module-local `clog2(value, minimum_result)` constant function called with minimum zero |
| Structural case (implemented) | Named `generate`/`case` with ascending signed-decimal choices and mandatory default |
| Runtime two-way mux process (implemented) | Named `always @*` block, one-bit `if` condition, complete blocking assignments and process-driven `output reg` targets |
| Synchronous register (implemented) | Named `always @(posedge clock)` block, active-high synchronous reset-to-zero, complete nonblocking assignments and process-driven `output reg` target |
| Asynchronous-reset register (implemented) | Named `always @(posedge clock or posedge reset)` block, active-high asynchronous reset-to-zero, reset priority, complete nonblocking assignments and process-driven `output reg` target |
| Synchronous enabled register (implemented) | Named `always @(posedge clock)` block with reset-priority active-high synchronous reset-to-zero, active-high capture, implicit disabled hold and nonblocking assignments |
| Asynchronous enabled register (implemented) | Named `always @(posedge clock or posedge reset)` block with immediate reset-priority active-high asynchronous reset-to-zero, active-high capture, implicit disabled hold and nonblocking assignments |
| Parameterized synchronous counter (implemented) | Named `always @(posedge clock)` block with active-high synchronous reset-to-zero, active-high enable/hold, `count == LIMIT - 1` wrap and nonblocking increment; count width is portable address width of `LIMIT` |
| Synchronous read-first single-port memory (implemented) | `reg [WIDTH-1:0] memory [0:DEPTH-1]` plus one guarded named positive-edge process with nonblocking read and optional whole-word write assignments |
| Synchronous read-first simple-dual-port memory (implemented) | One `reg [WIDTH-1:0] memory [0:DEPTH-1]` and one named positive-edge process with independent guarded read/write addresses, nonblocking state updates and deterministic read-first same-address collisions |
| Synchronous Stream FIFO (implemented) | One synchronous-read `reg [WIDTH-1:0] memory [0:DEPTH-1]`, registered pop stage, wrapped read/write pointers, bounded occupancy and one named positive-edge process; ready/valid outputs preserve exact public capacity and no-bypass boundaries |
| Synchronous Stream m2s pipe (implemented) | `push_ready = pop_ready || !pop_valid` plus one named positive-edge process with an active-high synchronously reset valid register and an independently ready-enabled unreset payload register |
| Portable address width (implemented) | The same module-local `clog2(value, minimum_result)` constant function called with minimum one |
| Logical record/vector port | Deterministically flattened scalar/packed ports |
| Enum intent | Packed vector plus named local parameters |
| Parameterized memory | `reg [WIDTH-1:0] mem [0:DEPTH-1]` |

The emitter determines `wire` versus `reg` from driver semantics. Those words
are backend spellings and are not encoded in ParamRTL.

Increment 29 applies the first row through ordinary SpinalHDL component
syntax without constructing ParamRTL. Its explicit native mode accepts only
direct positive finite `HdlInt` parameters on top-level `UInt` ports and one
same-schema input-to-output assignment. The concrete default still passes
through inherited width inference, while the retained parameter schema drives
the module declaration and packed ranges. Ordinary `SpinalVerilog` leaves
this mode disabled and emits only the concrete witness width. An `HdlInt`
literal carries no symbolic tag and remains a normal concrete `UInt` width;
explicit parameterized generation rejects literal-only or mixed tagged and
untagged interfaces.

Boolean intent likewise remains typed in ParamRTL. Integer `1`/`0`
declarations and `NAME == 1` predicates are backend legalization, not the
canonical Boolean type. Increment 10 legalizes explicit mathematical integer
comparison nodes to Verilog operators only after each operand subtree fits the
target `integer` domain. Increment 11 legalizes conditional integer values only
after the condition and both value branches pass capability checks; the
condition is always parenthesized and expression precedence preserves the exact
tree. Increment 9 supports one named two-branch generate-if. Increment 14
supports one named integer-selected generate-case with unique literal choices
and a mandatory default. Only one of these conditional regions may appear in a
module; nested conditional structure remains outside the executable profile.

Increment 12 applies that same integer encoding to typed Boolean child
bindings. The canonical binding remains Boolean in ParamRTL; the emitter
legalizes its value expression and emits one ordinary named parameter
association. Non-literal predicates use an explicit `? 1 : 0` boundary so a
one-bit logical result is not assigned directly to a Verilog `integer`
parameter. Binding-kind validation happens before legalization, so the shared
Verilog spelling cannot make an integer expression a Boolean binding or vice
versa.

Increment 13 applies the same boundary to typed Boolean local parameters. A
`BooleanLocalParameter` emits as an integer local parameter whose nonliteral
value is normalized with `(predicate) ? 1 : 0`; Boolean literals may use direct
`1` or `0`. A Boolean-local reference emits as `NAME == 1`. Integer and Boolean
locals use the validator's single combined dependency-first order, so mixed
forward references in ParamRTL never become forward local declarations in
strict Verilog.

Increment 15 legalizes the first runtime combinational process. A process-owned
output emits as `output reg`; the process emits as a named `always @*` block
with explicit `if`/`else` paths and blocking `=` assignments. The canonical IR
does not encode `reg`, `always` or blocking syntax: it records one complete
stateless `CombinationalIf` and procedural assignment intent. Capability and
validation reject incomplete paths, mixed drivers and non-reference runtime
expressions before target spelling. `always_comb`, inferred latches and
nonblocking assignments remain forbidden in the strict profile.

Increment 16 legalizes the first sequential process. A sole process-owned
output emits as `output reg`; one named `always @(posedge clock)` block places
the active-high reset test inside the edge-sensitive body, emits a target-width
zero replication on reset and captures the direct input otherwise. Both paths
use nonblocking `<=`. ParamRTL retains the positive edge, synchronous reset
polarity/value and complete ownership semantics; `reg`, `always`, replication
and nonblocking syntax are backend spellings. Capability and validation reject
mixed items, incomplete or aliased roles, type mismatch, multiple processes
and unsupported width expressions before target spelling. At Increment 16,
`always_ff`, enables and mixed blocking assignment remained outside the
executable profile.

Increment 17 legalizes the parallel asynchronous-reset node. The reset is
explicitly present as `posedge reset` in the sensitivity list and is tested
before data capture in the process body. The target-width zero replication and
both nonblocking assignments are otherwise identical to the synchronous
variant. ParamRTL retains the asynchronous reset semantics; capability checks
reject unsupported roles or widths before syntax is emitted. A synchronous
sensitivity mutation, falling-edge clock, reset-to-ones and selectable
polarity remain forbidden. At Increment 17, enable/hold and `always_ff`
remained outside the executable profile.

Increment 18 legalizes intentional synchronous hold. One named positive-edge
process tests reset first and enable second. Reset emits target-width zero;
enable emits the direct data capture; no final assignment branch is emitted
when enable is low. The missing final `else` is sequential state retention and
must not be rewritten as a combinational incomplete assignment. Capability and
validation reject role aliasing, incorrect widths, mixed drivers and sibling
structure before target spelling. Enable-first priority, falling-edge
clocking, reset-to-ones, disabled capture and `always_ff` remain forbidden.

Increment 19 legalizes the matching asynchronous-reset hold form. The reset is
present as `posedge reset` in the sensitivity list and is tested before the
enable condition. Reset emits target-width zero, enable high emits direct data
capture and enable low emits no assignment. ParamRTL retains immediate
active-high reset assertion, reset priority and intentional hold; the backend
only supplies strict Verilog-2001 spelling. Capability and validation reject
role aliasing, incorrect widths, mixed ownership and sibling structure before
emission. Reversed reset/enable roles, synchronous reset, active-low enable,
falling-edge clocking, reset-to-ones and `always_ff` remain forbidden.

Increment 20 legalizes one bounded memory form. A process-owned read output
emits as `output reg`, while the memory remains an internal unpacked array of
packed `reg` elements. One named `always @(posedge clock)` process tests
`address < DEPTH`. Increment 22 conditionally schedules the read only when the
active-high read enable is asserted and independently schedules one whole-word
write when the write enable is asserted. Nonblocking semantics make a
same-address collision read-first. The surplus branch schedules a target-width
zero only for an enabled read; an omitted assignment intentionally holds the
output when reads are disabled. Nesting only the write under the address guard
makes surplus addresses write-inert without allowing read enable to gate valid
writes. Capability and validation prove positive width/depth, whole-domain
address capacity, exact data types, distinct controls and sole ownership before
spelling. No reset or initial block is emitted, so unwritten in-range reads
remain unspecified. Initialization, selectable enable polarity, masks,
multiple ports/clocks and selectable collision modes remain forbidden.

Increment 21 originally legalized `IntExpr.AddressWidth` as a fully
parenthesized threshold chain. Increment 24 replaces that target spelling with
one module-local constant function called with an internal minimum-result
literal of one. Target-independent ParamRTL validation still proves the
operand positive and retains the semantic one-bit minimum, while capability
validation proves the complete operand lies in the positive signed-32-bit
`integer` domain. The helper's `integer` input and loop state therefore cover
the complete target domain without truncation. No externally overrideable
sizing parameter or specialized module is introduced.

Increment 23 legalizes `IntExpr.Min` and `IntExpr.Max` to canonical
comparison ternaries. Both operands are rendered in the predicate and the
selected branches, so branch order is fixed and neither operand may be
specialized away from a default witness. The capability verifier and emitter
share a conservative expansion estimate and reject a rendered Min/Max tree
above 4096 syntax nodes with
`V2001-MIN-MAX-EXPANSION-TOO-LARGE`. The cap controls generated Verilog size only;
ParamRTL retains the target-neutral mathematical node. No `$min`, `$max`,
function declaration or SystemVerilog construct is emitted.

Increment 24 legalizes both `IntExpr.CeilLog2` and `IntExpr.AddressWidth` with
one shared module-local constant function per consuming module. The natural
default name is `clog2`; if that identifier is already occupied in the module,
the backend deterministically chooses the first free `clog2_1`, `clog2_2`, and
so on from a conservative module-local identifier set. The canonical helper initializes the
result to zero, shifts `value - 1` right until it reaches zero, increments once
per shift and clamps to the backend-supplied zero-or-one `minimum_result`. It is a Verilog constant
function: parameter and local-parameter expressions are evaluated during HDL
elaboration and no runtime shifter, counter or state is inferred. Every call
retains its full dynamic parameter expression; no default specialization is
allowed. Keeping the minimum inside the helper renders each arbitrary operand
once, so direct and mixed nesting remains linear without a log-specific
expansion cap. Reusing `clog2` in separate modules is safe because Verilog
function declarations are module-local.

`$clog2(PARAM)` is synthesizable and was standardized in IEEE 1364-2005
Verilog; it is not SystemVerilog-only. It remains outside the selected IEEE
1364-2001 baseline, and strict-2001 tools differ on whether they accept it as
an extension. MorphHDL therefore keeps rejecting `$clog2` and emits the local
constant function without upgrading the target language. `AddressWidth`
remains a separate semantic node because it returns at least one for operand
one, while mathematical `CeilLog2(1)` returns zero.

Increment 25 legalizes the atomic `SynchronousCounter` to one named
positive-edge process. Reset is tested first, enable second and the terminal
`count == LIMIT - 1` branch wraps to a zero replication whose width is the
same `clog2(LIMIT, 1)` used by the packed count port. The
nonterminal branch uses `count + 1'b1`; disabled enable has no assignment and
therefore holds state. Capability validation requires a direct positive public
limit in the signed-32 target domain and exact `AddressWidth(limit)` output
before emission. The helper remains elaboration-time logic; only the intended
runtime comparator, incrementer, mux and register are synthesized.

Increment 26 legalizes one bounded
`SynchronousReadFirstSimpleDualPortMemory`. The process-owned read output emits
as `output reg`; both packed unsigned address ports have one exactly equivalent
type and index one unpacked array. The public fixture derives both widths with
`clog2(DEPTH, 1)`, while the general node may use any mutually
type-equivalent widths whose complete domains independently cover `DEPTH`. One
named `always @(posedge clock)` process contains sibling read and write paths.
The read path tests its own `readAddress < DEPTH` guard, schedules an in-range
array value only when read enable is high, and otherwise schedules a width-wide
zero only for an enabled surplus read. Omitting a disabled-read assignment
retains output state. The write path separately tests
`writeAddress < DEPTH` and schedules one whole-word write only when write
enable is high. Nonblocking assignments guarantee that simultaneous
same-address operation observes the pre-write value before committing the
write; no bypass mux is emitted.

Capability and ParamRTL validation require independently capacity-safe
addresses, distinct direct controls/data roles, one shared clock and sole
memory/output ownership before spelling. No `initial` or reset logic is
emitted, so unwritten in-range values remain unspecified. Independent clocks,
asynchronous reads, masks, byte enables, additional ports and selectable
read-during-write modes remain forbidden.

Increment 27 legalizes one atomic `SynchronousStreamFifo`. The emitter derives
`POINTER_WIDTH = clog2(DEPTH, 1)` and
`OCCUPANCY_WIDTH = clog2(DEPTH + 1, 1)`, then declares one exact `DEPTH`-word
array, wrapped read/write pointers, an occupancy register, registered
`pop_valid`/`pop_data`, and combinational `push_ready = occupancy < DEPTH`.
One named positive-edge process writes whole words on accepted pushes,
synchronously refills an available pop stage only from already-queued storage,
updates occupancy from accepted push/pop events, and wraps each pointer at
`DEPTH - 1`. Active-high synchronous reset clears pointers, occupancy and
`pop_valid`, but neither memory nor invalid `pop_data`.

Consequently an empty push is not same-edge visible, a full push is rejected
even with `pop_ready`, middle simultaneous push/pop is accepted, and a stalled
valid pop holds its payload. Occupancy-one simultaneous push/pop at `DEPTH>1`
retains the new word but produces one synchronous refill bubble. The natural
internal names and helper/local names receive the same deterministic numeric
suffix treatment if a module-local identifier is already occupied. Bypass,
flush, occupancy ports, initialization, alternate latency, asynchronous read
or reset, and multiple clocks remain forbidden.

Increment 28 legalizes one atomic `SynchronousStreamM2sPipe`. The emitter
drives `push_ready = pop_ready || !pop_valid`, then emits one named positive-edge
process. Active-high synchronous reset clears `pop_valid`; otherwise ready
captures `push_valid`. A separate ready-enabled nonblocking assignment captures
`push_data` into `pop_data`, including invalid and reset edges, matching the
pinned default `holdPayload=false` behavior. Omitting assignments while full
and stalled holds both registers. There is no valid/payload bypass, helper,
memory, pointer, status or initialization. Full pop-plus-push replaces the
resident transaction without a bubble. Selectable hold/collapse policies,
`s2mPipe`, composition, asynchronous reset and multiple clocks remain
forbidden.

## Flat ABI

The v1 public ABI permits scalar and packed-vector ports. Bundles, logical
vectors and protocol records are flattened using stable field order and naming.
The layout is retained in ParamRTL or the identity-bound native typed metadata
for a possible future SystemVerilog target.

Increment 30 applies the same flat ABI directly to native tagged
Bits/UInt/SInt leaves cloned through HardType, Bundle, static Vec, Stream and
Flow. Each packed payload leaf and supported internal/register declaration
uses `[PARAMETER-1:0]`; concrete Bool controls have no range. SInt retains its
native Spinal AST identity. The historical Increment 30 profile and explicit
legacy mode follow the native Verilog style without a `signed` keyword.
Increment 60g changes the MorphHDL single-source default to signed scalar
declarations and proven minimal casts, as specified below. Native
`SpinalVerilog` remains unchanged.

Increment 53f extends that boundary to a typed `Vec` whose logical depth and
element layout remain attached to the ordinary native Vec. Strict
Verilog-2001 publishes each Vec subtree as one packed vector: an element width
`ELEMENT_WIDTH` and depth `DEPTH` produce
`[(ELEMENT_WIDTH * DEPTH)-1:0]`. Element zero occupies the least-significant
element slice, and constant or runtime indexing is lowered to legal packed
part-select logic. Composite elements contribute the sum of their flattened
leaf widths, so a two-element Vec of a three-leaf `WIDTH` Bundle is one
`6 * WIDTH`-bit port rather than six separately named ports. No unpacked array
port or SystemVerilog multidimensional packed type is required.

This Vec publication rule does not apply to `Mem`. A typed memory retains its
unpacked storage declaration, equivalent to
`reg [WIDTH-1:0] memory [0:DEPTH-1]`, so synthesis can continue to infer RAM.
An exact scalar `Mem[SInt]` element adds `signed` to this packed element
declaration in signed mode; an aggregate memory carrier remains unsigned.
The logical distinction is therefore explicit: Vec is a structural collection
with a packed Verilog boundary, while Mem is storage with an unpacked array
declaration.

Widths may depend on parameters. Port presence, name, direction and clock/reset
role may not depend on parameters. Designs requiring different interfaces use
separate static profiles or top modules.

## Forbidden output

Strict mode rejects SystemVerilog-only or ambiguous constructs, including:

- `logic`, `always_comb`, `always_ff` and `always_latch`;
- interfaces, modports, packages, structs, unions and typedefs;
- unpacked array ports and type parameters;
- classes, SVA/property syntax and SystemVerilog testbench constructs;
- Verilog-2005 `$clog2`, SystemVerilog `$bits` and other helpers outside the
  IEEE 1364-2001 baseline;
- raw/verbatim HDL that bypasses target verification;
- configuration-specialized module suffixes such as `__v_lanes4`.

## Validation gate

Every emitted fixture must pass:

1. ParamRTL validation and Verilog-2001 capability verification.
2. A project-owned forbidden-construct check.
3. Icarus parsing with `-g2001`.
4. Yosys `read_verilog` without `-sv` followed by hierarchy and consistency
   checks.
5. Default, minimum, awkward non-power-of-two and mixed parameter overrides.
6. Structural checks confirming widths, generate counts and memory depths.
7. Differential equivalence or cycle-accurate comparison with concrete
   SpinalHDL specializations.
8. Byte-for-byte determinism and one definition per logical module.

The parser and synthesis checks supplement the internal capability verifier;
they do not replace it.


## Native signed scalar publication (Increment 60g)

The MorphHDL single-source `MorphVerilog(config) { component }` path resolves
an otherwise neutral configuration to signed scalar declarations and minimal
proven casts. This is an output-policy change, not a new arithmetic algorithm.
`SInt` ports, internal wires, registers and exact scalar memory elements retain
their typed widths and use native Verilog-2001 `signed` declarations. Pure
signed arithmetic can therefore use `a + b` rather than redundant operand
casts. Existing truncation, overflow, intermediate widths and operator order
remain authoritative.

The following selections apply to a fresh `SpinalConfig`:

| Configuration supplied to `MorphVerilog` | Scalar declarations | Expression policy |
| --- | --- | --- |
| `config` | Signed | Proven minimal casts (default) |
| `MorphSignedCasts.enable(config)` | Signed | Explicit selection of the default policy |
| `MorphSignedDeclarations.enable(config)` | Signed | Existing casts retained |
| `MorphSignedCasts.disable(config)` | Signed | Cleanup explicitly disabled |
| `MorphSignedDeclarations.disable(config)` | Legacy unsigned spelling | Existing casts retained |

Options return copied configurations. Default resolution takes place on a
private publication copy, without adding markers to the caller's configuration.
There is no environment, process-global or thread-global rollout switch.
`MorphVerilog.tryGenerate`, canonical-IR generation and canonical-IR publication
use the same single-source path. The deprecated dual-factory witness path does
not acquire this new default.

The selectors are layered: enabling declarations on a config that already has
cleanup enabled retains that cleanup request. To select declaration-only output
from any enabled config, use `MorphSignedCasts.disable(config)`. Disabling
declarations takes precedence over cleanup; enabling cleanup explicitly also
re-enables declarations. Copies preserve explicit choices. `isEnabled(config)`
queries an installed option marker, not the eventual default of a neutral
configuration; a neutral caller config remains unmodified after publication.

Ordinary `SpinalVerilog(config)` and `SpinalVhdl(config)` retain their native
behavior even when a MorphHDL signedness option is present. The default changes
only MorphHDL's parameterized single-source publication. Previously generated
RTL consumers that require the legacy declaration spelling can request
`MorphSignedDeclarations.disable(config)` explicitly. The public
`symbolic_data_shapes.v` golden follows the new default; sealed historical
60a–60f reference writers select the legacy profile explicitly and retain their
original arithmetic and oracle bytes.

### Necessary boundaries

Cast removal requires exact typed graph identity and joint signedness, width
and expression-context evidence. A signed destination alone never makes an
unsigned right-hand subexpression signed. Unproven bit/part selections,
concatenations, mixed unsigned operands, literals, resize boundaries and external
connections retain their necessary interpretation. A boundary materialized as
an independently declared signed scalar can legitimately need no expression
cast; the policy does not enforce an arbitrary nonzero cast count.

`Bits`, `UInt`, `Bool`, shift amounts, addresses and masks remain unsigned.
Packed Vec/Bundle transport is not one signed scalar, even when every leaf is
`SInt`. A reconstructed leaf regains signed interpretation at its exact scalar
boundary, including a necessary `$signed` around a dynamic packed selection.
Separately published scalar Bundle/Stream/Flow leaves can be signed. `Mem`
remains native unpacked storage, not a repacked Vec. BlackBox source remains
externally owned; the policy controls only MorphHDL's local typed connections.

Unsupported width/context combinations still fail closed. This rollout does
not extend the signedness transfer rules, loosen parameter-domain validation,
change native defaults or retire genuine boundary/legacy native helpers.

### Qualification contract

The [60g record](../increment-60g-default-rollout.md) tracks final-head evidence.
Neutral-config default candidates are independently elaborated and compared
byte-for-byte with explicit-cleanup candidates, then run through the inherited
independent-native-reference proof corpus. Both Scala 2.12.18 and 2.13.12,
fresh-JVM determinism, downloaded cross-Scala byte comparison, native
Verilog/VHDL compatibility, strict V2001 tools, mutation counterexamples and
all inherited no-skip regression gates remain required. WIDTH qualification
includes 1, 5, 8 and 32; finite specialization matrices are not a universal
formal proof over every legal parameter value.
