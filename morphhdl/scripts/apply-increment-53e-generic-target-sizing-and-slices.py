#!/usr/bin/env python3
from pathlib import Path


def replace_once(path: Path, old: str, new: str, label: str) -> None:
    value = path.read_text()
    count = value.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected one match, found {count}")
    path.write_text(value.replace(old, new, 1))


# Expose exact graph-backed target-sized assignment records. The source is
# selected from original object identity, never from an emitted name or an
# equal concrete witness width.
auto = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedAutoResize.scala"
)
auto_value = auto.read_text()
auto_marker = '''  private def validSyntheticBooleanRecord(
      component: Component,
      record: SyntheticBooleanRecord
  ): Boolean =
'''
auto_method = '''  /**
    * Return exact whole-target UInt `.resized` edges whose original source is
    * one direct UInt leaf. Capture and lookup are identity-only. The emitted
    * names are intentionally not resolved here; publication does that only
    * after this graph proof has selected a unique source and target.
    */
  private[internals] def directMaterializedAssignmentsOf(
      component: Component
  ): Vector[(UInt, UInt, ResizeUInt)] = {
    if (component == null) return Vector.empty

    val result = ArrayBuffer.empty[(UInt, UInt, ResizeUInt)]
    val seen = new IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]()
    storageOf(component).foreach { storage =>
      val iterator = storage.byResizeSource.values().iterator()
      while (iterator.hasNext) {
        val record = iterator.next()
        if (
          seen.put(record.outer, java.lang.Boolean.TRUE) == null &&
          validRecord(component, record)
        ) {
          (record.outer.source, record.sourceDriver.source) match {
            case (resize: ResizeUInt, source: UInt)
                if resize.getTypeObject == TypeUInt &&
                  resize.size == record.target.getBitsWidth &&
                  resize.input != null &&
                  resize.input.getTypeObject == TypeUInt &&
                  ((resize.input eq record.resizeSource) ||
                    (resize.input eq source)) &&
                  (source.component eq component) &&
                  (source ne record.resizeSource) =>
              result += ((record.target, source, resize))
            case _ =>
          }
        }
      }
    }
    result.toVector
  }

'''
if "def directMaterializedAssignmentsOf(" not in auto_value:
    if auto_value.count(auto_marker) != 1:
        raise SystemExit(
            f"direct materialized assignment marker count={auto_value.count(auto_marker)}"
        )
    auto.write_text(auto_value.replace(auto_marker, auto_method + auto_marker, 1))


# Recover exact compiler-retained native fixed-range accesses. The result's
# direct assignment must still point at the exact access object; witness
# indices only validate that identity and never discover it.
slices = Path(
    "morphruntime/src/main/scala/spinal/core/"
    "ExternalParameterizedSliceRegistry.scala"
)
slice_value = slices.read_text()
import_marker = '''import scala.collection.mutable.ArrayBuffer

'''
import_replacement = '''import scala.collection.mutable.ArrayBuffer

import spinal.core.internals.{
  BitVectorRangedAccessFixed,
  DataAssignmentStatement
}

'''
if "BitVectorRangedAccessFixed" not in slice_value:
    if slice_value.count(import_marker) != 1:
        raise SystemExit(
            f"slice internal imports marker count={slice_value.count(import_marker)}"
        )
    slice_value = slice_value.replace(import_marker, import_replacement, 1)

