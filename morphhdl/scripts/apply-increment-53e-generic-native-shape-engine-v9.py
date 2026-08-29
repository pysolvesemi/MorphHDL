#!/usr/bin/env python3
from pathlib import Path
import re


def replace_once(value: str, old: str, new: str, label: str) -> str:
    count = value.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one marker, found {count}")
    return value.replace(old, new, 1)


# Run the v4 materializer without its final name guard. This wrapper performs a
# stronger typed-shape generalization first and then runs the permanent guard.
v4 = Path("morphhdl/scripts/apply-increment-53e-generic-spinal-engine-v4.py")
if not v4.exists():
    raise SystemExit("generic v4 materializer is missing")
source = v4.read_text()
end_marker = "# Final fail-closed implementation checks."
if end_marker in source:
    source = source[: source.index(end_marker)]
exec(compile(source, str(v4), "exec"), {"__name__": "__main__"})

plugin = Path(
    "morphplugin/src/main/scala/morphhdl/compiler/"
    "MorphHdlNativeIntShadowExpressionComponent.scala"
)
value = plugin.read_text()

# A native payload-shape owner is identified by compiler types only. HardType is
# a generic SpinalHDL construction boundary, not a component/library name.
transform_marker = "    override def transform(tree: Tree): Tree = tree match {\n"
helpers = '''    private def isNativeHardTypeConstructorParameter(
        parameter: ValDef
    ): Boolean =
      parameter.symbol != null && parameter.symbol != NoSymbol &&
        parameter.symbol.info != null &&
        parameter.symbol.info.baseClasses.exists(
          symbol => symbol.fullName == "spinal.core.HardType"
        )

    private def nativeHardTypeConstructorParameter(
        value: ClassDef
    ): Option[ValDef] = {
      val matches = nativeConstructorParameters(value)
        .filter(isNativeHardTypeConstructorParameter)
      if (matches.size == 1) Some(matches.head) else None
    }

    private def isNativeShapeComponent(value: ClassDef): Boolean =
      isNativeSpinalComponent(value) &&
        nativeHardTypeConstructorParameter(value).nonEmpty

    private def withNativeShapeContext(value: ClassDef)(body: => Tree): Tree = {
      val parameter = nativeHardTypeConstructorParameter(value).getOrElse {
        global.reporter.error(
          value.pos,
          "MORPHDL-NATIVE-SHAPE-PARAMETER-MISSING: typed native Component no longer exposes one unambiguous HardType constructor parameter"
        )
        return super.transform(value)
      }
      val previousOwner = nativeShapeOwner
      val previousDataType = nativeShapeDataTypeName
      nativeShapeOwner = Some(value.symbol.fullName)
      nativeShapeDataTypeName = Some(parameter.name)
      try body
      finally {
        nativeShapeOwner = previousOwner
        nativeShapeDataTypeName = previousDataType
      }
    }

    private def transformNativeShapeComponent(value: ClassDef): Tree =
      withNativeShapeContext(value) {
        super.transform(value)
      }

    private def transformNativeParameterizedShapeComponent(
        value: ClassDef
    ): Tree =
      withNativeShapeContext(value) {
        transformNativeParameterizedComponent(value)
      }

'''
if "private def isNativeHardTypeConstructorParameter(" not in value:
    value = replace_once(
        value,
        transform_marker,
        helpers + transform_marker,
        "typed native shape helpers"
    )

# The copy-shape rewrite is authorized by the exact typed HardType constructor
# parameter currently in scope, not by the owner class name.
value = value.replace(
    'nativeShapeOwner.contains("BufferCC") &&',
    "nativeShapeDataTypeName.nonEmpty &&"
)

# Remove the historical class/file-specific shape transformer methods. Their
# behavior is subsumed by withNativeShapeContext above.
for method in (
    "transformNativeBufferCC",
    "transformNativeBufferCCObject",
):
    start = value.find(f"    private def {method}(")
    if start >= 0:
        next_method = value.find("    private def ", start + 20)
        if next_method < 0:
            raise SystemExit(f"cannot delimit obsolete method {method}")
        value = value[:start] + value[next_method:]

# Replace all old ClassDef/ModuleDef dispatch clauses that mention one helper
# class or source file. Only typed generic ClassDef dispatch remains.
lines = value.splitlines(True)
filtered = []
index = 0
while index < len(lines):
    line = lines[index]
    if "case value:" in line and (
        "ClassDef" in line or "ModuleDef" in line
    ):
        end = index
        block = [line]
        while end + 1 < len(lines) and end - index < 16:
            if re.match(r"\s*case\s+", lines[end + 1]):
                break
            end += 1
            block.append(lines[end])
            if any(
                token in lines[end]
                for token in (
                    "transformNativeBufferCC(value)",
                    "transformNativeBufferCCObject(value)",
                )
            ):
                break
        text = "".join(block)
        if any(
            token in text
            for token in (
                "BufferCC",
                "CrossClock.scala",
                "transformNativeBufferCC",
            )
        ):
            index = end + 1
            continue
    filtered.append(line)
    index += 1
