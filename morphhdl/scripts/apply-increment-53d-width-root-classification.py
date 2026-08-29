#!/usr/bin/env python3
"""Refine generic widthOf discovery for Increment 53d.

An unstable-only widthOf call is an ordinary concrete SpinalHDL width query and
must not open a parameterization boundary. A definition that has at least one
stable Data-identity root must fail closed if another direct widthOf root is
unstable, preventing partial symbolic capture.
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


def patch_plugin() -> None:
    text = PLUGIN.read_text(encoding="utf-8")
    start_marker = (
        "    /**\n"
        "      * Discover direct `widthOf(Data)` roots in one native method body. Nested\n"
    )
    end_marker = "    private def transformNativeWidthFunction(\n"
    start = text.index(start_marker)
    end = text.index(end_marker, start)

    replacement = dedent(
        '''
            /**
              * Classify direct `widthOf(Data)` calls in one named definition.
              * Nested definitions and function literals own independent lifetimes and
              * are deliberately excluded from their lexical owner's scan.
              *
              * A stable Ident/Select root carries an exact Data identity and can open
              * a native parameterization boundary. An unstable-only call, such as
              * `widthOf(factory())`, is ordinary concrete SpinalHDL code and remains
              * untouched. When stable and unstable roots occur together, partial
              * capture would be unsound, so the complete definition fails closed.
              */
            private def nativeWidthRootScan(
                tree: Tree
            ): (Vector[Tree], Vector[Tree]) = {
              val stable = mutable.ArrayBuffer.empty[Tree]
              val unstable = mutable.ArrayBuffer.empty[Tree]
              object Finder extends Traverser {
                override def traverse(current: Tree): Unit = current match {
                  case _: DefDef | _: ClassDef | _: ModuleDef | _: Function =>
                  case Apply(fun, List(data)) if terminalName(fun) == "widthOf" =>
                    if (stableNativeWidthRoot(data)) stable += data
                    else unstable += current
                  case _ => super.traverse(current)
                }
              }
              Finder.traverse(tree)
              val canonicalStable = stable
                .groupBy(path)
                .toVector
                .sortBy(_._1)
                .map(_._2.head)
              (canonicalStable, unstable.toVector)
            }

            private def nativeWidthRoots(tree: Tree): Vector[Tree] = {
              val (stable, unstable) = nativeWidthRootScan(tree)
              if (stable.nonEmpty && unstable.nonEmpty) {
                val unsupported = unstable.head
                global.reporter.error(
                  unsupported.pos,
                  "MORPHDL-NATIVE-WIDTH-FUNCTION-ROOT-UNSTABLE: a parameterized native definition requires every direct widthOf root to be an Ident/Select Data identity"
                )
              }
              stable
            }

        '''
    ).lstrip()
    replacement = "".join(
        "    " + line if line.strip() else line
        for line in replacement.splitlines(True)
    )
    text = text[:start] + replacement + text[end:]

    required = (
        "private def nativeWidthRootScan",
        "if (stable.nonEmpty && unstable.nonEmpty)",
        "else unstable += current",
        "capture would be unsound",
    )
    for value in required:
        if value not in text:
            raise SystemExit("generic width-root classification is incomplete: " + value)

    old_unconditional = (
        "if (!stableNativeWidthRoot(data)) {\n"
        "              global.reporter.error"
    )
    if old_unconditional in text:
        raise SystemExit("unconditional unstable widthOf rejection remains")

    PLUGIN.write_text(text, encoding="utf-8")


def patch_test() -> None:
    text = TEST.read_text(encoding="utf-8")
    title = (
        'test("unstable-only width queries remain concrete while mixed roots fail closed")'
    )
    if title not in text:
        insertion = dedent(
            '''

              test("unstable-only width queries remain concrete while mixed roots fail closed") {
                val scan = section(
                  source,
                  "private def nativeWidthRootScan",
                  "private def transformNativeWidthFunction"
                )
                assert(scan.contains("else unstable += current"))
                assert(scan.contains("if (stable.nonEmpty && unstable.nonEmpty)"))
                assert(scan.contains("capture would be unsound"))
                assert(
                  !scan.contains(
                    "if (!stableNativeWidthRoot(data)) {"
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
    marker = (
        "grep -Fq 'transformIndependentNativeDefinition(definition)' \"$plugin\"\n"
    )
    addition = (
        "grep -Fq 'private def nativeWidthRootScan' \"$plugin\"\n"
        "grep -Fq 'if (stable.nonEmpty && unstable.nonEmpty)' \"$plugin\"\n"
        "grep -Fq 'else unstable += current' \"$plugin\"\n"
    )
    if "private def nativeWidthRootScan" not in text:
        count = text.count(marker)
        if count != 1:
            raise SystemExit(f"expected one generic guard insertion point, found {count}")
        text = text.replace(marker, marker + addition, 1)
    GUARD.write_text(text, encoding="utf-8")


def patch_doc() -> None:
    text = DOC.read_text(encoding="utf-8")
    paragraph = dedent(
        '''
            `widthOf` discovery classifies roots by exact Data-identity stability.
            Definitions containing only ephemeral calls such as `widthOf(factory())`
            remain ordinary concrete SpinalHDL and do not open a capture boundary.
            A definition containing at least one stable root still fails closed if any
            other direct root is unstable, so MorphHDL never performs partial symbolic
            capture. This classification is structural and independent of library,
            component, file, method or emitted-signal names.
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