slice_marker = '''  def parametersOf(component: Component): Vector[ElaborationIntegerParameter] = {
'''
slice_methods = '''  private def exactFixedAccess(
      record: ExternalParameterizedSliceRecord
  ): Option[BitVectorRangedAccessFixed] = {
    val matches = ArrayBuffer.empty[BitVectorRangedAccessFixed]
    record.result.foreachStatements {
      case assignment: DataAssignmentStatement
          if (assignment.target eq record.result) &&
            (assignment.finalTarget eq record.result) =>
        assignment.source match {
          case access: BitVectorRangedAccessFixed
              if (access.source eq record.source) &&
                BigInt(access.lo) == record.offset.default &&
                BigInt(access.getWidth) == record.width.default =>
            matches += access
          case _ =>
        }
      case _ =>
    }
    matches.toVector.distinct match {
      case Vector(access) => Some(access)
      case _              => None
    }
  }

  /**
    * Resolve one compiler-retained symbolic slice from the exact surviving
    * ranged-access object. Equal source widths, witness indices, signal names,
    * component classes and source paths are never lookup keys.
    */
  private[core] def fixedAccessRecordOf(
      component: Component,
      access: BitVectorRangedAccessFixed
  ): Option[ExternalParameterizedSliceRecord] = {
    if (component == null || access == null) return None
    val matches = slicesOf(component).filter(record =>
      exactFixedAccess(record).exists(_ eq access)
    )
    matches match {
      case Vector(record) => Some(record)
      case _              => None
    }
  }

  /** Exact records eligible for Verilog publication after graph validation. */
  private[core] def fixedAccessRecordsOf(
      component: Component
  ): Vector[(ExternalParameterizedSliceRecord, BitVectorRangedAccessFixed)] =
    slicesOf(component).flatMap(record =>
      exactFixedAccess(record).map(access => record -> access)
    )

'''
if "def fixedAccessRecordOf(" not in slice_value:
    if slice_value.count(slice_marker) != 1:
        raise SystemExit(
            f"fixed slice access marker count={slice_value.count(slice_marker)}"
        )
    slice_value = slice_value.replace(slice_marker, slice_methods + slice_marker, 1)
slices.write_text(slice_value)


# Generic publication and full-domain validation in the ordinary native
# fallback. No library/component names appear in this lowering.
fallback = Path(
    "morphhdl/src/main/scala/spinal/core/internals/"
    "ExternalParameterizedVerilogNativeFallback.scala"
)
value = fallback.read_text()

pipeline_old = '''    val rewrittenInitializers = rewriteSymbolicZeroAssignments(
      rewrittenDeclarations,
      analysis.symbolicZeroInitializers
    )
    val rewrittenCounterBoundaries = rewriteSymbolicCounterBoundaryComparisons(
      rewrittenInitializers,
      analysis.symbolicCounterBoundaryWidths
    )
'''
pipeline_new = '''    val rewrittenSlices = rewriteParameterizedFixedSlices(
      component,
      rewrittenDeclarations
    )
    val rewrittenAutoResizes = rewriteMaterializedAutoResizeAssignments(
      component,
      rewrittenSlices
    )
    val rewrittenInitializers = rewriteSymbolicZeroAssignments(
      rewrittenAutoResizes,
      analysis.symbolicZeroInitializers
    )
    val rewrittenCounterBoundaries = rewriteSymbolicCounterBoundaryComparisons(
      rewrittenInitializers,
      analysis.symbolicCounterBoundaryWidths
    )
'''
if value.count(pipeline_old) != 1:
    raise SystemExit(
        f"generic publication pipeline marker count={value.count(pipeline_old)}"
    )
value = value.replace(pipeline_old, pipeline_new, 1)

resize_start = '''  private def rewriteMaterializedAutoResizeAssignments(
'''
resize_end = '''  /**
    * Replace only the concrete witness assignment of compiler-created UInt
'''
if value.count(resize_start) > 1 or value.count(resize_end) != 1:
    raise SystemExit("generic auto-resize publication boundaries are ambiguous")
