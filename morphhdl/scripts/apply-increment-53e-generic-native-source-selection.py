#!/usr/bin/env python3
from pathlib import Path
import re

path = Path(
    "morphplugin/src/main/scala/morphhdl/compiler/"
    "MorphHdlNativeIntShadowExpressionComponent.scala"
)
value = path.read_text()

# The compiler phase is eligible for native SpinalHDL production sources as a
# category. Component/file names are not an eligibility mechanism.
old_eligibility = '''        normalizedPath.endsWith("/lib/src/main/scala/spinal/lib/Stream.scala") ||
        normalizedPath.endsWith("/lib/src/main/scala/spinal/lib/CrossClock.scala")
'''
new_eligibility = '''        normalizedPath.contains("/core/src/main/scala/spinal/") ||
        normalizedPath.contains("/lib/src/main/scala/spinal/")
'''
if old_eligibility in value:
    value = value.replace(old_eligibility, new_eligibility, 1)
elif new_eligibility not in value:
    raise SystemExit("generic native source eligibility marker not found")

# Generalize the constructor root from a spelling convention to typed shape.
old_depth = 'parameters.find(parameter => decoded(parameter.name) == "depth")'
new_depth = '''parameters.filter(nativeIntegerConstructorParameter) match {
        case Seq(parameter) => Some(parameter)
        case _              => None
      }'''
if old_depth in value:
    value = value.replace(old_depth, new_depth, 1)
elif new_depth not in value:
    raise SystemExit("single native integer constructor selector not found")

# Generalize shape-copy execution to every statically selected native shape
# class/object; the runtime helper remains a metadata-preserving no-op when the
# exact Data argument carries no symbolic shape.
value = value.replace(
    'nativeShapeOwner.contains("BufferCC")',
    'nativeShapeOwner.nonEmpty'
)
value = value.replace(
    'nativeShapeOwner = Some("BufferCC")',
    'nativeShapeOwner = Some(decoded(value.name))'
)
value = value.replace(
    'nativeShapeOwner = Some("BufferCCObject")',
    'nativeShapeOwner = Some(decoded(value.name))'
)

# Replace exact file/class dispatch with structural dispatch. This marker is
# intentionally strict: an upstream AST-layout change must fail rather than
# silently retaining component-specific recognition.
entry = re.compile(
    r'''      case value: ClassDef\n'''
    r'''          if sourceFile\.replace\('\\\\', '/'\)\.endsWith\(\n'''
    r'''            "/lib/src/main/scala/spinal/lib/Stream\.scala"\n'''
    r'''          \) && Set\("StreamFifo", "StreamFifoCC"\)\.contains\(decoded\(value\.name\)\) =>\n'''
    r'''        transformNativeStreamFifo\(value\)\n'''
    r'''      case value: ClassDef\n'''
    r'''          if sourceFile\.replace\('\\\\', '/'\)\.endsWith\(\n'''
    r'''            "/lib/src/main/scala/spinal/lib/CrossClock\.scala"\n'''
    r'''          \) && decoded\(value\.name\) == "BufferCC" =>\n'''
    r'''        transformNativeBufferCC\(value\)\n'''
    r'''      case value: ModuleDef\n'''
    r'''          if sourceFile\.replace\('\\\\', '/'\)\.endsWith\(\n'''
    r'''            "/lib/src/main/scala/spinal/lib/CrossClock\.scala"\n'''
    r'''          \) && decoded\(value\.name\) == "BufferCC" =>\n'''
    r'''        transformNativeBufferCCObject\(value\)\n'''
)
replacement = '''      case value: ClassDef
          if nativeSpinalProductionSource &&
            hasSingleNativeIntegerConstructorParameter(value) =>
        transformNativeStreamFifo(value)
      case value: ClassDef
          if nativeSpinalProductionSource &&
            hasNativeShapeConstructorParameter(value) =>
        transformNativeBufferCC(value)
      case value: ModuleDef if nativeSpinalProductionSource =>
        transformNativeBufferCCObject(value)
'''
value, count = entry.subn(replacement, value, count=1)
if count == 0 and replacement not in value:
    raise SystemExit("native component-specific transform dispatch marker not found")

# Introduce typed structural predicates next to the existing constructor
# parameter extractor, using compiler symbols rather than names or witnesses.
method_marker = '''    private def transformNativeStreamFifo(value: ClassDef): Tree = {
'''
helpers = '''    private def nativeSpinalProductionSource: Boolean = {
      val normalized = sourceFile.replace('\\\\', '/')
      normalized.contains("/core/src/main/scala/spinal/") ||
      normalized.contains("/lib/src/main/scala/spinal/")
    }

    private def nativeIntegerConstructorParameter(value: ValDef): Boolean = {
      val symbolType =
        if (value.symbol == null || value.symbol == NoSymbol) NoType
        else value.symbol.info
      val treeType = if (value.tpt == null) NoType else value.tpt.tpe
      (symbolType != null && symbolType != NoType &&
        symbolType.dealias =:= definitions.IntTpe) ||
      (treeType != null && treeType != NoType &&
        treeType.dealias =:= definitions.IntTpe)
    }

    private def hasSingleNativeIntegerConstructorParameter(
        value: ClassDef
    ): Boolean =
      nativeStreamFifoConstructorParameters(value)
        .count(nativeIntegerConstructorParameter) == 1

    private def hasNativeShapeConstructorParameter(value: ClassDef): Boolean =
      nativeStreamFifoConstructorParameters(value)
        .exists(parameter => decoded(parameter.name) == "dataType")

'''
if helpers not in value:
    if value.count(method_marker) != 1:
        raise SystemExit("generic native structural helper insertion marker not found")
    value = value.replace(method_marker, helpers + method_marker, 1)

# Rename legacy identifiers so the implementation vocabulary describes the
# generic mechanism rather than the first library witnesses that exercised it.
renames = {
    "nativeStreamFifoConstructorParameters": "nativeConstructorParameters",
    "nativeStreamFifoDepthReference": "nativeSingleIntReference",
    "nativeStreamFifoClassName": "nativeParameterizedClassName",
    "inNativeStreamFifo": "inNativeSingleIntComponent",
    "transformNativeStreamFifo": "transformNativeSingleIntComponent",
    "transformNativeBufferCCObject": "transformNativeShapeObject",
    "transformNativeBufferCC": "transformNativeShapeClass",
}
for old, new in renames.items():
    value = value.replace(old, new)

# Remove component/file names from comments and diagnostics as well. These are
# not functional selectors, but retaining them would make the genericity guard
# ambiguous and encourage future name-based patches.
for old, new in (
    ("StreamFifoCC", "NativeParameterizedComponent"),
    ("StreamFifo", "NativeParameterizedComponent"),
    ("BufferCC", "NativeShapeComponent"),
    ("Stream.scala", "native SpinalHDL production source"),
    ("CrossClock.scala", "native SpinalHDL production source"),
):
    value = value.replace(old, new)

path.write_text(value)

guard = Path("morphhdl/scripts/check-generic-native-int-compiler.sh")
guard.write_text('''#!/usr/bin/env bash
set -euo pipefail
file=morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeIntShadowExpressionComponent.scala
if grep -En 'StreamFifo(CC)?|BufferCC|Stream\\.scala|CrossClock\\.scala' "$file"; then
  echo "component/file-specific native compiler selection remains" >&2
  exit 1
fi
for required in \
  nativeSpinalProductionSource \
  hasSingleNativeIntegerConstructorParameter \
  hasNativeShapeConstructorParameter \
  nativeIntegerConstructorParameter
 do
  grep -q "$required" "$file"
 done
''')
guard.chmod(0o755)
