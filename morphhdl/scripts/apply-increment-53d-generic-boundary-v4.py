from pathlib import Path
from textwrap import dedent

plugin = Path(
    "morphplugin/src/main/scala/morphhdl/compiler/"
    "MorphHdlNativeIntShadowExpressionComponent.scala"
)
text = plugin.read_text(encoding="utf-8")

# Install the phase for every upstream SpinalHDL component source. Actual
# expression rewriting is gated separately below, so an eligible concrete source
# remains unchanged unless a proven native symbolic context is active.
eligible_start = text.index("  private def eligible(unit: CompilationUnit): Boolean = {")
content_start = text.index(
    '    val content = Option(unit.source).map(_.content.mkString).getOrElse("")',
    eligible_start,
)
eligible_end = text.index("\n\n  private def helperMethod", content_start)
eligibility = dedent(
    '''
    val content = Option(unit.source).map(_.content.mkString).getOrElse("")
    val upstreamSpinalComponentSource =
      normalizedPath.contains("/core/src/main/scala/spinal/") ||
        normalizedPath.contains("/lib/src/main/scala/spinal/")
    !normalizedPath.contains("/frontend/src/main/scala/") &&
      !normalizedPath.contains("/morphplugin/src/main/scala/") &&
      (
        content.contains("NativeIntShadow") ||
        content.contains("shadowInt") ||
        upstreamSpinalComponentSource
      )
  }
'''
).lstrip()
eligibility = "".join(
    "    " + line if line.strip() else line for line in eligibility.splitlines(True)
)
text = text[:content_start] + eligibility.rstrip() + text[eligible_end:]

state_marker = """    private var nativeWidthFunctionStaticBooleans = Set.empty[TermName]
    private var nativeWidthFunctionDepth = 0

    private val NativeStreamFifoStaticBooleanNames = Set(
"""
state_replacement = """    private var nativeWidthFunctionStaticBooleans = Set.empty[TermName]
    private var nativeWidthFunctionDepth = 0

    private val explicitNativeShadowSource: Boolean =
      Option(unit.source).exists { source =>
        val content = source.content.mkString
        content.contains("NativeIntShadow") || content.contains("shadowInt")
      }

    private val NativeStreamFifoStaticBooleanNames = Set(
"""
if text.count(state_marker) != 1:
    raise SystemExit("explicit-source state insertion point is ambiguous")
text = text.replace(state_marker, state_replacement, 1)

helper_start = text.index("    private def withoutNativeStreamFifoContext")
helper_end = text.index("    private def trackedInteger", helper_start)
helper = dedent(
    '''
    /**
      * Native symbolic runtime capture is owned by the exact named Scala
      * definition that opens it. A nested named definition has an independent
      * elaboration lifetime and must never inherit constructor or native-width
      * capture state from its lexical owner. Anonymous function bodies stay
      * within the owning definition because they execute as part of that body.
      */
    private def withoutEnclosingNativeRuntimeContext[A](body: => A): A = {
      val previousStreamFifoDataTypeName = nativeStreamFifoDataTypeName
      val previousStreamFifoDepthName = nativeStreamFifoDepthName
      val previousStreamFifoDepthReference = nativeStreamFifoDepthReference
      val previousStreamFifoDepthLine = nativeStreamFifoDepthLine
      val previousStreamFifoStaticBooleans = nativeStreamFifoStaticBooleans
      val previousWidthFunctionStaticBooleans = nativeWidthFunctionStaticBooleans
      val previousWidthFunctionDepth = nativeWidthFunctionDepth

      nativeStreamFifoDataTypeName = None
      nativeStreamFifoDepthName = None
      nativeStreamFifoDepthReference = None
      nativeStreamFifoDepthLine = 1
      nativeStreamFifoStaticBooleans = Set.empty
      nativeWidthFunctionStaticBooleans = Set.empty
      nativeWidthFunctionDepth = 0

      try body
      finally {
        nativeStreamFifoDataTypeName = previousStreamFifoDataTypeName
        nativeStreamFifoDepthName = previousStreamFifoDepthName
        nativeStreamFifoDepthReference = previousStreamFifoDepthReference
        nativeStreamFifoDepthLine = previousStreamFifoDepthLine
        nativeStreamFifoStaticBooleans = previousStreamFifoStaticBooleans
        nativeWidthFunctionStaticBooleans = previousWidthFunctionStaticBooleans
        nativeWidthFunctionDepth = previousWidthFunctionDepth
      }
    }

'''
).lstrip()
helper = "".join(
    "    " + line if line.strip() else line for line in helper.splitlines(True)
)
text = text[:helper_start] + helper + text[helper_end:]

