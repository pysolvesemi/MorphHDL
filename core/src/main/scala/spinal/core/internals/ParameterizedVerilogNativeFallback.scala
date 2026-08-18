package spinal.core.internals

import java.util.regex.{Matcher, Pattern}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import spinal.core._

/**
  * Generic parameterized-Verilog lowering for the Increment 34 expression and
  * process surface.
  *
  * The existing ComponentEmitterVerilog remains authoritative for ordinary
  * expression and process syntax. This helper is used only when the narrower
  * Increment 30 direct-assignment gate rejects an otherwise valid ordinary
  * SpinalHDL graph. It validates retained widths and controls, asks the normal
  * emitter for Verilog-2001, then substitutes the public parameter header and
  * packed declaration ranges. No fixture-specific ParamRTL graph is involved.
  */
private[internals] object ParameterizedVerilogNativeFallback {
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
          ParameterizedMemory.parametersOf(component).nonEmpty ||
          ParameterizedProcess.parametersOf(component).nonEmpty ||
          ParameterizedStructure.parametersOf(component).nonEmpty ||
          component.children.exists(
            child => ParameterizedWidth.parametersOf(child).nonEmpty
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
    val hierarchy = ParameterizedVerilogHierarchy.analyze(component, pc, canonicalOf)
    val analysis = new Analysis(
      component,
      pc,
      hierarchy.parameters ++
        ParameterizedMemory.parametersOf(component) ++
        ParameterizedStructure.parametersOf(component) ++
        ParameterizedProcess.parametersOf(component),
      hierarchy.hasParameterizedInstances
    )
    analysis.validate()

    val withHeader =
      if (analysis.parameters.isEmpty) verilog
      else {
        val modulePattern =
          ("(?m)^module\\s+" + Pattern.quote(component.definitionName) + "\\s*\\(").r
        val rewritten = modulePattern.replaceFirstIn(
          verilog,
          Matcher.quoteReplacement(
            renderHeader(component.definitionName, analysis.parameters)
          )
        )
        if (rewritten == verilog) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-MODULE-HEADER-NOT-FOUND",
            s"normal Verilog emission did not contain the expected module header for '${component.definitionName}'"
          )
        }
        rewritten
      }

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
    withHierarchy
      .split("\\n", -1)
      .map(line => rewriteDeclarationLine(line, widthsByName))
      .mkString("\n")
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

      validateParameters()
      validateWidths()
      validateAssignments()
      validateProcesses()
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
            s"parameter name '${parameter.name}' is not a portable Verilog identifier"
          )
        }
        if (pc.verilogKeywords.contains(parameter.name)) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-NAME-RESERVED",
            s"parameter name '${parameter.name}' is reserved by IEEE 1364"
          )
        }
        if (
          parameter.minimum < 0 || parameter.maximum < parameter.minimum ||
          parameter.default < parameter.minimum || parameter.default > parameter.maximum ||
          parameter.maximum > BigInt(pc.config.bitVectorWidthMax)
        ) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-DOMAIN-INVALID",
            s"parameter '${parameter.name}' must have a non-negative bounded domain no larger than SpinalConfig.bitVectorWidthMax=${pc.config.bitVectorWidthMax}, with its default inside that domain"
          )
        }
        namedDeclarations.get(parameter.name).foreach { signal =>
          fail(
            if (signal.isIo)
              "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-PORT-NAME-COLLISION"
            else "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-SIGNAL-NAME-COLLISION",
            s"parameter '${parameter.name}' collides with signal '${signal.getName()}' of component '${component.definitionName}'"
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
              if (targetWidth.isSymbolic && sourceWidth.isSymbolic && targetWidth != sourceWidth) {
                fail(
                  "SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-WIDTH-MISMATCH",
                  s"assignment to '${target.getName()}' crosses symbolic width expressions '${targetWidth.render}' and '${sourceWidth.render}'",
                  ParameterizedWidth.sourceLocationOf(target)
                )
              }
              if (
                targetWidth.isSymbolic && !sourceWidth.isSymbolic &&
                !isUnfixedLiteral(assignment.source)
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
            val result = ParameterizedWidth.expressionOf(baseType) match {
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
          ParameterizedMemory.metadataOf(port.mem) match {
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
