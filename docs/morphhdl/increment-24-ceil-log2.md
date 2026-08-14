# Increment 24: target-neutral mathematical ceiling-log2

Increment 24 adds a general parameter-expression `ceilLog2` operation while
retaining the strict IEEE 1364-2001 target and the sixteen-file public fixture
inventory. It also replaces Increment 21's long portable address-width
conditional chain with the same shared module-local constant function.

## Why strict mode does not emit `$clog2`

`$clog2(PARAM)` is concise and synthesizable. It was standardized in IEEE
1364-2005 Verilog; it is not SystemVerilog-only. MorphHDL does not emit it
because the current compatibility baseline is deliberately IEEE 1364-2001,
not because of a synthesis limitation. Some 2001-mode tools accept `$clog2` as
an extension, but others correctly reject it.

Increment 24 keeps the existing baseline and emits an IEEE 1364-2001 constant
function instead. The helper is evaluated during HDL elaboration, so it does
not create a counter, shifter or other runtime hardware. The frontend and
ParamRTL semantic node remain independent of that backend spelling.

## Frontend contract

`HdlInt` exposes one unary operation:

```scala
val laneIndexWidth = lanes.ceilLog2
```

Its mathematical semantics for a positive integer `n` are:

```text
ceilLog2(1) = 0
ceilLog2(n) = (n - 1).bitLength, for n > 1
```

The concrete witness is evaluated exactly with arbitrary-precision integers.
The symbolic result retains the complete operand, declaration identities,
source origin and lexical-scope provenance in `IntExpr.CeilLog2`. A
nonpositive witness fails immediately with
`MORPH-FRONTEND-CEIL-LOG2-WITNESS-NONPOSITIVE`; a generate-index-dependent
operand remains outside this tranche.

This operation is deliberately distinct from `HdlInt.addressWidth`:

- `HdlInt.ceilLog2` is mathematical and returns zero for one;
- `HdlInt.addressWidth` describes a legal packed address port and returns at
  least one bit.

A caller that uses `ceilLog2` as a packed width must therefore compose it with
an independently positive expression when zero is possible.

## ParamRTL semantics

Target-independent validation proves that the complete legal operand interval
is positive, rather than trusting only its default. Failure uses
`PRTL-CEIL-LOG2-OPERAND-NOT-PROVEN-POSITIVE`. Exact evaluation, parameter
substitution, dependency discovery, expression equivalence and deterministic
local-parameter ordering recurse through the operand and retain the semantic
node.

Because `ceilLog2` is monotonic on positive integers, interval analysis maps
`[minimum, maximum]` to
`[ceilLog2(minimum), ceilLog2(maximum)]`. Literal operands normalize only after
their source expression has passed validation. No default-based
specialization may erase a dynamic parameter dependency.

The strict backend continues to represent parameter expressions in the
signed-32-bit `integer` domain; logarithm operands are additionally proven
positive. Capability validation therefore rejects a complete logarithm-operand
domain above `2147483647` before emission. The constant function uses Verilog
`integer` input and loop state, which exactly covers that already-proven target
domain. A wider parameter domain requires a separately widened target contract
rather than silent truncation.

## Strict Verilog-2001 lowering

Each module that consumes `CeilLog2` or `AddressWidth` emits exactly one
module-local helper, regardless of the number of calls:

```verilog
function integer clog2;
  input integer value;
  input integer minimum_result;
  integer remaining;
  begin
    clog2 = 0;
    for (remaining = value - 1; remaining > 0; remaining = remaining >> 1) begin
      clog2 = clog2 + 1;
    end
    if (clog2 < minimum_result) begin
      clog2 = minimum_result;
    end
  end
endfunction
```

Mathematical ceiling-log2 emits a direct constant-function call:

```verilog
localparam integer LANE_INDEX_WIDTH = clog2(LANES, 0);
```

Address width adds its distinct one-bit floor:

```verilog
clog2(DEPTH, 1)
```

