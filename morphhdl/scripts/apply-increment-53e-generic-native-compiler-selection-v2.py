#!/usr/bin/env python3
from pathlib import Path
import re

path = Path(
    "morphplugin/src/main/scala/morphhdl/compiler/"
    "MorphHdlNativeIntShadowExpressionComponent.scala"
)
value = path.read_text()

# Keep source eligibility broad, but activate expression rewriting only while
# transforming a structurally selected native class. This avoids touching
# unrelated package/object code while removing all component-name selection.
value = value.replace(
    'normalizedPath.endsWith("/lib/src/main/scala/spinal/lib/Stream.scala")',
    'normalizedPath.contains("/lib/src/main/scala/spinal/")'
)
value = value.replace(
    'normalizedPath.endsWith("/lib/src/main/scala/spinal/lib/CrossClock.scala")',
    'normalizedPath.contains("/lib/src/main/scala/spinal/")'
)

# Normalize inherited names without changing externally visible APIs.
for old, new in (
    ("nativeStreamFifoDepthReference", "nativeFormalizedIntReference"),
    ("nativeStreamFifoClassName", "nativeFormalizedOwnerName"),
    ("nativeStreamFifoConstructorParameters", "nativeConstructorParameters"),
    ("transformNativeStreamFifo", "transformNativeFormalizedClass"),
    ("inNativeStreamFifo", "inNativeFormalizedClass"),
    ("NativeStreamFifo", "NativeFormalizedClass"),
):
    value = value.replace(old, new)

# Rebuild the runtime-routing helper deterministically.
start = value.find("    private def inNativeCompilerRuntimeContext: Boolean =")
if start >= 0:
    end = value.find("\n\n", start)
    if end < 0:
        raise SystemExit("runtime-context helper terminator is missing")
    value = (
        value[:start]
        + "    private def inNativeCompilerRuntimeContext: Boolean =\n"
          "      inNativeFormalizedClass\n"
        + value[end:]
    )
else:
    marker = (
        "    private def inNativeFormalizedClass: Boolean =\n"
        "      nativeFormalizedIntReference.nonEmpty\n\n"
    )
    if marker not in value:
        raise SystemExit("formalized-class context marker is missing")
    value = value.replace(
        marker,
        marker
        + "    private def inNativeCompilerRuntimeContext: Boolean =\n"
          "      inNativeFormalizedClass\n\n",
        1,
    )

value = re.sub(
    r'''    private def selectedHelperMethod\(name: String\): Tree =\n(?:      .*\n){1,3}''',
    '''    private def selectedHelperMethod(name: String): Tree =
      if (inNativeCompilerRuntimeContext) helperMethod(name)
      else frontendHelperMethod(name)
''',
    value,
    count=1,
)

# Add structural constructor-Int selection if not already present.
if "private def isNativeIntConstructorParameter" not in value:
    marker = "    private def nativeConstructorParameters(value: ClassDef)"
    index = value.find(marker)
    if index < 0:
        raise SystemExit("constructor parameter helper marker is missing")
    helper = '''    private def isNativeIntConstructorParameter(
        parameter: ValDef
    ): Boolean = {
      val symbolType =
        if (parameter.symbol == null || parameter.symbol == NoSymbol) NoType
        else parameter.symbol.info.finalResultType
      (symbolType != NoType && symbolType =:= definitions.IntTpe) ||
        parameter.tpt.toString == "Int" ||
        parameter.tpt.toString.endsWith(".Int")
    }

    private def hasOneNativeFormalizableInt(value: ClassDef): Boolean =
      nativeConstructorParameters(value)
        .count(isNativeIntConstructorParameter) == 1

'''
    value = value[:index] + helper + value[index:]

# Select the sole constructor Int by type, never by the historic argument name.
value = re.sub(
    r'''parameters\.find\([^\n]*decoded\([^\n]*\.name\)[^\n]*==\s*"depth"[^\n]*\)''',
    "parameters.find(isNativeIntConstructorParameter)",
    value,
)
value = re.sub(
    r'''parameters\.find\([^\n]*decodedName[^\n]*==\s*"depth"[^\n]*\)''',
    "parameters.find(isNativeIntConstructorParameter)",
    value,
)

# Normalize the ClassDef entry guard. Keep any following cases intact.
pattern = re.compile(
    r'''      case value: ClassDef\n\s*if .*?=>\n\s*transformNativeFormalizedClass\(value\)''',
    re.S,
)
matches = list(pattern.finditer(value))
selected = [
    match for match in matches
    if "StreamFifo" in match.group(0)
    or "hasOneNativeFormalizableInt" in match.group(0)
    or "Stream.scala" in match.group(0)
]
if selected:
    match = selected[0]
    replacement = '''      case value: ClassDef
          if hasOneNativeFormalizableInt(value) =>
        transformNativeFormalizedClass(value)'''
    value = value[: match.start()] + replacement + value[match.end() :]

# Generic shape-copy capture inside every structurally selected class.
clone_marker = '''        case Apply(fun, List(data))
            if inNativeCompilerRuntimeContext && terminalName(fun) == "cloneOf" =>
'''
reg_case = '''        case Apply(fun, arguments)
            if inNativeCompilerRuntimeContext &&
              terminalName(fun) == "Reg" && arguments.nonEmpty =>
          rewriteNativeCopyShape(tree, fun, arguments)
'''
if clone_marker in value and reg_case not in value:
    value = value.replace(clone_marker, reg_case + clone_marker, 1)

# No executable selection may retain fixture names or files.
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
            "component-specific compiler literal remains: " + literal
        )

path.write_text(value)
