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
      "normalizedPath.endsWith(\"/lib/src/main/scala/spinal/lib/Stream.scala\")"
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
      "case definition: DefDef if decoded(definition.name)"
    )
    val caseEnd = value.indexOf("case definition: DefDef =>", caseStart)
    assert(caseStart >= 0 && caseEnd > caseStart)
    val definitionCase = value.substring(caseStart, caseEnd)
    assert(definitionCase.contains("if (inNativeRuntimeContext) {"))
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
    assert(value.contains("case Bind(name: TermName, body) =>"))
    assert(value.contains("private def nativeWidthRootAvailableAtEntry"))
    assert(value.contains("val parameters = definition.vparamss.flatten.map(_.name).toSet"))
    assert(value.contains("val locals = localNativeWidthNames(definition.rhs)"))
    assert(value.contains("val (rootSequence, transformedRhs) = withScope"))
    assert(value.contains("roots.map(root => super.transform(root).duplicate).toList"))
    assert(value.contains("val transformedBody = super.transform(definition.rhs)"))
    assert(value.contains("treeCopy.DefDef("))
    assert(value.contains("definition,"))
    assert(!value.contains("super.transform(definition)).asInstanceOf[DefDef]"))
    assert(!value.contains("MORPHDL-NATIVE-WIDTH-FUNCTION-ROOT-UNSTABLE"))
  }
}
