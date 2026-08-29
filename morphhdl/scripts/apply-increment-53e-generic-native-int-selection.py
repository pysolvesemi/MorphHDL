#!/usr/bin/env python3
"""Replace FIFO-name selection with a generic native Component rule.

The compiler transform is enabled for upstream SpinalHDL production Components
that expose exactly one Scala Int constructor parameter.  Runtime capture is
selected by the formal boundary's explicit argument name and may be claimed by
only the first matching constructor, preventing nested constructors from being
associated by equal numeric witnesses.
"""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[2]
PLUGIN = ROOT / "morphplugin/src/main/scala/morphhdl/compiler/MorphHdlNativeIntShadowExpressionComponent.scala"
RUNTIME = ROOT / "morphruntime/src/main/scala/spinal/core/ExternalNativeIntShadowRegistry.scala"


def replace_once(value: str, old: str, new: str, label: str) -> str:
    count = value.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one marker, found {count}")
    return value.replace(old, new, 1)


runtime = RUNTIME.read_text()
if "val argumentName: String" not in runtime:
    old = '''      val token: ExternalNativeIntFormalizationToken,
      val parentToken: Option[ExternalNativeIntFormalizationToken]
  ) {
'''
    new = '''      val token: ExternalNativeIntFormalizationToken,
      val parentToken: Option[ExternalNativeIntFormalizationToken],
      val argumentName: String
  ) {
    var constructorArgumentReference: Option[String] = None
'''
    runtime = replace_once(runtime, old, new, "active boundary selected argument")

    old = '''      token = token,
      parentToken = previous.headOption.map(_.token)
    )
'''
    new = '''      token = token,
      parentToken = previous.headOption.map(_.token),
      argumentName = argumentName
    )
'''
    runtime = replace_once(runtime, old, new, "active boundary construction")

old_method = '''  def captureArgumentTracked(
      value: Int,
      name: String,
      reference: String,
      sourceLocation: String
  ): Int = withBoundaryOrValue(value, false, name, sourceLocation) { boundary =>
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
    value
  }
'''
new_method = '''  def captureArgumentTracked(
      value: Int,
      name: String,
      reference: String,
      sourceLocation: String
  ): Int = withBoundaryOrValue(value, false, name, sourceLocation) { boundary =>
    if (name != boundary.argumentName) value
    else {
      boundary.constructorArgumentReference match {
        case Some(existing) if existing != reference =>
          // A nested native constructor may expose the same argument name.
          // The first exact constructor reference owns this boundary; later
          // matches are ordinary concrete Ints and cannot steal provenance.
          value
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
if old_method in runtime:
    runtime = replace_once(
        runtime,
        old_method,
        new_method,
        "generic constructor-argument claim",
    )
elif "boundary.constructorArgumentReference" not in runtime:
    raise SystemExit("captureArgumentTracked has an unknown shape")
RUNTIME.write_text(runtime)

plugin = PLUGIN.read_text()

# Broaden the source gate from one hand-picked file to upstream production
# sources.  MorphHDL's own user/frontend code is not instrumented.
stream_gate = 'normalizedPath.endsWith("/lib/src/main/scala/spinal/lib/Stream.scala")'
upstream_gate = '''(
        normalizedPath.contains("/core/src/main/scala/spinal/") ||
        normalizedPath.contains("/lib/src/main/scala/spinal/")
      )'''
if stream_gate in plugin:
    plugin = plugin.replace(stream_gate, upstream_gate)

# Select the constructor parameter by Scala Int type.  The transform entry below
# guarantees there is exactly one, so no name or numeric witness is a discovery
# key.
plugin = plugin.replace(
    'decoded(parameter.name) == "depth"',
    'isNativeIntConstructorParameter(parameter)',
)

helper_anchor = '''    private def normalizeGenerate(original: Tree, condition: Tree, body: Tree): Tree = {
'''
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
    plugin = replace_once(
        plugin,
        helper_anchor,
        helper + helper_anchor,
        "generic native component predicate",
    )

# Replace the source/class-name dispatch with the structural predicate.
pattern = re.compile(
    r'''      case value: ClassDef\n'''
    r'''          if sourceFile\.replace\('\\\\\\\\', '/'\)\.endsWith\(\n'''
    r'''            "/lib/src/main/scala/spinal/lib/Stream\.scala"\n'''
    r'''          \) && Set\("StreamFifo", "StreamFifoCC"\)\.contains\(decoded\(value\.name\)\) =>\n'''
    r'''        transformNativeStreamFifo\(value\)\n'''
)
replacement = '''      case value: ClassDef if isGenericNativeIntComponent(value) =>
        transformNativeStreamFifo(value)
'''
plugin, count = pattern.subn(replacement, plugin, count=1)
if count == 0 and 'Set("StreamFifo", "StreamFifoCC")' in plugin:
    raise SystemExit("generic native Component dispatch marker was not found")

# Diagnostics and internal context names are not semantic discovery keys, but
# remove user-facing FIFO wording so future failures describe the generic rule.
plugin = plugin.replace("native StreamFifo constructor", "native Component constructor")
plugin = plugin.replace("native StreamFifo class", "native Component class")
plugin = plugin.replace("native StreamFifo depth", "native Component Int parameter")

for forbidden in (
    'Set("StreamFifo", "StreamFifoCC")',
    'endsWith("/lib/src/main/scala/spinal/lib/Stream.scala")',
):
    if forbidden in plugin:
        raise SystemExit(f"component-specific compiler selection remains: {forbidden}")

PLUGIN.write_text(plugin)
