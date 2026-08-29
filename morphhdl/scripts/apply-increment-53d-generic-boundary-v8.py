from pathlib import Path
import runpy

patcher = Path("morphhdl/scripts/apply-increment-53d-generic-boundary-v4.py")
value = patcher.read_text(encoding="utf-8")

# Scope the v4 rewrite-guard edits to the production transformer. The source
# contains helper matchers with similar cases, so global occurrence counts are
# intentionally avoided.
start = value.index("for old, new, label in (\n")
end = value.index("\nfor forbidden in (", start)
positional = "\n".join(
    [
        "transform_start = text.index(",
        '    "    override def transform(tree: Tree): Tree = tree match {"',
        ")",
        "transform_end = text.index(",
        '    "\\n    }\\n  }\\n\\n  override def newPhase",',
        "    transform_start,",
        ")",
        "",
        "def replace_transform_case(marker: str, replacement: str, label: str) -> None:",
        "    global text, transform_end",
        "    index = text.rfind(marker, transform_start, transform_end)",
        "    if index < 0:",
        '        raise SystemExit(f"{label} was not found in the production transformer")',
        "    text = text[:index] + replacement + text[index + len(marker):]",
        "    transform_end += len(replacement) - len(marker)",
        "",
        "replace_transform_case(",
        '    "      case conditional: If    => rewriteIf(conditional)",',
        '    "      case conditional: If if inNativeRewriteContext => rewriteIf(conditional)",',
        '    "If rewrite guard",',
        ")",
        "replace_transform_case(",
        '    "      case value: ValDef =>",',
        '    "      case value: ValDef if inNativeRewriteContext =>",',
        '    "ValDef rewrite guard",',
        ")",
        "replace_transform_case(",
        '    "      case assignment: Assign =>",',
        '    "      case assignment: Assign if inNativeRewriteContext =>",',
        '    "Assign rewrite guard",',
        ")",
        "replace_transform_case(",
        '    "      case other => rewriteExpression(other, None).tree",',
        '    "      case other if inNativeRewriteContext => rewriteExpression(other, None).tree\\n"',
        '    "      case other                           => super.transform(other)",',
        '    "fallback rewrite guard",',
        ")",
        "",
    ]
)
patcher.write_text(value[:start] + positional + value[end:], encoding="utf-8")
runpy.run_path(str(patcher), run_name="__main__")

# Preserve the original method parameter symbols and transform only the RHS.
# Transforming the complete DefDef and then inserting roots from the original
# tree breaks bindings such as widthOf(bt) in generic native helper methods.
plugin = Path(
    "morphplugin/src/main/scala/morphhdl/compiler/"
    "MorphHdlNativeIntShadowExpressionComponent.scala"
)
text = plugin.read_text(encoding="utf-8")
old = """        val transformed = withScope(super.transform(definition)).asInstanceOf[DefDef]
        val rootSequence = Apply(
          scalaSeqApply,
          roots.map(_.duplicate).toList
        )
        val wrapped = Apply(
          Apply(
            helperMethod("withWidthFunctionBoundary"),
            List(rootSequence) ++ sourceArguments(definition)
          ),
          List(transformed.rhs)
        )
        wrapped.setPos(definition.rhs.pos)
        treeCopy.DefDef(
          transformed,
          transformed.mods,
          transformed.name,
          transformed.tparams,
          transformed.vparamss,
          transformed.tpt,
          wrapped
        )
"""
new = """        val transformedRhs = withScope(super.transform(definition.rhs))
        val rootSequence = Apply(
          scalaSeqApply,
          roots.map(_.duplicate).toList
        )
        val wrapped = Apply(
          Apply(
            helperMethod("withWidthFunctionBoundary"),
            List(rootSequence) ++ sourceArguments(definition)
          ),
          List(transformedRhs)
        )
        wrapped.setPos(definition.rhs.pos)
        treeCopy.DefDef(
          definition,
          definition.mods,
          definition.name,
          definition.tparams,
          definition.vparamss,
          definition.tpt,
          wrapped
        )
"""
if text.count(old) != 1:
    raise SystemExit("definition-symbol preservation replacement point is ambiguous")
text = text.replace(old, new, 1)
plugin.write_text(text, encoding="utf-8")

regression = Path(
    "morphhdl/src/test/scala/morphhdl/GenericNativeDefinitionBoundaryTests.scala"
)
regression_text = regression.read_text(encoding="utf-8")
needle = """    assert(value.contains("roots.map(_.duplicate).toList"))
    assert(!value.contains("MORPHDL-NATIVE-WIDTH-FUNCTION-ROOT-UNSTABLE"))
"""
replacement = """    assert(value.contains("roots.map(_.duplicate).toList"))
    assert(value.contains("val transformedRhs = withScope(super.transform(definition.rhs))"))
    assert(value.contains("List(transformedRhs)"))
    assert(!value.contains("super.transform(definition)).asInstanceOf[DefDef]"))
    assert(!value.contains("MORPHDL-NATIVE-WIDTH-FUNCTION-ROOT-UNSTABLE"))
"""
if regression_text.count(needle) != 1:
    raise SystemExit("definition-symbol regression insertion point is ambiguous")
regression.write_text(regression_text.replace(needle, replacement, 1), encoding="utf-8")

guard = Path("morphhdl/scripts/check-native-stream-width-adapter-boundary.sh")
guard_text = guard.read_text(encoding="utf-8")
anchor = "grep -Fq 'private def nativeWidthRootAvailableAtEntry' \"$plugin\"\n"
addition = """grep -Fq 'val transformedRhs = withScope(super.transform(definition.rhs))' "$plugin"
grep -Fq 'List(transformedRhs)' "$plugin"
if grep -Fq 'super.transform(definition)).asInstanceOf[DefDef]' "$plugin"; then
  echo "Native method transformation must preserve original DefDef parameter symbols" >&2
  exit 1
fi
"""
if "Native method transformation must preserve original DefDef parameter symbols" not in guard_text:
    if guard_text.count(anchor) != 1:
        raise SystemExit("definition-symbol guard insertion point is ambiguous")
    guard_text = guard_text.replace(anchor, anchor + addition, 1)
    guard.write_text(guard_text, encoding="utf-8")
