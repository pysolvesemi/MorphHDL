package morphhdl.backend.verilog2001

import morphhdl.paramrtl.IntExpr.{
  Add,
  AddressWidth,
  Divide,
  GenerateIndexRef,
  Literal,
  LocalParameterRef,
  Modulo,
  Multiply,
  Negate,
  ParameterRef,
  Select,
  Subtract
}
import morphhdl.paramrtl.BoolExpr.{
  And => BoolAnd,
  Equal => BoolEqual,
  GreaterThan => BoolGreaterThan,
  GreaterThanOrEqual => BoolGreaterThanOrEqual,
  LessThan => BoolLessThan,
  LessThanOrEqual => BoolLessThanOrEqual,
  Literal => BoolLiteral,
  LocalParameterRef => BoolLocalParameterRef,
  Not => BoolNot,
  NotEqual => BoolNotEqual,
  Or => BoolOr,
  ParameterRef => BoolParameterRef
}
import morphhdl.paramrtl.ModuleItem.{
  AsynchronousEnabledRegister,
  AsynchronousRegister,
  CombinationalIf,
  ContinuousAssign,
  GenerateCase,
  GenerateFor,
  GenerateIf,
  ModuleInstance,
  SynchronousEnabledRegister,
  SynchronousReadFirstSinglePortMemory,
  SynchronousRegister
}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.{IndexedPartSelect, Ref}
import morphhdl.paramrtl.Signedness.{Signed, Unsigned}
import morphhdl.paramrtl._

object Verilog2001Emitter {
  def emit(design: Design): Either[DiagnosticSet, String] =
    ParamRtlValidator.validate(design) match {
      case Left(diagnostics) => Left(diagnostics)
      case Right(validated) =>
        Verilog2001Capability.verify(validated) match {
          case Left(diagnostics) => Left(diagnostics)
          case Right(capable)    => Right(render(capable))
        }
    }

  private[morphhdl] def renderVerified(validated: ValidatedDesign): String =
    render(validated)

  private def render(validated: ValidatedDesign): String = {
    validated.orderedModules
      .map(module => renderModule(module, validated.moduleFacts(module.name)))
      .mkString("\n\n") + "\n"
  }

