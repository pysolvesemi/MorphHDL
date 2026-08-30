package spinal.core.internals

import java.util.IdentityHashMap
import java.util.regex.{Matcher, Pattern}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import spinal.core._

/** MorphHDL-owned external parameterized-Verilog lowering for ordinary
  * expressions, declarations, connections and hierarchy.
  *
  * The existing ComponentEmitterVerilog remains authoritative for ordinary
  * expression and process syntax. This helper is used only when the narrower
  * Increment 30 direct-assignment gate rejects an otherwise valid ordinary
  * SpinalHDL graph. It validates retained widths and controls, asks the normal
  * emitter for Verilog-2001, then substitutes the public parameter header and
  * packed declaration ranges. No fixture-specific ParamRTL graph is involved.
  */
private[internals] object ExternalParameterizedVerilogNativeFallback {
  private val eligibleGateFailures = Set(
    "SPINAL-PARAMETERIZED-VERILOG-REGISTER-INIT-UNSUPPORTED",
    "SPINAL-PARAMETERIZED-VERILOG-INITIAL-ASSIGNMENT-UNSUPPORTED",
    "SPINAL-PARAMETERIZED-VERILOG-STATEMENT-UNSUPPORTED",
    "SPINAL-PARAMETERIZED-VERILOG-UNTAGGED-PORT",
    "SPINAL-PARAMETERIZED-VERILOG-UNTAGGED-INTERNAL-SIGNAL",
    "SPINAL-PARAMETERIZED-VERILOG-NO-SYMBOLIC-PORTS",
    "SPINAL-PARAMETERIZED-VERILOG-NO-DIRECT-ASSIGNMENTS",
    "SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-UNSUPPORTED",
    "SPINAL-PARAMETERIZED-VERILOG-REGISTER-UNSUPPORTED",
    "SPINAL-PARAMETERIZED-VERILOG-REGISTER-PATHS-UNSUPPORTED",
    "SPINAL-PARAMETERIZED-VERILOG-REGISTER-DRIVER-UNSUPPORTED",
    "SPINAL-PARAMETERIZED-VERILOG-MULTIPLE-DRIVERS-UNSUPPORTED",
    "SPINAL-PARAMETERIZED-VERILOG-OUTPUT-DRIVER-UNSUPPORTED",
    "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-UNSUPPORTED"
  )

  def supports(
      failure: ParameterizedVerilogException,
      component: Component
  ): Boolean =
    eligibleGateFailures.contains(failure.code) &&
      (
        ParameterizedWidth.parametersOf(component).nonEmpty ||
          ExternalParameterizedMemoryRegistry.parametersOf(component).nonEmpty ||
          ExternalParameterizedValueRegistry.parametersOf(component).nonEmpty ||
          ParameterizedProcess.parametersOf(component).nonEmpty ||
          ParameterizedStructure.parametersOf(component).nonEmpty ||
          component.children.exists { child =>
            ParameterizedWidth.parametersOf(child).nonEmpty ||
            ExternalParameterizedMemoryRegistry.parametersOf(child).nonEmpty ||
            ExternalParameterizedValueRegistry.parametersOf(child).nonEmpty ||
            ParameterizedProcess.parametersOf(child).nonEmpty ||
            ParameterizedStructure.parametersOf(child).nonEmpty ||
            ExternalFormalParameterRegistry.bindingsOf(child).nonEmpty
          }
      )

  def rewrite(component: Component, verilog: String, pc: PhaseContext): String =
    rewrite(component, verilog, pc, child => child)

  def rewrite(
      component: Component,
      verilog: String,
      pc: PhaseContext,
      canonicalOf: Component => Component
  ): String = {
    val hierarchy = ExternalParameterizedVerilogHierarchy.analyze(component, pc, canonicalOf)
    MorphHdlExternalParameterizedVerilog.validateComponentParameterRootInventory(
      component,
      includeChildActuals = true
    )
    val analysis = new Analysis(
      component,
      pc,
      hierarchy.parameters ++
        ExternalParameterizedMemoryRegistry.parametersOf(component) ++
        ExternalParameterizedValueRegistry.parametersOf(component) ++
        ParameterizedStructure.parametersOf(component) ++
        ParameterizedProcess.parametersOf(component),
      hierarchy.hasParameterizedInstances
    )
    analysis.validate()

    val withHeader =
      if (analysis.parameters.isEmpty) verilog
      else
        ensureParameterHeader(
          verilog,
          component.definitionName,
          analysis.parameters
        )

    val (withHierarchy, hierarchyWidths) = hierarchy.rewrite(withHeader)
    val allWidths =
      analysis.symbolicDeclarationWidths.map { case (name, expression) =>
        name -> expression.range
      } ++ hierarchyWidths
    val groupedWidths = allWidths.groupBy(_._1)
    groupedWidths
      .collectFirst {
        case (name, values) if values.map(_._2).distinct.size != 1 => name
      }
      .foreach { name =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-DECLARATION-WIDTH-CONFLICT",
          s"symbolic analysis inferred conflicting packed ranges for declaration '$name'"
        )
      }
    val widthsByName = groupedWidths.toVector
      .map { case (name, values) => name -> values.head._2 }
      .sortBy { case (name, _) => -name.length }
    val rewrittenDeclarations = withHierarchy
      .split("\\n", -1)
      .map(line => rewriteDeclarationLine(line, widthsByName))
      .mkString("\n")
    val rewrittenCounterBoundaries = rewriteSymbolicCounterBoundaryComparisons(
      rewrittenDeclarations,
      analysis.symbolicCounterBoundaryWidths
    )
    val rewrittenValues = rewriteRetainedValueAssignments(
      component,
      rewrittenCounterBoundaries
    )
    val rewrittenResizes = rewriteRetainedResizeAssignments(
      component,
      rewrittenValues
    )
    val canonical =
      if (isCanonicalDirectSurface(component))
        canonicalizeDeclarations(component, rewrittenResizes)
      else rewrittenResizes
    lowerRetainedIntegerHelpers(canonical, component.definitionName)
  }

  private def ensureParameterHeader(
      verilog: String,
      definitionName: String,
      parameters: Vector[ElaborationIntegerParameter]
  ): String = {
    val lines = verilog.split("\n", -1).toVector
    val modulePattern =
      ("^\\s*module\\s+" + Pattern.quote(definitionName) + "\\b.*$").r
    val moduleLines = lines.zipWithIndex.collect {
      case (line, index) if modulePattern.findFirstIn(line).nonEmpty => index
    }
    if (moduleLines.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MODULE-HEADER-NOT-FOUND",
        s"normal Verilog emission contains ${moduleLines.size} module headers for '$definitionName'"
      )
    }
    val start = moduleLines.head
    val line = lines(start)
    val plain =
      ("^(\\s*)module\\s+" + Pattern.quote(definitionName) + "\\s*\\(\\s*$").r
    val indent = line.takeWhile(_.isWhitespace)
    val end =
      if (plain.findFirstIn(line).nonEmpty) start
      else if (line.contains("#(")) {
        val close = (start + 1 until lines.size)
          .find(index => lines(index).trim == ") (")
          .getOrElse {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-MODULE-HEADER-UNSUPPORTED",
              s"parameterized module '$definitionName' has no canonical ') (' header terminator"
            )
          }
        val parameterPattern =
          "\\bparameter\\s+integer\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(-?[0-9]+)".r
        val existing = lines.slice(start + 1, close).flatMap { declaration =>
          parameterPattern.findFirstMatchIn(declaration).map { matched =>
            matched.group(1) -> BigInt(matched.group(2))
          }
        }
        val duplicates = existing.groupBy(_._1).collectFirst {
          case (name, values) if values.size != 1 => name
        }
        duplicates.foreach { name =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-MODULE-PARAMETER-AMBIGUOUS",
            s"module '$definitionName' declares parameter '$name' more than once"
          )
        }
        val existingMap = existing.toMap
        val expectedMap = parameters.map(parameter => parameter.name -> parameter.default).toMap
        if (existingMap != expectedMap) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-MODULE-PARAMETER-SCHEMA-CONFLICT",
            s"module '$definitionName' emitted parameter schema ${existingMap.toVector
                .sortBy(_._1)
                .mkString(",")}, expected ${expectedMap.toVector.sortBy(_._1).mkString(",")}"
          )
        }
        close
      } else {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-MODULE-HEADER-UNSUPPORTED",
          s"module '$definitionName' does not use one portable native header form"
        )
      }

    val declarations = parameters.zipWithIndex.map { case (parameter, index) =>
      val comma = if (index == parameters.size - 1) "" else ","
      s"${indent}  parameter integer ${parameter.name} = ${parameter.default}$comma"
    }
    (
      lines.take(start) ++
        Vector(s"${indent}module $definitionName #(") ++
        declarations ++
        Vector(s"${indent}) (") ++
        lines.drop(end + 1)
    ).mkString("\n")
  }

  private def renderHeader(
      definitionName: String,
      parameters: Vector[ElaborationIntegerParameter]
  ): String = {
    val declarations = parameters.zipWithIndex
      .map { case (parameter, index) =>
        val comma = if (index == parameters.size - 1) "" else ","
        s"  parameter integer ${parameter.name} = ${parameter.default}$comma"
      }
      .mkString("\n")
    s"module $definitionName #(\n$declarations\n) ("
  }

  private val NativeAddressWidthHelper = "morphhdl_address_width"
  private val NativeCeilLog2Helper = "morphhdl_ceil_log2"
  private val NativeIntegerHelper =
    "(?<![A-Za-z0-9_$])morphhdl_[A-Za-z0-9_]+\\s*\\(".r
  private val FunctionIntegerName =
    "(?m)^\\s*function\\s+integer\\s+([A-Za-z_][A-Za-z0-9_$]*)\\s*;\\s*$".r

  /** Compiler-shadow helper names are an internal expression IR, not Verilog
    * functions. Lower the reviewed positive-width helpers after every other
    * native rewrite so declarations, structural alternatives and memories all
    * share one collision-safe IEEE-1364 implementation.
    */
  private def lowerRetainedIntegerHelpers(
      verilog: String,
      definitionName: String
  ): String = {
    val needsAddressWidth = verilog.contains(NativeAddressWidthHelper + "(")
    val needsCeilLog2 = verilog.contains(NativeCeilLog2Helper + "(")
    if (!needsAddressWidth && !needsCeilLog2) {
      NativeIntegerHelper.findFirstIn(verilog).foreach { helper =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-UNSUPPORTED",
          s"module '$definitionName' retains unsupported native Int helper '${helper.trim}'"
        )
      }
      return verilog
    }

    val existingPortableHelpers =
      FunctionIntegerName
        .findAllMatchIn(verilog)
        .map(_.group(1))
        .filter(name => verilog.contains(renderPortableLogFunction(name).mkString("\n")))
        .toVector
        .distinct
    if (existingPortableHelpers.size > 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-LOG-HELPER-AMBIGUOUS",
        s"module '$definitionName' contains multiple portable logarithm helpers: ${existingPortableHelpers.sorted.mkString(", ")}"
      )
    }
    val helperName = existingPortableHelpers.headOption.getOrElse {
      firstAvailableIdentifier("clog2", identifiers(verilog))
    }

    val withAddressWidth =
      replaceNativeUnaryCalls(
        verilog,
        NativeAddressWidthHelper,
        argument => s"$helperName($argument, 1)",
        definitionName
      )
    val lowered =
      replaceNativeUnaryCalls(
        withAddressWidth,
        NativeCeilLog2Helper,
        argument => s"$helperName($argument, 0)",
        definitionName
      )
    NativeIntegerHelper.findFirstIn(lowered).foreach { helper =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-UNSUPPORTED",
        s"module '$definitionName' retains unsupported native Int helper '${helper.trim}'"
      )
    }

    if (existingPortableHelpers.nonEmpty) lowered
    else insertPortableLogFunction(lowered, definitionName, helperName)
  }

  private def replaceNativeUnaryCalls(
      value: String,
      functionName: String,
      replacement: String => String,
      definitionName: String
  ): String = {
    val marker = functionName + "("
    val out = new StringBuilder
    var cursor = 0
    var next = value.indexOf(marker, cursor)
    while (next >= 0) {
      val beforeIsIdentifier =
        next > 0 && isIdentifierCharacter(value.charAt(next - 1))
      if (beforeIsIdentifier) {
        out.append(value.substring(cursor, next + marker.length))
        cursor = next + marker.length
      } else {
        out.append(value.substring(cursor, next))
        val argumentStart = next + marker.length
        var depth = 1
        var index = argumentStart
        while (index < value.length && depth > 0) {
          value.charAt(index) match {
            case '(' => depth += 1
            case ')' => depth -= 1
            case _   =>
          }
          index += 1
        }
        if (depth != 0) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-MALFORMED",
            s"module '$definitionName' contains an unterminated call to '$functionName'"
          )
        }
        val argument = replaceNativeUnaryCalls(
          value.substring(argumentStart, index - 1),
          functionName,
          replacement,
          definitionName
        )
        out.append(replacement(argument))
        cursor = index
      }
      next = value.indexOf(marker, cursor)
    }
    out.append(value.substring(cursor))
    out.toString
  }

  private def insertPortableLogFunction(
      verilog: String,
      definitionName: String,
      helperName: String
  ): String = {
    val lines = verilog.split("\n", -1).toVector
    val modulePattern =
      ("^\\s*module\\s+" + Pattern.quote(definitionName) + "\\b.*$").r
    val moduleLines = lines.zipWithIndex.collect {
      case (line, index) if modulePattern.findFirstIn(line).nonEmpty => index
    }
    if (moduleLines.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MODULE-HEADER-NOT-FOUND",
        s"normal Verilog emission contains ${moduleLines.size} module headers for '$definitionName'"
      )
    }
    val moduleLine = moduleLines.head
    val portEnd =
      (moduleLine + 1 until lines.size)
        .find(index => lines(index).trim == ");")
        .getOrElse {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-MODULE-HEADER-NOT-FOUND",
            s"normal Verilog emission did not contain a complete module header for '$definitionName'"
          )
        }
    lines
      .patch(
        portEnd + 1,
        Vector("") ++ renderPortableLogFunction(helperName) ++ Vector(""),
        0
      )
      .mkString("\n")
  }

  private def renderPortableLogFunction(name: String): Vector[String] =
    Vector(
      s"  function integer $name;",
      "    input integer value;",
      "    input integer minimum_result;",
      "    integer remaining;",
      "    begin",
      s"      $name = 0;",
      "      for (remaining = value - 1; remaining > 0; remaining = remaining >> 1) begin",
      s"        $name = $name + 1;",
      "      end",
      s"      if ($name < minimum_result) begin",
      s"        $name = minimum_result;",
      "      end",
      "    end",
      "  endfunction"
    )

  private def identifiers(value: String): Set[String] =
    "[A-Za-z_][A-Za-z0-9_$]*".r.findAllIn(value).toSet

  private def firstAvailableIdentifier(base: String, used: Set[String]): String =
    if (!used(base)) base
    else {
      var suffix = 1
      var candidate = s"${base}_$suffix"
      while (used(candidate)) {
        suffix += 1
        candidate = s"${base}_$suffix"
      }
      candidate
    }

  private def isIdentifierCharacter(value: Char): Boolean =
    value.isLetterOrDigit || value == '_' || value == '$'

  private def rewriteDeclarationLine(
      line: String,
      widthsByName: Vector[(String, String)]
  ): String = {
    val trimmed = line.trim
    val declarationLine =
      trimmed.startsWith("input ") || trimmed.startsWith("output ") ||
        trimmed.startsWith("inout ") || trimmed.startsWith("wire ") ||
        trimmed.startsWith("reg ") || trimmed.startsWith("logic ")
    if (!declarationLine) return line

    widthsByName.foldLeft(line) { case (current, (name, range)) =>
      val quotedName = Pattern.quote(name)
      val packedPattern = ("(\\[[^\\]]+\\])(\\s+)(" + quotedName + ")(?=\\s*(?:[,;]|$))").r
      var replaced = false
      val withRange = packedPattern.replaceAllIn(
        current,
        matched => {
          if (replaced) matched.matched
          else {
            replaced = true
            range + matched.group(2) + matched.group(3)
          }
        }
      )
      if (replaced) withRange
      else {
        val scalarPattern = ("(\\s+)(" + quotedName + ")(?=\\s*(?:[,;]|$))").r
        var inserted = false
        scalarPattern.replaceAllIn(
          withRange,
          matched => {
            if (inserted) matched.matched
            else {
              inserted = true
              matched.group(1) + range + " " + matched.group(2)
            }
          }
        )
      }
    }
  }

  /** Replace only the concrete witness assignment of compiler-created UInt
    * carriers. The carrier was retained by exact object identity; its final
    * emitted name is read from that object after normal Spinal naming. No port,
    * component or user signal name is used as a discovery key.
    */
  private def rewriteRetainedValueAssignments(
      component: Component,
      verilog: String
  ): String = {
    val records = ExternalParameterizedValueRegistry.valuesOf(component)
    if (records.isEmpty) return verilog

    records.foreach { case (value, record) =>
      validateRetainedValueProjection(component, value, record)
    }

    val named = records.map { case (value, record) =>
      val name = Option(value.getName()).filter(_.nonEmpty).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VALUE-NAME-MISSING",
          "one retained native UInt carrier has no final emitted name",
          record.sourceLocation.orElse(record.expression.sourceLocation)
        )
      }
      name -> record
    }
    named
      .groupBy(_._1)
      .collectFirst {
        case (name, values) if distinctValueRecords(values.map(_._2).toVector).size != 1 =>
          name
      }
      .foreach { name =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VALUE-NAME-CONFLICT",
          s"multiple retained native UInt carriers resolved to emitted name '$name'"
        )
      }

    var lines = verilog.split("\n", -1).toVector
    val uniqueNamed = named.foldLeft(
      Vector.empty[(String, ExternalParameterizedValueRecord)]
    ) { case (known, value @ (name, record)) =>
      if (
        known.exists { case (existingName, existingRecord) =>
          existingName == name && equivalentValueRecord(existingRecord, record)
        }
      ) known
      else known :+ value
    }
    uniqueNamed.sortBy { case (name, _) => -name.length }.foreach { case (name, record) =>
      val pattern = (
        "^(\\s*assign\\s+" + Pattern.quote(name) +
          "\\s*=\\s*)(.*?)(;\\s*)$"
      ).r
      var count = 0
      lines = lines.map { line =>
        line match {
          case pattern(prefix, _, suffix) =>
            count += 1
            prefix + "(" + record.expression.verilog + ")" + suffix
          case _ => line
        }
      }
      if (count != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VALUE-ASSIGNMENT-NOT-UNIQUE",
          s"retained native UInt carrier '$name' maps to $count continuous assignments",
          record.sourceLocation.orElse(record.expression.sourceLocation)
        )
      }
    }
    lines.mkString("\n")
  }

  private def equivalentValueRecord(
      left: ExternalParameterizedValueRecord,
      right: ExternalParameterizedValueRecord
  ): Boolean =
    left.witness == right.witness &&
      ElabInt.equivalentExpression(left.expression, right.expression)

  private def distinctValueRecords(
      values: Vector[ExternalParameterizedValueRecord]
  ): Vector[ExternalParameterizedValueRecord] =
    values.foldLeft(Vector.empty[ExternalParameterizedValueRecord]) {
      case (known, value) if known.exists(equivalentValueRecord(_, value)) =>
        known
      case (known, value) => known :+ value
    }

  private def validateRetainedValueProjection(
      component: Component,
      value: UInt,
      record: ExternalParameterizedValueRecord
  ): Unit = {
    if (record.expression.exactDomain.isEmpty) return
    val role =
      s"retained UInt value '${Option(value.getName()).filter(_.nonEmpty).getOrElse("<unnamed>")}'"
    val source = record.sourceLocation.orElse(record.expression.sourceLocation)
    val evaluation = ParameterizedStructure
      .projectedDeclarationEvaluationOf(
        component,
        value,
        record.expression,
        role,
        source
      )
      .getOrElse {
        fail(
          "SPINAL-ELAB-DOMAIN-PROJECTION-EVIDENCE-MISSING",
          s"$role lost its exact typed evaluation evidence",
          source
        )
      }
    evaluation.results
      .collectFirst {
        case (rootValue, result) if result < 0 =>
          rootValue -> result
      }
      .foreach { case (rootValue, result) =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VALUE-DOMAIN-UNSUPPORTED",
          s"$role evaluates to negative value $result at root value $rootValue",
          source
        )
      }

    val widthsByRoot = ParameterizedWidth.expressionOf(value) match {
      case Some(width) if width.exactDomain.nonEmpty =>
        val widthEvaluation = ParameterizedStructure
          .projectedDeclarationEvaluationOf(
            component,
            value,
            width,
            s"$role carrier width",
            source.orElse(width.sourceLocation)
          )
          .getOrElse {
            fail(
              "SPINAL-ELAB-DOMAIN-PROJECTION-EVIDENCE-MISSING",
              s"$role carrier width lost its exact typed evaluation evidence",
              source.orElse(width.sourceLocation)
            )
          }
        if (widthEvaluation.rootValues != evaluation.rootValues) {
          fail(
            "SPINAL-ELAB-DOMAIN-PROJECTION-OWNER-DOMAIN-MISMATCH",
            s"$role value and carrier width resolve to different exact owner domains",
            source
          )
        }
        widthEvaluation.results.toMap
      case Some(width) =>
        evaluation.rootValues.iterator.map(_ -> width.minimum).toMap
      case None =>
        evaluation.rootValues.iterator
          .map(_ -> BigInt(value.getBitsWidth))
          .toMap
    }
    evaluation.results
      .collectFirst {
        case (rootValue, result) if widthsByRoot.get(rootValue).forall { width =>
              width < 1 || !width.isValidInt || result >= (BigInt(1) << width.toInt)
            } =>
          rootValue -> (result -> widthsByRoot.get(rootValue))
      }
      .foreach { case (rootValue, (result, width)) =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VALUE-WIDTH-INSUFFICIENT",
          s"$role evaluates to $result at root value $rootValue, outside carrier width ${width.map(_.toString).getOrElse("<missing>")}",
          source
        )
      }
  }

  /** Resolve typed and legacy shadow resize provenance through exact native
    * Resize identity. Both paths may coexist during migration only when their
    * expressions are equivalent.
    */
  private def retainedResizeExpression(
      resize: Resize
  ): Option[ElaborationIntegerExpression] = {
    val typed = ParameterizedWidth.resizeExpressionOf(resize)
    val legacy = ExternalParameterizedResizeRegistry.expressionOf(resize)
    (typed, legacy) match {
      case (Some(left), Some(right)) if !ExternalFormalParameterRegistry.equivalentExpression(left, right) =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-RESIZE-PROVENANCE-CONFLICT",
          s"one exact native Resize target is associated with conflicting typed expression '${left.verilog}' and legacy expression '${right.verilog}'",
          left.sourceLocation.orElse(right.sourceLocation)
        )
      case (Some(value), _) => Some(value)
      case (_, Some(value)) => Some(value)
      case _                => None
    }
  }

  /** Replace a concrete witness LSB slice emitted for one exact native Resize
    * with the retained symbolic target range. The eligible assignment, target
    * and Resize node are discovered from the normalized graph by JVM identity;
    * emitted names are used only after that proof to address the corresponding
    * native Verilog assignment. Other Resize renderings remain owned by the
    * native emitter.
    */
  private def rewriteRetainedResizeAssignments(
      component: Component,
      verilog: String
  ): String = {
    final case class RetainedResizeAssignment(
        targetName: String,
        sourceName: Option[String],
        witnessSize: Int,
        expression: ElaborationIntegerExpression
    )

    val retained = ArrayBuffer.empty[RetainedResizeAssignment]
    component.dslBody.walkLeafStatements {
      case assignment: DataAssignmentStatement if assignment.target == assignment.finalTarget =>
        (assignment.target, assignment.source) match {
          case (target: BitVector, resize: Resize)
              if (target.component eq component) && target.isComb &&
                target.getBitsWidth == resize.size =>
            val capturedAutoResize = ExternalParameterizedAutoResize
              .materializedResizeBoundary(component, resize)
              .flatMap {
                case (outer, autoTarget) if (outer eq assignment) && (autoTarget eq target) =>
                  val sourceName = resize.input match {
                    case source: BaseType =>
                      Option(source.getName()).filter(_.nonEmpty).getOrElse {
                        fail(
                          "SPINAL-PARAMETERIZED-VERILOG-RESIZE-SOURCE-NAME-MISSING",
                          "one captured native auto-resize source has no final emitted name"
                        )
                      }
                    case _ =>
                      fail(
                        "SPINAL-PARAMETERIZED-VERILOG-RESIZE-SOURCE-UNSUPPORTED",
                        "one captured native auto-resize source is not a direct packed signal"
                      )
                  }
                  ParameterizedWidth
                    .expressionOf(autoTarget)
                    .map(expression => expression -> Some(sourceName))
                case _ => None
              }
            retainedResizeExpression(resize)
              .map(expression => expression -> Option.empty[String])
              .orElse(capturedAutoResize)
              .filter(_._1.parameters.nonEmpty)
              .foreach { case (expression, sourceName) =>
                if (expression.default != BigInt(resize.size)) {
                  fail(
                    "SPINAL-PARAMETERIZED-VERILOG-RESIZE-WITNESS-MISMATCH",
                    s"native Resize target ${resize.size} does not match retained symbolic default ${expression.default}",
                    expression.sourceLocation
                  )
                }
                val targetName = Option(target.getName()).filter(_.nonEmpty).getOrElse {
                  fail(
                    "SPINAL-PARAMETERIZED-VERILOG-RESIZE-TARGET-NAME-MISSING",
                    "one retained native Resize target has no final emitted name",
                    expression.sourceLocation
                  )
                }
                retained += RetainedResizeAssignment(
                  targetName,
                  sourceName,
                  resize.size,
                  expression
                )
              }
          case _ =>
        }
      case _ =>
    }
    if (retained.isEmpty) return verilog

    val unique = retained.toVector.foldLeft(
      Vector.empty[RetainedResizeAssignment]
    ) { case (known, value) =>
      if (
        known.exists { existing =>
          existing.targetName == value.targetName &&
          existing.sourceName == value.sourceName &&
          existing.witnessSize == value.witnessSize &&
          ElabInt.equivalentExpression(existing.expression, value.expression)
        }
      ) known
      else known :+ value
    }
    val grouped = unique.groupBy(_.targetName)
    grouped
      .collectFirst {
        case (name, values) if values.size != 1 => name
      }
      .foreach { name =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-RESIZE-TARGET-CONFLICT",
          s"retained native Resize assignments for target '$name' disagree"
        )
      }

    var lines = verilog.split("\n", -1).toVector
    grouped.toVector.sortBy { case (name, _) => -name.length }.foreach { case (name, values) =>
      val record = values.head
      val assignmentPattern = (
        "^(\\s*assign\\s+" + Pattern.quote(name) +
          "\\s*=\\s*)(.*?)(;\\s*)$"
      ).r
      val concreteRange = (
        "^(.*)\\[\\s*" + (record.witnessSize - 1) +
          "\\s*:\\s*0\\s*\\](\\s*)$"
      ).r
      val symbolicRange = {
        val expression = record.expression.verilog
        if ("[A-Za-z_][A-Za-z0-9_$]*".r.pattern.matcher(expression).matches())
          s"[$expression-1:0]"
        else s"[($expression)-1:0]"
      }
      var assignmentCount = 0
      lines = record.sourceName match {
        case None =>
          lines.map { line =>
            line match {
              case assignmentPattern(prefix, rhs, suffix) =>
                assignmentCount += 1
                rhs match {
                  case concreteRange(source, trailing) =>
                    prefix + source + symbolicRange + trailing + suffix
                  case _ => line
                }
              case _ => line
            }
          }
        case Some(sourceName) =>
          val exactEdge = (
            "^(\\s*(?:assign\\s+)?" + Pattern.quote(name) +
              "\\s*=\\s*)" + Pattern.quote(sourceName) +
              "(\\s*)\\[\\s*" + (record.witnessSize - 1) +
              "\\s*:\\s*0\\s*\\](\\s*;\\s*)$"
          ).r
          lines.map { line =>
            line match {
              case exactEdge(prefix, spacing, suffix) =>
                assignmentCount += 1
                prefix + sourceName + spacing + symbolicRange + suffix
              case _ => line
            }
          }
      }
      if (assignmentCount != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-RESIZE-ASSIGNMENT-NOT-UNIQUE",
          s"retained native Resize target '$name' maps to $assignmentCount exact emitted assignments",
          record.expression.sourceLocation
        )
      }
    }
    lines.mkString("\n")
  }

  /** Preserve the untouched native full-range Counter boundary comparison when
    * its concrete witness state is widened externally. Only state leaves whose
    * provenance was retained by ExternalParameterizedCounterRegistry are
    * eligible; fixed-width literals written by users are intentionally left
    * unchanged.
    */
  private def rewriteSymbolicCounterBoundaryComparisons(
      verilog: String,
      boundaries: Vector[(String, ElaborationIntegerExpression)]
  ): String = {
    val literalSyntax = "[0-9]+'[sS]?[bBoOdDhH][0-9a-fA-F_xXzZ_]+"
    val literalParser =
      "(?i)^([0-9]+)'([s]?)([bodh])([0-9a-f_xz_]+)$".r

    def literalValue(digits: String, radix: String): Option[BigInt] = {
      if (
        digits.exists(character =>
          character == 'x' || character == 'X' ||
            character == 'z' || character == 'Z'
        )
      ) None
      else {
        val base = radix.toLowerCase match {
          case "b" => 2
          case "o" => 8
          case "d" => 10
          case "h" => 16
        }
        Some(BigInt(digits.replace("_", ""), base))
      }
    }

    def isWitnessAllOnes(
        literal: String,
        width: ElaborationIntegerExpression
    ): Boolean = literal match {
      case literalParser(sizeText, _, radix, digits) if width.default.isValidInt && width.default > 0 =>
        val size = BigInt(sizeText)
        size == width.default &&
        literalValue(digits, radix).contains((BigInt(1) << size.toInt) - 1)
      case _ => false
    }

    boundaries.distinct.foldLeft(verilog) { case (current, (name, width)) =>
      if (width.parameters.isEmpty || !width.default.isValidInt || width.default < 1) current
      else {
        val repeatCount =
          if ("[A-Za-z_][A-Za-z0-9_$]*".r.pattern.matcher(width.verilog).matches())
            width.verilog
          else s"(${width.verilog})"
        val replacement = s"{$repeatCount{1'b1}}"
        val signal = Pattern.quote(name)
        val left =
          ("(^|[^A-Za-z0-9_$])(" + signal +
            "\\s*(?:===|!==|==|!=)\\s*)(" + literalSyntax + ")").r
        val right =
          ("(^|[^A-Za-z0-9_$])(" + literalSyntax +
            ")(\\s*(?:===|!==|==|!=)\\s*" + signal +
            ")(?=$|[^A-Za-z0-9_$])").r
        val afterLeft = left.replaceAllIn(
          current,
          matched =>
            if (isWitnessAllOnes(matched.group(3), width))
              matched.group(1) + matched.group(2) + replacement
            else matched.matched
        )
        right.replaceAllIn(
          afterLeft,
          matched =>
            if (isWitnessAllOnes(matched.group(2), width))
              matched.group(1) + replacement + matched.group(3)
            else matched.matched
        )
      }
    }
  }

  private final case class RenderedDeclaration(
      indent: String,
      syntax: String,
      direction: Option[String],
      net: String,
      range: String,
      name: String,
      suffix: String
  ) {
    private def renderedDirection: String = direction match {
      case Some("input")  => "input "
      case Some("output") => "output"
      case Some("inout")  => "inout "
      case Some(other)    => other
      case None           => ""
    }

    def renderPort(comma: Boolean): String = {
      val ending = suffix + (if (comma) "," else "")
      val body = f"$renderedDirection%6s $net%-4s $range%-8s $name$ending"
      indent + syntax + body
    }

    def renderSignal: String = {
      val body = f"$net%-10s $range%-8s $name$suffix"
      indent + syntax + body + ";"
    }
  }

  /** Preserve the declaration canonicalization contract of the original
    * direct-assignment bridge without reordering ordinary native expression,
    * process, hierarchy or library output. The direct surface contains only
    * whole-leaf assignments (and at most its native unconditional register
    * path); every richer graph must retain the native emitter's declaration
    * order so a concrete witness remains byte-identical after concretization.
    */
  private def isCanonicalDirectSurface(component: Component): Boolean = {
    val assignments = ArrayBuffer.empty[DataAssignmentStatement]
    var unsupported =
      component.children.nonEmpty ||
        ExternalParameterizedMemoryRegistry.parametersOf(component).nonEmpty ||
        ExternalParameterizedValueRegistry.parametersOf(component).nonEmpty ||
        ParameterizedProcess.parametersOf(component).nonEmpty ||
        ParameterizedStructure.parametersOf(component).nonEmpty

    component.dslBody.walkLeafStatements {
      case _: BaseType                         =>
      case assignment: DataAssignmentStatement => assignments += assignment
      case _                                   => unsupported = true
    }
    component.dslBody.walkStatements {
      case _: TreeStatement => unsupported = true
      case _                =>
    }

    !unsupported && assignments.nonEmpty && assignments.forall { assignment =>
      (assignment.target, assignment.source) match {
        case (target: BaseType, _: BaseType) =>
          assignment.finalTarget == target &&
          assignment.parentScope == target.rootScopeStatement
        case _ => false
      }
    }
  }

  private def canonicalizeDeclarations(
      component: Component,
      verilog: String
  ): String = {
    val lines = verilog.split("\\n", -1).toVector
    val moduleIndex = lines.indexWhere(_.trim.startsWith("module "))
    if (moduleIndex < 0) return verilog
    val parameterizedPortStart = (moduleIndex until lines.size)
      .find(index => lines(index).trim == ") (")
      .map(_ + 1)
    val plainPortStart =
      if (!lines(moduleIndex).contains("#(") && lines(moduleIndex).trim.endsWith("("))
        Some(moduleIndex + 1)
      else None
    val portStart = parameterizedPortStart.orElse(plainPortStart).getOrElse(return verilog)
    val portEnd = (portStart until lines.size)
      .find(lines(_).trim == ");")
      .getOrElse(return verilog)

    val portPattern =
      "^(\\s*)(.*?)(input|output|inout)\\s+(wire|reg|logic)\\s*(\\[[^\\]]+\\])?\\s*([A-Za-z_][A-Za-z0-9_$]*)(.*?)(?:,)?\\s*$".r
    val parsedPorts = lines.slice(portStart, portEnd).filter(_.trim.nonEmpty).map {
      case portPattern(indent, syntax, direction, net, range, name, suffix) =>
        RenderedDeclaration(
          indent,
          syntax,
          Some(direction),
          net,
          Option(range).getOrElse(""),
          name,
          suffix.replaceFirst(",\\s*$", "").trim
        )
      case line =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-PORT-LINE-MAPPING-AMBIGUOUS",
          s"module '${component.definitionName}' contains an unsupported native port declaration: ${line.trim}"
        )
    }
    val duplicatePorts = parsedPorts.groupBy(_.name).collectFirst {
      case (name, values) if values.size != 1 => name
    }
    duplicatePorts.foreach { name =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-PORT-LINE-MAPPING-AMBIGUOUS",
        s"module '${component.definitionName}' contains multiple native port declarations named '$name'"
      )
    }
    val graphPorts = component.getOrdredNodeIo.toVector
      .filterNot(_.isSuffix)
      .flatMap(port => Option(port.getName()).filter(_.nonEmpty))
      .toSet
    val missingGraphPorts = graphPorts.diff(parsedPorts.map(_.name).toSet)
    if (missingGraphPorts.nonEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-PORT-LINE-MAPPING-MISSING",
        s"module '${component.definitionName}' has no native declaration for graph ports ${missingGraphPorts.toVector.sorted
            .mkString(", ")}"
      )
    }
    val orderedPorts = parsedPorts.sortBy { port =>
      val direction = port.direction match {
        case Some("input")  => 0
        case Some("output") => 1
        case _              => 2
      }
      (direction, port.name)
    }
    val canonicalPorts = orderedPorts.zipWithIndex.map { case (port, index) =>
      port.renderPort(comma = index != orderedPorts.size - 1)
    }

    val declarationNames = mutable.LinkedHashSet.empty[String]
    component.dslBody.walkDeclarations {
      case baseType: BaseType if !baseType.isIo && !baseType.isSuffix =>
        Option(baseType.getName()).filter(_.nonEmpty).foreach(declarationNames += _)
      case _ =>
    }
    val signalPattern =
      "^(\\s*)(.*?)(wire|reg|logic)\\s*(\\[[^\\]]+\\])?\\s*([A-Za-z_][A-Za-z0-9_$]*)(.*?)\\s*;\\s*$".r
    val signalSlots = lines.zipWithIndex.collect {
      case (signalPattern(indent, syntax, net, range, name, suffix), index)
          if index > portEnd && declarationNames.contains(name) =>
        index -> RenderedDeclaration(
          indent,
          syntax,
          None,
          net,
          Option(range).getOrElse(""),
          name,
          suffix.trim
        )
    }
    val duplicateSignals = signalSlots.map(_._2).groupBy(_.name).collectFirst {
      case (name, values) if values.size != 1 => name
    }
    duplicateSignals.foreach { name =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-SIGNAL-LINE-MAPPING-AMBIGUOUS",
        s"module '${component.definitionName}' contains multiple native declarations named '$name'"
      )
    }
    val orderedSignals = signalSlots.map(_._2).sortBy(_.name)
    var result = lines
      .patch(portStart, canonicalPorts, portEnd - portStart)
    if (signalSlots.nonEmpty) {
      // Port canonicalization preserves the number of lines, so original signal
      // declaration indexes remain valid.
      signalSlots.map(_._1).sorted.zip(orderedSignals).foreach { case (index, signal) =>
        result = result.updated(index, signal.renderSignal)
      }
    }
    val normalized =
      if (
        portEnd + 2 < result.size &&
        result(portEnd + 1).trim.isEmpty &&
        result(portEnd + 2).trim.isEmpty
      ) result.patch(portEnd + 1, Nil, 1)
      else result
    normalized.mkString("\n")
  }

  private final class Analysis(
      component: Component,
      pc: PhaseContext,
      hierarchyParameters: Vector[ElaborationIntegerParameter],
      hasParameterizedHierarchy: Boolean
  ) {
    private val declarations = ArrayBuffer.empty[BaseType]
    private val memories = ArrayBuffer.empty[Mem[_]]
    private val assignments = ArrayBuffer.empty[DataAssignmentStatement]
    private val treeStatements = ArrayBuffer.empty[TreeStatement]
    private val widthInference = new WidthInference

    component.dslBody.walkDeclarations {
      case baseType: BaseType if !baseType.isSuffix => declarations += baseType
      case memory: Mem[_]                           => memories += memory
      case _                                        =>
    }
    component.dslBody.walkLeafStatements {
      case assignment: DataAssignmentStatement => assignments += assignment
      case _                                   =>
    }
    component.dslBody.walkStatements {
      case tree: TreeStatement => treeStatements += tree
      case _                   =>
    }

    lazy val symbolicDeclarationWidths: Vector[(String, WidthExpr)] =
      declarations.distinct.toVector.flatMap {
        case bitVector: BitVector =>
          val expression = widthInference.ofBase(bitVector)
          if (expression.isSymbolic) {
            Option(bitVector.getName()).filter(_.nonEmpty).map(_ -> expression)
          } else None
        case _ => None
      }

    lazy val symbolicCounterBoundaryWidths: Vector[(String, ElaborationIntegerExpression)] =
      declarations.distinct.toVector.flatMap {
        case bitVector: BitVector =>
          spinal.lib.ExternalParameterizedCounterRegistry
            .boundaryWidthOf(bitVector)
            .flatMap { expression =>
              Option(bitVector.getName()).filter(_.nonEmpty).map(_ -> expression)
            }
        case _ => None
      }

    lazy val parameters: Vector[ElaborationIntegerParameter] = {
      val referenced =
        symbolicDeclarationWidths.flatMap(_._2.parameters) ++ hierarchyParameters
      val retainedRoots =
        symbolicDeclarationWidths.flatMap(_._2.parameterRoots)
      retainedRoots
        .groupBy(_.name)
        .collectFirst {
          case (name, roots) if distinctParameterRoots(roots).size != 1 =>
            name -> roots
        }
        .foreach { case (name, roots) =>
          fail(
            "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED",
            s"component '${component.definitionName}' retains independently sourced declarations for parameter '$name'",
            roots.flatMap(_.sourceLocation).headOption
          )
        }
      val grouped = referenced.groupBy(_.name)
      grouped
        .collectFirst {
          case (name, values) if values.distinct.size != 1 => name
        }
        .foreach { name =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-SCHEMA-CONFLICT",
            s"parameter '$name' has conflicting declarations on component '${component.definitionName}'"
          )
        }
      grouped.toVector.map(_._2.head).sortBy(_.name)
    }

    def validate(): Unit = {
      if (pc.config.isSystemVerilog) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-MODE-UNSUPPORTED",
          "generic parameterized expressions target Verilog-2001, not SystemVerilog"
        )
      }
      // Native memories are validated and canonically lowered before this
      // generic declaration-width pass.
      if (parameters.isEmpty && !hasParameterizedHierarchy) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-NO-SYMBOLIC-PORTS",
          s"component '${component.definitionName}' has no retained or inferred symbolic packed widths"
        )
      }
      val ports = declarations.distinct.filter(_.isIo)
      if (!ports.exists(_.isInput) || !ports.exists(_.isOutput)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-PORT-DIRECTIONS-UNSUPPORTED",
          s"component '${component.definitionName}' must expose at least one native input and one native output"
        )
      }

      validateParameters()
      validateWidths()
      validateAssignments()
      validateProcesses()
    }

    private def parameterSourceLocation(
        parameter: ElaborationIntegerParameter
    ): Option[String] =
      declarations.distinct.collectFirst {
        case bitVector: BitVector
            if widthInference.ofBase(bitVector).parameters.contains(parameter) &&
              ParameterizedWidth.sourceLocationOf(bitVector).nonEmpty =>
          ParameterizedWidth.sourceLocationOf(bitVector).get
      }

    private def validateParameters(): Unit = {
      val portableIdentifier = "[A-Za-z_][A-Za-z0-9_]*".r
      val namedDeclarations = declarations.distinct.flatMap { value =>
        Option(value.getName()).filter(_.nonEmpty).map(_ -> value)
      }.toMap

      parameters.foreach { parameter =>
        if (
          parameter.name == null ||
          !portableIdentifier.pattern.matcher(parameter.name).matches()
        ) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-NAME-INVALID",
            s"parameter name '${parameter.name}' is not a portable Verilog identifier",
            parameterSourceLocation(parameter)
          )
        }
        if (pc.verilogKeywords.contains(parameter.name)) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-NAME-RESERVED",
            s"parameter name '${parameter.name}' is reserved by IEEE 1364",
            parameterSourceLocation(parameter)
          )
        }
        if (
          parameter.minimum < 0 || parameter.maximum < parameter.minimum ||
          parameter.default < parameter.minimum || parameter.default > parameter.maximum ||
          parameter.maximum > BigInt(pc.config.bitVectorWidthMax)
        ) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-DOMAIN-INVALID",
            s"parameter '${parameter.name}' must have a non-negative bounded domain no larger than SpinalConfig.bitVectorWidthMax=${pc.config.bitVectorWidthMax}, with its default inside that domain",
            parameterSourceLocation(parameter)
          )
        }
        namedDeclarations.get(parameter.name).foreach { signal =>
          fail(
            if (signal.isIo)
              "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-PORT-NAME-COLLISION"
            else "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-SIGNAL-NAME-COLLISION",
            s"parameter '${parameter.name}' collides with signal '${signal.getName()}' of component '${component.definitionName}'",
            ParameterizedWidth.sourceLocationOf(signal).orElse(parameterSourceLocation(parameter))
          )
        }
      }
    }

    private def validateWidths(): Unit = {
      declarations.distinct.foreach {
        case bitVector: BitVector =>
          val expression = widthInference.ofBase(bitVector)
          val projectedResults =
            widthInference.projectedResultsOf(bitVector, expression)
          if (expression.default != BigInt(bitVector.getBitsWidth)) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-WITNESS-MISMATCH",
              s"signal '${bitVector.getName()}' concrete width ${bitVector.getBitsWidth} does not match inferred width default ${expression.default}",
              ParameterizedWidth.sourceLocationOf(bitVector)
            )
          }
          val minimum = projectedResults.map(_.min).getOrElse(expression.minimum)
          val maximum = projectedResults.map(_.max).getOrElse(expression.maximum)
          if (minimum < 1) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-EXPRESSION-DOMAIN-NONPOSITIVE",
              s"signal '${bitVector.getName()}' width expression '${expression.render}' reaches $minimum; every declared width must stay positive over its exact owner domain",
              ParameterizedWidth.sourceLocationOf(bitVector)
            )
          }
          if (maximum > BigInt(pc.config.bitVectorWidthMax)) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-EXPRESSION-DOMAIN-TOO-LARGE",
              s"signal '${bitVector.getName()}' width expression '${expression.render}' reaches $maximum, above SpinalConfig.bitVectorWidthMax=${pc.config.bitVectorWidthMax}",
              ParameterizedWidth.sourceLocationOf(bitVector)
            )
          }
        case _ =>
      }
    }

    private def validateAssignments(): Unit = {
      assignments.foreach { assignment =>
        if (!isHierarchyBoundary(assignment)) {
          assignment.finalTarget match {
            case target: BitVector if assignment.target == target && assignment.source.isInstanceOf[WidthProvider] =>
              val targetWidth = widthInference.ofBase(target)
              val sourceWidth = widthInference.ofExpression(assignment.source)
              val nativeCounterNext = isNativeCounterNextAssignment(
                assignment,
                target,
                targetWidth,
                sourceWidth
              )
              val provenAutoResize = isProvenAutoResizeAssignment(
                assignment,
                target,
                targetWidth,
                sourceWidth
              )
              val provenModularUpdate = isProvenModularUIntUpdate(
                assignment,
                target,
                targetWidth,
                sourceWidth
              )
              val provenCapturedDomainEquivalent =
                isProvenCapturedDomainWidthEquivalence(
                  assignment,
                  targetWidth,
                  sourceWidth
                )
              val provenInvariantTargetWidth =
                targetWidth.isSymbolic &&
                  targetWidth.minimum == targetWidth.maximum &&
                  sourceWidth.default == targetWidth.default
              if (
                targetWidth.isSymbolic && sourceWidth.isSymbolic &&
                targetWidth != sourceWidth && !nativeCounterNext &&
                !provenAutoResize && !provenModularUpdate &&
                !provenCapturedDomainEquivalent
              ) {
                fail(
                  "SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-WIDTH-MISMATCH",
                  s"assignment to '${target.getName()}' crosses symbolic width expressions '${targetWidth.render}' and '${sourceWidth.render}'",
                  ParameterizedWidth.sourceLocationOf(target)
                )
              }
              if (
                targetWidth.isSymbolic && !sourceWidth.isSymbolic &&
                !isUnfixedLiteral(assignment.source) && !nativeCounterNext &&
                !provenAutoResize && !provenModularUpdate &&
                !provenInvariantTargetWidth &&
                !provenCapturedDomainEquivalent
              ) {
                fail(
                  "SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-WIDTH-MISMATCH",
                  s"assignment to symbolic signal '${target.getName()}' uses concrete-width expression ${sourceWidth.render}; explicit domain-safe conversion is required",
                  ParameterizedWidth.sourceLocationOf(target)
                )
              }
            case _ =>
          }
        }
      }
    }

    /** Distinct symbolic width expressions may be equal only inside the exact
      * bounded structural alternative that owns an assignment. Authorize that
      * assignment by identity only when both expressions retain evaluator
      * provenance for the same predicate root and exhaustive evaluation agrees
      * at every admitted root value for its complete captured path.
      */
    private def isProvenCapturedDomainWidthEquivalence(
        assignment: DataAssignmentStatement,
        targetWidth: WidthExpr,
        sourceWidth: WidthExpr
    ): Boolean = {
      if (!targetWidth.isSymbolic) return false
      ParameterizedStructure
        .capturedAssignmentDomainOf(component, assignment)
        .exists { domain =>
          domain.values.forall { value =>
            val target = widthInference.evaluate(
              targetWidth,
              domain.root,
              value
            )
            val source = widthInference.evaluate(
              sourceWidth,
              domain.root,
              value
            )
            target.nonEmpty && target == source && target.exists(_ > 0)
          }
        }
    }

    /** Native Counter elaboration intentionally uses the concrete witness width
      * in its arithmetic and zero-valued clear path. Accept that widening only
      * for statements captured by exact object identity at Counter construction;
      * user-authored assignments to the public valueNext signal are not covered.
      */
    private def isNativeCounterNextAssignment(
        assignment: DataAssignmentStatement,
        target: BitVector,
        targetWidth: WidthExpr,
        sourceWidth: WidthExpr
    ): Boolean =
      spinal.lib.ExternalParameterizedCounterRegistry
        .nativeNextAssignmentWidthOf(target, assignment)
        .exists { expression =>
          val retained = WidthRetained(
            expression.verilog,
            expression.default,
            expression.minimum,
            expression.maximum,
            expression.parameters.distinct.sortBy(_.name),
            validatedParameterRoots(
              expression.parameterRoots,
              s"retained native Counter width '${expression.verilog}'",
              expression.sourceLocation
            ),
            expression.exactDomain,
            projectionSignatureOf(expression)
          )
          targetWidth == retained &&
          sourceWidth.default == targetWidth.default &&
          sourceWidth.minimum >= targetWidth.minimum &&
          sourceWidth.maximum <= targetWidth.maximum &&
          sourceWidth.parameters.forall(targetWidth.parameters.contains)
        }

    /** Native UInt `.resized` is an explicit whole-target sizing boundary.
      * Authorize it only when pre-normalization capture and the surviving
      * statement/target identities agree and no fixed Resize node remains.
      */
    private def isProvenAutoResizeAssignment(
        assignment: DataAssignmentStatement,
        target: BitVector,
        targetWidth: WidthExpr,
        sourceWidth: WidthExpr
    ): Boolean =
      target match {
        case uint: UInt =>
          val materialized = assignment.source match {
            case resize: Resize =>
              ExternalParameterizedAutoResize
                .materializedResizeBoundary(component, resize)
                .exists { case (outer, resizeTarget) =>
                  (outer eq assignment) && (resizeTarget eq uint) &&
                  equivalentWidthExpression(sourceWidth, targetWidth)
                }
            case _ => false
          }
          targetWidth.isSymbolic &&
          sourceWidth.default == targetWidth.default &&
          (ExternalParameterizedAutoResize.proves(
            component,
            assignment,
            uint
          ) || materialized)
        case _ => false
      }

    private final case class ModularUIntFacts(
        targetReferences: Int,
        booleanValues: Int
    )

    /** A direct unsigned self-update made only from Add/Sub and Boolean values
      * is stable modulo the symbolic target width. Native normalization may
      * widen Boolean-to-UInt carriers to the concrete witness, but the whole
      * assignment's LSB truncation/zero extension preserves the exact result
      * for every positive legal target width.
      */
    private def isProvenModularUIntUpdate(
        assignment: DataAssignmentStatement,
        target: BitVector,
        targetWidth: WidthExpr,
        sourceWidth: WidthExpr
    ): Boolean =
      target match {
        case uint: UInt
            if targetWidth.isSymbolic &&
              sourceWidth.default == targetWidth.default &&
              (assignment.target eq uint) &&
              (assignment.finalTarget eq uint) =>
          val active = new IdentityHashMap[Expression, java.lang.Boolean]()

          def combine(
              left: Option[ModularUIntFacts],
              right: Option[ModularUIntFacts]
          ): Option[ModularUIntFacts] =
            for {
              leftFacts <- left
              rightFacts <- right
            } yield ModularUIntFacts(
              leftFacts.targetReferences + rightFacts.targetReferences,
              leftFacts.booleanValues + rightFacts.booleanValues
            )

          def visit(expression: Expression): Option[ModularUIntFacts] = {
            if (expression == null || active.containsKey(expression)) return None
            if (expression eq uint) return Some(ModularUIntFacts(1, 0))
            active.put(expression, java.lang.Boolean.TRUE)
            val result = expression match {
              case operator: Operator.BitVector.Add if operator.getTypeObject == TypeUInt =>
                combine(visit(operator.left), visit(operator.right))
              case operator: Operator.BitVector.Sub if operator.getTypeObject == TypeUInt =>
                combine(visit(operator.left), visit(operator.right))
              case cast: CastBitsToUInt => visit(cast.input)
              case cast: CastUIntToBits => visit(cast.input)
              case _: CastBoolToBits    => Some(ModularUIntFacts(0, 1))
              case resize: Resize
                  if resize.getTypeObject == TypeUInt ||
                    resize.getTypeObject == TypeBits =>
                visit(resize.input).filter(_.targetReferences == 0)
              case value: BaseType
                  if (value.getTypeObject == TypeUInt ||
                    value.getTypeObject == TypeBits) &&
                    value.isTypeNode && value.isComb &&
                    value.isDirectionLess &&
                    Statement.isSomethingToFullStatement(value) =>
                value.head match {
                  case driver: DataAssignmentStatement
                      if (driver.target eq value) &&
                        (driver.finalTarget eq value) &&
                        widthInference.ofBase(value) ==
                        widthInference.ofExpression(driver.source) =>
                    visit(driver.source)
                  case _ => None
                }
              case _ => None
            }
            active.remove(expression)
            result
          }

          visit(assignment.source).exists { facts =>
            facts.targetReferences == 1 && facts.booleanValues >= 1
          }
        case _ => false
      }

    private def isUnfixedLiteral(expression: Expression): Boolean =
      expression match {
        case literal: BitVectorLiteral => !literal.hasSpecifiedBitCount
        case resize: Resize            => isUnfixedLiteral(resize.input)
        case cast: CastBitVectorToBitVector =>
          isUnfixedLiteral(cast.input)
        case _ => false
      }

    private def isHierarchyBoundary(
        assignment: DataAssignmentStatement
    ): Boolean =
      referencesDirectChild(assignment.target) ||
        referencesDirectChild(assignment.source)

    private def referencesDirectChild(expression: Expression): Boolean = {
      var found = false
      def visit(current: Expression): Unit = {
        if (!found) {
          current match {
            case baseType: BaseType if baseType.component != null && baseType.component.parent == component =>
              found = true
            case other => other.foreachExpression(visit)
          }
        }
      }
      visit(expression)
      found
    }

    private def validateProcesses(): Unit = {
      val registers = declarations.distinct.filter(_.isReg).toVector
      registers.foreach { register =>
        val clockDomain = register.clockDomain
        if (clockDomain == null || clockDomain.clock == null) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-CLOCK-DOMAIN-MISSING",
            s"register '${register.getName()}' has no complete ClockDomain"
          )
        }
        if (
          clockDomain.reset != null &&
          clockDomain.config.resetKind != SYNC &&
          clockDomain.config.resetKind != ASYNC
        ) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-RESET-KIND-UNSUPPORTED",
            s"register '${register.getName()}' uses an unsupported reset kind"
          )
        }
      }
      // Driver ownership, combinational completeness, latch detection and
      // clock/reset legality have already run in the shared inherited Spinal
      // phase plan. Keeping ordinary statements in the native AST preserves
      // those checks while the normal emitter owns process syntax.
    }

    private final class WidthInference {
      private val baseCache = mutable.HashMap.empty[BaseType, WidthExpr]
      private val expressionCache = mutable.HashMap.empty[Expression, WidthExpr]
      private val activeBases = mutable.HashSet.empty[BaseType]
      private val retainedOrigins =
        new IdentityHashMap[WidthExpr, ElaborationIntegerExpression]()

      private def retained(
          expression: ElaborationIntegerExpression
      ): WidthExpr = {
        val value = WidthRetained(
          expression.verilog,
          expression.default,
          expression.minimum,
          expression.maximum,
          expression.parameters.distinct.sortBy(_.name),
          validatedParameterRoots(
            expression.parameterRoots,
            s"retained width '${expression.verilog}'",
            expression.sourceLocation
          ),
          expression.exactDomain,
          projectionSignatureOf(expression)
        )
        retainedOrigins.put(value, expression)
        value
      }

      /** Exact bounded evaluation; unsupported or unproven nodes return None. */
      def evaluate(
          expression: WidthExpr,
          root: ParameterizedStructure.StructuralPredicateRoot,
          value: BigInt
      ): Option[BigInt] = expression match {
        case WidthLiteral(literal) => Some(literal)
        case retained: WidthRetained =>
          Option(retainedOrigins.get(retained)).flatMap(origin =>
            root.elaborationRoot
              .flatMap(elaborationRoot => ElabInt.evaluateExact(origin, elaborationRoot, value))
              .orElse(
                ExternalNativeIntShadowRegistry.evaluateDefinitionExpression(
                  origin,
                  root,
                  value
                )
              )
          )
        case WidthBinary(operator, left, right, _, _, _, _, _) =>
          for {
            l <- evaluate(left, root, value)
            r <- evaluate(right, root, value)
            result <- operator match {
              case "+" => Some(l + r)
              case "-" => Some(l - r)
              case "*" => Some(l * r)
              case _   => None
            }
          } yield result
        case _ => None
      }

      /** Certify every exact typed width origin against this declaration's
        * structural owner, then exhaustively evaluate the final inferred width
        * over that owner domain.
        */
      def projectedResultsOf(
          declaration: BitVector,
          expression: WidthExpr
      ): Option[Vector[BigInt]] = {
        val origins = projectedOriginsOf(expression).foldLeft(
          Vector.empty[ElaborationIntegerExpression]
        ) { (known, origin) =>
          if (known.exists(_ eq origin)) known else known :+ origin
        }
        if (origins.isEmpty) return None

        val roots = origins
          .flatMap(_.exactDomain.map(_.root))
          .foldLeft(
            Vector.empty[ElaborationIntegerParameterRoot]
          ) { (known, root) =>
            if (known.exists(_ eq root)) known else known :+ root
          }
        if (roots.size != 1) {
          fail(
            "SPINAL-ELAB-DOMAIN-PROJECTION-ROOT-IDENTITY-MISMATCH",
            s"signal '${declaration.getName()}' inferred width combines ${roots.size} independent exact projection roots",
            ParameterizedWidth.sourceLocationOf(declaration)
          )
        }
        val source = ParameterizedWidth.sourceLocationOf(declaration)
        val evaluations = origins.map { origin =>
          ParameterizedStructure
            .projectedDeclarationEvaluationOf(
              component,
              declaration,
              origin,
              s"signal '${declaration.getName()}' retained width",
              source.orElse(origin.sourceLocation)
            )
            .getOrElse {
              fail(
                "SPINAL-ELAB-DOMAIN-PROJECTION-EVIDENCE-MISSING",
                s"signal '${declaration.getName()}' retained typed width lost its exact evaluation evidence",
                source.orElse(origin.sourceLocation)
              )
            }
        }
        val rootValues = evaluations.head.rootValues
        if (evaluations.exists(_.rootValues != rootValues)) {
          fail(
            "SPINAL-ELAB-DOMAIN-PROJECTION-OWNER-DOMAIN-MISMATCH",
            s"signal '${declaration.getName()}' retained width origins resolve to different structural owner domains",
            source
          )
        }
        val root = roots.head
        val results = rootValues.toVector.sorted.map { rootValue =>
          evaluateProjected(expression, root, rootValue).getOrElse {
            fail(
              "SPINAL-ELAB-DOMAIN-PROJECTION-WIDTH-EVALUATION-UNPROVEN",
              s"signal '${declaration.getName()}' inferred width '${expression.render}' cannot be evaluated exactly at ${root.name}=$rootValue",
              source
            )
          }
        }
        results.find(value => value < expression.minimum || value > expression.maximum).foreach { value =>
          fail(
            "SPINAL-ELAB-DOMAIN-PROJECTION-BOUNDS-MISMATCH",
            s"signal '${declaration.getName()}' inferred width '${expression.render}' evaluates to $value outside retained bounds [${expression.minimum}, ${expression.maximum}]",
            source
          )
        }
        Some(results)
      }

      private def projectedOriginsOf(
          expression: WidthExpr
      ): Vector[ElaborationIntegerExpression] = expression match {
        case retained: WidthRetained =>
          Option(retainedOrigins.get(retained)).toVector.filter(
            _.exactDomain.nonEmpty
          )
        case value: WidthBinary =>
          projectedOriginsOf(value.left) ++ projectedOriginsOf(value.right)
        case value: WidthSelect =>
          projectedOriginsOf(value.whenTrue) ++
            projectedOriginsOf(value.whenFalse)
        case _ => Vector.empty
      }

      private def evaluateProjected(
          expression: WidthExpr,
          root: ElaborationIntegerParameterRoot,
          rootValue: BigInt
      ): Option[BigInt] = expression match {
        case WidthLiteral(value) => Some(value)
        case retained: WidthRetained =>
          Option(retainedOrigins.get(retained)).flatMap(origin => ElabInt.evaluateExact(origin, root, rootValue))
        case value: WidthBinary =>
          for {
            left <- evaluateProjected(value.left, root, rootValue)
            right <- evaluateProjected(value.right, root, rootValue)
            result <- value.operator match {
              case "+" => Some(left + right)
              case "-" => Some(left - right)
              case "*" => Some(left * right)
              case _   => None
            }
          } yield result
        case value: WidthSelect =>
          for {
            whenTrue <- evaluateProjected(value.whenTrue, root, rootValue)
            whenFalse <- evaluateProjected(value.whenFalse, root, rootValue)
          } yield value.selection.select(whenTrue, whenFalse)
        case _ => None
      }

      def ofBase(baseType: BaseType): WidthExpr = {
        baseCache.get(baseType) match {
          case Some(value)                            => value
          case None if activeBases.contains(baseType) => WidthLiteral(baseType.getBitsWidth)
          case None =>
            activeBases += baseType
            val result =
              ExternalParameterizedAutoResize
                .sourceDriverOfResizeSource(component, baseType)
                .map { driver =>
                  val sourceWidth = ofExpression(driver.source)
                  if (sourceWidth.default != BigInt(baseType.getBitsWidth)) {
                    fail(
                      "SPINAL-PARAMETERIZED-VERILOG-AUTO-RESIZE-SOURCE-WITNESS-MISMATCH",
                      s"captured native auto-resize source has ${baseType.getBitsWidth} bits but its exact driver width '${sourceWidth.render}' has default ${sourceWidth.default}"
                    )
                  }
                  sourceWidth
                }
                .getOrElse {
                  ParameterizedWidth.expressionOf(baseType) match {
                    case Some(expression) => retained(expression)
                    case None =>
                      baseType match {
                        case _: Bool              => WidthLiteral(1)
                        case bitVector: BitVector => inferUntaggedBitVector(bitVector)
                        case _                    => WidthLiteral(baseType.getBitsWidth)
                      }
                  }
                }
            activeBases -= baseType
            baseCache(baseType) = result
            result
        }
      }

      private def inferUntaggedBitVector(bitVector: BitVector): WidthExpr = {
        val fullAssignments = ArrayBuffer.empty[DataAssignmentStatement]
        bitVector.foreachStatements {
          case assignment: DataAssignmentStatement
              if assignment.target == bitVector &&
                assignment.finalTarget == bitVector &&
                !isHierarchyBoundary(assignment) =>
            fullAssignments += assignment
          case _ =>
        }
        val provenAutoResizeBoundary = bitVector match {
          case uint: UInt if fullAssignments.size == 1 =>
            ExternalParameterizedAutoResize.proves(
              component,
              fullAssignments.head,
              uint
            )
          case _ => false
        }
        if (provenAutoResizeBoundary) WidthLiteral(bitVector.getBitsWidth)
        else {
          val sourceWidths = fullAssignments.map(assignment => ofExpression(assignment.source))
          val symbolicWidths = sourceWidths.filter(_.isSymbolic)
          if (symbolicWidths.isEmpty) WidthLiteral(bitVector.getBitsWidth)
          else symbolicWidths.reduce(widthMax)
        }
      }

      def ofExpression(expression: Expression): WidthExpr = {
        expressionCache.getOrElseUpdate(expression, inferExpression(expression))
      }

      /** Spinal input normalization inserts concrete-witness Resize nodes around
        * operands.  Those nodes are not user-visible resizes and must retain the
        * operand's symbolic width when their concrete size equals its witness.
        * A top-level Resize expression still goes through inferResize and remains
        * subject to the full-domain narrowing rule.
        */
      private def operandWidth(expression: Expression): WidthExpr = expression match {
        case resize: Resize =>
          val source = ofExpression(resize.input)
          if (source.isSymbolic && source.default == BigInt(resize.size)) source
          else ofExpression(resize)
        case other => ofExpression(other)
      }

      private def inferExpression(expression: Expression): WidthExpr = expression match {
        case baseType: BaseType             => ofBase(baseType)
        case resize: Resize                 => inferResize(resize)
        case cast: CastBitVectorToBitVector => operandWidth(cast.input)
        case _: CastBoolToBits              => WidthLiteral(1)
        case operator: Operator.Bits.Cat =>
          widthAdd(operandWidth(operator.left), operandWidth(operator.right))
        case operator: Operator.BitVector.Add =>
          widthMax(operandWidth(operator.left), operandWidth(operator.right))
        case operator: Operator.BitVector.Sub =>
          widthMax(operandWidth(operator.left), operandWidth(operator.right))
        case operator: Operator.BitVector.And =>
          widthMax(operandWidth(operator.left), operandWidth(operator.right))
        case operator: Operator.BitVector.Or =>
          widthMax(operandWidth(operator.left), operandWidth(operator.right))
        case operator: Operator.BitVector.Xor =>
          widthMax(operandWidth(operator.left), operandWidth(operator.right))
        case operator: Operator.BitVector.Mul =>
          widthAdd(operandWidth(operator.left), operandWidth(operator.right))
        case operator: Operator.BitVector.Div => operandWidth(operator.left)
        case operator: Operator.BitVector.Mod =>
          widthMin(operandWidth(operator.left), operandWidth(operator.right))
        case operator: Operator.BitVector.Repeat =>
          widthMultiply(operandWidth(operator.source), WidthLiteral(operator.count))
        case operator: Operator.BitVector.ShiftLeftByInt =>
          widthAdd(operandWidth(operator.source), WidthLiteral(operator.shift))
        case operator: Operator.BitVector.ShiftRightByInt =>
          widthMax(
            widthSubtract(operandWidth(operator.source), WidthLiteral(operator.shift)),
            WidthLiteral(0)
          )
        case operator: Operator.BitVector.ShiftLeftByIntFixedWidth =>
          operandWidth(operator.source)
        case operator: Operator.BitVector.ShiftRightByIntFixedWidth =>
          operandWidth(operator.source)
        case operator: Operator.BitVector.ShiftRightByUInt =>
          operandWidth(operator.left)
        case operator: Operator.BitVector.ShiftLeftByUIntFixedWidth =>
          operandWidth(operator.left)
        case operator: Operator.Bits.Not   => operandWidth(operator.source)
        case operator: Operator.UInt.Not   => operandWidth(operator.source)
        case operator: Operator.SInt.Not   => operandWidth(operator.source)
        case operator: Operator.SInt.Minus => operandWidth(operator.source)
        case mux: MultiplexerWidthable =>
          mux.inputs.map(operandWidth).reduce(widthMax)
        case mux: BinaryMultiplexerWidthable =>
          widthMax(operandWidth(mux.whenTrue), operandWidth(mux.whenFalse))
        case access: BitVectorRangedAccessFixed    => inferFixedRange(access)
        case access: BitVectorRangedAccessFloating => inferFloatingRange(access)
        case access: BitVectorBitAccessFixed       => inferFixedBit(access)
        case _: BitVectorBitAccessFloating         => WidthLiteral(1)
        case literal: BitVectorLiteral             => WidthLiteral(literal.getWidth)
        case _: BoolLiteral                        => WidthLiteral(1)
        case port: MemReadSync =>
          ExternalParameterizedMemoryRegistry.metadataOf(port.mem) match {
            case Some(metadata) => retained(metadata.elementWidth)
            case None           => WidthLiteral(port.getWidth)
          }
        case widthProvider: Expression with WidthProvider =>
          val childWidths = ArrayBuffer.empty[WidthExpr]
          widthProvider.foreachExpression(child => childWidths += ofExpression(child))
          if (childWidths.exists(_.isSymbolic)) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-WIDTH-EXPRESSION-UNSUPPORTED",
              s"ordinary expression '${widthProvider.opName}' has a symbolic operand, but Increment 31 has no reviewed result-width rule for ${widthProvider.getClass.getSimpleName}"
            )
          }
          WidthLiteral(widthProvider.getWidth)
        case other =>
          val childWidths = ArrayBuffer.empty[WidthExpr]
          other.foreachExpression(child => childWidths += ofExpression(child))
          if (childWidths.exists(_.isSymbolic)) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-WIDTH-EXPRESSION-UNSUPPORTED",
              s"ordinary expression '${other.opName}' has a symbolic operand but no reviewed packed-width rule"
            )
          }
          WidthLiteral(1)
      }

      /** A native BitVector.resize call materializes one weak-clone target whose
        * direct driver is the exact Resize expression. The compiler bridge
        * retains the symbolic target width on that native target object. Recover
        * it by statement and object identity; never infer it from a matching
        * witness width or emitted name.
        */
      private def retainedResizeTarget(resize: Resize): Option[BitVector] = {
        var found: BitVector = null
        assignments.foreach { assignment =>
          if (
            (assignment.source eq resize) &&
            (assignment.target eq assignment.finalTarget)
          ) {
            assignment.target match {
              case target: BitVector
                  if (target.component eq component) &&
                    target.isTypeNode && target.isComb &&
                    target.isDirectionLess &&
                    target.getBitsWidth == resize.size &&
                    ParameterizedWidth.expressionOf(target).exists { expression =>
                      expression.parameters.nonEmpty &&
                      expression.default == BigInt(resize.size)
                    } =>
                if (found == null) found = target
                else if (!(found eq target)) {
                  fail(
                    "SPINAL-PARAMETERIZED-VERILOG-RESIZE-TARGET-AMBIGUOUS",
                    s"one native Resize expression drives multiple retained symbolic targets in component '${component.definitionName}'"
                  )
                }
              case _ =>
            }
          }
        }
        Option(found)
      }

      private def inferResize(resize: Resize): WidthExpr = {
        val retainedExpression =
          retainedResizeExpression(resize).map { expression =>
            if (expression.default != BigInt(resize.size)) {
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-RESIZE-WITNESS-MISMATCH",
                s"native Resize target ${resize.size} does not match retained symbolic default ${expression.default}",
                expression.sourceLocation
              )
            }
            retained(expression)
          }
        val retainedTarget =
          ExternalParameterizedAutoResize
            .syntheticBooleanResizeTarget(component, resize)
            .map(target => target: BitVector)
            .orElse(retainedResizeTarget(resize))
        val capturedAutoResizeTarget =
          ExternalParameterizedAutoResize
            .materializedResizeBoundary(component, resize)
            .flatMap { case (assignment, target) =>
              val targetWidth = ofBase(target)
              val inputWidth = ofExpression(resize.input)
              if (
                isProvenCapturedDomainWidthEquivalence(
                  assignment,
                  targetWidth,
                  inputWidth
                )
              ) Some(targetWidth)
              else None
            }
        retainedExpression
          .orElse(capturedAutoResizeTarget)
          .orElse(retainedTarget.map(target => ofBase(target)))
          .getOrElse {
            val source = ofExpression(resize.input)
            val size = BigInt(resize.size)
            if (!source.isSymbolic) WidthLiteral(size)
            else if (size <= source.minimum) WidthLiteral(size)
            else if (
              size >= source.maximum &&
              (resize.getTypeObject == TypeBits || resize.getTypeObject == TypeUInt)
            ) {
              // The untouched Verilog emitter gives every Resize node its
              // fixed target width: nested resizes are wrapped in a target-sized
              // temporary, while a top-level resize is consumed by its
              // target-sized assignment. For Bits/UInt, a target no smaller
              // than the complete symbolic source domain is therefore an
              // invariant zero-extension/equality operation. The witness-sized
              // leading-zero fragment remains semantically exact because the
              // fixed target context supplies any remaining zero extension or
              // discards only leading zeros. Signed widening is deliberately
              // excluded because a witness-sized unsigned concatenation cannot
              // prove sign extension for a smaller symbolic source.
              WidthLiteral(size)
            } else {
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-RESIZE-DOMAIN-UNSUPPORTED",
                s"resize from symbolic width '${source.render}' to ${resize.size} is neither a domain-invariant narrowing nor an unsigned domain-invariant widening; domain-crossing and signed widening resize lowering are deferred"
              )
            }
          }
      }

      private def inferFixedRange(access: BitVectorRangedAccessFixed): WidthExpr = {
        val source = ofExpression(access.source)
        if (source.isSymbolic && BigInt(access.hi) >= source.minimum) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-SLICE-DOMAIN-UNSUPPORTED",
            s"fixed slice ${access.hi} downto ${access.lo} is not valid for the complete symbolic source-width domain '${source.render}' in [${source.minimum}, ${source.maximum}]"
          )
        }
        WidthLiteral(access.getWidth)
      }

      private def inferFloatingRange(access: BitVectorRangedAccessFloating): WidthExpr = {
        val source = ofExpression(access.source)
        if (source.isSymbolic) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-SLICE-DOMAIN-UNSUPPORTED",
            "floating slices of a symbolic-width source are deferred until symbolic index constraints are integrated"
          )
        }
        WidthLiteral(access.getWidth)
      }

      private def inferFixedBit(access: BitVectorBitAccessFixed): WidthExpr = {
        val source = ofExpression(access.source)
        if (source.isSymbolic && BigInt(access.bitId) >= source.minimum) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-SLICE-DOMAIN-UNSUPPORTED",
            s"fixed bit ${access.bitId} is not valid for the complete symbolic source-width domain '${source.render}' in [${source.minimum}, ${source.maximum}]"
          )
        }
        WidthLiteral(1)
      }
    }
  }

  private sealed trait WidthExpr {
    def default: BigInt
    def minimum: BigInt
    def maximum: BigInt
    def parameters: Vector[ElaborationIntegerParameter]
    def parameterRoots: Vector[ElaborationIntegerParameterRoot]
    def precedence: Int
    def render: String

    final def isSymbolic: Boolean = parameters.nonEmpty
    final def range: String =
      if (precedence >= 100) s"[$render-1:0]" else s"[($render)-1:0]"
  }

  private final case class WidthLiteral(value: BigInt) extends WidthExpr {
    override val default: BigInt = value
    override val minimum: BigInt = value
    override val maximum: BigInt = value
    override val parameters: Vector[ElaborationIntegerParameter] = Vector.empty
    override val parameterRoots: Vector[ElaborationIntegerParameterRoot] = Vector.empty
    override val precedence: Int = 100
    override val render: String = value.toString
  }

  private final case class WidthRetained(
      render: String,
      default: BigInt,
      minimum: BigInt,
      maximum: BigInt,
      parameters: Vector[ElaborationIntegerParameter],
      parameterRoots: Vector[ElaborationIntegerParameterRoot],
      exactDomain: Option[ElaborationExactDomain[BigInt]],
      projection: Option[WidthProjectionSignature]
  ) extends WidthExpr {
    override val precedence: Int = 100
  }

  private final case class WidthProjectionSignature(
      root: ElaborationIntegerParameterRoot,
      admitted: Set[BigInt],
      representative: BigInt
  )

  private def projectionSignatureOf(
      expression: ElaborationIntegerExpression
  ): Option[WidthProjectionSignature] =
    expression.projectionProvenance.map { value =>
      WidthProjectionSignature(value.root, value.admitted, value.representative)
    }

  private final case class WidthBinary(
      operator: String,
      left: WidthExpr,
      right: WidthExpr,
      default: BigInt,
      minimum: BigInt,
      maximum: BigInt,
      precedence: Int,
      commutative: Boolean
  ) extends WidthExpr {
    override val parameters: Vector[ElaborationIntegerParameter] =
      (left.parameters ++ right.parameters).distinct.sortBy(_.name)
    override val parameterRoots: Vector[ElaborationIntegerParameterRoot] =
      validatedParameterRoots(
        left.parameterRoots ++ right.parameterRoots,
        s"derived native width '${left.render} $operator ${right.render}'",
        None
      )

    private def operand(value: WidthExpr, rightOperand: Boolean): String = {
      val needsParentheses =
        value.precedence < precedence ||
          (rightOperand && value.precedence == precedence && !commutative)
      if (needsParentheses) s"(${value.render})" else value.render
    }

    override val render: String =
      s"${operand(left, rightOperand = false)} $operator ${operand(right, rightOperand = true)}"
  }

  private sealed trait WidthSelection {
    def comparison: String
    def select(left: BigInt, right: BigInt): BigInt
  }

  private case object WidthMaximum extends WidthSelection {
    override val comparison: String = ">"
    override def select(left: BigInt, right: BigInt): BigInt = left.max(right)
  }

  private case object WidthMinimum extends WidthSelection {
    override val comparison: String = "<"
    override def select(left: BigInt, right: BigInt): BigInt = left.min(right)
  }

  private final case class WidthSelect(
      selection: WidthSelection,
      whenTrue: WidthExpr,
      whenFalse: WidthExpr,
      default: BigInt,
      minimum: BigInt,
      maximum: BigInt
  ) extends WidthExpr {
    private val condition =
      s"${whenTrue.render} ${selection.comparison} ${whenFalse.render}"
    override val parameters: Vector[ElaborationIntegerParameter] =
      (whenTrue.parameters ++ whenFalse.parameters).distinct.sortBy(_.name)
    override val parameterRoots: Vector[ElaborationIntegerParameterRoot] =
      validatedParameterRoots(
        whenTrue.parameterRoots ++ whenFalse.parameterRoots,
        s"selected native width '$condition'",
        None
      )
    override val precedence: Int = 10
    override val render: String =
      s"$condition ? ${whenTrue.render} : ${whenFalse.render}"
  }

  /** Preserve declaration provenance with JVM identity semantics. */
  private def distinctParameterRoots(
      roots: Vector[ElaborationIntegerParameterRoot]
  ): Vector[ElaborationIntegerParameterRoot] =
    roots
      .foldLeft(Vector.empty[ElaborationIntegerParameterRoot]) {
        case (known, root) if known.exists(_ eq root) => known
        case (known, root)                            => known :+ root
      }
      .sortBy(_.name)

  private def validatedParameterRoots(
      roots: Vector[ElaborationIntegerParameterRoot],
      role: String,
      sourceLocation: Option[String]
  ): Vector[ElaborationIntegerParameterRoot] = {
    val distinct = distinctParameterRoots(roots)
    distinct
      .groupBy(_.name)
      .collectFirst {
        case (name, declarations) if declarations.size > 1 => name
      }
      .foreach { name =>
        fail(
          "SPINAL-ELAB-INT-INDEPENDENT-ROOTS-UNSUPPORTED",
          s"$role combines independently sourced declarations for parameter '$name'",
          distinct.find(_.name == name).flatMap(_.sourceLocation).orElse(sourceLocation)
        )
      }
    distinct
  }

  private def widthAdd(left: WidthExpr, right: WidthExpr): WidthExpr =
    (left, right) match {
      case (WidthLiteral(value), other) if value == 0 => other
      case (other, WidthLiteral(value)) if value == 0 => other
      case (WidthLiteral(x), WidthLiteral(y))         => WidthLiteral(x + y)
      case _ =>
        canonicalBinary(
          "+",
          left,
          right,
          left.default + right.default,
          left.minimum + right.minimum,
          left.maximum + right.maximum,
          precedence = 60,
          commutative = true
        )
    }

  private def widthSubtract(left: WidthExpr, right: WidthExpr): WidthExpr =
    (left, right) match {
      case (other, WidthLiteral(value)) if value == 0 => other
      case (WidthLiteral(x), WidthLiteral(y))         => WidthLiteral(x - y)
      case _ =>
        WidthBinary(
          "-",
          left,
          right,
          left.default - right.default,
          left.minimum - right.maximum,
          left.maximum - right.minimum,
          precedence = 60,
          commutative = false
        )
    }

  private def widthMultiply(left: WidthExpr, right: WidthExpr): WidthExpr =
    (left, right) match {
      case (WidthLiteral(value), _) if value == 0     => WidthLiteral(0)
      case (_, WidthLiteral(value)) if value == 0     => WidthLiteral(0)
      case (WidthLiteral(value), other) if value == 1 => other
      case (other, WidthLiteral(value)) if value == 1 => other
      case (WidthLiteral(x), WidthLiteral(y))         => WidthLiteral(x * y)
      case _ =>
        val products = Vector(
          left.minimum * right.minimum,
          left.minimum * right.maximum,
          left.maximum * right.minimum,
          left.maximum * right.maximum
        )
        canonicalBinary(
          "*",
          left,
          right,
          left.default * right.default,
          products.min,
          products.max,
          precedence = 70,
          commutative = true
        )
    }

  private def widthMax(left: WidthExpr, right: WidthExpr): WidthExpr = {
    if (equivalentWidthExpression(left, right)) left
    else {
      (left, right) match {
        case (WidthLiteral(x), WidthLiteral(y)) => WidthLiteral(x.max(y))
        case _ if left.maximum <= right.minimum => right
        case _ if right.maximum <= left.minimum => left
        case _ =>
          WidthSelect(
            WidthMaximum,
            left,
            right,
            left.default.max(right.default),
            left.minimum.max(right.minimum),
            left.maximum.max(right.maximum)
          )
      }
    }
  }

  private def widthMin(left: WidthExpr, right: WidthExpr): WidthExpr = {
    if (equivalentWidthExpression(left, right)) left
    else {
      (left, right) match {
        case (WidthLiteral(x), WidthLiteral(y)) => WidthLiteral(x.min(y))
        case _ if left.maximum <= right.minimum => left
        case _ if right.maximum <= left.minimum => right
        case _ =>
          WidthSelect(
            WidthMinimum,
            left,
            right,
            left.default.min(right.default),
            left.minimum.min(right.minimum),
            left.maximum.min(right.maximum)
          )
      }
    }
  }

  private def equivalentWidthExpression(
      left: WidthExpr,
      right: WidthExpr
  ): Boolean = {
    if (left == right) return true

    (left, right) match {
      case (l: WidthRetained, r: WidthRetained) =>
        l.projection == r.projection &&
        exactWidthDomainEquivalent(l.exactDomain, r.exactDomain)

      case (
            WidthBinary(lOperator, lLeft, lRight, _, _, _, lPrecedence, lCommutative),
            WidthBinary(rOperator, rLeft, rRight, _, _, _, rPrecedence, rCommutative)
          )
          if lOperator == rOperator && lPrecedence == rPrecedence &&
            lCommutative == rCommutative =>
        val direct =
          equivalentWidthExpression(lLeft, rLeft) &&
            equivalentWidthExpression(lRight, rRight)
        direct ||
        (lCommutative &&
          equivalentWidthExpression(lLeft, rRight) &&
          equivalentWidthExpression(lRight, rLeft))

      // A native mux may retain a conditional width even when both alternatives
      // carry the same exact typed width function. Such a select is an identity
      // function; its rendered condition is deliberately not inspected.
      case (select: WidthSelect, other) if equivalentWidthExpression(select.whenTrue, select.whenFalse) =>
        equivalentWidthExpression(select.whenTrue, other)
      case (other, select: WidthSelect) if equivalentWidthExpression(select.whenTrue, select.whenFalse) =>
        equivalentWidthExpression(other, select.whenTrue)

      case _ => false
    }
  }

  private def exactWidthDomainEquivalent(
      left: Option[ElaborationExactDomain[BigInt]],
      right: Option[ElaborationExactDomain[BigInt]]
  ): Boolean =
    (left, right) match {
      case (Some(l), Some(r)) =>
        (l.root eq r.root) &&
        l.parameter == r.parameter &&
        l.universe == r.universe &&
        l.evaluations == r.evaluations
      case _ => false
    }

  private def canonicalBinary(
      operator: String,
      left: WidthExpr,
      right: WidthExpr,
      default: BigInt,
      minimum: BigInt,
      maximum: BigInt,
      precedence: Int,
      commutative: Boolean
  ): WidthExpr = {
    def orderKey(value: WidthExpr): (Int, String) =
      (if (value.isSymbolic) 0 else 1, value.render)
    val leftKey = orderKey(left)
    val rightKey = orderKey(right)
    val leftComesAfter =
      leftKey._1 > rightKey._1 ||
        (leftKey._1 == rightKey._1 && leftKey._2.compareTo(rightKey._2) > 0)
    val ordered =
      if (commutative && leftComesAfter) (right, left)
      else (left, right)
    WidthBinary(
      operator,
      ordered._1,
      ordered._2,
      default,
      minimum,
      maximum,
      precedence,
      commutative
    )
  }

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String] = None
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
