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
and nested process construction are rejected. Handles cannot escape the callback
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
aggregate preservation settings. It contains no timestamps, filesystem paths or
JVM identity hashes. Reporting does not change emitted RTL, and callers choose
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
