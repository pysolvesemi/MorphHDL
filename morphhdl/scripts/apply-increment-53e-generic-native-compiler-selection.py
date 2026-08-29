#!/usr/bin/env python3
from pathlib import Path
import re

path = Path(
    "morphplugin/src/main/scala/morphhdl/compiler/"
    "MorphHdlNativeIntShadowExpressionComponent.scala"
)
value = path.read_text()

# Instrument ordinary native SpinalHDL library sources. Runtime boundary
# identity, not a source filename or component name, decides whether one call
# carries symbolic provenance.
value = value.replace(
    'normalizedPath.endsWith("/lib/src/main/scala/spinal/lib/Stream.scala")',
    'normalizedPath.contains("/lib/src/main/scala/spinal/")'
)
value = value.replace(
    'normalizedPath.endsWith("/lib/src/main/scala/spinal/lib/CrossClock.scala")',
    'normalizedPath.contains("/lib/src/main/scala/spinal/")'
)

# Rename all inherited witness-oriented state before changing behavior. The
# resulting production compiler vocabulary describes generic formalized native
# constructors, types and integer roots only.
renames = (
    ("NativeStreamFifoStaticBooleanNames", "NativeFormalizedStaticBooleanNames"),
    ("nativeStreamFifoConstructorParameters", "nativeConstructorParameters"),
    ("nativeStreamFifoDepthReference", "nativeFormalizedIntReference"),
    ("nativeStreamFifoDataTypeName", "nativeFormalizedDataTypeName"),
    ("nativeStreamFifoStaticBooleans", "nativeFormalizedStaticBooleans"),
    ("nativeStreamFifoDepthName", "nativeFormalizedIntName"),
    ("nativeStreamFifoDepthLine", "nativeFormalizedIntLine"),
    ("nativeStreamFifoClassName", "nativeFormalizedOwnerName"),
    ("withoutNativeStreamFifoContext", "withoutNativeFormalizedContext"),
    ("rewriteNativeStreamFifoDepth", "rewriteNativeFormalizedInt"),
    ("nativeStreamFifoDataType", "nativeFormalizedDataType"),
    ("nativeStreamFifoDepth", "nativeFormalizedInt"),
    ("nativeDepthSourceArguments", "nativeFormalizedSourceArguments"),
    ("transformNativeStreamFifo", "transformNativeFormalizedClass"),
    ("inNativeStreamFifo", "inNativeFormalizedClass"),
    ("NativeStreamFifo", "NativeFormalizedClass"),
    ("nativeStreamFifo", "nativeFormalized"),
    ("native StreamFifoCC", "native formalized class"),
    ("native StreamFifo", "native formalized class"),
)
for old, new in renames:
    value = value.replace(old, new)

# Static constructor booleans are discovered by Scala type, not by the names of
# options used by any one library component.
value = re.sub(
    r'''    private val NativeFormalizedStaticBooleanNames = Set\(.*?    \)\n(?=    private val binaryOperations)''',
    "",
    value,
    count=1,
    flags=re.S,
)

# Source-level routing remains independent of the currently selected formal.
source_context = '''    private def inNativeCompilerRuntimeContext: Boolean =
      sourceFile.replace('\\\\', '/').contains(
        "/lib/src/main/scala/spinal/"
      )

'''
if "private def inNativeCompilerRuntimeContext" in value:
    value = re.sub(
        r'''    private def inNativeCompilerRuntimeContext: Boolean =\n(?:      .*\n){1,5}''',
        source_context,
        value,
        count=1,
    )
else:
    marker = '''    private def inNativeFormalizedClass: Boolean =
      nativeFormalizedIntReference.nonEmpty

'''
    if marker not in value:
        raise SystemExit("native compiler runtime-context insertion marker is missing")
    value = value.replace(marker, marker + source_context, 1)

value = value.replace(
    '''    private def selectedHelperMethod(name: String): Tree =
      if (inNativeFormalizedClass) helperMethod(name) else frontendHelperMethod(name)
''',
    '''    private def selectedHelperMethod(name: String): Tree =
      if (inNativeCompilerRuntimeContext) helperMethod(name)
      else frontendHelperMethod(name)
'''
)
value = value.replace(
    '''    private def selectedConditionalHelperMethod(name: String): Tree =
      if (inNativeFormalizedClass) helperMethod(name)
      else frontendConditionalHelperMethod(name)
''',
    '''    private def selectedConditionalHelperMethod(name: String): Tree =
      if (inNativeCompilerRuntimeContext) helperMethod(name)
      else frontendConditionalHelperMethod(name)
'''
)

