# Vec and loop publication options

`MorphAggregateOptions` configures single-source `MorphVerilog` generation:

```scala
import morphhdl._
import spinal.core._

val config = MorphAggregateOptions(
  SpinalConfig(targetDirectory = "rtl"),
  preserveConstantVecs = true,
  preserveConstantLoops = true,
  vecLayout = MorphAggregateOptions.UnpackedArray
)
MorphVerilog(config)(new MyTop)
```

Both preservation switches default to `false`; `vecLayout` defaults to
`PackedVector`. The helper copies the configuration instead of mutating it.
Ordinary concrete generation keeps its existing behavior with the switches off.
Parameterized Vec geometry already retained by MorphVerilog does not require
`preserveConstantVecs`. To use unpacked arrays for constant Vecs, enable that
switch as well.

With constant preservation enabled, `Vec(Bits(8 bits), 10)` keeps its width as
`8 * 10` rather than `80`. A packed declaration is equivalent to:

```verilog
wire [(8 * 10)-1:0] values;
```

With `UnpackedArray`, an internal Vec instead becomes:

```verilog
wire [(8)-1:0] values [0:(10)-1];
```

`Vec.fill(4, 10)(Bits(8 bits))` can use two unpacked dimensions:

```verilog
wire [(8)-1:0] values [0:(4)-1][0:(10)-1];
```

Verilog-2001 supports these internal multidimensional arrays. Module ports
remain one packed vector, with all dimension factors retained, because
Verilog-2001 does not support unpacked array ports or multidimensional packed
vectors. Element zero occupies the least significant packed slice; the last
nested dimension varies fastest. Whole Vec assignments and `asBits` retain that
ordering. Named-field publication can be combined with these options: internal
record fields become separate arrays and each port field remains packed.

The publisher preserves the underlying wire/register behavior, including
constant-array reset/control blocks and procedural assignment ordering. It emits
no memory-selection attributes or storage policy. Whether a register array
becomes flops or RAM depends on its access pattern and the synthesis tool; this
option makes no promise to prevent memory inference.

## Typed constant loops

Use the existing typed range API to retain a constant structural loop:

```scala
val x = in(Vec(Bits(8 bits), 10))
val y = out(Vec(Bits(8 bits), 10))
ElabFiniteRange.foreach(ElabInt.literal(10), "lanes") { i =>
  i(y) := ~i(x)
}
```

With `preserveConstantLoops = true`, this emits a `genvar` loop. The switch can
also retain a frontend typed constant procedural range:

```scala
import morphhdl.frontend._

when(enable) {
  (0 until HdlInt.literal(10)).named("lanes", "lane").foreach { i =>
    outputBits(i * HdlInt.literal(8), HdlInt.literal(8)) :=
      inputBits(i * HdlInt.literal(8), HdlInt.literal(8))
  }
}
```

These APIs retain the loop body and its index provenance. An ordinary Scala
`for (i <- 0 until 10)` still executes during elaboration and is unrolled.
The loop switch can retain the Vec operands needed by a typed loop even when
`preserveConstantVecs` is false. Empty typed ranges emit no body.

The existing typed-operation eligibility checks remain in force. In particular,
parameterized register-array dynamic writes retain their existing limitations
on extra reset/enable control; selecting unpacked layout does not expand that
symbolic control-flow support. Unsupported combinations fail rather than
silently using a witness size or dropping control logic. Concatenated array
write targets must belong to retained Vec geometry.

## Area collections with index branches

With the MorphHDL compiler plugin and `preserveConstantLoops`, a zero-based
ordinary Area collection containing `if (index == literal)` can retain one
structural template:

```scala
val inputs = in(Vec(Bool(), 3))
val outputs = out(Vec(Bool(), 3))
val adapters = for (index <- 0 until 3) yield new Area {
  val selected = if (index == 0) inputs(index) else !inputs(index)
  outputs(index) := selected
}
val first = out Bool()
first := adapters(0).selected
```

The loop emits a generate-for; index conditions emit nested generate-if/else.
Each iteration has independent registers and child instances. Bool-valued and
statement branches are supported, including branches nested in hardware `when`
with their captured defaults and assignment priority. Packed bit/slice access,
constant-width `B(index, width bits)` / `U(index, width bits)` tags, and
`UInt === index` retain the index rather than its elaboration witness. Index
widths must cover the complete finite domain. The fixed inner Scala loops remain
ordinary unrolled loops.

Static collection member access exports a common-scope hardware leaf through
one authenticated singleton branch. It must be valid for every admitted count.
The retained collection is not a general Scala `Seq`: escaping its Area objects,
arbitrary iteration and unsupported uses of the index are rejected. A
`zipWithIndex.foreach` containing only `.doc(...)` calls labels the retained
template once with `generated lane`; it cannot construct or alter hardware.