  private def renderModule(module: ModuleDef, facts: ValidatedModuleFacts): String = {
    val integerParameters = module.parameters.sortBy(_.name)
    val booleanParameters = module.booleanParameters.sortBy(_.name)
    val ports = module.ports.sortBy(_.name)
    val localParameters = facts.orderedLocalDeclarations
    val instances = facts.orderedInstances
    val generateFors = module.items.collect { case generate: GenerateFor => generate }.sortBy(_.label)
    val generateIfs = module.items.collect { case generate: GenerateIf => generate }.sortBy(generateIfSortKey)
    val generateCases = module.items.collect { case generate: GenerateCase => generate }.sortBy(generateCaseSortKey)
    val combinationalIfs = module.items.collect { case process: CombinationalIf => process }.sortBy(_.label)
    val synchronousRegisters =
      module.items.collect { case process: SynchronousRegister => process }.sortBy(_.label)
    val asynchronousRegisters =
      module.items.collect { case process: AsynchronousRegister => process }.sortBy(_.label)
    val synchronousEnabledRegisters =
      module.items.collect { case process: SynchronousEnabledRegister => process }.sortBy(_.label)
    val asynchronousEnabledRegisters =
      module.items.collect { case process: AsynchronousEnabledRegister => process }.sortBy(_.label)
    val synchronousReadFirstSinglePortMemories =
      module.items.collect { case memory: SynchronousReadFirstSinglePortMemory => memory }.sortBy(_.label)
    val proceduralOutputs = (
      combinationalIfs
        .flatMap(process => process.whenTrue.map(_.target.name) ++ process.whenFalse.map(_.target.name)) ++
        synchronousRegisters.map(_.assignment.target.name) ++
        asynchronousRegisters.map(_.assignment.target.name) ++
        synchronousEnabledRegisters.map(_.assignment.target.name) ++
        asynchronousEnabledRegisters.map(_.assignment.target.name) ++
        synchronousReadFirstSinglePortMemories.map(_.readData.name)
    ).toSet
    val assignments = module.items.collect { case assignment: ContinuousAssign => assignment }.sortBy { assignment =>
      (assignment.target.name, renderRtlExpr(assignment.value))
    }

    val lines = Vector.newBuilder[String]

    val renderedParameters =
      integerParameters.map(parameter => parameter.name -> parameter.default.toString) ++
        booleanParameters.map(parameter => parameter.name -> (if (parameter.default) "1" else "0"))

    if (renderedParameters.nonEmpty) {
      lines += s"module ${module.name} #("
      renderedParameters.sortBy(_._1).zipWithIndex.foreach { case ((name, default), index) =>
        val comma = if (index == renderedParameters.size - 1) "" else ","
        lines += s"  parameter integer $name = $default$comma"
      }
      lines += ") ("
    } else {
      lines += s"module ${module.name} ("
    }

    ports.zipWithIndex.foreach { case (port, index) =>
      val comma = if (index == ports.size - 1) "" else ","
      lines += renderPort(port, proceduralOutputs.contains(port.name)) + comma
    }
    lines += ");"

    if (localParameters.nonEmpty) {
      lines += ""
      localParameters.foreach {
        case localParameter: IntegerLocalParameter =>
          lines += s"  localparam integer ${localParameter.name} = ${renderIntExpr(localParameter.value)};"
        case localParameter: BooleanLocalParameter =>
          val value = localParameter.value match {
            case BoolLiteral(flag) => if (flag) "1" else "0"
            case other             => s"(${renderBoolExpr(other)}) ? 1 : 0"
          }
          lines += s"  localparam integer ${localParameter.name} = $value;"
      }
    }

    if (synchronousReadFirstSinglePortMemories.nonEmpty) {
      lines += ""
      synchronousReadFirstSinglePortMemories.foreach { memory =>
        val signedness = memory.elementType.signedness match {
          case Unsigned => ""
          case Signed   => "signed "
        }
        lines +=
          s"  reg $signedness${renderPackedRange(memory.elementType.width)}${memory.memoryName} ${renderMemoryRange(memory.depth)};"
      }
    }

    if (instances.nonEmpty) {
      lines += ""
      instances.zipWithIndex.foreach { case (instance, index) =>
        renderInstance(instance, "  ").foreach(lines += _)
        if (index != instances.size - 1) lines += ""
      }
    }

    if (generateFors.nonEmpty || generateIfs.nonEmpty || generateCases.nonEmpty) {
      lines += ""
      if (generateFors.nonEmpty) {
        generateFors.foreach { generate =>
          lines += s"  genvar ${generate.indexName};"
        }
      }
      lines += "  generate"
      generateFors.zipWithIndex.foreach { case (generate, generateIndex) =>
        if (generateIndex != 0) lines += ""
        lines +=
          s"    for (${generate.indexName} = 0; ${generate.indexName} < ${renderComparisonOperand(
              generate.count
            )}; ${generate.indexName} = ${generate.indexName} + 1) begin : ${generate.label}"
        val bodyInstances = generate.body.collect { case instance: ModuleInstance => instance }.sortBy(_.name)
        bodyInstances.zipWithIndex.foreach { case (instance, instanceIndex) =>
          renderInstance(instance, "      ").foreach(lines += _)
          if (instanceIndex != bodyInstances.size - 1) lines += ""
        }
        lines += "    end"
      }
      generateIfs.zipWithIndex.foreach { case (generate, generateIndex) =>
        if (generateFors.nonEmpty || generateIndex != 0) lines += ""
        lines += s"    if (${renderBoolExpr(generate.condition)}) begin : ${generate.whenTrue.label}"
        renderGenerateBlockBody(generate.whenTrue, "      ").foreach(lines += _)
        lines += s"    end else begin : ${generate.whenFalse.label}"
        renderGenerateBlockBody(generate.whenFalse, "      ").foreach(lines += _)
        lines += "    end"
      }
      generateCases.zipWithIndex.foreach { case (generate, generateIndex) =>
        if (generateFors.nonEmpty || generateIfs.nonEmpty || generateIndex != 0) lines += ""
        lines += s"    case (${renderIntExpr(generate.selector)})"
        generate.choices.sortBy(choice => (choice.value, choice.block.label)).foreach { choice =>
          lines += s"      ${choice.value}: begin : ${choice.block.label}"
          renderGenerateBlockBody(choice.block, "        ").foreach(lines += _)
          lines += "      end"
        }
        lines += s"      default: begin : ${generate.default.label}"
        renderGenerateBlockBody(generate.default, "        ").foreach(lines += _)
        lines += "      end"
        lines += "    endcase"
      }
      lines += "  endgenerate"
    }

    if (assignments.nonEmpty) {
      lines += ""
      assignments.foreach { assignment =>
        lines += s"  assign ${assignment.target.name} = ${renderRtlExpr(assignment.value)};"
      }
    }

    synchronousReadFirstSinglePortMemories.foreach { memory =>
      val zeroWidth = renderReplicationWidth(memory.elementType.width)
      lines += ""
      lines += s"  always @(posedge ${memory.clock.name}) begin : ${memory.label}"
      lines += s"    if (${memory.address.name} < ${renderComparisonOperand(memory.depth)}) begin"
      lines += s"      if (${memory.readEnable.name} == 1'b1) begin"
      lines += s"        ${memory.readData.name} <= ${memory.memoryName}[${memory.address.name}];"
      lines += "      end"
      lines += s"      if (${memory.writeEnable.name} == 1'b1) begin"
      lines += s"        ${memory.memoryName}[${memory.address.name}] <= ${memory.writeData.name};"
      lines += "      end"
      lines += s"    end else if (${memory.readEnable.name} == 1'b1) begin"
      lines += s"      ${memory.readData.name} <= {$zeroWidth{1'b0}};"
      lines += "    end"
      lines += "  end"
    }

    combinationalIfs.foreach { process =>
      lines += ""
      lines += s"  always @* begin : ${process.label}"
      lines += s"    if (${process.condition.name} == 1'b1) begin"
      process.whenTrue.sortBy(assignment => (assignment.target.name, assignment.value.name)).foreach {
        assignment =>
          lines += s"      ${assignment.target.name} = ${assignment.value.name};"
      }
      lines += "    end else begin"
      process.whenFalse.sortBy(assignment => (assignment.target.name, assignment.value.name)).foreach {
        assignment =>
          lines += s"      ${assignment.target.name} = ${assignment.value.name};"
      }
      lines += "    end"
      lines += "  end"
    }

    synchronousRegisters.foreach { process =>
      val target = process.assignment.target.name
      val targetPort = ports.find(_.name == target).get
      val resetWidth = renderReplicationWidth(targetPort.dataType.width)
      lines += ""
      lines += s"  always @(posedge ${process.clock.name}) begin : ${process.label}"
      lines += s"    if (${process.reset.name} == 1'b1) begin"
      lines += s"      $target <= {$resetWidth{1'b0}};"
      lines += "    end else begin"
      lines += s"      $target <= ${process.assignment.value.name};"
      lines += "    end"
      lines += "  end"
    }

    asynchronousRegisters.foreach { process =>
      val target = process.assignment.target.name
      val targetPort = ports.find(_.name == target).get
      val resetWidth = renderReplicationWidth(targetPort.dataType.width)
      lines += ""
      lines += s"  always @(posedge ${process.clock.name} or posedge ${process.reset.name}) begin : ${process.label}"
      lines += s"    if (${process.reset.name} == 1'b1) begin"
      lines += s"      $target <= {$resetWidth{1'b0}};"
      lines += "    end else begin"
      lines += s"      $target <= ${process.assignment.value.name};"
      lines += "    end"
      lines += "  end"
    }

    synchronousEnabledRegisters.foreach { process =>
      val target = process.assignment.target.name
      val targetPort = ports.find(_.name == target).get
      val resetWidth = renderReplicationWidth(targetPort.dataType.width)
      lines += ""
      lines += s"  always @(posedge ${process.clock.name}) begin : ${process.label}"
      lines += s"    if (${process.reset.name} == 1'b1) begin"
      lines += s"      $target <= {$resetWidth{1'b0}};"
      lines += s"    end else if (${process.enable.name} == 1'b1) begin"
      lines += s"      $target <= ${process.assignment.value.name};"
      lines += "    end"
      lines += "  end"
    }

    asynchronousEnabledRegisters.foreach { process =>
      val target = process.assignment.target.name
      val targetPort = ports.find(_.name == target).get
      val resetWidth = renderReplicationWidth(targetPort.dataType.width)
      lines += ""
      lines += s"  always @(posedge ${process.clock.name} or posedge ${process.reset.name}) begin : ${process.label}"
      lines += s"    if (${process.reset.name} == 1'b1) begin"
      lines += s"      $target <= {$resetWidth{1'b0}};"
      lines += s"    end else if (${process.enable.name} == 1'b1) begin"
      lines += s"      $target <= ${process.assignment.value.name};"
      lines += "    end"
      lines += "  end"
    }

    lines += ""
    lines += "endmodule"
    lines.result().mkString("\n")
  }

  private def renderGenerateBlockBody(block: GenerateBlock, indent: String): Vector[String] = {
    val instances = block.body.collect { case instance: ModuleInstance => instance }.sortBy(_.name)
    val assignments = block.body.collect { case assignment: ContinuousAssign => assignment }.sortBy { assignment =>
      (assignment.target.name, renderRtlExpr(assignment.value))
    }
    val lines = Vector.newBuilder[String]
    instances.zipWithIndex.foreach { case (instance, index) =>
      renderInstance(instance, indent).foreach(lines += _)
      if (index != instances.size - 1 || assignments.nonEmpty) lines += ""
    }
    assignments.foreach { assignment =>
      lines += s"${indent}assign ${assignment.target.name} = ${renderRtlExpr(assignment.value)};"
    }
    lines.result()
  }

  private def renderInstance(instance: ModuleInstance, indent: String): Vector[String] = {
    val parameterBindings =
      (instance.parameterBindings.map(binding => binding.parameterName -> renderIntExpr(binding.value)) ++
        instance.booleanParameterBindings.map(binding => binding.parameterName -> renderBooleanBinding(binding.value)))
        .sortBy(_._1)
    val portConnections = instance.portConnections.sortBy(_.portName)
    val lines = Vector.newBuilder[String]
    val associationIndent = indent + "  "

    if (parameterBindings.nonEmpty) {
      lines += s"$indent${instance.moduleName} #("
      parameterBindings.zipWithIndex.foreach { case ((name, value), index) =>
        val comma = if (index == parameterBindings.size - 1) "" else ","
        lines += s"$associationIndent.$name($value)$comma"
      }
      lines += s"$indent) ${instance.name} ("
    } else {
      lines += s"$indent${instance.moduleName} ${instance.name} ("
    }

    portConnections.zipWithIndex.foreach { case (connection, index) =>
      val comma = if (index == portConnections.size - 1) "" else ","
      lines += s"$associationIndent.${connection.portName}(${renderRtlExpr(connection.actual)})$comma"
    }
    lines += s"$indent);"
    lines.result()
  }

  private def renderPort(port: Port, procedurallyDriven: Boolean): String = {
    val direction = port.direction match {
      case Input  => "input"
      case Output => "output"
    }
    val signedness = port.dataType.signedness match {
      case Unsigned => ""
      case Signed   => "signed "
    }
    val range = renderPackedRange(port.dataType.width)

    val storage = if (port.direction == Output && procedurallyDriven) "reg" else "wire"
    f"  $direction%-6s $storage $signedness$range${port.name}"
  }

  private def renderPackedRange(width: IntExpr): String = width match {
    case Literal(value)          => s"[${value - 1}:0] "
    case ParameterRef(name)      => s"[$name-1:0] "
    case LocalParameterRef(name) => s"[$name-1:0] "
    case expression              => s"[(${renderIntExpr(expression)})-1:0] "
  }

  private def renderMemoryRange(depth: IntExpr): String = depth match {
    case Literal(value)          => s"[0:${value - 1}]"
    case ParameterRef(name)      => s"[0:$name-1]"
    case LocalParameterRef(name) => s"[0:$name-1]"
    case expression              => s"[0:(${renderIntExpr(expression)})-1]"
  }

  private def renderReplicationWidth(width: IntExpr): String = width match {
    case _: Literal | _: ParameterRef | _: LocalParameterRef => renderIntExpr(width)
    case _                                                   => s"(${renderIntExpr(width)})"
  }

  private val ConditionalPrecedence = 0
  private val AdditivePrecedence = 10
  private val MultiplicativePrecedence = 20
  private val UnaryPrecedence = 30
  private val AtomicPrecedence = 40

  private final case class RenderedIntExpr(text: String, precedence: Int)

  private def renderIntExpr(expression: IntExpr): String = renderIntExprWithPrecedence(expression).text

  private def renderIntExprWithPrecedence(expression: IntExpr): RenderedIntExpr = expression match {
    case Literal(value)          => RenderedIntExpr(value.toString, AtomicPrecedence)
    case ParameterRef(name)      => RenderedIntExpr(name, AtomicPrecedence)
    case LocalParameterRef(name) => RenderedIntExpr(name, AtomicPrecedence)
    case GenerateIndexRef(name)  => RenderedIntExpr(name, AtomicPrecedence)
    case AddressWidth(value)     => renderAddressWidth(value)
    case Negate(value) =>
      val rendered = renderIntExprWithPrecedence(value)
      val needsParentheses = rendered.precedence <= UnaryPrecedence || rendered.text.startsWith("-")
      val operand = if (needsParentheses) s"(${rendered.text})" else rendered.text
      RenderedIntExpr(s"-$operand", UnaryPrecedence)
    case Add(left, right)      => renderBinary(left, "+", right, AdditivePrecedence)
    case Subtract(left, right) => renderBinary(left, "-", right, AdditivePrecedence)
    case Multiply(left, right) => renderBinary(left, "*", right, MultiplicativePrecedence)
    case Divide(left, right)   => renderBinary(left, "/", right, MultiplicativePrecedence)
    case Modulo(left, right)   => renderBinary(left, "%", right, MultiplicativePrecedence)
    case Select(condition, whenTrue, whenFalse) =>
      val renderedTrue = renderIntExprWithPrecedence(whenTrue)
      val renderedFalse = renderIntExprWithPrecedence(whenFalse)
      val trueText =
        if (renderedTrue.precedence <= ConditionalPrecedence) s"(${renderedTrue.text})"
        else renderedTrue.text
      val falseText =
        if (renderedFalse.precedence <= ConditionalPrecedence) s"(${renderedFalse.text})"
        else renderedFalse.text
      RenderedIntExpr(
        s"(${renderBoolExpr(condition)}) ? $trueText : $falseText",
        ConditionalPrecedence
      )
  }

  private def renderBinary(
      left: IntExpr,
      operator: String,
      right: IntExpr,
      precedence: Int
  ): RenderedIntExpr = {
    val renderedLeft = renderIntExprWithPrecedence(left)
    val renderedRight = renderIntExprWithPrecedence(right)
    val leftText = if (renderedLeft.precedence < precedence) s"(${renderedLeft.text})" else renderedLeft.text
    val parenthesizeRight = renderedRight.precedence <= precedence || renderedRight.text.startsWith("-")
    val rightText = if (parenthesizeRight) s"(${renderedRight.text})" else renderedRight.text
    RenderedIntExpr(s"$leftText $operator $rightText", precedence)
  }

  /**
    * IEEE 1364-2001 has no portable ceiling-log2 operator. The operand has already been
    * proven positive and within the signed 32-bit target domain, so this fixed chain covers
    * every legal value without relying on SystemVerilog's `$clog2`.
    */
  private def renderAddressWidth(value: IntExpr): RenderedIntExpr = {
    val shape = AddressWidthLowering.shape(value)
    if (shape.thresholds.isEmpty)
      return RenderedIntExpr(shape.maximumResult.toString, AtomicPrecedence)

    val operand = renderDelimitedIntOperand(shape.base)
    var chain = shape.maximumResult.toString
    shape.thresholds.reverseIterator.foreach { case (threshold, width) =>
      val falseBranch = if (width == shape.maximumResult - 1) chain else s"($chain)"
      chain = s"($operand <= $threshold) ? $width : $falseBranch"
    }
    RenderedIntExpr(chain, ConditionalPrecedence)
  }

  private def renderRtlExpr(expression: RtlExpr): String = expression match {
    case Ref(name) => name
    case IndexedPartSelect(base, offset, width) =>
      s"${base.name}[${renderDelimitedIntOperand(offset)} +: ${renderDelimitedIntOperand(width)}]"
  }

  private def renderDelimitedIntOperand(expression: IntExpr): String = {
    val rendered = renderIntExprWithPrecedence(expression)
    if (rendered.precedence <= ConditionalPrecedence) s"(${rendered.text})" else rendered.text
  }

  private val BoolOrPrecedence = 10
  private val BoolAndPrecedence = 20
  private val BoolNotPrecedence = 30
  private val BoolAtomicPrecedence = 40

  private final case class RenderedBoolExpr(text: String, precedence: Int)

  private def renderBoolExpr(expression: BoolExpr): String = renderBoolExprWithPrecedence(expression).text

  private def renderBooleanBinding(expression: BoolExpr): String = expression match {
    case BoolLiteral(value) => if (value) "1" else "0"
    case other              => s"(${renderBoolExpr(other)}) ? 1 : 0"
  }

  private def renderBoolExprWithPrecedence(expression: BoolExpr): RenderedBoolExpr = {
    final case class Frame(value: BoolExpr, expanded: Boolean)
    val rendered = new java.util.IdentityHashMap[BoolExpr, RenderedBoolExpr]()
    val work = scala.collection.mutable.ArrayBuffer(Frame(expression, expanded = false))

    while (work.nonEmpty) {
      val frame = work.remove(work.length - 1)
      if (!rendered.containsKey(frame.value)) {
        if (!frame.expanded) {
          work += Frame(frame.value, expanded = true)
          frame.value match {
            case BoolNot(operand)       => work += Frame(operand, expanded = false)
            case BoolAnd(left, right)   => work += Frame(right, expanded = false); work += Frame(left, expanded = false)
            case BoolOr(left, right)    => work += Frame(right, expanded = false); work += Frame(left, expanded = false)
            case _                      =>
          }
        } else {
          val value = frame.value match {
            case BoolLiteral(result) => RenderedBoolExpr(if (result) "1'b1" else "1'b0", BoolAtomicPrecedence)
            case BoolParameterRef(name) => RenderedBoolExpr(s"$name == 1", BoolAtomicPrecedence)
            case BoolLocalParameterRef(name) => RenderedBoolExpr(s"$name == 1", BoolAtomicPrecedence)
            case BoolLessThan(left, right)           => renderComparison(left, "<", right)
            case BoolLessThanOrEqual(left, right)    => renderComparison(left, "<=", right)
            case BoolGreaterThan(left, right)        => renderComparison(left, ">", right)
            case BoolGreaterThanOrEqual(left, right) => renderComparison(left, ">=", right)
            case BoolEqual(left, right)              => renderComparison(left, "==", right)
            case BoolNotEqual(left, right)           => renderComparison(left, "!=", right)
            case BoolNot(operand) =>
              val inner = rendered.get(operand)
              val needsParentheses = inner.precedence < BoolNotPrecedence || inner.text.contains(" ")
              val operandText = if (needsParentheses) s"(${inner.text})" else inner.text
              RenderedBoolExpr(s"!$operandText", BoolNotPrecedence)
            case BoolAnd(left, right) =>
              renderBoolBinary(rendered.get(left), "&&", rendered.get(right), BoolAndPrecedence)
            case BoolOr(left, right) =>
              renderBoolBinary(rendered.get(left), "||", rendered.get(right), BoolOrPrecedence)
          }
          rendered.put(frame.value, value)
        }
      }
    }
    rendered.get(expression)
  }

  private def renderComparison(left: IntExpr, operator: String, right: IntExpr): RenderedBoolExpr =
    RenderedBoolExpr(
      s"${renderComparisonOperand(left)} $operator ${renderComparisonOperand(right)}",
      BoolAtomicPrecedence
    )

  private def renderComparisonOperand(expression: IntExpr): String = {
    renderDelimitedIntOperand(expression)
  }

  private def renderBoolBinary(
      left: RenderedBoolExpr,
      operator: String,
      right: RenderedBoolExpr,
      precedence: Int
  ): RenderedBoolExpr = {
    val leftText = if (left.precedence < precedence) s"(${left.text})" else left.text
    val rightText = if (right.precedence < precedence) s"(${right.text})" else right.text
    RenderedBoolExpr(s"$leftText $operator $rightText", precedence)
  }

  private def generateIfSortKey(generate: GenerateIf): (String, String) =
    generate.whenTrue.label -> generate.whenFalse.label

  private def generateCaseSortKey(generate: GenerateCase): (String, String) =
    generate.default.label -> generate.choices
      .sortBy(choice => (choice.value, choice.block.label))
      .map(choice => s"${choice.value}:${choice.block.label}")
      .mkString("|")
}

