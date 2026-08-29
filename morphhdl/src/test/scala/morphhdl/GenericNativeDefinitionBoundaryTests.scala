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
