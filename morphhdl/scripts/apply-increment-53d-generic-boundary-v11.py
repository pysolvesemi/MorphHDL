from pathlib import Path
import runpy

# Start from the coherent generic v10 transformation, then close the first
# all-Spinal source-tree failure with a lexical-category rule. Pattern binders
# are local to their case alternatives and therefore cannot be evaluated at a
# named method's entry boundary.
runpy.run_path(
    "morphhdl/scripts/apply-increment-53d-generic-boundary-v10.py",
    run_name="__main__",
)

plugin = Path(
    "morphplugin/src/main/scala/morphhdl/compiler/"
    "MorphHdlNativeIntShadowExpressionComponent.scala"
)
text = plugin.read_text(encoding="utf-8")
old = """          case value: ValDef =>
            found += value.name
            super.traverse(value.rhs)
          case _ => super.traverse(current)
"""
new = """          case value: ValDef =>
            found += value.name
            super.traverse(value.rhs)
          case Bind(name: TermName, body) =>
            // Pattern variables are introduced below method entry. A widthOf
            // rooted in such a binder must stay in that lexical alternative
            // instead of being hoisted into a method-entry runtime boundary.
            found += name
            super.traverse(body)
          case _ => super.traverse(current)
"""
if text.count(old) != 1:
    raise SystemExit(
        "local-width declaration classifier insertion point is ambiguous"
    )
text = text.replace(old, new, 1)
plugin.write_text(text, encoding="utf-8")

# Repair two source-architecture assertions emitted by the earlier prototype.
# These tests inspect stable tokens only; they do not alter production behavior.
regression = Path(
    "morphhdl/src/test/scala/morphhdl/GenericNativeDefinitionBoundaryTests.scala"
)
value = regression.read_text(encoding="utf-8")
broken_defdef_assertion = 'assert(value.contains("treeCopy.DefDef(\n          definition,"))'
if value.count(broken_defdef_assertion) != 1:
    raise SystemExit("generated DefDef assertion repair point is ambiguous")
index = value.index(broken_defdef_assertion)
line_start = value.rfind("\n", 0, index) + 1
indent = value[line_start:index]
value = value.replace(
    broken_defdef_assertion,
    'assert(value.contains("treeCopy.DefDef("))\n'
    + indent
    + 'assert(value.contains("definition,"))',
    1,
)
broken_definition_locator = (
    '"case definition: DefDef if decoded(definition.name) != "<init>""'
)
if value.count(broken_definition_locator) != 1:
    raise SystemExit("generated nested-definition locator repair point is ambiguous")
value = value.replace(
    broken_definition_locator,
    '"case definition: DefDef if decoded(definition.name)"',
    1,
)
needle = 'assert(value.contains("private def localNativeWidthNames"))'
if value.count(needle) != 1:
    raise SystemExit("generic regression insertion point is ambiguous")
index = value.index(needle)
line_start = value.rfind("\n", 0, index) + 1
indent = value[line_start:index]
value = value.replace(
    needle,
    needle + "\n" + indent
    + 'assert(value.contains("case Bind(name: TermName, body) =>"))',
    1,
)
regression.write_text(value, encoding="utf-8")

guard = Path("morphhdl/scripts/check-native-stream-width-adapter-boundary.sh")
value = guard.read_text(encoding="utf-8")
marker = "grep -Fq 'private def nativeWidthRootAvailableAtEntry' \"$plugin\"\n"
replacement = marker + "grep -Fq 'case Bind(name: TermName, body) =>' \"$plugin\"\n"
if value.count(marker) != 1:
    raise SystemExit("generic source-guard insertion point is ambiguous")
guard.write_text(value.replace(marker, replacement, 1), encoding="utf-8")

doc = Path("docs/morphhdl/increment-53d-native-streamwidth-adapter.md")
value = doc.read_text(encoding="utf-8")
old = """Method-entry discovery accepts stable parameters and enclosing members. A
local Data value declared later in the method and a constructor or call
expression remain ordinary concrete SpinalHDL instead of being evaluated out
of order. Accepted roots and the method body are transformed together in one
lexical scope, while the original `DefDef` parameter symbols are retained.
"""
new = """Method-entry discovery accepts stable parameters and enclosing members. A
local Data value declared later in the method, a pattern-bound value inside a
match/case alternative, and a constructor or call expression remain ordinary
concrete SpinalHDL instead of being evaluated out of order. Accepted roots and
the method body are transformed together in one lexical scope, while the
original `DefDef` parameter symbols are retained.
"""
if value.count(old) != 1:
    raise SystemExit("generic documentation insertion point is ambiguous")
doc.write_text(value.replace(old, new, 1), encoding="utf-8")

for forbidden in (
    'normalizedPath.endsWith("/lib/src/main/scala/spinal/lib/Stream.scala")',
    "withoutNativeStreamFifoContext",
    "if inNativeStreamFifo && decoded(definition.name)",
):
    if forbidden in text:
        raise SystemExit(
            f"component-specific generic-boundary fragment remains: {forbidden}"
        )

for required in (
    "val upstreamSpinalComponentSource",
    'normalizedPath.contains("/core/src/main/scala/spinal/")',
    'normalizedPath.contains("/lib/src/main/scala/spinal/")',
    "explicitNativeShadowSource || inNativeRuntimeContext",
    "private def withoutEnclosingNativeRuntimeContext",
    "private def nativeWidthRootAvailableAtEntry",
    "case Bind(name: TermName, body) =>",
):
    if required not in text:
        raise SystemExit(f"generic architecture fragment is missing: {required}")
