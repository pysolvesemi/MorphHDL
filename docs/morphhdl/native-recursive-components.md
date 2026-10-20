# Native recursive interfaces and symbolic array publication (CA-046–CA-050)

`ElabRecursiveComponent` declares a direct-recursion boundary inside an ordinary
Scala `Component`. Its interface constructor is written once. A recursive call
constructs only the typed interface and retains a reference to the exact owning
definition; it does not execute the Component constructor again. Application code
does not declare a BlackBox, repeat ports, choose a BlackBox definition name, or
duplicate generic associations.

```scala
@dontName val count = ElabFormalParameter(actualCount, "COUNT", 1, capacity)
@dontName val initialCount = ElabFormalParameter(actualCount, "INITIAL_COUNT", 1, capacity)
@dontName val mask = ElabFormalParameter(actualMask, "PIPELINE_MASK", 0, maximumMask)

@dontName val recursive = ElabRecursiveComponent(Vector(count, initialCount, mask)) {
  values => new Ports(elementType, values(0), wordBits)
}
val io = recursive.ports

// Inside the retained COUNT > 1 branch:
val child = recursive(count -> ((count + 1) / 2))
child.ports.terms := nextTerms
child.ports.clk := io.clk
child.ports.reset := io.reset
child.ports.softReset := io.softReset
child.ports.enable := io.enable
io.result := child.ports.result
```

`Ports` is a normal Bundle constructor with directed fields. Its field names
form the module interface, without an added `ports` prefix. Include clocks,
reset, soft reset and enables in that interface when the implementation uses
them. The complete interface is checked at publication; implicit extra clock
ports cannot silently disappear from recursive calls. The port constructor must
create fresh ports and must not implement the Component body.

The declaration takes the complete inventory of direct definition formals.
Override keys are their exact `ElabInt` identities. Omitted actuals preserve the
corresponding formal, so `INITIAL_COUNT` and `PIPELINE_MASK` remain distinct from
the decreasing `COUNT`. Duplicate, foreign or expression-valued keys are rejected.
Calls must stay inside their declaring Component. Each emitted edge still needs
the existing final-owner proof of a guarded, strictly decreasing metric, legal
actual domains and correctly substituted port geometry. Definition/instance/port
identities are checked independently of matching names. Legacy explicit
BlackBox references retain their existing validation.

The migrated [`RecursiveUnsignedSum`](../../lib/src/main/scala/spinal/lib/RecursiveUnsignedSum.scala)
uses this boundary for two guarded children. Its unsigned extension/truncation
and modular addition contract is unchanged. The clocked
[`NativeRecursiveTreeFixture`](../../morphhdl/src/test/scala/nativeapplication/NativeRecursiveTreeFixture.scala)
shows one-child adjacent-pair recursion, signed/composite payloads, level bridges
and registers. The body supplies the operation and state placement; the recursion
boundary neither substitutes an algorithm nor inserts registers.

## A retained pair/tail loop

The native finite-index API provides an explicit structural comparison:

```scala
ElabFiniteRange.foreach((count + 1) / 2, "combine") { i =>
  i.whenAffineLessThan(2, 1, count, "pair") {
    i(combined) := operation(i.affine(lanes, 2, 0), i.affine(lanes, 2, 1))
  } {
    i(combined) := i.affine(lanes, 2, 0)
  }
}
```

Both alternatives describe generate-time hardware. The proof enumerates the
bounded, correlated `(parameter value, iteration)` domain before admitting an
affine read. It retains the exact guard path, loop token, Vec geometry and native
block identity for publication. A proof narrowed during capture is replayed from
its retained table; it is not widened using a later ambient domain. Arithmetic
must fit the Verilog integer domain and the existing finite proof budget.
An alternative with no reachable parameter/index pair is not elaborated. This
uses the complete retained domain, so a branch absent only at the source default
is still captured for other legal overrides.

For the unsigned difference fixture, native emission includes:

```verilog
genvar i;
// Within the COUNT > 1 generate branch:
for (i = 0; i < ((COUNT + 1) / 2); i = i + 1) begin : g_combine_2_1
  if (((2 * i + 1) < COUNT)) begin : pair_true
    assign combined[i] = (lanes[(2 * i)] - lanes[((2 * i) + 1)]);
  end else begin : pair_false
    assign combined[i] = lanes[(2 * i)];
  end
end
```

The subsequent pipeline loop applies the bridge to every combined value,
including the odd tail. There is no separate `paired` array or capacity-unrolled
pair/tail operation. This structure comes from native capture, not RTL cleanup.

The initial frontend capability probe rejected `(i * 2 + 1) < count` with
`MORPH-FRONTEND-GENINDEX-CONSUMER-UNSUPPORTED`. The new supported spelling is the
native `whenAffineLessThan` method; arbitrary frontend GenIndex comparisons are
not introduced by this change. The new guard currently requires a positive
affine coefficient, nonnegative offset, and a symbolic bound correlated with
the loop count through one exact parameter root. Independently rooted bounds,
unguarded or incorrectly guarded out-of-range reads, incomplete writes and
competing assignments remain errors.