resize_method = '''  private def rewriteMaterializedAutoResizeAssignments(
      component: Component,
      verilog: String
  ): String = {
    val records =
      ExternalParameterizedAutoResize.directMaterializedAssignmentsOf(component)
    if (records.isEmpty) return verilog

    val directIdentifier = "^([A-Za-z_][A-Za-z0-9_$]*)$".r
    val truncatedIdentifier =
      "^([A-Za-z_][A-Za-z0-9_$]*)\\s*\\[\\s*([0-9]+)\\s*:\\s*0\\s*\\]$".r
    val replicatedZeroExtension =
      "^\\{\\s*\\{\\s*([0-9]+)\\s*\\{\\s*1'b0\\s*\\}\\s*\\}\\s*,\\s*([A-Za-z_][A-Za-z0-9_$]*)\\s*\\}$".r
    val sizedZeroExtension =
      "^\\{\\s*([0-9]+)'[sS]?[bBoOdDhH]([0_]+)\\s*,\\s*([A-Za-z_][A-Za-z0-9_$]*)\\s*\\}$".r

    var lines = verilog.split("\n", -1).toVector
    val grouped = records.groupBy { case (target, _, _) => target }.toVector
    grouped.collectFirst {
      case (target, values) if values.distinct.size != 1 => target
    }.foreach { target =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-AUTO-RESIZE-REWRITE-AMBIGUOUS",
        s"one UInt target '${target.getName()}' maps to multiple exact target-sizing records",
        ParameterizedWidth.sourceLocationOf(target)
      )
    }

    grouped.map(_._2.head).sortBy { case (target, _, _) =>
      Option(target.getName()).getOrElse("")
    }.foreach { case (target, source, resize) =>
      val targetName = Option(target.getName()).filter(_.nonEmpty).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-AUTO-RESIZE-TARGET-NAME-MISSING",
          "one exact target-sized UInt assignment has no final emitted target name",
          ParameterizedWidth.sourceLocationOf(target)
        )
      }
      val sourceName = Option(source.getName()).filter(_.nonEmpty).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-AUTO-RESIZE-SOURCE-NAME-MISSING",
          s"exact target-sized UInt '$targetName' has no final emitted direct-source name",
          ParameterizedWidth.sourceLocationOf(target)
        )
      }
      val assignment = (
        "^(\\s*(?:assign\\s+)?" + Pattern.quote(targetName) +
          "\\s*(?:=|<=)\\s*)(.*?)(;\\s*(?://.*)?)$"
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
      val sourceWidth = source.getBitsWidth
      val targetWidth = resize.size
      val emittedSource = rhs match {
        case directIdentifier(name)
            if sourceWidth == targetWidth && name == sourceName =>
          name
        case truncatedIdentifier(name, high)
            if targetWidth <= sourceWidth && name == sourceName &&
              BigInt(high) == BigInt(targetWidth - 1) =>
          name
        case replicatedZeroExtension(count, name)
            if targetWidth >= sourceWidth && name == sourceName &&
              BigInt(count) == BigInt(targetWidth - sourceWidth) =>
          name
        case sizedZeroExtension(size, digits, name)
            if targetWidth >= sourceWidth && name == sourceName &&
              BigInt(size) == BigInt(targetWidth - sourceWidth) &&
              digits.forall(character => character == '0' || character == '_') =>
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
    lines.mkString("\n")
  }

  /**
    * Publish compiler-retained moving native slices from exact result/access
    * identity. The native witness `[high:low]` is accepted only when it matches
    * the retained defaults; the output uses a Verilog-2001 indexed part-select
    * so correlated parameter-dependent endpoints remain valid.
    */
  private def rewriteParameterizedFixedSlices(
      component: Component,
      verilog: String
  ): String = {
    val records = ExternalParameterizedSliceRegistry.fixedAccessRecordsOf(component)
    if (records.isEmpty) return verilog

    var lines = verilog.split("\n", -1).toVector
    records.foreach { case (record, access) =>
      val resultName = Option(record.result.getName()).filter(_.nonEmpty).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-SLICE-RESULT-NAME-MISSING",
          "one exact retained native slice result has no final emitted name",
          record.sourceLocation
        )
      }
      val sourceName = Option(record.source.getName()).filter(_.nonEmpty).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-SLICE-SOURCE-NAME-MISSING",
          s"retained native slice result '$resultName' has no final emitted source name",
          record.sourceLocation
        )
      }
      val assignment = (
        "^(\\s*(?:assign\\s+)?" + Pattern.quote(resultName) +
          "\\s*(?:=|<=)\\s*)" + Pattern.quote(sourceName) +
          "\\s*\\[\\s*" + access.hi + "\\s*:\\s*" + access.lo +
          "\\s*\\](;\\s*(?://.*)?)$"
      ).r
      val matches = lines.zipWithIndex.flatMap { case (line, index) =>
        assignment.findFirstMatchIn(line).map(index -> _)
      }
      if (matches.size != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-SLICE-ASSIGNMENT-NOT-UNIQUE",
          s"exact retained native slice '$resultName' maps to ${matches.size} emitted fixed-range assignments",
          record.sourceLocation
        )
      }
      val (index, matched) = matches.head
      val offset = s"(${record.offset.verilog})"
      val width = s"(${record.width.verilog})"
      lines = lines.updated(
        index,
        matched.group(1) + sourceName + s"[$offset +: $width]" + matched.group(2)
      )
    }
    lines.mkString("\n")
  }

'''
if value.count(resize_start) == 1:
    start = value.index(resize_start)
    end = value.index(resize_end, start)
    value = value[:start] + resize_method + value[end:]