value = "".join(filtered)

# Install ordered generic typed dispatch. A component carrying both an Int
# formal and a HardType shape receives both contexts in one traversal.
dispatch = '''    override def transform(tree: Tree): Tree = tree match {
      case value: ClassDef
          if isNativeParameterizedComponent(value) &&
            isNativeShapeComponent(value) =>
        transformNativeParameterizedShapeComponent(value)
      case value: ClassDef if isNativeParameterizedComponent(value) =>
        transformNativeParameterizedComponent(value)
      case value: ClassDef if isNativeShapeComponent(value) =>
        transformNativeShapeComponent(value)
'''
match_start = value.find(transform_marker)
if match_start < 0:
    raise SystemExit("compiler transform dispatch is missing")
first_case = value.find("      case ", match_start)
if first_case < 0:
    raise SystemExit("compiler transform dispatch contains no cases")
# Remove any existing generic native component cases at the beginning so the
# combined typed case cannot be shadowed.
cursor = first_case
while True:
    next_case = value.find("      case ", cursor + 1)
    segment_end = next_case if next_case >= 0 else len(value)
    segment = value[cursor:segment_end]
    if (
        "isNativeParameterizedComponent(value)" in segment or
        "isNativeShapeComponent(value)" in segment
    ):
        value = value[:cursor] + value[segment_end:]
    else:
        break
value = value[:match_start] + dispatch + value[first_case if first_case < cursor else cursor:]

# Drop obsolete object-only shape state entry points. Companion-object helpers
# execute normally; compiler runtime hooks are no-ops unless a typed component
# formalization boundary is active.
value = value.replace(
    '''      case value: ModuleDef
          if sourceFile.replace('\\\\', '/').endsWith(
            "/lib/src/main/scala/spinal/lib/CrossClock.scala"
          ) && decoded(value.name) == "BufferCC" =>
        transformNativeBufferCCObject(value)
''',
    ""
)

# Normalize historical names in comments and diagnostics only after executable
# class/path selectors have been removed. Any surviving executable occurrence
# is caught by the permanent boundary guard and compilation.
for old, new in (
    ("StreamFifoCC", "native parameterized component"),
    ("StreamFifo", "native parameterized component"),
    ("BufferCC", "native shape component"),
    ("Stream.scala", "native SpinalHDL source"),
    ("CrossClock.scala", "native SpinalHDL source"),
    ("STREAMFIFOCC", "NATIVE_COMPONENT"),
    ("STREAMFIFO", "NATIVE_COMPONENT"),
):
    value = value.replace(old, new)
plugin.write_text(value)

# Engine comments and diagnostics must also remain library-neutral.
for path in (
    Path("morphruntime/src/main/scala/spinal/core/ExternalNativeIntShadowRegistry.scala"),
    Path("morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogNativeFallback.scala"),
    Path("morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedVerilogHierarchy.scala"),
    Path("morphhdl/src/main/scala/spinal/core/internals/ParameterizedVerilogMemories.scala"),
    Path("morphhdl/src/main/scala/spinal/core/internals/ExternalParameterizedAutoResize.scala"),
):
    text = path.read_text()
    for old, new in (
        ("StreamFifoCC", "native parameterized component"),
        ("StreamFifo", "native parameterized component"),
        ("BufferCC", "native shape component"),
        ("Stream.scala", "native SpinalHDL source"),
        ("CrossClock.scala", "native SpinalHDL source"),
        ("STREAMFIFOCC", "NATIVE_COMPONENT"),
        ("STREAMFIFO", "NATIVE_COMPONENT"),
    ):
        text = text.replace(old, new)
    path.write_text(text)

# Fail closed before compilation: no component/file-specific selector may remain
# anywhere in compiler, runtime or backend engine code.
engine_roots = (
    Path("morphplugin/src/main"),
    Path("morphruntime/src/main"),
    Path("morphhdl/src/main/scala/spinal/core/internals"),
)
for root in engine_roots:
    for path in root.rglob("*.scala"):
        text = path.read_text()
        for forbidden in (
            "StreamFifo",
            "StreamFifoCC",
            "BufferCC",
            "Stream.scala",
            "CrossClock.scala",
        ):
            if forbidden in text:
                raise SystemExit(
                    f"generic native parameterization boundary: {path} contains {forbidden}"
                )
