#!/usr/bin/env python3
from pathlib import Path
import runpy

# Build the complete generic source-tree transformation first.
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

regression = Path(
    "morphhdl/src/test/scala/morphhdl/GenericNativeDefinitionBoundaryTests.scala"
)
value = regression.read_text(encoding="utf-8")
marker = """            assert(value.contains("private def localNativeWidthNames"))
            assert(value.contains("private def nativeWidthRootAvailableAtEntry"))
"""
replacement = """            assert(value.contains("private def localNativeWidthNames"))
            assert(value.contains("case Bind(name: TermName, body) =>"))
            assert(value.contains("private def nativeWidthRootAvailableAtEntry"))
"""
if value.count(marker) != 1:
    raise SystemExit("generic regression insertion point is ambiguous")
regression.write_text(value.replace(marker, replacement, 1), encoding="utf-8")

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
        raise SystemExit(f"component-specific generic-boundary fragment remains: {forbidden}")

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
