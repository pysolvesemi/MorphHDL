from pathlib import Path
from textwrap import dedent
import runpy

# Reproduce the last fully compiling generic source-tree repair, then correct
# the two remaining generic semantic defects exposed by the functional suites.
runpy.run_path(
    "morphhdl/scripts/apply-increment-53d-generic-boundary-v8.py",
    run_name="__main__",
)

plugin = Path(
    "morphplugin/src/main/scala/morphhdl/compiler/"
    "MorphHdlNativeIntShadowExpressionComponent.scala"
)
text = plugin.read_text(encoding="utf-8")

old_width_transform = """        val transformedRhs = withScope(super.transform(definition.rhs))
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
"""
new_width_transform = """        val (rootSequence, transformedRhs) = withScope {
          val transformedRoots =
            roots.map(root => super.transform(root).duplicate).toList
          val transformedBody = super.transform(definition.rhs)
          (Apply(scalaSeqApply, transformedRoots), transformedBody)
        }
        val wrapped = Apply(
          Apply(
            helperMethod("withWidthFunctionBoundary"),
            List(rootSequence) ++ sourceArguments(definition)
          ),
          List(transformedRhs)
        )
"""
if text.count(old_width_transform) != 1:
    raise SystemExit("generic width-method transformation replacement point is ambiguous")
text = text.replace(old_width_transform, new_width_transform, 1)

old_nested_method = """      case definition: DefDef if decoded(definition.name) != "<init>" =>
        // A named method never inherits the native capture of its lexical
        // owner. It independently discovers any method-entry width roots and
        // opens a fresh boundary only when it owns them.
        if (inNativeRuntimeContext)
          withoutEnclosingNativeRuntimeContext {
            transformIndependentNativeDefinition(definition)
          }
        else transformIndependentNativeDefinition(definition)
"""
new_nested_method = """      case definition: DefDef if decoded(definition.name) != "<init>" =>
        // A named method never inherits the native capture of its lexical
        // owner. While an enclosing native boundary is active, clear it and
        // transform the nested method normally; do not discover or open a
        // second boundary from inside the first one. A top-level method may
        // independently own direct method-entry width roots.
        if (inNativeRuntimeContext)
          withoutEnclosingNativeRuntimeContext {
            withScope(super.transform(definition))
          }
        else transformIndependentNativeDefinition(definition)
"""
if text.count(old_nested_method) != 1:
    raise SystemExit("generic nested-method ownership replacement point is ambiguous")
text = text.replace(old_nested_method, new_nested_method, 1)

for forbidden in (
    "withoutNativeStreamFifoContext",
    "if inNativeStreamFifo && decoded(definition.name)",
    'normalizedPath.endsWith("/lib/src/main/scala/spinal/lib/Stream.scala")',
    "MORPHDL-NATIVE-WIDTH-FUNCTION-ROOT-UNSTABLE",
):
    if forbidden in text:
        raise SystemExit(f"component-specific or eager-rejection fragment remains: {forbidden}")
for required in (
    "val upstreamSpinalComponentSource",
    "explicitNativeShadowSource || inNativeRuntimeContext",
    "private def withoutEnclosingNativeRuntimeContext",
    "private def transformIndependentNativeDefinition",
    "private def nativeWidthRootAvailableAtEntry",
    "roots.map(root => super.transform(root).duplicate).toList",
    "val transformedBody = super.transform(definition.rhs)",
    "withScope(super.transform(definition))",
    "else transformIndependentNativeDefinition(definition)",
):
    if required not in text:
        raise SystemExit(f"generic architecture fragment is missing: {required}")
plugin.write_text(text, encoding="utf-8")

