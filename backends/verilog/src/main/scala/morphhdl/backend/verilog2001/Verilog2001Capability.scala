package morphhdl.backend.verilog2001

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
import morphhdl.paramrtl._
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
import morphhdl.paramrtl.RtlExpr.{IndexedPartSelect, Ref}

object Verilog2001Capability {
  private val MinimumInteger = BigInt(Int.MinValue)
  private val MaximumInteger = BigInt(Int.MaxValue)
  private val ReservedWords = Set(
    "always",
    "and",
    "assign",
    "automatic",
    "begin",
    "buf",
    "bufif0",
    "bufif1",
    "case",
    "casex",
    "casez",
    "cell",
    "cmos",
    "config",
    "deassign",
    "default",
    "defparam",
    "design",
    "disable",
    "edge",
    "else",
    "end",
    "endcase",
    "endconfig",
    "endfunction",
    "endgenerate",
    "endmodule",
    "endprimitive",
    "endspecify",
    "endtable",
    "endtask",
    "event",
    "for",
    "force",
    "forever",
    "fork",
    "function",
    "generate",
    "genvar",
    "highz0",
    "highz1",
    "if",
    "ifnone",
    "incdir",
    "include",
    "initial",
    "inout",
    "input",
    "instance",
    "integer",
    "join",
    "large",
    "liblist",
    "library",
    "localparam",
    "macromodule",
    "medium",
    "module",
    "nand",
    "negedge",
    "nmos",
    "nor",
    "noshowcancelled",
    "notif0",
    "notif1",
    "or",
    "output",
    "parameter",
    "pmos",
    "posedge",
    "primitive",
    "pull0",
    "pull1",
    "pulldown",
    "pullup",
    "pulsestyle_ondetect",
    "pulsestyle_onevent",
    "rcmos",
    "real",
    "realtime",
    "reg",
    "release",
    "repeat",
    "rnmos",
    "rpmos",
    "rtran",
    "rtranif0",
    "rtranif1",
    "scalared",
    "showcancelled",
    "signed",
    "small",
    "specify",
    "specparam",
    "strong0",
    "strong1",
    "supply0",
    "supply1",
    "table",
    "task",
    "time",
    "tran",
    "tranif0",
    "tranif1",
    "tri",
    "tri0",
    "tri1",
    "triand",
    "trior",
    "trireg",
    "unsigned",
    "use",
    "vectored",
    "wait",
    "wand",
    "weak0",
    "weak1",
    "while",
    "wire",
    "wor",
    "xnor",
    "xor"
  )

