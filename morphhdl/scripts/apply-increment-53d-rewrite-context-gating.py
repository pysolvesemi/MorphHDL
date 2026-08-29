#!/usr/bin/env python3
"""Scope native Int/Bool rewrites to discovered runtime boundaries.

Semantic source eligibility may be broad (`widthOf` anywhere), but ordinary
SpinalHDL code outside a proven native boundary must remain under the stock
Scala transformer. Explicit MorphHDL shadow sources retain their legacy whole-
source instrumentation contract.
"""

from pathlib import Path
from textwrap import dedent


PLUGIN = Path(
    "morphplugin/src/main/scala/morphhdl/compiler/"
    "MorphHdlNativeIntShadowExpressionComponent.scala"
)
TEST = Path(
    "morphhdl/src/test/scala/morphhdl/"
    "GenericNativeDefinitionBoundaryTests.scala"
)
GUARD = Path("morphhdl/scripts/check-native-stream-width-adapter-boundary.sh")
DOC = Path("docs/morphhdl/increment-53d-native-streamwidth-adapter.md")


def replace_once(text: str, old: str, new: str, role: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"expected one {role}, found {count}")
    return text.replace(old, new, 1)


def patch_plugin() -> None:
    text = PLUGIN.read_text(encoding="utf-8")

    state_marker = "    private var nativeWidthFunctionDepth = 0\n"
    context_block = dedent(
        '''

            /**
              * Explicit MorphHDL shadow sources retain the Increment 49 whole-source
              * instrumentation contract. Semantically discovered native library
              * sources rewrite only while an exact constructor or width-function
              * runtime boundary is active.
              */
            private val explicitShadowSource: Boolean = {
              val content = Option(unit.source).map(_.content.mkString).getOrElse("")
              content.contains("NativeIntShadow") || content.contains("shadowInt")
            }

            private def inNativeRewriteContext: Boolean =
              explicitShadowSource || inNativeRuntimeContext
        '''
    ).rstrip() + "\n"
    context_block = "".join(
        "    " + line if line.strip() else line
        for line in context_block.splitlines(True)
    )
    if "private def inNativeRewriteContext" not in text:
        text = replace_once(
            text,
            state_marker,
            state_marker + context_block,
            "native width state marker",
        )

    text = replace_once(
        text,
        "      case conditional: If    => rewriteIf(conditional)\n",
        "      case conditional: If if inNativeRewriteContext => rewriteIf(conditional)\n",
        "conditional rewrite case",
    )
    text = replace_once(
        text,
        "      case value: ValDef =>\n",
        "      case value: ValDef if inNativeRewriteContext =>\n",
        "ValDef rewrite case",
    )
    text = replace_once(
        text,
        "      case assignment: Assign =>\n",
        "      case assignment: Assign if inNativeRewriteContext =>\n",
        "assignment rewrite case",
    )
    text = replace_once(
        text,
        "      case other => rewriteExpression(other, None).tree\n",
        "      case other if inNativeRewriteContext =>\n"
        "        rewriteExpression(other, None).tree\n"
        "      case other => super.transform(other)\n",
        "fallback rewrite case",
    )

    required = (
        "private val explicitShadowSource: Boolean",
        "private def inNativeRewriteContext: Boolean",
        "explicitShadowSource || inNativeRuntimeContext",
        "case conditional: If if inNativeRewriteContext",
        "case value: ValDef if inNativeRewriteContext",
        "case assignment: Assign if inNativeRewriteContext",
        "case other if inNativeRewriteContext",
        "case other => super.transform(other)",
    )
    for value in required:
        if value not in text:
            raise SystemExit("rewrite-context gating is incomplete: " + value)

    forbidden = (
        "case conditional: If    => rewriteIf(conditional)",
        "case other => rewriteExpression(other, None).tree",
    )
    for value in forbidden:
        if value in text:
            raise SystemExit("unscoped native rewrite remains: " + value)

    PLUGIN.write_text(text, encoding="utf-8")


def patch_test() -> None:
    text = TEST.read_text(encoding="utf-8")
    title = (
        'test("semantic eligibility never enables unbounded whole-source rewrites")'
    )
    if title not in text:
        insertion = dedent(
            '''

              test("semantic eligibility never enables unbounded whole-source rewrites") {
                val state = section(
                  source,
                  "private var nativeWidthFunctionDepth",
                  "private val NativeStreamFifoStaticBooleanNames"
                )
                assert(state.contains("private val explicitShadowSource: Boolean"))
                assert(state.contains("private def inNativeRewriteContext: Boolean"))
                assert(
                  state.contains(
                    "explicitShadowSource || inNativeRuntimeContext"
                  )
                )

                val transform = section(
                  source,
                  "override def transform(tree: Tree)",
                  "override def newPhase"
                )
                Vector(
                  "case conditional: If if inNativeRewriteContext",
                  "case value: ValDef if inNativeRewriteContext",
                  "case assignment: Assign if inNativeRewriteContext",
                  "case other if inNativeRewriteContext",
                  "case other => super.transform(other)"
                ).foreach { contract =>
                  assert(transform.contains(contract), s"missing rewrite gate: $contract")
                }
                assert(
                  !transform.contains(
                    "case conditional: If    => rewriteIf(conditional)"
                  )
                )
              }
            '''
        ).rstrip()
        close = text.rfind("\n}")
        if close < 0:
            raise SystemExit("architecture test class close was not found")
        text = text[:close] + insertion + text[close:]
    TEST.write_text(text, encoding="utf-8")


def patch_guard() -> None:
    text = GUARD.read_text(encoding="utf-8")
    marker = "grep -Fq 'else unstable += current' \"$plugin\"\n"
    addition = (
        "grep -Fq 'private val explicitShadowSource: Boolean' \"$plugin\"\n"
        "grep -Fq 'private def inNativeRewriteContext: Boolean' \"$plugin\"\n"
        "grep -Fq 'case conditional: If if inNativeRewriteContext' \"$plugin\"\n"
        "grep -Fq 'case value: ValDef if inNativeRewriteContext' \"$plugin\"\n"
        "grep -Fq 'case assignment: Assign if inNativeRewriteContext' \"$plugin\"\n"
        "grep -Fq 'case other => super.transform(other)' \"$plugin\"\n"
    )
    if "private def inNativeRewriteContext: Boolean" not in text:
        text = replace_once(
            text,
            marker,
            marker + addition,
            "rewrite-context guard insertion point",
        )
    GUARD.write_text(text, encoding="utf-8")


def patch_doc() -> None:
    text = DOC.read_text(encoding="utf-8")
    paragraph = dedent(
        '''
            Semantic source-unit eligibility does not imply whole-unit rewriting.
            Existing explicit `NativeIntShadow`/`shadowInt` sources retain their
            established instrumentation behavior, while ordinary SpinalHDL files
            discovered through `widthOf` are transformed only inside an active,
            definition-owned constructor or width-function boundary. All other trees
            fall through to the stock Scala transformer. This prevents unrelated Int,
            Boolean, overload-resolution and lexical-scope behavior from changing in
            any current or future SpinalHDL component.
        '''
    ).strip()
    if paragraph not in text:
        text = text.rstrip() + "\n\n" + paragraph + "\n"
    DOC.write_text(text, encoding="utf-8")


def main() -> None:
    patch_plugin()
    patch_test()
    patch_guard()
    patch_doc()


if __name__ == "__main__":
    main()