regression = Path(
    "morphhdl/src/test/scala/morphhdl/GenericNativeDefinitionBoundaryTests.scala"
)
regression.write_text(
    dedent(
        '''
        package morphhdl

        import java.nio.charset.StandardCharsets
        import java.nio.file.{Files, Path, Paths}

        import org.scalatest.funsuite.AnyFunSuite

        /**
          * Compiler-architecture regression for every upstream SpinalHDL
          * component. Library components are functional witnesses only; no
          * component name or source filename participates in capture ownership.
          */
        class GenericNativeDefinitionBoundaryTests extends AnyFunSuite {
          private val relativePlugin = Paths.get(
            "morphplugin/src/main/scala/morphhdl/compiler/" +
              "MorphHdlNativeIntShadowExpressionComponent.scala"
          )

          private def repositoryRoot: Path = {
            val start = Paths.get(System.getProperty("user.dir")).toAbsolutePath
            var current: Path = start
            while (
              current != null && !Files.isRegularFile(current.resolve(relativePlugin))
            ) current = current.getParent
            assert(current != null, s"Could not locate repository root above $start")
            current
          }

          private def source: String =
            new String(
              Files.readAllBytes(repositoryRoot.resolve(relativePlugin)),
              StandardCharsets.UTF_8
            )

          test("all upstream SpinalHDL component sources use generic phase eligibility") {
            val value = source
            assert(value.contains("val upstreamSpinalComponentSource"))
            assert(value.contains("/core/src/main/scala/spinal/"))
            assert(value.contains("/lib/src/main/scala/spinal/"))
            assert(!value.contains(
              "normalizedPath.endsWith(\\\"/lib/src/main/scala/spinal/lib/Stream.scala\\\")"
            ))
          }

          test("ordinary eligible SpinalHDL code rewrites only in a proven context") {
            val value = source
            assert(value.contains("private def inNativeRewriteContext"))
            assert(value.contains("explicitNativeShadowSource || inNativeRuntimeContext"))
            assert(value.contains("case conditional: If if inNativeRewriteContext"))
            assert(value.contains("case value: ValDef if inNativeRewriteContext"))
            assert(value.contains("case assignment: Assign if inNativeRewriteContext"))
            assert(value.contains("case other                           => super.transform(other)"))
          }

          test("every nested named method has a component-independent empty lifetime") {
            val value = source
            assert(value.contains("private def withoutEnclosingNativeRuntimeContext"))
            val caseStart = value.indexOf(
              "case definition: DefDef if decoded(definition.name) != \"<init>\""
            )
            val caseEnd = value.indexOf("case definition: DefDef =>", caseStart)
            assert(caseStart >= 0 && caseEnd > caseStart)
            val definitionCase = value.substring(caseStart, caseEnd)
            assert(definitionCase.contains("if (inNativeRuntimeContext)"))
            assert(definitionCase.contains("withoutEnclosingNativeRuntimeContext"))
            assert(definitionCase.contains("withScope(super.transform(definition))"))
            assert(definitionCase.contains("else transformIndependentNativeDefinition(definition)"))
            val activeStart = definitionCase.indexOf("if (inNativeRuntimeContext)")
            val elseStart = definitionCase.indexOf("else transformIndependentNativeDefinition")
            assert(activeStart >= 0 && elseStart > activeStart)
            assert(!definitionCase.substring(activeStart, elseStart)
              .contains("transformIndependentNativeDefinition"))
            assert(!value.contains("if inNativeStreamFifo && decoded(definition.name)"))
            assert(!value.contains("withoutNativeStreamFifoContext"))
          }

          test("the generic reset snapshots every current native runtime context family") {
            val value = source
            val start = value.indexOf("private def withoutEnclosingNativeRuntimeContext")
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
            ).foreach(state => assert(helper.contains(state), s"generic reset omitted $state"))
          }

          test("top-level width methods retain symbols and transform roots in method scope") {
            val value = source
            assert(value.contains("private def localNativeWidthNames"))
            assert(value.contains("private def nativeWidthRootAvailableAtEntry"))
            assert(value.contains("val parameters = definition.vparamss.flatten.map(_.name).toSet"))
            assert(value.contains("val locals = localNativeWidthNames(definition.rhs)"))
            assert(value.contains("val (rootSequence, transformedRhs) = withScope"))
            assert(value.contains("roots.map(root => super.transform(root).duplicate).toList"))
            assert(value.contains("val transformedBody = super.transform(definition.rhs)"))
            assert(value.contains("treeCopy.DefDef(\n          definition,"))
            assert(!value.contains("super.transform(definition)).asInstanceOf[DefDef]"))
            assert(!value.contains("MORPHDL-NATIVE-WIDTH-FUNCTION-ROOT-UNSTABLE"))
          }
        }
'''
    ).lstrip(),
    encoding="utf-8",
)