  def verify(design: ValidatedDesign): Either[DiagnosticSet, ValidatedDesign] = {
    val diagnostics = Vector.newBuilder[Diagnostic]

    design.value.modules.sortBy(_.name).foreach { module =>
      val modulePath = Vector("modules", module.name)
      checkName(module.name, modulePath :+ "name", diagnostics)

      val parameters = module.parameters.sortBy(_.name)
      parameters.foreach { parameter =>
        val path = modulePath :+ "parameters" :+ parameter.name
        checkName(parameter.name, path :+ "name", diagnostics)
        checkInteger(parameter.default, path :+ "default", diagnostics)
        parameter.constraints.zipWithIndex.foreach {
          case (MinInclusive(value), index) =>
            checkInteger(value, path :+ "constraints" :+ index.toString, diagnostics)
          case (MaxInclusive(value), index) =>
            checkInteger(value, path :+ "constraints" :+ index.toString, diagnostics)
        }
        checkIntegerDomain(parameter, path :+ "constraints", diagnostics)
      }
      module.booleanParameters.sortBy(_.name).foreach { parameter =>
        checkName(
          parameter.name,
          modulePath :+ "booleanParameters" :+ parameter.name :+ "name",
          diagnostics
        )
      }
      val booleanParameterByName =
        module.booleanParameters.map(parameter => parameter.name -> parameter).toMap

      val facts = design.moduleFacts(module.name)

      module.localParameters.sortBy(_.name).foreach { localParameter =>
        val path = modulePath :+ "localParameters" :+ localParameter.name
        checkName(localParameter.name, path :+ "name", diagnostics)
        checkExpression(
          localParameter.value,
          facts.parameterFacts,
          facts.localParameterFacts,
          path :+ "value",
          diagnostics,
          booleanParameters = booleanParameterByName,
          booleanLocalParameters = facts.booleanLocalParameterFacts
        )
      }
      module.booleanLocalParameters.sortBy(_.name).foreach { localParameter =>
        val path = modulePath :+ "booleanLocalParameters" :+ localParameter.name
        checkName(localParameter.name, path :+ "name", diagnostics)
        checkBooleanExpression(
          localParameter.value,
          booleanParameterByName,
          facts.parameterFacts,
          facts.localParameterFacts,
          path :+ "value",
          diagnostics,
          booleanLocalParameters = facts.booleanLocalParameterFacts
        )
      }

      module.ports.sortBy(_.name).foreach { port =>
        val path = modulePath :+ "ports" :+ port.name
        checkName(port.name, path :+ "name", diagnostics)
        checkExpression(
          port.dataType.width,
          facts.parameterFacts,
          facts.localParameterFacts,
          path :+ "width",
          diagnostics,
          booleanParameters = booleanParameterByName,
          booleanLocalParameters = facts.booleanLocalParameterFacts
        )
      }

      module.items.collect { case instance: ModuleInstance => instance }.sortBy(_.name).foreach { instance =>
        val path = modulePath :+ "instances" :+ instance.name
        checkInstance(instance, facts, path, Map.empty, booleanParameterByName, diagnostics)
      }

      module.items.collect { case generate: GenerateFor => generate }.sortBy(_.label).foreach { generate =>
        val path = modulePath :+ "generateFors" :+ generate.label
        checkName(generate.label, path :+ "label", diagnostics)
        checkName(generate.indexName, path :+ "indexName", diagnostics)
        checkExpression(
          generate.count,
          facts.parameterFacts,
          facts.localParameterFacts,
          path :+ "count",
          diagnostics,
          booleanParameters = booleanParameterByName,
          booleanLocalParameters = facts.booleanLocalParameterFacts
        )

        val generateIndices = IntExpressionAnalysis
          .analyze(
            generate.count,
            facts.parameterFacts,
            facts.localParameterFacts,
            booleanParameterByName,
            Map.empty,
            facts.booleanLocalParameterFacts
          )
          .toOption
          .map { countFacts =>
            generate.indexName -> IntExprFacts(
              BigInt(0),
              IntInterval(Some(BigInt(0)), countFacts.interval.upper.map(_ - 1))
            )
          }
          .toMap

        generate.body.collect { case instance: ModuleInstance => instance }.sortBy(_.name).foreach { instance =>
          checkInstance(
            instance,
            facts,
            path :+ "instances" :+ instance.name,
            generateIndices,
            booleanParameterByName,
            diagnostics
          )
        }
      }

      module.items.collect { case generate: GenerateIf => generate }.sortBy(generateIfSortKey).foreach { generate =>
        val path = modulePath :+ "generateIfs" :+ generate.whenTrue.label
        checkBooleanExpression(
          generate.condition,
          booleanParameterByName,
          facts.parameterFacts,
          facts.localParameterFacts,
          path :+ "condition",
          diagnostics,
          booleanLocalParameters = facts.booleanLocalParameterFacts
        )
        checkGenerateBlock(
          generate.whenTrue,
          facts,
          booleanParameterByName,
          path :+ "whenTrue",
          diagnostics
        )
        checkGenerateBlock(
          generate.whenFalse,
          facts,
          booleanParameterByName,
          path :+ "whenFalse",
          diagnostics
        )
      }

      module.items.collect { case generate: GenerateCase => generate }.sortBy(generateCaseSortKey).foreach {
        generate =>
          val path = modulePath :+ "generateCases" :+ generate.default.label
          checkExpression(
            generate.selector,
            facts.parameterFacts,
            facts.localParameterFacts,
            path :+ "selector",
            diagnostics,
            booleanParameters = booleanParameterByName,
            booleanLocalParameters = facts.booleanLocalParameterFacts
          )
          generate.choices.sortBy(choice => (choice.value, choice.block.label)).foreach { choice =>
            checkInteger(choice.value, path :+ "choices" :+ choice.value.toString :+ "value", diagnostics)
            checkGenerateBlock(
              choice.block,
              facts,
              booleanParameterByName,
              path :+ "choices" :+ choice.value.toString :+ choice.block.label,
              diagnostics
            )
          }
          checkGenerateBlock(
            generate.default,
            facts,
            booleanParameterByName,
            path :+ "default" :+ generate.default.label,
            diagnostics
          )
      }

      module.items.collect { case assignment: ContinuousAssign => assignment }.zipWithIndex.foreach {
        case (assignment, index) =>
          checkRtlExpression(
            assignment.value,
            facts,
            modulePath :+ "assignments" :+ index.toString :+ "value",
            Map.empty,
            booleanParameterByName,
            diagnostics
          )
      }

      module.items.collect { case process: CombinationalIf => process }.sortBy(_.label).foreach { process =>
        checkName(
          process.label,
          modulePath :+ "combinationalProcesses" :+ process.label :+ "label",
          diagnostics
        )
      }

      module.items.collect { case process: SynchronousRegister => process }.sortBy(_.label).foreach { process =>
        checkName(
          process.label,
          modulePath :+ "synchronousRegisters" :+ process.label :+ "label",
          diagnostics
        )
      }

      module.items.collect { case process: AsynchronousRegister => process }.sortBy(_.label).foreach { process =>
        checkName(
          process.label,
          modulePath :+ "asynchronousRegisters" :+ process.label :+ "label",
          diagnostics
        )
      }

      module.items.collect { case process: SynchronousEnabledRegister => process }.sortBy(_.label).foreach {
        process =>
          checkName(
            process.label,
            modulePath :+ "synchronousEnabledRegisters" :+ process.label :+ "label",
            diagnostics
          )
      }

      module.items.collect { case process: AsynchronousEnabledRegister => process }.sortBy(_.label).foreach {
        process =>
          checkName(
            process.label,
            modulePath :+ "asynchronousEnabledRegisters" :+ process.label :+ "label",
            diagnostics
          )
      }

      module.items.collect { case memory: SynchronousReadFirstSinglePortMemory => memory }.sortBy(_.label).foreach {
        memory =>
          val path = modulePath :+ "synchronousReadFirstSinglePortMemories" :+ memory.label
          checkName(memory.label, path :+ "label", diagnostics)
          checkName(memory.memoryName, path :+ "memoryName", diagnostics)
          checkExpression(
            memory.elementType.width,
            facts.parameterFacts,
            facts.localParameterFacts,
            path :+ "elementType" :+ "width",
            diagnostics,
            booleanParameters = booleanParameterByName,
            booleanLocalParameters = facts.booleanLocalParameterFacts
          )
          checkExpression(
            memory.depth,
            facts.parameterFacts,
            facts.localParameterFacts,
            path :+ "depth",
            diagnostics,
            booleanParameters = booleanParameterByName,
            booleanLocalParameters = facts.booleanLocalParameterFacts
          )
      }

      module.items.collect { case memory: SynchronousReadFirstSimpleDualPortMemory => memory }.sortBy(_.label).foreach {
        memory =>
          val path = modulePath :+ "synchronousReadFirstSimpleDualPortMemories" :+ memory.label
          checkName(memory.label, path :+ "label", diagnostics)
          checkName(memory.memoryName, path :+ "memoryName", diagnostics)
          checkExpression(
            memory.elementType.width,
            facts.parameterFacts,
            facts.localParameterFacts,
            path :+ "elementType" :+ "width",
            diagnostics,
            booleanParameters = booleanParameterByName,
            booleanLocalParameters = facts.booleanLocalParameterFacts
          )
          checkExpression(
            memory.depth,
            facts.parameterFacts,
            facts.localParameterFacts,
            path :+ "depth",
            diagnostics,
            booleanParameters = booleanParameterByName,
            booleanLocalParameters = facts.booleanLocalParameterFacts
          )
      }

      module.items.collect { case counter: SynchronousCounter => counter }.sortBy(_.label).foreach {
        counter =>
          val path = modulePath :+ "synchronousCounters" :+ counter.label
          checkName(counter.label, path :+ "label", diagnostics)
          checkExpression(
            counter.limit,
            facts.parameterFacts,
            facts.localParameterFacts,
            path :+ "limit",
            diagnostics,
            booleanParameters = booleanParameterByName,
            booleanLocalParameters = facts.booleanLocalParameterFacts
          )
      }
    }

    val result = DiagnosticSet.from(diagnostics.result())
    if (result.isEmpty) Right(design) else Left(result)
  }

