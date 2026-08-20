package spinal.core.internals

import java.util.regex.{Matcher, Pattern}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import spinal.core._

/**
  * MorphHDL-owned Increment 33 relocation of validated ordinary SpinalHDL module items into
  * Verilog-2001 generate regions.
  *
  * The native emitter remains authoritative for declarations, assignments,
  * instance port order and expression syntax. This pass only extracts the
  * module items recorded by [[ParameterizedStructure]], substitutes retained
  * generate-index slices/Vec selections, and places those exact items beneath
  * generate-for/if/case control.
  */
private[internals] object ParameterizedVerilogStructural {
  private final case class LineRange(start: Int, end: Int) {
    require(start <= end)
    def indices: Range.Inclusive = start to end
    def overlaps(that: LineRange): Boolean = start <= that.end && that.start <= end
  }

  private final case class BlockPlan(
      block: ParameterizedStructuralBlock,
      ranges: Vector[LineRange],
      body: String
  )

  def hasRegions(component: Component): Boolean =
    ParameterizedStructure.regionsOf(component).nonEmpty

  def rewrite(
      component: Component,
      verilog: String,
      pc: PhaseContext,
      canonicalOf: Component => Component
  ): String = {
    val regions = ParameterizedStructure.regionsOf(component)
    if (regions.isEmpty) return verilog
    if (pc.config.isSystemVerilog) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MODE-UNSUPPORTED",
        "native structural generate lowering targets Verilog-2001, not SystemVerilog"
      )
    }

    val normalized = verilog.replace("\r\n", "\n").replace('\r', '\n')
    val lines = normalized.split("\n", -1).toVector
    val allBlocks = regions.flatMap(_.blocks)
    val duplicateBlocks = allBlocks.groupBy(identity).collectFirst {
      case (block, values) if values.size != 1 => block
    }
    duplicateBlocks.foreach { block =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-BLOCK-DUPLICATE",
        "one captured structural block is referenced by multiple generate regions",
        block.sourceLocation
      )
    }

    val portNames = component.getOrdredNodeIo.toVector
      .flatMap(port => Option(port.getName()))
      .toSet
    val parameters = mergeParameters(
      ParameterizedWidth.parametersOf(component) ++
        ExternalParameterizedMemoryRegistry.parametersOf(component) ++
        ParameterizedStructure.parametersOf(component) ++
        ParameterizedProcess.parametersOf(component)
    )
    validateParameters(component, parameters, portNames, pc)

    val plans = allBlocks.map { block =>
      planBlock(
        component,
        block,
        lines,
        portNames,
        parameters.map(_.name).toSet,
        canonicalOf
      )
    }
    val allRanges = plans.flatMap(_.ranges)
    allRanges.combinations(2).foreach {
      case Vector(left, right) if left.overlaps(right) =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-CAPTURE-OVERLAP",
          s"captured native module-item ranges ${left.start}-${left.end} and ${right.start}-${right.end} overlap"
        )
      case _ =>
    }

    val removed = allRanges.flatMap(_.indices).toSet
    val withoutCaptured = lines.zipWithIndex.collect {
      case (line, index) if !removed(index) => line
    }
    val withHeader = ensureParameterHeader(
      component.definitionName,
      withoutCaptured,
      parameters
    )
    val planByBlock = plans.map(plan => plan.block -> plan).toMap
    val renderedRegions = regions.map(region => renderRegion(region, planByBlock))

    val endmodule = withHeader.lastIndexWhere(_.trim == "endmodule")
    if (endmodule < 0) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-ENDMODULE-NOT-FOUND",
        s"native Verilog for '${component.definitionName}' contains no endmodule"
      )
    }
    (
      withHeader.take(endmodule) ++
        Vector("") ++
        renderedRegions.flatMap(_.split("\n", -1)).toVector ++
        withHeader.drop(endmodule)
    ).mkString("\n")
  }

  private def planBlock(
      component: Component,
      block: ParameterizedStructuralBlock,
      lines: Vector[String],
      portNames: Set[String],
      parameterNames: Set[String],
      canonicalOf: Component => Component
  ): BlockPlan = {
    val ranges = ArrayBuffer.empty[LineRange]
    val trackedInternalNames = mutable.LinkedHashSet.empty[String]

    // Normal Spinal transforms may forward or prune captured temporaries before
    // native emission. Such values have no emitted declaration (and therefore
    // no stable name); the instance connections and slice references below
    // recover the concrete wrapper nets that remain in Verilog.
    block.declarations.foreach { declaration =>
      Option(declaration.getName()).filter(_.nonEmpty).foreach { name =>
        trackedInternalNames += name
        ranges += findDeclarationLine(lines, name, block.sourceLocation)
      }
    }

    block.children.foreach { child =>
      val instanceName = Option(child.getName()).filter(_.nonEmpty).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-INSTANCE-NAME-MISSING",
          s"captured child of '${component.definitionName}' has no stable instance name",
          block.sourceLocation
        )
      }
      val canonical = canonicalOf(child)
      val definitionName = Option(canonical.definitionName).filter(_.nonEmpty).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-DEFINITION-NAME-MISSING",
          s"captured child '$instanceName' has no canonical definition name",
          block.sourceLocation
        )
      }
      val range = findInstanceRange(
        lines,
        definitionName,
        instanceName,
        block.sourceLocation
      )
      ranges += range
      val instanceText = range.indices.map(lines).mkString("\n")
      connectionActualNames(instanceText).foreach { name =>
        if (!portNames(name) && !parameterNames(name)) trackedInternalNames += name
      }
    }

    val fixedSlicePatterns = block.slices.map(fixedSlicePattern)
    var changed = true
    while (changed) {
      changed = false
      lines.zipWithIndex.foreach { case (line, index) =>
        if (!ranges.exists(_.indices.contains(index))) {
          val trimmed = line.trim
          val declaration = isDeclarationLine(trimmed)
          val assignment = trimmed.startsWith("assign ") && trimmed.endsWith(";")
          val mentionsInternal = trackedInternalNames.exists(name => containsName(line, name))
          val mentionsSlice = fixedSlicePatterns.exists(_.matcher(line).find())
          if ((declaration && mentionsInternal) || (assignment && (mentionsInternal || mentionsSlice))) {
            ranges += LineRange(index, index)
            identifiers(line).foreach { name =>
              if (
                !portNames(name) && !parameterNames(name) &&
                !VerilogWords(name)
              ) {
                if (trackedInternalNames.add(name)) changed = true
              }
            }
          }
        }
      }
    }

    val mergedRanges = mergeRanges(ranges.toVector)
    val selectedLines = mergedRanges.flatMap(_.indices.map(lines))
    var body = stripCommonIndent(selectedLines.mkString("\n").trim)
    if (body.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-BODY-NOT-FOUND",
        "captured structural body did not map to native Verilog module items",
        block.sourceLocation
      )
    }
    body = rewriteSlices(body, block.slices)
    body = rewriteVecSelections(body, block.vecIndices, block.sourceLocation)
    BlockPlan(block, mergedRanges, body)
  }

  private def findDeclarationLine(
      lines: Vector[String],
      name: String,
      sourceLocation: Option[String]
  ): LineRange = {
    val candidates = lines.zipWithIndex.collect {
      case (line, index)
          if isDeclarationLine(line.trim) && containsName(line, name) &&
            line.trim.endsWith(";") => index
    }
    if (candidates.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-DECLARATION-NOT-FOUND",
        s"native Verilog contains ${candidates.size} declaration lines for captured signal '$name'",
        sourceLocation
      )
    }
    LineRange(candidates.head, candidates.head)
  }

  private def findInstanceRange(
      lines: Vector[String],
      definitionName: String,
      instanceName: String,
      sourceLocation: Option[String]
  ): LineRange = {
    val instancePattern =
      ("\\b" + Pattern.quote(instanceName) + "\\s*\\(").r
    val instanceLines = lines.zipWithIndex.collect {
      case (line, index) if instancePattern.findFirstIn(line).nonEmpty => index
    }
    val candidates = instanceLines.flatMap { instanceLine =>
      val lower = math.max(0, instanceLine - 64)
      val start = (instanceLine to lower by -1).find { index =>
        containsName(lines(index), definitionName) &&
        !lines(index).trim.startsWith("module ")
      }
      val end = (instanceLine until lines.size).find(index => lines(index).trim == ");")
      for {
        from <- start
        to <- end
      } yield LineRange(from, to)
    }.distinct
    if (candidates.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-INSTANCE-NOT-FOUND",
        s"native Verilog contains ${candidates.size} instance blocks for '$definitionName $instanceName'",
        sourceLocation
      )
    }
    candidates.head
  }

  private def rewriteSlices(
      body: String,
      slices: Vector[ParameterizedStructure.StructuralSlice]
  ): String =
    slices.foldLeft(body) { case (current, slice) =>
      val sourceName = requiredName(
        slice.source,
        "structural slice source",
        slice.sourceLocation
      )
      val low = slice.offset.default
      val high = low + slice.width.default - 1
      val source = Pattern.quote(sourceName)
      val descending =
        ("\\b" + source + "\\s*\\[\\s*" + high + "\\s*:\\s*" + low + "\\s*\\]").r
      val indexed =
        ("\\b" + source + "\\s*\\[\\s*" + low + "\\s*\\+:\\s*" +
          slice.width.default + "\\s*\\]").r
      val replacement =
        s"$sourceName[${slice.offset.verilog} +: ${slice.width.verilog}]"
      val first = descending.replaceAllIn(current, Matcher.quoteReplacement(replacement))
      val rewritten = indexed.replaceAllIn(first, Matcher.quoteReplacement(replacement))
      if (rewritten == current) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SLICE-NOT-FOUND",
          s"native structural body contains no witness slice '$sourceName[$high:$low]'",
          slice.sourceLocation
        )
      }
      rewritten
    }

  private def rewriteVecSelections(
      body: String,
      selections: Vector[ParameterizedStructure.StructuralVecIndex],
      sourceLocation: Option[String]
  ): String = {
    if (selections.isEmpty) return body
    val selectors = selections.map(_.index).distinct
    if (selectors.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-VEC-SELECTOR-CONFLICT",
        "one structural body uses multiple distinct symbolic Vec selectors",
        sourceLocation
      )
    }
    val selector = selectors.head
    val minimum = selector.minimum.toInt
    val maximum = selector.maximum.toInt
    val default = selector.default.toInt

    val witnessMappings = selections.flatMap { selection =>
      val selectedLeaves = selection.selected.flatten.toVector
      val witnessLeaves = selection.vector.vec(default).asInstanceOf[Data].flatten.toVector
      if (
        selectedLeaves.size != witnessLeaves.size ||
        !selectedLeaves.zip(witnessLeaves).forall { case (left, right) => left eq right }
      ) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-VEC-WITNESS-MISMATCH",
          s"recorded Vec witness $default does not match the selected native element",
          selection.sourceLocation
        )
      }
      selectedLeaves.map { leaf =>
        requiredName(leaf, "Vec witness leaf", selection.sourceLocation)
      }
    }.distinct
    witnessMappings.foreach { name =>
      if (!containsName(body, name)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-VEC-REFERENCE-NOT-FOUND",
          s"native structural body contains no reference to Vec witness leaf '$name'",
          sourceLocation
        )
      }
    }

    val branches = (minimum to maximum).map { value =>
      var branchBody = body
      selections.foreach { selection =>
        val from = selection.selected.flatten.toVector
        val to = selection.vector.vec(value).asInstanceOf[Data].flatten.toVector
        if (from.size != to.size) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-VEC-LAYOUT-MISMATCH",
            s"Vec element $value has a different flattened layout from its witness",
            selection.sourceLocation
          )
        }
        from.zip(to).foreach { case (sourceLeaf, targetLeaf) =>
          val sourceName = requiredName(
            sourceLeaf,
            "Vec witness leaf",
            selection.sourceLocation
          )
          val targetName = requiredName(
            targetLeaf,
            s"Vec element $value leaf",
            selection.sourceLocation
          )
          branchBody = replaceName(branchBody, sourceName, targetName)
        }
      }
      s"${value}: begin : g_vec_${value}\n${indent(branchBody, 2)}\nend"
    }.mkString("\n")
    s"case (${selector.verilog})\n${indent(branches, 1)}\n  default: begin : g_vec_default\n  end\nendcase"
  }

  private def renderRegion(
      region: ParameterizedStructure.StructuralRegion,
      plans: Map[ParameterizedStructuralBlock, BlockPlan]
  ): String = region match {
    case value: ParameterizedStructure.StructuralFor =>
      val body = plans(value.body).body
      s"  genvar ${value.indexName};\n" +
        "  generate\n" +
        s"    for (${value.indexName} = 0; ${value.indexName} < ${value.count.verilog}; " +
        s"${value.indexName} = ${value.indexName} + 1) begin : ${value.label}\n" +
        indent(body, 3) + "\n" +
        "    end\n" +
        "  endgenerate"

    case value: ParameterizedStructure.StructuralIf =>
      s"  generate\n" +
        s"    if (${value.condition.verilog}) begin : ${value.whenTrueLabel}\n" +
        indent(plans(value.whenTrue).body, 3) + "\n" +
        s"    end else begin : ${value.whenFalseLabel}\n" +
        indent(plans(value.whenFalse).body, 3) + "\n" +
        "    end\n" +
        "  endgenerate"

    case value: ParameterizedStructure.StructuralCase =>
      val choices = value.choices.map { choice =>
        s"      ${choice.value}: begin : ${choice.label}\n" +
          indent(plans(choice.body).body, 4) + "\n" +
          "      end"
      }.mkString("\n")
      s"  generate\n" +
        s"    case (${value.selector.verilog})\n" +
        choices + "\n" +
        s"      default: begin : ${value.defaultLabel}\n" +
        indent(plans(value.defaultBody).body, 4) + "\n" +
        "      end\n" +
        "    endcase\n" +
        "  endgenerate"
  }

  private def ensureParameterHeader(
      definitionName: String,
      lines: Vector[String],
      parameters: Vector[ElaborationIntegerParameter]
  ): Vector[String] = {
    if (parameters.isEmpty) return lines
    val modulePattern =
      ("^module\\s+" + Pattern.quote(definitionName) + "\\b").r
    val moduleLines = lines.zipWithIndex.collect {
      case (line, index) if modulePattern.findFirstIn(line.trim).nonEmpty => index
    }
    if (moduleLines.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MODULE-HEADER-NOT-FOUND",
        s"native Verilog contains ${moduleLines.size} module headers for '$definitionName'"
      )
    }
    val moduleIndex = moduleLines.head
    val moduleLine = lines(moduleIndex)
    if (!moduleLine.contains("#(")) {
      val plain =
        ("^(\\s*)module\\s+" + Pattern.quote(definitionName) + "\\s*\\(\\s*$").r
      val matched = plain.findFirstMatchIn(moduleLine).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MODULE-HEADER-UNSUPPORTED",
          s"module '$definitionName' does not use the expected portable header form"
        )
      }
      val indentText = matched.group(1)
      val declarations = parameters.zipWithIndex.map { case (parameter, index) =>
        val comma = if (index == parameters.size - 1) "" else ","
        s"${indentText}  parameter integer ${parameter.name} = ${parameter.default}$comma"
      }
      lines.take(moduleIndex) ++
        Vector(s"${indentText}module $definitionName #(") ++
        declarations ++
        Vector(s"${indentText}) (") ++
        lines.drop(moduleIndex + 1)
    } else {
      val close = (moduleIndex + 1 until lines.size).find(index => lines(index).trim == ") (")
        .getOrElse {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-MODULE-HEADER-UNSUPPORTED",
            s"parameterized module '$definitionName' has no closing ') (' header line"
          )
        }
      val existingNames = lines.slice(moduleIndex + 1, close).flatMap { line =>
        val pattern = "\\bparameter\\s+integer\\s+([A-Za-z_][A-Za-z0-9_]*)".r
        pattern.findFirstMatchIn(line).map(_.group(1))
      }.toSet
      val missing = parameters.filterNot(parameter => existingNames(parameter.name))
      if (missing.isEmpty) lines
      else {
        val existing = lines.slice(moduleIndex + 1, close)
        val withComma =
          if (existing.isEmpty) existing
          else {
            val last = existing.last
            if (last.trim.endsWith(",")) existing
            else existing.dropRight(1) :+ (last + ",")
          }
        val additions = missing.zipWithIndex.map { case (parameter, index) =>
          val comma = if (index == missing.size - 1) "" else ","
          s"  parameter integer ${parameter.name} = ${parameter.default}$comma"
        }
        lines.take(moduleIndex + 1) ++ withComma ++ additions ++ lines.drop(close)
      }
    }
  }

  private def mergeParameters(
      values: Vector[ElaborationIntegerParameter]
  ): Vector[ElaborationIntegerParameter] = {
    val grouped = values.groupBy(_.name)
    grouped.collectFirst {
      case (name, declarations) if declarations.distinct.size != 1 => name
    }.foreach { name =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT",
        s"parameter '$name' has conflicting width and structural declarations"
      )
    }
    grouped.toVector.map(_._2.head).sortBy(_.name)
  }

  private def validateParameters(
      component: Component,
      parameters: Vector[ElaborationIntegerParameter],
      portNames: Set[String],
      pc: PhaseContext
  ): Unit = {
    val signalNames = ArrayBuffer.empty[String]
    component.dslBody.walkDeclarations {
      case baseType: BaseType => Option(baseType.getName()).foreach(signalNames += _)
      case _                  =>
    }
    parameters.foreach { parameter =>
      if (pc.verilogKeywords.contains(parameter.name)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-NAME-RESERVED",
          s"structural parameter '${parameter.name}' is reserved by IEEE 1364"
        )
      }
      if (portNames(parameter.name)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-PORT-NAME-COLLISION",
          s"structural parameter '${parameter.name}' collides with a port of '${component.definitionName}'"
        )
      }
      if (signalNames.contains(parameter.name)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-SIGNAL-NAME-COLLISION",
          s"structural parameter '${parameter.name}' collides with a signal of '${component.definitionName}'"
        )
      }
    }
  }

  private def fixedSlicePattern(
      slice: ParameterizedStructure.StructuralSlice
  ): Pattern = {
    val name = requiredName(slice.source, "slice source", slice.sourceLocation)
    val low = slice.offset.default
    val high = low + slice.width.default - 1
    Pattern.compile(
      "\\b" + Pattern.quote(name) + "\\s*\\[\\s*" + high +
        "\\s*:\\s*" + low + "\\s*\\]"
    )
  }

  private def connectionActualNames(instance: String): Set[String] = {
    val result = mutable.Set.empty[String]
    val connection = "\\.[A-Za-z_][A-Za-z0-9_$]*\\s*\\(([^)]*)\\)".r
    connection.findAllMatchIn(instance).foreach { matched =>
      identifiers(matched.group(1)).foreach(result += _)
    }
    result.toSet
  }

  private def requiredName(
      value: BaseType,
      role: String,
      sourceLocation: Option[String]
  ): String =
    Option(value.getName()).filter(_.nonEmpty).getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-NAME-MISSING",
        s"$role has no stable native Verilog name",
        sourceLocation
      )
    }

  private def isDeclarationLine(trimmed: String): Boolean =
    trimmed.startsWith("wire ") || trimmed.startsWith("reg ") ||
      trimmed.startsWith("logic ") || trimmed.startsWith("integer ")

  private def identifiers(value: String): Vector[String] =
    "[A-Za-z_][A-Za-z0-9_$]*".r.findAllIn(value).toVector

  private def containsName(value: String, name: String): Boolean =
    ("(?<![A-Za-z0-9_$])" + Pattern.quote(name) + "(?![A-Za-z0-9_$])").r
      .findFirstIn(value)
      .nonEmpty

  private def replaceName(value: String, from: String, to: String): String =
    ("(?<![A-Za-z0-9_$])" + Pattern.quote(from) + "(?![A-Za-z0-9_$])").r
      .replaceAllIn(value, Matcher.quoteReplacement(to))

  private def mergeRanges(values: Vector[LineRange]): Vector[LineRange] = {
    val sorted = values.distinct.sortBy(_.start)
    sorted.foldLeft(Vector.empty[LineRange]) {
      case (Vector(), value) => Vector(value)
      case (acc, value) if value.start <= acc.last.end + 1 =>
        acc.dropRight(1) :+ LineRange(acc.last.start, math.max(acc.last.end, value.end))
      case (acc, value) => acc :+ value
    }
  }

  private def stripCommonIndent(value: String): String = {
    val lines = value.split("\n", -1).toVector
    val nonEmpty = lines.filter(_.trim.nonEmpty)
    val common =
      if (nonEmpty.isEmpty) 0
      else nonEmpty.map(_.takeWhile(_ == ' ').length).min
    lines.map(line => if (line.length >= common) line.drop(common) else line).mkString("\n")
  }

  private def indent(value: String, level: Int): String = {
    val prefix = "  " * level
    value.split("\n", -1).map(line => if (line.nonEmpty) prefix + line else line).mkString("\n")
  }

  private val VerilogWords = Set(
    "assign",
    "begin",
    "case",
    "default",
    "else",
    "end",
    "endcase",
    "for",
    "generate",
    "if",
    "input",
    "integer",
    "module",
    "output",
    "parameter",
    "reg",
    "wire"
  )

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String] = None
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}