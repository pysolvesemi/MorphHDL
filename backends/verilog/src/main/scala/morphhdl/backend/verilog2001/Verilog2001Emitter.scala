package morphhdl.backend.verilog2001

import morphhdl.paramrtl.IntExpr.{
  Add,
  AddressWidth,
  CeilLog2,
  Divide,
  GenerateIndexRef,
  Literal,
  LocalParameterRef,
  Max,
  Min,
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
  SynchronousCounter,
  SynchronousEnabledRegister,
  SynchronousReadFirstSimpleDualPortMemory,
  SynchronousReadFirstSinglePortMemory,
  SynchronousRegister,
  SynchronousStreamFifo
}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.{IndexedPartSelect, Ref}
import morphhdl.paramrtl.Signedness.{Signed, Unsigned}
import morphhdl.paramrtl._

object Verilog2001Emitter {
  private val PortableCeilLog2FunctionBaseName = "clog2"

  private final case class RenderContext(portableCeilLog2FunctionName: String)

  private final case class FifoInternalNames(
      pointerWidth: String,
      occupancyWidth: String,
      readPointer: String,
      writePointer: String,
      occupancy: String,
      pushFire: String,
      popFire: String
  )

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
    implicit val context: RenderContext =
      RenderContext(portableCeilLog2FunctionName(module))
    val usesPortableCeilLog2 = moduleUsesPortableCeilLog2(module)
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
    val synchronousReadFirstSimpleDualPortMemories =
      module.items.collect { case memory: SynchronousReadFirstSimpleDualPortMemory => memory }.sortBy(_.label)
    val synchronousCounters =
      module.items.collect { case counter: SynchronousCounter => counter }.sortBy(_.label)
    val synchronousStreamFifos =
      module.items.collect { case fifo: SynchronousStreamFifo => fifo }.sortBy(_.label)
    val fifoNames = synchronousStreamFifos.headOption.map { fifo =>
      allocateFifoInternalNames(module, context.portableCeilLog2FunctionName)
    }
    val proceduralOutputs = (
      combinationalIfs
        .flatMap(process => process.whenTrue.map(_.target.name) ++ process.whenFalse.map(_.target.name)) ++
        synchronousRegisters.map(_.assignment.target.name) ++
        asynchronousRegisters.map(_.assignment.target.name) ++
        synchronousEnabledRegisters.map(_.assignment.target.name) ++
        asynchronousEnabledRegisters.map(_.assignment.target.name) ++
        synchronousReadFirstSinglePortMemories.map(_.readData.name) ++
        synchronousReadFirstSimpleDualPortMemories.map(_.readData.name) ++
        synchronousCounters.map(_.count.name) ++
        synchronousStreamFifos.flatMap(fifo => Vector(fifo.popValid.name, fifo.popData.name))
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

    if (usesPortableCeilLog2) {
      lines += ""
      renderPortableCeilLog2Function.foreach(lines += _)
    }

    if (localParameters.nonEmpty || synchronousStreamFifos.nonEmpty) {
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
      fifoNames.foreach { names =>
        val fifo = synchronousStreamFifos.head
        lines +=
          s"  localparam integer ${names.pointerWidth} = ${context.portableCeilLog2FunctionName}(${renderDelimitedIntOperand(fifo.depth)}, 1);"
        lines +=
          s"  localparam integer ${names.occupancyWidth} = ${context.portableCeilLog2FunctionName}(${renderDelimitedIntOperand(Add(fifo.depth, Literal(1)))}, 1);"
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

    if (synchronousReadFirstSimpleDualPortMemories.nonEmpty) {
      lines += ""
      synchronousReadFirstSimpleDualPortMemories.foreach { memory =>
        val signedness = memory.elementType.signedness match {
          case Unsigned => ""
          case Signed   => "signed "
        }
        lines +=
          s"  reg $signedness${renderPackedRange(memory.elementType.width)}${memory.memoryName} ${renderMemoryRange(memory.depth)};"
      }
    }

    if (synchronousStreamFifos.nonEmpty) {
      lines += ""
      val fifo = synchronousStreamFifos.head
      val names = fifoNames.get
      val signedness = fifo.elementType.signedness match {
        case Unsigned => ""
        case Signed   => "signed "
      }
      lines +=
        s"  reg $signedness${renderPackedRange(fifo.elementType.width)}${fifo.memoryName} ${renderMemoryRange(fifo.depth)};"
      lines += s"  reg [${names.pointerWidth}-1:0] ${names.readPointer};"
      lines += s"  reg [${names.pointerWidth}-1:0] ${names.writePointer};"
      lines += s"  reg [${names.occupancyWidth}-1:0] ${names.occupancy};"
      lines += s"  wire ${names.pushFire};"
      lines += s"  wire ${names.popFire};"
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

    synchronousStreamFifos.headOption.foreach { fifo =>
      val names = fifoNames.get
      lines += ""
      lines +=
        s"  assign ${fifo.pushReady.name} = ${names.occupancy} < ${renderComparisonOperand(fifo.depth)};"
      lines +=
        s"  assign ${names.pushFire} = ${fifo.pushValid.name} && ${fifo.pushReady.name};"
      lines +=
        s"  assign ${names.popFire} = ${fifo.popValid.name} && ${fifo.popReady.name};"
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

    synchronousReadFirstSimpleDualPortMemories.foreach { memory =>
      val zeroWidth = renderReplicationWidth(memory.elementType.width)
      lines += ""
      lines += s"  always @(posedge ${memory.clock.name}) begin : ${memory.label}"
      lines += s"    if (${memory.readAddress.name} < ${renderComparisonOperand(memory.depth)}) begin"
      lines += s"      if (${memory.readEnable.name} == 1'b1) begin"
      lines += s"        ${memory.readData.name} <= ${memory.memoryName}[${memory.readAddress.name}];"
      lines += "      end"
      lines += s"    end else if (${memory.readEnable.name} == 1'b1) begin"
      lines += s"      ${memory.readData.name} <= {$zeroWidth{1'b0}};"
      lines += "    end"
      lines += s"    if (${memory.writeAddress.name} < ${renderComparisonOperand(memory.depth)}) begin"
      lines += s"      if (${memory.writeEnable.name} == 1'b1) begin"
      lines += s"        ${memory.memoryName}[${memory.writeAddress.name}] <= ${memory.writeData.name};"
      lines += "      end"
      lines += "    end"
      lines += "  end"
    }

    synchronousCounters.foreach { counter =>
      val count = counter.count.name
      val countPort = ports.find(_.name == count).get
      // Validation guarantees an atomic AddressWidth(limit) expression here,
      // so the constant-function call is already a legal replication count.
      val resetWidth = renderIntExpr(countPort.dataType.width)
      lines += ""
      lines += s"  always @(posedge ${counter.clock.name}) begin : ${counter.label}"
      lines += s"    if (${counter.reset.name} == 1'b1) begin"
      lines += s"      $count <= {$resetWidth{1'b0}};"
      lines += s"    end else if (${counter.enable.name} == 1'b1) begin"
      lines += s"      if ($count == ${renderComparisonOperand(counter.limit)} - 1) begin"
      lines += s"        $count <= {$resetWidth{1'b0}};"
      lines += "      end else begin"
      lines += s"        $count <= $count + 1'b1;"
      lines += "      end"
      lines += "    end"
      lines += "  end"
    }

    synchronousStreamFifos.headOption.foreach { fifo =>
      val names = fifoNames.get
      lines += ""
      lines += s"  always @(posedge ${fifo.clock.name}) begin : ${fifo.label}"
      lines += s"    if (${fifo.reset.name} == 1'b1) begin"
      lines += s"      ${names.readPointer} <= {${names.pointerWidth}{1'b0}};"
      lines += s"      ${names.writePointer} <= {${names.pointerWidth}{1'b0}};"
      lines += s"      ${names.occupancy} <= {${names.occupancyWidth}{1'b0}};"
      lines += s"      ${fifo.popValid.name} <= 1'b0;"
      lines += "    end else begin"
      lines += s"      if (${names.pushFire} == 1'b1) begin"
      lines += s"        ${fifo.memoryName}[${names.writePointer}] <= ${fifo.pushData.name};"
      lines += s"        if (${names.writePointer} == ${renderComparisonOperand(fifo.depth)} - 1) begin"
      lines += s"          ${names.writePointer} <= {${names.pointerWidth}{1'b0}};"
      lines += "        end else begin"
      lines += s"          ${names.writePointer} <= ${names.writePointer} + 1'b1;"
      lines += "        end"
      lines += "      end"
      lines += s"      if (${fifo.popValid.name} == 1'b0) begin"
      lines += s"        if (${names.occupancy} > 0) begin"
      lines += s"          ${fifo.popData.name} <= ${fifo.memoryName}[${names.readPointer}];"
      lines += s"          ${fifo.popValid.name} <= 1'b1;"
      lines += s"          if (${names.readPointer} == ${renderComparisonOperand(fifo.depth)} - 1) begin"
      lines += s"            ${names.readPointer} <= {${names.pointerWidth}{1'b0}};"
      lines += "          end else begin"
      lines += s"            ${names.readPointer} <= ${names.readPointer} + 1'b1;"
      lines += "          end"
      lines += "        end"
      lines += s"      end else if (${names.popFire} == 1'b1) begin"
      lines += s"        if (${names.occupancy} > 1) begin"
      lines += s"          ${fifo.popData.name} <= ${fifo.memoryName}[${names.readPointer}];"
      lines += s"          ${fifo.popValid.name} <= 1'b1;"
      lines += s"          if (${names.readPointer} == ${renderComparisonOperand(fifo.depth)} - 1) begin"
      lines += s"            ${names.readPointer} <= {${names.pointerWidth}{1'b0}};"
      lines += "          end else begin"
      lines += s"            ${names.readPointer} <= ${names.readPointer} + 1'b1;"
      lines += "          end"
      lines += "        end else begin"
      lines += s"          ${fifo.popValid.name} <= 1'b0;"
      lines += "        end"
      lines += "      end"
      lines += s"      if (${names.pushFire} != ${names.popFire}) begin"
      lines += s"        if (${names.pushFire} == 1'b1) begin"
      lines += s"          ${names.occupancy} <= ${names.occupancy} + 1'b1;"
      lines += "        end else begin"
      lines += s"          ${names.occupancy} <= ${names.occupancy} - 1'b1;"
      lines += "        end"
      lines += "      end"
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

  private def renderGenerateBlockBody(
      block: GenerateBlock,
      indent: String
  )(implicit context: RenderContext): Vector[String] = {
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

  private def renderInstance(
      instance: ModuleInstance,
      indent: String
  )(implicit context: RenderContext): Vector[String] = {
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

  private def renderPort(
      port: Port,
      procedurallyDriven: Boolean
  )(implicit context: RenderContext): String = {
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

  private def renderPackedRange(width: IntExpr)(implicit context: RenderContext): String = width match {
    case Literal(value)          => s"[${value - 1}:0] "
    case ParameterRef(name)      => s"[$name-1:0] "
    case LocalParameterRef(name) => s"[$name-1:0] "
    case expression              => s"[(${renderIntExpr(expression)})-1:0] "
  }

  private def renderMemoryRange(depth: IntExpr)(implicit context: RenderContext): String = depth match {
    case Literal(value)          => s"[0:${value - 1}]"
    case ParameterRef(name)      => s"[0:$name-1]"
    case LocalParameterRef(name) => s"[0:$name-1]"
    case expression              => s"[0:(${renderIntExpr(expression)})-1]"
  }

  private def renderReplicationWidth(width: IntExpr)(implicit context: RenderContext): String = width match {
    case _: Literal | _: ParameterRef | _: LocalParameterRef => renderIntExpr(width)
    case _                                                   => s"(${renderIntExpr(width)})"
  }

  private val ConditionalPrecedence = 0
  private val AdditivePrecedence = 10
  private val MultiplicativePrecedence = 20
  private val UnaryPrecedence = 30
  private val AtomicPrecedence = 40

  private final case class RenderedIntExpr(text: String, precedence: Int)

  private def renderIntExpr(expression: IntExpr)(implicit context: RenderContext): String =
    renderIntExprWithPrecedence(expression).text

  private def renderIntExprWithPrecedence(
      expression: IntExpr
  )(implicit context: RenderContext): RenderedIntExpr =
    renderExpressionGraph(expression).integers.get(expression)

  private def renderBinary(
      left: RenderedIntExpr,
      operator: String,
      right: RenderedIntExpr,
      precedence: Int
  ): RenderedIntExpr = {
    val leftText = if (left.precedence < precedence) s"(${left.text})" else left.text
    val parenthesizeRight = right.precedence <= precedence || right.text.startsWith("-")
    val rightText = if (parenthesizeRight) s"(${right.text})" else right.text
    RenderedIntExpr(s"$leftText $operator $rightText", precedence)
  }

  private def renderExtremum(
      left: RenderedIntExpr,
      operator: String,
      right: RenderedIntExpr
  ): RenderedIntExpr = {
    val leftText =
      if (left.precedence <= ConditionalPrecedence) s"(${left.text})"
      else left.text
    val rightText =
      if (right.precedence <= ConditionalPrecedence) s"(${right.text})"
      else right.text
    RenderedIntExpr(
      s"($leftText $operator $rightText) ? $leftText : $rightText",
      ConditionalPrecedence
    )
  }

  /** IEEE 1364-2001 constant-function lowering with an internal result floor. */
  private def renderAddressWidth(
      value: IntExpr,
      integers: java.util.IdentityHashMap[IntExpr, RenderedIntExpr]
  )(implicit context: RenderContext): RenderedIntExpr = {
    val operand = renderDelimitedIntOperand(integers.get(value))
    RenderedIntExpr(
      s"${context.portableCeilLog2FunctionName}($operand, 1)",
      AtomicPrecedence
    )
  }

  private def renderCeilLog2(
      value: IntExpr,
      integers: java.util.IdentityHashMap[IntExpr, RenderedIntExpr]
  )(implicit context: RenderContext): RenderedIntExpr = {
    val operand = renderDelimitedIntOperand(integers.get(value))
    RenderedIntExpr(
      s"${context.portableCeilLog2FunctionName}($operand, 0)",
      AtomicPrecedence
    )
  }

  private def renderRtlExpr(expression: RtlExpr)(implicit context: RenderContext): String = expression match {
    case Ref(name) => name
    case IndexedPartSelect(base, offset, width) =>
      s"${base.name}[${renderDelimitedIntOperand(offset)} +: ${renderDelimitedIntOperand(width)}]"
  }

  private def renderDelimitedIntOperand(
      expression: IntExpr
  )(implicit context: RenderContext): String = {
    val rendered = renderIntExprWithPrecedence(expression)
    renderDelimitedIntOperand(rendered)
  }

  private def renderDelimitedIntOperand(rendered: RenderedIntExpr): String =
    if (rendered.precedence <= ConditionalPrecedence) s"(${rendered.text})" else rendered.text

  private val BoolOrPrecedence = 10
  private val BoolAndPrecedence = 20
  private val BoolNotPrecedence = 30
  private val BoolAtomicPrecedence = 40

  private final case class RenderedBoolExpr(text: String, precedence: Int)

  private final case class RenderedExpressionGraph(
      integers: java.util.IdentityHashMap[IntExpr, RenderedIntExpr],
      booleans: java.util.IdentityHashMap[BoolExpr, RenderedBoolExpr]
  )

  private def renderBoolExpr(expression: BoolExpr)(implicit context: RenderContext): String =
    renderBoolExprWithPrecedence(expression).text

  private def renderBooleanBinding(expression: BoolExpr)(implicit context: RenderContext): String = expression match {
    case BoolLiteral(value) => if (value) "1" else "0"
    case other              => s"(${renderBoolExpr(other)}) ? 1 : 0"
  }

  private def renderBoolExprWithPrecedence(
      expression: BoolExpr
  )(implicit context: RenderContext): RenderedBoolExpr =
    renderExpressionGraph(expression).booleans.get(expression)

  /** Iterative mixed-graph rendering preserves DAG sharing and never consumes expression depth. */
  private def renderExpressionGraph(
      root: AnyRef
  )(implicit context: RenderContext): RenderedExpressionGraph = {
    final case class Frame(value: AnyRef, expanded: Boolean)
    val integers = new java.util.IdentityHashMap[IntExpr, RenderedIntExpr]()
    val booleans = new java.util.IdentityHashMap[BoolExpr, RenderedBoolExpr]()
    val work = scala.collection.mutable.ArrayBuffer(Frame(root, expanded = false))

    def isDone(value: AnyRef): Boolean = value match {
      case integer: IntExpr => integers.containsKey(integer)
      case boolean: BoolExpr => booleans.containsKey(boolean)
      case _ => true
    }
    def push(value: AnyRef): Unit = work += Frame(value, expanded = false)
    def integer(value: IntExpr): RenderedIntExpr = integers.get(value)
    def boolean(value: BoolExpr): RenderedBoolExpr = booleans.get(value)

    while (work.nonEmpty) {
      val frame = work.remove(work.length - 1)
      if (!isDone(frame.value)) {
        if (!frame.expanded) {
          work += Frame(frame.value, expanded = true)
          frame.value match {
            case _: Literal | _: ParameterRef | _: LocalParameterRef | _: GenerateIndexRef =>
            case AddressWidth(operand) => push(operand)
            case CeilLog2(operand)     => push(operand)
            case Negate(operand)       => push(operand)
            case Add(left, right)      => push(right); push(left)
            case Subtract(left, right) => push(right); push(left)
            case Multiply(left, right) => push(right); push(left)
            case Divide(left, right)   => push(right); push(left)
            case Modulo(left, right)   => push(right); push(left)
            case Min(left, right)      => push(right); push(left)
            case Max(left, right)      => push(right); push(left)
            case Select(condition, whenTrue, whenFalse) =>
              push(whenFalse); push(whenTrue); push(condition)
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
            case value @ Literal(number) =>
              integers.put(value, RenderedIntExpr(number.toString, AtomicPrecedence))
            case value @ ParameterRef(name) =>
              integers.put(value, RenderedIntExpr(name, AtomicPrecedence))
            case value @ LocalParameterRef(name) =>
              integers.put(value, RenderedIntExpr(name, AtomicPrecedence))
            case value @ GenerateIndexRef(name) =>
              integers.put(value, RenderedIntExpr(name, AtomicPrecedence))
            case value @ AddressWidth(operand) =>
              integers.put(value, renderAddressWidth(operand, integers))
            case value @ CeilLog2(operand) =>
              integers.put(value, renderCeilLog2(operand, integers))
            case value @ Negate(operand) =>
              val rendered = integer(operand)
              val needsParentheses = rendered.precedence <= UnaryPrecedence || rendered.text.startsWith("-")
              val operandText = if (needsParentheses) s"(${rendered.text})" else rendered.text
              integers.put(value, RenderedIntExpr(s"-$operandText", UnaryPrecedence))
            case value @ Add(left, right) =>
              integers.put(value, renderBinary(integer(left), "+", integer(right), AdditivePrecedence))
            case value @ Subtract(left, right) =>
              integers.put(value, renderBinary(integer(left), "-", integer(right), AdditivePrecedence))
            case value @ Multiply(left, right) =>
              integers.put(value, renderBinary(integer(left), "*", integer(right), MultiplicativePrecedence))
            case value @ Divide(left, right) =>
              integers.put(value, renderBinary(integer(left), "/", integer(right), MultiplicativePrecedence))
            case value @ Modulo(left, right) =>
              integers.put(value, renderBinary(integer(left), "%", integer(right), MultiplicativePrecedence))
            case value @ Min(left, right) =>
              integers.put(value, renderExtremum(integer(left), "<", integer(right)))
            case value @ Max(left, right) =>
              integers.put(value, renderExtremum(integer(left), ">", integer(right)))
            case value @ Select(condition, whenTrue, whenFalse) =>
              val renderedTrue = integer(whenTrue)
              val renderedFalse = integer(whenFalse)
              val trueText = renderDelimitedIntOperand(renderedTrue)
              val falseText = renderDelimitedIntOperand(renderedFalse)
              integers.put(
                value,
                RenderedIntExpr(
                  s"(${boolean(condition).text}) ? $trueText : $falseText",
                  ConditionalPrecedence
                )
              )
            case value @ BoolLiteral(result) =>
              booleans.put(value, RenderedBoolExpr(if (result) "1'b1" else "1'b0", BoolAtomicPrecedence))
            case value @ BoolParameterRef(name) =>
              booleans.put(value, RenderedBoolExpr(s"$name == 1", BoolAtomicPrecedence))
            case value @ BoolLocalParameterRef(name) =>
              booleans.put(value, RenderedBoolExpr(s"$name == 1", BoolAtomicPrecedence))
            case value @ BoolLessThan(left, right) =>
              booleans.put(value, renderComparison(integer(left), "<", integer(right)))
            case value @ BoolLessThanOrEqual(left, right) =>
              booleans.put(value, renderComparison(integer(left), "<=", integer(right)))
            case value @ BoolGreaterThan(left, right) =>
              booleans.put(value, renderComparison(integer(left), ">", integer(right)))
            case value @ BoolGreaterThanOrEqual(left, right) =>
              booleans.put(value, renderComparison(integer(left), ">=", integer(right)))
            case value @ BoolEqual(left, right) =>
              booleans.put(value, renderComparison(integer(left), "==", integer(right)))
            case value @ BoolNotEqual(left, right) =>
              booleans.put(value, renderComparison(integer(left), "!=", integer(right)))
            case value @ BoolNot(operand) =>
              val inner = boolean(operand)
              val needsParentheses = inner.precedence < BoolNotPrecedence || inner.text.contains(" ")
              val operandText = if (needsParentheses) s"(${inner.text})" else inner.text
              booleans.put(value, RenderedBoolExpr(s"!$operandText", BoolNotPrecedence))
            case value @ BoolAnd(left, right) =>
              booleans.put(value, renderBoolBinary(boolean(left), "&&", boolean(right), BoolAndPrecedence))
            case value @ BoolOr(left, right) =>
              booleans.put(value, renderBoolBinary(boolean(left), "||", boolean(right), BoolOrPrecedence))
          }
        }
      }
    }
    RenderedExpressionGraph(integers, booleans)
  }

  private def renderComparison(
      left: RenderedIntExpr,
      operator: String,
      right: RenderedIntExpr
  ): RenderedBoolExpr =
    RenderedBoolExpr(
      s"${renderComparisonOperand(left)} $operator ${renderComparisonOperand(right)}",
      BoolAtomicPrecedence
    )

  private def renderComparisonOperand(expression: IntExpr)(implicit context: RenderContext): String =
    renderDelimitedIntOperand(expression)

  private def renderComparisonOperand(rendered: RenderedIntExpr): String =
    renderDelimitedIntOperand(rendered)

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

  private def renderPortableCeilLog2Function(
      implicit context: RenderContext
  ): Vector[String] = {
    val functionName = context.portableCeilLog2FunctionName
    Vector(
      s"  function integer $functionName;",
      "    input integer value;",
      "    input integer minimum_result;",
      "    integer remaining;",
      "    begin",
      s"      $functionName = 0;",
      "      for (remaining = value - 1; remaining > 0; remaining = remaining >> 1) begin",
      s"        $functionName = $functionName + 1;",
      "      end",
      s"      if ($functionName < minimum_result) begin",
      s"        $functionName = minimum_result;",
      "      end",
      "    end",
      "  endfunction"
    )
  }

  /** Chooses a handwritten-style module-local helper name without reserving an identifier. */
  private def portableCeilLog2FunctionName(module: ModuleDef): String = {
    val used = moduleDeclaredIdentifiers(module)

    firstAvailableName(PortableCeilLog2FunctionBaseName, used)
  }

  private def allocateFifoInternalNames(
      module: ModuleDef,
      helperName: String
  ): FifoInternalNames = {
    val used = moduleDeclaredIdentifiers(module)
    used += helperName

    def allocate(base: String): String = {
      val value = firstAvailableName(base, used)
      used += value
      value
    }

    FifoInternalNames(
      pointerWidth = allocate("POINTER_WIDTH"),
      occupancyWidth = allocate("OCCUPANCY_WIDTH"),
      readPointer = allocate("read_pointer"),
      writePointer = allocate("write_pointer"),
      occupancy = allocate("occupancy"),
      pushFire = allocate("push_fire"),
      popFire = allocate("pop_fire")
    )
  }

  private def moduleDeclaredIdentifiers(module: ModuleDef): scala.collection.mutable.Set[String] = {
    val used = scala.collection.mutable.Set.empty[String] ++
      module.parameters.map(_.name) ++
      module.booleanParameters.map(_.name) ++
      module.localParameters.map(_.name) ++
      module.booleanLocalParameters.map(_.name) ++
      module.ports.map(_.name)
    val work = scala.collection.mutable.ArrayBuffer.empty[ModuleItem]
    work ++= module.items

    while (work.nonEmpty) {
      work.remove(work.length - 1) match {
        case _: ContinuousAssign =>
        case instance: ModuleInstance => used += instance.name
        case generate: GenerateFor =>
          used += generate.label
          used += generate.indexName
          work ++= generate.body
        case generate: GenerateIf =>
          used += generate.whenTrue.label
          used += generate.whenFalse.label
          work ++= generate.whenTrue.body
          work ++= generate.whenFalse.body
        case generate: GenerateCase =>
          generate.choices.foreach { choice =>
            used += choice.block.label
            work ++= choice.block.body
          }
          used += generate.default.label
          work ++= generate.default.body
        case process: CombinationalIf => used += process.label
        case process: SynchronousRegister => used += process.label
        case process: AsynchronousRegister => used += process.label
        case process: SynchronousEnabledRegister => used += process.label
        case process: AsynchronousEnabledRegister => used += process.label
        case memory: SynchronousReadFirstSinglePortMemory =>
          used += memory.label
          used += memory.memoryName
        case memory: SynchronousReadFirstSimpleDualPortMemory =>
          used += memory.label
          used += memory.memoryName
        case counter: SynchronousCounter => used += counter.label
        case fifo: SynchronousStreamFifo =>
          used += fifo.label
          used += fifo.memoryName
      }
    }

    used
  }

  private def firstAvailableName(
      base: String,
      used: scala.collection.Set[String]
  ): String =
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

  /** Iterative identity-aware scan keeps helper discovery stack-safe and DAG-safe. */
  private def moduleUsesPortableCeilLog2(module: ModuleDef): Boolean = {
    val seen = new java.util.IdentityHashMap[AnyRef, java.lang.Boolean]()
    val work = scala.collection.mutable.ArrayBuffer[Any](module)

    while (work.nonEmpty) {
      work.remove(work.length - 1) match {
        case _: AddressWidth | _: CeilLog2 | _: SynchronousStreamFifo => return true
        case _: String | _: BigInt | null  =>
        case reference: AnyRef if !seen.containsKey(reference) =>
          seen.put(reference, java.lang.Boolean.TRUE)
          reference match {
            case values: Iterable[_] => values.foreach(work += _)
            case product: Product    => product.productIterator.foreach(work += _)
            case _                   =>
          }
        case _ =>
      }
    }
    false
  }
}

/** Exact emitted-AST cost plan for the Min/Max lowering expansion boundary. */
private[verilog2001] object Verilog2001IntExpressionLowering {
  val MaximumExpansionNodes = 4096L

  /**
    * Counts every node in the emitted expression syntax tree and fails closed before repeated
    * conditional operands can cause target text to grow exponentially. AddressWidth and
    * CeilLog2 each lower to one two-argument constant-function call, so they contribute only
    * their operand plus the call and internal floor literal.
    */
  def expansionWithin(expression: IntExpr, maximum: Long): Boolean = {
    final case class Frame(value: AnyRef, expanded: Boolean)
    val integerCosts = new java.util.IdentityHashMap[IntExpr, java.lang.Long]()
    val booleanCosts = new java.util.IdentityHashMap[BoolExpr, java.lang.Long]()
    val work = scala.collection.mutable.ArrayBuffer(Frame(expression, expanded = false))
    val TooLarge = -1L
    val LeafCost = if (maximum >= 1L) 1L else TooLarge
    val NegativeLiteralCost = if (maximum >= 2L) 2L else TooLarge
    val BooleanReferenceCost = if (maximum >= 3L) 3L else TooLarge

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
    def duplicatedBinary(left: Long, right: Long): Long = {
      val operands = plus(left, right, 0L)
      if (operands == TooLarge || maximum < 2L || operands > (maximum - 2L) / 2L) TooLarge
      else operands * 2L + 2L
    }

    while (work.nonEmpty) {
      val frame = work.remove(work.length - 1)
      if (!isDone(frame.value)) {
        if (!frame.expanded) {
          work += Frame(frame.value, expanded = true)
          frame.value match {
            case _: Literal | _: ParameterRef | _: LocalParameterRef | _: GenerateIndexRef =>
            case AddressWidth(operand) => push(operand)
            case CeilLog2(operand)     => push(operand)
            case Negate(operand)       => push(operand)
            case Add(left, right)      => push(right); push(left)
            case Subtract(left, right) => push(right); push(left)
            case Multiply(left, right) => push(right); push(left)
            case Divide(left, right)   => push(right); push(left)
            case Modulo(left, right)   => push(right); push(left)
            case Min(left, right)      => push(right); push(left)
            case Max(left, right)      => push(right); push(left)
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
            case value @ Literal(number) =>
              integerCosts.put(value, if (number < 0) NegativeLiteralCost else LeafCost)
            case value: ParameterRef     => integerCosts.put(value, LeafCost)
            case value: LocalParameterRef => integerCosts.put(value, LeafCost)
            case value: GenerateIndexRef => integerCosts.put(value, LeafCost)
            case value @ AddressWidth(operand) =>
              integerCosts.put(value, plus(integer(operand), LeafCost, 1L))
            case value @ CeilLog2(operand) =>
              integerCosts.put(value, plus(integer(operand), LeafCost, 1L))
            case value @ Negate(operand) => integerCosts.put(value, increment(integer(operand)))
            case value @ Add(left, right) => integerCosts.put(value, plus(integer(left), integer(right), 1L))
            case value @ Subtract(left, right) => integerCosts.put(value, plus(integer(left), integer(right), 1L))
            case value @ Multiply(left, right) => integerCosts.put(value, plus(integer(left), integer(right), 1L))
            case value @ Divide(left, right) => integerCosts.put(value, plus(integer(left), integer(right), 1L))
            case value @ Modulo(left, right) => integerCosts.put(value, plus(integer(left), integer(right), 1L))
            case value @ Min(left, right) =>
              integerCosts.put(value, duplicatedBinary(integer(left), integer(right)))
            case value @ Max(left, right) =>
              integerCosts.put(value, duplicatedBinary(integer(left), integer(right)))
            case value @ Select(condition, whenTrue, whenFalse) =>
              integerCosts.put(
                value,
                plus(boolean(condition), plus(integer(whenTrue), integer(whenFalse), 0L), 1L)
              )
            case value: BoolLiteral          => booleanCosts.put(value, LeafCost)
            case value: BoolParameterRef     => booleanCosts.put(value, BooleanReferenceCost)
            case value: BoolLocalParameterRef => booleanCosts.put(value, BooleanReferenceCost)
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

}
