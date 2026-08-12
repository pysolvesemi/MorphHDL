package morphhdl.paramrtl

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.IntExpr.{
  Add,
  Divide,
  Literal,
  LocalParameterRef,
  Modulo,
  Multiply,
  Negate,
  ParameterRef,
  Subtract
}
import morphhdl.paramrtl.IntExpressionFailure.{DivisorMayBeZero, UnresolvedLocalParameter, UnresolvedParameter}
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

final case class ValidatedModuleFacts private[morphhdl] (
    orderedLocalParameters: Vector[IntegerLocalParameter],
    parameterFacts: Map[String, IntExprFacts],
    localParameterFacts: Map[String, IntExprFacts]
)

final class ValidatedDesign private[morphhdl] (
    val value: Design,
    private[morphhdl] val moduleFacts: Map[String, ValidatedModuleFacts]
)

object ParamRtlValidator {
  private type DiagnosticBuilder =
    scala.collection.mutable.Builder[Diagnostic, Vector[Diagnostic]]

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

    val facts = modules.map { module =>
      module.name -> validateModule(module, diagnostics)
    }.toMap

    val result = DiagnosticSet.from(diagnostics.result())
    if (result.isEmpty) Right(new ValidatedDesign(design, facts)) else Left(result)
  }

  private def validateModule(
      module: ModuleDef,
      diagnostics: DiagnosticBuilder
  ): ValidatedModuleFacts = {
    val modulePath = Vector("modules", module.name)
    checkIdentifier(module.name, modulePath :+ "name", "module", diagnostics)

    val parameters = module.parameters.sortBy(_.name)
    val localParameters = module.localParameters.sortBy(_.name)
    val ports = module.ports.sortBy(_.name)

    addDuplicateDiagnostics(
      parameters.map(_.name),
      modulePath :+ "parameters",
      "PRTL-DUPLICATE-PARAMETER",
      "parameter",
      diagnostics
    )
    addDuplicateDiagnostics(
      localParameters.map(_.name),
      modulePath :+ "localParameters",
      "PRTL-DUPLICATE-LOCAL-PARAMETER",
      "local parameter",
      diagnostics
    )
    addDuplicateDiagnostics(
      ports.map(_.name),
      modulePath :+ "ports",
      "PRTL-DUPLICATE-PORT",
      "port",
      diagnostics
    )

    val declarationKinds = Vector(
      "parameter" -> parameters.map(_.name).toSet,
      "local parameter" -> localParameters.map(_.name).toSet,
      "port" -> ports.map(_.name).toSet
    )
    declarationKinds.combinations(2).foreach {
      case Vector((leftKind, leftNames), (rightKind, rightNames)) =>
        leftNames.intersect(rightNames).toVector.sorted.foreach { name =>
          diagnostics += Diagnostic(
            "PRTL-DUPLICATE-DECLARATION",
            modulePath :+ "declarations" :+ name,
            s"Name '$name' is declared as both a $leftKind and a $rightKind"
          )
        }
      case _ =>
    }

    parameters.foreach { parameter =>
      val path = modulePath :+ "parameters" :+ parameter.name
      checkIdentifier(parameter.name, path :+ "name", "parameter", diagnostics)
      validateParameter(parameter, path, diagnostics)
    }

    localParameters.foreach { localParameter =>
      val path = modulePath :+ "localParameters" :+ localParameter.name
      checkIdentifier(localParameter.name, path :+ "name", "local parameter", diagnostics)
    }

    val parameterByName = firstByName(parameters)(_.name)
    val localParameterByName = firstByName(localParameters)(_.name)
    val parameterNames = parameterByName.keySet
    val localParameterNames = localParameterByName.keySet

    localParameters.foreach { localParameter =>
      validateExpressionReferences(
        localParameter.value,
        parameterNames,
        localParameterNames,
        modulePath :+ "localParameters" :+ localParameter.name :+ "value",
        diagnostics
      )
    }

    ports.foreach { port =>
      val path = modulePath :+ "ports" :+ port.name
      checkIdentifier(port.name, path :+ "name", "port", diagnostics)
      validateExpressionReferences(
        port.dataType.width,
        parameterNames,
        localParameterNames,
        path :+ "width",
        diagnostics
      )
    }

    val dependencies = localParameterByName.map { case (name, localParameter) =>
      name -> IntExpressionAnalysis
        .localParameterReferences(localParameter.value)
        .filter(localParameterNames.contains)
        .distinct
        .sorted
    }

    val localParameterGraph = analyzeLocalParameterGraph(dependencies)
    val cycleGroups = localParameterGraph.cycleGroups
    cycleGroups.foreach { names =>
      diagnostics += Diagnostic(
        "PRTL-LOCAL-PARAMETER-CYCLE",
        modulePath :+ "localParameters" :+ names.head,
        s"Local-parameter dependency cycle members: ${names.mkString(", ")}"
      )
    }

    val orderedLocalParameters = localParameterGraph.orderedNames.map(localParameterByName)

    val parameterFacts = parameterByName.flatMap { case (name, parameter) =>
      IntExpressionAnalysis.parameterFacts(parameter).map(name -> _)
    }
    var localParameterFacts = Map.empty[String, IntExprFacts]

    orderedLocalParameters.foreach { localParameter =>
      val path = modulePath :+ "localParameters" :+ localParameter.name :+ "value"
      analyzeExpression(
        localParameter.value,
        parameterFacts,
        localParameterFacts,
        path,
        diagnostics
      ).foreach(facts => localParameterFacts = localParameterFacts.updated(localParameter.name, facts))
    }

    ports.foreach { port =>
      validateWidth(
        port.dataType.width,
        parameterFacts,
        localParameterFacts,
        modulePath :+ "ports" :+ port.name :+ "width",
        diagnostics
      )
    }

    validateAssignments(module, ports, modulePath, diagnostics)

    ValidatedModuleFacts(
      orderedLocalParameters,
      parameterFacts,
      localParameterFacts
    )
  }

  private def validateParameter(
      parameter: IntegerParameter,
      path: Vector[String],
      diagnostics: DiagnosticBuilder
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

  private def validateExpressionReferences(
      expression: IntExpr,
      parameters: Set[String],
      localParameters: Set[String],
      path: Vector[String],
      diagnostics: DiagnosticBuilder
  ): Unit = expression match {
    case Literal(_) =>
    case ParameterRef(name) if !parameters.contains(name) =>
      diagnostics += Diagnostic(
        "PRTL-UNRESOLVED-PARAMETER",
        path,
        s"Integer expression references unknown public parameter '$name'"
      )
    case ParameterRef(_) =>
    case LocalParameterRef(name) if !localParameters.contains(name) =>
      diagnostics += Diagnostic(
        "PRTL-UNRESOLVED-LOCAL-PARAMETER",
        path,
        s"Integer expression references unknown local parameter '$name'"
      )
    case LocalParameterRef(_) =>
    case Negate(value) =>
      validateExpressionReferences(value, parameters, localParameters, path :+ "operand", diagnostics)
    case Add(left, right) =>
      validateBinaryReferences(left, right, parameters, localParameters, path, diagnostics)
    case Subtract(left, right) =>
      validateBinaryReferences(left, right, parameters, localParameters, path, diagnostics)
    case Multiply(left, right) =>
      validateBinaryReferences(left, right, parameters, localParameters, path, diagnostics)
    case Divide(left, right) =>
      validateBinaryReferences(left, right, parameters, localParameters, path, diagnostics)
    case Modulo(left, right) =>
      validateBinaryReferences(left, right, parameters, localParameters, path, diagnostics)
  }

  private def validateBinaryReferences(
      left: IntExpr,
      right: IntExpr,
      parameters: Set[String],
      localParameters: Set[String],
      path: Vector[String],
      diagnostics: DiagnosticBuilder
  ): Unit = {
    validateExpressionReferences(left, parameters, localParameters, path :+ "left", diagnostics)
    validateExpressionReferences(right, parameters, localParameters, path :+ "right", diagnostics)
  }

  private def analyzeExpression(
      expression: IntExpr,
      parameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts],
      path: Vector[String],
      diagnostics: DiagnosticBuilder
  ): Option[IntExprFacts] =
    IntExpressionAnalysis.analyze(expression, parameters, localParameters) match {
      case Right(facts) => Some(facts)
      case Left(DivisorMayBeZero(operator, interval)) =>
        diagnostics += Diagnostic(
          "PRTL-DIVISOR-MAY-BE-ZERO",
          path,
          s"Divisor of '$operator' is not proven nonzero over legal domain ${renderInterval(interval)}"
        )
        None
      case Left(_: UnresolvedParameter) | Left(_: UnresolvedLocalParameter) => None
    }

  private def validateWidth(
      expression: IntExpr,
      parameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts],
      path: Vector[String],
      diagnostics: DiagnosticBuilder
  ): Unit = expression match {
    case Literal(value) if value <= 0 =>
      diagnostics += Diagnostic(
        "PRTL-WIDTH-NOT-POSITIVE",
        path,
        s"Packed width literal $value is not positive"
      )
    case Literal(_) =>
    case _ =>
      analyzeExpression(expression, parameters, localParameters, path, diagnostics).foreach { facts =>
        if (!facts.interval.lower.exists(_ >= 1)) {
          diagnostics += Diagnostic(
            "PRTL-WIDTH-NOT-PROVEN-POSITIVE",
            path,
            s"Packed width domain ${renderInterval(facts.interval)} is not proven positive"
          )
        }
      }
  }

  private def validateAssignments(
      module: ModuleDef,
      ports: Vector[Port],
      modulePath: Vector[String],
      diagnostics: DiagnosticBuilder
  ): Unit = {
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

  private final case class LocalParameterGraph(
      cycleGroups: Vector[Vector[String]],
      orderedNames: Vector[String]
  )

  private def analyzeLocalParameterGraph(
      dependencies: Map[String, Vector[String]]
  ): LocalParameterGraph = {
    val nodes = dependencies.keys.toVector.sorted
    val visited = scala.collection.mutable.Set.empty[String]
    val finishOrder = scala.collection.mutable.ArrayBuffer.empty[String]

    nodes.foreach { start =>
      if (!visited.contains(start)) {
        val stack = scala.collection.mutable.ArrayBuffer((start, false))
        while (stack.nonEmpty) {
          val (node, expanded) = stack.remove(stack.length - 1)
          if (expanded) {
            finishOrder += node
          } else if (!visited.contains(node)) {
            visited += node
            stack += ((node, true))
            dependencies.getOrElse(node, Vector.empty).reverseIterator.foreach { dependency =>
              if (!visited.contains(dependency)) stack += ((dependency, false))
            }
          }
        }
      }
    }

    val dependents = nodes.map { name =>
      name -> scala.collection.mutable.ArrayBuffer.empty[String]
    }.toMap
    nodes.foreach { name =>
      dependencies.getOrElse(name, Vector.empty).foreach { dependency =>
        dependents(dependency) += name
      }
    }

    val assigned = scala.collection.mutable.Set.empty[String]
    val cycleGroupsBuilder = Vector.newBuilder[Vector[String]]
    finishOrder.reverseIterator.foreach { start =>
      if (!assigned.contains(start)) {
        val members = scala.collection.mutable.ArrayBuffer.empty[String]
        val stack = scala.collection.mutable.ArrayBuffer(start)
        assigned += start
        while (stack.nonEmpty) {
          val node = stack.remove(stack.length - 1)
          members += node
          dependents(node).reverseIterator.foreach { dependent =>
            if (!assigned.contains(dependent)) {
              assigned += dependent
              stack += dependent
            }
          }
        }

        val sortedMembers = members.sorted.toVector
        val isCycle = sortedMembers.size > 1 || dependencies(sortedMembers.head).contains(sortedMembers.head)
        if (isCycle) cycleGroupsBuilder += sortedMembers
      }
    }

    val cycleGroups = cycleGroupsBuilder.result().sortBy(_.head)
    val cyclicNames = cycleGroups.iterator.flatten.toSet
    val remainingDependencies = scala.collection.mutable.Map.empty[String, Int]
    nodes.filterNot(cyclicNames.contains).foreach { name =>
      remainingDependencies.update(name, dependencies.getOrElse(name, Vector.empty).size)
    }

    var ready = scala.collection.immutable.TreeSet.empty[String] ++
      remainingDependencies.iterator.collect { case (name, count) if count == 0 => name }
    val orderedNames = Vector.newBuilder[String]
    while (ready.nonEmpty) {
      val name = ready.head
      ready -= name
      orderedNames += name

      dependents(name).foreach { dependent =>
        if (!cyclicNames.contains(dependent)) {
          val updated = remainingDependencies(dependent) - 1
          remainingDependencies.update(dependent, updated)
          if (updated == 0) ready += dependent
        }
      }
    }

    LocalParameterGraph(cycleGroups, orderedNames.result())
  }

  private def resolvePort(
      reference: Ref,
      ports: Map[String, Port],
      path: Vector[String],
      diagnostics: DiagnosticBuilder
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

  private def renderInterval(interval: IntInterval): String = {
    val lower = interval.lower.map(_.toString).getOrElse("-infinity")
    val upper = interval.upper.map(_.toString).getOrElse("+infinity")
    s"[$lower, $upper]"
  }

  private def checkIdentifier(
      name: String,
      path: Vector[String],
      kind: String,
      diagnostics: DiagnosticBuilder
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
      diagnostics: DiagnosticBuilder
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
