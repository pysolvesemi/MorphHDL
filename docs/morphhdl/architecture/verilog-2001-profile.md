# Strict Verilog-2001 target profile

The first MorphHDL target is synthesizable IEEE 1364-2001 Verilog. Output must
be accepted without enabling a SystemVerilog parser.

## Required mappings

| ParamRTL semantic construct | Verilog-2001 representation |
|---|---|
| Integer public parameter | `parameter integer` with a literal default |
| Boolean public parameter | `parameter integer` with canonical default `1` or `0` |
| Integer derived constant | `localparam integer` |
| Boolean derived constant | `localparam integer` with canonical `1`/`0` value |
| Named module instance | `Child #(.PARAM(expr)) instance (.port(signal))` |
| Named Boolean child binding | `.PARAM((boolean_expr) ? 1 : 0)` after integer-Boolean legalization; literals use `.PARAM(1)` or `.PARAM(0)` |
| Packed unsigned signal | `wire` or `reg [WIDTH-1:0]` |
| Combinational process | `always @*` |
| Clocked process | Edge-sensitive `always` |
| Structural loop | Named `generate`/`for` with `genvar` |
| Structural condition (implemented) | Named `generate`/`if` with explicit `== 1` Boolean references |
| Integer comparison (implemented) | `<`, `<=`, `>`, `>=`, `==` or `!=` after operand capability proof |
| Conditional integer value (implemented) | Parenthesized Boolean condition with Verilog-2001 `condition ? when_true : when_false` |
| Structural case (implemented) | Named `generate`/`case` with ascending signed-decimal choices and mandatory default |
| Runtime two-way mux process (implemented) | Named `always @*` block, one-bit `if` condition, complete blocking assignments and process-driven `output reg` targets |
| Synchronous register (implemented) | Named `always @(posedge clock)` block, active-high synchronous reset-to-zero, complete nonblocking assignments and process-driven `output reg` target |
| Asynchronous-reset register (implemented) | Named `always @(posedge clock or posedge reset)` block, active-high asynchronous reset-to-zero, reset priority, complete nonblocking assignments and process-driven `output reg` target |
| Synchronous enabled register (implemented) | Named `always @(posedge clock)` block with reset-priority active-high synchronous reset-to-zero, active-high capture, implicit disabled hold and nonblocking assignments |
| Asynchronous enabled register (implemented) | Named `always @(posedge clock or posedge reset)` block with immediate reset-priority active-high asynchronous reset-to-zero, active-high capture, implicit disabled hold and nonblocking assignments |
| Synchronous read-first single-port memory (implemented) | `reg [WIDTH-1:0] memory [0:DEPTH-1]` plus one guarded named positive-edge process with nonblocking read and optional whole-word write assignments |
| Portable address width (implemented) | Right-associated `value <= 2 ? 1 : value <= 4 ? 2 : ... : value <= 1073741824 ? 30 : 31` conditional expression |
| Logical record/vector port | Deterministically flattened scalar/packed ports |
| General-purpose `clog2` (deferred) | Must use a separately validated Verilog-2001 expression; never emit `$clog2` |
| Enum intent | Packed vector plus named local parameters |
| Parameterized memory | `reg [WIDTH-1:0] mem [0:DEPTH-1]` |

The emitter determines `wire` versus `reg` from driver semantics. Those words
are backend spellings and are not encoded in ParamRTL.

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
`address < DEPTH`, schedules the memory read first and conditionally schedules
one whole-word write with nonblocking assignments. Nonblocking semantics make
a same-address collision read-first. The explicit else schedules a target-width
zero for surplus addresses; nesting the write under the same guard makes those
addresses write-inert. Capability and validation prove positive width/depth,
whole-domain address capacity, exact data types, distinct controls and sole
ownership before spelling. No reset or initial block is emitted, so unwritten
in-range reads remain unspecified. Initialization, read enable, masks,
multiple ports/clocks and selectable collision modes remain forbidden.

Increment 21 legalizes `IntExpr.AddressWidth` without a helper function or
SystemVerilog system task. It emits one fully parenthesized right-associated
conditional chain covering thresholds `2^1` through `2^30`, returning widths
one through thirty and a final width of thirty-one. Capability validation has
already proved the operand lies within signed 32-bit `integer` range, while
target-independent ParamRTL validation has proved it positive. Every legal
value therefore has exact ceiling-log2 behavior and `DEPTH=1` retains a legal
one-bit port. The public memory address ABI inlines that expression in its
packed range; no externally overrideable sizing parameter, `$clog2`, constant
function or specialization is introduced. General logarithms and minimum/
maximum operations remain deferred.

The lowering is resource bounded. Five direct nested `AddressWidth` layers
collapse to the constant one over the already-proven positive signed-32-bit
domain. Other compositions use a shared capability/emitter cost plan and are
rejected with `V2001-ADDRESS-WIDTH-EXPANSION-TOO-LARGE` when their estimated
portable expansion exceeds 4096 expanded syntax nodes. This is a Verilog-2001
target cap, not a loss of the canonical ParamRTL expression.

## Flat ABI

The v1 public ABI permits scalar and packed-vector ports. Bundles, logical
vectors and protocol records are flattened using stable field order and naming.
The layout is retained in ParamRTL for a possible future SystemVerilog target.

Widths may depend on parameters. Port presence, name, direction and clock/reset
role may not depend on parameters. Designs requiring different interfaces use
separate static profiles or top modules.

## Forbidden output

Strict mode rejects SystemVerilog-only or ambiguous constructs, including:

- `logic`, `always_comb`, `always_ff` and `always_latch`;
- interfaces, modports, packages, structs, unions and typedefs;
- unpacked array ports and type parameters;
- classes, SVA/property syntax and SystemVerilog testbench constructs;
- `$clog2`, `$bits` and other non-Verilog-2001 sizing helpers;
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