Guarded affine reads require a separately driven source array. The new bounds
proof does not authorize same-loop array dependencies, even an acyclic forward
chain; those are rejected rather than inferred from witness edges. The existing
previous-index API and its dependency checks remain unchanged.

## Independent word geometry and direct array drivers

`COUNT` and `DATA_WIDTH` may be independent native formals. Declare the complete
formal inventory before component publication. Packed conversion retains the
product of the exact element-width and depth expressions; it does not infer a
word width from a construction witness. For example:

```scala
val terms = in Bits((count * width) bits)
val lanes = Vec(UInt(width bits), count)
lanes.assignFromBits(terms)
```

The [`NativeWidthRecursiveTree`](../../morphhdl/src/test/scala/nativeapplication/NativeWidthRecursiveTree.scala)
fixture carries that width through each pair operation, optional pipeline bank,
and recursive child. Its emitted interface and child bindings include:

```verilog
input wire [(DATA_WIDTH * COUNT)-1:0] terms;
output reg [DATA_WIDTH-1:0] result;
// Inside the retained step branch:
WidthSumTree #(
  .COUNT(((COUNT + 1) / 2)),
  .DATA_WIDTH(DATA_WIDTH),
  .INITIAL_COUNT(INITIAL_COUNT),
  .PIPELINE_MASK(PIPELINE_MASK)
) reference ( /* native connections */ );
```

Each unsigned operation is modulo `2**DATA_WIDTH`; the odd term is unchanged.
The difference fixture preserves adjacent subtraction order. Omitted pipeline
mask means no built-in registers; an explicit zero mask retains the parameter
while selecting combinational paths. Nonzero mask bits enable per-level registers
with the authored asynchronous reset and clock enable. Composite payload geometry
in the separate fixture remains explicit, rather than implicitly scaled by a
single word-width parameter.

Complete static packed-word assignments under generate branches are checked over
their active parameter domain. Inactive words beyond the default carrier width
do not supply native coverage or trigger witness-only overlap assertions. This
path requires exact, complete, disjoint coverage, one correlated width root and
retained assignment ownership. Nested choices under other parameters must prove
identical coverage on both alternatives. Missing words, overlapping active writes
and inadequate guards remain errors. General runtime conditional coverage and
unions of unrelated multi-root guards are not inferred here; simulation-only
legality assertions never narrow the proof domain.

Combinational Vec drivers with proven native ownership and complete continuous
assignments can publish directly into the array. Redundant scalar/view pairs are
omitted. Actual registers, runtime conditional event dependencies, required signed
read aliases and native `_zz_` helpers retain their existing semantics.

## Compatibility and validation

Ordinary Scala constructor recursion is not intercepted. Use the explicit typed
boundary. Mutual/non-local recursion and arbitrary recursive algorithms remain
unsupported. The existing bounded-recursion policy still requires one decreasing
metric and preserved other bindings. Supported payload/operation/register cases
remain subject to the native structural compiler's existing checks; this API
does not grant permission for foreign captures or arbitrary callbacks.

`Vec.reduceBalancedTree` and its native publication implementation are unchanged.
In particular, its operator order, odd-tail rules, level bridges and register
placement are not redirected through this opt-in API.

The structural/process workflow runs both supported Scala versions, the
recursive-interface and negative suites, existing bounded-recursion and partition
checks, the unsigned-sum override checker, and
[`check-typed-recursive-component.py`](../../morphhdl/scripts/check-typed-recursive-component.py).
The latter compares ordinary and retained-pair-loop fixtures with an independent
adjacent-pair cycle model for every COUNT 1..8 and pipeline mask 0..7, both source
defaults 1 and 5, reset/soft-reset/enable stalls, custom bridges and signed or
composite payloads. It performs Verilog-2001 compilation, lint, synthesis and
combinational SAT checks where no custom register bridge is present. The sum
checker separately covers COUNT through 17, width conversion/overflow and X/Z.
[`check-symbolic-recursive-width.py`](../../morphhdl/scripts/check-symbolic-recursive-width.py)
checks independent COUNT overrides 1/2/3/5/7/8/15/16 and DATA_WIDTH overrides
1/8/32/36/64, absent and explicit pipeline masks, sum and subtraction, defaults
1 and 8, reset/enable stalls, overflow and four-state combinational inputs. It
adds strict Verilog-2001 compilation, lint, recursive synthesis and combinational
SAT comparison against an independently constructed flat adjacent-pair reference.
Larger finite domains remain subject to the proof budget; these fixtures do not
claim exhaustive testing of arbitrary user programs.

Application Scala, compiler pins, delivered RTL and the Rust cleanup tool are
outside this compiler change. Application migration tests use external copies.
Exact candidate identities, commands, artifact hashes and qualification status
belong to the increment's qualification checkpoint; a local pass is not remote
or full-CI closure.