/** Shared structural plan keeps target-cost admission and emitted lowering in lockstep. */
private[verilog2001] object AddressWidthLowering {
  final case class Shape(
      base: IntExpr,
      maximumResult: Int,
      thresholds: Vector[(BigInt, Int)]
  )

  /** `value` is the operand of the outer address-width node. */
  def shape(value: IntExpr): Shape = {
    val (layers, base) =
      IntExpressionAnalysis.peelDirectAddressWidths(AddressWidth(value))

    var maximum = BigInt(Int.MaxValue)
    var layer = 0
    while (layer < layers) {
      maximum = addressWidth(maximum)
      layer += 1
    }
    val maximumResult = maximum.toInt
    val thresholds = (1 until maximumResult).map { result =>
      var threshold = BigInt(result)
      var inverseLayer = 0
      while (inverseLayer < layers) {
        threshold = BigInt(1) << threshold.toInt
        inverseLayer += 1
      }
      threshold -> result
    }.toVector
    Shape(base, maximumResult, thresholds)
  }

  /**
    * Estimates the expanded syntax tree and fails closed before repeated conditional operands
    * can cause target text to grow exponentially. Directly nested address-width nodes use the
    * flattened shape above and therefore get cheaper as their result range contracts.
    */
  def expansionWithin(expression: IntExpr, maximum: Long): Boolean = {
    final case class Frame(value: AnyRef, expanded: Boolean)
    val integerCosts = new java.util.IdentityHashMap[IntExpr, java.lang.Long]()
    val booleanCosts = new java.util.IdentityHashMap[BoolExpr, java.lang.Long]()
    val work = scala.collection.mutable.ArrayBuffer(Frame(expression, expanded = false))
    val TooLarge = -1L

    def isDone(value: AnyRef): Boolean = value match {
      case integer: IntExpr => integerCosts.containsKey(integer)
      case boolean: BoolExpr => booleanCosts.containsKey(boolean)
      case _ => true
    }
    def push(value: AnyRef): Unit = work += Frame(value, expanded = false)
    def integer(value: IntExpr): Long = integerCosts.get(value).longValue
    def boolean(value: BoolExpr): Long = booleanCosts.get(value).longValue
    def plus(left: Long, right: Long, overhead: Long): Long =
      if (left == TooLarge || right == TooLarge || left > maximum - right - overhead) TooLarge
      else left + right + overhead
    def increment(value: Long): Long =
      if (value == TooLarge || value >= maximum) TooLarge else value + 1L

    while (work.nonEmpty) {
      val frame = work.remove(work.length - 1)
      if (!isDone(frame.value)) {
        if (!frame.expanded) {
          work += Frame(frame.value, expanded = true)
          frame.value match {
            case _: Literal | _: ParameterRef | _: LocalParameterRef | _: GenerateIndexRef =>
            case AddressWidth(operand) =>
              val lowered = shape(operand)
              if (lowered.thresholds.nonEmpty) push(lowered.base)
            case Negate(operand)       => push(operand)
            case Add(left, right)      => push(right); push(left)
            case Subtract(left, right) => push(right); push(left)
            case Multiply(left, right) => push(right); push(left)
            case Divide(left, right)   => push(right); push(left)
            case Modulo(left, right)   => push(right); push(left)
            case Select(condition, whenTrue, whenFalse) =>
              push(whenFalse)
              push(whenTrue)
              push(condition)
            case _: BoolLiteral | _: BoolParameterRef | _: BoolLocalParameterRef =>
            case BoolNot(operand)       => push(operand)
            case BoolAnd(left, right)   => push(right); push(left)
            case BoolOr(left, right)    => push(right); push(left)
            case BoolLessThan(left, right)           => push(right); push(left)
            case BoolLessThanOrEqual(left, right)    => push(right); push(left)
            case BoolGreaterThan(left, right)        => push(right); push(left)
            case BoolGreaterThanOrEqual(left, right) => push(right); push(left)
            case BoolEqual(left, right)              => push(right); push(left)
            case BoolNotEqual(left, right)           => push(right); push(left)
          }
        } else {
          frame.value match {
            case value: Literal          => integerCosts.put(value, 1L)
            case value: ParameterRef     => integerCosts.put(value, 1L)
            case value: LocalParameterRef => integerCosts.put(value, 1L)
            case value: GenerateIndexRef => integerCosts.put(value, 1L)
            case value @ AddressWidth(operand) =>
              val lowered = shape(operand)
              val cost =
                if (lowered.thresholds.isEmpty) 1L
                else {
                  val operandCost = integer(lowered.base)
                  val comparisons = lowered.thresholds.size.toLong
                  if (
                    operandCost == TooLarge ||
                    operandCost + 1L > (maximum - 1L) / comparisons
                  ) TooLarge
                  else 1L + comparisons * (operandCost + 1L)
                }
              integerCosts.put(value, cost)
            case value @ Negate(operand) => integerCosts.put(value, increment(integer(operand)))
            case value @ Add(left, right) => integerCosts.put(value, plus(integer(left), integer(right), 1L))
            case value @ Subtract(left, right) => integerCosts.put(value, plus(integer(left), integer(right), 1L))
            case value @ Multiply(left, right) => integerCosts.put(value, plus(integer(left), integer(right), 1L))
            case value @ Divide(left, right) => integerCosts.put(value, plus(integer(left), integer(right), 1L))
            case value @ Modulo(left, right) => integerCosts.put(value, plus(integer(left), integer(right), 1L))
            case value @ Select(condition, whenTrue, whenFalse) =>
              integerCosts.put(
                value,
                plus(boolean(condition), plus(integer(whenTrue), integer(whenFalse), 0L), 1L)
              )
            case value: BoolLiteral          => booleanCosts.put(value, 1L)
            case value: BoolParameterRef     => booleanCosts.put(value, 1L)
            case value: BoolLocalParameterRef => booleanCosts.put(value, 1L)
            case value @ BoolNot(operand) => booleanCosts.put(value, increment(boolean(operand)))
            case value @ BoolAnd(left, right) => booleanCosts.put(value, plus(boolean(left), boolean(right), 1L))
            case value @ BoolOr(left, right) => booleanCosts.put(value, plus(boolean(left), boolean(right), 1L))
            case value @ BoolLessThan(left, right) => booleanCosts.put(value, plus(integer(left), integer(right), 1L))
            case value @ BoolLessThanOrEqual(left, right) => booleanCosts.put(value, plus(integer(left), integer(right), 1L))
            case value @ BoolGreaterThan(left, right) => booleanCosts.put(value, plus(integer(left), integer(right), 1L))
            case value @ BoolGreaterThanOrEqual(left, right) => booleanCosts.put(value, plus(integer(left), integer(right), 1L))
            case value @ BoolEqual(left, right) => booleanCosts.put(value, plus(integer(left), integer(right), 1L))
            case value @ BoolNotEqual(left, right) => booleanCosts.put(value, plus(integer(left), integer(right), 1L))
          }
        }
      }
    }
    integer(expression) != TooLarge
  }

  private def addressWidth(value: BigInt): BigInt =
    if (value <= 2) BigInt(1) else BigInt((value - 1).bitLength)
}