else:
    value = value.replace(resize_end, resize_method + resize_end, 1)

proof_marker = '''      def ofBase(baseType: BaseType): WidthExpr = {
'''
proof_method = '''      /**
        * Prove one exact retained slice remains inside its source over the full
        * finite domain of their common compiler-retained native-Int root.
        * Independent min/max bounds are insufficient because source width and
        * endpoints are correlated functions of the same parameter.
        */
      def provesSliceWithinSharedRoot(
          source: WidthExpr,
          offset: WidthExpr,
          width: WidthExpr
      ): Boolean = {
        if (!source.isSymbolic) return false
        val roots = retainedRoots(source) ++ retainedRoots(offset) ++ retainedRoots(width)
        roots.headOption.filter(root => roots.forall(_ eq root)).exists { root =>
          val parameters =
            (source.parameters ++ offset.parameters ++ width.parameters)
              .distinct
              .sortBy(_.name)
          val domainSize = root.maximum - root.minimum + 1
          if (
            root.parameters != parameters ||
            domainSize < 1 ||
            domainSize >
              ExternalNativeIntShadowRegistry.MaximumStructuralPredicateDomainSize
          ) false
          else {
            var value = root.minimum
            var proven = true
            while (value <= root.maximum && proven) {
              val sourceValue = evaluate(source, root, value)
              val offsetValue = evaluate(offset, root, value)
              val widthValue = evaluate(width, root, value)
              proven =
                sourceValue.exists(_ > 0) &&
                offsetValue.exists(_ >= 0) &&
                widthValue.exists(_ > 0) &&
                (for {
                  sourceBits <- sourceValue
                  startBit <- offsetValue
                  sliceBits <- widthValue
                } yield startBit + sliceBits <= sourceBits).contains(true)
              value += 1
            }
            proven
          }
        }
      }

'''
if "def provesSliceWithinSharedRoot(" not in value:
    if value.count(proof_marker) != 1:
        raise SystemExit(
            f"generic slice domain proof marker count={value.count(proof_marker)}"
        )
    value = value.replace(proof_marker, proof_method + proof_marker, 1)

fixed_old = '''      private def inferFixedRange(access: BitVectorRangedAccessFixed): WidthExpr = {
        val source = ofExpression(access.source)
        if (source.isSymbolic && BigInt(access.hi) >= source.minimum) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-SLICE-DOMAIN-UNSUPPORTED",
            s"fixed slice ${access.hi} downto ${access.lo} is not valid for the complete symbolic source-width domain '${source.render}' in [${source.minimum}, ${source.maximum}]"
          )
        }
        WidthLiteral(access.getWidth)
      }
'''
fixed_new = '''      private def inferFixedRange(access: BitVectorRangedAccessFixed): WidthExpr = {
        val source = ofExpression(access.source)
        ExternalParameterizedSliceRegistry.fixedAccessRecordOf(component, access) match {
          case Some(record) =>
            val offset = retained(record.offset)
            val width = retained(record.width)
            if (
              offset.default != BigInt(access.lo) ||
              width.default != BigInt(access.getWidth) ||
              !provesSliceWithinSharedRoot(source, offset, width)
            ) {
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-SLICE-DOMAIN-UNSUPPORTED",
                s"retained slice '${record.offset.verilog} +: ${record.width.verilog}' is not proven inside symbolic source width '${source.render}' over their complete shared domain",
                record.sourceLocation
              )
            }
            width
          case None =>
            if (source.isSymbolic && BigInt(access.hi) >= source.minimum) {
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-SLICE-DOMAIN-UNSUPPORTED",
                s"fixed slice ${access.hi} downto ${access.lo} is not valid for the complete symbolic source-width domain '${source.render}' in [${source.minimum}, ${source.maximum}]"
              )
            }
            WidthLiteral(access.getWidth)
        }
      }
'''
if value.count(fixed_old) != 1:
    raise SystemExit(
        f"generic fixed slice inference marker count={value.count(fixed_old)}"
    )
value = value.replace(fixed_old, fixed_new, 1)

fallback.write_text(value)