The explicit `ElabAreaCollection.tabulate(count, "adapters")` API also accepts
an authenticated parameterized `ElabInt` count. Its index supports
`selectBool`, `whenEqual`, `onlyEqual`, `packed`, `bits`, `uint`, and ordinary
finite Vec access; export a leaf using `collection.member(0)(_.selected)`.
The ordinary syntax bridge requires a Scala count and the supported equality
branch shape. Other ordinary Scala loops keep their existing semantics.

Generated hierarchy intentionally changes from individual `adapters_0`,
`adapters_1`, etc. declarations to indexed generate scopes with nested branch
labels. External hierarchical probes must follow the generated scopes; module
ports and functional behavior retain their source meaning. Turning loop
preservation off elaborates concrete iterations.

## Validation

Focused local tests exercise strict Verilog-2001 compilation, simulation and
Yosys synthesis for packed ports, multidimensional internal arrays, constant
register reset/enable behavior, supported parameterized writes with a depth
override, blocking dependencies, named record fields and typed loops. Existing
Vec and loop regressions are also run on both supported Scala versions.
Local result (2026-10-02): 177 cases across 13 suites pass on Scala 2.12.18
and 2.13.12, including 16 new aggregate/loop cases. Source-boundary checks and
native-audit negative controls also pass. Remote qualification remains paused
and is separate from these local checks.


## Enum debug strings

MorphVerilog suppresses simulation-only enum-to-string registers and their
conversion logic by default. This includes FSM `stateReg_string` and
`stateNext_string` helpers. Functional enum state registers remain ordinary
hardware. Enum values in report messages print their numeric encoding when
these helpers are disabled; assertion and parameter-domain checks remain enabled.

The option is `suppressEnumDebugStrings`, default `true`. No application change
is needed for the default. To restore enum names for waveform/report debugging:

```scala
import morphhdl.{MorphDebugOptions, MorphVerilog}

val config = MorphDebugOptions(
  SpinalConfig(targetDirectory = "rtl"),
  suppressEnumDebugStrings = false
)
MorphVerilog(config)(new MyTop)
```

`MorphDebugOptions` returns a configuration copy. The selection survives config
copies and applies to MorphVerilog's single-source, canonical-IR and compatibility
witness paths. Disabling suppression preserves the native setting, including an
explicit `withoutEnumString()` request. Ordinary SpinalVerilog defaults are
unchanged. The same option works with combined and per-component publication.

## Ordered priority and conditional prefix operations

`ElabProcess` provides two explicit combinational operations for patterns that
cannot be expressed with equality-only `GenIndex.whenSelected`:

```scala
import spinal.core._

val incomingBytes = ElabProcess.highestSetBitPlusOne(
  incomingKeepLanes, ElabInt.literal(32), ElabInt.literal(8))
val appendWord = ElabProcess.prefixCopy(
  shiftedInput, take, ElabInt.literal(32), ElabInt.literal(8))
```

`highestSetBitPlusOne` returns zero for an empty mask and the highest selected
bit's position plus one otherwise, including sparse masks. `prefixCopy` copies
elements whose unsigned index is less than `take`, with element zero in the least
significant slice; all other bits remain zero. Unknown/high-impedance predicates
use Verilog `if` semantics and do not select a write.

MorphVerilog emits each operation as one bounded procedural loop in its owning
combinational process, with an explicit zero default and blocking assignments.
The APIs retain loops even for literal counts. Ordinary SpinalVerilog uses the
complete concrete unrolled algorithms. The returned values are expressions;
do not assign additional drivers to them.

Counts and element widths must remain positive throughout their authenticated
parameter domains. Source width must equal the declared mask count or packed
`count * elementWidth` geometry. Priority result width must be 1..32 bits and
its smallest admitted width must represent the largest admitted count. `take`
is an owner-local UInt with a fixed width of 1..32 bits. Construct these
operations at component scope. Invalid geometry, ownership, competing writes,
and combinational target feedback are rejected.

These convenience operations are complemented by the scoped builder described
below. General scalar writes inside `HdlRange.foreach`, or converting its scoped index through
`U(index.asElabInt, ...)`, remain unsupported; the index is private to each
operation and cannot escape its process. Existing `whenSelected` loops retain
their original body restrictions. Their feedback validation stops at registers,
so a registered consumer of the assembled result is permitted without allowing
combinational or cross-iteration target reads.

### Canonical schema conflict diagnostics

When one native module identity maps to incompatible retained schemas, the
`SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-CANONICAL-SCHEMA-CONFLICT` diagnostic
identifies two conflicting component instance paths and the first differing
parameter, port field or logical Vec entry. Parameter summaries show defaults
and complete domains. Port differences distinguish direction, type, concrete
width and retained expression metadata. Missing entries are reported explicitly.

