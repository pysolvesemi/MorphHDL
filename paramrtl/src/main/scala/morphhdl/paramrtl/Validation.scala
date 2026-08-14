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
  LocalParameterRef => BoolLocalParameterRef,
  Not => BoolNot,
  NotEqual => BoolNotEqual,
  Or => BoolOr,
  ParameterRef => BoolParameterRef
}
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
import morphhdl.paramrtl.IntExpressionFailure.{
  AddressWidthOperandNotProvenPositive,
  CeilLog2OperandNotProvenPositive,
  DivisorMayBeZero,
  UnresolvedBooleanParameter,
  UnresolvedBooleanLocalParameter,
  UnresolvedGenerateIndex,
  UnresolvedLocalParameter,
  UnresolvedParameter
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
  SynchronousRegister
}
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
    instanceFacts: Map[String, ValidatedInstanceFacts] = Map.empty,
    orderedLocalDeclarations: Vector[LocalParameterDeclaration] = Vector.empty,
    booleanLocalParameterFacts: Map[String, Boolean] = Map.empty
)

final case class ValidatedInstanceFacts private[morphhdl] (
    targetModule: ModuleDef,
    parameterFacts: Map[String, IntExprFacts],
    localParameterFacts: Map[String, IntExprFacts],
    instantiatedPortTypes: Map[String, PackedBits],
    booleanParameters: Map[String, BooleanParameter] = Map.empty,
    booleanLocalParameterFacts: Map[String, Boolean] = Map.empty
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
    val booleanLocalParameters = module.booleanLocalParameters.sortBy(_.name)
    val ports = module.ports.sortBy(_.name)
    val instances = module.items.collect { case instance: ModuleInstance => instance }.sortBy(_.name)
    val generateFors = module.items.collect { case generate: GenerateFor => generate }.sortBy(_.label)
    val generateIfs = module.items.collect { case generate: GenerateIf => generate }.sortBy(generateIfSortKey)
    val generateIfBlocks = generateIfs.flatMap(generateBlocks)
    val generateCases = module.items.collect { case generate: GenerateCase => generate }.sortBy(generateCaseSortKey)
    val generateCaseBlocks = generateCases.flatMap(generateCaseBlocksInOrder)
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
      booleanLocalParameters.map(_.name),
      modulePath :+ "booleanLocalParameters",
      "PRTL-DUPLICATE-BOOLEAN-LOCAL-PARAMETER",
      "Boolean local parameter",
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
      generateFors.map(_.label) ++ generateIfBlocks.map(_.label) ++ generateCaseBlocks.map(_.label),
      modulePath :+ "generateLabels",
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
    addDuplicateDiagnostics(
      combinationalIfs.map(_.label),
      modulePath :+ "processLabels",
      "PRTL-DUPLICATE-COMBINATIONAL-PROCESS-LABEL",
      "combinational process label",
      diagnostics
    )
    addDuplicateDiagnostics(
      synchronousRegisters.map(_.label),
      modulePath :+ "processLabels",
      "PRTL-DUPLICATE-SYNCHRONOUS-REGISTER-LABEL",
      "synchronous register process label",
      diagnostics
    )
    addDuplicateDiagnostics(
      asynchronousRegisters.map(_.label),
      modulePath :+ "processLabels",
      "PRTL-DUPLICATE-ASYNCHRONOUS-REGISTER-LABEL",
      "asynchronous register process label",
      diagnostics
    )
    addDuplicateDiagnostics(
      synchronousEnabledRegisters.map(_.label),
      modulePath :+ "processLabels",
      "PRTL-DUPLICATE-SYNCHRONOUS-ENABLED-REGISTER-LABEL",
      "synchronous enabled register process label",
      diagnostics
    )
    addDuplicateDiagnostics(
      asynchronousEnabledRegisters.map(_.label),
      modulePath :+ "processLabels",
      "PRTL-DUPLICATE-ASYNCHRONOUS-ENABLED-REGISTER-LABEL",
      "asynchronous enabled register process label",
      diagnostics
    )
    addDuplicateDiagnostics(
      synchronousReadFirstSinglePortMemories.map(_.label),
      modulePath :+ "processLabels",
      "PRTL-DUPLICATE-SYNCHRONOUS-READ-FIRST-MEMORY-LABEL",
      "synchronous read-first memory process label",
      diagnostics
    )
    addDuplicateDiagnostics(
      synchronousReadFirstSimpleDualPortMemories.map(_.label),
      modulePath :+ "processLabels",
      "PRTL-SYNCHRONOUS-READ-FIRST-SIMPLE-DUAL-PORT-MEMORY-DUPLICATE-LABEL",
      "synchronous read-first simple dual-port memory process label",
      diagnostics
    )
    addDuplicateDiagnostics(
      synchronousReadFirstSinglePortMemories.map(_.memoryName) ++
        synchronousReadFirstSimpleDualPortMemories.map(_.memoryName),
      modulePath :+ "memories",
      "PRTL-DUPLICATE-MEMORY-NAME",
      "memory",
      diagnostics
    )
    addDuplicateDiagnostics(
      synchronousCounters.map(_.label),
      modulePath :+ "processLabels",
      "PRTL-DUPLICATE-SYNCHRONOUS-COUNTER-LABEL",
      "synchronous counter process label",
      diagnostics
    )

    val declarationKinds = Vector(
      "parameter" -> parameters.map(_.name).toSet,
      "Boolean parameter" -> booleanParameters.map(_.name).toSet,
      "local parameter" -> localParameters.map(_.name).toSet,
      "Boolean local parameter" -> booleanLocalParameters.map(_.name).toSet,
      "port" -> ports.map(_.name).toSet,
      "instance" -> instances.map(_.name).toSet,
      "generate label" ->
        (generateFors.map(_.label) ++ generateIfBlocks.map(_.label) ++ generateCaseBlocks.map(_.label)).toSet,
      "generate index" -> generateFors.map(_.indexName).toSet,
      "combinational process label" -> combinationalIfs.map(_.label).toSet,
      "synchronous register process label" -> synchronousRegisters.map(_.label).toSet,
      "asynchronous register process label" -> asynchronousRegisters.map(_.label).toSet,
      "synchronous enabled register process label" -> synchronousEnabledRegisters.map(_.label).toSet,
      "asynchronous enabled register process label" -> asynchronousEnabledRegisters.map(_.label).toSet,
      "synchronous read-first memory process label" -> synchronousReadFirstSinglePortMemories.map(_.label).toSet,
      "synchronous read-first simple dual-port memory process label" ->
        synchronousReadFirstSimpleDualPortMemories.map(_.label).toSet,
      "synchronous counter process label" -> synchronousCounters.map(_.label).toSet,
      "memory" ->
        (synchronousReadFirstSinglePortMemories.map(_.memoryName) ++
          synchronousReadFirstSimpleDualPortMemories.map(_.memoryName)).toSet
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

    booleanLocalParameters.foreach { localParameter =>
      val path = modulePath :+ "booleanLocalParameters" :+ localParameter.name
      checkIdentifier(localParameter.name, path :+ "name", "Boolean local parameter", diagnostics)
    }

    val parameterByName = firstByName(parameters)(_.name)
    val booleanParameterByName = firstByName(booleanParameters)(_.name)
    val localParameterByName = firstByName(localParameters)(_.name)
    val booleanLocalParameterByName = firstByName(booleanLocalParameters)(_.name)
    val parameterNames = parameterByName.keySet
    val booleanParameterNames = booleanParameterByName.keySet
    val localParameterNames = localParameterByName.keySet
    val booleanLocalParameterNames = booleanLocalParameterByName.keySet

    localParameters.foreach { localParameter =>
      validateExpressionReferences(
        localParameter.value,
        parameterNames,
        localParameterNames,
        modulePath :+ "localParameters" :+ localParameter.name :+ "value",
        diagnostics,
        booleanParameters = booleanParameterByName,
        booleanLocalParameters = booleanLocalParameterNames
      )
    }


    booleanLocalParameters.foreach { localParameter =>
      validateBooleanExpression(
        localParameter.value,
        booleanParameterByName,
        parameterNames,
        localParameterNames,
        modulePath :+ "booleanLocalParameters" :+ localParameter.name :+ "value",
        diagnostics,
        booleanLocalParameters = booleanLocalParameterNames
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
        booleanParameters = booleanParameterByName,
        booleanLocalParameters = booleanLocalParameterNames
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
        booleanParameterByName,
        booleanLocalParameterNames
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
        case (_: CombinationalIf, index) =>
          diagnostics += Diagnostic(
            "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
            path :+ "body" :+ index.toString,
            "Generate-for bodies cannot contain runtime combinational processes"
          )
        case (_: SynchronousRegister, index) =>
          diagnostics += Diagnostic(
            "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
            path :+ "body" :+ index.toString,
            "Generate-for bodies cannot contain synchronous register processes"
          )
        case (_: AsynchronousRegister, index) =>
          diagnostics += Diagnostic(
            "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
            path :+ "body" :+ index.toString,
            "Generate-for bodies cannot contain asynchronous register processes"
          )
        case (_: SynchronousEnabledRegister, index) =>
          diagnostics += Diagnostic(
            "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
            path :+ "body" :+ index.toString,
            "Generate-for bodies cannot contain synchronous enabled register processes"
          )
        case (_: AsynchronousEnabledRegister, index) =>
          diagnostics += Diagnostic(
            "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
            path :+ "body" :+ index.toString,
            "Generate-for bodies cannot contain asynchronous enabled register processes"
          )
        case (_: SynchronousReadFirstSinglePortMemory, index) =>
          diagnostics += Diagnostic(
            "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
            path :+ "body" :+ index.toString,
            "Generate-for bodies cannot contain synchronous read-first single-port memories"
          )
        case (_: SynchronousReadFirstSimpleDualPortMemory, index) =>
          diagnostics += Diagnostic(
            "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
            path :+ "body" :+ index.toString,
            "Generate-for bodies cannot contain synchronous read-first simple dual-port memories"
          )
        case (_: SynchronousCounter, index) =>
          diagnostics += Diagnostic(
            "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
            path :+ "body" :+ index.toString,
            "Generate-for bodies cannot contain synchronous counters"
          )
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
        case (_: GenerateCase, index) =>
          diagnostics += Diagnostic(
            "PRTL-NESTED-GENERATE-UNSUPPORTED",
            path :+ "body" :+ index.toString,
            "Generate-for bodies cannot contain generate-case constructs"
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
        diagnostics,
        booleanLocalParameters = booleanLocalParameterNames
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
          case (_: CombinationalIf, index) =>
            diagnostics += Diagnostic(
              "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
              branchPath :+ "body" :+ index.toString,
              "Generate-if branches cannot contain runtime combinational processes"
            )
          case (_: SynchronousRegister, index) =>
            diagnostics += Diagnostic(
              "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
              branchPath :+ "body" :+ index.toString,
              "Generate-if branches cannot contain synchronous register processes"
            )
          case (_: AsynchronousRegister, index) =>
            diagnostics += Diagnostic(
              "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
              branchPath :+ "body" :+ index.toString,
              "Generate-if branches cannot contain asynchronous register processes"
            )
          case (_: SynchronousEnabledRegister, index) =>
            diagnostics += Diagnostic(
              "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
              branchPath :+ "body" :+ index.toString,
              "Generate-if branches cannot contain synchronous enabled register processes"
            )
          case (_: AsynchronousEnabledRegister, index) =>
            diagnostics += Diagnostic(
              "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
              branchPath :+ "body" :+ index.toString,
              "Generate-if branches cannot contain asynchronous enabled register processes"
            )
          case (_: SynchronousReadFirstSinglePortMemory, index) =>
            diagnostics += Diagnostic(
              "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
              branchPath :+ "body" :+ index.toString,
              "Generate-if branches cannot contain synchronous read-first single-port memories"
            )
          case (_: SynchronousReadFirstSimpleDualPortMemory, index) =>
            diagnostics += Diagnostic(
              "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
              branchPath :+ "body" :+ index.toString,
              "Generate-if branches cannot contain synchronous read-first simple dual-port memories"
            )
          case (_: SynchronousCounter, index) =>
            diagnostics += Diagnostic(
              "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
              branchPath :+ "body" :+ index.toString,
              "Generate-if branches cannot contain synchronous counters"
            )
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
          case (_: GenerateCase, index) =>
            diagnostics += Diagnostic(
              "PRTL-NESTED-GENERATE-UNSUPPORTED",
              branchPath :+ "body" :+ index.toString,
              "Generate-if branches cannot contain another generate construct"
            )
        }
      }

    }

    if (generateCases.size > 1) {
      generateCases.drop(1).foreach { generate =>
        diagnostics += Diagnostic(
          "PRTL-MULTIPLE-GENERATE-CASE-UNSUPPORTED",
          modulePath :+ "generateCases" :+ generate.default.label,
          "At most one top-level generate-case is supported per module"
        )
      }
    }

    if (generateCases.nonEmpty && generateIfs.nonEmpty) {
      generateIfs.foreach { generate =>
        diagnostics += Diagnostic(
          "PRTL-MULTIPLE-CONDITIONAL-GENERATE-UNSUPPORTED",
          modulePath :+ "generateIfs" :+ generate.whenTrue.label,
          "A module cannot contain both generate-if and generate-case constructs"
        )
      }
    }

    generateCases.foreach { generate =>
      val path = modulePath :+ "generateCases" :+ generate.default.label
      if (generate.choices.isEmpty)
        diagnostics += Diagnostic(
          "PRTL-GENERATE-CASE-NO-CHOICES",
          path :+ "choices",
          "Generate-case requires at least one explicit literal choice"
        )
      validateExpressionReferences(
        generate.selector,
        parameterNames,
        localParameterNames,
        path :+ "selector",
        diagnostics,
        Set.empty,
        booleanParameterByName,
        booleanLocalParameterNames
      )

      addDuplicateCaseValueDiagnostics(generate, path, diagnostics)

      generateCaseNamedBlocks(generate).foreach { case (branchPathSuffix, block) =>
        val branchPath = path ++ branchPathSuffix
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
          case (_: CombinationalIf, index) =>
            diagnostics += Diagnostic(
              "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
              branchPath :+ "body" :+ index.toString,
              "Generate-case branches cannot contain runtime combinational processes"
            )
          case (_: SynchronousRegister, index) =>
            diagnostics += Diagnostic(
              "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
              branchPath :+ "body" :+ index.toString,
              "Generate-case branches cannot contain synchronous register processes"
            )
          case (_: AsynchronousRegister, index) =>
            diagnostics += Diagnostic(
              "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
              branchPath :+ "body" :+ index.toString,
              "Generate-case branches cannot contain asynchronous register processes"
            )
          case (_: SynchronousEnabledRegister, index) =>
            diagnostics += Diagnostic(
              "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
              branchPath :+ "body" :+ index.toString,
              "Generate-case branches cannot contain synchronous enabled register processes"
            )
          case (_: AsynchronousEnabledRegister, index) =>
            diagnostics += Diagnostic(
              "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
              branchPath :+ "body" :+ index.toString,
              "Generate-case branches cannot contain asynchronous enabled register processes"
            )
          case (_: SynchronousReadFirstSinglePortMemory, index) =>
            diagnostics += Diagnostic(
              "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
              branchPath :+ "body" :+ index.toString,
              "Generate-case branches cannot contain synchronous read-first single-port memories"
            )
          case (_: SynchronousReadFirstSimpleDualPortMemory, index) =>
            diagnostics += Diagnostic(
              "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
              branchPath :+ "body" :+ index.toString,
              "Generate-case branches cannot contain synchronous read-first simple dual-port memories"
            )
          case (_: SynchronousCounter, index) =>
            diagnostics += Diagnostic(
              "PRTL-PROCESS-IN-GENERATE-UNSUPPORTED",
              branchPath :+ "body" :+ index.toString,
              "Generate-case branches cannot contain synchronous counters"
            )
          case (_: GenerateFor, index) =>
            diagnostics += Diagnostic(
              "PRTL-NESTED-GENERATE-UNSUPPORTED",
              branchPath :+ "body" :+ index.toString,
              "Generate-case branches cannot contain another generate construct"
            )
          case (_: GenerateIf, index) =>
            diagnostics += Diagnostic(
              "PRTL-NESTED-GENERATE-UNSUPPORTED",
              branchPath :+ "body" :+ index.toString,
              "Generate-case branches cannot contain another generate construct"
            )
          case (_: GenerateCase, index) =>
            diagnostics += Diagnostic(
              "PRTL-NESTED-GENERATE-UNSUPPORTED",
              branchPath :+ "body" :+ index.toString,
              "Generate-case branches cannot contain another generate construct"
            )
        }
      }
    }

    if (combinationalIfs.size > 1) {
      combinationalIfs.drop(1).foreach { process =>
        diagnostics += Diagnostic(
          "PRTL-MULTIPLE-COMBINATIONAL-PROCESSES-UNSUPPORTED",
          modulePath :+ "combinationalProcesses" :+ process.label,
          "At most one top-level runtime combinational process is supported per module"
        )
      }
    }

    combinationalIfs.foreach { process =>
      val path = modulePath :+ "combinationalProcesses" :+ process.label
      checkIdentifier(process.label, path :+ "label", "combinational process label", diagnostics)

      Vector("whenTrue" -> process.whenTrue, "whenFalse" -> process.whenFalse).foreach {
        case (branchName, assignments) =>
          val branchPath = path :+ branchName
          if (assignments.isEmpty)
            diagnostics += Diagnostic(
              "PRTL-EMPTY-COMBINATIONAL-BRANCH",
              branchPath,
              s"Combinational process '${process.label}' requires a nonempty $branchName branch"
            )
          addDuplicateDiagnostics(
            assignments.map(_.target.name),
            branchPath :+ "targets",
            "PRTL-DUPLICATE-PROCEDURAL-TARGET",
            "procedural assignment target",
            diagnostics
          )
      }

      val trueTargets = process.whenTrue.map(_.target.name).toSet
      val falseTargets = process.whenFalse.map(_.target.name).toSet
      if (trueTargets != falseTargets) {
        val onlyTrue = (trueTargets -- falseTargets).toVector.sorted
        val onlyFalse = (falseTargets -- trueTargets).toVector.sorted
        diagnostics += Diagnostic(
          "PRTL-COMBINATIONAL-BRANCH-TARGET-MISMATCH",
          path :+ "branches",
          s"Combinational process branches must assign the same targets; true-only: ${onlyTrue.mkString(", ")}; false-only: ${onlyFalse.mkString(", ")}"
        )
      }
    }

    if (combinationalIfs.nonEmpty) {
      val generateItems = module.items.collect {
        case item: GenerateFor  => item
        case item: GenerateIf   => item
        case item: GenerateCase => item
      }.sortBy {
        case generate: GenerateFor  => s"0:${generate.label}"
        case generate: GenerateIf   => s"1:${generateIfSortKey(generate)}"
        case generate: GenerateCase => s"2:${generateCaseSortKey(generate)}"
        case _                      => "3"
      }
      generateItems.zipWithIndex.foreach { case (item, index) =>
        diagnostics += Diagnostic(
          "PRTL-COMBINATIONAL-PROCESS-WITH-GENERATE-UNSUPPORTED",
          modulePath :+ "combinationalProcesses" :+ "generateConflicts" :+ index.toString,
          s"Runtime combinational processes cannot be combined with ${moduleItemKind(item)} in this tranche"
        )
      }

      module.items.collect { case assignment: ContinuousAssign => assignment }
        .sortBy(assignment => (assignment.target.name, assignment.value.toString))
        .zipWithIndex
        .foreach { case (_, index) =>
          diagnostics += Diagnostic(
            "PRTL-COMBINATIONAL-PROCESS-MIXED-DRIVERS-UNSUPPORTED",
            modulePath :+ "combinationalProcesses" :+ "continuousAssignmentConflicts" :+ index.toString,
            "Runtime combinational process outputs cannot be mixed with continuous-assignment drivers in this tranche"
          )
        }
      module.items.collect { case instance: ModuleInstance => instance }
        .sortBy(instance => (instance.name, instance.moduleName))
        .zipWithIndex
        .foreach { case (_, index) =>
          diagnostics += Diagnostic(
            "PRTL-COMBINATIONAL-PROCESS-MIXED-DRIVERS-UNSUPPORTED",
            modulePath :+ "combinationalProcesses" :+ "instanceConflicts" :+ index.toString,
            "Runtime combinational process outputs cannot be mixed with instance drivers in this tranche"
          )
        }
    }

    if (synchronousRegisters.size > 1) {
      synchronousRegisters.drop(1).foreach { process =>
        diagnostics += Diagnostic(
          "PRTL-MULTIPLE-SYNCHRONOUS-REGISTERS-UNSUPPORTED",
          modulePath :+ "synchronousRegisters" :+ process.label,
          "At most one top-level synchronous register process is supported per module"
        )
      }
    }

    synchronousRegisters.foreach { process =>
      val path = modulePath :+ "synchronousRegisters" :+ process.label
      checkIdentifier(process.label, path :+ "label", "synchronous register process label", diagnostics)
    }

    if (synchronousRegisters.nonEmpty) {
      module.items
        .filter {
          case _: SynchronousRegister => false
          case _                      => true
        }
        .sortBy(moduleItemStableKey)
        .zipWithIndex
        .foreach { case (item, index) =>
          diagnostics += Diagnostic(
            "PRTL-SYNCHRONOUS-REGISTER-MIXED-ITEMS-UNSUPPORTED",
            modulePath :+ "synchronousRegisters" :+ "itemConflicts" :+ index.toString,
            s"Synchronous register processes cannot be combined with ${moduleItemKind(item)} in this tranche"
          )
        }
    }

    if (asynchronousRegisters.size > 1) {
      asynchronousRegisters.drop(1).foreach { process =>
        diagnostics += Diagnostic(
          "PRTL-MULTIPLE-ASYNCHRONOUS-REGISTERS-UNSUPPORTED",
          modulePath :+ "asynchronousRegisters" :+ process.label,
          "At most one top-level asynchronous register process is supported per module"
        )
      }
    }

    asynchronousRegisters.foreach { process =>
      val path = modulePath :+ "asynchronousRegisters" :+ process.label
      checkIdentifier(process.label, path :+ "label", "asynchronous register process label", diagnostics)
    }

    if (asynchronousRegisters.nonEmpty) {
      module.items
        .filter {
          case _: AsynchronousRegister => false
          case _                       => true
        }
        .sortBy(moduleItemStableKey)
        .zipWithIndex
        .foreach { case (item, index) =>
          diagnostics += Diagnostic(
            "PRTL-ASYNCHRONOUS-REGISTER-MIXED-ITEMS-UNSUPPORTED",
            modulePath :+ "asynchronousRegisters" :+ "itemConflicts" :+ index.toString,
            s"Asynchronous register processes cannot be combined with ${moduleItemKind(item)} in this tranche"
          )
        }
    }

    if (synchronousEnabledRegisters.size > 1) {
      synchronousEnabledRegisters.drop(1).foreach { process =>
        diagnostics += Diagnostic(
          "PRTL-MULTIPLE-SYNCHRONOUS-ENABLED-REGISTERS-UNSUPPORTED",
          modulePath :+ "synchronousEnabledRegisters" :+ process.label,
          "At most one top-level synchronous enabled register process is supported per module"
        )
      }
    }

    synchronousEnabledRegisters.foreach { process =>
      val path = modulePath :+ "synchronousEnabledRegisters" :+ process.label
      checkIdentifier(
        process.label,
        path :+ "label",
        "synchronous enabled register process label",
        diagnostics
      )
    }

    if (synchronousEnabledRegisters.nonEmpty) {
      module.items
        .filter {
          case _: SynchronousEnabledRegister => false
          case _                             => true
        }
        .sortBy(moduleItemStableKey)
        .zipWithIndex
        .foreach { case (item, index) =>
          diagnostics += Diagnostic(
            "PRTL-SYNCHRONOUS-ENABLED-REGISTER-MIXED-ITEMS-UNSUPPORTED",
            modulePath :+ "synchronousEnabledRegisters" :+ "itemConflicts" :+ index.toString,
            s"Synchronous enabled register processes cannot be combined with ${moduleItemKind(item)} in this tranche"
          )
        }
    }

    if (asynchronousEnabledRegisters.size > 1) {
      asynchronousEnabledRegisters.drop(1).foreach { process =>
        diagnostics += Diagnostic(
          "PRTL-MULTIPLE-ASYNCHRONOUS-ENABLED-REGISTERS-UNSUPPORTED",
          modulePath :+ "asynchronousEnabledRegisters" :+ process.label,
          "At most one top-level asynchronous enabled register process is supported per module"
        )
      }
    }

    asynchronousEnabledRegisters.foreach { process =>
      val path = modulePath :+ "asynchronousEnabledRegisters" :+ process.label
      checkIdentifier(
        process.label,
        path :+ "label",
        "asynchronous enabled register process label",
        diagnostics
      )
    }

    if (asynchronousEnabledRegisters.nonEmpty) {
      module.items
        .filter {
          case _: AsynchronousEnabledRegister => false
          case _                              => true
        }
        .sortBy(moduleItemStableKey)
        .zipWithIndex
        .foreach { case (item, index) =>
          diagnostics += Diagnostic(
            "PRTL-ASYNCHRONOUS-ENABLED-REGISTER-MIXED-ITEMS-UNSUPPORTED",
            modulePath :+ "asynchronousEnabledRegisters" :+ "itemConflicts" :+ index.toString,
            s"Asynchronous enabled register processes cannot be combined with ${moduleItemKind(item)} in this tranche"
          )
        }
    }

    if (synchronousReadFirstSinglePortMemories.size > 1) {
      synchronousReadFirstSinglePortMemories.drop(1).foreach { memory =>
        diagnostics += Diagnostic(
          "PRTL-MULTIPLE-SYNCHRONOUS-READ-FIRST-MEMORIES-UNSUPPORTED",
          modulePath :+ "synchronousReadFirstSinglePortMemories" :+ memory.label,
          "At most one top-level synchronous read-first single-port memory is supported per module"
        )
      }
    }

    synchronousReadFirstSinglePortMemories.foreach { memory =>
      val path = modulePath :+ "synchronousReadFirstSinglePortMemories" :+ memory.label
      checkIdentifier(
        memory.label,
        path :+ "label",
        "synchronous read-first memory process label",
        diagnostics
      )
      checkIdentifier(memory.memoryName, path :+ "memoryName", "memory", diagnostics)
      validateExpressionReferences(
        memory.elementType.width,
        parameterNames,
        localParameterNames,
        path :+ "elementType" :+ "width",
        diagnostics,
        booleanParameters = booleanParameterByName,
        booleanLocalParameters = booleanLocalParameterNames
      )
      validateExpressionReferences(
        memory.depth,
        parameterNames,
        localParameterNames,
        path :+ "depth",
        diagnostics,
        booleanParameters = booleanParameterByName,
        booleanLocalParameters = booleanLocalParameterNames
      )
    }

    if (synchronousReadFirstSinglePortMemories.nonEmpty) {
      module.items
        .filter {
          case _: SynchronousReadFirstSinglePortMemory => false
          case _                                       => true
        }
        .sortBy(moduleItemStableKey)
        .zipWithIndex
        .foreach { case (item, index) =>
          diagnostics += Diagnostic(
            "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-MIXED-ITEMS-UNSUPPORTED",
            modulePath :+ "synchronousReadFirstSinglePortMemories" :+ "itemConflicts" :+ index.toString,
            s"Synchronous read-first single-port memories cannot be combined with ${moduleItemKind(item)} in this tranche"
          )
        }
    }

    if (synchronousReadFirstSimpleDualPortMemories.size > 1) {
      synchronousReadFirstSimpleDualPortMemories.drop(1).foreach { memory =>
        diagnostics += Diagnostic(
          "PRTL-SYNCHRONOUS-READ-FIRST-SIMPLE-DUAL-PORT-MEMORY-MULTIPLE-MEMORIES-UNSUPPORTED",
          modulePath :+ "synchronousReadFirstSimpleDualPortMemories" :+ memory.label,
          "At most one top-level synchronous read-first simple dual-port memory is supported per module"
        )
      }
    }

    synchronousReadFirstSimpleDualPortMemories.foreach { memory =>
      val path = modulePath :+ "synchronousReadFirstSimpleDualPortMemories" :+ memory.label
      checkIdentifier(
        memory.label,
        path :+ "label",
        "synchronous read-first simple dual-port memory process label",
        diagnostics
      )
      checkIdentifier(memory.memoryName, path :+ "memoryName", "memory", diagnostics)
      validateExpressionReferences(
        memory.elementType.width,
        parameterNames,
        localParameterNames,
        path :+ "elementType" :+ "width",
        diagnostics,
        booleanParameters = booleanParameterByName,
        booleanLocalParameters = booleanLocalParameterNames
      )
      validateExpressionReferences(
        memory.depth,
        parameterNames,
        localParameterNames,
        path :+ "depth",
        diagnostics,
        booleanParameters = booleanParameterByName,
        booleanLocalParameters = booleanLocalParameterNames
      )
    }

    if (synchronousReadFirstSimpleDualPortMemories.nonEmpty) {
      module.items
        .filter {
          case _: SynchronousReadFirstSimpleDualPortMemory => false
          case _                                           => true
        }
        .sortBy(moduleItemStableKey)
        .zipWithIndex
        .foreach { case (item, index) =>
          diagnostics += Diagnostic(
            "PRTL-SYNCHRONOUS-READ-FIRST-SIMPLE-DUAL-PORT-MEMORY-MIXED-ITEMS-UNSUPPORTED",
            modulePath :+ "synchronousReadFirstSimpleDualPortMemories" :+ "itemConflicts" :+ index.toString,
            s"Synchronous read-first simple dual-port memories cannot be combined with ${moduleItemKind(item)} in this tranche"
          )
        }
    }

    if (synchronousCounters.size > 1) {
      synchronousCounters.drop(1).foreach { counter =>
        diagnostics += Diagnostic(
          "PRTL-MULTIPLE-SYNCHRONOUS-COUNTERS-UNSUPPORTED",
          modulePath :+ "synchronousCounters" :+ counter.label,
          "At most one top-level synchronous counter is supported per module"
        )
      }
    }

    synchronousCounters.foreach { counter =>
      val path = modulePath :+ "synchronousCounters" :+ counter.label
      checkIdentifier(
        counter.label,
        path :+ "label",
        "synchronous counter process label",
        diagnostics
      )
      validateExpressionReferences(
        counter.limit,
        parameterNames,
        localParameterNames,
        path :+ "limit",
        diagnostics,
        booleanParameters = booleanParameterByName,
        booleanLocalParameters = booleanLocalParameterNames
      )
      counter.limit match {
        case ParameterRef(name) if parameterNames(name) =>
        case _ =>
          diagnostics += Diagnostic(
            "PRTL-SYNCHRONOUS-COUNTER-LIMIT-NOT-DIRECT-PUBLIC-PARAMETER",
            path :+ "limit",
            "Synchronous counter limit must be a direct public integer parameter reference"
          )
      }
    }

    if (synchronousCounters.nonEmpty) {
      module.items
        .filter {
          case _: SynchronousCounter => false
          case _                     => true
        }
        .sortBy(moduleItemStableKey)
        .zipWithIndex
        .foreach { case (item, index) =>
          diagnostics += Diagnostic(
            "PRTL-SYNCHRONOUS-COUNTER-MIXED-ITEMS-UNSUPPORTED",
            modulePath :+ "synchronousCounters" :+ "itemConflicts" :+ index.toString,
            s"Synchronous counters cannot be combined with ${moduleItemKind(item)} in this tranche"
          )
        }
    }

    def integerLocalKey(name: String): String = s"integer:$name"
    def booleanLocalKey(name: String): String = s"boolean:$name"

    val integerDependencies = localParameterByName.map { case (name, localParameter) =>
      integerLocalKey(name) -> (
        IntExpressionAnalysis
          .localParameterReferences(localParameter.value)
          .filter(localParameterNames.contains)
          .map(integerLocalKey) ++
          IntExpressionAnalysis
            .booleanLocalParameterReferences(localParameter.value)
            .filter(booleanLocalParameterNames.contains)
            .map(booleanLocalKey)
      ).distinct.sorted
    }
    val booleanDependencies = booleanLocalParameterByName.map { case (name, localParameter) =>
      booleanLocalKey(name) -> (
        BoolExpressionAnalysis
          .localParameterReferences(localParameter.value)
          .filter(localParameterNames.contains)
          .map(integerLocalKey) ++
          BoolExpressionAnalysis
            .booleanLocalParameterReferences(localParameter.value)
            .filter(booleanLocalParameterNames.contains)
            .map(booleanLocalKey)
      ).distinct.sorted
    }

    val localParameterGraph = DependencyGraph.analyze(
      integerDependencies ++ booleanDependencies,
      key => {
        val separator = key.indexOf(':')
        val kindRank = if (key.startsWith("integer:")) "0" else "1"
        s"${key.substring(separator + 1)}:$kindRank"
      }
    )
    val cycleGroups = localParameterGraph.cycleGroups
    cycleGroups.foreach { names =>
      val firstIsBoolean = names.head.startsWith("boolean:")
      val renderedNames =
        if (names.forall(_.startsWith("integer:")))
          names.map(_.stripPrefix("integer:"))
        else
          names.map {
            case name if name.startsWith("integer:") => s"integer ${name.stripPrefix("integer:")}"
            case name                                => s"Boolean ${name.stripPrefix("boolean:")}"
          }
      diagnostics += Diagnostic(
        "PRTL-LOCAL-PARAMETER-CYCLE",
        modulePath :+
          (if (firstIsBoolean) "booleanLocalParameters" else "localParameters") :+
          names.head.substring(names.head.indexOf(':') + 1),
        s"Local-parameter dependency cycle members: ${renderedNames.mkString(", ")}"
      )
    }

    val orderedLocalDeclarations: Vector[LocalParameterDeclaration] =
      localParameterGraph.orderedNames.map {
        case key if key.startsWith("integer:") => localParameterByName(key.stripPrefix("integer:"))
        case key                               => booleanLocalParameterByName(key.stripPrefix("boolean:"))
      }
    val orderedLocalParameters = orderedLocalDeclarations.collect {
      case localParameter: IntegerLocalParameter => localParameter
    }

    val parameterFacts = parameterByName.flatMap { case (name, parameter) =>
      IntExpressionAnalysis.parameterFacts(parameter).map(name -> _)
    }
    var localParameterFacts = Map.empty[String, IntExprFacts]
    var booleanLocalParameterFacts = Map.empty[String, Boolean]

    orderedLocalDeclarations.foreach {
      case localParameter: IntegerLocalParameter =>
        val path = modulePath :+ "localParameters" :+ localParameter.name :+ "value"
        analyzeExpression(
          localParameter.value,
          parameterFacts,
          localParameterFacts,
          path,
          diagnostics,
          booleanParameters = booleanParameterByName,
          booleanLocalParameters = booleanLocalParameterFacts
        ).foreach(facts => localParameterFacts = localParameterFacts.updated(localParameter.name, facts))
      case localParameter: BooleanLocalParameter =>
        val path = modulePath :+ "booleanLocalParameters" :+ localParameter.name :+ "value"
        analyzeBooleanExpression(
          localParameter.value,
          booleanParameterByName,
          parameterFacts,
          localParameterFacts,
          path,
          diagnostics,
          booleanLocalParameterFacts
        )
        BoolExpressionAnalysis
          .evaluateDefault(
            localParameter.value,
            booleanParameterByName,
            parameterFacts,
            localParameterFacts,
            Map.empty,
            booleanLocalParameterFacts
          )
          .toOption
          .foreach(value => booleanLocalParameterFacts = booleanLocalParameterFacts.updated(localParameter.name, value))
    }

    generateIfs.foreach { generate =>
      analyzeBooleanExpression(
        generate.condition,
        booleanParameterByName,
        parameterFacts,
        localParameterFacts,
        modulePath :+ "generateIfs" :+ generate.whenTrue.label :+ "condition",
        diagnostics,
        booleanLocalParameterFacts
      )
    }

    generateCases.foreach { generate =>
      analyzeExpression(
        generate.selector,
        parameterFacts,
        localParameterFacts,
        modulePath :+ "generateCases" :+ generate.default.label :+ "selector",
        diagnostics,
        booleanParameters = booleanParameterByName,
        booleanLocalParameters = booleanLocalParameterFacts
      )
    }

    ports.foreach { port =>
      validateWidth(
        port.dataType.width,
        parameterFacts,
        localParameterFacts,
        modulePath :+ "ports" :+ port.name :+ "width",
        diagnostics,
        booleanParameterByName,
        booleanLocalParameterFacts
      )
    }

    synchronousReadFirstSinglePortMemories.foreach { memory =>
      val path = modulePath :+ "synchronousReadFirstSinglePortMemories" :+ memory.label
      validateWidth(
        memory.elementType.width,
        parameterFacts,
        localParameterFacts,
        path :+ "elementType" :+ "width",
        diagnostics,
        booleanParameterByName,
        booleanLocalParameterFacts
      )
      analyzeExpression(
        memory.depth,
        parameterFacts,
        localParameterFacts,
        path :+ "depth",
        diagnostics,
        booleanParameters = booleanParameterByName,
        booleanLocalParameters = booleanLocalParameterFacts
      ).foreach { facts =>
        if (!facts.interval.lower.exists(_ >= 1)) {
          val literal = memory.depth match {
            case Literal(value) => Some(value)
            case _              => None
          }
          diagnostics += Diagnostic(
            literal.fold("PRTL-MEMORY-DEPTH-NOT-PROVEN-POSITIVE")(_ => "PRTL-MEMORY-DEPTH-NOT-POSITIVE"),
            path :+ "depth",
            literal.fold(s"Memory depth domain ${renderInterval(facts.interval)} is not proven positive")(
              value => s"Memory depth literal $value is not positive"
            )
          )
        }
        if (facts.interval.upper.isEmpty)
          diagnostics += Diagnostic(
            "PRTL-MEMORY-DEPTH-UPPER-BOUND-NOT-PROVEN",
            path :+ "depth",
            s"Memory depth domain ${renderInterval(facts.interval)} does not have a finite upper bound"
          )
      }
    }

    synchronousReadFirstSimpleDualPortMemories.foreach { memory =>
      val path = modulePath :+ "synchronousReadFirstSimpleDualPortMemories" :+ memory.label
      validateWidth(
        memory.elementType.width,
        parameterFacts,
        localParameterFacts,
        path :+ "elementType" :+ "width",
        diagnostics,
        booleanParameterByName,
        booleanLocalParameterFacts
      )
      analyzeExpression(
        memory.depth,
        parameterFacts,
        localParameterFacts,
        path :+ "depth",
        diagnostics,
        booleanParameters = booleanParameterByName,
        booleanLocalParameters = booleanLocalParameterFacts
      ).foreach { facts =>
        if (!facts.interval.lower.exists(_ >= 1)) {
          val literal = memory.depth match {
            case Literal(value) => Some(value)
            case _              => None
          }
          diagnostics += Diagnostic(
            literal.fold("PRTL-MEMORY-DEPTH-NOT-PROVEN-POSITIVE")(_ => "PRTL-MEMORY-DEPTH-NOT-POSITIVE"),
            path :+ "depth",
            literal.fold(s"Memory depth domain ${renderInterval(facts.interval)} is not proven positive")(
              value => s"Memory depth literal $value is not positive"
            )
          )
        }
        if (facts.interval.upper.isEmpty)
          diagnostics += Diagnostic(
            "PRTL-MEMORY-DEPTH-UPPER-BOUND-NOT-PROVEN",
            path :+ "depth",
            s"Memory depth domain ${renderInterval(facts.interval)} does not have a finite upper bound"
          )
      }
    }

    synchronousCounters.foreach { counter =>
      val path = modulePath :+ "synchronousCounters" :+ counter.label :+ "limit"
      analyzeExpression(
        counter.limit,
        parameterFacts,
        localParameterFacts,
        path,
        diagnostics,
        booleanParameters = booleanParameterByName,
        booleanLocalParameters = booleanLocalParameterFacts
      ).foreach { facts =>
        if (!facts.interval.lower.exists(_ >= 1))
          diagnostics += Diagnostic(
            "PRTL-SYNCHRONOUS-COUNTER-LIMIT-NOT-PROVEN-POSITIVE",
            path,
            s"Synchronous counter limit domain ${renderInterval(facts.interval)} is not proven positive"
          )
        if (facts.interval.lower.isEmpty || facts.interval.upper.isEmpty)
          diagnostics += Diagnostic(
            "PRTL-SYNCHRONOUS-COUNTER-LIMIT-NOT-FINITELY-BOUNDED",
            path,
            s"Synchronous counter limit domain ${renderInterval(facts.interval)} must have finite lower and upper bounds"
          )
      }
    }

    generateFors.foreach { generate =>
      val path = modulePath :+ "generateFors" :+ generate.label :+ "count"
      analyzeExpression(
        generate.count,
        parameterFacts,
        localParameterFacts,
        path,
        diagnostics,
        booleanParameters = booleanParameterByName,
        booleanLocalParameters = booleanLocalParameterFacts
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
      booleanParameterByName,
      orderedLocalDeclarations = orderedLocalDeclarations,
      booleanLocalParameterFacts = booleanLocalParameterFacts
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
      generateIndices: Set[String] = Set.empty,
      booleanLocalParameters: Set[String] = Set.empty
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
    case BoolLocalParameterRef(name) =>
      if (!booleanLocalParameters.contains(name)) {
        val (code, message) =
          if (localParameters.contains(name))
            "PRTL-LOCAL-PARAMETER-KIND-MISMATCH" ->
              s"Boolean expression references integer local parameter '$name' as Boolean"
          else
            "PRTL-UNRESOLVED-BOOLEAN-LOCAL-PARAMETER" ->
              s"Boolean expression references unknown Boolean local parameter '$name'"
        diagnostics += Diagnostic(code, path, message)
      }
    case BoolNot(value) =>
      validateBooleanExpression(
        value,
        booleanParameters,
        integerParameters,
        localParameters,
        path :+ "operand",
        diagnostics,
        generateIndices,
        booleanLocalParameters
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
        generateIndices,
        booleanLocalParameters
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
        generateIndices,
        booleanLocalParameters
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
        generateIndices,
        booleanLocalParameters
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
        generateIndices,
        booleanLocalParameters
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
        generateIndices,
        booleanLocalParameters
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
        generateIndices,
        booleanLocalParameters
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
        generateIndices,
        booleanLocalParameters
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
        generateIndices,
        booleanLocalParameters
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
      generateIndices: Set[String],
      booleanLocalParameters: Set[String]
  ): Unit = {
    validateBooleanExpression(
      left,
      booleanParameters,
      integerParameters,
      localParameters,
      path :+ "left",
      diagnostics,
      generateIndices,
      booleanLocalParameters
    )
    validateBooleanExpression(
      right,
      booleanParameters,
      integerParameters,
      localParameters,
      path :+ "right",
      diagnostics,
      generateIndices,
      booleanLocalParameters
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
      generateIndices: Set[String],
      booleanLocalParameters: Set[String]
  ): Unit = {
    validateExpressionReferences(
      left,
      integerParameters,
      localParameters,
      path :+ "left",
      diagnostics,
      generateIndices,
      booleanParameters = booleanParameters,
      booleanLocalParameters = booleanLocalParameters
    )
    validateExpressionReferences(
      right,
      integerParameters,
      localParameters,
      path :+ "right",
      diagnostics,
      generateIndices,
      booleanParameters = booleanParameters,
      booleanLocalParameters = booleanLocalParameters
    )
  }

  private def analyzeBooleanExpression(
      expression: BoolExpr,
      booleanParameters: Map[String, BooleanParameter],
      parameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts],
      path: Vector[String],
      diagnostics: DiagnosticBuilder,
      booleanLocalParameters: Map[String, Boolean] = Map.empty
  ): Unit = expression match {
    case BoolLiteral(_) | BoolParameterRef(_) | BoolLocalParameterRef(_) =>
    case BoolNot(value) =>
      analyzeBooleanExpression(
        value,
        booleanParameters,
        parameters,
        localParameters,
        path :+ "operand",
        diagnostics,
        booleanLocalParameters
      )
    case BoolAnd(left, right) =>
      analyzeBooleanBinary(left, right, booleanParameters, parameters, localParameters, path, diagnostics, booleanLocalParameters)
    case BoolOr(left, right) =>
      analyzeBooleanBinary(left, right, booleanParameters, parameters, localParameters, path, diagnostics, booleanLocalParameters)
    case BoolLessThan(left, right) =>
      analyzeComparison(left, right, booleanParameters, parameters, localParameters, path, diagnostics, booleanLocalParameters)
    case BoolLessThanOrEqual(left, right) =>
      analyzeComparison(left, right, booleanParameters, parameters, localParameters, path, diagnostics, booleanLocalParameters)
    case BoolGreaterThan(left, right) =>
      analyzeComparison(left, right, booleanParameters, parameters, localParameters, path, diagnostics, booleanLocalParameters)
    case BoolGreaterThanOrEqual(left, right) =>
      analyzeComparison(left, right, booleanParameters, parameters, localParameters, path, diagnostics, booleanLocalParameters)
    case BoolEqual(left, right) =>
      analyzeComparison(left, right, booleanParameters, parameters, localParameters, path, diagnostics, booleanLocalParameters)
    case BoolNotEqual(left, right) =>
      analyzeComparison(left, right, booleanParameters, parameters, localParameters, path, diagnostics, booleanLocalParameters)
  }

  private def analyzeBooleanBinary(
      left: BoolExpr,
      right: BoolExpr,
      booleanParameters: Map[String, BooleanParameter],
      parameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts],
      path: Vector[String],
      diagnostics: DiagnosticBuilder,
      booleanLocalParameters: Map[String, Boolean]
  ): Unit = {
    analyzeBooleanExpression(
      left,
      booleanParameters,
      parameters,
      localParameters,
      path :+ "left",
      diagnostics,
      booleanLocalParameters
    )
    analyzeBooleanExpression(
      right,
      booleanParameters,
      parameters,
      localParameters,
      path :+ "right",
      diagnostics,
      booleanLocalParameters
    )
  }

  private def analyzeComparison(
      left: IntExpr,
      right: IntExpr,
      booleanParameters: Map[String, BooleanParameter],
      parameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts],
      path: Vector[String],
      diagnostics: DiagnosticBuilder,
      booleanLocalParameters: Map[String, Boolean]
  ): Unit = {
    analyzeExpression(
      left,
      parameters,
      localParameters,
      path :+ "left",
      diagnostics,
      booleanParameters = booleanParameters,
      booleanLocalParameters = booleanLocalParameters
    )
    analyzeExpression(
      right,
      parameters,
      localParameters,
      path :+ "right",
      diagnostics,
      booleanParameters = booleanParameters,
      booleanLocalParameters = booleanLocalParameters
    )
  }

  private def validateExpressionReferences(
      expression: IntExpr,
      parameters: Set[String],
      localParameters: Set[String],
      path: Vector[String],
      diagnostics: DiagnosticBuilder,
      generateIndices: Set[String] = Set.empty,
      booleanParameters: Map[String, BooleanParameter] = Map.empty,
      booleanLocalParameters: Set[String] = Set.empty
  ): Unit = {
    final case class Work(value: AnyRef, path: Vector[String])
    val work = scala.collection.mutable.ArrayBuffer(Work(expression, path))
    val seenIntegers = new java.util.IdentityHashMap[IntExpr, java.lang.Boolean]()
    val seenBooleans = new java.util.IdentityHashMap[BoolExpr, java.lang.Boolean]()

    def pushInteger(value: IntExpr, valuePath: Vector[String]): Unit = work += Work(value, valuePath)
    def pushBoolean(value: BoolExpr, valuePath: Vector[String]): Unit = work += Work(value, valuePath)

    while (work.nonEmpty) {
      val current = work.remove(work.length - 1)
      current.value match {
        case value: IntExpr if !seenIntegers.containsKey(value) =>
          seenIntegers.put(value, java.lang.Boolean.TRUE)
          value match {
            case Literal(_) =>
            case ParameterRef(name) if !parameters.contains(name) =>
              diagnostics += Diagnostic(
                "PRTL-UNRESOLVED-PARAMETER",
                current.path,
                s"Integer expression references unknown public parameter '$name'"
              )
            case ParameterRef(_) =>
            case LocalParameterRef(name) if !localParameters.contains(name) =>
              val (code, message) =
                if (booleanLocalParameters.contains(name))
                  "PRTL-LOCAL-PARAMETER-KIND-MISMATCH" ->
                    s"Integer expression references Boolean local parameter '$name' as integer"
                else
                  "PRTL-UNRESOLVED-LOCAL-PARAMETER" ->
                    s"Integer expression references unknown local parameter '$name'"
              diagnostics += Diagnostic(code, current.path, message)
            case LocalParameterRef(_) =>
            case GenerateIndexRef(name) if !generateIndices.contains(name) =>
              diagnostics += Diagnostic(
                "PRTL-GENERATE-INDEX-OUT-OF-SCOPE",
                current.path,
                s"Generate index '$name' is not in scope for this integer expression"
              )
            case GenerateIndexRef(_) =>
            case addressWidth: AddressWidth =>
              val (layers, base) = IntExpressionAnalysis.peelDirectAddressWidths(addressWidth)
              pushInteger(base, current.path ++ Vector.fill(layers)("operand"))
            case ceilLog2: CeilLog2 =>
              val (layers, base) = IntExpressionAnalysis.peelDirectCeilLog2s(ceilLog2)
              pushInteger(base, current.path ++ Vector.fill(layers)("operand"))
            case Negate(operand) => pushInteger(operand, current.path :+ "operand")
            case Add(left, right) =>
              pushInteger(right, current.path :+ "right")
              pushInteger(left, current.path :+ "left")
            case Subtract(left, right) =>
              pushInteger(right, current.path :+ "right")
              pushInteger(left, current.path :+ "left")
            case Multiply(left, right) =>
              pushInteger(right, current.path :+ "right")
              pushInteger(left, current.path :+ "left")
            case Divide(left, right) =>
              pushInteger(right, current.path :+ "right")
              pushInteger(left, current.path :+ "left")
            case Modulo(left, right) =>
              pushInteger(right, current.path :+ "right")
              pushInteger(left, current.path :+ "left")
            case Min(left, right) =>
              pushInteger(right, current.path :+ "right")
              pushInteger(left, current.path :+ "left")
            case Max(left, right) =>
              pushInteger(right, current.path :+ "right")
              pushInteger(left, current.path :+ "left")
            case Select(condition, whenTrue, whenFalse) =>
              pushInteger(whenFalse, current.path :+ "whenFalse")
              pushInteger(whenTrue, current.path :+ "whenTrue")
              pushBoolean(condition, current.path :+ "condition")
          }
        case value: BoolExpr if !seenBooleans.containsKey(value) =>
          seenBooleans.put(value, java.lang.Boolean.TRUE)
          value match {
            case BoolLiteral(_) =>
            case BoolParameterRef(name) if !booleanParameters.contains(name) =>
              diagnostics += Diagnostic(
                "PRTL-UNRESOLVED-BOOLEAN-PARAMETER",
                current.path,
                s"Boolean expression references unknown public parameter '$name'"
              )
            case BoolParameterRef(_) =>
            case BoolLocalParameterRef(name) if !booleanLocalParameters.contains(name) =>
              val (code, message) =
                if (localParameters.contains(name))
                  "PRTL-LOCAL-PARAMETER-KIND-MISMATCH" ->
                    s"Boolean expression references integer local parameter '$name' as Boolean"
                else
                  "PRTL-UNRESOLVED-BOOLEAN-LOCAL-PARAMETER" ->
                    s"Boolean expression references unknown Boolean local parameter '$name'"
              diagnostics += Diagnostic(code, current.path, message)
            case BoolLocalParameterRef(_) =>
            case BoolNot(operand) => pushBoolean(operand, current.path :+ "operand")
            case BoolAnd(left, right) =>
              pushBoolean(right, current.path :+ "right")
              pushBoolean(left, current.path :+ "left")
            case BoolOr(left, right) =>
              pushBoolean(right, current.path :+ "right")
              pushBoolean(left, current.path :+ "left")
            case BoolLessThan(left, right) =>
              pushInteger(right, current.path :+ "right")
              pushInteger(left, current.path :+ "left")
            case BoolLessThanOrEqual(left, right) =>
              pushInteger(right, current.path :+ "right")
              pushInteger(left, current.path :+ "left")
            case BoolGreaterThan(left, right) =>
              pushInteger(right, current.path :+ "right")
              pushInteger(left, current.path :+ "left")
            case BoolGreaterThanOrEqual(left, right) =>
              pushInteger(right, current.path :+ "right")
              pushInteger(left, current.path :+ "left")
            case BoolEqual(left, right) =>
              pushInteger(right, current.path :+ "right")
              pushInteger(left, current.path :+ "left")
            case BoolNotEqual(left, right) =>
              pushInteger(right, current.path :+ "right")
              pushInteger(left, current.path :+ "left")
          }
        case _ =>
      }
    }
  }

  private def validateBinaryReferences(
      left: IntExpr,
      right: IntExpr,
      parameters: Set[String],
      localParameters: Set[String],
      path: Vector[String],
      diagnostics: DiagnosticBuilder,
      generateIndices: Set[String],
      booleanParameters: Map[String, BooleanParameter],
      booleanLocalParameters: Set[String]
  ): Unit = {
    validateExpressionReferences(
      left,
      parameters,
      localParameters,
      path :+ "left",
      diagnostics,
      generateIndices,
      booleanParameters,
      booleanLocalParameters
    )
    validateExpressionReferences(
      right,
      parameters,
      localParameters,
      path :+ "right",
      diagnostics,
      generateIndices,
      booleanParameters,
      booleanLocalParameters
    )
  }

  private def analyzeExpression(
      expression: IntExpr,
      parameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts],
      path: Vector[String],
      diagnostics: DiagnosticBuilder,
      generateIndices: Map[String, IntExprFacts] = Map.empty,
      booleanParameters: Map[String, BooleanParameter] = Map.empty,
      booleanLocalParameters: Map[String, Boolean] = Map.empty
  ): Option[IntExprFacts] =
    IntExpressionAnalysis.analyze(
      expression,
      parameters,
      localParameters,
      booleanParameters,
      generateIndices,
      booleanLocalParameters
    ) match {
      case Right(facts) => Some(facts)
      case Left(DivisorMayBeZero(operator, interval)) =>
        diagnostics += Diagnostic(
          "PRTL-DIVISOR-MAY-BE-ZERO",
          path,
          s"Divisor of '$operator' is not proven nonzero over legal domain ${renderInterval(interval)}"
        )
        None
      case Left(AddressWidthOperandNotProvenPositive(interval)) =>
        diagnostics += Diagnostic(
          "PRTL-ADDRESS-WIDTH-OPERAND-NOT-PROVEN-POSITIVE",
          path,
          s"Address-width operand domain ${renderInterval(interval)} is not proven positive"
        )
        None
      case Left(CeilLog2OperandNotProvenPositive(interval)) =>
        diagnostics += Diagnostic(
          "PRTL-CEIL-LOG2-OPERAND-NOT-PROVEN-POSITIVE",
          path,
          s"Ceiling-log2 operand domain ${renderInterval(interval)} is not proven positive"
        )
        None
      case Left(_: UnresolvedParameter) |
          Left(_: UnresolvedBooleanParameter) |
          Left(_: UnresolvedBooleanLocalParameter) |
          Left(_: UnresolvedLocalParameter) |
          Left(_: UnresolvedGenerateIndex) => None
    }

  private def validateWidth(
      expression: IntExpr,
      parameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts],
      path: Vector[String],
      diagnostics: DiagnosticBuilder,
      booleanParameters: Map[String, BooleanParameter] = Map.empty,
      booleanLocalParameters: Map[String, Boolean] = Map.empty
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
        booleanParameters = booleanParameters,
        booleanLocalParameters = booleanLocalParameters
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
    val driverCounts = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
    val conditionalBranchDriverCounts =
      scala.collection.mutable.ArrayBuffer.empty[scala.collection.mutable.Map[String, Int]]
    val instanceFactsBuilder = Map.newBuilder[String, ValidatedInstanceFacts]
    val parentParameters = baseFacts.parameterFacts
    val parentLocals = baseFacts.localParameterFacts
    val parentBooleanParameters = baseFacts.booleanParameters
    val parentBooleanLocals = baseFacts.booleanLocalParameterFacts
    val parentParameterNames = module.parameters.map(_.name).toSet
    val parentLocalNames = module.localParameters.map(_.name).toSet
    val parentBooleanLocalNames = module.booleanLocalParameters.map(_.name).toSet
    val parentLocalExpressions = expandCombinedLocalExpressions(
      baseFacts.orderedLocalDeclarations,
      Map.empty,
      Map.empty
    )

    def expandParentExpression(expression: IntExpr): IntExpr =
      substituteLocalDefinition(
        expression,
        Map.empty,
        parentLocalExpressions.integer,
        Map.empty,
        parentLocalExpressions.boolean
      )

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
          parentBooleanParameters,
          parentBooleanLocalNames
        )
        analyzeExpression(
          offset,
          parentParameters,
          parentLocals,
          path :+ "offset",
          diagnostics,
          scopedIndexFacts,
          parentBooleanParameters,
          parentBooleanLocals
        )
        validateExpressionReferences(
          width,
          parentParameterNames,
          parentLocalNames,
          path :+ "width",
          diagnostics,
          allowedIndices,
          parentBooleanParameters,
          parentBooleanLocalNames
        )
        validateWidth(
          width,
          parentParameters,
          parentLocals,
          path :+ "width",
          diagnostics,
          parentBooleanParameters,
          parentBooleanLocals
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
      val booleanBindingPath = path :+ "booleanParameterBindings"
      val connectionPath = path :+ "portConnections"

      addDuplicateDiagnostics(
        instance.parameterBindings.map(_.parameterName),
        bindingPath,
        "PRTL-DUPLICATE-PARAMETER-BINDING",
        "parameter binding",
        diagnostics
      )
      addDuplicateDiagnostics(
        instance.booleanParameterBindings.map(_.parameterName),
        booleanBindingPath,
        "PRTL-DUPLICATE-BOOLEAN-PARAMETER-BINDING",
        "Boolean parameter binding",
        diagnostics
      )
      instance.parameterBindings
        .map(_.parameterName)
        .toSet
        .intersect(instance.booleanParameterBindings.map(_.parameterName).toSet)
        .toVector
        .sorted
        .foreach { name =>
          diagnostics += Diagnostic(
            "PRTL-DUPLICATE-INSTANCE-PARAMETER-BINDING",
            path :+ "parameterBindings" :+ name,
            s"Instance parameter '$name' is bound as both an integer and a Boolean parameter"
          )
        }
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
          booleanParameters = parentBooleanParameters,
          booleanLocalParameters = parentBooleanLocalNames
        )
      }
      instance.booleanParameterBindings.sortBy(_.parameterName).foreach { binding =>
        val currentPath = booleanBindingPath :+ binding.parameterName :+ "value"
        validateBooleanExpression(
          binding.value,
          parentBooleanParameters,
          parentParameterNames,
          parentLocalNames,
          currentPath,
          diagnostics,
          booleanLocalParameters = parentBooleanLocalNames
        )
        analyzeBooleanExpression(
          binding.value,
          parentBooleanParameters,
          parentParameters,
          parentLocals,
          currentPath,
          diagnostics,
          parentBooleanLocals
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
          val targetBooleanParameters = targetModule.booleanParameters.sortBy(_.name)
          val targetBooleanParameterByName = firstByName(targetBooleanParameters)(_.name)
          val bindingByName = firstByName(instance.parameterBindings.sortBy(_.parameterName))(_.parameterName)
          val booleanBindingByName =
            firstByName(instance.booleanParameterBindings.sortBy(_.parameterName))(_.parameterName)
          val analyzedBindings = scala.collection.mutable.Map.empty[String, IntExprFacts]
          val analyzedBooleanBindings = scala.collection.mutable.Map.empty[String, BooleanParameter]

          instance.parameterBindings.sortBy(_.parameterName).foreach { binding =>
            val currentPath = bindingPath :+ binding.parameterName
            targetParameterByName.get(binding.parameterName) match {
              case None if targetBooleanParameterByName.contains(binding.parameterName) =>
                diagnostics += Diagnostic(
                  "PRTL-INSTANCE-PARAMETER-KIND-MISMATCH",
                  currentPath,
                  s"Module '${targetModule.name}' parameter '${binding.parameterName}' is Boolean, but the instance supplies an integer binding"
                )
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
                  booleanParameters = parentBooleanParameters,
                  booleanLocalParameters = parentBooleanLocals
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

          instance.booleanParameterBindings.sortBy(_.parameterName).foreach { binding =>
            val currentPath = booleanBindingPath :+ binding.parameterName
            targetBooleanParameterByName.get(binding.parameterName) match {
              case None if targetParameterByName.contains(binding.parameterName) =>
                diagnostics += Diagnostic(
                  "PRTL-INSTANCE-PARAMETER-KIND-MISMATCH",
                  currentPath,
                  s"Module '${targetModule.name}' parameter '${binding.parameterName}' is integer, but the instance supplies a Boolean binding"
                )
              case None =>
                diagnostics += Diagnostic(
                  "PRTL-UNRESOLVED-INSTANCE-BOOLEAN-PARAMETER",
                  currentPath,
                  s"Module '${targetModule.name}' has no public Boolean parameter '${binding.parameterName}'"
                )
              case Some(targetParameter) =>
                BoolExpressionAnalysis
                  .evaluateDefault(
                    binding.value,
                    parentBooleanParameters,
                    parentParameters,
                    parentLocals,
                    Map.empty,
                    parentBooleanLocals
                  )
                  .toOption
                  .foreach { default =>
                    analyzedBooleanBindings.update(
                      binding.parameterName,
                      targetParameter.copy(default = default)
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
          val instantiatedBooleanParameters = targetBooleanParameters.map { parameter =>
            parameter.name -> analyzedBooleanBindings.getOrElse(parameter.name, parameter)
          }.toMap
          var instantiatedLocals = Map.empty[String, IntExprFacts]
          var instantiatedBooleanLocals = Map.empty[String, Boolean]
          targetBaseFacts.orderedLocalDeclarations.foreach {
            case localParameter: IntegerLocalParameter =>
              IntExpressionAnalysis
                .analyze(
                  localParameter.value,
                  instantiatedParameters,
                  instantiatedLocals,
                  instantiatedBooleanParameters,
                  Map.empty,
                  instantiatedBooleanLocals
                )
                .toOption
                .foreach(facts => instantiatedLocals = instantiatedLocals.updated(localParameter.name, facts))
            case localParameter: BooleanLocalParameter =>
              BoolExpressionAnalysis
                .evaluateDefault(
                  localParameter.value,
                  instantiatedBooleanParameters,
                  instantiatedParameters,
                  instantiatedLocals,
                  Map.empty,
                  instantiatedBooleanLocals
                )
                .toOption
                .foreach(value =>
                  instantiatedBooleanLocals = instantiatedBooleanLocals.updated(localParameter.name, value)
                )
          }

          val expandedBindings = targetParameters.map { parameter =>
            val raw = bindingByName.get(parameter.name).map(_.value).getOrElse(Literal(parameter.default))
            parameter.name -> substituteLocalDefinition(
              raw,
              Map.empty,
              parentLocalExpressions.integer,
              Map.empty,
              parentLocalExpressions.boolean
            )
          }.toMap
          val targetBooleanExpressions = targetBooleanParameters.map { parameter =>
            val expression = booleanBindingByName
              .get(parameter.name)
              .map(_.value)
              .getOrElse(BoolLiteral(parameter.default))
            parameter.name -> substituteBooleanDefinition(
              expression,
              Map.empty,
              parentLocalExpressions.integer,
              Map.empty,
              parentLocalExpressions.boolean
            )
          }.toMap
          val targetLocalExpressions =
            expandCombinedLocalExpressions(
              targetBaseFacts.orderedLocalDeclarations,
              expandedBindings,
              targetBooleanExpressions
            )
          val instantiatedPortTypes = targetModule.ports.map { port =>
            val width = port.dataType.width match {
              case ParameterRef(name)      => expandedBindings.getOrElse(name, port.dataType.width)
              case LocalParameterRef(name) => targetLocalExpressions.integer.getOrElse(name, port.dataType.width)
              case other =>
                substituteLocalDefinition(
                  other,
                  expandedBindings,
                  targetLocalExpressions.integer,
                  targetBooleanExpressions,
                  targetLocalExpressions.boolean
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
                      instantiatedBooleanParameters,
                      Map.empty,
                      instantiatedBooleanLocals
                    )
                    .toOption
                  val closedExpected = isClosedIntegerExpression(expected.width)
                  val effectiveExpected = instantiatedExpectedFacts
                    .filter(_ => closedExpected)
                    .map(facts => expected.copy(width = Literal(facts.defaultValue)))
                    .getOrElse(expected)
                  val effectiveExpectedFacts = instantiatedExpectedFacts.map { facts =>
                    if (closedExpected) facts.copy(interval = IntInterval.point(facts.defaultValue))
                    else facts
                  }
                  if (
                    !packedTypesEquivalent(
                      actual.dataType,
                      effectiveExpected,
                      module,
                      baseFacts,
                      effectiveExpectedFacts
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
            instantiatedPortTypes,
            instantiatedBooleanParameters,
            instantiatedBooleanLocals
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
        .analyze(
          generate.count,
          parentParameters,
          parentLocals,
          parentBooleanParameters,
          Map.empty,
          parentBooleanLocals
        )
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
      conditionalBranchDriverCounts ++= Vector(trueCounts, falseCounts)
    }

    generateCases.foreach { generate =>
      val path = modulePath :+ "generateCases" :+ generate.default.label

      def validateBlock(
          pathSuffix: Vector[String],
          block: GenerateBlock,
          counts: scala.collection.mutable.Map[String, Int]
      ): Unit = {
        val blockPath = path ++ pathSuffix
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

      sortedGenerateCaseChoices(generate).foreach { choice =>
        val counts = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
        validateBlock(Vector("choices", choice.value.toString, choice.block.label), choice.block, counts)
        conditionalBranchDriverCounts += counts
      }
      val defaultCounts = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
      validateBlock(Vector("default", generate.default.label), generate.default, defaultCounts)
      conditionalBranchDriverCounts += defaultCounts
    }

    combinationalIfs.foreach { process =>
      val path = modulePath :+ "combinationalProcesses" :+ process.label
      resolvePort(process.condition, portByName, path :+ "condition", diagnostics).foreach { port =>
        if (port.direction != Input)
          diagnostics += Diagnostic(
            "PRTL-COMBINATIONAL-CONDITION-NOT-INPUT",
            path :+ "condition",
            s"Combinational process condition '${port.name}' must be an input port"
          )
        if (!packedTypesEquivalent(port.dataType, PackedBits(Literal(1), Unsigned), module, baseFacts))
          diagnostics += Diagnostic(
            "PRTL-COMBINATIONAL-CONDITION-TYPE-MISMATCH",
            path :+ "condition",
            s"Combinational process condition '${port.name}' must have exact unsigned 1-bit type"
          )
      }

      def validateBranch(
          branchName: String,
          assignments: Vector[ProceduralAssign]
      ): scala.collection.mutable.Map[String, Int] = {
        val counts = scala.collection.mutable.Map.empty[String, Int].withDefaultValue(0)
        assignments.sortBy(assignment => (assignment.target.name, assignment.value.name)).zipWithIndex.foreach {
          case (assignment, index) =>
          val assignmentPath = path :+ branchName :+ "assignments" :+ index.toString
          val targetPort = resolvePort(assignment.target, portByName, assignmentPath :+ "target", diagnostics)
          val valuePort = resolvePort(assignment.value, portByName, assignmentPath :+ "value", diagnostics)

          targetPort.foreach { port =>
            counts.update(port.name, counts(port.name) + 1)
            if (port.direction != Output)
              diagnostics += Diagnostic(
                "PRTL-ILLEGAL-INPUT-DRIVER",
                assignmentPath :+ "target",
                s"Procedural assignment cannot drive input port '${port.name}'"
              )
          }
          valuePort.foreach { port =>
            if (port.direction != Input)
              diagnostics += Diagnostic(
                "PRTL-PROCEDURAL-OUTPUT-READ-UNSUPPORTED",
                assignmentPath :+ "value",
                s"Procedural assignment value '${port.name}' must be an input port; output reads are unsupported"
              )
          }
          for {
            targetType <- targetPort.map(_.dataType)
            valueType <- valuePort.map(_.dataType)
            if !packedTypesEquivalent(targetType, valueType, module, baseFacts)
          } diagnostics += Diagnostic(
            "PRTL-PROCEDURAL-TYPE-MISMATCH",
            assignmentPath,
            s"Procedural assignment target type '$targetType' does not exactly match value type '$valueType'"
          )
        }
        counts
      }

      conditionalBranchDriverCounts += validateBranch("whenTrue", process.whenTrue)
      conditionalBranchDriverCounts += validateBranch("whenFalse", process.whenFalse)
    }

    synchronousRegisters.foreach { process =>
      val path = modulePath :+ "synchronousRegisters" :+ process.label
      val oneBitUnsigned = PackedBits(Literal(1), Unsigned)

      def validateControl(role: String, reference: Ref): Option[Port] =
        resolvePort(reference, portByName, path :+ role, diagnostics).map { port =>
          if (port.direction != Input)
            diagnostics += Diagnostic(
              s"PRTL-SYNCHRONOUS-${role.toUpperCase}-NOT-INPUT",
              path :+ role,
              s"Synchronous register $role '${port.name}' must be an input port"
            )
          if (!packedTypesEquivalent(port.dataType, oneBitUnsigned, module, baseFacts))
            diagnostics += Diagnostic(
              s"PRTL-SYNCHRONOUS-${role.toUpperCase}-TYPE-MISMATCH",
              path :+ role,
              s"Synchronous register $role '${port.name}' must have exact unsigned 1-bit type"
            )
          port
        }

      validateControl("clock", process.clock)
      validateControl("reset", process.reset)

      val roleNames = Vector(
        "clock" -> process.clock.name,
        "reset" -> process.reset.name,
        "data" -> process.assignment.value.name
      )
      roleNames
        .groupBy(_._2)
        .collect { case (name, roles) if roles.size > 1 => name -> roles.map(_._1).sorted }
        .toVector
        .sortBy(_._1)
        .foreach { case (name, roles) =>
          diagnostics += Diagnostic(
            "PRTL-SYNCHRONOUS-REGISTER-ROLE-ALIAS",
            path :+ "roles" :+ name,
            s"Synchronous register roles must use distinct input ports; '$name' is used as ${roles.mkString(", ")}"
          )
        }

      val assignmentPath = path :+ "assignment"
      val targetPort =
        resolvePort(process.assignment.target, portByName, assignmentPath :+ "target", diagnostics)
      val valuePort =
        resolvePort(process.assignment.value, portByName, assignmentPath :+ "value", diagnostics)

      targetPort.foreach { port =>
        driverCounts.update(port.name, driverCounts(port.name) + 1)
        if (port.direction != Output)
          diagnostics += Diagnostic(
            "PRTL-ILLEGAL-INPUT-DRIVER",
            assignmentPath :+ "target",
            s"Synchronous register assignment cannot drive input port '${port.name}'"
          )
      }
      valuePort.foreach { port =>
        if (port.direction != Input)
          diagnostics += Diagnostic(
            "PRTL-SYNCHRONOUS-DATA-NOT-INPUT",
            assignmentPath :+ "value",
            s"Synchronous register data '${port.name}' must be an input port"
          )
      }
      for {
        targetType <- targetPort.map(_.dataType)
        valueType <- valuePort.map(_.dataType)
        if !packedTypesEquivalent(targetType, valueType, module, baseFacts)
      } diagnostics += Diagnostic(
        "PRTL-SYNCHRONOUS-DATA-TYPE-MISMATCH",
        assignmentPath,
        s"Synchronous register target type '$targetType' does not exactly match data type '$valueType'"
      )

      val outputNames = ports.filter(_.direction == Output).map(_.name)
      val targetName = targetPort.filter(_.direction == Output).map(_.name)
      if (targetName.forall(name => outputNames != Vector(name)))
        diagnostics += Diagnostic(
          "PRTL-SYNCHRONOUS-REGISTER-OUTPUT-SHAPE-UNSUPPORTED",
          path :+ "outputs",
          s"Synchronous register target must be the module's sole output; outputs are ${outputNames.mkString(", ")}"
        )
    }

    asynchronousRegisters.foreach { process =>
      val path = modulePath :+ "asynchronousRegisters" :+ process.label
      val oneBitUnsigned = PackedBits(Literal(1), Unsigned)

      def validateControl(role: String, reference: Ref): Option[Port] =
        resolvePort(reference, portByName, path :+ role, diagnostics).map { port =>
          if (port.direction != Input)
            diagnostics += Diagnostic(
              s"PRTL-ASYNCHRONOUS-${role.toUpperCase}-NOT-INPUT",
              path :+ role,
              s"Asynchronous register $role '${port.name}' must be an input port"
            )
          if (!packedTypesEquivalent(port.dataType, oneBitUnsigned, module, baseFacts))
            diagnostics += Diagnostic(
              s"PRTL-ASYNCHRONOUS-${role.toUpperCase}-TYPE-MISMATCH",
              path :+ role,
              s"Asynchronous register $role '${port.name}' must have exact unsigned 1-bit type"
            )
          port
        }

      validateControl("clock", process.clock)
      validateControl("reset", process.reset)

      val roleNames = Vector(
        "clock" -> process.clock.name,
        "reset" -> process.reset.name,
        "data" -> process.assignment.value.name
      )
      roleNames
        .groupBy(_._2)
        .collect { case (name, roles) if roles.size > 1 => name -> roles.map(_._1).sorted }
        .toVector
        .sortBy(_._1)
        .foreach { case (name, roles) =>
          diagnostics += Diagnostic(
            "PRTL-ASYNCHRONOUS-REGISTER-ROLE-ALIAS",
            path :+ "roles" :+ name,
            s"Asynchronous register roles must use distinct input ports; '$name' is used as ${roles.mkString(", ")}"
          )
        }

      val assignmentPath = path :+ "assignment"
      val targetPort =
        resolvePort(process.assignment.target, portByName, assignmentPath :+ "target", diagnostics)
      val valuePort =
        resolvePort(process.assignment.value, portByName, assignmentPath :+ "value", diagnostics)

      targetPort.foreach { port =>
        driverCounts.update(port.name, driverCounts(port.name) + 1)
        if (port.direction != Output)
          diagnostics += Diagnostic(
            "PRTL-ILLEGAL-INPUT-DRIVER",
            assignmentPath :+ "target",
            s"Asynchronous register assignment cannot drive input port '${port.name}'"
          )
      }
      valuePort.foreach { port =>
        if (port.direction != Input)
          diagnostics += Diagnostic(
            "PRTL-ASYNCHRONOUS-DATA-NOT-INPUT",
            assignmentPath :+ "value",
            s"Asynchronous register data '${port.name}' must be an input port"
          )
      }
      for {
        targetType <- targetPort.map(_.dataType)
        valueType <- valuePort.map(_.dataType)
        if !packedTypesEquivalent(targetType, valueType, module, baseFacts)
      } diagnostics += Diagnostic(
        "PRTL-ASYNCHRONOUS-DATA-TYPE-MISMATCH",
        assignmentPath,
        s"Asynchronous register target type '$targetType' does not exactly match data type '$valueType'"
      )

      val outputNames = ports.filter(_.direction == Output).map(_.name)
      val targetName = targetPort.filter(_.direction == Output).map(_.name)
      if (targetName.forall(name => outputNames != Vector(name)))
        diagnostics += Diagnostic(
          "PRTL-ASYNCHRONOUS-REGISTER-OUTPUT-SHAPE-UNSUPPORTED",
          path :+ "outputs",
          s"Asynchronous register target must be the module's sole output; outputs are ${outputNames.mkString(", ")}"
        )
    }

    synchronousEnabledRegisters.foreach { process =>
      val path = modulePath :+ "synchronousEnabledRegisters" :+ process.label
      val oneBitUnsigned = PackedBits(Literal(1), Unsigned)

      def validateControl(role: String, reference: Ref): Option[Port] =
        resolvePort(reference, portByName, path :+ role, diagnostics).map { port =>
          if (port.direction != Input)
            diagnostics += Diagnostic(
              s"PRTL-SYNCHRONOUS-ENABLED-${role.toUpperCase}-NOT-INPUT",
              path :+ role,
              s"Synchronous enabled register $role '${port.name}' must be an input port"
            )
          if (!packedTypesEquivalent(port.dataType, oneBitUnsigned, module, baseFacts))
            diagnostics += Diagnostic(
              s"PRTL-SYNCHRONOUS-ENABLED-${role.toUpperCase}-TYPE-MISMATCH",
              path :+ role,
              s"Synchronous enabled register $role '${port.name}' must have exact unsigned 1-bit type"
            )
          port
        }

      validateControl("clock", process.clock)
      validateControl("reset", process.reset)
      validateControl("enable", process.enable)

      val roleNames = Vector(
        "clock" -> process.clock.name,
        "reset" -> process.reset.name,
        "enable" -> process.enable.name,
        "data" -> process.assignment.value.name
      )
      roleNames
        .groupBy(_._2)
        .collect { case (name, roles) if roles.size > 1 => name -> roles.map(_._1).sorted }
        .toVector
        .sortBy(_._1)
        .foreach { case (name, roles) =>
          diagnostics += Diagnostic(
            "PRTL-SYNCHRONOUS-ENABLED-REGISTER-ROLE-ALIAS",
            path :+ "roles" :+ name,
            s"Synchronous enabled register roles must use distinct input ports; '$name' is used as ${roles.mkString(", ")}"
          )
        }

      val assignmentPath = path :+ "assignment"
      val targetPort =
        resolvePort(process.assignment.target, portByName, assignmentPath :+ "target", diagnostics)
      val valuePort =
        resolvePort(process.assignment.value, portByName, assignmentPath :+ "value", diagnostics)

      targetPort.foreach { port =>
        driverCounts.update(port.name, driverCounts(port.name) + 1)
        if (port.direction != Output)
          diagnostics += Diagnostic(
            "PRTL-ILLEGAL-INPUT-DRIVER",
            assignmentPath :+ "target",
            s"Synchronous enabled register assignment cannot drive input port '${port.name}'"
          )
      }
      valuePort.foreach { port =>
        if (port.direction != Input)
          diagnostics += Diagnostic(
            "PRTL-SYNCHRONOUS-ENABLED-DATA-NOT-INPUT",
            assignmentPath :+ "value",
            s"Synchronous enabled register data '${port.name}' must be an input port"
          )
      }
      for {
        targetType <- targetPort.map(_.dataType)
        valueType <- valuePort.map(_.dataType)
        if !packedTypesEquivalent(targetType, valueType, module, baseFacts)
      } diagnostics += Diagnostic(
        "PRTL-SYNCHRONOUS-ENABLED-DATA-TYPE-MISMATCH",
        assignmentPath,
        s"Synchronous enabled register target type '$targetType' does not exactly match data type '$valueType'"
      )

      val outputNames = ports.filter(_.direction == Output).map(_.name)
      val targetName = targetPort.filter(_.direction == Output).map(_.name)
      if (targetName.forall(name => outputNames != Vector(name)))
        diagnostics += Diagnostic(
          "PRTL-SYNCHRONOUS-ENABLED-REGISTER-OUTPUT-SHAPE-UNSUPPORTED",
          path :+ "outputs",
          s"Synchronous enabled register target must be the module's sole output; outputs are ${outputNames.mkString(", ")}"
        )
    }

    asynchronousEnabledRegisters.foreach { process =>
      val path = modulePath :+ "asynchronousEnabledRegisters" :+ process.label
      val oneBitUnsigned = PackedBits(Literal(1), Unsigned)

      def validateControl(role: String, reference: Ref): Option[Port] =
        resolvePort(reference, portByName, path :+ role, diagnostics).map { port =>
          if (port.direction != Input)
            diagnostics += Diagnostic(
              s"PRTL-ASYNCHRONOUS-ENABLED-${role.toUpperCase}-NOT-INPUT",
              path :+ role,
              s"Asynchronous enabled register $role '${port.name}' must be an input port"
            )
          if (!packedTypesEquivalent(port.dataType, oneBitUnsigned, module, baseFacts))
            diagnostics += Diagnostic(
              s"PRTL-ASYNCHRONOUS-ENABLED-${role.toUpperCase}-TYPE-MISMATCH",
              path :+ role,
              s"Asynchronous enabled register $role '${port.name}' must have exact unsigned 1-bit type"
            )
          port
        }

      validateControl("clock", process.clock)
      validateControl("reset", process.reset)
      validateControl("enable", process.enable)

      val roleNames = Vector(
        "clock" -> process.clock.name,
        "reset" -> process.reset.name,
        "enable" -> process.enable.name,
        "data" -> process.assignment.value.name
      )
      roleNames
        .groupBy(_._2)
        .collect { case (name, roles) if roles.size > 1 => name -> roles.map(_._1).sorted }
        .toVector
        .sortBy(_._1)
        .foreach { case (name, roles) =>
          diagnostics += Diagnostic(
            "PRTL-ASYNCHRONOUS-ENABLED-REGISTER-ROLE-ALIAS",
            path :+ "roles" :+ name,
            s"Asynchronous enabled register roles must use distinct input ports; '$name' is used as ${roles.mkString(", ")}"
          )
        }

      val assignmentPath = path :+ "assignment"
      val targetPort =
        resolvePort(process.assignment.target, portByName, assignmentPath :+ "target", diagnostics)
      val valuePort =
        resolvePort(process.assignment.value, portByName, assignmentPath :+ "value", diagnostics)

      targetPort.foreach { port =>
        driverCounts.update(port.name, driverCounts(port.name) + 1)
        if (port.direction != Output)
          diagnostics += Diagnostic(
            "PRTL-ILLEGAL-INPUT-DRIVER",
            assignmentPath :+ "target",
            s"Asynchronous enabled register assignment cannot drive input port '${port.name}'"
          )
      }
      valuePort.foreach { port =>
        if (port.direction != Input)
          diagnostics += Diagnostic(
            "PRTL-ASYNCHRONOUS-ENABLED-DATA-NOT-INPUT",
            assignmentPath :+ "value",
            s"Asynchronous enabled register data '${port.name}' must be an input port"
          )
      }
      for {
        targetType <- targetPort.map(_.dataType)
        valueType <- valuePort.map(_.dataType)
        if !packedTypesEquivalent(targetType, valueType, module, baseFacts)
      } diagnostics += Diagnostic(
        "PRTL-ASYNCHRONOUS-ENABLED-DATA-TYPE-MISMATCH",
        assignmentPath,
        s"Asynchronous enabled register target type '$targetType' does not exactly match data type '$valueType'"
      )

      val outputNames = ports.filter(_.direction == Output).map(_.name)
      val targetName = targetPort.filter(_.direction == Output).map(_.name)
      if (targetName.forall(name => outputNames != Vector(name)))
        diagnostics += Diagnostic(
          "PRTL-ASYNCHRONOUS-ENABLED-REGISTER-OUTPUT-SHAPE-UNSUPPORTED",
          path :+ "outputs",
          s"Asynchronous enabled register target must be the module's sole output; outputs are ${outputNames.mkString(", ")}"
        )
    }

    synchronousReadFirstSinglePortMemories.foreach { memory =>
      val path = modulePath :+ "synchronousReadFirstSinglePortMemories" :+ memory.label
      val oneBitUnsigned = PackedBits(Literal(1), Unsigned)

      def resolveInput(role: String, codeRole: String, reference: Ref): Option[Port] =
        resolvePort(reference, portByName, path :+ role, diagnostics).map { port =>
          if (port.direction != Input)
            diagnostics += Diagnostic(
              s"PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-$codeRole-NOT-INPUT",
              path :+ role,
              s"Synchronous read-first memory $role '${port.name}' must be an input port"
            )
          port
        }

      val clockPort = resolveInput("clock", "CLOCK", memory.clock)
      clockPort.foreach { port =>
        if (!packedTypesEquivalent(port.dataType, oneBitUnsigned, module, baseFacts))
          diagnostics += Diagnostic(
            "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-CLOCK-TYPE-MISMATCH",
            path :+ "clock",
            s"Synchronous read-first memory clock '${port.name}' must have exact unsigned 1-bit type"
          )
      }

      val readEnablePort = resolveInput("readEnable", "READ-ENABLE", memory.readEnable)
      readEnablePort.foreach { port =>
        if (!packedTypesEquivalent(port.dataType, oneBitUnsigned, module, baseFacts))
          diagnostics += Diagnostic(
            "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-READ-ENABLE-TYPE-MISMATCH",
            path :+ "readEnable",
            s"Synchronous read-first memory read enable '${port.name}' must have exact unsigned 1-bit type"
          )
      }

      val writeEnablePort = resolveInput("writeEnable", "WRITE-ENABLE", memory.writeEnable)
      writeEnablePort.foreach { port =>
        if (!packedTypesEquivalent(port.dataType, oneBitUnsigned, module, baseFacts))
          diagnostics += Diagnostic(
            "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-WRITE-ENABLE-TYPE-MISMATCH",
            path :+ "writeEnable",
            s"Synchronous read-first memory write enable '${port.name}' must have exact unsigned 1-bit type"
          )
      }

      val addressPort = resolveInput("address", "ADDRESS", memory.address)
      addressPort.foreach { port =>
        if (port.dataType.signedness != Unsigned)
          diagnostics += Diagnostic(
            "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-ADDRESS-TYPE-MISMATCH",
            path :+ "address",
            s"Synchronous read-first memory address '${port.name}' must be unsigned"
          )
      }

      val writeDataPort = resolveInput("writeData", "WRITE-DATA", memory.writeData)
      writeDataPort.foreach { port =>
        if (!packedTypesEquivalent(port.dataType, memory.elementType, module, baseFacts))
          diagnostics += Diagnostic(
            "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-WRITE-DATA-TYPE-MISMATCH",
            path :+ "writeData",
            s"Synchronous read-first memory write data type '${port.dataType}' does not exactly match element type '${memory.elementType}'"
          )
      }

      val readDataPort = resolvePort(memory.readData, portByName, path :+ "readData", diagnostics)
      readDataPort.foreach { port =>
        driverCounts.update(port.name, driverCounts(port.name) + 1)
        if (port.direction != Output)
          diagnostics += Diagnostic(
            "PRTL-ILLEGAL-INPUT-DRIVER",
            path :+ "readData",
            s"Synchronous read-first memory cannot drive input port '${port.name}'"
          )
        if (!packedTypesEquivalent(port.dataType, memory.elementType, module, baseFacts))
          diagnostics += Diagnostic(
            "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-READ-DATA-TYPE-MISMATCH",
            path :+ "readData",
            s"Synchronous read-first memory read data type '${port.dataType}' does not exactly match element type '${memory.elementType}'"
          )
      }

      Vector(
        "address" -> memory.address.name,
        "clock" -> memory.clock.name,
        "readData" -> memory.readData.name,
        "readEnable" -> memory.readEnable.name,
        "writeData" -> memory.writeData.name,
        "writeEnable" -> memory.writeEnable.name
      ).groupBy(_._2)
        .collect { case (name, roles) if roles.size > 1 => name -> roles.map(_._1).sorted }
        .toVector
        .sortBy(_._1)
        .foreach { case (name, roles) =>
          diagnostics += Diagnostic(
            "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-ROLE-ALIAS",
            path :+ "roles" :+ name,
            s"Synchronous read-first memory roles must use distinct ports; '$name' is used as ${roles.mkString(", ")}"
          )
        }

      val depthFacts = IntExpressionAnalysis
        .analyze(
          memory.depth,
          parentParameters,
          parentLocals,
          parentBooleanParameters,
          Map.empty,
          parentBooleanLocals
        )
        .toOption
      val addressWidthFacts = addressPort.flatMap(port => expressionFacts(port.dataType.width, baseFacts))
      val conservativeCapacityProven = for {
        depth <- depthFacts
        minimumDepth <- depth.interval.lower
        maximumDepth <- depth.interval.upper
        minimumAddressWidth <- addressWidthFacts.flatMap(_.interval.lower)
        if minimumDepth >= 1
      } yield {
        val requiredAddressWidth =
          if (maximumDepth <= 1) BigInt(1) else BigInt((maximumDepth - 1).bitLength)
        minimumAddressWidth >= requiredAddressWidth
      }
      val expandedLocals = expandCombinedLocalExpressions(
        baseFacts.orderedLocalDeclarations,
        Map.empty,
        Map.empty
      )
      def expandForCorrelation(expression: IntExpr): IntExpr =
        substituteLocalDefinition(
          expression,
          Map.empty,
          expandedLocals.integer,
          Map.empty,
          expandedLocals.boolean
        )
      val expandedDepth = expandForCorrelation(memory.depth)
      val correlatedCapacityProven = addressPort.exists { port =>
        expandForCorrelation(port.dataType.width) match {
          case AddressWidth(operand) =>
            IntExpressionEquivalence.equivalent(operand, expandedDepth)
          case _ => false
        }
      }
      if (!conservativeCapacityProven.contains(true) && !correlatedCapacityProven)
        diagnostics += Diagnostic(
          "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-ADDRESS-CAPACITY-NOT-PROVEN",
          path :+ "address",
          "Address width is not proven sufficient for the maximum legal memory depth"
        )

      val outputNames = ports.filter(_.direction == Output).map(_.name)
      val readOutputName = readDataPort.filter(_.direction == Output).map(_.name)
      if (readOutputName.forall(name => outputNames != Vector(name)))
        diagnostics += Diagnostic(
          "PRTL-SYNCHRONOUS-READ-FIRST-MEMORY-OUTPUT-SHAPE-UNSUPPORTED",
          path :+ "outputs",
          s"Synchronous read-first memory read data must be the module's sole output; outputs are ${outputNames.mkString(", ")}"
        )
    }

    synchronousReadFirstSimpleDualPortMemories.foreach { memory =>
      val path = modulePath :+ "synchronousReadFirstSimpleDualPortMemories" :+ memory.label
      val prefix = "PRTL-SYNCHRONOUS-READ-FIRST-SIMPLE-DUAL-PORT-MEMORY"
      val oneBitUnsigned = PackedBits(Literal(1), Unsigned)

      def resolveInput(role: String, codeRole: String, reference: Ref): Option[Port] =
        resolvePort(reference, portByName, path :+ role, diagnostics).map { port =>
          if (port.direction != Input)
            diagnostics += Diagnostic(
              s"$prefix-$codeRole-NOT-INPUT",
              path :+ role,
              s"Synchronous read-first simple dual-port memory $role '${port.name}' must be an input port"
            )
          port
        }

      def requireOneBitControl(role: String, codeRole: String, reference: Ref): Option[Port] = {
        val port = resolveInput(role, codeRole, reference)
        port.foreach { value =>
          if (!packedTypesEquivalent(value.dataType, oneBitUnsigned, module, baseFacts))
            diagnostics += Diagnostic(
              s"$prefix-$codeRole-TYPE-MISMATCH",
              path :+ role,
              s"Synchronous read-first simple dual-port memory $role '${value.name}' must have exact unsigned 1-bit type"
            )
        }
        port
      }

      requireOneBitControl("clock", "CLOCK", memory.clock)
      requireOneBitControl("readEnable", "READ-ENABLE", memory.readEnable)
      requireOneBitControl("writeEnable", "WRITE-ENABLE", memory.writeEnable)

      val readAddressPort = resolveInput("readAddress", "READ-ADDRESS", memory.readAddress)
      readAddressPort.foreach { port =>
        if (port.dataType.signedness != Unsigned)
          diagnostics += Diagnostic(
            s"$prefix-READ-ADDRESS-TYPE-MISMATCH",
            path :+ "readAddress",
            s"Synchronous read-first simple dual-port memory read address '${port.name}' must be unsigned"
          )
      }

      val writeAddressPort = resolveInput("writeAddress", "WRITE-ADDRESS", memory.writeAddress)
      writeAddressPort.foreach { port =>
        if (port.dataType.signedness != Unsigned)
          diagnostics += Diagnostic(
            s"$prefix-WRITE-ADDRESS-TYPE-MISMATCH",
            path :+ "writeAddress",
            s"Synchronous read-first simple dual-port memory write address '${port.name}' must be unsigned"
          )
      }

      for {
        readPort <- readAddressPort
        writePort <- writeAddressPort
        if !packedTypesEquivalent(readPort.dataType, writePort.dataType, module, baseFacts)
      } diagnostics += Diagnostic(
        s"$prefix-ADDRESS-TYPE-MISMATCH",
        path :+ "addresses",
        s"Synchronous read-first simple dual-port memory read address type '${readPort.dataType}' does not exactly match write address type '${writePort.dataType}'"
      )

      val writeDataPort = resolveInput("writeData", "WRITE-DATA", memory.writeData)
      writeDataPort.foreach { port =>
        if (!packedTypesEquivalent(port.dataType, memory.elementType, module, baseFacts))
          diagnostics += Diagnostic(
            s"$prefix-WRITE-DATA-TYPE-MISMATCH",
            path :+ "writeData",
            s"Synchronous read-first simple dual-port memory write data type '${port.dataType}' does not exactly match element type '${memory.elementType}'"
          )
      }

      val readDataPort = resolvePort(memory.readData, portByName, path :+ "readData", diagnostics)
      readDataPort.foreach { port =>
        driverCounts.update(port.name, driverCounts(port.name) + 1)
        if (port.direction != Output)
          diagnostics += Diagnostic(
            s"$prefix-READ-DATA-NOT-OUTPUT",
            path :+ "readData",
            s"Synchronous read-first simple dual-port memory read data '${port.name}' must be an output port"
          )
        if (!packedTypesEquivalent(port.dataType, memory.elementType, module, baseFacts))
          diagnostics += Diagnostic(
            s"$prefix-READ-DATA-TYPE-MISMATCH",
            path :+ "readData",
            s"Synchronous read-first simple dual-port memory read data type '${port.dataType}' does not exactly match element type '${memory.elementType}'"
          )
      }

      Vector(
        "clock" -> memory.clock.name,
        "readAddress" -> memory.readAddress.name,
        "readData" -> memory.readData.name,
        "readEnable" -> memory.readEnable.name,
        "writeAddress" -> memory.writeAddress.name,
        "writeData" -> memory.writeData.name,
        "writeEnable" -> memory.writeEnable.name
      ).groupBy(_._2)
        .collect { case (name, roles) if roles.size > 1 => name -> roles.map(_._1).sorted }
        .toVector
        .sortBy(_._1)
        .foreach { case (name, roles) =>
          diagnostics += Diagnostic(
            s"$prefix-ROLE-ALIAS",
            path :+ "roles" :+ name,
            s"Synchronous read-first simple dual-port memory roles must use distinct ports; '$name' is used as ${roles.mkString(", ")}"
          )
        }

      val depthFacts = IntExpressionAnalysis
        .analyze(
          memory.depth,
          parentParameters,
          parentLocals,
          parentBooleanParameters,
          Map.empty,
          parentBooleanLocals
        )
        .toOption
      val expandedLocals = expandCombinedLocalExpressions(
        baseFacts.orderedLocalDeclarations,
        Map.empty,
        Map.empty
      )
      def expandForCorrelation(expression: IntExpr): IntExpr =
        substituteLocalDefinition(
          expression,
          Map.empty,
          expandedLocals.integer,
          Map.empty,
          expandedLocals.boolean
        )
      val expandedDepth = expandForCorrelation(memory.depth)

      def capacityProven(port: Option[Port]): Boolean = {
        val addressWidthFacts = port.flatMap(value => expressionFacts(value.dataType.width, baseFacts))
        val conservative = for {
          depth <- depthFacts
          minimumDepth <- depth.interval.lower
          maximumDepth <- depth.interval.upper
          minimumAddressWidth <- addressWidthFacts.flatMap(_.interval.lower)
          if minimumDepth >= 1
        } yield {
          val requiredAddressWidth =
            if (maximumDepth <= 1) BigInt(1) else BigInt((maximumDepth - 1).bitLength)
          minimumAddressWidth >= requiredAddressWidth
        }
        val correlated = port.exists { value =>
          expandForCorrelation(value.dataType.width) match {
            case AddressWidth(operand) =>
              IntExpressionEquivalence.equivalent(operand, expandedDepth)
            case _ => false
          }
        }
        conservative.contains(true) || correlated
      }

      if (!capacityProven(readAddressPort))
        diagnostics += Diagnostic(
          s"$prefix-READ-ADDRESS-CAPACITY-NOT-PROVEN",
          path :+ "readAddress",
          "Read-address width is not proven sufficient for the maximum legal memory depth"
        )
      if (!capacityProven(writeAddressPort))
        diagnostics += Diagnostic(
          s"$prefix-WRITE-ADDRESS-CAPACITY-NOT-PROVEN",
          path :+ "writeAddress",
          "Write-address width is not proven sufficient for the maximum legal memory depth"
        )

      val outputNames = ports.filter(_.direction == Output).map(_.name)
      val readOutputName = readDataPort.filter(_.direction == Output).map(_.name)
      if (readOutputName.forall(name => outputNames != Vector(name)))
        diagnostics += Diagnostic(
          s"$prefix-OUTPUT-SHAPE-UNSUPPORTED",
          path :+ "outputs",
          s"Synchronous read-first simple dual-port memory read data must be the module's sole output; outputs are ${outputNames.mkString(", ")}"
        )
    }

    synchronousCounters.foreach { counter =>
      val path = modulePath :+ "synchronousCounters" :+ counter.label
      val oneBitUnsigned = PackedBits(Literal(1), Unsigned)

      def validateControl(role: String, reference: Ref): Option[Port] =
        resolvePort(reference, portByName, path :+ role, diagnostics).map { port =>
          if (port.direction != Input)
            diagnostics += Diagnostic(
              s"PRTL-SYNCHRONOUS-COUNTER-${role.toUpperCase}-NOT-INPUT",
              path :+ role,
              s"Synchronous counter $role '${port.name}' must be an input port"
            )
          if (!packedTypesEquivalent(port.dataType, oneBitUnsigned, module, baseFacts))
            diagnostics += Diagnostic(
              s"PRTL-SYNCHRONOUS-COUNTER-${role.toUpperCase}-TYPE-MISMATCH",
              path :+ role,
              s"Synchronous counter $role '${port.name}' must have exact unsigned 1-bit type"
            )
          port
        }

      validateControl("clock", counter.clock)
      validateControl("reset", counter.reset)
      validateControl("enable", counter.enable)

      val countPort = resolvePort(counter.count, portByName, path :+ "count", diagnostics)
      countPort.foreach { port =>
        driverCounts.update(port.name, driverCounts(port.name) + 1)
        if (port.direction != Output)
          diagnostics += Diagnostic(
            "PRTL-SYNCHRONOUS-COUNTER-COUNT-NOT-OUTPUT",
            path :+ "count",
            s"Synchronous counter count '${port.name}' must be an output port"
          )
      }

      val expandedLocals = expandCombinedLocalExpressions(
        baseFacts.orderedLocalDeclarations,
        Map.empty,
        Map.empty
      )
      def expandForCorrelation(expression: IntExpr): IntExpr =
        substituteLocalDefinition(
          expression,
          Map.empty,
          expandedLocals.integer,
          Map.empty,
          expandedLocals.boolean
        )
      val expandedLimit = expandForCorrelation(counter.limit)
      val countTypeMatches = countPort.exists { port =>
        port.dataType.signedness == Unsigned &&
        (expandForCorrelation(port.dataType.width) match {
          case AddressWidth(operand) =>
            IntExpressionEquivalence.equivalent(operand, expandedLimit)
          case _ => false
        })
      }
      if (!countTypeMatches)
        diagnostics += Diagnostic(
          "PRTL-SYNCHRONOUS-COUNTER-COUNT-TYPE-MISMATCH",
          path :+ "count",
          "Synchronous counter count must have exact unsigned AddressWidth(limit) type"
        )

      Vector(
        "clock" -> counter.clock.name,
        "count" -> counter.count.name,
        "enable" -> counter.enable.name,
        "reset" -> counter.reset.name
      ).groupBy(_._2)
        .collect { case (name, roles) if roles.size > 1 => name -> roles.map(_._1).sorted }
        .toVector
        .sortBy(_._1)
        .foreach { case (name, roles) =>
          diagnostics += Diagnostic(
            "PRTL-SYNCHRONOUS-COUNTER-ROLE-ALIAS",
            path :+ "roles" :+ name,
            s"Synchronous counter roles must use distinct ports; '$name' is used as ${roles.mkString(", ")}"
          )
        }

      val outputNames = ports.filter(_.direction == Output).map(_.name)
      val countOutputName = countPort.filter(_.direction == Output).map(_.name)
      if (countOutputName.forall(name => outputNames != Vector(name)))
        diagnostics += Diagnostic(
          "PRTL-SYNCHRONOUS-COUNTER-OUTPUT-SHAPE-UNSUPPORTED",
          path :+ "outputs",
          s"Synchronous counter count must be the module's sole output; outputs are ${outputNames.mkString(", ")}"
        )
    }

    ports.filter(_.direction == Output).foreach { port =>
      val legalDriverCounts =
        if (conditionalBranchDriverCounts.isEmpty) Vector(driverCounts(port.name))
        else conditionalBranchDriverCounts.toVector.map(branch => driverCounts(port.name) + branch(port.name))
      if (legalDriverCounts.exists(_ == 0)) {
        val message =
          if (
            generateIfs.isEmpty && generateCases.isEmpty && combinationalIfs.isEmpty &&
            synchronousRegisters.isEmpty && asynchronousRegisters.isEmpty &&
            synchronousEnabledRegisters.isEmpty && asynchronousEnabledRegisters.isEmpty &&
            synchronousReadFirstSinglePortMemories.isEmpty &&
            synchronousReadFirstSimpleDualPortMemories.isEmpty && synchronousCounters.isEmpty
          )
            s"Output port '${port.name}' has no driver"
          else if (combinationalIfs.nonEmpty)
            s"Output port '${port.name}' is undriven in at least one runtime combinational branch"
          else if (synchronousRegisters.nonEmpty)
            s"Output port '${port.name}' is not owned by the synchronous register process"
          else if (asynchronousRegisters.nonEmpty)
            s"Output port '${port.name}' is not owned by the asynchronous register process"
          else if (synchronousEnabledRegisters.nonEmpty)
            s"Output port '${port.name}' is not owned by the synchronous enabled register process"
          else if (asynchronousEnabledRegisters.nonEmpty)
            s"Output port '${port.name}' is not owned by the asynchronous enabled register process"
          else if (synchronousReadFirstSinglePortMemories.nonEmpty)
            s"Output port '${port.name}' is not owned by the synchronous read-first single-port memory"
          else if (synchronousReadFirstSimpleDualPortMemories.nonEmpty)
            s"Output port '${port.name}' is not owned by the synchronous read-first simple dual-port memory"
          else if (synchronousCounters.nonEmpty)
            s"Output port '${port.name}' is not owned by the synchronous counter"
          else s"Output port '${port.name}' is undriven for at least one legal generate configuration"
        diagnostics += Diagnostic(
          "PRTL-UNDRIVEN-OUTPUT",
          modulePath :+ "ports" :+ port.name,
          message
        )
      }
      val maximumDrivers = legalDriverCounts.max
      if (legalDriverCounts.exists(_ > 1)) {
        val message =
          if (
            generateIfs.isEmpty && generateCases.isEmpty && combinationalIfs.isEmpty &&
            synchronousRegisters.isEmpty && asynchronousRegisters.isEmpty &&
            synchronousEnabledRegisters.isEmpty && asynchronousEnabledRegisters.isEmpty &&
            synchronousReadFirstSinglePortMemories.isEmpty &&
            synchronousReadFirstSimpleDualPortMemories.isEmpty && synchronousCounters.isEmpty
          )
            s"Output port '${port.name}' has $maximumDrivers drivers"
          else if (combinationalIfs.nonEmpty)
            s"Output port '${port.name}' has up to $maximumDrivers drivers in a runtime combinational branch"
          else if (synchronousRegisters.nonEmpty)
            s"Output port '${port.name}' has $maximumDrivers drivers including its synchronous register process"
          else if (asynchronousRegisters.nonEmpty)
            s"Output port '${port.name}' has $maximumDrivers drivers including its asynchronous register process"
          else if (synchronousEnabledRegisters.nonEmpty)
            s"Output port '${port.name}' has $maximumDrivers drivers including its synchronous enabled register process"
          else if (asynchronousEnabledRegisters.nonEmpty)
            s"Output port '${port.name}' has $maximumDrivers drivers including its asynchronous enabled register process"
          else if (synchronousReadFirstSinglePortMemories.nonEmpty)
            s"Output port '${port.name}' has $maximumDrivers drivers including its synchronous read-first single-port memory"
          else if (synchronousReadFirstSimpleDualPortMemories.nonEmpty)
            s"Output port '${port.name}' has $maximumDrivers drivers including its synchronous read-first simple dual-port memory"
          else if (synchronousCounters.nonEmpty)
            s"Output port '${port.name}' has $maximumDrivers drivers including its synchronous counter"
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
      val locals = expandCombinedLocalExpressions(
        parentFacts.orderedLocalDeclarations,
        Map.empty,
        Map.empty
      )
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
          case LocalParameterRef(name) => locals.integer.getOrElse(name, left.width)
          case other =>
            substituteLocalDefinition(other, Map.empty, locals.integer, Map.empty, locals.boolean)
        }
        val rightWidth = rightFactsOverride match {
          // Instance-side widths are already expanded in their source scope. Keep the
          // shared DAG opaque so a deep local chain reaches iterative equality safely.
          case Some(_) => right.width
          case None =>
            right.width match {
              case LocalParameterRef(name) => locals.integer.getOrElse(name, right.width)
              case other =>
                substituteLocalDefinition(other, Map.empty, locals.integer, Map.empty, locals.boolean)
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
            Map.empty,
            facts.booleanLocalParameterFacts
          )
          .toOption
    }

  /** Iterative mixed-expression walk stays stack-safe for deeply expanded shared local DAGs. */
  private def isClosedIntegerExpression(expression: IntExpr): Boolean = {
    val integers = scala.collection.mutable.ArrayBuffer(expression)
    val booleans = scala.collection.mutable.ArrayBuffer.empty[BoolExpr]
    val visitedIntegers = new java.util.IdentityHashMap[IntExpr, java.lang.Boolean]()
    val visitedBooleans = new java.util.IdentityHashMap[BoolExpr, java.lang.Boolean]()

    while (integers.nonEmpty || booleans.nonEmpty) {
      if (integers.nonEmpty) {
        val value = integers.remove(integers.length - 1)
        if (!visitedIntegers.containsKey(value)) {
          visitedIntegers.put(value, java.lang.Boolean.TRUE)
          value match {
            case Literal(_) =>
            case ParameterRef(_) | LocalParameterRef(_) | GenerateIndexRef(_) => return false
            case AddressWidth(operand) => integers += operand
            case CeilLog2(operand) => integers += operand
            case Negate(operand) => integers += operand
            case Add(left, right) => integers += left; integers += right
            case Subtract(left, right) => integers += left; integers += right
            case Multiply(left, right) => integers += left; integers += right
            case Divide(left, right) => integers += left; integers += right
            case Modulo(left, right) => integers += left; integers += right
            case Min(left, right) => integers += left; integers += right
            case Max(left, right) => integers += left; integers += right
            case Select(condition, whenTrue, whenFalse) =>
              booleans += condition
              integers += whenTrue
              integers += whenFalse
          }
        }
      } else {
        val value = booleans.remove(booleans.length - 1)
        if (!visitedBooleans.containsKey(value)) {
          visitedBooleans.put(value, java.lang.Boolean.TRUE)
          value match {
            case BoolLiteral(_) =>
            case BoolParameterRef(_) | BoolLocalParameterRef(_) => return false
            case BoolNot(operand) => booleans += operand
            case BoolAnd(left, right) => booleans += left; booleans += right
            case BoolOr(left, right) => booleans += left; booleans += right
            case BoolLessThan(left, right) => integers += left; integers += right
            case BoolLessThanOrEqual(left, right) => integers += left; integers += right
            case BoolGreaterThan(left, right) => integers += left; integers += right
            case BoolGreaterThanOrEqual(left, right) => integers += left; integers += right
            case BoolEqual(left, right) => integers += left; integers += right
            case BoolNotEqual(left, right) => integers += left; integers += right
          }
        }
      }
    }
    true
  }

  private final case class ExpandedLocalExpressions(
      integer: Map[String, IntExpr],
      boolean: Map[String, BoolExpr]
  )

  /** Dependency-first expansion shares prior DAG nodes and never walks a local chain recursively. */
  private def expandCombinedLocalExpressions(
      ordered: Vector[LocalParameterDeclaration],
      parameters: Map[String, IntExpr],
      booleanParameters: Map[String, BoolExpr] = Map.empty
  ): ExpandedLocalExpressions = {
    var expandedIntegers = Map.empty[String, IntExpr]
    var expandedBooleans = Map.empty[String, BoolExpr]
    ordered.foreach {
      case localParameter: IntegerLocalParameter =>
        expandedIntegers = expandedIntegers.updated(
          localParameter.name,
          substituteLocalDefinition(
            localParameter.value,
            parameters,
            expandedIntegers,
            booleanParameters,
            expandedBooleans
          )
        )
      case localParameter: BooleanLocalParameter =>
        expandedBooleans = expandedBooleans.updated(
          localParameter.name,
          substituteBooleanDefinition(
            localParameter.value,
            parameters,
            expandedIntegers,
            booleanParameters,
            expandedBooleans
          )
        )
    }
    ExpandedLocalExpressions(expandedIntegers, expandedBooleans)
  }

  private def substituteLocalDefinition(
      expression: IntExpr,
      parameters: Map[String, IntExpr],
      locals: Map[String, IntExpr],
      booleanParameters: Map[String, BoolExpr] = Map.empty,
      booleanLocals: Map[String, BoolExpr] = Map.empty
  ): IntExpr =
    substituteLocalDefinitionMemoized(
      expression,
      parameters,
      locals,
      booleanParameters,
      booleanLocals,
      new java.util.IdentityHashMap[IntExpr, IntExpr](),
      new java.util.IdentityHashMap[BoolExpr, BoolExpr]()
    )

  private def substituteLocalDefinitionMemoized(
      expression: IntExpr,
      parameters: Map[String, IntExpr],
      locals: Map[String, IntExpr],
      booleanParameters: Map[String, BoolExpr],
      booleanLocals: Map[String, BoolExpr],
      integerMemo: java.util.IdentityHashMap[IntExpr, IntExpr],
      booleanMemo: java.util.IdentityHashMap[BoolExpr, BoolExpr]
  ): IntExpr = {
    substituteDefinitionGraph(
      expression,
      parameters,
      locals,
      booleanParameters,
      booleanLocals,
      integerMemo,
      booleanMemo
    )
    integerMemo.get(expression)
  }

  private def substituteBooleanDefinition(
      expression: BoolExpr,
      parameters: Map[String, IntExpr],
      locals: Map[String, IntExpr],
      booleanParameters: Map[String, BoolExpr],
      booleanLocals: Map[String, BoolExpr] = Map.empty
  ): BoolExpr =
    substituteBooleanDefinitionMemoized(
      expression,
      parameters,
      locals,
      booleanParameters,
      booleanLocals,
      new java.util.IdentityHashMap[IntExpr, IntExpr](),
      new java.util.IdentityHashMap[BoolExpr, BoolExpr]()
    )

  private def substituteBooleanDefinitionMemoized(
      expression: BoolExpr,
      parameters: Map[String, IntExpr],
      locals: Map[String, IntExpr],
      booleanParameters: Map[String, BoolExpr],
      booleanLocals: Map[String, BoolExpr],
      integerMemo: java.util.IdentityHashMap[IntExpr, IntExpr],
      booleanMemo: java.util.IdentityHashMap[BoolExpr, BoolExpr]
  ): BoolExpr = {
    substituteDefinitionGraph(
      expression,
      parameters,
      locals,
      booleanParameters,
      booleanLocals,
      integerMemo,
      booleanMemo
    )
    booleanMemo.get(expression)
  }

  /** Iterative mixed-expression rebuild preserves DAG sharing without consuming call stack. */
  private def substituteDefinitionGraph(
      root: AnyRef,
      parameters: Map[String, IntExpr],
      locals: Map[String, IntExpr],
      booleanParameters: Map[String, BoolExpr],
      booleanLocals: Map[String, BoolExpr],
      integerMemo: java.util.IdentityHashMap[IntExpr, IntExpr],
      booleanMemo: java.util.IdentityHashMap[BoolExpr, BoolExpr]
  ): Unit = {
    final case class Frame(value: AnyRef, expanded: Boolean)
    val work = scala.collection.mutable.ArrayBuffer(Frame(root, expanded = false))

    def isDone(value: AnyRef): Boolean = value match {
      case integer: IntExpr => integerMemo.containsKey(integer)
      case boolean: BoolExpr => booleanMemo.containsKey(boolean)
      case _ => true
    }
    def push(value: AnyRef): Unit = work += Frame(value, expanded = false)
    def integer(value: IntExpr): IntExpr = integerMemo.get(value)
    def boolean(value: BoolExpr): BoolExpr = booleanMemo.get(value)

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
            case value: Literal          => integerMemo.put(value, value)
            case value @ ParameterRef(name) => integerMemo.put(value, parameters.getOrElse(name, value))
            case value @ LocalParameterRef(name) => integerMemo.put(value, locals.getOrElse(name, value))
            case value: GenerateIndexRef => integerMemo.put(value, value)
            case value @ AddressWidth(operand) => integerMemo.put(value, AddressWidth(integer(operand)))
            case value @ CeilLog2(operand)     => integerMemo.put(value, CeilLog2(integer(operand)))
            case value @ Negate(operand)       => integerMemo.put(value, Negate(integer(operand)))
            case value @ Add(left, right)      => integerMemo.put(value, Add(integer(left), integer(right)))
            case value @ Subtract(left, right) => integerMemo.put(value, Subtract(integer(left), integer(right)))
            case value @ Multiply(left, right) => integerMemo.put(value, Multiply(integer(left), integer(right)))
            case value @ Divide(left, right)   => integerMemo.put(value, Divide(integer(left), integer(right)))
            case value @ Modulo(left, right)   => integerMemo.put(value, Modulo(integer(left), integer(right)))
            case value @ Min(left, right)      => integerMemo.put(value, Min(integer(left), integer(right)))
            case value @ Max(left, right)      => integerMemo.put(value, Max(integer(left), integer(right)))
            case value @ Select(condition, whenTrue, whenFalse) =>
              integerMemo.put(value, Select(boolean(condition), integer(whenTrue), integer(whenFalse)))
            case value: BoolLiteral => booleanMemo.put(value, value)
            case value @ BoolParameterRef(name) =>
              booleanMemo.put(value, booleanParameters.getOrElse(name, value))
            case value @ BoolLocalParameterRef(name) =>
              booleanMemo.put(value, booleanLocals.getOrElse(name, value))
            case value @ BoolNot(operand) => booleanMemo.put(value, BoolNot(boolean(operand)))
            case value @ BoolAnd(left, right) => booleanMemo.put(value, BoolAnd(boolean(left), boolean(right)))
            case value @ BoolOr(left, right) => booleanMemo.put(value, BoolOr(boolean(left), boolean(right)))
            case value @ BoolLessThan(left, right) => booleanMemo.put(value, BoolLessThan(integer(left), integer(right)))
            case value @ BoolLessThanOrEqual(left, right) =>
              booleanMemo.put(value, BoolLessThanOrEqual(integer(left), integer(right)))
            case value @ BoolGreaterThan(left, right) =>
              booleanMemo.put(value, BoolGreaterThan(integer(left), integer(right)))
            case value @ BoolGreaterThanOrEqual(left, right) =>
              booleanMemo.put(value, BoolGreaterThanOrEqual(integer(left), integer(right)))
            case value @ BoolEqual(left, right) => booleanMemo.put(value, BoolEqual(integer(left), integer(right)))
            case value @ BoolNotEqual(left, right) =>
              booleanMemo.put(value, BoolNotEqual(integer(left), integer(right)))
          }
        }
      }
    }
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
      case generate: GenerateCase =>
        generateCaseBlocksInOrder(generate).foreach { block =>
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

  private def sortedGenerateCaseChoices(generate: GenerateCase): Vector[GenerateCaseChoice] =
    generate.choices.sortBy(choice => (choice.value, choice.block.label))

  private def generateCaseBlocksInOrder(generate: GenerateCase): Vector[GenerateBlock] =
    sortedGenerateCaseChoices(generate).map(_.block) :+ generate.default

  private def generateCaseNamedBlocks(
      generate: GenerateCase
  ): Vector[(Vector[String], GenerateBlock)] =
    sortedGenerateCaseChoices(generate).map { choice =>
      Vector("choices", choice.value.toString, choice.block.label) -> choice.block
    } :+ (Vector("default", generate.default.label) -> generate.default)

  private def generateCaseSortKey(generate: GenerateCase): (String, String) =
    generate.default.label -> sortedGenerateCaseChoices(generate)
      .map(choice => s"${choice.value}:${choice.block.label}")
      .mkString("|")

  private def moduleItemKind(item: ModuleItem): String = item match {
    case _: ContinuousAssign   => "a continuous assignment"
    case _: ModuleInstance     => "a module instance"
    case _: GenerateFor        => "a generate-for construct"
    case _: GenerateIf         => "a generate-if construct"
    case _: GenerateCase       => "a generate-case construct"
    case _: CombinationalIf    => "a runtime combinational process"
    case _: SynchronousRegister  => "another synchronous register process"
    case _: AsynchronousRegister => "another asynchronous register process"
    case _: SynchronousEnabledRegister => "another synchronous enabled register process"
    case _: AsynchronousEnabledRegister => "another asynchronous enabled register process"
    case _: SynchronousReadFirstSinglePortMemory =>
      "another synchronous read-first single-port memory"
    case _: SynchronousReadFirstSimpleDualPortMemory =>
      "another synchronous read-first simple dual-port memory"
    case _: SynchronousCounter => "another synchronous counter process"
  }

  private def moduleItemStableKey(item: ModuleItem): String = item match {
    case assignment: ContinuousAssign =>
      s"0:${assignment.target.name}:${assignment.value}"
    case instance: ModuleInstance =>
      s"1:${instance.name}:${instance.moduleName}:${instance.parameterBindings}:${instance.booleanParameterBindings}:${instance.portConnections}"
    case generate: GenerateFor =>
      s"2:${generate.label}:${generate.indexName}:${generate.count}:${generate.body}"
    case generate: GenerateIf =>
      s"3:${generateIfSortKey(generate)}:${generate.condition}:${generate.whenTrue.body}:${generate.whenFalse.body}"
    case generate: GenerateCase =>
      s"4:${generateCaseSortKey(generate)}:${generate.selector}:${generate.choices}:${generate.default.body}"
    case process: CombinationalIf =>
      s"5:${process.label}:${process.condition.name}:${process.whenTrue}:${process.whenFalse}"
    case process: SynchronousRegister =>
      s"6:${process.label}:${process.clock.name}:${process.reset.name}:${process.assignment}"
    case process: AsynchronousRegister =>
      s"7:${process.label}:${process.clock.name}:${process.reset.name}:${process.assignment}"
    case process: SynchronousEnabledRegister =>
      s"8:${process.label}:${process.clock.name}:${process.reset.name}:${process.enable.name}:${process.assignment}"
    case process: AsynchronousEnabledRegister =>
      s"9:${process.label}:${process.clock.name}:${process.reset.name}:${process.enable.name}:${process.assignment}"
    case memory: SynchronousReadFirstSinglePortMemory =>
      s"10:${memory.label}:${memory.memoryName}:${memory.clock.name}:${memory.readEnable.name}:${memory.writeEnable.name}:${memory.address.name}:${memory.writeData.name}:${memory.readData.name}:${memory.elementType}:${memory.depth}"
    case counter: SynchronousCounter =>
      s"11:${counter.label}:${counter.clock.name}:${counter.reset.name}:${counter.enable.name}:${counter.count.name}:${counter.limit}"
    case memory: SynchronousReadFirstSimpleDualPortMemory =>
      s"12:${memory.label}:${memory.memoryName}:${memory.clock.name}:${memory.readEnable.name}:${memory.writeEnable.name}:${memory.readAddress.name}:${memory.writeAddress.name}:${memory.writeData.name}:${memory.readData.name}:${memory.elementType}:${memory.depth}"
  }

  private def addDuplicateCaseValueDiagnostics(
      generate: GenerateCase,
      path: Vector[String],
      diagnostics: DiagnosticBuilder
  ): Unit =
    generate.choices
      .groupBy(_.value)
      .collect { case (value, choices) if choices.size > 1 => value -> choices.map(_.block.label).sorted }
      .toVector
      .sortBy(_._1)
      .foreach { case (value, labels) =>
        diagnostics += Diagnostic(
          "PRTL-DUPLICATE-GENERATE-CASE-VALUE",
          path :+ "choices" :+ value.toString,
          s"Generate-case literal '$value' is selected by multiple branches: ${labels.mkString(", ")}"
        )
      }

  private def containsGenerateIndex(expression: IntExpr): Boolean = {
    val stack = scala.collection.mutable.ArrayBuffer(expression)
    while (stack.nonEmpty) {
      stack.remove(stack.length - 1) match {
        case GenerateIndexRef(_)                                 => return true
        case Literal(_) | ParameterRef(_) | LocalParameterRef(_) =>
        case AddressWidth(value)                                 => stack += value
        case CeilLog2(value)                                     => stack += value
        case Negate(value)                                       => stack += value
        case Add(left, right)                                    => stack += left; stack += right
        case Subtract(left, right)                               => stack += left; stack += right
        case Multiply(left, right)                               => stack += left; stack += right
        case Divide(left, right)                                 => stack += left; stack += right
        case Modulo(left, right)                                 => stack += left; stack += right
        case Min(left, right)                                    => stack += left; stack += right
        case Max(left, right)                                    => stack += left; stack += right
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
        case BoolLiteral(_) | BoolParameterRef(_) | BoolLocalParameterRef(_) =>
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
