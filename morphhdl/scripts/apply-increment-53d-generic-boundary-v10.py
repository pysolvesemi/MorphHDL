from pathlib import Path
from textwrap import dedent
import runpy

# Build the complete generic v9 repair first.
runpy.run_path(
    "morphhdl/scripts/apply-increment-53d-generic-boundary-v9.py",
    run_name="__main__",
)

plugin = Path(
    "morphplugin/src/main/scala/morphhdl/compiler/"
    "MorphHdlNativeIntShadowExpressionComponent.scala"
)
text = plugin.read_text(encoding="utf-8")

# Rebuild the opening of the production transformer as one syntactic unit.
# Earlier publisher scripts patched adjacent cases independently, which left an
# ambiguous parser layout even though each individual case body was correct.
transform_start = text.index(
    "    override def transform(tree: Tree): Tree = tree match {"
)
fallback_def = text.index(
    "      case definition: DefDef => withScope(super.transform(definition))",
    transform_start,
)
prefix = dedent(
    r'''
        override def transform(tree: Tree): Tree = tree match {
          case value: ClassDef
              if sourceFile.replace('\\', '/').endsWith(
                "/lib/src/main/scala/spinal/lib/Stream.scala"
              ) && decoded(value.name) == "StreamFifo" =>
            transformNativeStreamFifo(value)
          case value: ClassDef if inNativeRuntimeContext =>
            withoutEnclosingNativeRuntimeContext {
              withScope(super.transform(value))
            }
          case value: ModuleDef if inNativeRuntimeContext =>
            withoutEnclosingNativeRuntimeContext {
              withScope(super.transform(value))
            }
          case application @ Apply(Select(condition, name), List(body))
              if inNativeStreamFifo && decoded(name) == "generate" =>
            normalizeGenerate(application, condition, body)
          case value: Match if inNativeStreamFifo =>
            normalizeBooleanMatch(value).getOrElse(super.transform(value))
          case template: Template => withScope(super.transform(template))
          case block: Block       => withScope(super.transform(block))
          case function: Function => withScope(super.transform(function))
          case definition: DefDef if decoded(definition.name) != "<init>" =>
            // A named method never inherits the native capture of its lexical
            // owner. While an enclosing native boundary is active, clear it and
            // transform the nested method normally; do not discover or open a
            // second boundary from inside the first one. A top-level method may
            // independently own direct method-entry width roots.
            if (inNativeRuntimeContext) {
              withoutEnclosingNativeRuntimeContext {
                withScope(super.transform(definition))
              }
            } else {
              transformIndependentNativeDefinition(definition)
            }
    '''
).lstrip()
prefix = "".join(
    "    " + line if line.strip() else line for line in prefix.splitlines(True)
)
text = text[:transform_start] + prefix + text[fallback_def:]

# Static sanity: the rebuilt prefix must contain balanced braces and exactly one
# generic named-method case before the constructor fallback.
rebuilt = text[transform_start:text.index(
    "      case definition: DefDef => withScope(super.transform(definition))",
    transform_start,
)]
if rebuilt.count("{") != rebuilt.count("}") + 1:
    raise SystemExit(
        "rebuilt transformer prefix has unexpected brace balance: "
        f"opens={rebuilt.count('{')} closes={rebuilt.count('}')}"
    )
if rebuilt.count(
    'case definition: DefDef if decoded(definition.name) != "<init>"'
) != 1:
    raise SystemExit("generic named-method case count is not exactly one")
if "transformIndependentNativeDefinition(definition)" not in rebuilt:
    raise SystemExit("top-level independent method discovery is missing")
if "withScope(super.transform(definition))" not in rebuilt:
    raise SystemExit("active-context nested method transformation is missing")
plugin.write_text(text, encoding="utf-8")

# Keep the architecture regression aligned with the explicit-brace production
# form while continuing to prohibit component-named ownership decisions.
regression = Path(
    "morphhdl/src/test/scala/morphhdl/GenericNativeDefinitionBoundaryTests.scala"
)
value = regression.read_text(encoding="utf-8")
value = value.replace(
    'assert(definitionCase.contains("if (inNativeRuntimeContext)"))',
    'assert(definitionCase.contains("if (inNativeRuntimeContext) {"))',
)
regression.write_text(value, encoding="utf-8")
