#!/usr/bin/env python3
from pathlib import Path
import re

path = Path(
    "morphplugin/src/main/scala/morphhdl/compiler/"
    "MorphHdlNativeIntShadowExpressionComponent.scala"
)
value = path.read_text()

if "nativeFormalIntComponentCandidate" in value:
    forbidden = re.compile(
        r"StreamFifo(?:CC)?|BufferCC|Stream\.scala|CrossClock\.scala|"
        r"pushToPopGray|popToPushGray"
    )
    if forbidden.search(value):
        raise SystemExit("generic native capture still contains component-specific recognition")
    raise SystemExit(0)

# Rename implementation state and helpers before removing literal recognition.
value = value.replace("nativeStreamFifo", "nativeFormalIntComponent")
value = value.replace("NativeStreamFifo", "NativeFormalIntComponent")

# Source eligibility is the upstream Spinal production roots, not selected files.
source_patterns = [
    re.compile(
        r'normalizedPath\.endsWith\("/lib/src/main/scala/spinal/lib/Stream\.scala"\)'
        r'(?:\s*\|\|\s*normalizedPath\.endsWith\('
        r'"/lib/src/main/scala/spinal/lib/CrossClock\.scala"\))?'
    ),
]
source_replacement = '''(
        normalizedPath.contains("/core/src/main/scala/spinal/") ||
        normalizedPath.contains("/lib/src/main/scala/spinal/")
      )'''
for pattern in source_patterns:
    value = pattern.sub(source_replacement, value)

transform_marker = "    override def transform(tree: Tree): Tree = tree match {\n"
if value.count(transform_marker) != 1:
    raise SystemExit(
        f"generic native capture transform marker count={value.count(transform_marker)}"
    )

helper = '''    /**
      * Select a native formalization context by constructor shape. Exactly one
      * Scala Int constructor dimension is required. Zero or multiple dimensions
      * are left untouched and therefore fail closed unless a future explicit
      * mapping supplies the selected formal. No class/package/source name is a
      * discovery key.
      */
    private def nativeFormalIntComponentCandidate(value: ClassDef): Boolean = {
      val parameters = nativeFormalIntComponentConstructorParameters(value)
      val integerParameters = parameters.filter { parameter =>
        parameter.symbol != null && parameter.symbol != NoSymbol &&
        parameter.symbol.info != null &&
        (parameter.symbol.info =:= definitions.IntTpe)
      }
      integerParameters.size == 1
    }

'''
value = value.replace(transform_marker, helper + transform_marker, 1)

# Replace the old selected-class entry with the structural selector. Match only
# the case whose action is the renamed native formal-component transformer.
case_pattern = re.compile(
    r'''      case value: ClassDef\s+if .*?=>\s*'''
    r'''        transformNativeFormalIntComponent\(value\)''',
    re.S,
)
matches = list(case_pattern.finditer(value))
if len(matches) != 1:
    raise SystemExit(
        f"generic native capture selected-class case count={len(matches)}"
    )
match = matches[0]
value = (
    value[: match.start()]
    + "      case value: ClassDef if nativeFormalIntComponentCandidate(value) =>\n"
      "        transformNativeFormalIntComponent(value)"
    + value[match.end() :]
)

# Select the constructor dimension by Scala type rather than the conventional
# `depth` spelling. This substitution is intentionally narrow and fail-closed.
lookup = re.compile(
    r'''val (\w+) = parameters\.find\(parameter =>\s*'''
    r'''decoded\(parameter\.name\) == "depth"\)'''
)
lookup_matches = list(lookup.finditer(value))
if len(lookup_matches) == 1:
    match = lookup_matches[0]
    selected_name = match.group(1)
    replacement = f'''val integerParameters = parameters.filter {{ parameter =>
        parameter.symbol != null && parameter.symbol != NoSymbol &&
        parameter.symbol.info != null &&
        (parameter.symbol.info =:= definitions.IntTpe)
      }}
      val {selected_name} =
        if (integerParameters.size == 1) integerParameters.headOption else None'''
    value = value[: match.start()] + replacement + value[match.end() :]
elif len(lookup_matches) > 1:
    raise SystemExit(
        f"generic native capture constructor lookup count={len(lookup_matches)}"
    )

# Component names may remain only in old diagnostics or source guards; replace
# them with neutral wording after structural selection is installed.
value = value.replace("StreamFifoCC", "native formal component")
value = value.replace("StreamFifo", "native formal component")
value = value.replace("BufferCC", "native data-shape component")
value = value.replace(
    "/lib/src/main/scala/spinal/lib/Stream.scala", "<native-spinal-source>"
)
value = value.replace(
    "/lib/src/main/scala/spinal/lib/CrossClock.scala", "<native-spinal-source>"
)
value = value.replace("pushToPopGray", "nativePointerTransfer")
value = value.replace("popToPushGray", "nativePointerReturn")

forbidden = re.compile(
    r"StreamFifo(?:CC)?|BufferCC|Stream\.scala|CrossClock\.scala|"
    r"pushToPopGray|popToPushGray"
)
problem = forbidden.search(value)
if problem:
    raise SystemExit(
        f"generic native capture retained forbidden token {problem.group(0)!r}"
    )

path.write_text(value)