guard = Path("morphhdl/scripts/check-native-stream-width-adapter-boundary.sh")
guard_text = guard.read_text(encoding="utf-8")
old_guard_lines = """grep -Fq 'val transformedRhs = withScope(super.transform(definition.rhs))' "$plugin"
grep -Fq 'List(transformedRhs)' "$plugin"
"""
new_guard_lines = """grep -Fq 'val (rootSequence, transformedRhs) = withScope' "$plugin"
grep -Fq 'roots.map(root => super.transform(root).duplicate).toList' "$plugin"
grep -Fq 'val transformedBody = super.transform(definition.rhs)' "$plugin"
grep -Fq 'withScope(super.transform(definition))' "$plugin"
grep -Fq 'else transformIndependentNativeDefinition(definition)' "$plugin"
grep -Fq 'List(transformedRhs)' "$plugin"
"""
if guard_text.count(old_guard_lines) != 1:
    raise SystemExit("generic source-guard replacement point is ambiguous")
guard_text = guard_text.replace(old_guard_lines, new_guard_lines, 1)
guard.write_text(guard_text, encoding="utf-8")

doc = Path("docs/morphhdl/increment-53d-native-streamwidth-adapter.md")
doc_text = doc.read_text(encoding="utf-8")
heading = "\n## Generic native definition-lifetime closure"
if heading in doc_text:
    doc_text = doc_text[: doc_text.index(heading)].rstrip()
section = dedent(
    '''

    ## Generic native definition-lifetime closure

    The ownership repair is compiler-wide rather than tied to `StreamFifo`,
    `StreamFifoCC`, `StreamWidthAdapter`, an emitted signal name, or one source
    file. The parser phase is installed for every upstream SpinalHDL `core` and
    `lib` source, while semantic expression rewriting is activated only by
    explicit MorphHDL shadow syntax or a proven native runtime context. Ordinary
    eligible SpinalHDL code therefore remains unchanged.

    Every nested named method reached inside an active constructor or native
    width boundary owns a fresh empty lifetime. MorphHDL snapshots and clears the
    complete enclosing runtime state and transforms that method normally; it does
    not discover or open a second width boundary from inside the first one. A
    method reached with no active native context may independently establish a
    boundary from its own direct method-entry roots. The same rule applies to all
    components because no component name participates in the decision.

    Method-entry discovery accepts stable parameters and enclosing members. A
    local Data value declared later in the method and a constructor or call
    expression remain ordinary concrete SpinalHDL instead of being evaluated out
    of order. Accepted roots and the method body are transformed together in one
    lexical scope, while the original `DefDef` parameter symbols are retained.
    Proven symbolic use remains fail-closed in runtime identity and domain checks.

    Dual-Scala architecture checks reject component-named lifetime guards,
    file-specific eligibility, eager rejection of unrelated concrete `widthOf`
    calls, nested-boundary reopening, and method-parameter symbol replacement.
    Native StreamWidthAdapter and compound-depth StreamFifo remain independent
    functional witnesses; the inherited native-Int, memory, hierarchy, process,
    and concrete-baseline suites provide cross-component regression coverage.
'''
).rstrip() + "\n"
doc.write_text(doc_text + section, encoding="utf-8")
