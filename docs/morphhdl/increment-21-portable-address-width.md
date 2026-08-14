# Increment 21: portable depth-derived address width

Increment 21 closes the address-geometry gap left by the first memory tranche.
It adds one typed ceiling-log2 operation, keeps its operand and result in
ParamRTL, and lowers it to strict Verilog-2001 without `$clog2`, a helper
function or an externally overrideable sizing parameter.

## Frontend contract

`HdlInt.addressWidth` returns another guarded `HdlInt`:

```scala
val depth = HdlInt.param("DEPTH", default = 5, min = 1, max = 5)
val addressType = packedBits(depth.addressWidth)
```

Its concrete witness is `max(1, ceil(log2(depth)))`. A nonpositive witness
fails at the call site, and a generate-index-dependent operand is rejected
because port geometry must be loop invariant in the current frontend. The
symbolic result retains every public, local, Boolean-select and source-origin
dependency in one `IntExpr.AddressWidth(value)` node. It may be used anywhere
the implemented integer expression surface already accepts a loop-invariant
value, including packed widths, local parameters and child bindings.

## ParamRTL semantics

For a mathematical integer `value`, the canonical result is:

```text
AddressWidth(value) = max(1, ceil(log2(value)))
```

The complete operand domain must be positive. A safe default does not hide an
invalid legal override. ParamRTL keeps that semantic rule target neutral; the
strict Verilog backend separately requires a finite operand domain no greater
than `Int.MaxValue`, matching its signed 32-bit `integer` capability and the
current public frontend parameter ceiling.

Analysis maps exact defaults and interval endpoints monotonically through the
operation. Substitution, equivalence, dependency discovery and local ordering
retain the semantic node. Public, local and parent-bound contexts therefore
evaluate the same operation instead of specializing to the concrete witness.

The memory-capacity proof recognizes exact correlation between
`AddressWidth(depth)` and that same memory `depth`, including expressions that
normalize to the same tree. This proves every legal depth has enough address
encodings even though an unconditional interval comparison would see a
one-bit minimum width and a five-word maximum depth. A fixed or unrelated
address expression still uses the conservative whole-domain capacity proof.

## Strict Verilog-2001 lowering

IEEE 1364-2001 has no portable ceiling-log2 system function. The backend emits
one deterministic right-associated conditional expression:

```verilog
(DEPTH <= 2) ? 1 :
((DEPTH <= 4) ? 2 :
 ...
 ((DEPTH <= 1073741824) ? 30 : 31))
```

Every threshold from `2^1` through `2^30` is present exactly once. The result
is one through thirty at those thresholds and thirty-one otherwise. Complete
parenthesization makes precedence independent of use as a packed width, local
value or binding. Capability verification proves the signed-32-bit domain
before rendering, so the final branch covers every permitted operand.

Portable lowering is deliberately bounded. Repeated direct nesting is
algebraically flattened over the proven positive signed-32-bit domain; five or
more direct `AddressWidth` layers have the constant result one. For other
compositions, capability verification and emission share one lowering plan and
node-cost estimate. An expression estimated to expand beyond 4096 expanded
syntax nodes fails with `V2001-ADDRESS-WIDTH-EXPANSION-TOO-LARGE` before rendering.
This prevents adversarial expression growth while leaving the semantic
ParamRTL node target neutral.

The public memory fixture inlines that expression in its ANSI port range:

```verilog
input wire [((DEPTH <= 2) ? 1 : ...)-1:0] address
```

There is no `ADDRESS_WIDTH` public parameter: adding one would let a caller
override the derived ABI independently of `DEPTH`. There is no local parameter
because an ANSI port cannot depend on a body declaration in strict
Verilog-2001. `$clog2`, `$bits`, SystemVerilog syntax and constant functions
remain absent.

## Upgraded public memory contract

`SinglePortMemory` remains the sixteenth generated fixture and keeps:

- independent `WIDTH=8` and `DEPTH=5` defaults;
- `WIDTH` bounded from 1 through 32 and `DEPTH` from 1 through 5;
- one positive-edge synchronous read-first whole-word port;
- synchronous zero reads and ignored writes for `address >= DEPTH`;
- unspecified unwritten in-range storage;
- no reset, initialization, read enable, write mask or additional port.

Only the packed address ABI changes. The default 8x5 instance has three
address bits, the awkward 5x3 instance has two and the minimum 1x1 instance
has one. `DEPTH=2` also has one bit, explicitly covering both sides of the
minimum-width rule. The concrete Spinal witness remains the default 8x5
read-first memory and still executes every inherited validation phase.

## Executable evidence

The public generator still produces exactly sixteen files in normal and
reverse-construction order, and the two directories must remain byte
identical. `single_port_memory.v` must match its reviewed golden byte for byte.

The strict gate requires:

- an exact source match for the complete portable conditional address range;
- a repository-wide rejection of `$clog2` and other SystemVerilog sizing
  helpers;
- capability tests for direct-nesting flattening and the limit of 4096
  expanded syntax nodes;
- Verilator strict 1364-2001 lint at depths 5, 3, 2 and 1;
- Icarus simulation of simultaneous 8x5, 5x3 and 1x1 instances;
- Yosys checks for address port width, memory `ABITS`, comparator `A_WIDTH`,
  requested depth/element width, read-first metadata and positive-edge state.
  Depths 1, 3 and 5 retain the guarded asynchronous-memory-read plus output
  register form; the full-code `DEPTH=2` case is proved as the equivalent
  absorbed synchronous memory read. Both forms require inactive asynchronous
  and synchronous read-reset connections and uninitialized reset metadata,
  exact one-bit read-control connections, exact full-memory unknown `INIT`,
  zero wide-continuation metadata and the exact active-high in-range
  whole-word write guard;
- the twelve Increment 20 negative checks for collision, clock, guard,
  initialization, depth and memory metadata, plus empty/truncated `INIT`,
  read/write wide-continuation, empty read-enable/read-clock, widened reset,
  short/signed comparator right-hand side, and full-domain read-clock enable,
  polarity, asynchronous-reset and synchronous-reset mutations;
- an additional fixed-three-bit mutation checked at `DEPTH=3`, proving a
  source edit cannot bypass the derived two-bit ABI.

The memory-only Verilator `WIDTHEXPAND` waiver remains scoped to comparing a
packed address with Verilog's 32-bit `integer DEPTH`. The old `WIDTHTRUNC`
waiver is removed because the address now has the exact depth-derived index
width. General-purpose logarithms, minimum/maximum expressions and wider
memory policies remain outside this increment.

## Validation parity

This tranche extends the existing partial `PhaseInferWidth` adaptation but
does not claim a new inherited phase as fully implemented. Concrete validation
still uses the shared Spinal phase plan. ParamRTL independently proves the
portable expression's complete domain, exact boundary values, dependency and
binding contexts, target capability and correlation with memory depth.

## Recommended next increment

Increment 22 should add one active-high whole-word read enable to this same
single-port memory, with a deliberately narrow policy: writes remain governed
only by the existing write enable and in-range guard; read enable high performs
the current synchronous read/zero behavior; read enable low holds
`read_data`, including during writes. When both enables are high at one valid
address, collision remains read-first. Keep reset, initialization, byte masks,
additional ports, alternate collision modes and selectable polarity deferred.
