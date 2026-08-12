package morphhdl.backend.verilog2001

import morphhdl.paramrtl.IntExpr.{
  Add,
  Divide,
  GenerateIndexRef,
  Literal,
  LocalParameterRef,
  Modulo,
  Multiply,
  Negate,
  ParameterRef,
  Subtract
}
import morphhdl.paramrtl.ModuleItem.{ContinuousAssign, GenerateFor, ModuleInstance}
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
    val parameters = module.parameters.sortBy(_.name)
    val ports = module.ports.sortBy(_.name)
    val localParameters = facts.orderedLocalParameters
    val instances = facts.orderedInstances
    val generateFors = module.items.collect { case generate: GenerateFor => generate }.sortBy(_.label)
    val assignments = module.items.collect { case assignment: ContinuousAssign => assignment }.sortBy { assignment =>
      (assignment.target.name, renderRtlExpr(assignment.value))
    }

    val lines = Vector.newBuilder[String]

    if (parameters.nonEmpty) {
      lines += s"module ${module.name} #("
      parameters.zipWithIndex.foreach { case (parameter, index) =>
        val comma = if (index == parameters.size - 1) "" else ","
        lines += s"  parameter integer ${parameter.name} = ${parameter.default}$comma"
      }
      lines += ") ("
    } else {
      lines += s"module ${module.name} ("
    }

    ports.zipWithIndex.foreach { case (port, index) =>
      val comma = if (index == ports.size - 1) "" else ","
      lines += renderPort(port) + comma
    }
    lines += ");"

    if (localParameters.nonEmpty) {
      lines += ""
      localParameters.foreach { localParameter =>
        lines += s"  localparam integer ${localParameter.name} = ${renderIntExpr(localParameter.value)};"
      }
    }

    if (instances.nonEmpty) {
      lines += ""
      instances.zipWithIndex.foreach { case (instance, index) =>
        renderInstance(instance, "  ").foreach(lines += _)
        if (index != instances.size - 1) lines += ""
      }
    }

    if (generateFors.nonEmpty) {
      lines += ""
      generateFors.foreach { generate =>
        lines += s"  genvar ${generate.indexName};"
      }
      lines += "  generate"
      generateFors.zipWithIndex.foreach { case (generate, generateIndex) =>
        if (generateIndex != 0) lines += ""
        lines +=
          s"    for (${generate.indexName} = 0; ${generate.indexName} < ${renderIntExpr(
              generate.count
            )}; ${generate.indexName} = ${generate.indexName} + 1) begin : ${generate.label}"
        val bodyInstances = generate.body.collect { case instance: ModuleInstance => instance }.sortBy(_.name)
        bodyInstances.zipWithIndex.foreach { case (instance, instanceIndex) =>
          renderInstance(instance, "      ").foreach(lines += _)
          if (instanceIndex != bodyInstances.size - 1) lines += ""
        }
        lines += "    end"
      }
      lines += "  endgenerate"
    }

    if (assignments.nonEmpty) {
      lines += ""
      assignments.foreach { assignment =>
        lines += s"  assign ${assignment.target.name} = ${renderRtlExpr(assignment.value)};"
      }
    }

    lines += ""
    lines += "endmodule"
    lines.result().mkString("\n")
  }

  private def renderInstance(instance: ModuleInstance, indent: String): Vector[String] = {
    val parameterBindings = instance.parameterBindings.sortBy(_.parameterName)
    val portConnections = instance.portConnections.sortBy(_.portName)
    val lines = Vector.newBuilder[String]
    val associationIndent = indent + "  "

    if (parameterBindings.nonEmpty) {
      lines += s"$indent${instance.moduleName} #("
      parameterBindings.zipWithIndex.foreach { case (binding, index) =>
        val comma = if (index == parameterBindings.size - 1) "" else ","
        lines += s"$associationIndent.${binding.parameterName}(${renderIntExpr(binding.value)})$comma"
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

  private def renderPort(port: Port): String = {
    val direction = port.direction match {
      case Input  => "input"
      case Output => "output"
    }
    val signedness = port.dataType.signedness match {
      case Unsigned => ""
      case Signed   => "signed "
    }
    val range = port.dataType.width match {
      case Literal(value)          => s"[${value - 1}:0] "
      case ParameterRef(name)      => s"[$name-1:0] "
      case LocalParameterRef(name) => s"[$name-1:0] "
      case expression              => s"[(${renderIntExpr(expression)})-1:0] "
    }

    f"  $direction%-6s wire $signedness$range${port.name}"
  }

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

  private def renderRtlExpr(expression: RtlExpr): String = expression match {
    case Ref(name) => name
    case IndexedPartSelect(base, offset, width) =>
      s"${base.name}[${renderIntExpr(offset)} +: ${renderIntExpr(width)}]"
  }
}
