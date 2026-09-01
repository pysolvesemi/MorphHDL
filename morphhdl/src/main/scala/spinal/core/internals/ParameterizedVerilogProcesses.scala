package spinal.core.internals

import java.util.IdentityHashMap
import java.util.regex.{Matcher, Pattern}

import scala.collection.mutable

import spinal.core._

/** MorphHDL-owned Increment 34 replacement of one witnessed native assignment with a
  * parameter-bounded Verilog-2001 procedural `for`.
  *
  * Normal SpinalHDL elaboration and process grouping remain authoritative. The
  * captured assignment carries a private marker through native emission; this
  * pass locates that exact statement, substitutes its retained packed slices,
  * and wraps it in the reviewed loop. Structural construction is handled
  * independently by [[ParameterizedVerilogStructural]].
  */
private[internals] object ParameterizedVerilogProcesses {
  private sealed trait SliceSide
  private case object TargetSlice extends SliceSide
  private case object SourceSlice extends SliceSide

  private final case class ValidatedSlice(
      retained: ParameterizedStructure.StructuralSlice,
      side: SliceSide
  )

  private final case class ValidatedLoop(
      retained: ParameterizedProceduralFor,
      slices: Vector[ValidatedSlice]
  )

  private final case class PlannedLoop(
      lineIndex: Int,
      processStart: Int,
      lines: Vector[String]
  )

  def hasLoops(component: Component): Boolean =
    ParameterizedProcess.loopsOf(component).nonEmpty

  def rewrite(
      component: Component,
      verilog: String,
      pc: PhaseContext
  ): String = {
    val loops = ParameterizedProcess.loopsOf(component)
    if (loops.isEmpty) return verilog
    if (pc.config.isSystemVerilog) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-MODE-UNSUPPORTED",
        "parameterized procedural-loop lowering targets Verilog-2001, not SystemVerilog"
      )
    }

    validateNames(component, loops, pc)
    val liveStatementCounts =
      new IdentityHashMap[Statement, java.lang.Integer]()
    val liveAssignments = Vector.newBuilder[DataAssignmentStatement]
    component.dslBody.walkStatements { statement =>
      val count = Option(liveStatementCounts.get(statement)).fold(0)(_.intValue)
      liveStatementCounts.put(statement, java.lang.Integer.valueOf(count + 1))
      statement match {
        case assignment: DataAssignmentStatement => liveAssignments += assignment
        case _                                   =>
      }
    }
    val finalLiveAssignments = liveAssignments.result()
    val claimedAssignments =
      new IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]()
    val validatedLoops = loops.map(loop =>
      validateLoop(
        component,
        loop,
        liveStatementCounts,
        finalLiveAssignments,
        claimedAssignments
      )
    )
    val normalized = verilog.replace("\r\n", "\n").replace('\r', '\n')
    val originalLines = normalized.split("\n", -1).toVector
    val plans = validatedLoops.map(loop => planLoop(loop, originalLines))
    plans
      .groupBy(_.lineIndex)
      .collectFirst {
        case (line, values) if values.size != 1 => line
      }
      .foreach { line =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-PROCESS-MARKER-OVERLAP",
          s"multiple procedural loops resolved to emitted line ${line + 1}"
        )
      }

    val replacementByLine = plans.map(plan => plan.lineIndex -> plan.lines).toMap
    val declarationIndex = plans.map(_.processStart).min
    val declarations = loops.map(loop => s"  integer ${loop.indexName};").toVector

    val rewritten = Vector.newBuilder[String]
    originalLines.zipWithIndex.foreach { case (line, index) =>
      if (index == declarationIndex) {
        declarations.foreach(rewritten += _)
        rewritten += ""
      }
      replacementByLine.get(index) match {
        case Some(lines) => lines.foreach(rewritten += _)
        case None        => rewritten += line
      }
    }
    val result = rewritten.result().mkString("\n")
    if (loops.exists(loop => markerOccurrences(result, loop.marker) != 0)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-MARKER-LEAK",
        "one or more internal procedural-loop markers remained in emitted Verilog"
      )
    }
    result
  }

  private def planLoop(
      validated: ValidatedLoop,
      lines: Vector[String]
  ): PlannedLoop = {
    val loop = validated.retained
    val markerLines = lines.zipWithIndex.collect {
      case (line, index) if markerOccurrences(line, loop.marker) != 0 => index
    }
    if (markerLines.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-MARKER-NOT-FOUND",
        s"native Verilog contains ${markerLines.size} assignments for retained procedural loop '${loop.label}', expected exactly one",
        loop.sourceLocation
      )
    }
    val lineIndex = markerLines.head
    val processStart = (lineIndex to 0 by -1)
      .find { index =>
        lines(index).trim.startsWith("always @(")
      }
      .getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-PROCESS-CONTEXT-NOT-FOUND",
          s"retained assignment for procedural loop '${loop.label}' was not emitted inside an always block",
          loop.sourceLocation
        )
      }

    val original = removeMarker(lines(lineIndex), loop.marker)
    val rewrittenAssignment = rewriteSlices(original, validated)
    val indentation = original.takeWhile(_.isWhitespace)
    val statement = rewrittenAssignment.trim
    PlannedLoop(
      lineIndex,
      processStart,
      Vector(
        s"${indentation}for (${loop.indexName} = 0; ${loop.indexName} < ${loop.count.verilog}; ${loop.indexName} = ${loop.indexName} + 1) begin : ${loop.label}",
        s"$indentation  $statement",
        s"${indentation}end"
      )
    )
  }

  private def removeMarker(line: String, marker: String): String = {
    val pattern = markerPattern(marker)
    val matcher = pattern.matcher(line)
    if (!matcher.find() || matcher.find()) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-MARKER-CARDINALITY-MISMATCH",
        s"emitted procedural assignment contains ${markerOccurrences(line, marker)} exact '$marker' markers; exactly one is required"
      )
    }
    val withoutMarker = pattern.matcher(line).replaceFirst("")
    val withoutEmptyComment =
      withoutMarker.replaceFirst("""\s*//\s*$""", "")
    withoutEmptyComment.replaceFirst("""\s+$""", "")
  }

  private def rewriteSlices(
      line: String,
      validated: ValidatedLoop
  ): String = {
    val loop = validated.retained
    val assignment =
      """^([ \t]*)(.*?)([ \t]*(?:<=|=)[ \t]*)(.*?)(;[ \t]*(?://.*)?)$""".r
    val (indentation, initialTarget, operator, initialSource, suffix) =
      line match {
        case assignment(indent, target, assignmentOperator, source, end) =>
          (indent, target, assignmentOperator, source, end)
        case _ =>
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-PROCESS-ASSIGNMENT-NOT-FOUND",
            s"retained procedural loop '${loop.label}' did not map to one complete native assignment line",
            loop.sourceLocation
          )
      }
    val rewritten = validated.slices.foldLeft(initialTarget -> initialSource) { case ((target, source), planned) =>
      planned.side match {
        case TargetSlice =>
          rewriteSliceOccurrence(target, loop, planned.retained) -> source
        case SourceSlice =>
          target -> rewriteSliceOccurrence(source, loop, planned.retained)
      }
    }
    indentation + rewritten._1 + operator + rewritten._2 + suffix
  }

  private def rewriteSliceOccurrence(
      region: String,
      loop: ParameterizedProceduralFor,
      slice: ParameterizedStructure.StructuralSlice
  ): String = {
    val sourceName = Option(slice.source.getName()).filter(_.nonEmpty).getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-SLICE-NAME-MISSING",
        "one procedural-loop packed-slice source has no stable native name",
        slice.sourceLocation.orElse(loop.sourceLocation)
      )
    }
    val quoted = Pattern.quote(sourceName)
    val high = slice.offset.default + slice.width.default - 1
    val low = slice.offset.default
    val descending =
      ("""(?<![A-Za-z0-9_$])""" + quoted +
        """\s*\[\s*""" + high + """\s*:\s*""" + low + """\s*\]""").r
    val indexed =
      ("""(?<![A-Za-z0-9_$])""" + quoted +
        """\s*\[\s*""" + low + """\s*\+:\s*""" +
        slice.width.default + """\s*\]""").r
    val replacement =
      s"$sourceName[${slice.offset.verilog} +: ${slice.width.verilog}]"
    val occurrences =
      descending.findAllMatchIn(region).size + indexed.findAllMatchIn(region).size
    if (occurrences != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-SLICE-EMITTED-CARDINALITY-MISMATCH",
        s"native assignment for loop '${loop.label}' contains $occurrences witnessed slices '$sourceName[$high:$low]' on its retained assignment side; exactly one identity-authorized occurrence is required",
        slice.sourceLocation.orElse(loop.sourceLocation)
      )
    }
    val descendingRewritten = descending.replaceFirstIn(
      region,
      Matcher.quoteReplacement(replacement)
    )
    if (descendingRewritten != region) descendingRewritten
    else indexed.replaceFirstIn(region, Matcher.quoteReplacement(replacement))
  }

  private def validateLoop(
      component: Component,
      loop: ParameterizedProceduralFor,
      liveStatementCounts: IdentityHashMap[Statement, java.lang.Integer],
      liveAssignments: Vector[DataAssignmentStatement],
      claimedAssignments: IdentityHashMap[
        DataAssignmentStatement,
        java.lang.Boolean
      ]
  ): ValidatedLoop = {
    val source = loop.sourceLocation
    val retained = loop.assignment
    if (retained == null || retained.target == null || retained.source == null) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-ASSIGNMENT-EVIDENCE-STALE",
        s"procedural loop '${loop.label}' retained no complete native assignment identity",
        source
      )
    }
    val retainedCount = identityCount(liveStatementCounts, retained)
    if (retainedCount != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-ASSIGNMENT-EVIDENCE-STALE",
        s"procedural loop '${loop.label}' retained assignment identity is live $retainedCount times; exactly one is required",
        source
      )
    }
    if (
      (retained.finalTarget.component ne component) ||
      claimedAssignments.put(retained, java.lang.Boolean.TRUE) != null
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-ASSIGNMENT-EVIDENCE-CARDINALITY",
        s"procedural loop '${loop.label}' does not own one unique live assignment in the published component",
        source
      )
    }
    val retainedMarkerCount =
      markerOccurrences(Option(retained.locationString).getOrElse(""), loop.marker)
    val markerOwners = liveAssignments.filter(assignment =>
      markerOccurrences(
        Option(assignment.locationString).getOrElse(""),
        loop.marker
      ) != 0
    )
    val liveMarkerCount = markerOwners
      .map(assignment =>
        markerOccurrences(
          Option(assignment.locationString).getOrElse(""),
          loop.marker
        )
      )
      .sum
    if (
      retainedMarkerCount != 1 || liveMarkerCount != 1 ||
      markerOwners.size != 1 || (markerOwners.head ne retained)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-MARKER-LINEAGE-MISMATCH",
        s"procedural loop '${loop.label}' retained $retainedMarkerCount exact markers while $liveMarkerCount markers occur on ${markerOwners.size} live assignments; exactly its retained assignment must own one marker",
        source
      )
    }
    if (loop.slices.isEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-SLICE-EVIDENCE-MISSING",
        s"procedural loop '${loop.label}' retained no packed-slice identities",
        source
      )
    }

    val claimedSlices =
      new IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]()
    val claimedResults = new IdentityHashMap[BitVector, java.lang.Boolean]()
    val validated = loop.slices.map { slice =>
      val sliceSource = Option(slice).flatMap(_.sourceLocation).orElse(source)
      if (
        slice == null || slice.source == null || slice.result == null ||
        slice.assignment == null
      ) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-PROCESS-SLICE-EVIDENCE-STALE",
          s"procedural loop '${loop.label}' retained an incomplete packed-slice identity",
          sliceSource
        )
      }
      val anchor = slice.assignment
      if (anchor.target == null || anchor.source == null) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-PROCESS-SLICE-EVIDENCE-STALE",
          s"procedural loop '${loop.label}' retained a slice anchor without complete target/source expressions",
          sliceSource
        )
      }
      if (
        claimedSlices.put(anchor, java.lang.Boolean.TRUE) != null ||
        claimedResults.put(slice.result, java.lang.Boolean.TRUE) != null ||
        claimedAssignments.put(anchor, java.lang.Boolean.TRUE) != null
      ) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-PROCESS-SLICE-EVIDENCE-CARDINALITY",
          s"procedural loop '${loop.label}' reuses one retained slice anchor or result identity",
          sliceSource
        )
      }
      val anchorCount = identityCount(liveStatementCounts, anchor)
      val resultCount = identityCount(liveStatementCounts, slice.result)
      if (anchorCount > 1 || resultCount > 1 || anchorCount != resultCount) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-PROCESS-SLICE-EVIDENCE-STALE",
          s"procedural loop '${loop.label}' slice anchor/result are live $anchorCount/$resultCount times; both must be retained once or consumed together",
          sliceSource
        )
      }
      val witnessLineage = anchor.source match {
        case access: BitVectorRangedAccessFixed =>
          (access.source eq slice.source) &&
          BigInt(access.lo) == slice.offset.default &&
          BigInt(access.hi - access.lo + 1) == slice.width.default
        case _ => false
      }
      if (
        (slice.source.component ne component) ||
        (resultCount == 1 && (slice.result.component ne component)) ||
        (anchor.target ne slice.result) ||
        (anchor.finalTarget ne slice.result) || !witnessLineage
      ) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-PROCESS-SLICE-LINEAGE-MISMATCH",
          s"procedural loop '${loop.label}' retained slice no longer has one exact result assignment from its witnessed source range (source owner: ${slice.source.component eq component}; result owner: ${slice.result.component eq component}; direct target: ${anchor.target eq slice.result}; final target: ${anchor.finalTarget eq slice.result}; witnessed range: $witnessLineage)",
          sliceSource
        )
      }

      val sourceResultCount =
        expressionIdentityCount(retained.source, slice.result)
      val sourceWitnessCount =
        expressionIdentityCount(retained.source, anchor.source)
      val targetResultCount =
        expressionIdentityCount(retained.target, slice.result)
      val directTarget = retained.target match {
        case target: RangedAssignmentFixed =>
          (target.out eq slice.source) &&
          BigInt(target.lo) == slice.offset.default &&
          BigInt(target.hi - target.lo + 1) == slice.width.default
        case _ => false
      }
      val sourceLineageCount = sourceResultCount + sourceWitnessCount
      val targetLineageCount = targetResultCount + (if (directTarget) 1 else 0)
      if (sourceLineageCount > 1 || targetLineageCount > 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-PROCESS-SLICE-LINEAGE-CARDINALITY",
          s"procedural loop '${loop.label}' uses one retained slice $sourceLineageCount times as a source and $targetLineageCount times as a target",
          sliceSource
        )
      }
      val side =
        if (sourceLineageCount == 1 && targetResultCount == 0) SourceSlice
        else if (sourceLineageCount == 0 && targetLineageCount == 1) TargetSlice
        else {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-PROCESS-SLICE-LINEAGE-MISMATCH",
            s"procedural loop '${loop.label}' cannot map one retained slice identity uniquely to its final target or source expression",
            sliceSource
          )
        }
      ValidatedSlice(slice, side)
    }
    if (validated.count(_.side == TargetSlice) != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-ASSIGNMENT-LINEAGE-MISMATCH",
        s"procedural loop '${loop.label}' retains ${validated.count(_.side == TargetSlice)} exact target slices; exactly one is required",
        source
      )
    }
    ValidatedLoop(loop, validated)
  }

  private def identityCount(
      counts: IdentityHashMap[Statement, java.lang.Integer],
      statement: Statement
  ): Int = Option(counts.get(statement)).fold(0)(_.intValue)

  private def expressionIdentityCount(
      root: Expression,
      expected: Expression
  ): Int = {
    if (root == null || expected == null) return 0
    if (root eq expected) return 1
    var count = 0
    root.foreachExpression(value => count += expressionIdentityCount(value, expected))
    count
  }

  private def markerPattern(marker: String): Pattern =
    Pattern.compile(
      "(?<![A-Za-z0-9_$])" + Pattern.quote(marker) +
        "(?![A-Za-z0-9_$])"
    )

  private def markerOccurrences(value: String, marker: String): Int = {
    val matcher = markerPattern(marker).matcher(Option(value).getOrElse(""))
    var count = 0
    while (matcher.find()) count += 1
    count
  }

  private def validateNames(
      component: Component,
      loops: Vector[ParameterizedProceduralFor],
      pc: PhaseContext
  ): Unit = {
    val portable = "[A-Za-z_][A-Za-z0-9_]*".r
    val declarationNames = mutable.LinkedHashSet.empty[String]
    component.dslBody.walkDeclarations {
      case value: DeclarationStatement =>
        Option(value.getName()).filter(_.nonEmpty).foreach(declarationNames += _)
      case _ =>
    }
    component.getOrdredNodeIo.foreach { value =>
      Option(value.getName()).filter(_.nonEmpty).foreach(declarationNames += _)
    }
    val parameterNames =
      (
        ParameterizedWidth.parametersOf(component) ++
          ParameterizedVerilogVecs.parametersOf(component) ++
          ParameterizedStructure.parametersOf(component) ++
          ParameterizedProcess.parametersOf(component)
      ).map(_.name).toSet

    val allNames = loops.flatMap(loop =>
      Vector(
        (loop.label, "procedural loop label", loop.sourceLocation),
        (loop.indexName, "procedural loop index", loop.sourceLocation)
      )
    )
    allNames.foreach { case (name, role, sourceLocation) =>
      if (name == null || !portable.pattern.matcher(name).matches()) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-PROCESS-NAME-INVALID",
          s"$role '$name' is not a portable Verilog identifier",
          sourceLocation
        )
      }
      if (pc.verilogKeywords.contains(name)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-PROCESS-NAME-RESERVED",
          s"$role '$name' is reserved by IEEE 1364",
          sourceLocation
        )
      }
      if (parameterNames(name)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-PROCESS-PARAMETER-NAME-COLLISION",
          s"$role '$name' collides with a retained parameter",
          sourceLocation
        )
      }
      if (role.endsWith("index") && declarationNames(name)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-PROCESS-SIGNAL-NAME-COLLISION",
          s"$role '$name' collides with an existing signal",
          sourceLocation
        )
      }
    }
    allNames
      .groupBy(_._1)
      .collectFirst {
        case (name, values) if values.size != 1 => name
      }
      .foreach { name =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-PROCESS-NAME-DUPLICATE",
          s"procedural loop name '$name' is reused"
        )
      }
  }

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String] = None
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