The diagnostic chooses parameter and port names in sorted order and bounds each
reported value to 240 characters. It avoids printing opaque expression identity
objects. Equal printed expressions can still carry different authenticated
metadata; the diagnostic states that distinction. These summaries never decide
whether definitions can share: the existing exact schema equality and formal
ownership checks remain authoritative.

## Scoped procedural loops

Use `ElabProcess.uintLoop(count, width)` or `ElabProcess.bitsLoop(count, width)`
when the loop needs ordered scalar writes or general conditional slice writes:

```scala
import spinal.core._

val incomingBytes = ElabProcess.uintLoop(lanes, ElabInt.literal(8)) { p =>
  p.when(p.bit(incomingKeepLanes, p.index)) {
    p.assign((p.index + 1).asUInt(ElabInt.literal(8)))
  }
}

val appendWord = ElabProcess.bitsLoop(lanes, lanes * byteWidth) { p =>
  p.when(p.index.asUInt(ElabInt.literal(32)) < p.uint(take)) {
    p.assignSlice(p.index, byteWidth, p.slice(shiftedInput, p.index, byteWidth))
  }
}
```

Each call owns one combinational result, starts with a whole-result zero default,
and emits one bounded procedural loop. Iterations and writes execute in source
order with blocking assignments; later writes take priority. `when` and nested
`ifElse(condition)(yes)(no)` retain Verilog `if` four-state behavior. Counts may
be zero: no iterations execute and the result remains zero. Ordinary
SpinalVerilog elaborates the complete concrete unrolled implementation.

The builder supports owner-local `uint`, `bits` and `bool` inputs, indexed `bit`
and `slice` reads, whole-result `assign`, and `assignSlice`. Values support
unsigned `<`, `<=`, `===`, `=/=` comparisons and width-matched `&`, `|`, `^`.
Predicates support `!`, `&&`, `||`. Comparisons explicitly zero-extend operands.
`index + constant` retains a proven nonnegative offset; `asUInt(width)` produces
an unsigned **scoped value**, not a native UInt or Scala integer. Its width must
be 1..32, with the smallest admitted width representing the largest admitted
index over the complete domain. Geometry needs authenticated expressions;
indexed bounds must be provable for every legal parameter value. Counts and
geometry stay below the signed procedural-index limit, and result/element
widths remain positive. Packed input widths must also remain positive. Only inputs
referenced by recorded actions count as dependencies; empty bodies and bodies
without a hardware dependency are rejected.

Create native source expressions before entering the callback. Inside it, use
only builder operations: native declarations, child creation, native assignments
and native process construction are rejected. Handles cannot escape the callback
or cross builders/components. Each result is an expression, so extra writers and
combinational target feedback remain errors. These APIs do not change ordinary
Scala-loop unrolling or relax `HdlRange.foreach`/`whenSelected` restrictions.

Captured process expressions retain exact alias/driver lineage. Native pruning
may substitute an observed owner-local alias with its captured dependency;
replacement drivers, changed literal/slice geometry, foreign declarations and
register substitution are rejected. Public input boundaries remain identifiable
even when their assignment appears later in Scala. Registers stop dependency
traversal. No signal-name matching grants lineage authority.

## Optional publication decision report

```scala
val report = MorphVerilog.generateWithPublicationReport(config) {
  new MyTop
}
val json = report.toJson
```

This opt-in entry point returns the ordinary generation report in `generated`
and an authenticated module/instance inventory in `modules`. The deterministic
JSON lists native sharing groups, retained parameter uses (including transitive
child bindings), retained procedural/structural loops, logical Vec schemas and
aggregate preservation settings. Generated metadata omits timestamps, output-directory paths and
JVM identity hashes; source locations and diagnostic excerpts can contain authored
paths. Reporting does not change emitted RTL, and callers choose
whether and where to save the JSON. Capture state is generation-local and is
released on failures.

The report describes successful native publication decisions. Ordinary Scala
loops have already executed before capture, so it explicitly marks their origins
and iteration counts as unobservable rather than inventing unrolling evidence.
Different native groups remain separate; the report does not infer semantic
compatibility from Scala class names or rendered schemas.

The compact regression matrix covers literal/symbolic and zero/boundary counts,
packed/unpacked options, combined/per-component output, reordered instances,
width-dependent and scalar-only child formals, non-default overrides, ordinary
fallback, alias pruning and rejection controls. Simulation includes sparse masks,
every 8-bit take value, randomized data and X/Z predicates. Lint, synthesis,
no-latch/driver checks and an unrolled-control equivalence proof accompany it on
both supported Scala versions. Full branch qualification remains a separate gate.

### Multiple outputs, defaults and ordered folds

