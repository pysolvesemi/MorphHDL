package spinal.core.internals

import java.util.regex.{Matcher, Pattern}

import scala.collection.mutable

import spinal.core._

/**
  * MorphHDL-owned Increment 34 replacement of one witnessed native assignment with a
  * parameter-bounded Verilog-2001 procedural `for`.
  *
  * Normal SpinalHDL elaboration and process grouping remain authoritative. The
  * captured assignment carries a private marker through native emission; this
  * pass locates that exact statement, substitutes its retained packed slices,
  * and wraps it in the reviewed loop. Structural construction is handled
  * independently by [[ParameterizedVerilogStructural]].
  */
private[internals] object ParameterizedVerilogProcesses {
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
    val normalized = verilog.replace("\r\n", "\n").replace('\r', '\n')
    val originalLines = normalized.split("\n", -1).toVector
    val plans = loops.map(loop => planLoop(loop, originalLines))
    plans.groupBy(_.lineIndex).collectFirst {
      case (line, values) if values.size != 1 => line
    }.foreach { line =>
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
    if (loops.exists(loop => result.contains(loop.marker))) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-MARKER-LEAK",
        "one or more internal procedural-loop markers remained in emitted Verilog"
      )
    }
    result
  }

  private def planLoop(
      loop: ParameterizedProceduralFor,
      lines: Vector[String]
  ): PlannedLoop = {
    val markerLines = lines.zipWithIndex.collect {
      case (line, index) if line.contains(loop.marker) => index
    }
    if (markerLines.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-MARKER-NOT-FOUND",
        s"native Verilog contains ${markerLines.size} assignments for retained procedural loop '${loop.label}', expected exactly one",
        loop.sourceLocation
      )
    }
    val lineIndex = markerLines.head
    val processStart = (lineIndex to 0 by -1).find { index =>
      lines(index).trim.startsWith("always @(")
    }.getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-PROCESS-CONTEXT-NOT-FOUND",
        s"retained assignment for procedural loop '${loop.label}' was not emitted inside an always block",
        loop.sourceLocation
      )
    }

    val original = removeMarker(lines(lineIndex), loop.marker)
    val rewrittenAssignment = rewriteSlices(original, loop)
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
    val withoutMarker = line.replace(marker, "")
    val withoutEmptyComment =
      withoutMarker.replaceFirst("""\s*//\s*$""", "")
    withoutEmptyComment.replaceFirst("""\s+$""", "")
  }

  private def rewriteSlices(
      line: String,
      loop: ParameterizedProceduralFor
  ): String =
    loop.slices.foldLeft(line) { case (current, slice) =>
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
      val descendingRewritten =
        descending.replaceAllIn(current, Matcher.quoteReplacement(replacement))
      val rewritten =
        indexed.replaceAllIn(
          descendingRewritten,
          Matcher.quoteReplacement(replacement)
        )
      if (rewritten == current) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-PROCESS-SLICE-NOT-FOUND",
          s"native assignment for loop '${loop.label}' did not contain witnessed slice '$sourceName[$high:$low]'",
          slice.sourceLocation.orElse(loop.sourceLocation)
        )
      }
      rewritten
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
    allNames.groupBy(_._1).collectFirst {
      case (name, values) if values.size != 1 => name
    }.foreach { name =>
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
