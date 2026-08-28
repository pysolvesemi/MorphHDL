#!/usr/bin/env python3
from pathlib import Path


def replace_once(text: str, old: str, new: str, role: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{role}: expected one exact match, found {count}")
    return text.replace(old, new, 1)


plugin_path = Path(
    "morphplugin/src/main/scala/morphhdl/compiler/"
    "MorphHdlNativeIntShadowExpressionComponent.scala"
)
plugin = plugin_path.read_text(encoding="utf-8")

if "nativeStreamFifoClassName" not in plugin:
    plugin = replace_once(
        plugin,
        """    private var nativeStreamFifoDepthLine: Int = 1
    private var nativeStreamFifoStaticBooleans = Set.empty[TermName]
""",
        """    private var nativeStreamFifoDepthLine: Int = 1
    private var nativeStreamFifoStaticBooleans = Set.empty[TermName]
    private var nativeStreamFifoClassName: Option[String] = None
""",
        "native FIFO class context declaration",
    )

    plugin = replace_once(
        plugin,
        """      val previousDepthLine = nativeStreamFifoDepthLine
      val previousStaticBooleans = nativeStreamFifoStaticBooleans
      nativeStreamFifoDataTypeName = None
      nativeStreamFifoDepthName = None
      nativeStreamFifoDepthReference = None
      nativeStreamFifoStaticBooleans = Set.empty
""",
        """      val previousDepthLine = nativeStreamFifoDepthLine
      val previousStaticBooleans = nativeStreamFifoStaticBooleans
      val previousClassName = nativeStreamFifoClassName
      nativeStreamFifoDataTypeName = None
      nativeStreamFifoDepthName = None
      nativeStreamFifoDepthReference = None
      nativeStreamFifoStaticBooleans = Set.empty
      nativeStreamFifoClassName = None
""",
        "native FIFO context suspension",
    )

    plugin = replace_once(
        plugin,
        """        nativeStreamFifoDepthLine = previousDepthLine
        nativeStreamFifoStaticBooleans = previousStaticBooleans
""",
        """        nativeStreamFifoDepthLine = previousDepthLine
        nativeStreamFifoStaticBooleans = previousStaticBooleans
        nativeStreamFifoClassName = previousClassName
""",
        "native FIFO context restoration",
    )

    plugin = replace_once(
        plugin,
        """          val previousLine = nativeStreamFifoDepthLine
          val previousBooleans = nativeStreamFifoStaticBooleans
          nativeStreamFifoDataTypeName = dataType.map(_.name)
""",
        """          val previousLine = nativeStreamFifoDepthLine
          val previousBooleans = nativeStreamFifoStaticBooleans
          val previousClassName = nativeStreamFifoClassName
          nativeStreamFifoDataTypeName = dataType.map(_.name)
""",
        "native FIFO constructor context save",
    )

    plugin = replace_once(
        plugin,
        """          nativeStreamFifoDepthLine = sourceLine(depthParameter)
          nativeStreamFifoStaticBooleans = parameters.collect {
""",
        """          nativeStreamFifoDepthLine = sourceLine(depthParameter)
          nativeStreamFifoClassName = Some(decoded(value.name))
          nativeStreamFifoStaticBooleans = parameters.collect {
""",
        "native FIFO constructor class selection",
    )

    plugin = replace_once(
        plugin,
        """            nativeStreamFifoDepthLine = previousLine
            nativeStreamFifoStaticBooleans = previousBooleans
""",
        """            nativeStreamFifoDepthLine = previousLine
            nativeStreamFifoStaticBooleans = previousBooleans
            nativeStreamFifoClassName = previousClassName
""",
        "native FIFO constructor context restore",
    )

    plugin = replace_once(
        plugin,
        """          ) && decoded(value.name) == "StreamFifo" =>
        transformNativeStreamFifo(value)
""",
        """          ) && Set("StreamFifo", "StreamFifoCC").contains(decoded(value.name)) =>
        transformNativeStreamFifo(value)
""",
        "native FIFO class selection",
    )

    plugin = replace_once(
        plugin,
        """      case definition: DefDef
          if inNativeStreamFifo && decoded(definition.name) != "<init>" =>
        withoutNativeStreamFifoContext {
          withScope(super.transform(definition))
        }
""",
        """      case definition: DefDef
          if inNativeStreamFifo && decoded(definition.name) != "<init>" &&
            !(nativeStreamFifoClassName.contains("StreamFifoCC") &&
              Set("isFull", "isEmpty").contains(decoded(definition.name))) =>
        withoutNativeStreamFifoContext {
          withScope(super.transform(definition))
        }
""",
        "native StreamFifoCC helper-method context",
    )

    plugin_path.write_text(plugin, encoding="utf-8")


todo_path = Path("docs/morphhdl/parameterized-verilog-todo.md")
todo = todo_path.read_text(encoding="utf-8")
if "**Increment 53e —" not in todo:
    entry = """- [ ] **Increment 53e — Native StreamFifoCC parameterization without source edits**

  **Dependencies:** Increment 53 implemented and merged.

  Apply the generic native-`Int` provenance, expression, memory, hierarchy and
  process lowering path to the exact untouched `spinal.lib.StreamFifoCC` class.
  MorphHDL may provide only an external construction/provenance adapter that
  returns the native `spinal.lib.StreamFifoCC[T]`; it must not add a copied,
  subclassed or separately authored asynchronous FIFO. Keep all upstream-owned
  SpinalHDL `core`, `lib`, `idslplugin` and other production sources byte-identical.
  Preserve the native power-of-two/depth-at-least-two contract, dual clock
  domains, Gray-coded pointers, `BufferCC` synchronizers, cross-clock memory,
  optional buffered pop reset, push/pop occupancy and stream ordering. Emit one
  parameterized Verilog-2001 `StreamFifoCC` definition and prove legal DEPTH
  overrides 4, 8 and 16 using deterministic dual-Scala generation, concrete
  parity, asynchronous-clock simulation, strict lint and synthesis. Retain
  symbolic pointer widths and range/index expressions by graph identity; emitted
  signal names or component-specific Verilog rewriting are not acceptable.

"""
    anchor = "- [ ] **Increment 54 — MorphHDL module extraction and native-tree cleanup**"
    if todo.count(anchor) != 1:
        raise SystemExit("Increment 53e TODO anchor is missing or ambiguous")
    todo = todo.replace(anchor, entry + anchor, 1)
    todo_path.write_text(todo, encoding="utf-8")
