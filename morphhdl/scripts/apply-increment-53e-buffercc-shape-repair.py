#!/usr/bin/env python3
from pathlib import Path

path = Path(
    "morphplugin/src/main/scala/morphhdl/compiler/"
    "MorphHdlNativeIntShadowExpressionComponent.scala"
)
value = path.read_text()


def replace_once(old: str, new: str, label: str) -> None:
    global value
    count = value.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    value = value.replace(old, new, 1)


replace_once(
    '''        normalizedPath.endsWith("/lib/src/main/scala/spinal/lib/Stream.scala")
''',
    '''        normalizedPath.endsWith("/lib/src/main/scala/spinal/lib/Stream.scala") ||
        normalizedPath.endsWith("/lib/src/main/scala/spinal/lib/CrossClock.scala")
''',
    "CrossClock compiler eligibility",
)

replace_once(
    '''    private var nativeStreamFifoClassName: Option[String] = None

''',
    '''    private var nativeStreamFifoClassName: Option[String] = None
    private var nativeShapeOwner: Option[String] = None
    private var nativeShapeDataTypeName: Option[TermName] = None

''',
    "native shape context fields",
)

replace_once(
    '''    private def inNativeStreamFifo: Boolean =
      nativeStreamFifoDepthReference.nonEmpty

''',
    '''    private def inNativeStreamFifo: Boolean =
      nativeStreamFifoDepthReference.nonEmpty

    private def inNativeCompilerRuntimeContext: Boolean =
      inNativeStreamFifo || nativeShapeOwner.nonEmpty

    private def nativeShapeDataType(tree: Tree): Boolean = tree match {
      case Ident(name: TermName) => nativeShapeDataTypeName.contains(name)
      case Select(This(_), name: TermName) =>
        nativeShapeDataTypeName.contains(name)
      case _ => false
    }

''',
    "native shape context helpers",
)

replace_once(
    '''    private def selectedHelperMethod(name: String): Tree =
      if (inNativeStreamFifo) helperMethod(name) else frontendHelperMethod(name)
''',
    '''    private def selectedHelperMethod(name: String): Tree =
      if (inNativeCompilerRuntimeContext) helperMethod(name)
      else frontendHelperMethod(name)
''',
    "runtime helper routing",
)

replace_once(
    '''        case Apply(fun, List(data))
            if inNativeStreamFifo && terminalName(fun) == "cloneOf" =>
          rewriteNativeClone(tree, fun, data)
        case Apply(fun, arguments)
            if inNativeStreamFifo &&
              (terminalName(fun) == "RegNextWhen" || terminalName(fun) == "RegNext") &&
              arguments.nonEmpty =>
          rewriteNativeCopyShape(tree, fun, arguments)
''',
    '''        case Apply(fun, List(data))
            if inNativeCompilerRuntimeContext && terminalName(fun) == "cloneOf" =>
          rewriteNativeClone(tree, fun, data)
        case Apply(fun, arguments)
            if nativeShapeOwner.contains("BufferCC") &&
              terminalName(fun) == "Reg" && arguments.nonEmpty &&
              nativeShapeDataType(arguments.head) =>
          rewriteNativeCopyShape(tree, fun, arguments)
        case Apply(fun, arguments)
            if inNativeStreamFifo &&
              (terminalName(fun) == "RegNextWhen" || terminalName(fun) == "RegNext") &&
              arguments.nonEmpty =>
          rewriteNativeCopyShape(tree, fun, arguments)
''',
    "BufferCC clone/register rewrites",
)

replace_once(
    '''    private def normalizeGenerate(original: Tree, condition: Tree, body: Tree): Tree = {
''',
    '''    private def transformNativeBufferCC(value: ClassDef): Tree = {
      val parameters = nativeStreamFifoConstructorParameters(value)
      val dataType = parameters.find(parameter => decoded(parameter.name) == "dataType")
      dataType match {
        case None =>
          global.reporter.error(
            value.pos,
            "MORPHDL-NATIVE-BUFFERCC-DATATYPE-MISSING: native BufferCC constructor no longer exposes dataType"
          )
          super.transform(value)
        case Some(parameter) =>
          val previousOwner = nativeShapeOwner
          val previousDataType = nativeShapeDataTypeName
          nativeShapeOwner = Some("BufferCC")
          nativeShapeDataTypeName = Some(parameter.name)
          try super.transform(value)
          finally {
            nativeShapeOwner = previousOwner
            nativeShapeDataTypeName = previousDataType
          }
      }
    }

    private def transformNativeBufferCCObject(value: ModuleDef): Tree = {
      val previousOwner = nativeShapeOwner
      val previousDataType = nativeShapeDataTypeName
      nativeShapeOwner = Some("BufferCCObject")
      nativeShapeDataTypeName = None
      try super.transform(value)
      finally {
        nativeShapeOwner = previousOwner
        nativeShapeDataTypeName = previousDataType
      }
    }

    private def normalizeGenerate(original: Tree, condition: Tree, body: Tree): Tree = {
''',
    "native BufferCC transformers",
)

replace_once(
    '''    override def transform(tree: Tree): Tree = tree match {
      case value: ClassDef
          if sourceFile.replace('\\\\', '/').endsWith(
            "/lib/src/main/scala/spinal/lib/Stream.scala"
          ) && Set("StreamFifo", "StreamFifoCC").contains(decoded(value.name)) =>
        transformNativeStreamFifo(value)
''',
    '''    override def transform(tree: Tree): Tree = tree match {
      case value: ClassDef
          if sourceFile.replace('\\\\', '/').endsWith(
            "/lib/src/main/scala/spinal/lib/Stream.scala"
          ) && Set("StreamFifo", "StreamFifoCC").contains(decoded(value.name)) =>
        transformNativeStreamFifo(value)
      case value: ClassDef
          if sourceFile.replace('\\\\', '/').endsWith(
            "/lib/src/main/scala/spinal/lib/CrossClock.scala"
          ) && decoded(value.name) == "BufferCC" =>
        transformNativeBufferCC(value)
      case value: ModuleDef
          if sourceFile.replace('\\\\', '/').endsWith(
            "/lib/src/main/scala/spinal/lib/CrossClock.scala"
          ) && decoded(value.name) == "BufferCC" =>
        transformNativeBufferCCObject(value)
''',
    "BufferCC transform entrypoints",
)

path.write_text(value)
