package morphhdl.paramrtl

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{Literal, ParameterRef}
import morphhdl.paramrtl.ModuleItem.ContinuousAssign
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.Ref

final case class Diagnostic(code: String, path: Vector[String], message: String) {
  def pathString: String = path.mkString("/")
}

final case class DiagnosticSet private (values: Vector[Diagnostic]) {
  def codes: Vector[String] = values.map(_.code)
  def isEmpty: Boolean = values.isEmpty
}

object DiagnosticSet {
  def from(values: Vector[Diagnostic]): DiagnosticSet = {
    val ordered = values.distinct.sortBy { diagnostic =>
      (diagnostic.pathString, diagnostic.code, diagnostic.message)
    }
    new DiagnosticSet(ordered)
  }
}

final class ValidatedDesign private[morphhdl] (val value: Design)

object ParamRtlValidator {
  private val LogicalIdentifier = "[A-Za-z_][A-Za-z0-9_]*".r

  def validate(design: Design): Either[DiagnosticSet, ValidatedDesign] = {
    val diagnostics = Vector.newBuilder[Diagnostic]
    val modules = design.modules.sortBy(_.name)

    checkIdentifier(design.top, Vector("design", "top"), "top module", diagnostics)
    addDuplicateDiagnostics(
      modules.map(_.name),
      Vector("design", "modules"),
      "PRTL-DUPLICATE-MODULE",
      "module",
      diagnostics
    )

    val moduleNames = modules.map(_.name).toSet
    if (!moduleNames.contains(design.top)) {
      diagnostics += Diagnostic(
        "PRTL-UNRESOLVED-TOP",
        Vector("design", "top"),
        s"Top module '${design.top}' does not resolve to a module definition"
      )
    }

    modules.foreach(validateModule(_, diagnostics))

    val result = DiagnosticSet.from(diagnostics.result())
    if (result.isEmpty) Right(new ValidatedDesign(design)) else Left(result)
  }

  private def validateModule(
      module: ModuleDef,
      diagnostics: scala.collection.mutable.Builder[Diagnostic, Vector[Diagnostic]]
  ): Unit = {
    val modulePath = Vector("modules", module.name)
    checkIdentifier(module.name, modulePath :+ "name", "module", diagnostics)

    val parameters = module.parameters.sortBy(_.name)
    val ports = module.ports.sortBy(_.name)

    addDuplicateDiagnostics(
      parameters.map(_.name),
      modulePath :+ "parameters",
      "PRTL-DUPLICATE-PARAMETER",
      "parameter",
      diagnostics
    )
    addDuplicateDiagnostics(
      ports.map(_.name),
      modulePath :+ "ports",
      "PRTL-DUPLICATE-PORT",
      "port",
      diagnostics
    )

    val parameterNames = parameters.map(_.name).toSet
    val portNames = ports.map(_.name).toSet
    parameterNames.intersect(portNames).toVector.sorted.foreach { name =>
      diagnostics += Diagnostic(
        "PRTL-DUPLICATE-DECLARATION",
        modulePath :+ "declarations" :+ name,
        s"Name '$name' is declared as both a parameter and a port"
      )
    }

    parameters.foreach { parameter =>
      val path = modulePath :+ "parameters" :+ parameter.name
      checkIdentifier(parameter.name, path :+ "name", "parameter", diagnostics)
      validateParameter(parameter, path, diagnostics)
    }

    val parameterByName = firstByName(parameters)(_.name)
    ports.foreach { port =>
      val path = modulePath :+ "ports" :+ port.name
      checkIdentifier(port.name, path :+ "name", "port", diagnostics)
      validateWidth(port.dataType.width, parameterByName, path :+ "width", diagnostics)
    }

    val portByName = firstByName(ports)(_.name)
    val driverCounts = scala.collection.mutable.Map[String, Int]().withDefaultValue(0)

    module.items.zipWithIndex.foreach { case (ContinuousAssign(target, value), index) =>
      val path = modulePath :+ "items" :+ index.toString
      val targetPort = resolvePort(target, portByName, path :+ "target", diagnostics)
      val valuePort = value match {
        case ref: Ref => resolvePort(ref, portByName, path :+ "value", diagnostics)
      }

      targetPort.foreach { port =>
        driverCounts.update(port.name, driverCounts(port.name) + 1)
        if (port.direction == Input) {
          diagnostics += Diagnostic(
            "PRTL-ILLEGAL-INPUT-DRIVER",
            path :+ "target",
            s"Continuous assignment cannot drive input port '${port.name}'"
          )
        }
      }

      for {
        targetType <- targetPort.map(_.dataType)
        valueType <- valuePort.map(_.dataType)
        if targetType != valueType
      } diagnostics += Diagnostic(
        "PRTL-TYPE-MISMATCH",
        path,
        s"Continuous assignment target type '$targetType' does not match value type '$valueType'"
      )
    }

    ports.filter(_.direction == Output).foreach { port =>
      driverCounts(port.name) match {
        case 0 =>
          diagnostics += Diagnostic(
            "PRTL-UNDRIVEN-OUTPUT",
            modulePath :+ "ports" :+ port.name,
            s"Output port '${port.name}' has no driver"
          )
        case count if count > 1 =>
          diagnostics += Diagnostic(
            "PRTL-MULTIPLE-DRIVERS",
            modulePath :+ "ports" :+ port.name,
            s"Output port '${port.name}' has $count continuous drivers"
          )
        case _ =>
      }
    }
  }

