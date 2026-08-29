#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
plugin_path = ROOT / "morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeIntShadowExpressionComponent.scala"
runtime_path = ROOT / "morphruntime/src/main/scala/spinal/core/ExternalNativeIntShadowRegistry.scala"


def once(value: str, old: str, new: str, label: str) -> str:
    count = value.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one marker, found {count}")
    return value.replace(old, new, 1)


runtime = runtime_path.read_text()
if "val argumentName: String" not in runtime:
    runtime = once(
        runtime,
        '''      val token: ExternalNativeIntFormalizationToken,
      val parentToken: Option[ExternalNativeIntFormalizationToken]
  ) {
''',
        '''      val token: ExternalNativeIntFormalizationToken,
      val parentToken: Option[ExternalNativeIntFormalizationToken],
      val argumentName: String
  ) {
    var constructorArgumentReference: Option[String] = None
''',
        "active boundary argument identity",
    )
    runtime = once(
        runtime,
        '''      token = token,
      parentToken = previous.headOption.map(_.token)
    )
''',
        '''      token = token,
      parentToken = previous.headOption.map(_.token),
      argumentName = argumentName
    )
''',
        "active boundary argument construction",
    )

if "boundary.constructorArgumentReference" not in runtime:
    start = runtime.find("  def captureArgumentTracked(\n")
    end = runtime.find("\n  /** Compiler hook: select one local", start)
    if start < 0 or end < 0:
        raise SystemExit("captureArgumentTracked method boundaries not found")
    method = '''  def captureArgumentTracked(
      value: Int,
      name: String,
      reference: String,
      sourceLocation: String
  ): Int = withBoundaryOrValue(value, false, name, sourceLocation) { boundary =>
    if (name != boundary.argumentName) value
    else {
      boundary.constructorArgumentReference match {
        case Some(existing) if existing != reference => value
        case _ =>
          validateReference(reference, sourceLocation, "constructor argument")
          requireRootWitness(boundary, value, name, sourceLocation)
          val tracked = ExternalNativeIntShadowTrackedValue(value, Root, sourceLocation)
          retainTracked(boundary, reference, tracked)
          retainSlot(
            boundary,
            ExternalNativeIntShadowKind.ConstructorArgument,
            name,
            value,
            Root,
            sourceLocation
          )
          boundary.constructorArgumentReference = Some(reference)
          value
      }
    }
  }
'''
    runtime = runtime[:start] + method + runtime[end:]
runtime_path.write_text(runtime)

plugin = plugin_path.read_text()
plugin = plugin.replace(
    'normalizedPath.endsWith("/lib/src/main/scala/spinal/lib/Stream.scala")',
    '''(
        normalizedPath.contains("/core/src/main/scala/spinal/") ||
        normalizedPath.contains("/lib/src/main/scala/spinal/")
      )''',
)
plugin = plugin.replace(
    'decoded(parameter.name) == "depth"',
    'isNativeIntConstructorParameter(parameter)',
)

anchor = "    private def normalizeGenerate(original: Tree, condition: Tree, body: Tree): Tree = {\n"
if "private def isGenericNativeIntComponent" not in plugin:
    helper = '''    private def isNativeIntConstructorParameter(
        parameter: ValDef
    ): Boolean = {
      val symbol = parameter.symbol
      symbol != null && symbol != NoSymbol && symbol.info != null &&
        (symbol.info =:= definitions.IntTpe)
    }

    private def isGenericNativeIntComponent(value: ClassDef): Boolean = {
      val normalized = sourceFile.replace('\\\\', '/')
      val upstreamProductionSource =
        normalized.contains("/core/src/main/scala/spinal/") ||
          normalized.contains("/lib/src/main/scala/spinal/")
      val componentOwner =
        value.symbol != null && value.symbol != NoSymbol &&
          value.symbol.info != null &&
          value.symbol.info.baseClasses.exists(
            base => base.fullName == "spinal.core.Component"
          )
      val integerParameters =
        nativeStreamFifoConstructorParameters(value)
          .count(isNativeIntConstructorParameter)
      upstreamProductionSource && componentOwner && integerParameters == 1
    }

'''
    plugin = once(plugin, anchor, helper + anchor, "generic component helper")

needle = 'Set("StreamFifo", "StreamFifoCC").contains(decoded(value.name))'
if needle in plugin:
    case_pos = plugin.rfind("      case value: ClassDef", 0, plugin.find(needle))
    call_end = plugin.find("        transformNativeStreamFifo(value)", plugin.find(needle))
    if case_pos < 0 or call_end < 0:
        raise SystemExit("component-specific transform case boundaries not found")
    call_end += len("        transformNativeStreamFifo(value)")
    plugin = (
        plugin[:case_pos]
        + "      case value: ClassDef if isGenericNativeIntComponent(value) =>\n"
        + "        transformNativeStreamFifo(value)"
        + plugin[call_end:]
    )

if needle in plugin:
    raise SystemExit("component-name transform dispatch remains")
plugin_path.write_text(plugin)
