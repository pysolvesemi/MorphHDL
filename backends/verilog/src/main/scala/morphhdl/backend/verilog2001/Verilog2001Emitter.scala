package morphhdl.backend.verilog2001

import morphhdl.paramrtl.IntExpr.{Literal, ParameterRef}
import morphhdl.paramrtl.ModuleItem.ContinuousAssign
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref
import morphhdl.paramrtl.Signedness.{Signed, Unsigned}
import morphhdl.paramrtl._

object Verilog2001Emitter {
  def emit(design: Design): Either[DiagnosticSet, String] =
    ParamRtlValidator.validate(design) match {
      case Left(diagnostics) => Left(diagnostics)
      case Right(validated) =>
        Verilog2001Capability.verify(validated) match {
          case Left(diagnostics) => Left(diagnostics)
          case Right(capable)    => Right(render(capable.value))
        }
    }

  private def render(design: Design): String = {
    val orderedModules = design.modules.sortBy(module => (if (module.name == design.top) 0 else 1, module.name))
    orderedModules.map(renderModule).mkString("\n\n") + "\n"
  }

  private def renderModule(module: ModuleDef): String = {
    val parameters = module.parameters.sortBy(_.name)
    val ports = module.ports.sortBy(_.name)
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
      case Literal(value) => s"[${value - 1}:0] "
      case expression     => s"[${renderIntExpr(expression)}-1:0] "
    }

    f"  $direction%-6s wire $signedness$range${port.name}"
  }

  private def renderIntExpr(expression: IntExpr): String = expression match {
    case Literal(value)     => value.toString
    case ParameterRef(name) => name
  }

  private def renderRtlExpr(expression: RtlExpr): String = expression match {
    case Ref(name) => name
  }
}