context_marker = """    private def inNativeRuntimeContext: Boolean =
      inNativeStreamFifo || inNativeWidthFunction

    private def nativeStreamFifoDataType(tree: Tree): Boolean = tree match {
"""
context_replacement = """    private def inNativeRuntimeContext: Boolean =
      inNativeStreamFifo || inNativeWidthFunction

    private def inNativeRewriteContext: Boolean =
      explicitNativeShadowSource || inNativeRuntimeContext

    private def nativeStreamFifoDataType(tree: Tree): Boolean = tree match {
"""
if text.count(context_marker) != 1:
    raise SystemExit("rewrite-context insertion point is ambiguous")
text = text.replace(context_marker, context_replacement, 1)

roots_start = text.index("    private def nativeWidthRoots(tree: Tree): Vector[Tree] = {")
roots_end = text.index("\n\n    private def transformNativeWidthFunction", roots_start)
roots = dedent(
    '''
    private def localNativeWidthNames(tree: Tree): Set[TermName] = {
      val found = mutable.LinkedHashSet.empty[TermName]
      object Finder extends Traverser {
        override def traverse(current: Tree): Unit = current match {
          case _: DefDef | _: ClassDef | _: ModuleDef | _: Function =>
          case value: ValDef =>
            found += value.name
            super.traverse(value.rhs)
          case _ => super.traverse(current)
        }
      }
      Finder.traverse(tree)
      found.toSet
    }

    private def nativeWidthRootAvailableAtEntry(
        tree: Tree,
        parameters: Set[TermName],
        locals: Set[TermName]
    ): Boolean = tree match {
      case Ident(name: TermName) => parameters(name) || !locals(name)
      case Select(This(_), _)    => true
      case Select(base, _)       =>
        nativeWidthRootAvailableAtEntry(base, parameters, locals)
      case Typed(value, _)       =>
        nativeWidthRootAvailableAtEntry(value, parameters, locals)
      case This(_)               => true
      case _                     => false
    }

    /**
      * Discover direct `widthOf(Data)` roots available when one native method is
      * entered. Nested definitions own independent lifetimes. A local Data value
      * declared inside the method cannot be evaluated before its declaration, so
      * a local-only `widthOf` remains ordinary concrete SpinalHDL. Method
      * parameters and enclosing members are safe generic roots for any component.
      */
    private def nativeWidthRoots(definition: DefDef): Vector[Tree] = {
      val parameters = definition.vparamss.flatten.map(_.name).toSet
      val locals = localNativeWidthNames(definition.rhs)
      val found = mutable.ArrayBuffer.empty[Tree]
      object Finder extends Traverser {
        override def traverse(current: Tree): Unit = current match {
          case _: DefDef | _: ClassDef | _: ModuleDef | _: Function =>
          case Apply(fun, List(data)) if terminalName(fun) == "widthOf" =>
            if (
              stableNativeWidthRoot(data) &&
                nativeWidthRootAvailableAtEntry(data, parameters, locals)
            ) found += data
          case _ => super.traverse(current)
        }
      }
      Finder.traverse(definition.rhs)
      found
        .groupBy(path)
        .toVector
        .sortBy(_._1)
        .map(_._2.head)
    }
'''
).lstrip()
roots = "".join(
    "    " + line if line.strip() else line for line in roots.splitlines(True)
)
text = text[:roots_start] + roots.rstrip() + text[roots_end:]

# Entry roots already live in the DefDef's lexical scope; copying them directly
# avoids re-running expression instrumentation outside that scope.
old_root_sequence = """        val rootSequence = Apply(
          scalaSeqApply,
          roots.map(root => super.transform(root).duplicate).toList
        )
"""
new_root_sequence = """        val rootSequence = Apply(
          scalaSeqApply,
          roots.map(_.duplicate).toList
        )
"""
if text.count(old_root_sequence) != 1:
    raise SystemExit("width-root sequence replacement point is ambiguous")
text = text.replace(old_root_sequence, new_root_sequence, 1)

override_marker = "    override def transform(tree: Tree): Tree = tree match {\n"
independent = dedent(
    '''
    private def transformIndependentNativeDefinition(
        definition: DefDef
    ): Tree = {
      val roots = nativeWidthRoots(definition)
      if (roots.nonEmpty) transformNativeWidthFunction(definition, roots)
      else withScope(super.transform(definition))
    }

'''
).lstrip()
independent = "".join(
    "    " + line if line.strip() else line for line in independent.splitlines(True)
)
if text.count(override_marker) != 1:
    raise SystemExit("transformer override insertion point is ambiguous")
text = text.replace(override_marker, independent + override_marker, 1)