# Identify constructor roles from typed/syntactic Scala parameter types. This
# is intentionally independent of class names, parameter names and witnesses.
helper_marker = '''    private def nativeConstructorParameters('''
if "private def constructorParameterTypeName" not in value:
    index = value.find(helper_marker)
    if index < 0:
        raise SystemExit("native constructor-parameter helper marker is missing")
    helper = '''    private def constructorParameterTypeName(tree: Tree): String = tree match {
      case AppliedTypeTree(base, _) => constructorParameterTypeName(base)
      case Ident(name)              => decoded(name)
      case Select(_, name)          => decoded(name)
      case _                        => tree.toString
    }

    private def hasNativeParameterType(
        parameter: ValDef,
        expected: String,
        semanticType: Type
    ): Boolean = {
      val symbolType =
        if (parameter.symbol == null || parameter.symbol == NoSymbol) NoType
        else parameter.symbol.info.finalResultType
      (symbolType != NoType && symbolType =:= semanticType) ||
        constructorParameterTypeName(parameter.tpt) == expected
    }

    private def isNativeIntConstructorParameter(
        parameter: ValDef
    ): Boolean =
      hasNativeParameterType(parameter, "Int", definitions.IntTpe)

    private def isNativeBooleanConstructorParameter(
        parameter: ValDef
    ): Boolean =
      hasNativeParameterType(parameter, "Boolean", definitions.BooleanTpe)

    private def isNativeDataTypeConstructorParameter(
        parameter: ValDef
    ): Boolean =
      constructorParameterTypeName(parameter.tpt) == "HardType"

    private def hasOneNativeFormalizableInt(value: ClassDef): Boolean =
      nativeConstructorParameters(value)
        .count(isNativeIntConstructorParameter) == 1

'''
    value = value[:index] + helper + value[index:]

# Replace historical constructor-role discovery by type.
for pattern in (
    r'''parameters\.find\(parameter\s*=>\s*decoded\(parameter\.name\)\s*==\s*"depth"\)''',
    r'''parameters\.find\(parameter\s*=>\s*parameter\.name\.decodedName\.toString\s*==\s*"depth"\)''',
    r'''parameters\.find\(_\.name\.decodedName\.toString\s*==\s*"depth"\)''',
):
    value = re.sub(pattern, "parameters.find(isNativeIntConstructorParameter)", value)
value = value.replace(
    'parameters.find(parameter => decoded(parameter.name) == "dataType")',
    'parameters.find(isNativeDataTypeConstructorParameter)'
)
value = value.replace(
    'if NativeFormalizedStaticBooleanNames(decoded(parameter.name))',
    'if isNativeBooleanConstructorParameter(parameter)'
)

# Replace the inherited class transformer as one structural unit. Exactly one
# native Int is the current bounded formalization contract; every Boolean and
# optional HardType role is discovered from type. The compiler passes no public
# formal name—the live boundary supplies it.
method_start = value.find(
    "    private def transformNativeFormalizedClass(value: ClassDef): Tree = {"
)
method_end = value.find("\n\n    private def normalizeGenerate", method_start)
if method_start < 0 or method_end < 0:
    raise SystemExit("generic formalized-class transformer boundary is missing")
method = '''    private def transformNativeFormalizedClass(value: ClassDef): Tree = {
      val parameters = nativeConstructorParameters(value)
      val dataType = parameters.find(isNativeDataTypeConstructorParameter)
      val formalInt = parameters.find(isNativeIntConstructorParameter)
      formalInt match {
        case None =>
          global.reporter.error(
            value.pos,
            "MORPHDL-NATIVE-FORMALIZED-INT-MISSING: selected native class no longer exposes one Int constructor parameter"
          )
          super.transform(value)
        case Some(formalIntParameter) =>
          val previousDataTypeName = nativeFormalizedDataTypeName
          val previousName = nativeFormalizedIntName
          val previousReference = nativeFormalizedIntReference
          val previousLine = nativeFormalizedIntLine
          val previousBooleans = nativeFormalizedStaticBooleans
          val previousOwnerName = nativeFormalizedOwnerName
          nativeFormalizedDataTypeName = dataType.map(_.name)
          nativeFormalizedIntName = Some(formalIntParameter.name)
          nativeFormalizedIntReference = Some(
            sourceReference(formalIntParameter, "argument:selected-native-int")
          )
          nativeFormalizedIntLine = sourceLine(formalIntParameter)
          nativeFormalizedOwnerName = Some(decoded(value.name))
          nativeFormalizedStaticBooleans = parameters.collect {
            case parameter if isNativeBooleanConstructorParameter(parameter) =>
              parameter.name
          }.toSet
          try super.transform(value)
          finally {
            nativeFormalizedDataTypeName = previousDataTypeName
            nativeFormalizedIntName = previousName
            nativeFormalizedIntReference = previousReference
            nativeFormalizedIntLine = previousLine
            nativeFormalizedStaticBooleans = previousBooleans
            nativeFormalizedOwnerName = previousOwnerName
          }
      }
    }'''
value = value[:method_start] + method + value[method_end:]

