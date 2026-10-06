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