application_case = """      case application @ Apply(Select(condition, name), List(body))
          if inNativeStreamFifo && decoded(name) == "generate" =>
"""
class_isolation = """      case value: ClassDef if inNativeRuntimeContext =>
        withoutEnclosingNativeRuntimeContext {
          withScope(super.transform(value))
        }
      case value: ModuleDef if inNativeRuntimeContext =>
        withoutEnclosingNativeRuntimeContext {
          withScope(super.transform(value))
        }
"""
if text.count(application_case) != 1:
    raise SystemExit("named-type isolation insertion point is ambiguous")
text = text.replace(application_case, class_isolation + application_case, 1)

cases_start = text.index(
    "      case definition: DefDef\n"
    "          if inNativeStreamFifo && decoded(definition.name) != \"<init>\" =>"
)
cases_end = text.index("      case definition: DefDef =>", cases_start)
generic_case = dedent(
    '''
      case definition: DefDef if decoded(definition.name) != "<init>" =>
        // A named method never inherits the native capture of its lexical
        // owner. It independently discovers any method-entry width roots and
        // opens a fresh boundary only when it owns them.
        if (inNativeRuntimeContext)
          withoutEnclosingNativeRuntimeContext {
            transformIndependentNativeDefinition(definition)
          }
        else transformIndependentNativeDefinition(definition)
'''
).lstrip()
generic_case = "".join(
    "      " + line if line.strip() else line for line in generic_case.splitlines(True)
)
text = text[:cases_start] + generic_case + text[cases_end:]

for old, new, label in (
    (
        "      case conditional: If    => rewriteIf(conditional)\n",
        "      case conditional: If if inNativeRewriteContext => rewriteIf(conditional)\n",
        "If rewrite guard",
    ),
    (
        "      case value: ValDef =>\n",
        "      case value: ValDef if inNativeRewriteContext =>\n",
        "ValDef rewrite guard",
    ),
    (
        "      case assignment: Assign =>\n",
        "      case assignment: Assign if inNativeRewriteContext =>\n",
        "Assign rewrite guard",
    ),
    (
        "      case other => rewriteExpression(other, None).tree\n",
        "      case other if inNativeRewriteContext => rewriteExpression(other, None).tree\n"
        "      case other                           => super.transform(other)\n",
        "fallback rewrite guard",
    ),
):
    if text.count(old) != 1:
        raise SystemExit(f"{label} replacement point is ambiguous")
    text = text.replace(old, new, 1)

for forbidden in (
    "withoutNativeStreamFifoContext",
    "if inNativeStreamFifo && decoded(definition.name)",
    'normalizedPath.endsWith("/lib/src/main/scala/spinal/lib/Stream.scala")',
    "MORPHDL-NATIVE-WIDTH-FUNCTION-ROOT-UNSTABLE",
):
    if forbidden in text:
        raise SystemExit(f"component-specific or eager-rejection fragment remains: {forbidden}")
