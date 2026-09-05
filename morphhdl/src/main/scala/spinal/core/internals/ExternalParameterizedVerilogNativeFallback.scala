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
          ExternalParameterizedAutoResize.parametersOf(component).nonEmpty ||
          ParameterizedMemory.parametersOf(component).nonEmpty ||
          ExternalParameterizedValueRegistry.parametersOf(component).nonEmpty ||
          ParameterizedBlackBoxGenericRegistry.hasSymbolicBindings(component) ||
          ParameterizedVerilogVecs.hasVectors(component) ||
          ParameterizedProcess.parametersOf(component).nonEmpty ||
          ParameterizedStructure.parametersOf(component).nonEmpty ||
          component.children.exists { child =>
            ParameterizedWidth.parametersOf(child).nonEmpty ||
            ExternalParameterizedAutoResize.parametersOf(child).nonEmpty ||
            ParameterizedMemory.parametersOf(child).nonEmpty ||
            ExternalParameterizedValueRegistry.parametersOf(child).nonEmpty ||
            ParameterizedBlackBoxGenericRegistry.hasSymbolicBindings(child) ||
            ParameterizedVerilogVecs.hasVectors(child) ||
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
        ParameterizedMemory.parametersOf(component) ++
        ExternalParameterizedValueRegistry.parametersOf(component) ++
        ParameterizedVerilogVecs.parametersOf(component) ++
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
    val rewrittenInitializers = rewriteRetainedZeroInitializers(
      component,
      rewrittenDeclarations
    )
    val rewrittenValues = rewriteRetainedValueAssignments(
      component,
      rewrittenInitializers
    )
    val rewrittenResizes = rewriteRetainedResizeAssignments(
      component,
      rewrittenValues,
      nativeSignedResize = morphhdl.MorphSignedCasts.isEnabled(pc.config)
    )
    val rewrittenNormalizedTypedResizes =
      rewriteNormalizedTypedUIntResizeAssignments(
        component,
        rewrittenResizes
      )
    val canonical =
      if (isCanonicalDirectSurface(component))
        canonicalizeDeclarations(component, rewrittenNormalizedTypedResizes)
      else rewrittenNormalizedTypedResizes
    val withVectors = ParameterizedVerilogVecs.rewrite(
      component,
      canonical,
      pc
    )
    val withFiniteFolds = ParameterizedVerilogFiniteFolds.rewrite(
      component,
      withVectors,
      pc
    )
    lowerRetainedIntegerHelpers(withFiniteFolds, component.definitionName)
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

  private sealed trait NativeHelperContext
  private case object NativeHelperBareCall extends NativeHelperContext
  private case object NativeHelperNamedAssociation extends NativeHelperContext
  private case object NativeHelperHierarchy extends NativeHelperContext
  private case object NativeHelperOtherToken extends NativeHelperContext
  private case object NativeHelperNonCode extends NativeHelperContext

  private sealed trait NativeHelperPrefixToken
  private case object NativePrefixIdentifier extends NativeHelperPrefixToken
  private case object NativePrefixDot extends NativeHelperPrefixToken
  private case object NativePrefixLeftParen extends NativeHelperPrefixToken
  private case object NativePrefixComma extends NativeHelperPrefixToken
  private case object NativePrefixOther extends NativeHelperPrefixToken

  /** Typed symbolic helper names are an internal expression IR, not Verilog
    * functions. Lower the reviewed positive-width helpers after every other
    * native rewrite so declarations, structural alternatives and memories all
    * share one collision-safe IEEE-1364 implementation.
    */
  private[internals] def lowerRetainedIntegerHelpers(
      verilog: String,
      definitionName: String
  ): String = {
    val needsAddressWidth =
      containsNativeUnaryCall(verilog, NativeAddressWidthHelper)
    val needsCeilLog2 = containsNativeUnaryCall(verilog, NativeCeilLog2Helper)
    if (!needsAddressWidth && !needsCeilLog2) {
      firstUnsupportedNativeIntegerHelper(verilog).foreach { helper =>
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
    firstUnsupportedNativeIntegerHelper(lowered).foreach { helper =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-UNSUPPORTED",
        s"module '$definitionName' retains unsupported native Int helper '${helper.trim}'"
      )
    }

    if (existingPortableHelpers.nonEmpty) lowered
    else insertPortableLogFunction(lowered, definitionName, helperName)
  }

  /** A module instance may contain `.port (` or `.parameter (` immediately
    * after its opening parenthesis or a preceding association comma. That
    * token is a named association, not an expression helper call. Exclude only
    * that local grammar after the hierarchy/Vec passes have already proved and
    * emitted it. Physical lines are not syntax: a wrapped hierarchical
    * expression such as `instance\n.morphhdl_helper(...)` must remain visible
    * to this fail-closed audit.
    */
  private def firstUnsupportedNativeIntegerHelper(
      value: String
  ): Option[String] =
    NativeIntegerHelper
      .findAllMatchIn(value)
      .find { helper =>
        nativeHelperContext(value, helper.start) match {
          case NativeHelperNamedAssociation | NativeHelperNonCode => false
          case _                                                  => true
        }
      }
      .map(_.matched)

  /** Classify the token immediately before one helper identifier while
    * treating Verilog whitespace, comments, strings and escaped identifiers
    * as lexical syntax rather than raw characters. The same classifier drives
    * detection, rewriting and the final unsupported-helper audit, so trivia
    * cannot turn a hierarchical call into a bare call or named association.
    */
  private def nativeHelperContext(
      value: String,
      helperStart: Int
  ): NativeHelperContext = {
    if (value == null || helperStart < 0 || helperStart > value.length)
      return NativeHelperNonCode

    var previous: NativeHelperPrefixToken = null
    var beforePrevious: NativeHelperPrefixToken = null

    def retain(token: NativeHelperPrefixToken): Unit = {
      beforePrevious = previous
      previous = token
    }

    var index = 0
    while (index < helperStart) {
      val current = value.charAt(index)
      if (current.isWhitespace) {
        index += 1
      } else if (
        current == '/' && index + 1 < value.length &&
        value.charAt(index + 1) == '/'
      ) {
        val newline = value.indexOf('\n', index + 2)
        if (newline < 0 || newline >= helperStart)
          return NativeHelperNonCode
        index = newline + 1
      } else if (
        current == '/' && index + 1 < value.length &&
        value.charAt(index + 1) == '*'
      ) {
        val end = value.indexOf("*/", index + 2)
        if (end < 0 || end + 2 > helperStart)
          return NativeHelperNonCode
        index = end + 2
      } else if (current == '"') {
        var cursor = index + 1
        var escaped = false
        var closed = false
        while (cursor < value.length && !closed) {
          val character = value.charAt(cursor)
          if (escaped) escaped = false
          else if (character == '\\') escaped = true
          else if (character == '"') closed = true
          cursor += 1
        }
        if (!closed || cursor > helperStart)
          return NativeHelperNonCode
        retain(NativePrefixOther)
        index = cursor
      } else if (current == '\\') {
        var cursor = index + 1
        while (cursor < value.length && !value.charAt(cursor).isWhitespace)
          cursor += 1
        if (cursor > helperStart) return NativeHelperNonCode
        retain(NativePrefixIdentifier)
        index = cursor
      } else if (isIdentifierCharacter(current)) {
        var cursor = index + 1
        while (
          cursor < value.length &&
          isIdentifierCharacter(value.charAt(cursor))
        ) cursor += 1
        if (cursor > helperStart) return NativeHelperNonCode
        retain(NativePrefixIdentifier)
        index = cursor
      } else {
        retain(
          current match {
            case '.' => NativePrefixDot
            case '(' => NativePrefixLeftParen
            case ',' => NativePrefixComma
            case _   => NativePrefixOther
          }
        )
        index += 1
      }
    }

    previous match {
      case NativePrefixDot =>
        beforePrevious match {
          case null | NativePrefixLeftParen | NativePrefixComma =>
            NativeHelperNamedAssociation
          case _ => NativeHelperHierarchy
        }
      case NativePrefixIdentifier => NativeHelperOtherToken
      case _                       => NativeHelperBareCall
    }
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
      val beforeDisallowsCall =
        nativeHelperContext(value, next) != NativeHelperBareCall
      if (beforeDisallowsCall) {
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

  private def containsNativeUnaryCall(
      value: String,
      functionName: String
  ): Boolean = {
    val marker = functionName + "("
    var next = value.indexOf(marker)
    while (next >= 0) {
      if (nativeHelperContext(value, next) == NativeHelperBareCall) return true
      next = value.indexOf(marker, next + marker.length)
    }
    false
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
    // Native Verilog may prefix a declaration with one or more synthesis
    // attributes. Those attributes are syntax attached to the declaration,
    // not a reason to hide its retained packed-width identity from this pass.
    val declarationLine =
      "^(?:\\(\\*.*?\\*\\)\\s*)*(?:input|output|inout|wire|reg|logic)\\b".r
        .findPrefixOf(trimmed)
        .nonEmpty
    if (!declarationLine) return line

    widthsByName.foldLeft(line) { case (current, (name, range)) =>
      val quotedName = Pattern.quote(name)
      val declarationEnd = "(?=\\s*(?:/\\*.*?\\*/\\s*)*(?:[,;]|$))"
      val packedPattern =
        ("(\\[[^\\]]+\\])(\\s+)(" + quotedName + ")" + declarationEnd).r
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
        val scalarPattern =
          ("(\\s+)(" + quotedName + ")" + declarationEnd).r
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

  /** Rewrite only graph-proven zero initializers of retained-width registers.
    *
    * The native emitter correctly sizes an initializer to the construction
    * witness, but that literal must grow with a later parameter specialization.
    * Authorization starts from the exact InitAssignmentStatement target and
    * its poison-free zero BitVectorLiteral source. Every direct, full-target
    * invariant-zero write to that same retained register is then counted in
    * the graph, and the emitted signal name and witness literal may rewrite
    * exactly that many Verilog edges. This preserves lineage when an ordinary
    * register also has a clear, wrap or flush-to-zero assignment.
    */
  private def isInvariantZero(expression: Expression): Boolean =
    expression match {
      case literal: BitVectorLiteral =>
        !literal.hasPoison() && literal.getValue() == 0
      case resize: Resize => isInvariantZero(resize.input)
      case cast: CastBitVectorToBitVector =>
        isInvariantZero(cast.input)
      case _ => false
    }

  private def isAuthorizedZeroAssignment(
      statement: AssignmentStatement,
      target: BitVector
  ): Boolean =
    (statement.target eq target) &&
      (statement.finalTarget eq target) &&
      (statement.source match {
        case sourceWidth: WidthProvider =>
          sourceWidth.getWidth == target.getBitsWidth &&
            isInvariantZero(statement.source)
        case _ => false
      })

  private def rewriteRetainedZeroInitializers(
      component: Component,
      verilog: String
  ): String = {
    final case class RetainedZeroInitializer(
        target: BitVector,
        name: String,
        width: ElaborationIntegerExpression,
        witness: String
    )

    val initializers = ArrayBuffer.empty[RetainedZeroInitializer]
    component.dslBody.walkLeafStatements {
      case statement: InitAssignmentStatement =>
        statement.finalTarget match {
          case target: BitVector if target.component eq component =>
            ParameterizedWidth
              .expressionOf(target)
              .filter(_.parameters.nonEmpty)
              .foreach { width =>
                statement.source match {
                  case literal: BitVectorLiteral
                      if !literal.hasPoison() && literal.getValue() == 0 &&
                        literal.getWidth == target.getBitsWidth =>
                    val name = Option(target.getName()).filter(_.nonEmpty).getOrElse {
                      fail(
                        "SPINAL-PARAMETERIZED-VERILOG-ZERO-INIT-NAME-MISSING",
                        "one retained-width zero-initialized register has no final emitted name",
                        ParameterizedWidth.sourceLocationOf(target)
                      )
                    }
                    initializers += RetainedZeroInitializer(
                      target,
                      name,
                      width,
                      emittedRetainedWitness(literal)
                    )
                  case _ =>
                }
              }
          case _ =>
        }
      case _ =>
    }
    if (initializers.isEmpty) return verilog

    initializers
      .groupBy(_.name)
      .collectFirst { case (name, values) if values.size != 1 => name }
      .foreach { name =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-ZERO-INIT-NAME-CONFLICT",
          s"multiple retained-width zero initializers resolved to emitted name '$name'"
        )
      }

    var lines = verilog.split("\\n", -1).toVector
    initializers.sortBy(value => -value.name.length).foreach { initializer =>
      var authorizedEdges = 0
      component.dslBody.walkLeafStatements {
        case statement: AssignmentStatement
            if isAuthorizedZeroAssignment(statement, initializer.target) =>
          authorizedEdges += 1
        case _ =>
      }
      val pattern = (
        "^(\\s*" + Pattern.quote(initializer.name) +
          "\\s*(?:<=|=)\\s*)" + Pattern.quote(initializer.witness) + "(;.*)$"
      ).r
      var exactEdges = 0
      lines = lines.map {
        case pattern(prefix, suffix) =>
          exactEdges += 1
          prefix + "{" + initializer.width.verilog + "{1'b0}}" + suffix
        case line => line
      }
      if (authorizedEdges == 0 || exactEdges != authorizedEdges) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-ZERO-INIT-EMITTED-LINEAGE-MISMATCH",
          s"retained-width zero initializer '${initializer.name}' maps to $exactEdges exact emitted witness edges, but the graph authorizes $authorizedEdges direct invariant-zero assignments",
          initializer.width.sourceLocation
        )
      }
    }
    lines.mkString("\n")
  }

  /** Replace only the concrete witness assignment of compiler-created UInt
    * carriers. The carrier was retained by exact object identity; its final
    * emitted name is read from that object after normal Spinal naming. No port,
    * component or user signal name is used as a discovery key.
    */
  private[internals] def rewriteRetainedValueAssignments(
      component: Component,
      verilog: String
  ): String = {
    val records = ExternalParameterizedValueRegistry.valuesOf(component)
    if (records.isEmpty) return verilog

    val liveAssignments = ArrayBuffer.empty[DataAssignmentStatement]
    component.dslBody.walkStatements {
      case assignment: DataAssignmentStatement => liveAssignments += assignment
      case _                                   =>
    }
    val claimedAssignments =
      new IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]()

    val validated = records.map { case (value, record) =>
      val witnessLiteral = validateRetainedValueAssignmentLineage(
        component,
        value,
        record,
        liveAssignments.toVector,
        claimedAssignments
      )
      validateRetainedValueProjection(component, value, record)
      (value, record, witnessLiteral)
    }

    val named = validated.map { case (value, record, witnessLiteral) =>
      val name = Option(value.getName()).filter(_.nonEmpty).getOrElse {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VALUE-NAME-MISSING",
          "one retained native UInt carrier has no final emitted name",
          record.sourceLocation.orElse(record.expression.sourceLocation)
        )
      }
      (name, record, witnessLiteral)
    }
    named
      .groupBy { case (name, _, _) => name }
      .collectFirst {
        case (name, values) if values.size != 1 => name
      }
      .foreach { name =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-VALUE-NAME-CONFLICT",
          s"multiple retained native UInt carriers resolved to emitted name '$name'"
        )
      }

    var lines = verilog.split("\n", -1).toVector
    named
      .sortBy { case (name, _, _) => -name.length }
      .foreach { case (name, record, witnessLiteral) =>
        val pattern = (
          "^(\\s*assign\\s+" + Pattern.quote(name) +
            "\\s*=\\s*)(.*?)(;\\s*)$"
        ).r
        val expectedWitness = emittedRetainedWitness(witnessLiteral)
        var targetCount = 0
        var exactLineageCount = 0
        lines = lines.map { line =>
          line match {
            case pattern(prefix, rhs, suffix) =>
              targetCount += 1
              if (rhs.trim == expectedWitness) {
                exactLineageCount += 1
                prefix + "(" + record.expression.verilog + ")" + suffix
              } else line
            case _ => line
          }
        }
        if (targetCount != 1 || exactLineageCount != 1) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-VALUE-EMITTED-LINEAGE-MISMATCH",
            s"retained native UInt carrier '$name' maps to $targetCount emitted target assignments and $exactLineageCount exact native witness edges; exactly its retained direct literal emission is required",
            record.sourceLocation.orElse(record.expression.sourceLocation)
          )
        }
      }
    lines.mkString("\n")
  }

  /** Mirror the inherited emitter's poison-free BitVector literal syntax for
    * the exact retained witness source.  The final signal name locates a
    * candidate assignment only; this source-derived text is the authorization
    * for replacing that assignment's right-hand side.
    */
  private def emittedRetainedWitness(literal: BitVectorLiteral): String = {
    val width = literal.getWidth
    if (width > 4)
      s"${width}'h${literal.hexString(width, false)}"
    else
      s"${width}'b${literal.getBitsStringOn(width, 'x')}"
  }

  private def validateRetainedValueAssignmentLineage(
      component: Component,
      value: UInt,
      record: ExternalParameterizedValueRecord,
      liveAssignments: Vector[DataAssignmentStatement],
      claimedAssignments: IdentityHashMap[
        DataAssignmentStatement,
        java.lang.Boolean
      ]
  ): BitVectorLiteral = {
    val source = record.sourceLocation.orElse(record.expression.sourceLocation)
    val retainedAssignment = record.assignment.getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VALUE-ASSIGNMENT-EVIDENCE-STALE",
        "one retained native UInt carrier lost its weakly retained witness assignment identity",
        source
      )
    }
    val retainedWitnessSource = record.witnessSource.getOrElse {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VALUE-ASSIGNMENT-LINEAGE-MISMATCH",
        "one retained native UInt carrier lost its weakly retained literal witness source identity",
        source
      )
    }
    if (value.component ne component) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VALUE-COMPONENT-MISMATCH",
        "one retained native UInt carrier is not owned by the component being published",
        source
      )
    }
    if (!liveAssignments.exists(_ eq retainedAssignment)) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VALUE-ASSIGNMENT-EVIDENCE-STALE",
        "one retained native UInt carrier lost its exact witness assignment identity",
        source
      )
    }
    val exactTargets = liveAssignments.filter(_.finalTarget eq value)
    if (
      exactTargets.size != 1 ||
      (exactTargets.head ne retainedAssignment)
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VALUE-ASSIGNMENT-EVIDENCE-AMBIGUOUS",
        s"one retained native UInt carrier has ${exactTargets.size} live assignments; exactly its retained witness assignment is required",
        source
      )
    }
    val exactWitnessLiteral = retainedWitnessSource match {
      case literal: BitVectorLiteral if !literal.hasPoison() && literal.getValue() == record.witness =>
        Some(literal)
      case _ => None
    }
    if (
      (retainedAssignment.target ne value) ||
      (retainedAssignment.finalTarget ne value) ||
      (retainedAssignment.source ne retainedWitnessSource) ||
      exactWitnessLiteral.isEmpty
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VALUE-ASSIGNMENT-LINEAGE-MISMATCH",
        "one retained native UInt carrier changed its exact direct literal witness source",
        source
      )
    }
    if (claimedAssignments.put(retainedAssignment, java.lang.Boolean.TRUE) != null) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VALUE-ASSIGNMENT-EVIDENCE-REUSED",
        "one native witness assignment was claimed by multiple retained UInt values",
        source
      )
    }
    exactWitnessLiteral.get
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

    val retainedWidth = ParameterizedWidth.expressionOf(value)
    val pointwiseWidth = retainedWidth.filter { width =>
      (record.expression.exactDomain, width.exactDomain) match {
        case (Some(valueDomain), Some(widthDomain)) =>
          (valueDomain.root eq widthDomain.root) &&
          valueDomain.parameter == widthDomain.parameter &&
          valueDomain.evidenceValues == widthDomain.evidenceValues
        case _ => false
      }
    }
    val widthsByRoot = pointwiseWidth match {
      case Some(width) =>
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
      case None if retainedWidth.nonEmpty =>
        val width = retainedWidth.get
        evaluation.rootValues.iterator.map(_ -> width.minimum).toMap
      case None =>
        evaluation.rootValues.iterator
          .map(_ -> BigInt(value.getBitsWidth))
          .toMap
    }
    evaluation.results
      .collectFirst {
        case (rootValue, result) if widthsByRoot.get(rootValue).forall { width =>
              width < 1 || BigInt(result.bitLength) > width
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

  /** Resolve typed resize provenance through exact native Resize identity. */
  private def retainedResizeExpression(
      resize: Resize
  ): Option[ElaborationIntegerExpression] =
    ParameterizedWidth.resizeExpressionOf(resize)

  /** Replace target-witness syntax emitted for one exact native Resize. A
    * narrowing slice receives the retained symbolic range; a proven unsigned
    * widening replaces the native witness-sized zero prefix with one invariant
    * zero bit, preserving unsignedness while the symbolic target declaration
    * performs any remaining zero extension. The
    * eligible assignment, target and Resize node are discovered from the
    * normalized graph by JVM identity; emitted names are used only after that
    * proof to address the corresponding native Verilog assignment. Other
    * Resize renderings remain owned by the native emitter.
    */
  private[internals] def rewriteRetainedResizeAssignments(
      component: Component,
      verilog: String,
      nativeSignedResize: Boolean = false
  ): String = {
    final case class RetainedResizeAssignment(
        assignment: DataAssignmentStatement,
        resize: Resize,
        target: BitVector,
        source: Option[BaseType],
        targetName: String,
        sourceName: Option[String],
        witnessSize: Int,
        inputWitnessSize: Int,
        unsigned: Boolean,
        expression: ElaborationIntegerExpression
    )

    val retained = ArrayBuffer.empty[RetainedResizeAssignment]
    component.dslBody.walkLeafStatements {
      case assignment: DataAssignmentStatement if assignment.target == assignment.finalTarget =>
        (assignment.target, assignment.source) match {
          case (target: BitVector, resize: Resize)
              if (target.component eq component) && target.isComb &&
                target.getBitsWidth == resize.size &&
                !(nativeSignedResize && resize.getClass == classOf[ResizeSInt]) =>
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
                  Some(ParameterizedWidth.expressionOf(autoTarget) -> sourceName)
                case _ => None
              }
            val retainedPublication =
              (retainedResizeExpression(resize), capturedAutoResize) match {
                case (Some(typed), Some((Some(captured), sourceName))) =>
                  if (
                    !ExternalFormalParameterRegistry.equivalentExpression(
                      typed,
                      captured
                    )
                  ) {
                    fail(
                      "SPINAL-PARAMETERIZED-VERILOG-RESIZE-PROVENANCE-CONFLICT",
                      s"one exact native Resize target is associated with conflicting typed expression '${typed.verilog}' and captured source-qualified expression '${captured.verilog}'",
                      typed.sourceLocation.orElse(captured.sourceLocation)
                    )
                  }
                  Some(typed -> Some(sourceName))
                case (Some(typed), Some((None, sourceName))) =>
                  // Native input normalization may move the symbolic width
                  // from the exact target onto the materialized Resize.  The
                  // captured boundary still supplies exact source identity;
                  // the Resize-local typed publication supplies the width.
                  Some(typed -> Some(sourceName))
                case (Some(typed), None) =>
                  Some(typed -> Option.empty[String])
                case (None, Some((Some(captured), sourceName))) =>
                  Some(captured -> Some(sourceName))
                case (None, _) => None
              }
            retainedPublication
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
                // A witness-equal Resize emits no target-specific syntax: the
                // symbolic declaration itself applies any proven one-sided
                // sizing at other domain points. Do not claim a text rewrite
                // when there is nothing exact to replace.
                if (resize.size != resize.input.getWidth) {
                  retained += RetainedResizeAssignment(
                    assignment,
                    resize,
                    target,
                    sourceName.map(_ => resize.input.asInstanceOf[BaseType]),
                    targetName,
                    sourceName,
                    resize.size,
                    resize.input.getWidth,
                    resize.getTypeObject == TypeBits ||
                      resize.getTypeObject == TypeUInt,
                    expression
                  )
                }
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
          (existing.assignment eq value.assignment) &&
          (existing.resize eq value.resize) &&
          (existing.target eq value.target) &&
          existing.source.size == value.source.size &&
          existing.source.zip(value.source).forall { case (left, right) =>
            left eq right
          } &&
          existing.targetName == value.targetName &&
          existing.sourceName == value.sourceName &&
          existing.witnessSize == value.witnessSize &&
          existing.inputWitnessSize == value.inputWitnessSize &&
          existing.unsigned == value.unsigned &&
          ElabInt.equivalentExpression(existing.expression, value.expression)
        }
      ) known
      else known :+ value
    }
    val grouped = unique.groupBy(_.targetName)
    grouped
      .collectFirst {
        case (name, values)
            if values.size > 1 && {
              val first = values.head
              values.exists(_.sourceName.isEmpty) ||
              values.exists(value => !(value.target eq first.target)) ||
              values.tail.exists(value => value.assignment eq first.assignment) ||
              values.tail.exists(value => value.resize eq first.resize) ||
              values.tail.exists(value =>
                value.source.exists(source =>
                  first.source.exists(_ eq source)
                )
              ) ||
              values.flatMap(_.sourceName).distinct.size != values.size ||
              values.exists(value =>
                !ElabInt.equivalentExpression(
                  value.expression,
                  first.expression
                )
              )
            } =>
          name
      }
      .foreach { name =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-RESIZE-TARGET-CONFLICT",
          s"retained native Resize assignments for target '$name' disagree"
        )
      }

    var lines = verilog.split("\n", -1).toVector
    grouped.toVector
      .sortBy { case (name, _) => -name.length }
      .flatMap { case (_, values) =>
        values.sortBy(value =>
          (
            value.sourceName.getOrElse(""),
            value.witnessSize,
            value.inputWitnessSize
          )
        )
      }
      .foreach { record =>
      val name = record.targetName
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
      val targetCount =
        lines.count(line => assignmentPattern.findFirstIn(line).nonEmpty)
      var exactRewriteCount = 0
      lines = if (record.witnessSize < record.inputWitnessSize) {
        record.sourceName match {
          case None =>
            lines.map { line =>
              line match {
                case assignmentPattern(prefix, rhs, suffix) =>
                  rhs match {
                    case concreteRange(source, trailing) =>
                      exactRewriteCount += 1
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
                  exactRewriteCount += 1
                  prefix + sourceName + spacing + symbolicRange + suffix
                case _ => line
              }
            }
        }
      } else {
        if (!record.unsigned) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-SIGNED-RESIZE-GROW-DOMAIN-UNSUPPORTED",
            s"retained signed Resize target '$name' uses witness-specific sign extension",
            record.expression.sourceLocation
          )
        }
        val padding = record.witnessSize - record.inputWitnessSize
        val concreteGrow = (
          "^\\{\\s*" + padding + "'d0\\s*,\\s*(.*?)\\s*\\}$"
        ).r
        record.sourceName match {
          case None =>
            lines.map { line =>
              line match {
                case assignmentPattern(prefix, rhs, suffix) =>
                  rhs match {
                    case concreteGrow(source) =>
                      exactRewriteCount += 1
                      prefix + "{1'b0, " + source.trim + "}" + suffix
                    case _ => line
                  }
                case _ => line
              }
            }
          case Some(sourceName) =>
            val exactGrowEdge = (
              "^(\\s*(?:assign\\s+)?" + Pattern.quote(name) +
                "\\s*=\\s*)\\{\\s*" + padding +
                "'d0\\s*,\\s*" + Pattern.quote(sourceName) +
                "\\s*\\}(\\s*;\\s*)$"
            ).r
            lines.map { line =>
              line match {
                case exactGrowEdge(prefix, suffix) =>
                  exactRewriteCount += 1
                  prefix + "{1'b0, " + sourceName + "}" + suffix
                case _ => line
              }
            }
        }
      }
      // A source-qualified captured auto-resize may live inside a structural
      // generate branch, where publication has already converted its exact
      // assignment to procedural syntax.  Its live target/Resize/source graph
      // identities and unique emitted source edge remain authoritative.  The
      // weaker explicit typed-resize path has no source identity and must
      // still prove one unique module-scope target assignment.
      val targetAssignmentUnique = record.sourceName.nonEmpty || targetCount == 1
      if (!targetAssignmentUnique || exactRewriteCount != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-RESIZE-ASSIGNMENT-NOT-UNIQUE",
          s"retained native Resize target '$name' maps to $targetCount module-scope target assignments and $exactRewriteCount exact native Resize rewrites; one exact rewrite and one target assignment for an unqualified typed resize are required",
          record.expression.sourceLocation
        )
      }
    }
    lines.mkString("\n")
  }

  /** Reconstruct one explicit typed UInt resize whose witness-sized carrier
    * native normalization removed. The capture API proves the exact surviving
    * assignment, target and source identities. This text rewrite then masks
    * the unsigned source at its own symbolic width before the fixed consumer
    * applies ordinary Verilog assignment sizing.
    */
  private def rewriteNormalizedTypedUIntResizeAssignments(
      component: Component,
      verilog: String
  ): String = {
    final case class NormalizedTypedUIntResizeAssignment(
        target: UInt,
        source: UInt,
        targetName: String,
        sourceName: String,
        sourceWidth: ElaborationIntegerExpression,
        targetWidth: ElaborationIntegerExpression
    )

    val captured = ArrayBuffer.empty[NormalizedTypedUIntResizeAssignment]
    component.dslBody.walkLeafStatements {
      case assignment: DataAssignmentStatement if assignment.target == assignment.finalTarget =>
        assignment.target match {
          case target: UInt =>
            ExternalParameterizedAutoResize
              .normalizedTypedUIntResizeBoundary(
                component,
                assignment,
                target
              )
              .foreach { boundary =>
                val targetName =
                  Option(target.getName()).filter(_.nonEmpty).getOrElse {
                    fail(
                      "SPINAL-PARAMETERIZED-VERILOG-NORMALIZED-TYPED-RESIZE-TARGET-NAME-MISSING",
                      "one normalized typed UInt resize target has no final emitted name",
                      boundary.targetWidth.sourceLocation
                    )
                  }
                val sourceName =
                  Option(boundary.source.getName()).filter(_.nonEmpty).getOrElse {
                    fail(
                      "SPINAL-PARAMETERIZED-VERILOG-NORMALIZED-TYPED-RESIZE-SOURCE-NAME-MISSING",
                      "one normalized typed UInt resize source has no final emitted name",
                      boundary.sourceWidth.sourceLocation
                    )
                  }
                if (targetName == sourceName) {
                  fail(
                    "SPINAL-PARAMETERIZED-VERILOG-NORMALIZED-TYPED-RESIZE-NAME-ALIAS",
                    s"normalized typed UInt resize target '$targetName' aliases its source",
                    boundary.targetWidth.sourceLocation.orElse(
                      boundary.sourceWidth.sourceLocation
                    )
                  )
                }
                if (
                  boundary.targetWidth.parameters.isEmpty ||
                  boundary.sourceWidth.default < 1 ||
                  boundary.targetWidth.default < 1
                ) {
                  fail(
                    "SPINAL-PARAMETERIZED-VERILOG-NORMALIZED-TYPED-RESIZE-WIDTH-INVALID",
                    s"normalized typed UInt resize '$sourceName' -> '$targetName' must retain a positive source width and positive symbolic target width",
                    boundary.targetWidth.sourceLocation.orElse(
                      boundary.sourceWidth.sourceLocation
                    )
                  )
                }
                captured += NormalizedTypedUIntResizeAssignment(
                  target,
                  boundary.source,
                  targetName,
                  sourceName,
                  boundary.sourceWidth,
                  boundary.targetWidth
                )
              }
          case _ =>
        }
      case _ =>
    }
    if (captured.isEmpty) return verilog

    def equivalent(
        left: NormalizedTypedUIntResizeAssignment,
        right: NormalizedTypedUIntResizeAssignment
    ): Boolean =
      (left.target eq right.target) &&
        (left.source eq right.source) &&
        left.targetName == right.targetName &&
        left.sourceName == right.sourceName &&
        ExternalFormalParameterRegistry.equivalentExpression(
          left.sourceWidth,
          right.sourceWidth
        ) &&
        ExternalFormalParameterRegistry.equivalentExpression(
          left.targetWidth,
          right.targetWidth
        )

    val unique = captured.toVector.foldLeft(
      Vector.empty[NormalizedTypedUIntResizeAssignment]
    ) { case (known, value) =>
      if (known.exists(equivalent(_, value))) known else known :+ value
    }
    unique
      .groupBy(_.targetName)
      .collectFirst {
        case (name, values) if values.size != 1 => name
      }
      .foreach { name =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-NORMALIZED-TYPED-RESIZE-TARGET-CONFLICT",
          s"normalized typed UInt resize target name '$name' maps to multiple captured boundaries"
        )
      }
    unique
      .groupBy(_.sourceName)
      .collectFirst {
        case (name, values)
            if values
              .map(_.source)
              .foldLeft(Vector.empty[UInt]) {
                case (known, source) if known.exists(_ eq source) => known
                case (known, source)                              => known :+ source
              }
              .size != 1 =>
          name
      }
      .foreach { name =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-NORMALIZED-TYPED-RESIZE-SOURCE-CONFLICT",
          s"normalized typed UInt resize source name '$name' maps to multiple captured identities"
        )
      }

    var lines = verilog.split("\n", -1).toVector
    unique.sortBy(value => -value.targetName.length).foreach { record =>
      val targetAssignment = (
        "^\\s*assign\\s+" + Pattern.quote(record.targetName) +
          "\\s*=.*;\\s*$"
      ).r
      val exactEdge = (
        "^(\\s*assign\\s+" + Pattern.quote(record.targetName) +
          "\\s*=\\s*)" + Pattern.quote(record.sourceName) +
          "(\\s*;\\s*)$"
      ).r
      val targetAssignmentCount =
        lines.count(line => targetAssignment.findFirstIn(line).nonEmpty)
      var exactEdgeCount = 0
      val sourceWidth = record.sourceWidth.verilog
      val targetWidth = record.targetWidth.verilog
      val sourceWidthCount =
        if (
          "[A-Za-z_][A-Za-z0-9_$]*".r.pattern
            .matcher(sourceWidth)
            .matches()
        ) sourceWidth
        else s"($sourceWidth)"
      val mask = s"~({$sourceWidthCount{1'b1}} << ($targetWidth))"
      lines = lines.map { line =>
        line match {
          case exactEdge(prefix, suffix) =>
            exactEdgeCount += 1
            prefix + "(" + record.sourceName + " & " + mask + ")" + suffix
          case _ => line
        }
      }
      if (targetAssignmentCount != 1 || exactEdgeCount != 1) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-NORMALIZED-TYPED-RESIZE-ASSIGNMENT-NOT-UNIQUE",
          s"normalized typed UInt resize '${record.sourceName}' -> '${record.targetName}' maps to $targetAssignmentCount target assignments and $exactEdgeCount exact source edges",
          record.targetWidth.sourceLocation.orElse(
            record.sourceWidth.sourceLocation
          )
        )
      }
    }
    lines.mkString("\n")
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
        ParameterizedMemory.parametersOf(component).nonEmpty ||
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
      "^(\\s*)(.*?)(input|output|inout)\\s+(wire|reg|logic)\\s*((?:signed\\s+)?\\[[^\\]]+\\])?\\s*([A-Za-z_][A-Za-z0-9_$]*)(.*?)(?:,)?\\s*$".r
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
      "^(\\s*)(.*?)(wire|reg|logic)\\s*((?:signed\\s+)?\\[[^\\]]+\\])?\\s*([A-Za-z_][A-Za-z0-9_$]*)(.*?)\\s*;\\s*$".r
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

    /** Exact native statements whose logical width and layout are owned by
      * the typed Vec packed-operation validator.  The ordinary native graph
      * deliberately uses finite-capacity carriers and witness-width wrappers;
      * asking the generic assignment pass to reinterpret those implementation
      * nodes can reject a valid logical Vec width before the authoritative Vec
      * lineage check runs.  Membership is solely by retained statement
      * identity, and the Vec backend still requires each statement to be live
      * before publication.
      */
    private lazy val exactPackedVecEvidenceAssignments = {
      val retained =
        new IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]()
      ParameterizedVec.retainedVectorsOf(component).foreach { vector =>
        ParameterizedVec.operationsOf(vector).foreach {
          case value: ParameterizedVecPackedRead =>
            (value.resultAssignments ++ value.carrierAssignments)
              .foreach(assignment => retained.put(assignment, java.lang.Boolean.TRUE))
          case value: ParameterizedVecPackedAssignment =>
            (value.assignments ++ value.carrierAssignments)
              .foreach(assignment => retained.put(assignment, java.lang.Boolean.TRUE))
          case _ =>
        }
      }
      retained
    }

    /** Distinct full-capacity carriers beneath typed Vec packing are internal
      * native audit nodes, not declarations whose finite witness construction
      * defines a public symbolic width.  The Vec backend later validates and
      * rewrites these exact identities to the factorized logical packed range.
      */
    private lazy val exactPackedVecFiniteCarriers = {
      val retained = new IdentityHashMap[BaseType, java.lang.Boolean]()
      ParameterizedVec.retainedVectorsOf(component).foreach { vector =>
        ParameterizedVec.operationsOf(vector).foreach {
          case value: ParameterizedVecPackedRead if value.carrier ne value.result =>
            retained.put(value.carrier, java.lang.Boolean.TRUE)
          case value: ParameterizedVecPackedAssignment if value.carrier ne value.source =>
            retained.put(value.carrier, java.lang.Boolean.TRUE)
          case _ =>
        }
      }
      retained
    }

    /** Exact decoder witnesses retained for typed Vec dynamic writes are
      * finite native implementation nodes. Their one-shifted carrier geometry
      * is revalidated by the Vec backend from the retained statement, operand,
      * target, width, and guard identities before publication.
      */
    private lazy val exactDynamicVecDecoderAssignments = {
      val retained =
        new IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]()
      ParameterizedVec.retainedVectorsOf(component).foreach { vector =>
        ParameterizedVec.operationsOf(vector).foreach {
          case value: ParameterizedVecDynamicWrite =>
            value.decoderAssignments.foreach { assignment =>
              retained.put(assignment, java.lang.Boolean.TRUE)
            }
          case _ =>
        }
      }
      retained
    }

    private lazy val exactDynamicVecDecoders = {
      val retained = new IdentityHashMap[BaseType, java.lang.Boolean]()
      ParameterizedVec.retainedVectorsOf(component).foreach { vector =>
        ParameterizedVec.operationsOf(vector).foreach {
          case value: ParameterizedVecDynamicWrite =>
            retained.put(value.decoder, java.lang.Boolean.TRUE)
          case _ =>
        }
      }
      retained
    }

    private def isExactPackedVecEvidenceAssignment(
        assignment: DataAssignmentStatement
    ): Boolean =
      assignment != null &&
        exactPackedVecEvidenceAssignments.containsKey(assignment)

    private def isExactDynamicVecDecoderAssignment(
        assignment: DataAssignmentStatement
    ): Boolean =
      assignment != null &&
        exactDynamicVecDecoderAssignments.containsKey(assignment)

    /** Exact direct literal assignments retained by ElabValue are validated by
      * their identity registry before publication and rewritten from that same
      * identity after native emission. The generic width pass must not reject
      * their deliberately concrete construction witness first.
      */
    private lazy val exactRetainedValueEvidenceAssignments = {
      val retained =
        new IdentityHashMap[DataAssignmentStatement, java.lang.Boolean]()
      ExternalParameterizedValueRegistry.valuesOf(component).foreach { case (_, record) =>
        record.assignment.foreach { assignment =>
          retained.put(assignment, java.lang.Boolean.TRUE)
        }
      }
      retained
    }

    private def isExactRetainedValueEvidenceAssignment(
        assignment: DataAssignmentStatement
    ): Boolean =
      assignment != null &&
        exactRetainedValueEvidenceAssignments.containsKey(assignment)

    private def isExactPackedVecFiniteCarrier(baseType: BaseType): Boolean =
      baseType != null && exactPackedVecFiniteCarriers.containsKey(baseType)

    private def isExactDynamicVecDecoder(baseType: BaseType): Boolean =
      baseType != null && exactDynamicVecDecoders.containsKey(baseType)

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

    private lazy val normalizedTypedResizeBoundaries =
      assignments.toVector.flatMap { assignment =>
        assignment.target match {
          case target: UInt if assignment.target == assignment.finalTarget =>
            ExternalParameterizedAutoResize
              .normalizedTypedUIntResizeBoundary(
                component,
                assignment,
                target
              )
              .toVector
          case _ => Vector.empty
        }
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

    lazy val parameters: Vector[ElaborationIntegerParameter] = {
      val normalizedResizeExpressions =
        normalizedTypedResizeBoundaries.flatMap { boundary =>
          Vector(boundary.sourceWidth, boundary.targetWidth)
        }
      val referenced =
        symbolicDeclarationWidths.flatMap(_._2.parameters) ++
          normalizedResizeExpressions.flatMap(_.parameters) ++
          hierarchyParameters
      val retainedRoots =
        symbolicDeclarationWidths.flatMap(_._2.parameterRoots) ++
          normalizedResizeExpressions.flatMap(_.parameterRoots)
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
      val hasNativeInput = ports.exists(port => port.isInput && !port.isOutput && !port.isInOut)
      val hasNativeOutput = ports.exists(port => port.isOutput && !port.isInput && !port.isInOut)
      val ordinaryPortSurface = hasNativeInput && hasNativeOutput
      val exactStructuralVecOutputSurface =
        !hasParameterizedHierarchy &&
          ParameterizedVerilogVecs.isExactStructuralOutputSurface(
            component,
            ports.toVector
          )
      if (!ordinaryPortSurface && !exactStructuralVecOutputSurface) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-PORT-DIRECTIONS-UNSUPPORTED",
          s"component '${component.definitionName}' must expose at least one native input and one native output, or one exact output-only finite structural typed-Vec surface"
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
              // Typed Vec packing records only statements whose source shape
              // and native carrier layout are checked by exact identity in the
              // Vec backend. Recognize that complete evidence set before
              // asking this generic pass to reinterpret finite witness nodes.
              if (
                !isExactPackedVecEvidenceAssignment(assignment) &&
                !isExactDynamicVecDecoderAssignment(assignment) &&
                !isExactRetainedValueEvidenceAssignment(assignment)
              ) {
                val targetWidth = widthInference.ofBase(target)
                val sourceWidth = widthInference.ofExpression(assignment.source)
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
                val provenCompleteDomainEquivalent =
                  isProvenCompleteDomainWidthEquivalence(
                    targetWidth,
                    sourceWidth
                  )
                val provenInvariantZero =
                  targetWidth.isSymbolic && targetWidth.minimum > 0 &&
                    isInvariantZero(assignment.source)
                val provenInvariantTargetWidth =
                  targetWidth.isSymbolic &&
                    targetWidth.minimum == targetWidth.maximum &&
                    sourceWidth.default == targetWidth.default
                if (
                  targetWidth.isSymbolic && sourceWidth.isSymbolic &&
                  !equivalentWidthExpression(targetWidth, sourceWidth) &&
                  !provenAutoResize && !provenModularUpdate &&
                  !provenCapturedDomainEquivalent &&
                  !provenCompleteDomainEquivalent
                ) {
                  fail(
                    "SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-WIDTH-MISMATCH",
                    s"assignment to '${target.getName()}' crosses symbolic width expressions '${targetWidth.render}' and '${sourceWidth.render}'",
                    ParameterizedWidth.sourceLocationOf(target)
                  )
                }
                if (
                  targetWidth.isSymbolic && !sourceWidth.isSymbolic &&
                  !isUnfixedLiteral(assignment.source) &&
                  !provenAutoResize && !provenModularUpdate &&
                  !provenInvariantTargetWidth &&
                  !provenCapturedDomainEquivalent &&
                  !provenInvariantZero
                ) {
                  fail(
                    "SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-WIDTH-MISMATCH",
                    s"assignment to symbolic signal '${target.getName()}' uses concrete-width expression ${sourceWidth.render}; explicit domain-safe conversion is required",
                    ParameterizedWidth.sourceLocationOf(target)
                  )
                }
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

    /** Prove unsigned narrowing/equality over the exact structural owner of a
      * captured assignment. Missing, non-positive or growing points all fail
      * closed; equality at the concrete witness is not sufficient.
      */
    private def isProvenCapturedDomainWidthNarrowOrEqual(
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
            target.exists(targetValue =>
              targetValue > 0 &&
                source.exists(sourceValue =>
                  sourceValue > 0 && targetValue <= sourceValue
                )
            )
          }
        }
    }

    /** Prove width equality over one complete typed root without correlating
      * independently sourced equal-schema domains. WidthSelect represents the
      * maximum/minimum across alternative assignment widths; disjoint partial
      * exact tables therefore contribute only where each arm has evidence.
      */
    private def isProvenCompleteDomainWidthEquivalence(
        left: WidthExpr,
        right: WidthExpr
    ): Boolean =
      isProvenCompleteDomainWidthRelation(left, right)(_ == _)

    /** Prove unsigned narrowing/equality over one complete typed root. */
    private def isProvenCompleteDomainWidthNarrowOrEqual(
        target: WidthExpr,
        source: WidthExpr
    ): Boolean =
      isProvenCompleteDomainWidthRelation(target, source)(_ <= _)

    private def isProvenCompleteDomainWidthRelation(
        left: WidthExpr,
        right: WidthExpr
    )(
        relation: (BigInt, BigInt) => Boolean
    ): Boolean = {
      def domainsOf(
          expression: WidthExpr
      ): Vector[ElaborationExactDomain[BigInt]] = expression match {
        case retained: WidthRetained => retained.exactDomain.toVector
        case binary: WidthBinary =>
          domainsOf(binary.left) ++ domainsOf(binary.right)
        case select: WidthSelect =>
          domainsOf(select.whenTrue) ++ domainsOf(select.whenFalse)
        case _ => Vector.empty
      }

      val domains = domainsOf(left) ++ domainsOf(right)
      if (domains.isEmpty) return false
      val roots = domains.foldLeft(
        Vector.empty[ElaborationIntegerParameterRoot]
      ) { (known, domain) =>
        if (known.exists(_ eq domain.root)) known else known :+ domain.root
      }
      if (roots.size != 1) return false
      val universe = domains.head.universe
      if (universe.isEmpty || domains.exists(_.universe != universe))
        return false
      val root = roots.head

      def evaluate(
          expression: WidthExpr,
          rootValue: BigInt
      ): Option[BigInt] = expression match {
        case WidthLiteral(value) => Some(value)
        case retained: WidthRetained =>
          retained.exactDomain
            .filter(domain => domain.root eq root)
            .flatMap(_.evaluate(rootValue))
        case binary: WidthBinary =>
          for {
            l <- evaluate(binary.left, rootValue)
            r <- evaluate(binary.right, rootValue)
            result <- binary.operator match {
              case "+" => Some(l + r)
              case "-" => Some(l - r)
              case "*" => Some(l * r)
              case _   => None
            }
          } yield result
        case select: WidthSelect =>
          (evaluate(select.whenTrue, rootValue), evaluate(select.whenFalse, rootValue)) match {
            case (Some(l), Some(r))  => Some(select.selection.select(l, r))
            case (Some(value), None) => Some(value)
            case (None, Some(value)) => Some(value)
            case _                   => None
          }
        case _ => None
      }

      universe.forall { rootValue =>
        val l = evaluate(left, rootValue)
        val r = evaluate(right, rootValue)
        l.exists(leftValue =>
          leftValue > 0 &&
            r.exists(rightValue =>
              rightValue > 0 && relation(leftValue, rightValue)
            )
        )
      }
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
        val value = retainedWidthExpression(expression)
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
            root.elaborationRoot.flatMap(elaborationRoot =>
              ElabInt.evaluateExact(origin, elaborationRoot, value)
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
        declaration match {
          case bits: Bits if ParameterizedVec.packedShapeOf(bits).nonEmpty =>
            // The exact packed-result identity already carries its complete
            // factorized Vec geometry.  Its width may intentionally combine
            // independent element-width and depth roots, so it is not a
            // single-owner structural projection.
            return None
          case _ =>
        }
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
              exactPackedVecWidth(baseType)
                .orElse {
                  if (
                    isExactPackedVecFiniteCarrier(baseType) ||
                    isExactDynamicVecDecoder(baseType)
                  )
                    Some(WidthLiteral(baseType.getBitsWidth))
                  else None
                }
                .getOrElse {
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
                      val witnessInactive = ParameterizedStructure
                        .capturedWitnessInactiveDataAssignmentsOf(component)
                        .exists(_ eq driver)
                      val exactBoundary = ExternalParameterizedAutoResize
                        .targetOfResizeSource(component, baseType)
                        .nonEmpty
                      val fixedWitnessSafe = witnessInactive && exactBoundary &&
                        ParameterizedStructure
                          .capturedAssignmentDomainOf(component, driver)
                          .exists { domain =>
                            domain.values.forall { value =>
                              evaluate(sourceWidth, domain.root, value)
                                .exists(width =>
                                  width > 0 &&
                                    width <= BigInt(baseType.getBitsWidth)
                                )
                            }
                          }
                      // A witness-inactive `.resized` clone is emitted only
                      // inside its exact captured owner.  Keep its concrete
                      // carrier width when the exhaustive owner domain proves
                      // that the original source can only be zero-extended,
                      // never truncated, before the exact outer Resize.
                      if (fixedWitnessSafe)
                        WidthLiteral(baseType.getBitsWidth)
                      else sourceWidth
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
                }
            activeBases -= baseType
            baseCache(baseType) = result
            result
        }
      }

      /** Recover only the exact logical width retained on Vec.asBits.  The
        * finite carrier Resize beneath this value is an audited implementation
        * detail and can cross the public width domain by construction.
        */
      private def exactPackedVecWidth(baseType: BaseType): Option[WidthExpr] =
        baseType match {
          case bits: Bits =>
            ParameterizedVec.packedShapeOf(bits).map { shape =>
              // A single-root packed width already has one exact typed
              // expression produced by the core Vec algorithm. Prefer that
              // retained identity so ordinary typed widths such as DEPTH and
              // DEPTH * 24 compare through their common exact domain instead
              // of differing only because this fallback re-associated the
              // equivalent factors as DEPTH * 1 or DEPTH * (8 + 8 + 8).
              ParameterizedVec
                .packedWidthExpressionOf(bits)
                .map(retained)
                .getOrElse {
                  // Independently rooted element-width and depth expressions
                  // cannot form one core exact-domain expression. Preserve
                  // their factorized geometry directly for that case.
                  val elementWidth = shape.elementLeaves
                    .map(leaf => retained(leaf.width))
                    .foldLeft[WidthExpr](WidthLiteral(0))(widthAdd)
                  widthMultiply(retained(shape.depth), elementWidth)
                }
            }
          case _ => None
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
        val fixedTypedResizeConsumer = bitVector match {
          case uint: UInt if fullAssignments.size == 1 =>
            ExternalParameterizedAutoResize
              .preservesFixedTypedResizeConsumer(
                component,
                fullAssignments.head,
                uint
              )
          case _ => false
        }
        if (provenAutoResizeBoundary || fixedTypedResizeConsumer)
          WidthLiteral(bitVector.getBitsWidth)
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
          retainedResizeExpression(resize).foreach { retained =>
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-NESTED-TYPED-RESIZE-UNSUPPORTED",
              s"nested typed Resize target '${retained.verilog}' has no reviewed native-expression reconstruction; assign the resize to an explicit carrier first",
              retained.sourceLocation
            )
          }
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
          ParameterizedMemory.metadataOf(port.mem) match {
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

      /** Classify one explicit symbolic Resize against the complete source
        * geometry.  Bounds prove a relation only when their Cartesian product
        * cannot cross.  Overlapping same-root widths instead require the exact
        * retained evaluation tables; equal parameter names or witnesses never
        * establish correlation.
        */
      private def validateRetainedResizeDomain(
          resize: Resize,
          target: WidthExpr,
          source: WidthExpr,
          sourceLocation: Option[String]
      ): Unit = {
        if (source.default != BigInt(resize.input.getWidth)) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-RESIZE-SOURCE-WITNESS-MISMATCH",
            s"native Resize input has ${resize.input.getWidth} bits, but its retained width '${source.render}' has default ${source.default}",
            sourceLocation
          )
        }
        if (source.minimum < 1 || target.minimum < 1) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-RESIZE-DOMAIN-NONPOSITIVE",
            s"resize from '${source.render}' in [${source.minimum}, ${source.maximum}] to '${target.render}' in [${target.minimum}, ${target.maximum}] must stay positive over its complete legal domain",
            sourceLocation
          )
        }

        // 60e's exact native SInt occurrence sizes both the sign replication
        // and the selected payload symbolically. No witness relation or LHS
        // extension is needed, including domains that cross equality.
        if (morphhdl.MorphSignedCasts.isEnabled(pc.config) &&
            resize.getClass == classOf[ResizeSInt]) return

        val exactComparisons = (target, source) match {
          case (left: WidthRetained, right: WidthRetained) =>
            (left.exactDomain, right.exactDomain) match {
              case (Some(l), Some(r))
                  if (l.root eq r.root) &&
                    l.parameter == r.parameter &&
                    l.evidenceValues == r.evidenceValues &&
                    l.evidenceValues.nonEmpty &&
                    (left.projection == right.projection ||
                      (l.evidenceValues == l.universe &&
                        r.evidenceValues == r.universe)) =>
                Some(l.evidenceValues.toVector.sorted.map { rootValue =>
                  val targetValue = l.byRootValue(rootValue)
                  val sourceValue = r.byRootValue(rootValue)
                  targetValue.compare(sourceValue)
                })
              case _ => None
            }
          case _ => None
        }

        val (alwaysNarrowOrEqual, alwaysGrowOrEqual) =
          exactComparisons match {
            case Some(comparisons) =>
              comparisons.forall(_ <= 0) -> comparisons.forall(_ >= 0)
            case None =>
              (target.maximum <= source.minimum) ->
                (target.minimum >= source.maximum)
          }

        if (!alwaysNarrowOrEqual && !alwaysGrowOrEqual) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-RESIZE-DOMAIN-CROSSING-UNSUPPORTED",
            s"resize from '${source.render}' in [${source.minimum}, ${source.maximum}] to '${target.render}' in [${target.minimum}, ${target.maximum}] crosses narrowing and widening over its complete legal domain",
            sourceLocation
          )
        }

        val hasStrictGrowth = exactComparisons match {
          case Some(comparisons) => comparisons.exists(_ > 0)
          case None              => target.maximum > source.minimum
        }
        if (resize.getTypeObject == TypeSInt && alwaysGrowOrEqual && hasStrictGrowth) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-SIGNED-RESIZE-GROW-DOMAIN-UNSUPPORTED",
            s"signed resize from '${source.render}' to '${target.render}' grows over its complete legal domain, but the native witness renderer freezes the source sign-bit index",
            sourceLocation
          )
        }
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
        retainedExpression.foreach { target =>
          validateRetainedResizeDomain(
            resize,
            target,
            ofExpression(resize.input),
            retainedResizeExpression(resize).flatMap(_.sourceLocation)
          )
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
              val exactNativeBoundary = resize.input match {
                case source: BaseType =>
                  ExternalParameterizedAutoResize
                    .targetOfResizeSource(component, source)
                    .exists(_ eq target)
                case _ => false
              }
              if (!exactNativeBoundary) None
              else {
                val alwaysNarrowOrEqual =
                  isProvenCapturedDomainWidthNarrowOrEqual(
                    assignment,
                    targetWidth,
                    inputWidth
                  ) ||
                    isProvenCompleteDomainWidthNarrowOrEqual(
                      targetWidth,
                      inputWidth
                    )
                if (alwaysNarrowOrEqual) Some(targetWidth) else None
              }
            }
        retainedExpression
          .orElse(capturedAutoResizeTarget)
          .orElse(retainedTarget.map(target => ofBase(target)))
          .getOrElse {
            val source = ofExpression(resize.input)
            val size = BigInt(resize.size)
            if (!source.isSymbolic) WidthLiteral(size)
            else if (morphhdl.MorphSignedCasts.isEnabled(pc.config) &&
                resize.getClass == classOf[ResizeSInt] && size > 0 && source.minimum > 0) WidthLiteral(size)
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

  /** Construct the exact retained-width summary used by native width
    * inference. Keeping this conversion outside WidthInference gives the
    * package-local semantic tests a narrow probe without exposing WidthExpr
    * or any publication internals.
    */
  private def retainedWidthExpression(
      expression: ElaborationIntegerExpression
  ): WidthRetained =
    WidthRetained(
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

  /** Package-local semantic boundary for adversarial retained-expression
    * tests. Production assignment analysis reaches the same comparator through
    * WidthInference.retained.
    */
  private[internals] def equivalentRetainedWidthExpressions(
      left: ElaborationIntegerExpression,
      right: ElaborationIntegerExpression
  ): Boolean =
    equivalentWidthExpression(
      retainedWidthExpression(left),
      retainedWidthExpression(right)
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
        l.default == r.default &&
        l.minimum == r.minimum &&
        l.maximum == r.maximum &&
        l.parameters == r.parameters &&
        l.parameterRoots.size == r.parameterRoots.size &&
        l.parameterRoots.zip(r.parameterRoots).forall { case (leftRoot, rightRoot) =>
          leftRoot eq rightRoot
        } &&
        exactWidthDomainEquivalent(l.exactDomain, r.exactDomain) &&
        // Projection provenance remains authoritative for a partial active
        // domain.  A full-domain expression may legitimately lose only that
        // redundant slot while passing through native algebra (for example
        // DEPTH versus 1 * DEPTH), because the exhaustive exact table already
        // proves every admitted root value.
        (l.projection == r.projection ||
          (completeWidthDomain(l.exactDomain) &&
            completeWidthDomain(r.exactDomain)))

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

  private def completeWidthDomain(
      domain: Option[ElaborationExactDomain[BigInt]]
  ): Boolean =
    domain.exists(value => value.evidenceValues == value.universe)

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
