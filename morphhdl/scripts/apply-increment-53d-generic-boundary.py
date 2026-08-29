#!/usr/bin/env python3
from pathlib import Path
from textwrap import dedent


def indented(value: str, prefix: str) -> str:
    return ''.join(prefix + line if line.strip() else line for line in value.splitlines(True))


def replace_once(text: str, old: str, new: str, label: str) -> str:
    if text.count(old) != 1:
        raise SystemExit(f"expected exactly one {label}, found {text.count(old)}")
    return text.replace(old, new, 1)


plugin = Path(
    "morphplugin/src/main/scala/morphhdl/compiler/"
    "MorphHdlNativeIntShadowExpressionComponent.scala"
)
text = plugin.read_text(encoding="utf-8")

old_helper = indented(dedent('''
    private def withoutNativeStreamFifoContext[A](body: => A): A = {
      val previousDataTypeName = nativeStreamFifoDataTypeName
      val previousDepthName = nativeStreamFifoDepthName
      val previousDepthReference = nativeStreamFifoDepthReference
      val previousDepthLine = nativeStreamFifoDepthLine
      val previousStaticBooleans = nativeStreamFifoStaticBooleans
      nativeStreamFifoDataTypeName = None
      nativeStreamFifoDepthName = None
      nativeStreamFifoDepthReference = None
      nativeStreamFifoStaticBooleans = Set.empty
      try body
      finally {
        nativeStreamFifoDataTypeName = previousDataTypeName
        nativeStreamFifoDepthName = previousDepthName
        nativeStreamFifoDepthReference = previousDepthReference
        nativeStreamFifoDepthLine = previousDepthLine
        nativeStreamFifoStaticBooleans = previousStaticBooleans
      }
    }

''').lstrip(), "    ")

new_helper = indented(dedent('''
    /**
      * Native runtime capture is owned by the exact named definition
      * that opens it. A nested named definition has an independent
      * elaboration lifetime and therefore cannot inherit an enclosing
      * constructor or native width-function capture. Function literals
      * remain in the current definition because their bodies execute as
      * part of that definition's elaboration.
      */
    private def withoutEnclosingNativeRuntimeContext[A](body: => A): A = {
      val previousStreamFifoDataTypeName = nativeStreamFifoDataTypeName
      val previousStreamFifoDepthName = nativeStreamFifoDepthName
      val previousStreamFifoDepthReference = nativeStreamFifoDepthReference
      val previousStreamFifoDepthLine = nativeStreamFifoDepthLine
      val previousStreamFifoStaticBooleans = nativeStreamFifoStaticBooleans
      val previousWidthFunctionDepth = nativeWidthFunctionDepth
      val previousWidthFunctionStaticBooleans = nativeWidthFunctionStaticBooleans

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
        nativeWidthFunctionStaticBooleans = previousWidthFunctionStaticBooleans
      }
    }

''').lstrip(), "    ")
text = replace_once(text, old_helper, new_helper, "component-specific context helper")

old_cases = indented(dedent('''
    case definition: DefDef
        if inNativeStreamFifo && decoded(definition.name) != "<init>" =>
      // StreamFifo owns a dedicated constructor-capture contract. Isolate
      // every nested method before the generic width-function matcher so a
      // helper driver cannot move into a symbolic body while its consumer
      // remains at module scope.
      withoutNativeStreamFifoContext {
        withScope(super.transform(definition))
      }
    case definition: DefDef if decoded(definition.name) != "<init>" =>
      val roots = nativeWidthRoots(definition.rhs)
      if (roots.nonEmpty) transformNativeWidthFunction(definition, roots)
      else withScope(super.transform(definition))
''').lstrip(), "      ")
new_cases = indented(dedent('''
    case definition: DefDef if decoded(definition.name) != "<init>" =>
      // A named definition never inherits the active native capture of
      // its lexical owner. After clearing that state, discover and open
      // any direct width-function boundary owned by this definition.
      if (inNativeRuntimeContext)
        withoutEnclosingNativeRuntimeContext {
          transformIndependentNativeDefinition(definition)
        }
      else transformIndependentNativeDefinition(definition)
''').lstrip(), "      ")

