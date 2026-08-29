#!/usr/bin/env python3
"""Apply the component-agnostic Increment 53d native-boundary repair.

This temporary publisher helper changes only MorphHDL-owned production,
contract, guard and documentation files. It is removed before publication.
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

    old_eligibility = (
        'normalizedPath.endsWith('
        '"/lib/src/main/scala/spinal/lib/Stream.scala")'
    )
    new_eligibility = 'content.contains("widthOf")'
    if old_eligibility in text:
        text = replace_once(
            text,
            old_eligibility,
            new_eligibility,
            "Stream.scala eligibility clause",
        )
    elif new_eligibility not in text:
        raise SystemExit("native source eligibility contract was not recognized")

    old_helper = "    private def withoutNativeStreamFifoContext"
    new_helper = "    private def withoutEnclosingNativeRuntimeContext"
    if old_helper in text:
        start = text.index(old_helper)
        end = text.index("    private def trackedInteger", start)
        helper = dedent(
            '''
                /**
                  * Runtime capture is owned by the exact named Scala definition that
                  * opens it. A nested named definition has an independent elaboration
                  * lifetime and must not inherit an enclosing constructor or native
                  * width-function capture. Function literals deliberately remain in
                  * their owning definition because they execute as part of that body.
                  */
                private def withoutEnclosingNativeRuntimeContext[A](body: => A): A = {
                  val previousStreamFifoDataTypeName = nativeStreamFifoDataTypeName
                  val previousStreamFifoDepthName = nativeStreamFifoDepthName
                  val previousStreamFifoDepthReference = nativeStreamFifoDepthReference
                  val previousStreamFifoDepthLine = nativeStreamFifoDepthLine
                  val previousStreamFifoStaticBooleans = nativeStreamFifoStaticBooleans
                  val previousWidthFunctionDepth = nativeWidthFunctionDepth
                  val previousWidthFunctionStaticBooleans =
                    nativeWidthFunctionStaticBooleans

                  nativeStreamFifoDataTypeName = None
                  nativeStreamFifoDepthName = None
                  nativeStreamFifoDepthReference = None
                  nativeStreamFifoDepthLine = 1
                  nativeStreamFifoStaticBooleans = Set.empty
                  nativeWidthFunctionDepth = 0
                  nativeWidthFunctionStaticBooleans = Set.empty

                  try body
                  finally {
                    nativeStreamFifoDataTypeName = previousStreamFifoDataTypeName
                    nativeStreamFifoDepthName = previousStreamFifoDepthName
                    nativeStreamFifoDepthReference = previousStreamFifoDepthReference
                    nativeStreamFifoDepthLine = previousStreamFifoDepthLine
                    nativeStreamFifoStaticBooleans = previousStreamFifoStaticBooleans
                    nativeWidthFunctionDepth = previousWidthFunctionDepth
                    nativeWidthFunctionStaticBooleans =
                      previousWidthFunctionStaticBooleans
                  }
                }

            '''
        ).lstrip()
        helper = "".join(
            "    " + line if line.strip() else line
            for line in helper.splitlines(True)
        )
        text = text[:start] + helper + text[end:]
    elif new_helper not in text:
        raise SystemExit("native runtime-context isolation helper was not recognized")

    independent_name = "transformIndependentNativeDefinition"
    override_marker = "    override def transform(tree: Tree): Tree = tree match {\n"
    if independent_name not in text:
        method = dedent(
            '''
                private def transformIndependentNativeDefinition(
                    definition: DefDef
                ): Tree = {
                  val roots = nativeWidthRoots(definition.rhs)
                  if (roots.nonEmpty) transformNativeWidthFunction(definition, roots)
                  else withScope(super.transform(definition))
                }

            '''
        ).lstrip()
        method = "".join(
            "    " + line if line.strip() else line
            for line in method.splitlines(True)
        )
        text = replace_once(
            text,
            override_marker,
            method + override_marker,
            "transform override marker",
        )

    component_guard = (
        "      case definition: DefDef\n"
        "          if inNativeStreamFifo && decoded(definition.name) != \"<init>\" =>"
    )
    generic_case_marker = (
        "      case definition: DefDef if decoded(definition.name) != \"<init>\" =>\n"
        "        // Named definitions own independent native-capture lifetimes."
    )
    if component_guard in text:
        start = text.index(component_guard)
        end = text.index("      case definition: DefDef =>", start)
        generic_case = dedent(
            '''
                case definition: DefDef if decoded(definition.name) != "<init>" =>
                  // Named definitions own independent native-capture lifetimes.
                  // Clear every enclosing runtime context, then discover and open
                  // only the direct width-function boundary owned by this body.
                  if (inNativeRuntimeContext)
                    withoutEnclosingNativeRuntimeContext {
                      transformIndependentNativeDefinition(definition)
                    }
                  else transformIndependentNativeDefinition(definition)
            '''
        ).lstrip()
        generic_case = "".join(
            "      " + line if line.strip() else line
            for line in generic_case.splitlines(True)
        )
        text = text[:start] + generic_case + text[end:]
    elif generic_case_marker not in text:
        raise SystemExit("named-definition transformer contract was not recognized")

    forbidden = (
        "withoutNativeStreamFifoContext",
        "if inNativeStreamFifo && decoded(definition.name)",
        'normalizedPath.endsWith("/lib/src/main/scala/spinal/lib/Stream.scala")',
    )
    for value in forbidden:
        if value in text:
            raise SystemExit("component-specific native boundary remains: " + value)

    required = (
        'content.contains("widthOf")',
        "private def withoutEnclosingNativeRuntimeContext",
        "if (inNativeRuntimeContext)",
        "transformIndependentNativeDefinition(definition)",
        "nativeWidthRoots(definition.rhs)",
    )
    for value in required:
        if value not in text:
            raise SystemExit("generic native boundary is incomplete: " + value)

    PLUGIN.write_text(text, encoding="utf-8")


def write_architecture_test() -> None:
    TEST.write_text(
        dedent(
            '''
                package morphhdl

                import java.nio.charset.StandardCharsets
                import java.nio.file.{Files, Path, Paths}

                import org.scalatest.funsuite.AnyFunSuite

                /**
                  * Compiler architecture regression for every ordinary SpinalHDL
                  * definition. Native library components remain functional witnesses;
                  * no component or source-file name is a recognition key for this rule.
                  */
                class GenericNativeDefinitionBoundaryTests extends AnyFunSuite {
                  private val PluginRelativePath = Paths.get(
                    "morphplugin",
                    "src",
                    "main",
                    "scala",
                    "morphhdl",
                    "compiler",
                    "MorphHdlNativeIntShadowExpressionComponent.scala"
                  )

                  @scala.annotation.tailrec
                  private def locatePlugin(current: Path): Path = {
                    val candidate = current.resolve(PluginRelativePath)
                    if (Files.isRegularFile(candidate)) candidate
                    else {
                      val parent = current.getParent
                      if (parent == null) {
                        fail(
                          s"Unable to locate $PluginRelativePath from " +
                            Paths.get("").toAbsolutePath.normalize()
                        )
                      } else locatePlugin(parent)
                    }
                  }

                  private lazy val source: String = {
                    val plugin = locatePlugin(Paths.get("").toAbsolutePath.normalize())
                    new String(Files.readAllBytes(plugin), StandardCharsets.UTF_8)
                  }

                  private def section(
                      value: String,
                      startMarker: String,
                      endMarker: String
                  ): String = {
                    val start = value.indexOf(startMarker)
                    val end = value.indexOf(endMarker, start)
                    assert(start >= 0 && end > start)
                    value.substring(start, end)
                  }

                  test("semantic eligibility is independent of component and source-file names") {
                    val eligible = section(
                      source,
                      "private def eligible",
                      "private def helperMethod"
                    )
                    assert(eligible.contains("content.contains(\\\"widthOf\\\")"))
                    assert(eligible.contains("content.contains(\\\"NativeIntShadow\\\")"))
                    assert(!eligible.contains("Stream.scala"))
                    assert(!eligible.contains("StreamWidthAdapter"))
                    assert(!eligible.contains("StreamFifo"))
                    assert(!eligible.contains("StreamFifoCC"))
                  }

                  test("every named definition uses one component-agnostic capture lifetime") {
                    val transform = section(
                      source,
                      "override def transform(tree: Tree)",
                      "override def newPhase"
                    )
                    assert(
                      transform.contains(
                        "case definition: DefDef if decoded(definition.name) != \\\"<init>\\\""
                      )
                    )
                    assert(transform.contains("if (inNativeRuntimeContext)"))
                    assert(transform.contains("withoutEnclosingNativeRuntimeContext"))
                    assert(
                      transform.contains(
                        "transformIndependentNativeDefinition(definition)"
                      )
                    )
                    assert(
                      !transform.contains(
                        "if inNativeStreamFifo && decoded(definition.name)"
                      )
                    )
                    assert(!source.contains("withoutNativeStreamFifoContext"))
                  }

                  test("the isolation snapshot covers every current runtime-context family") {
                    val helper = section(
                      source,
                      "private def withoutEnclosingNativeRuntimeContext",
                      "private def trackedInteger"
                    )
                    Vector(
                      "nativeStreamFifoDataTypeName",
                      "nativeStreamFifoDepthName",
                      "nativeStreamFifoDepthReference",
                      "nativeStreamFifoDepthLine",
                      "nativeStreamFifoStaticBooleans",
                      "nativeWidthFunctionDepth",
                      "nativeWidthFunctionStaticBooleans"
                    ).foreach { state =>
                      assert(helper.contains(state), s"generic isolation omitted $state")
                    }
                    assert(helper.contains("nativeStreamFifoDepthReference = None"))
                    assert(helper.contains("nativeWidthFunctionDepth = 0"))
                    assert(
                      helper.contains(
                        "nativeWidthFunctionStaticBooleans = Set.empty"
                      )
                    )
                  }

                  test("each isolated definition rediscovers only its direct width roots") {
                    val helper = section(
                      source,
                      "private def transformIndependentNativeDefinition",
                      "override def transform(tree: Tree)"
                    )
                    assert(helper.contains("nativeWidthRoots(definition.rhs)"))
                    assert(
                      helper.contains(
                        "transformNativeWidthFunction(definition, roots)"
                      )
                    )
                    assert(helper.contains("withScope(super.transform(definition))"))
                  }
                }
            '''
        ).lstrip(),
        encoding="utf-8",
    )


def patch_guard() -> None:
    text = GUARD.read_text(encoding="utf-8")
    marker = 'grep -Fq \'terminalName(fun) == "widthOf"\' "$plugin"\n'
    addition = dedent(
        '''
            grep -Fq 'content.contains("widthOf")' "$plugin"
            grep -Fq 'private def withoutEnclosingNativeRuntimeContext' "$plugin"
            grep -Fq 'if (inNativeRuntimeContext)' "$plugin"
            grep -Fq 'transformIndependentNativeDefinition(definition)' "$plugin"

            if grep -Fq 'withoutNativeStreamFifoContext' "$plugin"; then
              echo "Named-definition isolation must not use a StreamFifo-only helper" >&2
              exit 1
            fi
            if grep -Fq 'if inNativeStreamFifo && decoded(definition.name)' "$plugin"; then
              echo "Named-definition isolation must be generic across native contexts" >&2
              exit 1
            fi
            if grep -Fq 'normalizedPath.endsWith("/lib/src/main/scala/spinal/lib/Stream.scala")' "$plugin"; then
              echo "Native width-function eligibility must not depend on Stream.scala" >&2
              exit 1
            fi
        '''
    ).lstrip()
    if "Native width-function eligibility must not depend on Stream.scala" not in text:
        text = replace_once(text, marker, marker + addition, "source guard marker")
    GUARD.write_text(text, encoding="utf-8")


def patch_doc() -> None:
    text = DOC.read_text(encoding="utf-8")
    section = dedent(
        '''
            ## Generic native definition-boundary closure

            Native symbolic capture is now owned by the exact named Scala definition
            that opens it. Every non-constructor `DefDef` reached under an enclosing
            constructor or native `widthOf(Data)` boundary snapshots and clears the
            complete active runtime context, independently discovers only the direct
            width roots in its own body, and opens a fresh width-function boundary
            when those roots require one. Function literals remain in their owning
            definition because their body executes as part of that definition.

            This is a compiler-wide lexical rule rather than a `StreamFifo`,
            `StreamFifoCC` or `StreamWidthAdapter` exception. Native source-unit
            eligibility is keyed to semantic `widthOf` usage or explicit MorphHDL
            shadow markers, not to `Stream.scala` or another component file name.
            Consequently, additional untouched SpinalHDL components using the same
            native width/provenance machinery receive the same lifetime, ownership
            and continuous-assignment dominance protection automatically.

            The permanent dual-Scala contract checks the component-agnostic compiler
            structure. Native StreamWidthAdapter, StreamFifo, AXI4 Slave Factory,
            enum-localparam, hierarchy, memory and process suites remain independent
            functional witnesses; none is used as a production recognition key.
        '''
    ).strip()
    if "## Generic native definition-boundary closure" not in text:
        text = text.rstrip() + "\n\n" + section + "\n"
    DOC.write_text(text, encoding="utf-8")


def main() -> None:
    patch_plugin()
    write_architecture_test()
    patch_guard()
    patch_doc()


if __name__ == "__main__":
    main()
