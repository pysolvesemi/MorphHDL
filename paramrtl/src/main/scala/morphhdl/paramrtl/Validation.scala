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
import morphhdl.paramrtl.ModuleItem.{ContinuousAssign, ModuleInstance}
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
    localParameterFacts: Map[String, IntExprFacts],
    orderedInstances: Vector[ModuleItem.ModuleInstance] = Vector.empty,
    instanceFacts: Map[String, ValidatedInstanceFacts] = Map.empty
)

final case class ValidatedInstanceFacts private[morphhdl] (
    targetModule: ModuleDef,
    parameterFacts: Map[String, IntExprFacts],
    localParameterFacts: Map[String, IntExprFacts],
    instantiatedPortTypes: Map[String, PackedBits]
)

final class ValidatedDesign private[morphhdl] (
    val value: Design,
    private[morphhdl] val moduleFacts: Map[String, ValidatedModuleFacts],
    private[morphhdl] val orderedModules: Vector[ModuleDef]
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

    val moduleByName = firstByName(modules)(_.name)
    val moduleNames = moduleByName.keySet
    if (!moduleNames.contains(design.top)) {
      diagnostics += Diagnostic(
        "PRTL-UNRESOLVED-TOP",
        Vector("design", "top"),
        s"Top module '${design.top}' does not resolve to a module definition"
      )
    }

    val baseFacts = modules.map { module =>
      module.name -> validateModule(module, diagnostics)
    }.toMap

    val moduleDependencies = moduleByName.map { case (name, module) =>
      name -> module.items
        .collect {
          case instance: ModuleInstance if moduleNames.contains(instance.moduleName) =>
            instance.moduleName
        }
        .distinct
        .sorted
    }
    val moduleGraph = DependencyGraph.analyze(moduleDependencies)
    moduleGraph.cycleGroups.foreach { names =>
      diagnostics += Diagnostic(
        "PRTL-MODULE-INSTANTIATION-CYCLE",
        Vector("modules", names.head, "instances"),
        s"Module-instantiation cycle members: ${names.mkString(", ")}"
      )
    }

    val facts = modules.map { module =>
      module.name -> validateHierarchy(
        module,
        baseFacts(module.name),
        moduleByName,
        baseFacts,
        diagnostics
      )
    }.toMap

    val result = DiagnosticSet.from(diagnostics.result())
    if (result.isEmpty) {
      val orderedModules = moduleGraph.orderedNames.map(moduleByName)
      Right(new ValidatedDesign(design, facts, orderedModules))
    } else Left(result)
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
    val instances = module.items.collect { case instance: ModuleInstance => instance }.sortBy(_.name)

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
    addDuplicateDiagnostics(
      instances.map(_.name),
      modulePath :+ "instances",
      "PRTL-DUPLICATE-INSTANCE",
      "instance",
      diagnostics
    )

    val declarationKinds = Vector(
      "parameter" -> parameters.map(_.name).toSet,
      "local parameter" -> localParameters.map(_.name).toSet,
      "port" -> ports.map(_.name).toSet,
      "instance" -> instances.map(_.name).toSet
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

    instances.foreach { instance =>
      val path = modulePath :+ "instances" :+ instance.name
      checkIdentifier(instance.name, path :+ "name", "instance", diagnostics)
      checkIdentifier(instance.moduleName, path :+ "moduleName", "referenced module", diagnostics)
    }

    val dependencies = localParameterByName.map { case (name, localParameter) =>
      name -> IntExpressionAnalysis
        .localParameterReferences(localParameter.value)
        .filter(localParameterNames.contains)
        .distinct
        .sorted
    }

    val localParameterGraph = DependencyGraph.analyze(dependencies)
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

  private def validateHierarchy(
      module: ModuleDef,
      baseFacts: ValidatedModuleFacts,
      moduleByName: Map[String, ModuleDef],
      allBaseFacts: Map[String, ValidatedModuleFacts],
      diagnostics: DiagnosticBuilder
  ): ValidatedModuleFacts = {
    val modulePath = Vector("modules", module.name)
    val ports = module.ports.sortBy(_.name)
    val portByName = firstByName(ports)(_.name)
    val instances = module.items.collect { case instance: ModuleInstance => instance }.sortBy(_.name)
    val driverCounts = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)

    module.items.zipWithIndex.foreach {
      case (ContinuousAssign(target, value), index) =>
        val path = modulePath :+ "items" :+ index.toString
        val targetPort = resolvePort(target, portByName, path :+ "target", diagnostics)
        val valuePort = value match {
          case ref: Ref => resolvePort(ref, portByName, path :+ "value", diagnostics)
        }
        targetPort.foreach { port =>
          driverCounts.update(port.name, driverCounts(port.name) + 1)
          if (port.direction == Input)
            diagnostics += Diagnostic(
              "PRTL-ILLEGAL-INPUT-DRIVER",
              path :+ "target",
              s"Continuous assignment cannot drive input port '${port.name}'"
            )
        }
        for {
          targetType <- targetPort.map(_.dataType)
          valueType <- valuePort.map(_.dataType)
          if !packedTypesEquivalent(targetType, valueType, module, baseFacts)
        } diagnostics += Diagnostic(
          "PRTL-TYPE-MISMATCH",
          path,
          s"Continuous assignment target type '$targetType' does not match value type '$valueType'"
        )
      case (_: ModuleInstance, _) =>
    }

    val instanceFactsBuilder = Map.newBuilder[String, ValidatedInstanceFacts]
    instances.foreach { instance =>
      val path = modulePath :+ "instances" :+ instance.name
      val bindingPath = path :+ "parameterBindings"
      val connectionPath = path :+ "portConnections"

      addDuplicateDiagnostics(
        instance.parameterBindings.map(_.parameterName),
        bindingPath,
        "PRTL-DUPLICATE-PARAMETER-BINDING",
        "parameter binding",
        diagnostics
      )
      addDuplicateDiagnostics(
        instance.portConnections.map(_.portName),
        connectionPath,
        "PRTL-DUPLICATE-PORT-CONNECTION",
        "port connection",
        diagnostics
      )

      val parentParameters = baseFacts.parameterFacts
      val parentLocals = baseFacts.localParameterFacts
      val parentParameterNames = module.parameters.map(_.name).toSet
      val parentLocalNames = module.localParameters.map(_.name).toSet
      instance.parameterBindings.sortBy(_.parameterName).foreach { binding =>
        validateExpressionReferences(
          binding.value,
          parentParameterNames,
          parentLocalNames,
          bindingPath :+ binding.parameterName :+ "value",
          diagnostics
        )
      }

      moduleByName.get(instance.moduleName) match {
        case None =>
          diagnostics += Diagnostic(
            "PRTL-UNRESOLVED-INSTANCE-MODULE",
            path :+ "moduleName",
            s"Instance '${instance.name}' references unknown module '${instance.moduleName}'"
          )
        case Some(targetModule) =>
          val targetBaseFacts = allBaseFacts(targetModule.name)
          val targetParameters = targetModule.parameters.sortBy(_.name)
          val targetParameterByName = firstByName(targetParameters)(_.name)
          val bindingByName = firstByName(instance.parameterBindings.sortBy(_.parameterName))(_.parameterName)
          val analyzedBindings = scala.collection.mutable.Map.empty[String, IntExprFacts]

          instance.parameterBindings.sortBy(_.parameterName).foreach { binding =>
            val currentPath = bindingPath :+ binding.parameterName
            targetParameterByName.get(binding.parameterName) match {
              case None =>
                diagnostics += Diagnostic(
                  "PRTL-UNRESOLVED-INSTANCE-PARAMETER",
                  currentPath,
                  s"Module '${targetModule.name}' has no public parameter '${binding.parameterName}'"
                )
              case Some(targetParameter) =>
                analyzeExpression(
                  binding.value,
                  parentParameters,
                  parentLocals,
                  currentPath :+ "value",
                  diagnostics
                ).foreach { bindingFacts =>
                  analyzedBindings.update(binding.parameterName, bindingFacts)
                  if (!domainContained(bindingFacts.interval, targetParameter)) {
                    diagnostics += Diagnostic(
                      "PRTL-PARAMETER-BINDING-DOMAIN-NOT-PROVEN",
                      currentPath :+ "value",
                      s"Binding domain ${renderInterval(bindingFacts.interval)} is not proven within legal domain ${renderParameterDomain(
                          targetParameter
                        )} of '${targetModule.name}.${targetParameter.name}'"
                    )
                  }
                }
            }
          }

          val instantiatedParameters = targetParameters.map { parameter =>
            parameter.name -> analyzedBindings.getOrElse(
              parameter.name,
              IntExprFacts(parameter.default, IntInterval.point(parameter.default))
            )
          }.toMap
          var instantiatedLocals = Map.empty[String, IntExprFacts]
          targetBaseFacts.orderedLocalParameters.foreach { localParameter =>
            IntExpressionAnalysis
              .analyze(localParameter.value, instantiatedParameters, instantiatedLocals)
              .toOption
              .foreach(facts => instantiatedLocals = instantiatedLocals.updated(localParameter.name, facts))
          }

          val parentLocalExpressions = expandLocalExpressions(baseFacts.orderedLocalParameters, Map.empty)
          val expandedBindings = targetParameters.map { parameter =>
            val raw = bindingByName.get(parameter.name).map(_.value).getOrElse(Literal(parameter.default))
            parameter.name -> IntExpressionEquivalence.substitute(raw, Map.empty, parentLocalExpressions)
          }.toMap
          val targetLocalExpressions =
            expandLocalExpressions(targetBaseFacts.orderedLocalParameters, expandedBindings)
          val instantiatedPortTypes = targetModule.ports.map { port =>
            val width = port.dataType.width match {
              case ParameterRef(name)      => expandedBindings.getOrElse(name, port.dataType.width)
              case LocalParameterRef(name) => targetLocalExpressions.getOrElse(name, port.dataType.width)
              case other                   => substituteLocalDefinition(other, expandedBindings, targetLocalExpressions)
            }
            port.name -> port.dataType.copy(width = width)
          }.toMap
          val targetPortByName = firstByName(targetModule.ports.sortBy(_.name))(_.name)
          val connectionByName = firstByName(instance.portConnections.sortBy(_.portName))(_.portName)

          instance.portConnections.sortBy(_.portName).foreach { connection =>
            val currentPath = connectionPath :+ connection.portName
            targetPortByName.get(connection.portName) match {
              case None =>
                diagnostics += Diagnostic(
                  "PRTL-UNRESOLVED-INSTANCE-PORT",
                  currentPath,
                  s"Module '${targetModule.name}' has no port '${connection.portName}'"
                )
              case Some(targetPort) =>
                val actualPort = connection.actual match {
                  case ref: Ref => resolvePort(ref, portByName, currentPath :+ "actual", diagnostics)
                }
                actualPort.foreach { parentPort =>
                  if (targetPort.direction == Output) {
                    driverCounts.update(parentPort.name, driverCounts(parentPort.name) + 1)
                    if (parentPort.direction == Input)
                      diagnostics += Diagnostic(
                        "PRTL-ILLEGAL-INPUT-DRIVER",
                        currentPath :+ "actual",
                        s"Output port '${targetModule.name}.${targetPort.name}' cannot drive parent input '${parentPort.name}'"
                      )
                  }
                  val expected = instantiatedPortTypes(targetPort.name)
                  val instantiatedExpectedFacts = IntExpressionAnalysis
                    .analyze(targetPort.dataType.width, instantiatedParameters, instantiatedLocals)
                    .toOption
                  if (
                    !packedTypesEquivalent(
                      parentPort.dataType,
                      expected,
                      module,
                      baseFacts,
                      instantiatedExpectedFacts
                    )
                  )
                    diagnostics += Diagnostic(
                      "PRTL-INSTANCE-PORT-TYPE-MISMATCH",
                      currentPath,
                      s"Parent port '${parentPort.name}' is not type-compatible with instantiated port '${targetModule.name}.${targetPort.name}'"
                    )
                }
            }
          }

          targetModule.ports.sortBy(_.name).foreach { targetPort =>
            if (!connectionByName.contains(targetPort.name))
              diagnostics += Diagnostic(
                "PRTL-MISSING-INSTANCE-PORT-CONNECTION",
                connectionPath :+ targetPort.name,
                s"Instance '${instance.name}' is missing required port '${targetPort.name}'"
              )
          }

          instanceFactsBuilder += instance.name -> ValidatedInstanceFacts(
            targetModule,
            instantiatedParameters,
            instantiatedLocals,
            instantiatedPortTypes
          )
      }
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
            s"Output port '${port.name}' has $count drivers"
          )
        case _ =>
      }
    }

    baseFacts.copy(
      orderedInstances = instances,
      instanceFacts = instanceFactsBuilder.result()
    )
  }

  private def packedTypesEquivalent(
      left: PackedBits,
      right: PackedBits,
      parent: ModuleDef,
      parentFacts: ValidatedModuleFacts,
      rightFactsOverride: Option[IntExprFacts] = None
  ): Boolean = {
    if (left.signedness != right.signedness) false
    else {
      val locals = expandLocalExpressions(parentFacts.orderedLocalParameters, Map.empty)
      val leftFacts = expressionFacts(left.width, parentFacts)
      val rightFacts = rightFactsOverride.orElse(expressionFacts(right.width, parentFacts))
      val equalFinitePoints = (leftFacts, rightFacts) match {
        case (Some(a), Some(b)) =>
          a.interval.lower.isDefined &&
          a.interval.upper.isDefined &&
          b.interval.lower.isDefined &&
          b.interval.upper.isDefined &&
          a.interval.lower == a.interval.upper &&
          b.interval.lower == b.interval.upper &&
          a.interval == b.interval
        case _ => false
      }
      if (equalFinitePoints) true
      else {
        val leftWidth = left.width match {
          case LocalParameterRef(name) => locals.getOrElse(name, left.width)
          case other                   => substituteLocalDefinition(other, Map.empty, locals)
        }
        val rightWidth = rightFactsOverride match {
          // Instance-side widths are already expanded in their source scope. Keep the
          // shared DAG opaque so a deep local chain reaches iterative equality safely.
          case Some(_) => right.width
          case None =>
            right.width match {
              case LocalParameterRef(name) => locals.getOrElse(name, right.width)
              case other                   => substituteLocalDefinition(other, Map.empty, locals)
            }
        }
        IntExpressionEquivalence.equivalent(leftWidth, rightWidth)
      }
    }
  }

  private def expressionFacts(expression: IntExpr, facts: ValidatedModuleFacts): Option[IntExprFacts] =
    expression match {
      case Literal(value)          => Some(IntExprFacts(value, IntInterval.point(value)))
      case ParameterRef(name)      => facts.parameterFacts.get(name)
      case LocalParameterRef(name) => facts.localParameterFacts.get(name)
      case other => IntExpressionAnalysis.analyze(other, facts.parameterFacts, facts.localParameterFacts).toOption
    }

  /** Dependency-first expansion shares prior DAG nodes and never walks a local chain recursively. */
  private def expandLocalExpressions(
      ordered: Vector[IntegerLocalParameter],
      parameters: Map[String, IntExpr]
  ): Map[String, IntExpr] = {
    var expanded = Map.empty[String, IntExpr]
    ordered.foreach { localParameter =>
      expanded = expanded.updated(
        localParameter.name,
        substituteLocalDefinition(localParameter.value, parameters, expanded)
      )
    }
    expanded
  }

  private def substituteLocalDefinition(
      expression: IntExpr,
      parameters: Map[String, IntExpr],
      locals: Map[String, IntExpr]
  ): IntExpr = expression match {
    case value: Literal          => value
    case ParameterRef(name)      => parameters.getOrElse(name, expression)
    case LocalParameterRef(name) => locals.getOrElse(name, expression)
    case Negate(value) =>
      Negate(substituteLocalDefinition(value, parameters, locals))
    case Add(left, right) =>
      Add(
        substituteLocalDefinition(left, parameters, locals),
        substituteLocalDefinition(right, parameters, locals)
      )
    case Subtract(left, right) =>
      Subtract(
        substituteLocalDefinition(left, parameters, locals),
        substituteLocalDefinition(right, parameters, locals)
      )
    case Multiply(left, right) =>
      Multiply(
        substituteLocalDefinition(left, parameters, locals),
        substituteLocalDefinition(right, parameters, locals)
      )
    case Divide(left, right) =>
      Divide(
        substituteLocalDefinition(left, parameters, locals),
        substituteLocalDefinition(right, parameters, locals)
      )
    case Modulo(left, right) =>
      Modulo(
        substituteLocalDefinition(left, parameters, locals),
        substituteLocalDefinition(right, parameters, locals)
      )
  }

  private def domainContained(interval: IntInterval, parameter: IntegerParameter): Boolean = {
    val (lower, upper) = parameterBounds(parameter)
    lower.forall(required => interval.lower.exists(_ >= required)) &&
    upper.forall(required => interval.upper.exists(_ <= required))
  }

  private def renderParameterDomain(parameter: IntegerParameter): String = {
    val (lower, upper) = parameterBounds(parameter)
    renderInterval(IntInterval(lower, upper))
  }

  private def parameterBounds(parameter: IntegerParameter): (Option[BigInt], Option[BigInt]) = {
    val minimums = parameter.constraints.collect { case MinInclusive(value) => value }
    val maximums = parameter.constraints.collect { case MaxInclusive(value) => value }
    val lower = if (minimums.isEmpty) None else Some(minimums.max)
    val upper = if (maximums.isEmpty) None else Some(maximums.min)
    lower -> upper
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
