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
value = auto.read_text()
marker = '''  private def validSyntheticBooleanRecord(
      component: Component,
      record: SyntheticBooleanRecord
  ): Boolean =
'''
method = '''  /**
    * Enumerate surviving whole-target UInt auto-resize assignments by exact
    * pre-normalization graph identity. The returned ResizeUInt is the native
    * materialization of `.resized`; callers may lower it to ordinary unsigned
    * assignment coercion, whose width is determined by the destination for
    * every legal parameter value.
    *
    * Component names, source paths, emitted identifiers and equal concrete
    * widths are never discovery keys. Ambiguous records are omitted and later
    * validation therefore remains fail-closed.
    */
  private[internals] def materializedAssignmentsOf(
      component: Component
  ): Vector[(UInt, ResizeUInt)] = {
    if (component == null) return Vector.empty

    val result = ArrayBuffer.empty[(UInt, ResizeUInt)]
    val seen = new IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]()
    storageOf(component).foreach { storage =>
      val iterator = storage.byResizeSource.values().iterator()
      while (iterator.hasNext) {
        val record = iterator.next()
        if (
          seen.put(record.outer, java.lang.Boolean.TRUE) == null &&
          validRecord(component, record)
        ) {
          record.outer.source match {
            case resize: ResizeUInt
                if resize.getTypeObject == TypeUInt &&
                  resize.size == record.target.getBitsWidth &&
                  resize.input != null &&
                  resize.input.getTypeObject == TypeUInt &&
                  ((resize.input eq record.resizeSource) ||
                    (resize.input eq record.sourceDriver.source)) =>
              result += record.target -> resize
            case _ =>
          }
        }
      }
    }
    result.toVector
  }

'''
if "def materializedAssignmentsOf(" not in value:
    if value.count(marker) != 1:
        raise SystemExit(
            f"materialized assignment enumeration marker count={value.count(marker)}"
        )
    auto.write_text(value.replace(marker, method + marker, 1))

fallback = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogNativeFallback.scala"
)
value = fallback.read_text()

call_old = '''    val rewrittenCounterBoundaries = rewriteSymbolicCounterBoundaryComparisons(
      rewrittenDeclarations,
      analysis.symbolicCounterBoundaryWidths
    )
'''
call_new = '''    val rewrittenAutoResizes = rewriteMaterializedAutoResizeAssignments(
      component,
      rewrittenDeclarations
    )
    val rewrittenCounterBoundaries = rewriteSymbolicCounterBoundaryComparisons(
      rewrittenAutoResizes,
      analysis.symbolicCounterBoundaryWidths
    )
'''
if "val rewrittenAutoResizes = rewriteMaterializedAutoResizeAssignments(" not in value:
    if value.count(call_old) != 1:
        raise SystemExit(
            f"auto-resize rewrite call marker count={value.count(call_old)}"
        )
    value = value.replace(call_old, call_new, 1)

