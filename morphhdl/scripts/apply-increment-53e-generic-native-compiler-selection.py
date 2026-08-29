#!/usr/bin/env python3
from pathlib import Path
import re

path = Path(
    "morphplugin/src/main/scala/morphhdl/compiler/"
    "MorphHdlNativeIntShadowExpressionComponent.scala"
)
value = path.read_text()

# The compiler component is loaded only for native Spinal library compilation.
# Instrument every production source under spinal/lib; runtime activity decides
# whether a particular execution belongs to an explicit MorphHDL boundary.
value = value.replace(
    'normalizedPath.endsWith("/lib/src/main/scala/spinal/lib/Stream.scala")',
    'normalizedPath.contains("/lib/src/main/scala/spinal/")'
)
value = value.replace(
    'normalizedPath.endsWith("/lib/src/main/scala/spinal/lib/CrossClock.scala")',
    'normalizedPath.contains("/lib/src/main/scala/spinal/")'
)

# Rename inherited fixture-specific compiler state. These are implementation
# identifiers only; diagnostics and semantics stay source-compatible.
renames = (
    ("nativeStreamFifoDepthReference", "nativeFormalizedIntReference"),
    ("nativeStreamFifoClassName", "nativeFormalizedOwnerName"),
    ("nativeStreamFifoConstructorParameters", "nativeConstructorParameters"),
    ("transformNativeStreamFifo", "transformNativeFormalizedClass"),
    ("inNativeStreamFifo", "inNativeFormalizedClass"),
    ("NativeStreamFifo", "NativeFormalizedClass"),
    ("native StreamFifo", "native formalized class"),
    ("native StreamFifoCC", "native formalized class"),
)
for old, new in renames:
    value = value.replace(old, new)

# Source-level runtime routing is generic and independent of the currently
# selected constructor parameter. Outside a live boundary all runtime helpers
# return ordinary native values.
source_context = '''    private def inNativeCompilerRuntimeContext: Boolean =
      sourceFile.replace('\\\\', '/').contains(
        "/lib/src/main/scala/spinal/"
      )

'''
if "private def inNativeCompilerRuntimeContext" in value:
    value = re.sub(
        r'''    private def inNativeCompilerRuntimeContext: Boolean =\n(?:      .*\n){1,5}''',
        source_context,
        value,
        count=1,
    )
else:
    marker = '''    private def inNativeFormalizedClass: Boolean =
      nativeFormalizedIntReference.nonEmpty

'''
    if marker not in value:
        raise SystemExit("native compiler runtime-context insertion marker is missing")
    value = value.replace(marker, marker + source_context, 1)

value = value.replace(
    '''    private def selectedHelperMethod(name: String): Tree =
      if (inNativeFormalizedClass) helperMethod(name) else frontendHelperMethod(name)
''',
    '''    private def selectedHelperMethod(name: String): Tree =
      if (inNativeCompilerRuntimeContext) helperMethod(name)
      else frontendHelperMethod(name)
'''
)

# Identify a formalizable class structurally: exactly one constructor Int is
# supported by one boundary root. No source path, component class name, argument
# name, or equal witness is used as selection evidence.
helper_marker = '''    private def nativeConstructorParameters(value: ClassDef)'''
if "private def isNativeIntConstructorParameter" not in value:
    index = value.find(helper_marker)
    if index < 0:
        raise SystemExit("native constructor-parameter helper marker is missing")
    helper = '''    private def isNativeIntConstructorParameter(
        parameter: ValDef
    ): Boolean = {
      val symbolType =
        if (parameter.symbol == null || parameter.symbol == NoSymbol) NoType
        else parameter.symbol.info.finalResultType
      (symbolType != NoType && symbolType =:= definitions.IntTpe) ||
        decoded(parameter.tpt.toString) == "Int" ||
        parameter.tpt.toString.endsWith(".Int")
    }

    private def hasOneNativeFormalizableInt(value: ClassDef): Boolean =
      nativeConstructorParameters(value)
        .count(isNativeIntConstructorParameter) == 1

'''
    value = value[:index] + helper + value[index:]