`clog2` is the natural name a handwritten Verilog-2001 implementation would
normally use. Before emission, the backend collects every identifier visible
in the module. If `clog2` is already occupied, it deterministically selects the
first free `clog2_1`, `clog2_2`, and so on, and uses that name consistently for
the declaration and every call in that module. Functions in separate modules
may safely reuse the same local name. The signed-32 target capability ceiling
prevents a wider operand from reaching the helper.
The backend supplies only literal zero or one as `minimum_result`. Encoding the
floor inside the helper keeps each arbitrary operand expression present once,
so nested `AddressWidth`/`CeilLog2` compositions grow linearly and require no
log-specific source-expansion cap.

Increment 27 later renames the backend-private generated helper to the natural
module-local spelling `clog2`, with deterministic `clog2_1`, `clog2_2`, and so
on for collisions. This changes generated text, reviewed goldens and hashes,
but does not change the Scala/ParamRTL API or ceiling-log2 semantics.

The old 31-comparison chain, native `$clog2`, an externally overrideable width
parameter, generated runtime logic and configuration-specialized module copies
are all forbidden. Constant-function calls remain ordinary target spelling;
ParamRTL retains `CeilLog2` and `AddressWidth` as distinct target-neutral
semantic nodes.

## Public fixtures and executable gates

The existing `derived_width.v` fixture adds:

```scala
val laneIndexWidth = localParam("LANE_INDEX_WIDTH", lanes.ceilLog2)
val paddedWidth = localParam(
  "PADDED_WIDTH",
  (totalWidth + clampedPadding).max(HdlInt.literal(4)) + laneIndexWidth
)
```

The same emitted module is elaborated at widths 37, 4, 20, 29, 25 and 7.
Lane counts one, two, three and four exercise `ceilLog2` results zero, one and
two, including exact power-of-two and non-power-of-two boundaries. The
one-lane port width exposes the required zero result: changing
`ceilLog2(1)` to one changes the expected four-bit shape.

The existing `single_port_memory.v` fixture keeps depths one, two, three and
five and now derives its one-, one-, two- and three-bit address widths through
the same helper plus the explicit one-bit address floor.

The release gate requires:

- focused frontend, ParamRTL and backend suites on Scala 2.12 and 2.13;
- byte-identical normal and reverse construction with exactly sixteen files;
- exactly one canonical helper in each module that needs logarithmic sizing
  and no helper in the other generated modules;
- repository-wide generated-RTL rejection of native `$clog2`, `$bits`, the old
  31-threshold chain and SystemVerilog-only syntax;
- Verilator IEEE 1364-2001 lint and Icarus simulation at every reviewed
  one/two/three/four-lane and one/two/three/five-depth boundary;
- Yosys synthesis with exact port widths and no helper-derived runtime cells;
- negative mutations for helper initialization, decrement, shift, increment,
  input/loop declarations, one-bit address floor, mathematical zero boundary and
  default specialization.

Every inherited concrete SpinalHDL validation phase still runs before the
independent ParamRTL and target-capability gates. Increment 24 changes only the
parameter-expression algebra and constant-expression target lowering; it does
not add comment support, raw HDL or a second output profile.

## Recommended next increment

Increment 25 should add one bounded parameterized synchronous counter vertical
slice. Public limit/count parameters should derive storage width through the
existing `addressWidth`/`ceilLog2` algebra, while the runtime tranche adds only
the required increment, terminal comparison, rollover, reset, enable and hold
semantics. A concrete Spinal `Counter` witness plus power-of-two,
non-power-of-two and awkward ParamRTL/Verilog overrides would make the new
sizing operation useful without jumping directly to a broad FIFO feature.

The Increment 25 review made one prerequisite explicit: the existing
shared-address single-port memory cannot support simultaneous FIFO push and
pop at different locations. Increment 26 therefore adds a bounded
single-clock synchronous 1R1W simple-dual-port memory with independent read
and write addresses. A bounded synchronous FIFO follows in Increment 27.
