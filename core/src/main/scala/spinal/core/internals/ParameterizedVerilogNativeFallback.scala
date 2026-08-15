package spinal.core.internals

import java.util.regex.{Matcher, Pattern}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import spinal.core._

/**
  * Generic parameterized-Verilog lowering for the bounded Increment 31
  * expression surface.
  *
  * The existing ComponentEmitterVerilog remains authoritative for ordinary
  * expression and process syntax. This helper is used only when the narrower
  * Increment 30 direct-assignment gate rejects an otherwise valid ordinary
  * SpinalHDL graph. It validates symbolic result widths, asks the normal
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
    "SPINAL-PARAMETERIZED-VERILOG-NO-DIRECT-ASSIGNMENTS",
    "SPINAL-PARAMETERIZED-VERILOG-ASSIGNMENT-UNSUPPORTED",
    "SPINAL-PARAMETERIZED-VERILOG-REGISTER-UNSUPPORTED",
    "SPINAL-PARAMETERIZED-VERILOG-REGISTER-PATHS-UNSUPPORTED",
    "SPINAL-PARAMETERIZED-VERILOG-REGISTER-DRIVER-UNSUPPORTED",
    "SPINAL-PARAMETERIZED-VERILOG-MULTIPLE-DRIVERS-UNSUPPORTED",
    "SPINAL-PARAMETERIZED-VERILOG-OUTPUT-DRIVER-UNSUPPORTED"
  )

  def supports(
      failure: ParameterizedVerilogException,
      component: Component
  ): Boolean =
    eligibleGateFailures.contains(failure.code) &&
      ParameterizedWidth.parametersOf(component).nonEmpty &&
      hasPotentialIncrement31Surface(component)

  private def hasPotentialIncrement31Surface(component: Component): Boolean = {
    val declarations = ArrayBuffer.empty[BaseType]
    component.dslBody.walkDeclarations {
      case baseType: BaseType if !baseType.isSuffix => declarations += baseType
      case _                                        =>
    }
    val registers = declarations.distinct.filter(_.isReg).toVector
    if (registers.isEmpty) true
    else {
      val initializedBoolRegisters = registers.collect {
        case value: Bool if hasSingleFalseInit(value) => value
      }
      val symbolicPayloadRegisters = registers.collect {
        case value: BitVector
            if !value.hasInit && ParameterizedWidth.parameterOf(value).nonEmpty => value
      }
      initializedBoolRegisters.size == 1 && symbolicPayloadRegisters.nonEmpty &&
        registers.size == initializedBoolRegisters.size + symbolicPayloadRegisters.size
    }
  }

  private def hasSingleFalseInit(value: Bool): Boolean = {
    var count = 0
    var falseOnly = true
    value.foreachStatements {
      case init: InitAssignmentStatement =>
        count += 1
        init.source match {
          case literal: BoolLiteral if !literal.value =>
          case _                                      => falseOnly = false
        }
      case _ =>
    }
    count == 1 && falseOnly
  }

  def rewrite(component: Component, verilog: String, pc: PhaseContext): String = {
    val analysis = new Analysis(component, pc)
    analysis.validate()

    val modulePattern =
      ("(?m)^module\\s+" + Pattern.quote(component.definitionName) + "\\s*\\(").r
    val withHeader = modulePattern.replaceFirstIn(
      verilog,
      Matcher.quoteReplacement(renderHeader(component.definitionName, analysis.parameters))
    )
    if (withHeader == verilog) {
      fail(
        "SPINAL-PARAMETERIZED-VERILOG-MODULE-HEADER-NOT-FOUND",
        s"normal Verilog emission did not contain the expected module header for '${component.definitionName}'"
      )
    }

    val widthsByName = analysis.symbolicDeclarationWidths.sortBy {
      case (name, _) => -name.length
    }
    withHeader
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
      widthsByName: Vector[(String, WidthExpr)]
  ): String = {
    val trimmed = line.trim
    val declarationLine =
      trimmed.startsWith("input ") || trimmed.startsWith("output ") ||
        trimmed.startsWith("inout ") || trimmed.startsWith("wire ") ||
        trimmed.startsWith("reg ") || trimmed.startsWith("logic ")
    if (!declarationLine) return line

    widthsByName.foldLeft(line) { case (current, (name, expression)) =>
      val quotedName = Pattern.quote(name)
      val packedPattern = ("(\\[[^\\]]+\\])(\\s+)(" + quotedName + ")(?=\\s*[,;])").r
      var replaced = false
      val withRange = packedPattern.replaceAllIn(
        current,
        matched => {
          if (replaced) matched.matched
          else {
            replaced = true
            expression.range + matched.group(2) + matched.group(3)
          }
        }
      )
      if (replaced) withRange
      else {
        val scalarPattern = ("(\\s+)(" + quotedName + ")(?=\\s*[,;])").r
        var inserted = false
        scalarPattern.replaceAllIn(
          withRange,
          matched => {
            if (inserted) matched.matched
            else {
              inserted = true
              matched.group(1) + expression.range + " " + matched.group(2)
            }
          }
        )
      }
    }
  }

  private final class Analysis(component: Component, pc: PhaseContext) {
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
      val referenced = symbolicDeclarationWidths.flatMap(_._2.parameters)
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
      if (component.parent != null || component.children.nonEmpty) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-HIERARCHY-UNSUPPORTED",
          s"component '${component.definitionName}' uses hierarchy before Increment 32 parameter binding"
        )
      }
      if (memories.nonEmpty) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-MEMORY-UNSUPPORTED",
          s"component '${component.definitionName}' uses native memories before Increment 35"
        )
      }
      if (parameters.isEmpty) {
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
          parameter.minimum < 1 || parameter.maximum < parameter.minimum ||
          parameter.default < parameter.minimum || parameter.default > parameter.maximum ||
          parameter.maximum > BigInt(pc.config.bitVectorWidthMax)
        ) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-PARAMETER-DOMAIN-INVALID",
            s"parameter '${parameter.name}' must have a positive non-empty domain bounded by SpinalConfig.bitVectorWidthMax=${pc.config.bitVectorWidthMax}, with its default inside that domain"
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
            if (targetWidth.isSymbolic && !sourceWidth.isSymbolic) {
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

    private def validateProcesses(): Unit = {
      val registers = declarations.distinct.filter(_.isReg).toVector
      if (registers.isEmpty) {
        if (treeStatements.nonEmpty) {
          fail(
            "SPINAL-PARAMETERIZED-VERILOG-PROCESS-UNSUPPORTED",
            "generic conditional/process lowering is deferred to Increment 34; Increment 31 permits tree statements only for the reviewed Stream.m2sPipe library proof"
          )
        }
      } else {
        validateM2sPipeRegisterShape(registers)
      }
    }

    private def validateM2sPipeRegisterShape(registers: Vector[BaseType]): Unit = {
      val initializedBoolRegisters = registers.collect {
        case value: Bool if hasSingleFalseInit(value) => value
      }
      val symbolicPayloadRegisters = registers.collect {
        case value: BitVector
            if !value.hasInit && widthInference.ofBase(value).isSymbolic => value
      }
      val accepted =
        initializedBoolRegisters.size == 1 && symbolicPayloadRegisters.nonEmpty &&
          registers.size == initializedBoolRegisters.size + symbolicPayloadRegisters.size
      if (!accepted) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-SEQUENTIAL-SURFACE-UNSUPPORTED",
          "Increment 31 permits sequential logic only for one real Stream.m2sPipe stage: one false-initialized valid register plus one or more uninitialized symbolic payload registers"
        )
      }

      val clockDomain = registers.head.clockDomain
      val sameClockDomain = registers.forall(_.clockDomain eq clockDomain)
      val directClock =
        clockDomain != null && clockDomain.clock != null &&
          clockDomain.clock.isInput && clockDomain.clock.isInstanceOf[Bool]
      val directReset =
        clockDomain != null && clockDomain.reset != null &&
          clockDomain.reset.isInput && clockDomain.reset.isInstanceOf[Bool]
      val reviewedDomain =
        clockDomain != null && sameClockDomain && directClock && directReset &&
          clockDomain.config.clockEdge == RISING &&
          clockDomain.config.resetKind == SYNC &&
          clockDomain.config.resetActiveLevel == HIGH &&
          clockDomain.softReset == null && clockDomain.clockEnable == null
      if (!reviewedDomain || treeStatements.isEmpty) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STREAM-M2S-CLOCK-DOMAIN-UNSUPPORTED",
          "the Stream.m2sPipe proof requires one rising-edge clock and one active-high synchronous reset, with no soft reset or clock enable"
        )
      }

      val symbolicPorts = component.getOrdredNodeIo.collect {
        case value: BitVector if widthInference.ofBase(value).isSymbolic => value
      }
      if (!symbolicPorts.exists(_.isInput) || !symbolicPorts.exists(_.isOutput)) {
        fail(
          "SPINAL-PARAMETERIZED-VERILOG-STREAM-M2S-PORT-SHAPE-UNSUPPORTED",
          "the Stream.m2sPipe proof requires symbolic payload input and output ports"
        )
      }
    }

    private def hasSingleFalseInit(value: Bool): Boolean = {
      var count = 0
      var falseOnly = true
      value.foreachStatements {
        case init: InitAssignmentStatement =>
          count += 1
          init.source match {
            case literal: BoolLiteral if !literal.value =>
            case _                                      => falseOnly = false
          }
        case _ =>
      }
      count == 1 && falseOnly
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
            val result = ParameterizedWidth.parameterOf(baseType) match {
              case Some(parameter) => WidthParameter(parameter)
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
              if assignment.target == bitVector && assignment.finalTarget == bitVector =>
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

      private def inferExpression(expression: Expression): WidthExpr = expression match {
        case baseType: BaseType => ofBase(baseType)
        case resize: Resize     => inferResize(resize)
        case cast: CastBitVectorToBitVector => ofExpression(cast.input)
        case _: CastBoolToBits              => WidthLiteral(1)
        case operator: Operator.Bits.Cat =>
          widthAdd(ofExpression(operator.left), ofExpression(operator.right))
        case operator: Operator.BitVector.Add =>
          widthMax(ofExpression(operator.left), ofExpression(operator.right))
        case operator: Operator.BitVector.Sub =>
          widthMax(ofExpression(operator.left), ofExpression(operator.right))
        case operator: Operator.BitVector.And =>
          widthMax(ofExpression(operator.left), ofExpression(operator.right))
        case operator: Operator.BitVector.Or =>
          widthMax(ofExpression(operator.left), ofExpression(operator.right))
        case operator: Operator.BitVector.Xor =>
          widthMax(ofExpression(operator.left), ofExpression(operator.right))
        case operator: Operator.BitVector.Mul =>
          widthAdd(ofExpression(operator.left), ofExpression(operator.right))
        case operator: Operator.BitVector.Div => ofExpression(operator.left)
        case operator: Operator.BitVector.Mod =>
          widthMin(ofExpression(operator.left), ofExpression(operator.right))
        case operator: Operator.BitVector.Repeat =>
          widthMultiply(ofExpression(operator.source), WidthLiteral(operator.count))
        case operator: Operator.BitVector.ShiftLeftByInt =>
          widthAdd(ofExpression(operator.source), WidthLiteral(operator.shift))
        case operator: Operator.BitVector.ShiftRightByInt =>
          widthMax(
            widthSubtract(ofExpression(operator.source), WidthLiteral(operator.shift)),
            WidthLiteral(0)
          )
        case operator: Operator.BitVector.ShiftLeftByIntFixedWidth =>
          ofExpression(operator.source)
        case operator: Operator.BitVector.ShiftRightByIntFixedWidth =>
          ofExpression(operator.source)
        case operator: Operator.BitVector.ShiftRightByUInt =>
          ofExpression(operator.left)
        case operator: Operator.BitVector.ShiftLeftByUIntFixedWidth =>
          ofExpression(operator.left)
        case operator: Operator.Bits.Not => ofExpression(operator.source)
        case operator: Operator.UInt.Not => ofExpression(operator.source)
        case operator: Operator.SInt.Not => ofExpression(operator.source)
        case operator: Operator.SInt.Minus => ofExpression(operator.source)
        case mux: MultiplexerWidthable =>
          mux.inputs.map(ofExpression).reduce(widthMax)
        case mux: BinaryMultiplexerWidthable =>
          widthMax(ofExpression(mux.whenTrue), ofExpression(mux.whenFalse))
        case access: BitVectorRangedAccessFixed => inferFixedRange(access)
        case access: BitVectorRangedAccessFloating => inferFloatingRange(access)
        case access: BitVectorBitAccessFixed => inferFixedBit(access)
        case _: BitVectorBitAccessFloating => WidthLiteral(1)
        case literal: BitVectorLiteral => WidthLiteral(literal.getWidth)
        case _: BoolLiteral            => WidthLiteral(1)
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
    val ordered =
      if (commutative && orderKey(left) > orderKey(right)) (right, left)
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
