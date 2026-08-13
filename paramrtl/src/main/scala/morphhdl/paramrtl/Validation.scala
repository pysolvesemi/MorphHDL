package morphhdl.paramrtl

import morphhdl.paramrtl.IntConstraint.{MaxInclusive, MinInclusive}
import morphhdl.paramrtl.BoolExpr.{
  And => BoolAnd,
  Equal => BoolEqual,
  GreaterThan => BoolGreaterThan,
  GreaterThanOrEqual => BoolGreaterThanOrEqual,
  LessThan => BoolLessThan,
  LessThanOrEqual => BoolLessThanOrEqual,
  Literal => BoolLiteral,
  Not => BoolNot,
  NotEqual => BoolNotEqual,
  Or => BoolOr,
  ParameterRef => BoolParameterRef
}
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
  Select,
  Subtract
}
import morphhdl.paramrtl.IntExpressionFailure.{
  DivisorMayBeZero,
  UnresolvedBooleanParameter,
  UnresolvedGenerateIndex,
  UnresolvedLocalParameter,
  UnresolvedParameter
}
import morphhdl.paramrtl.ModuleItem.{ContinuousAssign, GenerateFor, GenerateIf, ModuleInstance}
import morphhdl.paramrtl.PortDirection.{Input, Output}
import morphhdl.paramrtl.RtlExpr.{IndexedPartSelect, Ref}
import morphhdl.paramrtl.Signedness.Unsigned

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
    booleanParameters: Map[String, BooleanParameter] = Map.empty,
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
      name -> collectInstances(module.items)
        .collect { case instance if moduleNames.contains(instance.moduleName) => instance.moduleName }
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
    val booleanParameters = module.booleanParameters.sortBy(_.name)
    val localParameters = module.localParameters.sortBy(_.name)
    val ports = module.ports.sortBy(_.name)
    val instances = module.items.collect { case instance: ModuleInstance => instance }.sortBy(_.name)
    val generateFors = module.items.collect { case generate: GenerateFor => generate }.sortBy(_.label)
    val generateIfs = module.items.collect { case generate: GenerateIf => generate }.sortBy(generateIfSortKey)
    val generateIfBlocks = generateIfs.flatMap(generateBlocks)

    addDuplicateDiagnostics(
      parameters.map(_.name),
      modulePath :+ "parameters",
      "PRTL-DUPLICATE-PARAMETER",
      "parameter",
      diagnostics
    )
    addDuplicateDiagnostics(
      booleanParameters.map(_.name),
      modulePath :+ "booleanParameters",
      "PRTL-DUPLICATE-BOOLEAN-PARAMETER",
      "Boolean parameter",
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
    addDuplicateDiagnostics(
      generateFors.map(_.label) ++ generateIfBlocks.map(_.label),
      modulePath :+ "generateFors",
      "PRTL-DUPLICATE-GENERATE-LABEL",
      "generate label",
      diagnostics
    )
    addDuplicateDiagnostics(
      generateFors.map(_.indexName),
      modulePath :+ "generateIndices",
      "PRTL-DUPLICATE-GENERATE-INDEX",
      "generate index",
      diagnostics
    )

    val declarationKinds = Vector(
      "parameter" -> parameters.map(_.name).toSet,
      "Boolean parameter" -> booleanParameters.map(_.name).toSet,
      "local parameter" -> localParameters.map(_.name).toSet,
      "port" -> ports.map(_.name).toSet,
      "instance" -> instances.map(_.name).toSet,
      "generate label" -> (generateFors.map(_.label) ++ generateIfBlocks.map(_.label)).toSet,
      "generate index" -> generateFors.map(_.indexName).toSet
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

    booleanParameters.foreach { parameter =>
      val path = modulePath :+ "booleanParameters" :+ parameter.name
      checkIdentifier(parameter.name, path :+ "name", "Boolean parameter", diagnostics)
    }

    localParameters.foreach { localParameter =>
      val path = modulePath :+ "localParameters" :+ localParameter.name
      checkIdentifier(localParameter.name, path :+ "name", "local parameter", diagnostics)
    }

    val parameterByName = firstByName(parameters)(_.name)
    val booleanParameterByName = firstByName(booleanParameters)(_.name)
    val localParameterByName = firstByName(localParameters)(_.name)
    val parameterNames = parameterByName.keySet
    val booleanParameterNames = booleanParameterByName.keySet
    val localParameterNames = localParameterByName.keySet

    localParameters.foreach { localParameter =>
      validateExpressionReferences(
        localParameter.value,
        parameterNames,
        localParameterNames,
        modulePath :+ "localParameters" :+ localParameter.name :+ "value",
        diagnostics,
        booleanParameters = booleanParameterByName
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
        diagnostics,
        booleanParameters = booleanParameterByName
      )
      IntExpressionAnalysis
        .parameterReferences(port.dataType.width)
        .toSet
        .intersect(booleanParameterNames)
        .toVector
        .sorted
        .headOption
        .foreach { name =>
          diagnostics += Diagnostic(
            "PRTL-PUBLIC-PORT-CONDITIONALITY-UNSUPPORTED",
            path :+ "width",
            s"Public port '${port.name}' width cannot depend on Boolean parameter '$name'"
          )
        }
    }

    instances.foreach { instance =>
      val path = modulePath :+ "instances" :+ instance.name
      checkIdentifier(instance.name, path :+ "name", "instance", diagnostics)
      checkIdentifier(instance.moduleName, path :+ "moduleName", "referenced module", diagnostics)
    }

    generateFors.foreach { generate =>
      val path = modulePath :+ "generateFors" :+ generate.label
      checkIdentifier(generate.label, path :+ "label", "generate label", diagnostics)
      checkIdentifier(generate.indexName, path :+ "indexName", "generate index", diagnostics)
      validateExpressionReferences(
        generate.count,
        parameterNames,
        localParameterNames,
        path :+ "count",
        diagnostics,
        Set.empty,
        booleanParameterByName
      )

      val bodyInstances = generate.body.collect { case instance: ModuleInstance => instance }.sortBy(_.name)
      addDuplicateDiagnostics(
        bodyInstances.map(_.name),
        path :+ "instances",
        "PRTL-DUPLICATE-INSTANCE",
        "instance",
        diagnostics
      )
      bodyInstances.foreach { instance =>
        val instancePath = path :+ "instances" :+ instance.name
        checkIdentifier(instance.name, instancePath :+ "name", "instance", diagnostics)
        checkIdentifier(instance.moduleName, instancePath :+ "moduleName", "referenced module", diagnostics)
      }
      generate.body.zipWithIndex.foreach {
        case (_: ModuleInstance, _) =>
        case (_: GenerateFor, index) =>
          diagnostics += Diagnostic(
            "PRTL-NESTED-GENERATE-UNSUPPORTED",
            path :+ "body" :+ index.toString,
            "Nested generate-for loops are not supported by this IR tranche"
          )
        case (_: GenerateIf, index) =>
          diagnostics += Diagnostic(
            "PRTL-NESTED-GENERATE-UNSUPPORTED",
            path :+ "body" :+ index.toString,
            "Generate-for bodies cannot contain generate-if constructs"
          )
        case (_, index) =>
          diagnostics += Diagnostic(
            "PRTL-GENERATE-BODY-ITEM-UNSUPPORTED",
            path :+ "body" :+ index.toString,
            "Generate-for bodies currently support module instances only"
          )
      }
    }

    if (generateIfs.size > 1) {
      generateIfs.drop(1).foreach { generate =>
        diagnostics += Diagnostic(
          "PRTL-MULTIPLE-GENERATE-IF-UNSUPPORTED",
          modulePath :+ "generateIfs" :+ generate.whenTrue.label,
          "At most one top-level generate-if is supported per module"
        )
      }
    }

    generateIfs.foreach { generate =>
      val path = modulePath :+ "generateIfs" :+ generate.whenTrue.label
      validateBooleanExpression(
        generate.condition,
        booleanParameterByName,
        parameterNames,
        localParameterNames,
        path :+ "condition",
        diagnostics
      )

      generateBlocks(generate).zipWithIndex.foreach { case (block, branchIndex) =>
        val branchName = if (branchIndex == 0) "whenTrue" else "whenFalse"
        val branchPath = path :+ branchName
        checkIdentifier(block.label, branchPath :+ "label", "generate branch label", diagnostics)
        val bodyInstances = block.body.collect { case instance: ModuleInstance => instance }.sortBy(_.name)
        addDuplicateDiagnostics(
          bodyInstances.map(_.name),
          branchPath :+ "instances",
          "PRTL-DUPLICATE-INSTANCE",
          "instance",
          diagnostics
        )
        bodyInstances.foreach { instance =>
          val instancePath = branchPath :+ "instances" :+ instance.name
          checkIdentifier(instance.name, instancePath :+ "name", "instance", diagnostics)
          checkIdentifier(instance.moduleName, instancePath :+ "moduleName", "referenced module", diagnostics)
        }
        block.body.zipWithIndex.foreach {
          case (_: ModuleInstance, _)   =>
          case (_: ContinuousAssign, _) =>
          case (_: GenerateFor, index) =>
            diagnostics += Diagnostic(
              "PRTL-NESTED-GENERATE-UNSUPPORTED",
              branchPath :+ "body" :+ index.toString,
              "Generate-if branches cannot contain another generate construct"
            )
          case (_: GenerateIf, index) =>
            diagnostics += Diagnostic(
              "PRTL-NESTED-GENERATE-UNSUPPORTED",
              branchPath :+ "body" :+ index.toString,
              "Generate-if branches cannot contain another generate construct"
            )
        }
      }

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
        diagnostics,
        booleanParameters = booleanParameterByName
      ).foreach(facts => localParameterFacts = localParameterFacts.updated(localParameter.name, facts))
    }

    generateIfs.foreach { generate =>
      analyzeBooleanExpression(
        generate.condition,
        booleanParameterByName,
        parameterFacts,
        localParameterFacts,
        modulePath :+ "generateIfs" :+ generate.whenTrue.label :+ "condition",
        diagnostics
      )
    }

    ports.foreach { port =>
      validateWidth(
        port.dataType.width,
        parameterFacts,
        localParameterFacts,
        modulePath :+ "ports" :+ port.name :+ "width",
        diagnostics,
        booleanParameterByName
      )
    }

    generateFors.foreach { generate =>
      val path = modulePath :+ "generateFors" :+ generate.label :+ "count"
      analyzeExpression(
        generate.count,
        parameterFacts,
        localParameterFacts,
        path,
        diagnostics,
        booleanParameters = booleanParameterByName
      ).foreach { facts =>
        if (!facts.interval.lower.exists(_ >= 1))
          diagnostics += Diagnostic(
            "PRTL-GENERATE-COUNT-NOT-PROVEN-POSITIVE",
            path,
            s"Generate count domain ${renderInterval(facts.interval)} is not proven positive"
          )
      }
    }
    ValidatedModuleFacts(
      orderedLocalParameters,
      parameterFacts,
      localParameterFacts,
      booleanParameterByName
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

  private def validateBooleanExpression(
      expression: BoolExpr,
      booleanParameters: Map[String, BooleanParameter],
      integerParameters: Set[String],
      localParameters: Set[String],
      path: Vector[String],
      diagnostics: DiagnosticBuilder,
      generateIndices: Set[String] = Set.empty
  ): Unit = expression match {
    case BoolLiteral(_) =>
    case BoolParameterRef(name) =>
      if (!booleanParameters.contains(name)) {
        diagnostics += Diagnostic(
          "PRTL-UNRESOLVED-BOOLEAN-PARAMETER",
          path,
          s"Boolean expression references unknown public parameter '$name'"
        )
      }
    case BoolNot(value) =>
      validateBooleanExpression(
        value,
        booleanParameters,
        integerParameters,
        localParameters,
        path :+ "operand",
        diagnostics,
        generateIndices
      )
    case BoolAnd(left, right) =>
      validateBooleanBinary(
        left,
        right,
        booleanParameters,
        integerParameters,
        localParameters,
        path,
        diagnostics,
        generateIndices
      )
    case BoolOr(left, right) =>
      validateBooleanBinary(
        left,
        right,
        booleanParameters,
        integerParameters,
        localParameters,
        path,
        diagnostics,
        generateIndices
      )
    case BoolLessThan(left, right) =>
      validateComparisonReferences(
        left,
        right,
        booleanParameters,
        integerParameters,
        localParameters,
        path,
        diagnostics,
        generateIndices
      )
    case BoolLessThanOrEqual(left, right) =>
      validateComparisonReferences(
        left,
        right,
        booleanParameters,
        integerParameters,
        localParameters,
        path,
        diagnostics,
        generateIndices
      )
    case BoolGreaterThan(left, right) =>
      validateComparisonReferences(
        left,
        right,
        booleanParameters,
        integerParameters,
        localParameters,
        path,
        diagnostics,
        generateIndices
      )
    case BoolGreaterThanOrEqual(left, right) =>
      validateComparisonReferences(
        left,
        right,
        booleanParameters,
        integerParameters,
        localParameters,
        path,
        diagnostics,
        generateIndices
      )
    case BoolEqual(left, right) =>
      validateComparisonReferences(
        left,
        right,
        booleanParameters,
        integerParameters,
        localParameters,
        path,
        diagnostics,
        generateIndices
      )
    case BoolNotEqual(left, right) =>
      validateComparisonReferences(
        left,
        right,
        booleanParameters,
        integerParameters,
        localParameters,
        path,
        diagnostics,
        generateIndices
      )
  }

  private def validateBooleanBinary(
      left: BoolExpr,
      right: BoolExpr,
      booleanParameters: Map[String, BooleanParameter],
      integerParameters: Set[String],
      localParameters: Set[String],
      path: Vector[String],
      diagnostics: DiagnosticBuilder,
      generateIndices: Set[String]
  ): Unit = {
    validateBooleanExpression(
      left,
      booleanParameters,
      integerParameters,
      localParameters,
      path :+ "left",
      diagnostics,
      generateIndices
    )
    validateBooleanExpression(
      right,
      booleanParameters,
      integerParameters,
      localParameters,
      path :+ "right",
      diagnostics,
      generateIndices
    )
  }

  private def validateComparisonReferences(
      left: IntExpr,
      right: IntExpr,
      booleanParameters: Map[String, BooleanParameter],
      integerParameters: Set[String],
      localParameters: Set[String],
      path: Vector[String],
      diagnostics: DiagnosticBuilder,
      generateIndices: Set[String]
  ): Unit = {
    validateExpressionReferences(
      left,
      integerParameters,
      localParameters,
      path :+ "left",
      diagnostics,
      generateIndices,
      booleanParameters = booleanParameters
    )
    validateExpressionReferences(
      right,
      integerParameters,
      localParameters,
      path :+ "right",
      diagnostics,
      generateIndices,
      booleanParameters = booleanParameters
    )
  }

  private def analyzeBooleanExpression(
      expression: BoolExpr,
      booleanParameters: Map[String, BooleanParameter],
      parameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts],
      path: Vector[String],
      diagnostics: DiagnosticBuilder
  ): Unit = expression match {
    case BoolLiteral(_) | BoolParameterRef(_) =>
    case BoolNot(value) =>
      analyzeBooleanExpression(
        value,
        booleanParameters,
        parameters,
        localParameters,
        path :+ "operand",
        diagnostics
      )
    case BoolAnd(left, right) =>
      analyzeBooleanBinary(left, right, booleanParameters, parameters, localParameters, path, diagnostics)
    case BoolOr(left, right) =>
      analyzeBooleanBinary(left, right, booleanParameters, parameters, localParameters, path, diagnostics)
    case BoolLessThan(left, right) =>
      analyzeComparison(left, right, booleanParameters, parameters, localParameters, path, diagnostics)
    case BoolLessThanOrEqual(left, right) =>
      analyzeComparison(left, right, booleanParameters, parameters, localParameters, path, diagnostics)
    case BoolGreaterThan(left, right) =>
      analyzeComparison(left, right, booleanParameters, parameters, localParameters, path, diagnostics)
    case BoolGreaterThanOrEqual(left, right) =>
      analyzeComparison(left, right, booleanParameters, parameters, localParameters, path, diagnostics)
    case BoolEqual(left, right) =>
      analyzeComparison(left, right, booleanParameters, parameters, localParameters, path, diagnostics)
    case BoolNotEqual(left, right) =>
      analyzeComparison(left, right, booleanParameters, parameters, localParameters, path, diagnostics)
  }

  private def analyzeBooleanBinary(
      left: BoolExpr,
      right: BoolExpr,
      booleanParameters: Map[String, BooleanParameter],
      parameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts],
      path: Vector[String],
      diagnostics: DiagnosticBuilder
  ): Unit = {
    analyzeBooleanExpression(
      left,
      booleanParameters,
      parameters,
      localParameters,
      path :+ "left",
      diagnostics
    )
    analyzeBooleanExpression(
      right,
      booleanParameters,
      parameters,
      localParameters,
      path :+ "right",
      diagnostics
    )
  }

  private def analyzeComparison(
      left: IntExpr,
      right: IntExpr,
      booleanParameters: Map[String, BooleanParameter],
      parameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts],
      path: Vector[String],
      diagnostics: DiagnosticBuilder
  ): Unit = {
    analyzeExpression(
      left,
      parameters,
      localParameters,
      path :+ "left",
      diagnostics,
      booleanParameters = booleanParameters
    )
    analyzeExpression(
      right,
      parameters,
      localParameters,
      path :+ "right",
      diagnostics,
      booleanParameters = booleanParameters
    )
  }

  private def validateExpressionReferences(
      expression: IntExpr,
      parameters: Set[String],
      localParameters: Set[String],
      path: Vector[String],
      diagnostics: DiagnosticBuilder,
      generateIndices: Set[String] = Set.empty,
      booleanParameters: Map[String, BooleanParameter] = Map.empty
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
    case GenerateIndexRef(name) if !generateIndices.contains(name) =>
      diagnostics += Diagnostic(
        "PRTL-GENERATE-INDEX-OUT-OF-SCOPE",
        path,
        s"Generate index '$name' is not in scope for this integer expression"
      )
    case GenerateIndexRef(_) =>
    case Negate(value) =>
      validateExpressionReferences(
        value,
        parameters,
        localParameters,
        path :+ "operand",
        diagnostics,
        generateIndices,
        booleanParameters
      )
    case Add(left, right) =>
      validateBinaryReferences(
        left,
        right,
        parameters,
        localParameters,
        path,
        diagnostics,
        generateIndices,
        booleanParameters
      )
    case Subtract(left, right) =>
      validateBinaryReferences(
        left,
        right,
        parameters,
        localParameters,
        path,
        diagnostics,
        generateIndices,
        booleanParameters
      )
    case Multiply(left, right) =>
      validateBinaryReferences(
        left,
        right,
        parameters,
        localParameters,
        path,
        diagnostics,
        generateIndices,
        booleanParameters
      )
    case Divide(left, right) =>
      validateBinaryReferences(
        left,
        right,
        parameters,
        localParameters,
        path,
        diagnostics,
        generateIndices,
        booleanParameters
      )
    case Modulo(left, right) =>
      validateBinaryReferences(
        left,
        right,
        parameters,
        localParameters,
        path,
        diagnostics,
        generateIndices,
        booleanParameters
      )
    case Select(condition, whenTrue, whenFalse) =>
      validateBooleanExpression(
        condition,
        booleanParameters,
        parameters,
        localParameters,
        path :+ "condition",
        diagnostics,
        generateIndices
      )
      validateExpressionReferences(
        whenTrue,
        parameters,
        localParameters,
        path :+ "whenTrue",
        diagnostics,
        generateIndices,
        booleanParameters
      )
      validateExpressionReferences(
        whenFalse,
        parameters,
        localParameters,
        path :+ "whenFalse",
        diagnostics,
        generateIndices,
        booleanParameters
      )
  }

  private def validateBinaryReferences(
      left: IntExpr,
      right: IntExpr,
      parameters: Set[String],
      localParameters: Set[String],
      path: Vector[String],
      diagnostics: DiagnosticBuilder,
      generateIndices: Set[String],
      booleanParameters: Map[String, BooleanParameter]
  ): Unit = {
    validateExpressionReferences(
      left,
      parameters,
      localParameters,
      path :+ "left",
      diagnostics,
      generateIndices,
      booleanParameters
    )
    validateExpressionReferences(
      right,
      parameters,
      localParameters,
      path :+ "right",
      diagnostics,
      generateIndices,
      booleanParameters
    )
  }

  private def analyzeExpression(
      expression: IntExpr,
      parameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts],
      path: Vector[String],
      diagnostics: DiagnosticBuilder,
      generateIndices: Map[String, IntExprFacts] = Map.empty,
      booleanParameters: Map[String, BooleanParameter] = Map.empty
  ): Option[IntExprFacts] =
    IntExpressionAnalysis.analyze(
      expression,
      parameters,
      localParameters,
      booleanParameters,
      generateIndices
    ) match {
      case Right(facts) => Some(facts)
      case Left(DivisorMayBeZero(operator, interval)) =>
        diagnostics += Diagnostic(
          "PRTL-DIVISOR-MAY-BE-ZERO",
          path,
          s"Divisor of '$operator' is not proven nonzero over legal domain ${renderInterval(interval)}"
        )
        None
      case Left(_: UnresolvedParameter) |
          Left(_: UnresolvedBooleanParameter) |
          Left(_: UnresolvedLocalParameter) |
          Left(_: UnresolvedGenerateIndex) => None
    }

  private def validateWidth(
      expression: IntExpr,
      parameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts],
      path: Vector[String],
      diagnostics: DiagnosticBuilder,
      booleanParameters: Map[String, BooleanParameter] = Map.empty
  ): Unit = expression match {
    case Literal(value) if value <= 0 =>
      diagnostics += Diagnostic(
        "PRTL-WIDTH-NOT-POSITIVE",
        path,
        s"Packed width literal $value is not positive"
      )
    case Literal(_) =>
    case _ =>
      analyzeExpression(
        expression,
        parameters,
        localParameters,
        path,
        diagnostics,
        booleanParameters = booleanParameters
      ).foreach { facts =>
        if (!facts.interval.lower.exists(_ >= 1)) {
          diagnostics += Diagnostic(
            "PRTL-WIDTH-NOT-PROVEN-POSITIVE",
            path,
            s"Packed width domain ${renderInterval(facts.interval)} is not proven positive"
          )
        }
      }
  }

  private final case class GenerateContext(
      indexName: String,
      count: IntExpr,
      indexFacts: Option[IntExprFacts]
  )

  private final case class ActualFacts(
      port: Port,
      dataType: PackedBits,
      indexed: Boolean,
      canonicalSlice: Boolean
  )

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
    val generateFors = module.items.collect { case generate: GenerateFor => generate }.sortBy(_.label)
    val generateIfs = module.items.collect { case generate: GenerateIf => generate }.sortBy(generateIfSortKey)
    val driverCounts = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    val conditionalDriverMinimums = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    val conditionalDriverMaximums = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    val instanceFactsBuilder = Map.newBuilder[String, ValidatedInstanceFacts]
    val parentParameters = baseFacts.parameterFacts
    val parentLocals = baseFacts.localParameterFacts
    val parentBooleanParameters = baseFacts.booleanParameters
    val parentParameterNames = module.parameters.map(_.name).toSet
    val parentLocalNames = module.localParameters.map(_.name).toSet
    val parentLocalExpressions = expandLocalExpressions(baseFacts.orderedLocalParameters, Map.empty)

    def expandParentExpression(expression: IntExpr): IntExpr =
      substituteLocalDefinition(expression, Map.empty, parentLocalExpressions)

    def resolveActual(
        expression: RtlExpr,
        path: Vector[String],
        generateContext: Option[GenerateContext]
    ): Option[ActualFacts] = expression match {
      case ref: Ref =>
        resolvePort(ref, portByName, path, diagnostics).map { port =>
          ActualFacts(port, port.dataType, indexed = false, canonicalSlice = true)
        }
      case IndexedPartSelect(base, offset, width) =>
        val allowedIndices = generateContext.map(context => Set(context.indexName)).getOrElse(Set.empty)
        val scopedIndexFacts = generateContext
          .flatMap(context => context.indexFacts.map(context.indexName -> _))
          .toMap
        validateExpressionReferences(
          offset,
          parentParameterNames,
          parentLocalNames,
          path :+ "offset",
          diagnostics,
          allowedIndices,
          parentBooleanParameters
        )
        analyzeExpression(
          offset,
          parentParameters,
          parentLocals,
          path :+ "offset",
          diagnostics,
          scopedIndexFacts,
          parentBooleanParameters
        )
        validateExpressionReferences(
          width,
          parentParameterNames,
          parentLocalNames,
          path :+ "width",
          diagnostics,
          allowedIndices,
          parentBooleanParameters
        )
        validateWidth(
          width,
          parentParameters,
          parentLocals,
          path :+ "width",
          diagnostics,
          parentBooleanParameters
        )
        if (containsGenerateIndex(width))
          diagnostics += Diagnostic(
            "PRTL-GENERATE-SLICE-WIDTH-VARIES",
            path :+ "width",
            "Indexed part-select width must be independent of the generate index"
          )

        val basePort = resolvePort(base, portByName, path :+ "base", diagnostics)
        val canonical = generateContext match {
          case None =>
            diagnostics += Diagnostic(
              "PRTL-INDEXED-PART-SELECT-REQUIRES-GENERATE",
              path,
              "Indexed part-selects are currently supported only inside generate-for bodies"
            )
            false
          case Some(context) =>
            val canonicalOffset = IntExpressionEquivalence.equivalent(
              expandParentExpression(offset),
              Multiply(GenerateIndexRef(context.indexName), expandParentExpression(width))
            )
            val completeBase = basePort.exists { port =>
              IntExpressionEquivalence.equivalent(
                expandParentExpression(port.dataType.width),
                Multiply(expandParentExpression(context.count), expandParentExpression(width))
              )
            }
            val result =
              context.indexFacts.isDefined && !containsGenerateIndex(width) && canonicalOffset && completeBase
            if (!result)
              diagnostics += Diagnostic(
                "PRTL-GENERATE-SLICE-NOT-CANONICAL",
                path,
                s"Generate slice must use offset '${context.indexName} * width' and partition a base width equal to count * width"
              )
            result
        }

        basePort.map { port =>
          // IEEE 1364 part-select results are unsigned, even when the selected vector is signed.
          ActualFacts(port, PackedBits(width, Unsigned), indexed = true, canonicalSlice = canonical)
        }
    }

    def validateInstance(
        instance: ModuleInstance,
        path: Vector[String],
        factsKey: String,
        generateContext: Option[GenerateContext],
        branchDriverCounts: scala.collection.mutable.Map[String, Int]
    ): Unit = {
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

      instance.parameterBindings.sortBy(_.parameterName).foreach { binding =>
        validateExpressionReferences(
          binding.value,
          parentParameterNames,
          parentLocalNames,
          bindingPath :+ binding.parameterName :+ "value",
          diagnostics,
          booleanParameters = parentBooleanParameters
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
                  diagnostics,
                  booleanParameters = parentBooleanParameters
                ).foreach { bindingFacts =>
                  analyzedBindings.update(binding.parameterName, bindingFacts)
                  if (!domainContained(bindingFacts.interval, targetParameter))
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

          val instantiatedParameters = targetParameters.map { parameter =>
            parameter.name -> analyzedBindings.getOrElse(
              parameter.name,
              IntExprFacts(parameter.default, IntInterval.point(parameter.default))
            )
          }.toMap
          val targetBooleanParameters =
            targetModule.booleanParameters.map(parameter => parameter.name -> parameter).toMap
          var instantiatedLocals = Map.empty[String, IntExprFacts]
          targetBaseFacts.orderedLocalParameters.foreach { localParameter =>
            IntExpressionAnalysis
              .analyze(
                localParameter.value,
                instantiatedParameters,
                instantiatedLocals,
                targetBooleanParameters,
                Map.empty
              )
              .toOption
              .foreach(facts => instantiatedLocals = instantiatedLocals.updated(localParameter.name, facts))
          }

          val expandedBindings = targetParameters.map { parameter =>
            val raw = bindingByName.get(parameter.name).map(_.value).getOrElse(Literal(parameter.default))
            parameter.name -> IntExpressionEquivalence.substitute(raw, Map.empty, parentLocalExpressions)
          }.toMap
          val targetBooleanDefaults = targetBooleanParameters.map { case (name, parameter) =>
            name -> BoolLiteral(parameter.default)
          }
          val targetLocalExpressions =
            expandLocalExpressions(
              targetBaseFacts.orderedLocalParameters,
              expandedBindings,
              targetBooleanDefaults
            )
          val instantiatedPortTypes = targetModule.ports.map { port =>
            val width = port.dataType.width match {
              case ParameterRef(name)      => expandedBindings.getOrElse(name, port.dataType.width)
              case LocalParameterRef(name) => targetLocalExpressions.getOrElse(name, port.dataType.width)
              case other =>
                substituteLocalDefinition(
                  other,
                  expandedBindings,
                  targetLocalExpressions,
                  targetBooleanDefaults
                )
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
                resolveActual(connection.actual, currentPath :+ "actual", generateContext).foreach { actual =>
                  if (targetPort.direction == Output) {
                    generateContext match {
                      case None if !actual.indexed =>
                        branchDriverCounts.update(actual.port.name, branchDriverCounts(actual.port.name) + 1)
                      case Some(_) if actual.indexed && actual.canonicalSlice =>
                        branchDriverCounts.update(actual.port.name, branchDriverCounts(actual.port.name) + 1)
                      case Some(_) if !actual.indexed =>
                        diagnostics += Diagnostic(
                          "PRTL-GENERATE-OUTPUT-NOT-CANONICAL",
                          currentPath :+ "actual",
                          "A generated child output must drive a canonical indexed part-select"
                        )
                      case _ =>
                    }
                    if (actual.port.direction == Input)
                      diagnostics += Diagnostic(
                        "PRTL-ILLEGAL-INPUT-DRIVER",
                        currentPath :+ "actual",
                        s"Output port '${targetModule.name}.${targetPort.name}' cannot drive parent input '${actual.port.name}'"
                      )
                  }
                  val expected = instantiatedPortTypes(targetPort.name)
                  val instantiatedExpectedFacts = IntExpressionAnalysis
                    .analyze(
                      targetPort.dataType.width,
                      instantiatedParameters,
                      instantiatedLocals,
                      targetBooleanParameters,
                      Map.empty
                    )
                    .toOption
                  if (
                    !packedTypesEquivalent(
                      actual.dataType,
                      expected,
                      module,
                      baseFacts,
                      instantiatedExpectedFacts
                    )
                  )
                    diagnostics += Diagnostic(
                      "PRTL-INSTANCE-PORT-TYPE-MISMATCH",
                      currentPath,
                      s"Parent expression on '${actual.port.name}' is not type-compatible with instantiated port '${targetModule.name}.${targetPort.name}'"
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

          instanceFactsBuilder += factsKey -> ValidatedInstanceFacts(
            targetModule,
            instantiatedParameters,
            instantiatedLocals,
            instantiatedPortTypes
          )
      }
    }

    def validateAssignment(
        assignment: ContinuousAssign,
        path: Vector[String],
        branchDriverCounts: scala.collection.mutable.Map[String, Int]
    ): Unit = {
      val ContinuousAssign(target, value) = assignment
      val targetPort = resolvePort(target, portByName, path :+ "target", diagnostics)
      val valueFacts = resolveActual(value, path :+ "value", None)
      targetPort.foreach { port =>
        branchDriverCounts.update(port.name, branchDriverCounts(port.name) + 1)
        if (port.direction == Input)
          diagnostics += Diagnostic(
            "PRTL-ILLEGAL-INPUT-DRIVER",
            path :+ "target",
            s"Continuous assignment cannot drive input port '${port.name}'"
          )
      }
      for {
        targetType <- targetPort.map(_.dataType)
        valueType <- valueFacts.map(_.dataType)
        if !packedTypesEquivalent(targetType, valueType, module, baseFacts)
      } diagnostics += Diagnostic(
        "PRTL-TYPE-MISMATCH",
        path,
        s"Continuous assignment target type '$targetType' does not match value type '$valueType'"
      )
    }

    module.items.zipWithIndex.foreach {
      case (assignment: ContinuousAssign, index) =>
        validateAssignment(assignment, modulePath :+ "items" :+ index.toString, driverCounts)
      case _ =>
    }

    instances.foreach { instance =>
      validateInstance(instance, modulePath :+ "instances" :+ instance.name, instance.name, None, driverCounts)
    }

    generateFors.foreach { generate =>
      val path = modulePath :+ "generateFors" :+ generate.label
      val countFacts = IntExpressionAnalysis
        .analyze(generate.count, parentParameters, parentLocals, parentBooleanParameters, Map.empty)
        .toOption
      val context = GenerateContext(
        generate.indexName,
        generate.count,
        countFacts
          .filter(_.interval.lower.exists(_ >= 1))
          .map { facts =>
            IntExprFacts(
              defaultValue = 0,
              interval = IntInterval(lower = Some(0), upper = facts.interval.upper.map(_ - 1))
            )
          }
      )
      generate.body.collect { case instance: ModuleInstance => instance }.sortBy(_.name).foreach { instance =>
        validateInstance(
          instance,
          path :+ "instances" :+ instance.name,
          s"${generate.label}.${instance.name}",
          Some(context),
          driverCounts
        )
      }
    }

    generateIfs.foreach { generate =>
      val path = modulePath :+ "generateIfs" :+ generate.whenTrue.label
      val trueCounts = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
      val falseCounts = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)

      def validateBlock(block: GenerateBlock, counts: scala.collection.mutable.Map[String, Int]): Unit = {
        val blockPath = path :+ block.label
        block.body.zipWithIndex.foreach {
          case (instance: ModuleInstance, _) =>
            validateInstance(
              instance,
              blockPath :+ "instances" :+ instance.name,
              s"${block.label}.${instance.name}",
              None,
              counts
            )
          case (assignment: ContinuousAssign, index) =>
            validateAssignment(assignment, blockPath :+ "items" :+ index.toString, counts)
          case _ => // Nested generate diagnostics are emitted by validateModule.
        }
      }

      validateBlock(generate.whenTrue, trueCounts)
      validateBlock(generate.whenFalse, falseCounts)

      ports.filter(_.direction == Output).foreach { port =>
        val branchCounts = Vector(trueCounts(port.name), falseCounts(port.name))
        conditionalDriverMinimums.update(
          port.name,
          conditionalDriverMinimums(port.name) + branchCounts.min
        )
        conditionalDriverMaximums.update(
          port.name,
          conditionalDriverMaximums(port.name) + branchCounts.max
        )
      }
    }

    ports.filter(_.direction == Output).foreach { port =>
      val minimumDrivers = driverCounts(port.name) + conditionalDriverMinimums(port.name)
      val maximumDrivers = driverCounts(port.name) + conditionalDriverMaximums(port.name)
      if (minimumDrivers == 0) {
        val message =
          if (generateIfs.isEmpty) s"Output port '${port.name}' has no driver"
          else s"Output port '${port.name}' is undriven for at least one legal generate configuration"
        diagnostics += Diagnostic(
          "PRTL-UNDRIVEN-OUTPUT",
          modulePath :+ "ports" :+ port.name,
          message
        )
      }
      if (maximumDrivers > 1) {
        val message =
          if (generateIfs.isEmpty) s"Output port '${port.name}' has $maximumDrivers drivers"
          else s"Output port '${port.name}' has up to $maximumDrivers drivers in a legal generate configuration"
        diagnostics += Diagnostic(
          "PRTL-MULTIPLE-DRIVERS",
          modulePath :+ "ports" :+ port.name,
          message
        )
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
      case other =>
        IntExpressionAnalysis
          .analyze(
            other,
            facts.parameterFacts,
            facts.localParameterFacts,
            facts.booleanParameters,
            Map.empty
          )
          .toOption
    }

  /** Dependency-first expansion shares prior DAG nodes and never walks a local chain recursively. */
  private def expandLocalExpressions(
      ordered: Vector[IntegerLocalParameter],
      parameters: Map[String, IntExpr],
      booleanParameters: Map[String, BoolExpr] = Map.empty
  ): Map[String, IntExpr] = {
    var expanded = Map.empty[String, IntExpr]
    ordered.foreach { localParameter =>
      expanded = expanded.updated(
        localParameter.name,
        substituteLocalDefinition(localParameter.value, parameters, expanded, booleanParameters)
      )
    }
    expanded
  }

  private def substituteLocalDefinition(
      expression: IntExpr,
      parameters: Map[String, IntExpr],
      locals: Map[String, IntExpr],
      booleanParameters: Map[String, BoolExpr] = Map.empty
  ): IntExpr = expression match {
    case value: Literal          => value
    case ParameterRef(name)      => parameters.getOrElse(name, expression)
    case LocalParameterRef(name) => locals.getOrElse(name, expression)
    case value: GenerateIndexRef => value
    case Negate(value) =>
      Negate(substituteLocalDefinition(value, parameters, locals, booleanParameters))
    case Add(left, right) =>
      Add(
        substituteLocalDefinition(left, parameters, locals, booleanParameters),
        substituteLocalDefinition(right, parameters, locals, booleanParameters)
      )
    case Subtract(left, right) =>
      Subtract(
        substituteLocalDefinition(left, parameters, locals, booleanParameters),
        substituteLocalDefinition(right, parameters, locals, booleanParameters)
      )
    case Multiply(left, right) =>
      Multiply(
        substituteLocalDefinition(left, parameters, locals, booleanParameters),
        substituteLocalDefinition(right, parameters, locals, booleanParameters)
      )
    case Divide(left, right) =>
      Divide(
        substituteLocalDefinition(left, parameters, locals, booleanParameters),
        substituteLocalDefinition(right, parameters, locals, booleanParameters)
      )
    case Modulo(left, right) =>
      Modulo(
        substituteLocalDefinition(left, parameters, locals, booleanParameters),
        substituteLocalDefinition(right, parameters, locals, booleanParameters)
      )
    case Select(condition, whenTrue, whenFalse) =>
      Select(
        substituteBooleanDefinition(condition, parameters, locals, booleanParameters),
        substituteLocalDefinition(whenTrue, parameters, locals, booleanParameters),
        substituteLocalDefinition(whenFalse, parameters, locals, booleanParameters)
      )
  }

  private def substituteBooleanDefinition(
      expression: BoolExpr,
      parameters: Map[String, IntExpr],
      locals: Map[String, IntExpr],
      booleanParameters: Map[String, BoolExpr]
  ): BoolExpr = expression match {
    case value: BoolLiteral => value
    case BoolParameterRef(name) => booleanParameters.getOrElse(name, expression)
    case BoolLessThan(left, right) =>
      BoolLessThan(
        substituteLocalDefinition(left, parameters, locals, booleanParameters),
        substituteLocalDefinition(right, parameters, locals, booleanParameters)
      )
    case BoolLessThanOrEqual(left, right) =>
      BoolLessThanOrEqual(
        substituteLocalDefinition(left, parameters, locals, booleanParameters),
        substituteLocalDefinition(right, parameters, locals, booleanParameters)
      )
    case BoolGreaterThan(left, right) =>
      BoolGreaterThan(
        substituteLocalDefinition(left, parameters, locals, booleanParameters),
        substituteLocalDefinition(right, parameters, locals, booleanParameters)
      )
    case BoolGreaterThanOrEqual(left, right) =>
      BoolGreaterThanOrEqual(
        substituteLocalDefinition(left, parameters, locals, booleanParameters),
        substituteLocalDefinition(right, parameters, locals, booleanParameters)
      )
    case BoolEqual(left, right) =>
      BoolEqual(
        substituteLocalDefinition(left, parameters, locals, booleanParameters),
        substituteLocalDefinition(right, parameters, locals, booleanParameters)
      )
    case BoolNotEqual(left, right) =>
      BoolNotEqual(
        substituteLocalDefinition(left, parameters, locals, booleanParameters),
        substituteLocalDefinition(right, parameters, locals, booleanParameters)
      )
    case BoolNot(value) =>
      BoolNot(substituteBooleanDefinition(value, parameters, locals, booleanParameters))
    case BoolAnd(left, right) =>
      BoolAnd(
        substituteBooleanDefinition(left, parameters, locals, booleanParameters),
        substituteBooleanDefinition(right, parameters, locals, booleanParameters)
      )
    case BoolOr(left, right) =>
      BoolOr(
        substituteBooleanDefinition(left, parameters, locals, booleanParameters),
        substituteBooleanDefinition(right, parameters, locals, booleanParameters)
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

  /** Nested generate constructs are rejected, so dependency discovery visits one body level. */
  private def collectInstances(items: Vector[ModuleItem]): Vector[ModuleInstance] = {
    val result = Vector.newBuilder[ModuleInstance]
    items.foreach {
      case instance: ModuleInstance => result += instance
      case generate: GenerateFor =>
        generate.body.foreach {
          case instance: ModuleInstance => result += instance
          case _                        =>
        }
      case generate: GenerateIf =>
        generateBlocks(generate).foreach { block =>
          block.body.foreach {
            case instance: ModuleInstance => result += instance
            case _                        =>
          }
        }
      case _ =>
    }
    result.result()
  }

  private def generateBlocks(generate: GenerateIf): Vector[GenerateBlock] =
    Vector(generate.whenTrue, generate.whenFalse)

  private def generateIfSortKey(generate: GenerateIf): (String, String) =
    generate.whenTrue.label -> generate.whenFalse.label

  private def containsGenerateIndex(expression: IntExpr): Boolean = {
    val stack = scala.collection.mutable.ArrayBuffer(expression)
    while (stack.nonEmpty) {
      stack.remove(stack.length - 1) match {
        case GenerateIndexRef(_)                                 => return true
        case Literal(_) | ParameterRef(_) | LocalParameterRef(_) =>
        case Negate(value)                                       => stack += value
        case Add(left, right)                                    => stack += left; stack += right
        case Subtract(left, right)                               => stack += left; stack += right
        case Multiply(left, right)                               => stack += left; stack += right
        case Divide(left, right)                                 => stack += left; stack += right
        case Modulo(left, right)                                 => stack += left; stack += right
        case Select(condition, whenTrue, whenFalse) =>
          if (containsGenerateIndex(condition)) return true
          stack += whenTrue
          stack += whenFalse
      }
    }
    false
  }

  private def containsGenerateIndex(expression: BoolExpr): Boolean = {
    val stack = scala.collection.mutable.ArrayBuffer(expression)
    while (stack.nonEmpty) {
      stack.remove(stack.length - 1) match {
        case BoolLiteral(_) | BoolParameterRef(_) =>
        case BoolNot(value)                       => stack += value
        case BoolAnd(left, right)                 => stack += left; stack += right
        case BoolOr(left, right)                  => stack += left; stack += right
        case BoolLessThan(left, right) =>
          if (containsGenerateIndex(left) || containsGenerateIndex(right)) return true
        case BoolLessThanOrEqual(left, right) =>
          if (containsGenerateIndex(left) || containsGenerateIndex(right)) return true
        case BoolGreaterThan(left, right) =>
          if (containsGenerateIndex(left) || containsGenerateIndex(right)) return true
        case BoolGreaterThanOrEqual(left, right) =>
          if (containsGenerateIndex(left) || containsGenerateIndex(right)) return true
        case BoolEqual(left, right) =>
          if (containsGenerateIndex(left) || containsGenerateIndex(right)) return true
        case BoolNotEqual(left, right) =>
          if (containsGenerateIndex(left) || containsGenerateIndex(right)) return true
      }
    }
    false
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