# Neutral constructor capture: the runtime boundary owns the formal name.
rewrite_start = value.find(
    "    private def rewriteNativeFormalizedInt(original: Tree): Rewrite = {"
)
rewrite_end = value.find("\n\n    private def rewriteAlias", rewrite_start)
if rewrite_start < 0 or rewrite_end < 0:
    raise SystemExit("generic formalized-Int rewrite boundary is missing")
rewrite_method = '''    private def rewriteNativeFormalizedInt(original: Tree): Rewrite = {
      val reference = nativeFormalizedIntReference.getOrElse(
        throw new IllegalStateException("native formalized Int reference is missing")
      )
      Rewrite(
        call(
          "compilerTrackArgument",
          List(
            super.transform(original),
            Literal(Constant("")),
            Literal(Constant(reference))
          ) ++ nativeFormalizedSourceArguments,
          original
        ),
        intReference = Some(reference)
      )
    }'''
value = value[:rewrite_start] + rewrite_method + value[rewrite_end:]

# Generic class entrypoint. No component or source-file selector remains.
class_case = re.compile(
    r'''      case value: ClassDef\n\s*if sourceFile\.replace\('\\\\\\\\', '/'\)\.endsWith\(\n\s*"/lib/src/main/scala/spinal/lib/Stream\.scala"\n\s*\) && Set\("StreamFifo", "StreamFifoCC"\)\.contains\(decoded\(value\.name\)\) =>\n\s*transformNativeFormalizedClass\(value\)'''
)
value, _ = class_case.subn(
    '''      case value: ClassDef
          if inNativeCompilerRuntimeContext &&
            hasOneNativeFormalizableInt(value) =>
        transformNativeFormalizedClass(value)''',
    value,
    count=1,
)
value = value.replace(
    'Set("StreamFifo", "StreamFifoCC").contains(decoded(value.name))',
    'hasOneNativeFormalizableInt(value)'
)
value = value.replace(
    '''sourceFile.replace('\\\\', '/').endsWith(
            "/lib/src/main/scala/spinal/lib/Stream.scala"
          ) && hasOneNativeFormalizableInt(value)''',
    '''inNativeCompilerRuntimeContext &&
            hasOneNativeFormalizableInt(value)'''
)

# A proven symbolic descending range is generic. It no longer depends on the
# owner class of the source expression.
value = value.replace(
    '''            if inNativeFormalizedClass &&
              nativeFormalizedOwnerName.contains("StreamFifoCC") &&
              descendingRangeBounds(range).nonEmpty =>''',
    '''            if inNativeFormalizedClass &&
              descendingRangeBounds(range).nonEmpty =>'''
)

# Keep methods within the generic compiler context. Runtime hooks already no-op
# when no selected provenance reaches a method, so no method-name exceptions are
# required or permitted.
special_method_case = '''      case definition: DefDef
          if inNativeFormalizedClass && decoded(definition.name) != "<init>" &&
            !(nativeFormalizedOwnerName.contains("StreamFifoCC") &&
              Set("isFull", "isEmpty").contains(decoded(definition.name))) =>
        withoutNativeFormalizedContext {
          withScope(super.transform(definition))
        }
'''
value = value.replace(special_method_case, "")

# Route generic native expression capture through runtime-selective helpers.
value = value.replace("if inNativeFormalizedClass &&", "if inNativeCompilerRuntimeContext &&")
value = value.replace("if inNativeFormalizedClass =>", "if inNativeCompilerRuntimeContext =>")

# Generic Data-shape copying for native Reg/clone/RegNext calls.
reg_case = '''        case Apply(fun, arguments)
            if inNativeCompilerRuntimeContext &&
              terminalName(fun) == "Reg" && arguments.nonEmpty =>
          rewriteNativeCopyShape(tree, fun, arguments)
'''
expression_marker = '''        case Apply(fun, List(data))
            if inNativeCompilerRuntimeContext && terminalName(fun) == "cloneOf" =>
'''
if reg_case not in value and expression_marker in value:
    value = value.replace(expression_marker, reg_case + expression_marker, 1)

# Enforce the reusable compiler boundary before writing it. Comments may retain
# historical explanation, but executable identifiers, strings and selectors may
# not contain a concrete witness or its constructor-option names.
without_comments = re.sub(r'/\*.*?\*/', '', value, flags=re.S)
without_comments = re.sub(r'//.*', '', without_comments)
for token in (
    "StreamFifo",
    "STREAMFIFO",
    "BufferCC",
    "/lib/src/main/scala/spinal/lib/Stream.scala",
    "/lib/src/main/scala/spinal/lib/CrossClock.scala",
    "withAsyncRead",
    "withBypass",
    "allowExtraMsb",
    "forFMax",
    "useVec",
):
    if token in without_comments:
        raise SystemExit(
            "component-specific compiler token remains after genericization: " + token
        )

path.write_text(value)
