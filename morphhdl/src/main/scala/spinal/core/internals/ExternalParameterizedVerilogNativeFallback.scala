package spinal.core.internals

import java.util.IdentityHashMap
import java.util.regex.Pattern

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import spinal.core._

/**
  * MorphHDL-owned external parameterized-Verilog lowering for ordinary
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
          component.children.exists(
            child =>
              ParameterizedWidth.parametersOf(child).nonEmpty ||
                ExternalParameterizedMemoryRegistry.parametersOf(child).nonEmpty ||
                ExternalParameterizedValueRegistry.parametersOf(child).nonEmpty ||
                ParameterizedStructure.parametersOf(child).nonEmpty ||
                ParameterizedProcess.parametersOf(child).nonEmpty
          )
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
      else ensureParameterHeader(
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
    groupedWidths.collectFirst {
      case (name, values) if values.map(_._2).distinct.size != 1 => name
    }.foreach { name =>
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
    val canonical =
      if (isCanonicalDirectSurface(component))
        canonicalizeDeclarations(component, rewrittenValues)
      else rewrittenValues
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
        val close = (start + 1 until lines.size).find(index => lines(index).trim == ") (")
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
            s"module '$definitionName' emitted parameter schema ${existingMap.toVector.sortBy(_._1).mkString(",")}, expected ${expectedMap.toVector.sortBy(_._1).mkString(",")}"
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
    val declarations = parameters.zipWithIndex.map { case (parameter, index) =>
      val comma = if (index == parameters.size - 1) "" else ","
      s"  parameter integer ${parameter.name} = ${parameter.default}$comma"
    }.mkString("\n")
    s"module $definitionName #(\n$declarations\n) ("
  }

  private val NativeAddressWidthHelper = "morphhdl_address_width"
  private val NativeCeilLog2Helper = "morphhdl_ceil_log2"
  private val VerilogPreprocessorDirectives = Set(
    "begin_keywords",
    "celldefine",
    "default_nettype",
    "define",
    "else",
    "elsif",
    "end_keywords",
    "endcelldefine",
    "endif",
    "ifdef",
    "ifndef",
    "include",
    "line",
    "nounconnected_drive",
    "pragma",
    "resetall",
    "timescale",
    "unconnected_drive",
    "undef"
  )
  private val VerilogMacroNameDirectives =
    Set("define", "undef", "ifdef", "ifndef", "elsif")

  private final case class VerilogIdentifierToken(
      name: String,
      start: Int,
      end: Int,
      escaped: Boolean
  )

  private final case class VerilogNamedDeclaration(
      kind: String,
      name: String,
      nameStart: Int,
      headerBoundary: Int
  )

  private final case class VerilogFunctionDefinition(
      name: String,
      nameStart: Int,
      start: Int,
      end: Int
  )

  private final case class VerilogLexicalView(
      value: String,
      identifiers: Vector[VerilogIdentifierToken],
      activeCharacters: scala.collection.immutable.BitSet,
      opaqueExpressionStarts: scala.collection.immutable.BitSet,
      closingParenthesis: Map[Int, Int],
      reservedIdentifiers: Set[String],
      declarations: Vector[VerilogNamedDeclaration],
      functions: Vector[VerilogFunctionDefinition]
  ) {
    private val declarationNameStarts = declarations.map(_.nameStart).toSet

    def nextActiveNonWhitespace(from: Int): Option[Int] = {
      var index = math.max(0, from)
      while (index < value.length) {
        if (activeCharacters(index) && !value.charAt(index).isWhitespace)
          return Some(index)
        index += 1
      }
      None
    }

    def previousActiveNonWhitespace(from: Int): Option[Int] = {
      var index = math.min(from, value.length - 1)
      while (index >= 0) {
        if (activeCharacters(index) && !value.charAt(index).isWhitespace)
          return Some(index)
        index -= 1
      }
      None
    }

    def isDeclarationName(token: VerilogIdentifierToken): Boolean =
      declarationNameStarts(token.start)

  }

  private final case class NativeIntegerHelperCall(
      name: String,
      nameStart: Int,
      nameEnd: Int,
      openParenthesis: Int,
      escaped: Boolean,
      closeParenthesis: Option[Int]
  )

  private final case class TextEdit(
      start: Int,
      end: Int,
      replacement: String
  )

  /**
    * Compiler-shadow helper names are an internal expression IR, not Verilog
    * functions. Lower the reviewed positive-width helpers after every other
    * native rewrite so declarations, structural alternatives and memories all
    * share one collision-safe IEEE-1364 implementation.
    */
  private[internals] def lowerRetainedIntegerHelpers(
      verilog: String,
      definitionName: String
  ): String = {
    val lexical = lexVerilog(verilog, definitionName)
    val calls = nativeIntegerHelperCalls(lexical)
    val declaredNames = lexical.declarations.map(_.name).toSet
    val protectedNames = lexical.reservedIdentifiers ++
      lexical.identifiers.filter(_.escaped).map(_.name)

    calls.collectFirst {
      case call
          if call.escaped && !declaredNames(call.name) => call
    }.foreach { call =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-ESCAPED-UNSUPPORTED",
        s"module '$definitionName' uses escaped helper-like call '\\${call.name}', which cannot be compiler-owned retained native Int IR"
      )
    }

    calls.collectFirst {
      case call
          if (declaredNames(call.name) || protectedNames(call.name)) &&
            (call.name == NativeAddressWidthHelper ||
              call.name == NativeCeilLog2Helper) =>
        call.name -> declaredNames(call.name)
    }.foreach { case (name, declared) =>
      val role =
        if (declared) "declares user function, task or module"
        else "reserves user or preprocessor identifier"
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-NAME-COLLISION",
        s"module '$definitionName' $role '$name', which collides with retained native Int helper IR"
      )
    }

    val internalCalls = calls.filter(call =>
      !call.escaped && !declaredNames(call.name)
    )
    internalCalls.collectFirst {
      case call
          if call.name != NativeAddressWidthHelper &&
            call.name != NativeCeilLog2Helper => call
    }.foreach { call =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-UNSUPPORTED",
        s"module '$definitionName' retains unsupported native Int helper '${call.name}('"
      )
    }
    val supportedCalls = internalCalls.filter(call =>
      call.name == NativeAddressWidthHelper ||
        call.name == NativeCeilLog2Helper
    )
    if (supportedCalls.isEmpty) return verilog

    supportedCalls.collectFirst {
      case call if call.closeParenthesis.isEmpty => call
    }.foreach { call =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-MALFORMED",
        s"module '$definitionName' contains an unterminated call to '${call.name}'"
      )
    }
    supportedCalls.foreach(call =>
      validateNativeUnaryHelperCall(lexical, call, definitionName)
    )

    val portableFunctions = lexical.functions
      .filter(function => portableLogFunction(lexical, function))
    if (portableFunctions.size > 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-LOG-HELPER-AMBIGUOUS",
        s"module '$definitionName' contains multiple portable logarithm helpers: ${portableFunctions.map(_.name).sorted.mkString(", ")}"
      )
    }
    val existingPortableHelpers = portableFunctions.map(_.name)
    val helperName = existingPortableHelpers.headOption.getOrElse {
      firstAvailableIdentifier(
        "clog2",
        lexical.identifiers.map(_.name).toSet ++ lexical.reservedIdentifiers
      )
    }

    val edits = supportedCalls.flatMap { call =>
      val minimum = if (call.name == NativeAddressWidthHelper) 1 else 0
      Vector(
        TextEdit(call.nameStart, call.nameEnd, helperName),
        TextEdit(
          call.closeParenthesis.get,
          call.closeParenthesis.get,
          s", $minimum"
        )
      )
    }
    val lowered = applyTextEdits(verilog, edits, definitionName)
    val remainingLexical = lexVerilog(lowered, definitionName)
    nativeIntegerHelperCalls(remainingLexical)
      .filterNot(call =>
        remainingLexical.declarations.exists(_.name == call.name) ||
          call.escaped
      )
      .headOption
      .foreach { helper =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-UNSUPPORTED",
          s"module '$definitionName' retains unsupported native Int helper '${helper.name}('"
        )
      }

    if (existingPortableHelpers.nonEmpty) lowered
    else insertPortableLogFunction(lowered, definitionName, helperName)
  }

  private def lexVerilog(
      value: String,
      definitionName: String
  ): VerilogLexicalView = {
    val tokens = Vector.newBuilder[VerilogIdentifierToken]
    val active = mutable.BitSet.empty
    val opaqueExpressionStarts = mutable.BitSet.empty
    val reservedIdentifiers = mutable.LinkedHashSet.empty[String]
    val parenthesisStack = mutable.ArrayBuffer.empty[Int]
    val closing = mutable.LinkedHashMap.empty[Int, Int]

    def markActive(start: Int, end: Int): Unit = {
      var cursor = start
      while (cursor < end) {
        active += cursor
        cursor += 1
      }
    }

    def logicalDirectiveEnd(from: Int): Int = {
      var cursor = from
      var complete = false
      while (cursor < value.length && !complete) {
        val newline = value.indexOf('\n', cursor)
        if (newline < 0) cursor = value.length
        else {
          var previous = newline - 1
          if (previous >= cursor && value.charAt(previous) == '\r') previous -= 1
          if (previous >= cursor && value.charAt(previous) == '\\') {
            if (newline + 1 >= value.length) {
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-LEXICAL-ERROR",
                s"module '$definitionName' contains an incomplete continued preprocessor directive"
              )
            }
            cursor = newline + 1
          } else {
            cursor = newline
            complete = true
          }
        }
      }
      cursor
    }

    def reserveDirectiveMacroName(from: Int, until: Int): Unit = {
      var cursor = from
      while (cursor < until && value.charAt(cursor).isWhitespace) cursor += 1
      if (cursor < until && value.charAt(cursor) == '\\') {
        cursor += 1
        val start = cursor
        while (cursor < until && !value.charAt(cursor).isWhitespace) cursor += 1
        if (cursor > start) reservedIdentifiers += value.substring(start, cursor)
      } else if (cursor < until && isIdentifierStart(value.charAt(cursor))) {
        val start = cursor
        cursor += 1
        while (cursor < until && isIdentifierCharacter(value.charAt(cursor))) cursor += 1
        reservedIdentifiers += value.substring(start, cursor)
      }
    }

    def consumeMacroInvocation(from: Int, macroStart: Int): Int = {
      var cursor = from
      while (
        cursor < value.length &&
        (value.charAt(cursor) == ' ' || value.charAt(cursor) == '\t' ||
          value.charAt(cursor) == '\r')
      ) cursor += 1
      if (cursor >= value.length || value.charAt(cursor) != '(') return from

      opaqueExpressionStarts += macroStart
      var depth = 0
      var complete = false
      while (cursor < value.length && !complete) {
        val character = value.charAt(cursor)
        if (
          character == '/' && cursor + 1 < value.length &&
          value.charAt(cursor + 1) == '/'
        ) {
          cursor += 2
          while (cursor < value.length && value.charAt(cursor) != '\n') cursor += 1
        } else if (
          character == '/' && cursor + 1 < value.length &&
          value.charAt(cursor + 1) == '*'
        ) {
          cursor += 2
          while (
            cursor + 1 < value.length &&
            !(value.charAt(cursor) == '*' && value.charAt(cursor + 1) == '/')
          ) cursor += 1
          if (cursor + 1 >= value.length) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-LEXICAL-ERROR",
              s"module '$definitionName' contains an unterminated block comment in a macro invocation"
            )
          }
          cursor += 2
        } else if (character == '"') {
          cursor += 1
          var escaped = false
          var stringComplete = false
          while (cursor < value.length && !stringComplete) {
            val current = value.charAt(cursor)
            if (escaped) escaped = false
            else if (current == '\\') escaped = true
            else if (current == '"') stringComplete = true
            cursor += 1
          }
          if (!stringComplete) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-LEXICAL-ERROR",
              s"module '$definitionName' contains an unterminated string in a macro invocation"
            )
          }
        } else if (character == '\\') {
          cursor += 1
          while (cursor < value.length && !value.charAt(cursor).isWhitespace) {
            cursor += 1
          }
        } else {
          if (character == '(') depth += 1
          else if (character == ')') {
            depth -= 1
            if (depth == 0) complete = true
          }
          cursor += 1
        }
      }
      if (!complete) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-LEXICAL-ERROR",
          s"module '$definitionName' contains an unterminated macro invocation"
        )
      }
      cursor
    }

    var index = 0
    while (index < value.length) {
      val character = value.charAt(index)
      if (character == '/' && index + 1 < value.length && value.charAt(index + 1) == '/') {
        index += 2
        while (index < value.length && value.charAt(index) != '\n') index += 1
      } else if (
        character == '/' && index + 1 < value.length && value.charAt(index + 1) == '*'
      ) {
        val commentStart = index
        index += 2
        while (
          index + 1 < value.length &&
          !(value.charAt(index) == '*' && value.charAt(index + 1) == '/')
        ) index += 1
        if (index + 1 >= value.length) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-LEXICAL-ERROR",
            s"module '$definitionName' contains an unterminated block comment at offset $commentStart"
          )
        }
        index += 2
      } else if (character == '"') {
        val stringStart = index
        opaqueExpressionStarts += stringStart
        index += 1
        var escaped = false
        var complete = false
        while (index < value.length && !complete) {
          val current = value.charAt(index)
          if (escaped) escaped = false
          else if (current == '\\') escaped = true
          else if (current == '"') complete = true
          index += 1
        }
        if (!complete) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-LEXICAL-ERROR",
            s"module '$definitionName' contains an unterminated string literal at offset $stringStart"
          )
        }
      } else if (character == '\\') {
        val start = index
        index += 1
        val nameStart = index
        while (index < value.length && !value.charAt(index).isWhitespace) index += 1
        if (index == nameStart) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-LEXICAL-ERROR",
            s"module '$definitionName' contains an empty escaped identifier at offset $start"
          )
        }
        opaqueExpressionStarts += start
        tokens += VerilogIdentifierToken(
          value.substring(nameStart, index),
          start,
          index,
          escaped = true
        )
      } else if (character == '`') {
        val macroStart = index
        val nameStart = index + 1
        var nameEnd = nameStart
        while (
          nameEnd < value.length &&
          isIdentifierCharacter(value.charAt(nameEnd))
        ) nameEnd += 1
        if (nameEnd == nameStart) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-LEXICAL-ERROR",
            s"module '$definitionName' contains an empty preprocessor identifier at offset $macroStart"
          )
        }
        val name = value.substring(nameStart, nameEnd)
        if (VerilogPreprocessorDirectives(name)) {
          val end = logicalDirectiveEnd(nameEnd)
          if (VerilogMacroNameDirectives(name)) {
            reserveDirectiveMacroName(nameEnd, end)
          }
          index = end
        } else {
          reservedIdentifiers += name
          opaqueExpressionStarts += macroStart
          index = consumeMacroInvocation(nameEnd, macroStart)
        }
      } else if (character == '$') {
        val start = index
        index += 1
        while (index < value.length && isIdentifierCharacter(value.charAt(index))) index += 1
        markActive(start, index)
      } else if (isIdentifierStart(character)) {
        val start = index
        index += 1
        while (index < value.length && isIdentifierCharacter(value.charAt(index))) index += 1
        markActive(start, index)
        tokens += VerilogIdentifierToken(
          value.substring(start, index),
          start,
          index,
          escaped = false
        )
      } else {
        active += index
        if (character == '(') parenthesisStack += index
        else if (character == ')' && parenthesisStack.nonEmpty) {
          val open = parenthesisStack.remove(parenthesisStack.size - 1)
          closing(open) = index
        }
        index += 1
      }
    }

    val tokenVector = tokens.result()
    val activeSet = scala.collection.immutable.BitSet(active.toSeq: _*)
    val opaqueSet = scala.collection.immutable.BitSet(
      opaqueExpressionStarts.toSeq: _*
    )
    val provisional = VerilogLexicalView(
      value,
      tokenVector,
      activeSet,
      opaqueSet,
      closing.toMap,
      reservedIdentifiers.toSet,
      Vector.empty,
      Vector.empty
    )
    val (declarations, functions) = namedDeclarations(provisional)
    provisional.copy(declarations = declarations, functions = functions)
  }

  private def namedDeclarations(
      lexical: VerilogLexicalView
  ): (Vector[VerilogNamedDeclaration], Vector[VerilogFunctionDefinition]) = {
    val tokens = lexical.identifiers
    val declarations = Vector.newBuilder[VerilogNamedDeclaration]
    val functions = Vector.newBuilder[VerilogFunctionDefinition]
    tokens.zipWithIndex.foreach { case (token, tokenIndex) =>
      val declarationKind =
        if (token.escaped) None
        else token.name match {
          case "function" => Some("function")
          case "module"   => Some("module")
          case "task"     => Some("task")
          case _          => None
        }
      declarationKind match {
        case None =>
        case Some(kind) =>
          val boundary = declarationHeaderBoundary(
            lexical,
            token.end,
            kind
          )
          val headerTokens = tokens
            .drop(tokenIndex + 1)
            .takeWhile(_.start < boundary)
          headerTokens.lastOption.foreach { nameToken =>
            declarations += VerilogNamedDeclaration(
              kind,
              nameToken.name,
              nameToken.start,
              boundary
            )
            if (kind == "function") {
              val endToken = tokens
                .drop(tokenIndex + 1)
                .find(candidate =>
                  !candidate.escaped &&
                    candidate.name == "endfunction" &&
                    candidate.start > boundary
                )
              functions += VerilogFunctionDefinition(
                nameToken.name,
                nameToken.start,
                token.start,
                endToken.map(_.end).getOrElse(boundary + 1)
              )
            }
          }
      }
    }
    declarations.result() -> functions.result()
  }

  private def declarationHeaderBoundary(
      lexical: VerilogLexicalView,
      from: Int,
      kind: String
  ): Int = {
    var cursor = from
    var bracketDepth = 0
    while (cursor < lexical.value.length) {
      if (lexical.activeCharacters(cursor)) {
        lexical.value.charAt(cursor) match {
          case '[' => bracketDepth += 1
          case ']' =>
            bracketDepth -= 1
            if (bracketDepth < 0) {
              fail(
                "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-LEXICAL-ERROR",
                s"$kind declaration contains an unmatched closing packed-range bracket"
              )
            }
          case '(' if bracketDepth == 0 => return cursor
          case ';' if bracketDepth == 0 => return cursor
          case ';' =>
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-LEXICAL-ERROR",
              s"$kind declaration terminates with an unclosed packed range"
            )
          case _                        =>
        }
      }
      cursor += 1
    }
    fail(
      "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-LEXICAL-ERROR",
      s"$kind declaration has no complete header"
    )
  }

  private def nativeIntegerHelperCalls(
      lexical: VerilogLexicalView
  ): Vector[NativeIntegerHelperCall] =
    lexical.identifiers.flatMap { token =>
      if (
        !token.name.startsWith("morphhdl_") ||
        lexical.isDeclarationName(token)
      ) None
      else {
        val qualified = lexical
          .previousActiveNonWhitespace(token.start - 1)
          .exists { index =>
            val character = lexical.value.charAt(index)
            val packageQualified =
              character == ':' && lexical
                .previousActiveNonWhitespace(index - 1)
                .exists(previous => lexical.value.charAt(previous) == ':')
            character == '.' || character == '`' || character == '$' ||
              packageQualified
          }
        if (qualified) None
        else {
          lexical.nextActiveNonWhitespace(token.end) match {
            case Some(open) if lexical.value.charAt(open) == '(' =>
              Some(
                NativeIntegerHelperCall(
                  token.name,
                  token.start,
                  token.end,
                  open,
                  token.escaped,
                  lexical.closingParenthesis.get(open)
                )
              )
            case _ => None
          }
        }
      }
    }

  private def validateNativeUnaryHelperCall(
      lexical: VerilogLexicalView,
      call: NativeIntegerHelperCall,
      definitionName: String
  ): Unit = {
    val close = call.closeParenthesis.get
    var cursor = call.openParenthesis + 1
    val delimiterStack = mutable.ArrayBuffer.empty[Char]
    var topLevelCommas = 0
    var nonempty = false

    def closeDelimiter(expected: Char, actual: Char): Unit = {
      if (delimiterStack.isEmpty || delimiterStack.last != expected) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-MALFORMED",
          s"module '$definitionName' contains crossed or unmatched delimiter '$actual' in call to '${call.name}'"
        )
      }
      delimiterStack.remove(delimiterStack.size - 1)
    }

    while (cursor < close) {
      if (lexical.opaqueExpressionStarts(cursor)) nonempty = true
      if (lexical.activeCharacters(cursor)) {
        val character = lexical.value.charAt(cursor)
        character match {
          case '(' =>
            delimiterStack += '('
          case ')' =>
            closeDelimiter('(', ')')
          case '[' =>
            delimiterStack += '['
          case ']' =>
            closeDelimiter('[', ']')
          case '{' =>
            delimiterStack += '{'
          case '}' =>
            closeDelimiter('{', '}')
          case ',' if delimiterStack.isEmpty =>
            topLevelCommas += 1
          case ','                          => ()
          case value if !value.isWhitespace => nonempty = true
          case _                            =>
        }
      }
      cursor += 1
    }

    if (
      delimiterStack.nonEmpty || !nonempty || topLevelCommas != 0
    ) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-MALFORMED",
        s"module '$definitionName' requires exactly one nonempty top-level argument in call to '${call.name}'"
      )
    }
  }

  private def portableLogFunction(
      lexical: VerilogLexicalView,
      function: VerilogFunctionDefinition
  ): Boolean = {
    val hasImmediateAttribute = lexical
      .previousActiveNonWhitespace(function.start - 1)
      .exists { close =>
        lexical.value.charAt(close) == ')' &&
        lexical
          .previousActiveNonWhitespace(close - 1)
          .exists(star => lexical.value.charAt(star) == '*')
      }
    val actual = lexical.value
      .substring(function.start, function.end)
      .filterNot(_.isWhitespace)
    val expected = renderPortableLogFunction(function.name)
      .mkString("\n")
      .filterNot(_.isWhitespace)
    !hasImmediateAttribute && actual == expected
  }

  private def applyTextEdits(
      value: String,
      edits: Vector[TextEdit],
      definitionName: String
  ): String = {
    val ordered = edits.sortBy(edit => (-edit.start, -edit.end))
    ordered.sliding(2).foreach {
      case Vector(later, earlier) if earlier.end > later.start =>
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-NATIVE-INT-HELPER-EDIT-OVERLAP",
          s"module '$definitionName' produced overlapping retained native Int helper edits"
        )
      case _ =>
    }
    ordered.foldLeft(value) { case (current, edit) =>
      current.substring(0, edit.start) + edit.replacement + current.substring(edit.end)
    }
  }

  private def insertPortableLogFunction(
      verilog: String,
      definitionName: String,
      helperName: String
  ): String = {
    val lexical = lexVerilog(verilog, definitionName)
    val modules = lexical.declarations.filter(declaration =>
      declaration.kind == "module" && declaration.name == definitionName
    )
    if (modules.size != 1) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MODULE-HEADER-NOT-FOUND",
        s"normal Verilog emission contains ${modules.size} active module headers for '$definitionName'"
      )
    }
    val headerEnd = moduleHeaderEnd(lexical, modules.head, definitionName)
    verilog.substring(0, headerEnd) +
      "\n\n" + renderPortableLogFunction(helperName).mkString("\n") + "\n" +
      verilog.substring(headerEnd)
  }

  private def moduleHeaderEnd(
      lexical: VerilogLexicalView,
      declaration: VerilogNamedDeclaration,
      definitionName: String
  ): Int = {
    def malformed(detail: String): Nothing =
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MODULE-HEADER-NOT-FOUND",
        s"normal Verilog emission did not contain a complete active module header for '$definitionName': $detail"
      )

    def next(from: Int): Int =
      lexical.nextActiveNonWhitespace(from).getOrElse {
        malformed("unexpected end of input")
      }

    def close(open: Int, role: String): Int =
      lexical.closingParenthesis.getOrElse(
        open,
        malformed(s"unterminated $role list")
      )

    var cursor = declaration.headerBoundary
    lexical.value.charAt(cursor) match {
      case ';' => cursor + 1
      case '(' =>
        val before = lexical
          .previousActiveNonWhitespace(cursor - 1)
          .map(lexical.value.charAt)
        if (before.contains('#')) {
          cursor = next(close(cursor, "parameter") + 1)
          if (lexical.value.charAt(cursor) == ';') return cursor + 1
          if (lexical.value.charAt(cursor) != '(')
            malformed("parameter list is not followed by a port list")
        }
        cursor = close(cursor, "port") + 1
        val terminator = next(cursor)
        if (lexical.value.charAt(terminator) != ';')
          malformed("port list is not terminated by a semicolon")
        terminator + 1
      case _ => malformed("unsupported header boundary")
    }
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

  private def isIdentifierStart(value: Char): Boolean =
    value.isLetter || value == '_'

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

  /**
    * Replace only the concrete witness assignment of compiler-created UInt
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
    named.groupBy(_._1).collectFirst {
      case (name, values) if values.map(_._2).distinct.size != 1 => name
    }.foreach { name =>
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-VALUE-NAME-CONFLICT",
        s"multiple retained native UInt carriers resolved to emitted name '$name'"
      )
    }

    var lines = verilog.split("\n", -1).toVector
    named.distinct.sortBy { case (name, _) => -name.length }.foreach {
      case (name, record) =>
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

  /**
    * Preserve the untouched native full-range Counter boundary comparison when
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
      if (digits.exists(character =>
            character == 'x' || character == 'X' ||
              character == 'z' || character == 'Z')) None
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
      case literalParser(sizeText, _, radix, digits)
          if width.default.isValidInt && width.default > 0 =>
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
      case Some("input") => "input "
      case Some("output") => "output"
      case Some("inout") => "inout "
      case Some(other) => other
      case None => ""
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

  /**
      * Preserve the declaration canonicalization contract of the original
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
        case _: BaseType =>
        case assignment: DataAssignmentStatement => assignments += assignment
        case _ => unsupported = true
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
    val portEnd = (portStart until lines.size).find(lines(_).trim == ");")
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
    val graphPorts = component.getOrdredNodeIo.toVector.filterNot(_.isSuffix)
      .flatMap(port => Option(port.getName()).filter(_.nonEmpty)).toSet
    val missingGraphPorts = graphPorts.diff(parsedPorts.map(_.name).toSet)
    if (missingGraphPorts.nonEmpty) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-EXTERNAL-PORT-LINE-MAPPING-MISSING",
        s"module '${component.definitionName}' has no native declaration for graph ports ${missingGraphPorts.toVector.sorted.mkString(", ")}"
      )
    }
    val orderedPorts = parsedPorts.sortBy { port =>
      val direction = port.direction match {
        case Some("input") => 0
        case Some("output") => 1
        case _ => 2
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

    lazy val symbolicCounterBoundaryWidths
        : Vector[(String, ElaborationIntegerExpression)] =
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
      val grouped = referenced.groupBy(_.name)
      grouped.collectFirst {
        case (name, values) if values.distinct.size != 1 => name
      }.foreach { name =>
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
      // Native memories are validated and canonically lowered after this
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
          if (expression.default != BigInt(bitVector.getBitsWidth)) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-WITNESS-MISMATCH",
              s"signal '${bitVector.getName()}' concrete width ${bitVector.getBitsWidth} does not match inferred width default ${expression.default}",
              ParameterizedWidth.sourceLocationOf(bitVector)
            )
          }
          if (expression.minimum < 1) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-EXPRESSION-DOMAIN-NONPOSITIVE",
              s"signal '${bitVector.getName()}' width expression '${expression.render}' reaches ${expression.minimum}; every declared width must stay positive over the complete parameter domain",
              ParameterizedWidth.sourceLocationOf(bitVector)
            )
          }
          if (expression.maximum > BigInt(pc.config.bitVectorWidthMax)) {
            fail(
              "SPINAL-PARAMETERIZED-VERILOG-EXPRESSION-DOMAIN-TOO-LARGE",
              s"signal '${bitVector.getName()}' width expression '${expression.render}' reaches ${expression.maximum}, above SpinalConfig.bitVectorWidthMax=${pc.config.bitVectorWidthMax}",
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
            case target: BitVector
                if assignment.target == target && assignment.source.isInstanceOf[WidthProvider] =>
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
              if (
                targetWidth.isSymbolic && sourceWidth.isSymbolic &&
                targetWidth != sourceWidth && !nativeCounterNext &&
                !provenAutoResize && !provenModularUpdate
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
                !provenAutoResize && !provenModularUpdate
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

    /**
      * Native Counter elaboration intentionally uses the concrete witness width
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
            expression.parameters.distinct.sortBy(_.name)
          )
          targetWidth == retained &&
          sourceWidth.default == targetWidth.default &&
          sourceWidth.minimum >= targetWidth.minimum &&
          sourceWidth.maximum <= targetWidth.maximum &&
          sourceWidth.parameters.forall(targetWidth.parameters.contains)
        }

    /**
      * Native UInt `.resized` is an explicit whole-target sizing boundary.
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
          targetWidth.isSymbolic &&
          sourceWidth.default == targetWidth.default &&
          ExternalParameterizedAutoResize.proves(component, assignment, uint)
        case _ => false
      }

    private final case class ModularUIntFacts(
        targetReferences: Int,
        booleanValues: Int
    )

    /**
      * A direct unsigned self-update made only from Add/Sub and Boolean values
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
              case operator: Operator.BitVector.Add
                  if operator.getTypeObject == TypeUInt =>
                combine(visit(operator.left), visit(operator.right))
              case operator: Operator.BitVector.Sub
                  if operator.getTypeObject == TypeUInt =>
                combine(visit(operator.left), visit(operator.right))
              case cast: CastBitsToUInt => visit(cast.input)
              case cast: CastUIntToBits => visit(cast.input)
              case _: CastBoolToBits => Some(ModularUIntFacts(0, 1))
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
            case baseType: BaseType
                if baseType.component != null && baseType.component.parent == component =>
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

      def ofBase(baseType: BaseType): WidthExpr = {
        baseCache.get(baseType) match {
          case Some(value) => value
          case None if activeBases.contains(baseType) => WidthLiteral(baseType.getBitsWidth)
          case None =>
            activeBases += baseType
            val result =
              ExternalParameterizedAutoResize
                .targetOfResizeSource(component, baseType)
                .map(ofBase)
                .getOrElse {
                  ParameterizedWidth.expressionOf(baseType) match {
                    case Some(expression) =>
                      WidthRetained(
                        expression.verilog,
                        expression.default,
                        expression.minimum,
                        expression.maximum,
                        expression.parameters.distinct.sortBy(_.name)
                      )
                    case None =>
                      baseType match {
                        case _: Bool => WidthLiteral(1)
                        case bitVector: BitVector => inferUntaggedBitVector(bitVector)
                        case _ => WidthLiteral(baseType.getBitsWidth)
                      }
                  }
                }
            activeBases -= baseType
            baseCache(baseType) = result
            result
        }
      }

      private def inferUntaggedBitVector(bitVector: BitVector): WidthExpr = {
        val fullSources = ArrayBuffer.empty[Expression]
        bitVector.foreachStatements {
          case assignment: DataAssignmentStatement
              if assignment.target == bitVector &&
                assignment.finalTarget == bitVector &&
                !isHierarchyBoundary(assignment) =>
            fullSources += assignment.source
          case _ =>
        }
        val sourceWidths = fullSources.map(ofExpression)
        val symbolicWidths = sourceWidths.filter(_.isSymbolic)
        if (symbolicWidths.isEmpty) WidthLiteral(bitVector.getBitsWidth)
        else symbolicWidths.reduce(widthMax)
      }

      def ofExpression(expression: Expression): WidthExpr = {
        expressionCache.getOrElseUpdate(expression, inferExpression(expression))
      }

      /**
        * Spinal input normalization inserts concrete-witness Resize nodes around
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
        case baseType: BaseType => ofBase(baseType)
        case resize: Resize     => inferResize(resize)
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
        case operator: Operator.Bits.Not => operandWidth(operator.source)
        case operator: Operator.UInt.Not => operandWidth(operator.source)
        case operator: Operator.SInt.Not => operandWidth(operator.source)
        case operator: Operator.SInt.Minus => operandWidth(operator.source)
        case mux: MultiplexerWidthable =>
          mux.inputs.map(operandWidth).reduce(widthMax)
        case mux: BinaryMultiplexerWidthable =>
          widthMax(operandWidth(mux.whenTrue), operandWidth(mux.whenFalse))
        case access: BitVectorRangedAccessFixed => inferFixedRange(access)
        case access: BitVectorRangedAccessFloating => inferFloatingRange(access)
        case access: BitVectorBitAccessFixed => inferFixedBit(access)
        case _: BitVectorBitAccessFloating => WidthLiteral(1)
        case literal: BitVectorLiteral => WidthLiteral(literal.getWidth)
        case _: BoolLiteral            => WidthLiteral(1)
        case port: MemReadSync =>
          ExternalParameterizedMemoryRegistry.metadataOf(port.mem) match {
            case Some(metadata) =>
              WidthRetained(
                metadata.elementWidth.verilog,
                metadata.elementWidth.default,
                metadata.elementWidth.minimum,
                metadata.elementWidth.maximum,
                metadata.elementWidth.parameters.distinct.sortBy(_.name)
              )
            case None => WidthLiteral(port.getWidth)
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

      private def inferResize(resize: Resize): WidthExpr = {
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
    override val precedence: Int = 100
    override val render: String = value.toString
  }

  private final case class WidthParameter(value: ElaborationIntegerParameter)
      extends WidthExpr {
    override val default: BigInt = value.default
    override val minimum: BigInt = value.minimum
    override val maximum: BigInt = value.maximum
    override val parameters: Vector[ElaborationIntegerParameter] = Vector(value)
    override val precedence: Int = 100
    override val render: String = value.name
  }

  private final case class WidthRetained(
      render: String,
      default: BigInt,
      minimum: BigInt,
      maximum: BigInt,
      parameters: Vector[ElaborationIntegerParameter]
  ) extends WidthExpr {
    override val precedence: Int = 100
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

    private def operand(value: WidthExpr, rightOperand: Boolean): String = {
      val needsParentheses =
        value.precedence < precedence ||
          (rightOperand && value.precedence == precedence && !commutative)
      if (needsParentheses) s"(${value.render})" else value.render
    }

    override val render: String =
      s"${operand(left, rightOperand = false)} $operator ${operand(right, rightOperand = true)}"
  }

  private final case class WidthSelect(
      condition: String,
      whenTrue: WidthExpr,
      whenFalse: WidthExpr,
      default: BigInt,
      minimum: BigInt,
      maximum: BigInt
  ) extends WidthExpr {
    override val parameters: Vector[ElaborationIntegerParameter] =
      (whenTrue.parameters ++ whenFalse.parameters).distinct.sortBy(_.name)
    override val precedence: Int = 10
    override val render: String =
      s"$condition ? ${whenTrue.render} : ${whenFalse.render}"
  }

  private def widthAdd(left: WidthExpr, right: WidthExpr): WidthExpr =
    (left, right) match {
      case (WidthLiteral(value), other) if value == 0 => other
      case (other, WidthLiteral(value)) if value == 0 => other
      case (WidthLiteral(x), WidthLiteral(y)) => WidthLiteral(x + y)
      case _ => canonicalBinary(
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
      case _ => WidthBinary(
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
      case (WidthLiteral(value), _) if value == 0 => WidthLiteral(0)
      case (_, WidthLiteral(value)) if value == 0 => WidthLiteral(0)
      case (WidthLiteral(value), other) if value == 1 => other
      case (other, WidthLiteral(value)) if value == 1 => other
      case (WidthLiteral(x), WidthLiteral(y)) => WidthLiteral(x * y)
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
    if (left == right) left
    else {
      (left, right) match {
        case (WidthLiteral(x), WidthLiteral(y)) => WidthLiteral(x.max(y))
        case _ if left.maximum <= right.minimum => right
        case _ if right.maximum <= left.minimum => left
        case _ => WidthSelect(
          s"${left.render} > ${right.render}",
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
    if (left == right) left
    else {
      (left, right) match {
        case (WidthLiteral(x), WidthLiteral(y)) => WidthLiteral(x.min(y))
        case _ if left.maximum <= right.minimum => left
        case _ if right.maximum <= left.minimum => right
        case _ => WidthSelect(
          s"${left.render} < ${right.render}",
          left,
          right,
          left.default.min(right.default),
          left.minimum.min(right.minimum),
          left.maximum.min(right.maximum)
        )
      }
    }
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
