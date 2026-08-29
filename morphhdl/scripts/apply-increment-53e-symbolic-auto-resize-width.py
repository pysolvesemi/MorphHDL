#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    value = path.read_text()
    count = value.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    path.write_text(value.replace(old, new, 1))


auto = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedAutoResize.scala"
)

marker = '''  private def validSyntheticBooleanRecord(
      component: Component,
      record: SyntheticBooleanRecord
  ): Boolean =
'''
method = '''  /**
    * Recover the exact whole UInt target which sized one surviving materialized
    * native `.resized` node. Capture occurred before SpinalHDL normalization,
    * while the auto-resize tag and both assignment edges were still present.
    * Publication therefore binds the later ResizeUInt only when the original
    * record, outer statement, source carrier and target all agree by identity.
    *
    * No component class, source path, emitted signal name or equal witness
    * width participates in discovery. Zero or ambiguous matches fail closed.
    */
  private[internals] def targetOfMaterializedResize(
      component: Component,
      resize: Resize
  ): Option[UInt] = {
    if (
      component == null || resize == null ||
      !resize.isInstanceOf[ResizeUInt] ||
      resize.getTypeObject != TypeUInt
    ) return None

    val matches = ArrayBuffer.empty[Record]
    storageOf(component).foreach { storage =>
      val iterator = storage.byResizeSource.values().iterator()
      while (iterator.hasNext) {
        val record = iterator.next()
        if (
          validRecord(component, record) &&
          (record.outer.source eq resize) &&
          resize.size == record.target.getBitsWidth &&
          resize.input != null &&
          resize.input.getTypeObject == TypeUInt &&
          ((resize.input eq record.resizeSource) ||
            (resize.input eq record.sourceDriver.source))
        ) {
          matches += record
        }
      }
    }
    matches.toVector.distinct match {
      case Vector(record) => Some(record.target)
      case _              => None
    }
  }

'''
value = auto.read_text()
if "def targetOfMaterializedResize(" not in value:
    if value.count(marker) != 1:
        raise SystemExit(
            f"materialized resize target marker count={value.count(marker)}"
        )
    auto.write_text(value.replace(marker, method + marker, 1))

fallback = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogNativeFallback.scala"
)
old = '''      private def inferResize(resize: Resize): WidthExpr = {
        ExternalParameterizedAutoResize
          .syntheticBooleanResizeTarget(component, resize)
          .map(target => ofBase(target))
          .getOrElse {
            val source = ofExpression(resize.input)
            val size = BigInt(resize.size)
            if (!source.isSymbolic) WidthLiteral(size)
            else if (size <= source.minimum) WidthLiteral(size)
            else {
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-RESIZE-DOMAIN-UNSUPPORTED",
                s"resize from symbolic width '${source.render}' to ${resize.size} is not a domain-invariant narrowing; widening and domain-crossing resize lowering is deferred"
              )
            }
          }
      }
'''
new = '''      private def inferResize(resize: Resize): WidthExpr = {
        ExternalParameterizedAutoResize
          .targetOfMaterializedResize(component, resize)
          .orElse(
            ExternalParameterizedAutoResize
              .syntheticBooleanResizeTarget(component, resize)
          )
          .map(target => ofBase(target))
          .getOrElse {
            val source = ofExpression(resize.input)
            val size = BigInt(resize.size)
            if (!source.isSymbolic) WidthLiteral(size)
            else if (size <= source.minimum) WidthLiteral(size)
            else {
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-RESIZE-DOMAIN-UNSUPPORTED",
                s"resize from symbolic width '${source.render}' to ${resize.size} is not a proven target-sized edge or domain-invariant narrowing"
              )
            }
          }
      }
'''
replace_once(
    fallback,
    old,
    new,
    "generic materialized auto-resize width inference",
)
