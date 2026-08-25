#!/usr/bin/env python3
from __future__ import annotations

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def replace_once(path: str, old: str, new: str) -> None:
    target = ROOT / path
    content = target.read_text(encoding="utf-8")
    count = content.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one replacement target, found {count}")
    target.write_text(content.replace(old, new, 1), encoding="utf-8")


def write(path: str, content: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


write(
    "morphruntime/src/main/scala/spinal/core/ExternalNativeIntFormalComponent.scala",
    '''package spinal.core

/**
  * Generic low-level boundary for a native child component whose ordinary
  * Scala `Int` constructor argument becomes a definition-side Verilog formal.
  *
  * The caller supplies a checked `ParameterizedMemoryDepth`, but this helper
  * does not assume the component owns a memory or expose the scalar as a packed
  * port width. Compiler-proven shadow, structural, value and memory metadata
  * remain authoritative for the child definition.
  */
private[spinal] object ExternalNativeIntFormalComponent {
  private val PortableIdentifier = "[A-Za-z_][A-Za-z0-9_]*".r

  def parameter[C <: Component](
      actual: ParameterizedMemoryDepth,
      name: String,
      minimum: BigInt,
      maximum: BigInt
  )(constructor: Int => C): C = {
    if (actual == null) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-ACTUAL-NULL",
        s"formalComponent.parameter '$name' requires one non-null bounded native Int actual",
        None
      )
    }
    val source = actual.sourceLocation
      .orElse(Option(actual.expression).flatMap(_.sourceLocation))
      .filter(_.nonEmpty)
    val callSite = source.getOrElse(s"<native-formal:$name>")

    if (constructor == null) {
      fail(
        "MORPH-FRONTEND-FORMAL-COMPONENT-CONSTRUCTOR-NULL",
        s"formalComponent.parameter slot '$name' requires one non-null native constructor",
        Some(callSite)
      )
    }
    if (
      name == null ||
      !PortableIdentifier.pattern.matcher(name).matches()
    ) {
      fail(
        "MORPH-FRONTEND-FORMAL-PARAMETER-NAME-INVALID",
        s"formal parameter name '$name' is not a portable Verilog identifier",
        Some(callSite)
      )
    }
    if (minimum < 1 || maximum < minimum || maximum > BigInt(Int.MaxValue)) {
      fail(
        "MORPH-FRONTEND-FORMAL-PARAMETER-DOMAIN-INVALID",
        s"formal parameter '$name' requires a positive non-empty Int-sized domain, received [$minimum, $maximum]",
        Some(callSite)
      )
    }

    val expression = actual.expression
    if (expression == null) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-ACTUAL-NULL",
        s"formalComponent.parameter '$name' requires one retained native Int expression",
        Some(callSite)
      )
    }
    if (
      expression.generateIndex.nonEmpty ||
      expression.default != BigInt(actual.value) ||
      expression.minimum < 1 || expression.maximum < expression.minimum ||
      expression.maximum > BigInt(Int.MaxValue) ||
      expression.default < expression.minimum ||
      expression.default > expression.maximum
    ) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-GEOMETRY-DOMAIN-INVALID",
        s"formalComponent.parameter '$name' expression '${expression.verilog}' must have witness ${actual.value} and a finite positive Int-sized loop-invariant domain, received default ${expression.default} in [${expression.minimum}, ${expression.maximum}]",
        Some(callSite)
      )
    }
    if (
      expression.minimum < minimum || expression.maximum > maximum ||
      expression.default < minimum || expression.default > maximum
    ) {
      fail(
        "MORPH-FRONTEND-FORMAL-PARAMETER-ACTUAL-DOMAIN-UNSUPPORTED",
        s"actual expression '${expression.verilog}' in [${expression.minimum}, ${expression.maximum}] with default ${expression.default} is incompatible with formal '$name' in [$minimum, $maximum]",
        Some(callSite)
      )
    }

    val parent = Option(Component.current).getOrElse {
      fail(
        "MORPH-FRONTEND-FORMAL-COMPONENT-PARENT-MISSING",
        s"formalComponent.parameter slot '$name' must execute inside one active parent Component",
        Some(callSite)
      )
    }
    val formal = ElaborationIntegerParameter(
      name,
      expression.default,
      minimum,
      maximum
    )
    val definitionExpression = ElaborationIntegerExpression(
      verilog = name,
      default = expression.default,
      minimum = minimum,
      maximum = maximum,
      parameters = Vector(formal),
      sourceLocation = Some(callSite)
    )
    val token = ExternalNativeIntFormalizationToken(
      callSite = callSite,
      valueOrigin = expression.sourceLocation.getOrElse(callSite),
      role = s"formalComponent.parameter($name)"
    )
    val capture = ExternalNativeIntShadowRegistry.captureWithDefinition(
      expression = expression,
      definitionExpression = definitionExpression,
      token = token,
      argumentName = name
    ) {
      constructor(actual.value)
    }
    val component = capture.result
    if (component == null) {
      fail(
        "MORPH-FRONTEND-FORMAL-COMPONENT-RESULT-NULL",
        s"formalComponent.parameter slot '$name' constructor returned null",
        Some(callSite)
      )
    }

    val ownerClassName = component.getClass.getName
    val binding = ExternalFormalParameterBinding(
      formal = formal,
      actual = expression,
      declarationKey = s"external-native-int::$ownerClassName::$name",
      ownerClassName = ownerClassName,
      sourceLocation = Some(callSite)
    )
    ExternalNativeIntFormalizationRegistry.attachComponentParameter(
      parent = parent,
      component = component,
      binding = binding,
      token = token
    )
    ExternalNativeIntShadowRegistry.attachComponent(
      component = component,
      binding = binding,
      capture = capture
    )
  }

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String]
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
'''
)

replace_once(
    "lib/src/main/scala/spinal/lib/Stream.scala",
    '''  def apply[T <: Data](dataType: HardType[T],
                       depth: Int,
                       latency: Int = 2,
                       forFMax: Boolean = false,
                       initPayload: => Option[T] = None): StreamFifo[T] = {
    assert(latency >= 0 && latency <= 2)
    new StreamFifo(
      dataType,
      depth,
      withAsyncRead = latency < 2,
      withBypass = latency == 0,
      forFMax = forFMax,
      initPayload = initPayload
    )
  }
''',
    '''  def apply[T <: Data](dataType: HardType[T],
                       depth: Int,
                       latency: Int = 2,
                       forFMax: Boolean = false,
                       initPayload: => Option[T] = None): StreamFifo[T] = {
    assert(latency >= 0 && latency <= 2)
    new StreamFifo(
      dataType,
      depth,
      withAsyncRead = latency < 2,
      withBypass = latency == 0,
      forFMax = forFMax,
      initPayload = initPayload
    )
  }

  /**
    * Retain a bounded symbolic FIFO depth while executing the ordinary native
    * `StreamFifo` constructor and implementation.
    */
  def apply[T <: Data](
      dataType: HardType[T],
      depth: ParameterizedMemoryDepth
  ): StreamFifo[T] =
    ExternalNativeIntFormalComponent.parameter(
      actual = depth,
      name = "DEPTH",
      minimum = BigInt(1),
      maximum = BigInt(4096)
    )(witness => new StreamFifo(dataType, witness))
'''
)
replace_once(
    "lib/src/main/scala/spinal/lib/Stream.scala",
    "        push := push + 1\n",
    "        push := (push + 1).resized\n"
)
replace_once(
    "lib/src/main/scala/spinal/lib/Stream.scala",
    "        pop := pop + 1\n",
    "        pop := (pop + 1).resized\n"
)
replace_once(
    "lib/src/main/scala/spinal/lib/Stream.scala",
    '''      when(io.flush){
        push := 0
        pop := 0
      }
''',
    '''      when(io.flush){
        push := U(0).resized
        pop := U(0).resized
      }
'''
)

replace_once(
    "frontend/src/main/scala/morphhdl/frontend/Library.scala",
    '''object StreamFifo {
  def apply[T <: Data](
      dataType: HardType[T],
      depth: HdlInt
  )(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): NativeStreamFifo[T] =
    spinal.lib.ExternalParameterizedStreamFifoDepthRegistry.create(dataType, depth)

  def apply[T <: Data](
      dataType: HardType[T],
      depth: ParameterizedMemoryDepth
  )(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): NativeStreamFifo[T] =
    apply(dataType, HdlInt.fromParameterizedMemoryDepth(depth))
}
''',
    '''object StreamFifo {
  def apply[T <: Data](
      dataType: HardType[T],
      depth: HdlInt
  )(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): NativeStreamFifo[T] =
    spinal.lib.StreamFifo(dataType, depth.toParameterizedMemoryDepth(file, line))

  def apply[T <: Data](
      dataType: HardType[T],
      depth: ParameterizedMemoryDepth
  )(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): NativeStreamFifo[T] =
    spinal.lib.StreamFifo(dataType, depth)
}
'''
)

replace_once(
    "frontend/src/main/scala/morphhdl/frontend/HdlInt.scala",
    '''  /** Convert the legacy direct bounded memory-depth parameter into HdlInt. */
  def fromParameterizedMemoryDepth(
      value: ParameterizedMemoryDepth
  )(implicit
      file: sourcecode.File,
      line: sourcecode.Line
  ): HdlInt = {
    if (value == null)
      throw new IllegalArgumentException(
        "symbolic StreamFifo depth must not be null"
      )
    val parameters = value.expression.parameters.distinct
    if (
      parameters.size != 1 ||
      value.expression.verilog.trim != parameters.head.name
    ) {
      throw new IllegalArgumentException(
        "ParameterizedMemoryDepth compatibility accepts one direct bounded parameter; use the HdlInt overload for compound depth expressions"
      )
    }
    val schema = parameters.head
    if (!schema.default.isValidInt || schema.default.toInt != value.value) {
      throw new IllegalArgumentException(
        s"symbolic StreamFifo depth witness ${value.value} disagrees with parameter '${schema.name}' default ${schema.default}"
      )
    }
    param(
      name = schema.name,
      default = schema.default,
      min = schema.minimum,
      max = schema.maximum
    )
  }

''',
    ""
)

replace_once(
    "morphhdl/src/test/scala/morphhdl/ParameterizedStreamFifoDepthTests.scala",
    "import morphhdl.frontend.{HdlInt, StreamFifo => MorphStreamFifo}\n",
    "import morphhdl.frontend.HdlInt\nimport morphhdl.frontend.HdlInt.hdlIntToParameterizedMemoryDepth\n"
)
replace_once(
    "morphhdl/src/test/scala/morphhdl/ParameterizedStreamFifoDepthTests.scala",
    '''  val fifo = MorphStreamFifo(
    morphhdl.frontend.HardType(morphhdl.frontend.Bits(8 bits)),
    depth
  )
''',
    '''  val fifo = spinal.lib.StreamFifo(
    HardType(Bits(8 bits)),
    depth
  )
'''
)
replace_once(
    "morphhdl/src/test/scala/morphhdl/ParameterizedStreamFifoDepthTests.scala",
    '  test("one untouched native StreamFifo definition preserves depths 1, 3, 5 and 8") {\n',
    '  test("one native StreamFifo definition preserves depths 1, 3, 5 and 8") {\n'
)

sidecar = ROOT / "frontend/src/main/scala/spinal/lib/ExternalParameterizedStreamFifoDepthRegistry.scala"
if not sidecar.exists():
    raise SystemExit(f"expected sidecar to remove: {sidecar}")
sidecar.unlink()

write(
    "morphhdl/scripts/check-native-streamfifo-structure-boundary.sh",
    '''#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"

[[ ! -e lib/src/main/scala/spinal/lib/ParameterizedStreamFifoDepth.scala ]]
[[ ! -e frontend/src/main/scala/spinal/lib/ExternalParameterizedStreamFifoDepthRegistry.scala ]]
! grep -R --line-number --fixed-strings 'rewriteParameterizedStreamFifoDepth' \
  morphhdl/src/main/scala frontend/src/main/scala morphruntime/src/main/scala lib/src/main/scala
! grep -R --line-number --fixed-strings 'fromParameterizedMemoryDepth' \
  frontend/src/main/scala
! grep -R --line-number -E \
  'io_push_(valid|ready|payload)|io_pop_(valid|ready|payload)|io_occupancy|io_availability' \
  morphhdl/src/main/scala/spinal/core/internals

grep -q 'depth: ParameterizedMemoryDepth' \
  lib/src/main/scala/spinal/lib/Stream.scala
grep -q 'ExternalNativeIntFormalComponent.parameter' \
  lib/src/main/scala/spinal/lib/Stream.scala
grep -q 'push := (push + 1).resized' \
  lib/src/main/scala/spinal/lib/Stream.scala
grep -q 'pop := (pop + 1).resized' \
  lib/src/main/scala/spinal/lib/Stream.scala
grep -q 'push := U(0).resized' \
  lib/src/main/scala/spinal/lib/Stream.scala
grep -q 'pop := U(0).resized' \
  lib/src/main/scala/spinal/lib/Stream.scala
grep -q 'object ExternalNativeIntFormalComponent' \
  morphruntime/src/main/scala/spinal/core/ExternalNativeIntFormalComponent.scala
grep -q 'val fifo = spinal.lib.StreamFifo' \
  morphhdl/src/test/scala/morphhdl/ParameterizedStreamFifoDepthTests.scala
! grep -q 'MorphStreamFifo' \
  morphhdl/src/test/scala/morphhdl/ParameterizedStreamFifoDepthTests.scala

python3 morphhdl/scripts/check-native-source-preservation.py \
  --manifest morphhdl/contracts/native-source-preservation.json

printf 'Increment 53 native StreamFifo source boundary passed.\\n'
'''
)

write(
    "docs/morphhdl/increment-53-native-streamfifo-parameter-structure.md",
    '''# Increment 53 — Native StreamFifo parameter structure

## Objective

Apply the generic native-`Int` formalization, expression-provenance and nested
symbolic-control-flow path from Increments 46 through 52 to the real
`spinal.lib.StreamFifo` implementation. One ordinary native FIFO definition
must cover depths 1, 3, 5 and 8.

Increment 53 intentionally restores the minimal Increment 37 native source
surface required by the roadmap: the `ParameterizedMemoryDepth` companion
object overload and the pointer-width-safe assignments in `Stream.scala`. It
does not restore the old FIFO sidecar or any emitted-name recognizer.

## Architecture

The public call enters the real native companion overload:

```scala
spinal.lib.StreamFifo(dataType, depth: ParameterizedMemoryDepth)
```

That overload delegates only the scalar formal boundary to the generic
`ExternalNativeIntFormalComponent.parameter` runtime helper and then executes
the ordinary `new StreamFifo(dataType, witness)` constructor. The helper is not
FIFO-specific and does not treat the scalar as a packed child-port width.

The MorphHDL compiler plugin is enabled while compiling the native `lib`
module. It selects the exact `StreamFifo` class and its ordinary `depth: Int`
constructor argument in memory. The existing native-`Int` shadow machinery
then retains:

- depth arithmetic and comparisons;
- `log2Up` and `isPow2` results;
- Boolean combinations and Boolean-to-integer pointer-width terms;
- native Boolean `generate` calls normalized to witness-equivalent Scala
  conditionals;
- exhaustive Boolean matches normalized to conditionals;
- nested depth-one, power-of-two and non-power-of-two alternatives.

A small MorphHDL runtime module is shared by `lib` and `frontend` so
compiler-inserted hooks are available without a `lib -> frontend -> lib`
dependency cycle. It reuses the formal, shadow, memory, value and structural
registries established by Increments 45 through 52; it contains no FIFO RTL
implementation.

When a symbolic Scala integer enters an ordinary native `UInt` operation, the
compiler creates an exact identity-retained UInt carrier. Publication rewrites
only that carrier's concrete witness assignment using its retained object
identity. No StreamFifo module, port or user signal name is used for discovery.

### Scalar component formal boundary

`DEPTH` controls storage and structural alternatives, but it is not the packed
width of `io.occupancy` or `io.availability`. Those ports use the derived width
`log2Up(DEPTH + 1)`. The generic scalar component boundary therefore retains
the exact formal-to-actual hierarchy binding on component identity without
attaching `DEPTH` to either packed port.

Definition-side proof still comes from compiler shadow plus memory, value,
structural and process registries. Hierarchy lowering resolves the scalar
formal from exact component identity and canonical declaration identity.

## Removed compatibility path

Increment 53 removes:

- `lib/src/main/scala/spinal/lib/ParameterizedStreamFifoDepth.scala`;
- `frontend/src/main/scala/spinal/lib/ExternalParameterizedStreamFifoDepthRegistry.scala`;
- `rewriteParameterizedStreamFifoDepth`;
- the emitted `io_push_*`, `io_pop_*`, occupancy and availability recognizer;
- the `HdlInt.fromParameterizedMemoryDepth` round-trip conversion.

The MorphHDL frontend adapter delegates directly to the real native overload.
Both a direct bounded parameter and a compound bounded `HdlInt` expression
cross the same `ParameterizedMemoryDepth` contract.

## Proof boundary

The dedicated contract proves on Scala 2.12.18 and 2.13.12 that:

- the test instantiates the real `spinal.lib.StreamFifo` overload;
- there is one parameterized native `StreamFifo` definition;
- the definition retains depth-one, power-of-two and non-power-of-two structure;
- the same definition simulates and synthesizes at depths 1, 3, 5 and 8;
- concrete `SpinalVerilog` remains concrete;
- inherited native memory, formalization, provenance, expression and nested
  control-flow tests remain green;
- the minimal reviewed `Stream.scala` edits are present and the sidecar/rewrite
  cannot return.
'''
)

for directory, prefix in (
    (ROOT / "morphhdl/.increment-status", "increment-53-"),
    (ROOT / "morphhdl/scripts", "dispatch-increment-53-"),
):
    if directory.exists():
        for path in directory.iterdir():
            if path.is_file() and path.name.startswith(prefix):
                path.unlink()