  private def checkGenerateBlock(
      block: GenerateBlock,
      facts: ValidatedModuleFacts,
      booleanParameters: Map[String, BooleanParameter],
      path: Vector[String],
      diagnostics: scala.collection.mutable.Builder[Diagnostic, Vector[Diagnostic]]
  ): Unit = {
    checkName(block.label, path :+ "label", diagnostics)
    block.body.collect { case instance: ModuleInstance => instance }.sortBy(_.name).foreach { instance =>
      checkInstance(
        instance,
        facts,
        path :+ "instances" :+ instance.name,
        Map.empty,
        booleanParameters,
        diagnostics
      )
    }
    block.body.collect { case assignment: ContinuousAssign => assignment }.zipWithIndex.foreach {
      case (assignment, index) =>
        checkRtlExpression(
          assignment.value,
          facts,
          path :+ "assignments" :+ index.toString :+ "value",
          Map.empty,
          booleanParameters,
          diagnostics
        )
    }
  }

  private def checkBooleanExpression(
      expression: BoolExpr,
      booleanParameters: Map[String, BooleanParameter],
      integerParameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts],
      path: Vector[String],
      diagnostics: scala.collection.mutable.Builder[Diagnostic, Vector[Diagnostic]],
      generateIndices: Map[String, IntExprFacts] = Map.empty,
      booleanLocalParameters: Map[String, Boolean] = Map.empty
  ): Unit = {
    final case class Work(value: BoolExpr, path: Vector[String])
    val work = scala.collection.mutable.ArrayBuffer(Work(expression, path))
    val seen = new java.util.IdentityHashMap[BoolExpr, java.lang.Boolean]()

    while (work.nonEmpty) {
      val current = work.remove(work.length - 1)
      if (!seen.containsKey(current.value)) {
        seen.put(current.value, java.lang.Boolean.TRUE)
        current.value match {
          case BoolLiteral(_) | BoolParameterRef(_) | BoolLocalParameterRef(_) =>
          case BoolNot(operand) => work += Work(operand, current.path :+ "operand")
          case BoolAnd(left, right) =>
            work += Work(right, current.path :+ "right")
            work += Work(left, current.path :+ "left")
          case BoolOr(left, right) =>
            work += Work(right, current.path :+ "right")
            work += Work(left, current.path :+ "left")
          case BoolLessThan(left, right) =>
            checkComparison(left, right, booleanParameters, integerParameters, localParameters, current.path, diagnostics, generateIndices, booleanLocalParameters)
          case BoolLessThanOrEqual(left, right) =>
            checkComparison(left, right, booleanParameters, integerParameters, localParameters, current.path, diagnostics, generateIndices, booleanLocalParameters)
          case BoolGreaterThan(left, right) =>
            checkComparison(left, right, booleanParameters, integerParameters, localParameters, current.path, diagnostics, generateIndices, booleanLocalParameters)
          case BoolGreaterThanOrEqual(left, right) =>
            checkComparison(left, right, booleanParameters, integerParameters, localParameters, current.path, diagnostics, generateIndices, booleanLocalParameters)
          case BoolEqual(left, right) =>
            checkComparison(left, right, booleanParameters, integerParameters, localParameters, current.path, diagnostics, generateIndices, booleanLocalParameters)
          case BoolNotEqual(left, right) =>
            checkComparison(left, right, booleanParameters, integerParameters, localParameters, current.path, diagnostics, generateIndices, booleanLocalParameters)
        }
      }
    }
  }

  private def checkComparison(
      left: IntExpr,
      right: IntExpr,
      booleanParameters: Map[String, BooleanParameter],
      parameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts],
      path: Vector[String],
      diagnostics: scala.collection.mutable.Builder[Diagnostic, Vector[Diagnostic]],
      generateIndices: Map[String, IntExprFacts],
      booleanLocalParameters: Map[String, Boolean]
  ): Unit = {
    checkExpression(
      left,
      parameters,
      localParameters,
      path :+ "left",
      diagnostics,
      generateIndices,
      booleanParameters,
      booleanLocalParameters
    )
    checkExpression(
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

  private def checkInstance(
      instance: ModuleInstance,
      facts: ValidatedModuleFacts,
      path: Vector[String],
      generateIndices: Map[String, IntExprFacts],
      booleanParameters: Map[String, BooleanParameter],
      diagnostics: scala.collection.mutable.Builder[Diagnostic, Vector[Diagnostic]]
  ): Unit = {
    checkName(instance.name, path :+ "name", diagnostics)
    instance.parameterBindings.sortBy(_.parameterName).foreach { binding =>
      checkExpression(
        binding.value,
        facts.parameterFacts,
        facts.localParameterFacts,
        path :+ "parameterBindings" :+ binding.parameterName :+ "value",
        diagnostics,
        generateIndices,
        booleanParameters,
        facts.booleanLocalParameterFacts
      )
    }
    instance.booleanParameterBindings.sortBy(_.parameterName).foreach { binding =>
      checkBooleanExpression(
        binding.value,
        booleanParameters,
        facts.parameterFacts,
        facts.localParameterFacts,
        path :+ "booleanParameterBindings" :+ binding.parameterName :+ "value",
        diagnostics,
        generateIndices,
        facts.booleanLocalParameterFacts
      )
    }
    instance.portConnections.sortBy(_.portName).foreach { connection =>
      checkRtlExpression(
        connection.actual,
        facts,
        path :+ "portConnections" :+ connection.portName :+ "actual",
        generateIndices,
        booleanParameters,
        diagnostics
      )
    }
  }

  private def checkRtlExpression(
      expression: RtlExpr,
      facts: ValidatedModuleFacts,
      path: Vector[String],
      generateIndices: Map[String, IntExprFacts],
      booleanParameters: Map[String, BooleanParameter],
      diagnostics: scala.collection.mutable.Builder[Diagnostic, Vector[Diagnostic]]
  ): Unit = expression match {
    case Ref(_) =>
    case IndexedPartSelect(_, offset, width) =>
      checkExpression(
        offset,
        facts.parameterFacts,
        facts.localParameterFacts,
        path :+ "offset",
        diagnostics,
        generateIndices,
        booleanParameters,
        facts.booleanLocalParameterFacts
      )
      checkExpression(
        width,
        facts.parameterFacts,
        facts.localParameterFacts,
        path :+ "width",
        diagnostics,
        generateIndices,
        booleanParameters,
        facts.booleanLocalParameterFacts
      )
  }

  private def checkName(
      name: String,
      path: Vector[String],
      diagnostics: scala.collection.mutable.Builder[Diagnostic, Vector[Diagnostic]]
  ): Unit =
    if (ReservedWords.contains(name)) {
      diagnostics += Diagnostic(
        "V2001-RESERVED-IDENTIFIER",
        path,
        s"Identifier '$name' is reserved by IEEE 1364-2001"
      )
    }

  private def checkInteger(
      value: BigInt,
      path: Vector[String],
      diagnostics: scala.collection.mutable.Builder[Diagnostic, Vector[Diagnostic]]
  ): Unit =
    if (value < MinimumInteger || value > MaximumInteger) {
      diagnostics += Diagnostic(
        "V2001-INTEGER-OUT-OF-RANGE",
        path,
        s"Integer $value is outside the portable signed 32-bit Verilog integer range"
      )
    }

  private def checkIntegerDomain(
      parameter: IntegerParameter,
      path: Vector[String],
      diagnostics: scala.collection.mutable.Builder[Diagnostic, Vector[Diagnostic]]
  ): Unit = {
    val minimums = parameter.constraints.collect { case MinInclusive(value) => value }
    val maximums = parameter.constraints.collect { case MaxInclusive(value) => value }
    val lower = if (minimums.isEmpty) None else Some(minimums.max)
    val upper = if (maximums.isEmpty) None else Some(maximums.min)

    if (!lower.exists(_ >= MinimumInteger) || !upper.exists(_ <= MaximumInteger)) {
      diagnostics += Diagnostic(
        "V2001-INTEGER-DOMAIN-OUT-OF-RANGE",
        path,
        s"Legal domain of parameter '${parameter.name}' must be bounded within the signed 32-bit Verilog integer range"
      )
    }
  }

  private def checkExpression(
      expression: IntExpr,
      parameters: Map[String, IntExprFacts],
      localParameters: Map[String, IntExprFacts],
      path: Vector[String],
      diagnostics: scala.collection.mutable.Builder[Diagnostic, Vector[Diagnostic]],
      generateIndices: Map[String, IntExprFacts] = Map.empty,
      booleanParameters: Map[String, BooleanParameter] = Map.empty,
      booleanLocalParameters: Map[String, Boolean] = Map.empty
  ): Unit = {
    final case class Work(value: IntExpr, path: Vector[String])
    val work = scala.collection.mutable.ArrayBuffer(Work(expression, path))

    def pushBinary(left: IntExpr, right: IntExpr, currentPath: Vector[String]): Unit = {
      work += Work(right, currentPath :+ "right")
      work += Work(left, currentPath :+ "left")
    }

    while (work.nonEmpty) {
      val current = work.remove(work.length - 1)
      IntExpressionAnalysis
        .analyze(
          current.value,
          parameters,
          localParameters,
          booleanParameters,
          generateIndices,
          booleanLocalParameters
        )
        .toOption
        .foreach { facts =>
          val interval = facts.interval
          if (
            interval.lower.isEmpty ||
            interval.upper.isEmpty ||
            interval.lower.exists(_ < MinimumInteger) ||
            interval.upper.exists(_ > MaximumInteger)
          ) {
            val renderedDomain = (interval.lower, interval.upper) match {
              case (Some(lower), Some(upper)) => s"[$lower, $upper]"
              case _                          => "unbounded"
            }
            diagnostics += Diagnostic(
              "V2001-INTEGER-EXPRESSION-OUT-OF-RANGE",
              current.path,
              s"Integer expression domain $renderedDomain is outside the portable signed 32-bit Verilog integer range"
            )
          }
        }

      current.value match {
        case Literal(_) | ParameterRef(_) | LocalParameterRef(_) | GenerateIndexRef(_) =>
        case AddressWidth(value) => work += Work(value, current.path :+ "operand")
        case CeilLog2(value)     => work += Work(value, current.path :+ "operand")
        case Negate(value) => work += Work(value, current.path :+ "operand")
        case Add(left, right)      => pushBinary(left, right, current.path)
        case Subtract(left, right) => pushBinary(left, right, current.path)
        case Multiply(left, right) => pushBinary(left, right, current.path)
        case Divide(left, right)   => pushBinary(left, right, current.path)
        case Modulo(left, right)   => pushBinary(left, right, current.path)
        case extremum @ Min(left, right) =>
          if (
            !Verilog2001IntExpressionLowering.expansionWithin(
              extremum,
              Verilog2001IntExpressionLowering.MaximumExpansionNodes
            )
          )
            diagnostics += Diagnostic(
              "V2001-MIN-MAX-EXPANSION-TOO-LARGE",
              current.path,
              s"Portable min/max lowering exceeds the ${Verilog2001IntExpressionLowering.MaximumExpansionNodes}-node expansion limit"
            )
          else pushBinary(left, right, current.path)
        case extremum @ Max(left, right) =>
          if (
            !Verilog2001IntExpressionLowering.expansionWithin(
              extremum,
              Verilog2001IntExpressionLowering.MaximumExpansionNodes
            )
          )
            diagnostics += Diagnostic(
              "V2001-MIN-MAX-EXPANSION-TOO-LARGE",
              current.path,
              s"Portable min/max lowering exceeds the ${Verilog2001IntExpressionLowering.MaximumExpansionNodes}-node expansion limit"
            )
          else pushBinary(left, right, current.path)
        case Select(condition, whenTrue, whenFalse) =>
          checkBooleanExpression(
            condition,
            booleanParameters,
            parameters,
            localParameters,
            current.path :+ "condition",
            diagnostics,
            generateIndices,
            booleanLocalParameters
          )
          work += Work(whenFalse, current.path :+ "whenFalse")
          work += Work(whenTrue, current.path :+ "whenTrue")
      }
    }
  }

  private def generateIfSortKey(generate: GenerateIf): (String, String) =
    generate.whenTrue.label -> generate.whenFalse.label

  private def generateCaseSortKey(generate: GenerateCase): (String, String) =
    generate.default.label -> generate.choices
      .sortBy(choice => (choice.value, choice.block.label))
      .map(choice => s"${choice.value}:${choice.block.label}")
      .mkString("|")
}