# Replace constructor-argument discovery by type rather than the historic name.
patterns = [
    r'''parameters\.find\(parameter\s*=>\s*decoded\(parameter\.name\)\s*==\s*"depth"\)''',
    r'''parameters\.find\(parameter\s*=>\s*parameter\.name\.decodedName\.toString\s*==\s*"depth"\)''',
    r'''parameters\.find\(_\.name\.decodedName\.toString\s*==\s*"depth"\)''',
]
for pattern in patterns:
    value = re.sub(
        pattern,
        "parameters.find(isNativeIntConstructorParameter)",
        value,
    )

# Generic class entrypoint. Classes without exactly one Int remain ordinary,
# while their expressions are still safely instrumented by source context and
# become no-ops unless nested beneath an active boundary.
class_case = re.compile(
    r'''      case value: ClassDef\n\s*if sourceFile\.replace\('\\\\\\\\', '/'\)\.endsWith\(\n\s*"/lib/src/main/scala/spinal/lib/Stream\.scala"\n\s*\) && Set\("StreamFifo", "StreamFifoCC"\)\.contains\(decoded\(value\.name\)\) =>\n\s*transformNativeFormalizedClass\(value\)'''
)
value, count = class_case.subn(
    '''      case value: ClassDef
          if inNativeCompilerRuntimeContext &&
            hasOneNativeFormalizableInt(value) =>
        transformNativeFormalizedClass(value)''',
    value,
    count=1,
)
if count == 0:
    # Tolerate a one-line or already partially generalized source condition.
    class_case_loose = re.compile(
        r'''      case value: ClassDef\n\s*if .*?Set\("StreamFifo", "StreamFifoCC"\)\.contains\(decoded\(value\.name\)\) =>\n\s*transformNativeFormalizedClass\(value\)''',
        re.S,
    )
    value, count = class_case_loose.subn(
        '''      case value: ClassDef
          if inNativeCompilerRuntimeContext &&
            hasOneNativeFormalizableInt(value) =>
        transformNativeFormalizedClass(value)''',
        value,
        count=1,
    )

# If a previous patch changed the method name but left the original literals,
# eliminate those literals explicitly and use the same structural guard.
value = value.replace(
    'Set("StreamFifo", "StreamFifoCC").contains(decoded(value.name))',
    'hasOneNativeFormalizableInt(value)'
)
value = value.replace(
    '''sourceFile.replace('\\\\', '/').endsWith(
            "/lib/src/main/scala/spinal/lib/Stream.scala"
          ) && hasOneNativeFormalizableInt(value)''',
    '''inNativeCompilerRuntimeContext &&
            hasOneNativeFormalizableInt(value)'''
)

# Route all generic expression capture in native library sources through the
# runtime-selective helper. This does not activate provenance by itself.
value = value.replace("if inNativeFormalizedClass &&", "if inNativeCompilerRuntimeContext &&")
value = value.replace("if inNativeFormalizedClass =>", "if inNativeCompilerRuntimeContext =>")

# Generic Data-shape copying: any native Reg/clone/RegNext call can preserve
# exact symbolic shape while a boundary is active. The runtime helper returns
# the original Data unchanged otherwise.
reg_case = '''        case Apply(fun, arguments)
            if inNativeCompilerRuntimeContext &&
              terminalName(fun) == "Reg" && arguments.nonEmpty =>
          rewriteNativeCopyShape(tree, fun, arguments)
'''
expression_marker = '''        case Apply(fun, List(data))
            if inNativeCompilerRuntimeContext && terminalName(fun) == "cloneOf" =>
'''
if reg_case not in value and expression_marker in value:
    value = value.replace(expression_marker, reg_case + expression_marker, 1)

# Remove component/source literals from executable compiler logic. Adapter and
# tests remain free to name their concrete native fixture.
for literal in (
    '"StreamFifo"',
    '"StreamFifoCC"',
    '"BufferCC"',
    '"BufferCCObject"',
    '"/lib/src/main/scala/spinal/lib/Stream.scala"',
    '"/lib/src/main/scala/spinal/lib/CrossClock.scala"',
):
    if literal in value:
        raise SystemExit(
            "component-specific compiler literal remains after genericization: " + literal
        )

path.write_text(value)