  private def validateParameter(
      parameter: IntegerParameter,
      path: Vector[String],
      diagnostics: scala.collection.mutable.Builder[Diagnostic, Vector[Diagnostic]]
  ): Unit = {
    val minimums = parameter.constraints.collect { case MinInclusive(value) => value }
    val maximums = parameter.constraints.collect { case MaxInclusive(value) => value }
    val lower = if (minimums.isEmpty) None else Some(minimums.max)
    val upper = if (maximums.isEmpty) None else Some(maximums.min)

    for {
      min <- lower
      max <- upper
      if min > max
    } diagnostics += Diagnostic(
      "PRTL-INCONSISTENT-CONSTRAINTS",
      path :+ "constraints",
      s"Parameter '${parameter.name}' has an empty legal range: minimum $min exceeds maximum $max"
    )

    parameter.constraints.zipWithIndex.foreach {
      case (MinInclusive(value), index) if parameter.default < value =>
        diagnostics += Diagnostic(
          "PRTL-DEFAULT-VIOLATES-CONSTRAINT",
          path :+ "constraints" :+ index.toString,
          s"Default ${parameter.default} for '${parameter.name}' is smaller than minimum $value"
        )
      case (MaxInclusive(value), index) if parameter.default > value =>
        diagnostics += Diagnostic(
          "PRTL-DEFAULT-VIOLATES-CONSTRAINT",
          path :+ "constraints" :+ index.toString,
          s"Default ${parameter.default} for '${parameter.name}' is larger than maximum $value"
        )
      case _ =>
    }
  }

  private def validateWidth(
      expression: IntExpr,
      parameters: Map[String, IntegerParameter],
      path: Vector[String],
      diagnostics: scala.collection.mutable.Builder[Diagnostic, Vector[Diagnostic]]
  ): Unit = expression match {
    case Literal(value) if value <= 0 =>
      diagnostics += Diagnostic(
        "PRTL-WIDTH-NOT-POSITIVE",
        path,
        s"Packed width literal $value is not positive"
      )
    case Literal(_) =>
    case ParameterRef(name) =>
      parameters.get(name) match {
        case None =>
          diagnostics += Diagnostic(
            "PRTL-UNRESOLVED-PARAMETER",
            path,
            s"Packed width references unknown parameter '$name'"
          )
        case Some(parameter) =>
          val lowerBounds = parameter.constraints.collect { case MinInclusive(value) => value }
          if (lowerBounds.isEmpty || lowerBounds.max < 1) {
            diagnostics += Diagnostic(
              "PRTL-WIDTH-NOT-PROVEN-POSITIVE",
              path,
              s"Packed width parameter '$name' needs a minimum constraint of at least 1"
            )
          }
      }
  }

  private def resolvePort(
      reference: Ref,
      ports: Map[String, Port],
      path: Vector[String],
      diagnostics: scala.collection.mutable.Builder[Diagnostic, Vector[Diagnostic]]
  ): Option[Port] = ports.get(reference.name) match {
    case some @ Some(_) => some
    case None =>
      diagnostics += Diagnostic(
        "PRTL-UNRESOLVED-RTL-REFERENCE",
        path,
        s"RTL reference '${reference.name}' does not resolve to a port"
      )
      None
  }

  private def checkIdentifier(
      name: String,
      path: Vector[String],
      kind: String,
      diagnostics: scala.collection.mutable.Builder[Diagnostic, Vector[Diagnostic]]
  ): Unit = name match {
    case LogicalIdentifier() =>
    case _ =>
      diagnostics += Diagnostic(
        "PRTL-INVALID-IDENTIFIER",
        path,
        s"$kind identifier '$name' must match [A-Za-z_][A-Za-z0-9_]*"
      )
  }

  private def addDuplicateDiagnostics(
      names: Vector[String],
      path: Vector[String],
      code: String,
      kind: String,
      diagnostics: scala.collection.mutable.Builder[Diagnostic, Vector[Diagnostic]]
  ): Unit =
    names
      .groupBy(identity)
      .toVector
      .collect { case (name, occurrences) if occurrences.size > 1 => name }
      .sorted
      .foreach { name =>
        diagnostics += Diagnostic(
          code,
          path :+ name,
          s"$kind '$name' is declared more than once"
        )
      }

  private def firstByName[A](values: Vector[A])(nameOf: A => String): Map[String, A] =
    values.foldLeft(Map.empty[String, A]) { (result, value) =>
      val name = nameOf(value)
      if (result.contains(name)) result else result.updated(name, value)
    }
}