independent = indented(dedent('''
    private def transformIndependentNativeDefinition(
        definition: DefDef
    ): Tree = {
      val roots = nativeWidthRoots(definition.rhs)
      if (roots.nonEmpty) transformNativeWidthFunction(definition, roots)
      else withScope(super.transform(definition))
    }

''').lstrip(), "    ")
override_marker = "    override def transform(tree: Tree): Tree = tree match {\n"
text = replace_once(text, override_marker, independent + override_marker, "transform override marker")
text = replace_once(text, old_cases, new_cases, "component-specific DefDef cases")

if "withoutNativeStreamFifoContext" in text:
    raise SystemExit("StreamFifo-only context helper remains")
if 'if inNativeStreamFifo && decoded(definition.name)' in text:
    raise SystemExit("StreamFifo-only DefDef guard remains")
if text.count("withoutEnclosingNativeRuntimeContext") < 2:
    raise SystemExit("generic context helper is not used")
if text.count("transformIndependentNativeDefinition") < 3:
    raise SystemExit("independent definition transformer is incomplete")
plugin.write_text(text, encoding="utf-8")

regression = Path(
    "morphhdl/src/test/scala/morphhdl/GenericNativeDefinitionBoundaryTests.scala"
)
regression.write_text(dedent('''
    package morphhdl

    import java.nio.charset.StandardCharsets
    import java.nio.file.{Files, Path, Paths}

    import org.scalatest.funsuite.AnyFunSuite

    /**
      * Compiler architecture regression for every ordinary SpinalHDL
      * named definition. Library components remain functional witnesses;
      * their names are not recognition keys for this lifetime rule.
      */
    class GenericNativeDefinitionBoundaryTests extends AnyFunSuite {
      private val relativePluginPath = Paths.get(
        "morphplugin/src/main/scala/morphhdl/compiler/" +
          "MorphHdlNativeIntShadowExpressionComponent.scala"
      )

      private def repositoryRoot: Path = {
        var current = Paths.get("").toAbsolutePath.normalize()
        while (current != null) {
          if (Files.isRegularFile(current.resolve(relativePluginPath)))
            return current
          current = current.getParent
        }
        throw new IllegalStateException(
          s"cannot locate repository root containing $relativePluginPath"
        )
      }

      private lazy val source: String =
        new String(
          Files.readAllBytes(repositoryRoot.resolve(relativePluginPath)),
          StandardCharsets.UTF_8
        )

      test("every named definition uses one component-agnostic capture boundary") {
        val value = source
        assert(value.contains("private def withoutEnclosingNativeRuntimeContext"))
        assert(value.contains("private def transformIndependentNativeDefinition"))
        assert(value.contains("if (inNativeRuntimeContext)"))
        assert(value.contains("transformIndependentNativeDefinition(definition)"))
        assert(!value.contains("if inNativeStreamFifo && decoded(definition.name)"))
        assert(!value.contains("withoutNativeStreamFifoContext"))
      }

      test("the generic reset covers every current native runtime context family") {
        val value = source
        val start = value.indexOf(
          "private def withoutEnclosingNativeRuntimeContext"
        )
        val end = value.indexOf("private def trackedInteger", start)
        assert(start >= 0 && end > start)
        val helper = value.substring(start, end)

        Vector(
          "nativeStreamFifoDataTypeName",
          "nativeStreamFifoDepthName",
          "nativeStreamFifoDepthReference",
          "nativeStreamFifoDepthLine",
          "nativeStreamFifoStaticBooleans",
          "nativeWidthFunctionDepth",
          "nativeWidthFunctionStaticBooleans"
        ).foreach { state =>
          assert(helper.contains(state), s"generic reset omitted $state")
        }
        assert(helper.contains("nativeStreamFifoDepthReference = None"))
        assert(helper.contains("nativeWidthFunctionDepth = 0"))
        assert(helper.contains("nativeWidthFunctionStaticBooleans = Set.empty"))
      }

      test("each isolated definition independently discovers its direct width roots") {
        val value = source
        val start = value.indexOf(
          "private def transformIndependentNativeDefinition"
        )
        val end = value.indexOf("override def transform", start)
        assert(start >= 0 && end > start)
        val helper = value.substring(start, end)
        assert(helper.contains("nativeWidthRoots(definition.rhs)"))
        assert(helper.contains("transformNativeWidthFunction(definition, roots)"))
        assert(helper.contains("withScope(super.transform(definition))"))
      }
    }
''').lstrip(), encoding="utf-8")