for required in (
    "upstreamSpinalComponentSource",
    "explicitNativeShadowSource || inNativeRuntimeContext",
    "withoutEnclosingNativeRuntimeContext",
    "transformIndependentNativeDefinition",
    "nativeWidthRootAvailableAtEntry",
    "localNativeWidthNames",
    "case conditional: If if inNativeRewriteContext",
    "case value: ValDef if inNativeRewriteContext",
    "case assignment: Assign if inNativeRewriteContext",
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

        /** Compiler-architecture regression for all SpinalHDL components. */
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

          test("every named definition uses one component-independent capture lifetime") {
            val value = source
            assert(value.contains("private def withoutEnclosingNativeRuntimeContext"))
            assert(value.contains("transformIndependentNativeDefinition(definition)"))
            assert(value.contains("case value: ClassDef if inNativeRuntimeContext"))
            assert(value.contains("case value: ModuleDef if inNativeRuntimeContext"))
            assert(!value.contains("if inNativeStreamFifo && decoded(definition.name)"))
            assert(!value.contains("withoutNativeStreamFifoContext"))
          }

          test("the generic reset snapshots every current native context family") {
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

          test("method-entry roots are generic while local-only widthOf stays concrete") {
            val value = source
            assert(value.contains("private def localNativeWidthNames"))
            assert(value.contains("private def nativeWidthRootAvailableAtEntry"))
            assert(value.contains("val parameters = definition.vparamss.flatten.map(_.name).toSet"))
            assert(value.contains("val locals = localNativeWidthNames(definition.rhs)"))
            assert(value.contains("roots.map(_.duplicate).toList"))
            assert(!value.contains("MORPHDL-NATIVE-WIDTH-FUNCTION-ROOT-UNSTABLE"))
          }
        }
'''
    ).lstrip(),
    encoding="utf-8",
)

workflow = Path(".github/workflows/morphhdl-native-stream-width-adapter.yml")
workflow_text = workflow.read_text(encoding="utf-8")
anchor = "            morphhdl.ParameterizedStreamWidthAdapterTests \\\n"
suite = "            morphhdl.GenericNativeDefinitionBoundaryTests \\\n"
if suite not in workflow_text:
    if workflow_text.count(anchor) != 1:
        raise SystemExit("53d workflow test insertion point is ambiguous")
    workflow_text = workflow_text.replace(anchor, anchor + suite, 1)
    workflow.write_text(workflow_text, encoding="utf-8")

guard = Path("morphhdl/scripts/check-native-stream-width-adapter-boundary.sh")
guard_text = guard.read_text(encoding="utf-8")
marker = 'grep -Fq \'terminalName(fun) == "widthOf"\' "$plugin"\n'
checks = dedent(
    '''
    grep -Fq 'val upstreamSpinalComponentSource' "$plugin"
    grep -Fq '/core/src/main/scala/spinal/' "$plugin"
    grep -Fq '/lib/src/main/scala/spinal/' "$plugin"
    grep -Fq 'private def inNativeRewriteContext' "$plugin"
    grep -Fq 'private def withoutEnclosingNativeRuntimeContext' "$plugin"
    grep -Fq 'transformIndependentNativeDefinition(definition)' "$plugin"
    grep -Fq 'private def nativeWidthRootAvailableAtEntry' "$plugin"

    if grep -Fq 'withoutNativeStreamFifoContext' "$plugin"; then
      echo "Named-definition isolation must not use a StreamFifo-only helper" >&2
      exit 1
    fi
    if grep -Fq 'if inNativeStreamFifo && decoded(definition.name)' "$plugin"; then
      echo "Named-definition isolation must be component-independent" >&2
      exit 1
    fi
    if grep -Fq 'normalizedPath.endsWith("/lib/src/main/scala/spinal/lib/Stream.scala")' "$plugin"; then
      echo "Native instrumentation eligibility must not be limited to Stream.scala" >&2
      exit 1
    fi
    if grep -Fq 'MORPHDL-NATIVE-WIDTH-FUNCTION-ROOT-UNSTABLE' "$plugin"; then
      echo "Unrelated concrete widthOf calls must not fail during candidate discovery" >&2
      exit 1
    fi
'''
).lstrip()
if "Native instrumentation eligibility must not be limited" not in guard_text:
    if guard_text.count(marker) != 1:
        raise SystemExit("source-boundary insertion point is ambiguous")
    guard_text = guard_text.replace(marker, marker + checks, 1)
    guard.write_text(guard_text, encoding="utf-8")

doc = Path("docs/morphhdl/increment-53d-native-streamwidth-adapter.md")
doc_text = doc.read_text(encoding="utf-8")
section = dedent(
    '''

    ## Generic native definition-lifetime closure

    The remaining ownership repair is compiler-wide rather than tied to
    `StreamFifo`, `StreamFifoCC`, `StreamWidthAdapter`, an emitted signal name,
    or one source file. The parser phase is installed for every upstream
    SpinalHDL `core` and `lib` component, while semantic rewriting is activated
    only by explicit MorphHDL shadow syntax or a proven native runtime context.
    Ordinary eligible SpinalHDL code therefore remains unchanged.

    Every named method, class, and object reached inside an active native
    context owns an independent capture lifetime. MorphHDL snapshots and clears
    the complete enclosing runtime state, rediscovers only the nested
    definition's own roots, and restores the enclosing state afterward.
    Anonymous function bodies remain part of their owning definition.

    Generic native-width discovery accepts method parameters and enclosing
    members that are available at method entry. A local Data value declared in
    the method body cannot be evaluated before its declaration, so a local-only
    `widthOf` remains ordinary concrete SpinalHDL. Unstable constructor or call
    expressions are likewise ignored during candidate discovery rather than
    causing unrelated native library compilation failures. Proven symbolic use
    remains fail-closed in the runtime identity and domain checks.

    The dual-Scala architecture regression forbids component-named lifetime
    guards and file-specific eligibility. Native StreamWidthAdapter and
    compound-depth StreamFifo are functional witnesses, while the inherited
    native-Int, memory, hierarchy, process, and concrete-baseline matrix proves
    the rule does not depend on those witnesses.
'''
).rstrip() + "\n"
if "## Generic native definition-lifetime closure" not in doc_text:
    doc.write_text(doc_text.rstrip() + section, encoding="utf-8")