`ElabProcess.outputsLoop(count, outputs)` returns a `Vector[Bits]`. Each output
specifies its authenticated width and default. The logical outputs share one
packed local carrier and one owning combinational process; output zero occupies
the least significant field. Returned fields retain their symbolic offsets and
widths through publication, including when earlier fields have variable widths.

```scala
val results = ElabProcess.outputsLoop(lanes, Seq(
  ElabScopedProcess.Output(ElabInt.literal(16), default = 7),
  ElabScopedProcess.Output.from(seed)
)) { p =>
  val lane = p.index
  val sample = p.slice(data, lane, byteWidth)
  p.when(p.bool(enable)) {
    p.output(0).assign(p.output(0).current + sample.extend(ElabInt.literal(16)))
    p.output(1).assign(p.output(1).current ^ p.bits(mask))
  }
}
```

`Output.from(seed)` uses an owner-local Bits, UInt or SInt value as the hardware
default on every evaluation. Literal defaults must fit the smallest legal width;
`Output(width, default = -7, signed = true)` supplies a signed default. Returned
fields are Bits; use `.asUInt`/`.asSInt` at the consuming boundary as appropriate.
`p.current` is shorthand for `p.output(0).current`.

Only these explicit accumulator reads refer to the process's evolving state.
Each read observes preceding blocking writes, including earlier iterations and
writes to other outputs. Defaults initialize every field before any iteration.
Ordinary input signals feeding back from the result remain illegal. Constant-only
programs and empty bodies remain rejected; hardware defaults count as real input
dependencies. Unknown/high-impedance conditions retain Verilog `if` behavior.

Scoped values support same-width `+`, `-` and `*`, with wrapping modulo
`2^width`, plus signed/unsigned comparisons and bitwise operations. Operand widths
and signedness must match for arithmetic and bitwise operations. Use
`.extend(width)` before arithmetic to retain more result bits; extension uses the
operand's signedness and cannot narrow. `p.sint(value)`, `.asSigned` and
`.asUnsigned` make interpretation explicit. Mixed-signedness comparisons are
rejected. `p.literal(value, width, signed = ...)` creates a sized scoped literal.
This API deliberately specifies wrapping operations rather than inferring
saturation or silently selecting a larger result width.

### Nested loops and correlated bounds

`p.foreach(count) { index => ... }` emits a nested bounded procedural loop.
Capture `val outer = p.index` before entering it when both indices are needed.
Indices support affine compositions such as `outer * columns + inner`, where
`columns` is an authenticated positive ElabInt, and nonnegative constant offsets.
Every nested index, value and predicate derived from it must remain inside its
lexical callback. Escaped handles, foreign owners, negative counts and index
arithmetic outside the signed procedural-index domain are rejected.

```scala
val row = p.index
p.foreach(columns) { column =>
  val position = row * columns + column
  p.when(p.bit(mask, position)) {
    p.output(0).assign(p.output(0).current + position.asUInt(sumWidth))
  }
}
```

Bounds use the existing authenticated expression-domain engine to prove
`available - required >= 0`, preserving shared parameter-root identity. Thus
`width - 1` iterations reading `index + 1` from a `width`-bit source can be
accepted across the complete legal domain. An independent parameter with the
same default does not provide that proof. Unprovable accesses remain errors;
there is no witness-only fallback or new unchecked algebra parser. Zero counts
execute no writes and retain the defaults. Nested loop-index variables receive
unconditional initial values to avoid incidental latch inference when a loop is
inside a conditional.

### Structured publication failures and separation reasons

`MorphVerilog.tryGenerateWithPublicationReport(config)(component)` returns
`Either[MorphPublicationFailure, MorphPublicationReport]`. Failures retain the
ordinary `MorphVerilogFailure` and add diagnostics with codes, bounded details,
instance paths where captured, and available source locations. `toJson` serializes
these diagnostics; capture state is released on failure. The throwing generation
APIs retain their existing behavior.

Successful reports now include `separations`. For multiple native definitions
from the same construction class, each is compared with the first named
definition. The report identifies the first differing retained schema or native
comparison section (attributes, declarations, logic, and so on), with bounded
excerpts. Explicit separate-name policies are distinguished where no trace differs.
These observations do not participate in equality or authorize merging. A class
name only narrows diagnostic candidates. Source locations may be unavailable;
the report does not invent them or claim a semantic root cause beyond the
captured difference. Diagnostic text can include authored source content.

Regression coverage includes exhaustive small matrix masks, signed boundary
values, hardware and literal defaults, zero outer/inner counts, X/Z conditions,
non-default parameter values, nested-index escape rejection, unrelated-root
bounds rejection, and tampered output-view/default/write identities. The emitted
fold is also compared against an independently authored combinational oracle.

Nested loop bounds and index scales used only as scalar formals retain independent
instance bindings. Their defaults are normalized for one generic definition;
reversed instance order, overrides and combined/per-component output are covered.