guard = Path("morphhdl/scripts/check-native-stream-width-adapter-boundary.sh")
guard_text = guard.read_text(encoding="utf-8")
guard_text = replace_once(
    guard_text,
    'test_file="morphhdl/src/test/scala/morphhdl/ParameterizedStreamWidthAdapterTests.scala"\n',
    'test_file="morphhdl/src/test/scala/morphhdl/ParameterizedStreamWidthAdapterTests.scala"\n'
    'generic_test_file="morphhdl/src/test/scala/morphhdl/GenericNativeDefinitionBoundaryTests.scala"\n',
    "guard test-file declaration"
)
guard_text = replace_once(
    guard_text,
    'for required in "$plugin" "$runtime" "$registry" "$resize_registry" "$test_file" "$doc_file"; do\n',
    'for required in "$plugin" "$runtime" "$registry" "$resize_registry" "$test_file" "$generic_test_file" "$doc_file"; do\n',
    "guard required-file loop"
)
marker = 'grep -Fq \'terminalName(fun) == "widthOf"\' "$plugin"\n'
addition = dedent('''
    grep -Fq 'private def withoutEnclosingNativeRuntimeContext' "$plugin"
    grep -Fq 'if (inNativeRuntimeContext)' "$plugin"
    grep -Fq 'transformIndependentNativeDefinition(definition)' "$plugin"
    grep -Fq 'class GenericNativeDefinitionBoundaryTests' "$generic_test_file"

    if grep -Fq 'withoutNativeStreamFifoContext' "$plugin"; then
      echo "Named-definition isolation must not use a StreamFifo-only helper" >&2
      exit 1
    fi
    if grep -Fq 'if inNativeStreamFifo && decoded(definition.name)' "$plugin"; then
      echo "Named-definition isolation must be generic across native contexts" >&2
      exit 1
    fi
''').lstrip()
guard_text = replace_once(guard_text, marker, marker + addition, "guard plugin marker")
guard.write_text(guard_text, encoding="utf-8")

doc = Path("docs/morphhdl/increment-53d-native-streamwidth-adapter.md")
doc_text = doc.read_text(encoding="utf-8")
section = dedent('''

    ## Generic named-definition lifetime closure

    Native symbolic capture belongs to the exact Scala definition that opens
    the runtime boundary. Every non-constructor `DefDef` reached while an
    enclosing constructor or native width-function boundary is active now
    snapshots and clears the complete native runtime-capture state, then
    independently discovers any direct `widthOf(Data)` roots owned by that
    definition and opens a fresh boundary only when required. Function
    literals remain inside their owning definition because they execute as
    part of that definition body.

    This is a compiler-wide lexical rule, not a `StreamFifo`, `StreamFifoCC`,
    or `StreamWidthAdapter` exception. The production transform contains no
    component-name condition for named-definition isolation. Native StreamFifo
    compound-depth and native StreamWidthAdapter suites remain functional
    witnesses, while a dedicated dual-Scala architecture regression prevents
    the generic rule from regressing. Any present or future SpinalHDL component
    that enters the same native constructor or `widthOf` capture machinery
    receives the same ownership and dominance protection automatically.
''').rstrip() + "\n"
if "## Generic named-definition lifetime closure" in doc_text:
    raise SystemExit("generic closure documentation already exists unexpectedly")
doc.write_text(doc_text.rstrip() + section, encoding="utf-8")