method_marker = '''  /**
    * Replace only the concrete witness assignment of compiler-created UInt
'''
method = '''  /**
    * Lower an exact target-sized native UInt `.resized` edge to ordinary
    * Verilog assignment coercion. IEEE-1364 assignment sizing performs the
    * required unsigned LSB truncation or zero extension from the destination
    * width, including when parameter overrides reverse the relationship that
    * held for the concrete witness.
    *
    * The graph registry authorizes one exact outer statement. Text is used only
    * to map that already-proven statement to the native emitter's unique direct
    * assignment and to remove one of its reviewed resize templates. Unsupported
    * expressions, partial targets and ambiguous emitted statements fail closed.
    */
  private def rewriteMaterializedAutoResizeAssignments(
      component: Component,
      verilog: String
  ): String = {
    val records = ExternalParameterizedAutoResize.materializedAssignmentsOf(component)
    if (records.isEmpty) return verilog

    def sourceLeaf(expression: Expression): Option[BaseType] = expression match {
      case value: BaseType => Some(value)
      case cast: CastBitVectorToBitVector => sourceLeaf(cast.input)
      case _ => None
    }

    def sourceNameMatches(emitted: String, source: BaseType): Boolean = {
      val graph = Option(source.getName()).filter(_.nonEmpty)
      graph.exists(name => emitted == name || emitted.endsWith("_" + name))
    }

    // Triple-quoted Scala regexes deliberately preserve regex backslashes and
    // avoid coupling this Python staging script to Scala string escaping.
    val directIdentifier = """^([A-Za-z_][A-Za-z0-9_$]*)$""".r
    val truncatedIdentifier =
      """^([A-Za-z_][A-Za-z0-9_$]*)\s*\[\s*([0-9]+)\s*:\s*0\s*\]$""".r
    val zeroExtendedIdentifier =
      """^\{\s*\{\s*([0-9]+)\s*\{\s*1'b0\s*\}\s*\}\s*,\s*([A-Za-z_][A-Za-z0-9_$]*)\s*\}$""".r

    var lines = verilog.split("\\n", -1).toVector
    val uniqueRecords = records.groupBy { case (target, _) => target }.toVector
    uniqueRecords.collectFirst {
      case (target, values) if values.distinct.size != 1 => target
    }.foreach { target =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-AUTO-RESIZE-REWRITE-AMBIGUOUS",
        s"one UInt target '${target.getName()}' maps to multiple exact auto-resize records",
        ParameterizedWidth.sourceLocationOf(target)
      )
    }

    uniqueRecords.map(_._2.head).sortBy { case (target, _) =>
      Option(target.getName()).getOrElse("")
    }.foreach { case (target, resize) =>
      val targetName = Option(target.getName()).filter(_.nonEmpty).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-AUTO-RESIZE-TARGET-NAME-MISSING",
          "one exact target-sized UInt assignment has no final emitted target name",
          ParameterizedWidth.sourceLocationOf(target)
        )
      }
      val source = sourceLeaf(resize.input).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-AUTO-RESIZE-SOURCE-UNSUPPORTED",
          s"target-sized assignment to '$targetName' is driven by '${resize.input.getClass.getName}', not one direct unsigned packed leaf",
          ParameterizedWidth.sourceLocationOf(target)
        )
      }
      val assignment = (
        """^(\s*(?:assign\s+)?""" + Pattern.quote(targetName) +
          """\s*(?:=|<=)\s*)(.*?)(;\s*(?://.*)?)$"""
      ).r
      val matches = lines.zipWithIndex.flatMap { case (line, index) =>
        assignment.findFirstMatchIn(line).map(index -> _)
      }
      if (matches.size != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-AUTO-RESIZE-ASSIGNMENT-NOT-UNIQUE",
          s"exact target-sized UInt '$targetName' maps to ${matches.size} native emitted assignments",
          ParameterizedWidth.sourceLocationOf(target)
        )
      }

      val (index, matched) = matches.head
      val rhs = matched.group(2).trim
      val sourceWidth = resize.input.getWidth
      val targetWidth = resize.size
      val emittedSource = rhs match {
        case directIdentifier(name)
            if sourceWidth == targetWidth && sourceNameMatches(name, source) =>
          name
        case truncatedIdentifier(name, high)
            if targetWidth <= sourceWidth &&
              BigInt(high) == BigInt(targetWidth - 1) &&
              sourceNameMatches(name, source) =>
          name
        case zeroExtendedIdentifier(count, name)
            if targetWidth >= sourceWidth &&
              BigInt(count) == BigInt(targetWidth - sourceWidth) &&
              sourceNameMatches(name, source) =>
          name
        case _ =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-AUTO-RESIZE-EMISSION-UNSUPPORTED",
            s"exact target-sized UInt '$targetName' emitted unsupported native resize expression '$rhs'",
            ParameterizedWidth.sourceLocationOf(target)
          )
      }
      lines = lines.updated(
        index,
        matched.group(1) + emittedSource + matched.group(3)
      )
    }
    lines.mkString("\\n")
  }

'''
if "private def rewriteMaterializedAutoResizeAssignments(" not in value:
    if value.count(method_marker) != 1:
        raise SystemExit(
            f"auto-resize rewrite method marker count={value.count(method_marker)}"
        )
    value = value.replace(method_marker, method + method_marker, 1)
fallback.write_text(value)
