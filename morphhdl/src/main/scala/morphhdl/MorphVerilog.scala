package morphhdl

import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths, StandardCopyOption, StandardOpenOption}

import scala.collection.JavaConverters._
import scala.util.control.NonFatal

import spinal.core.{Component, SpinalConfig, SpinalReport, SpinalVerilog, SystemVerilog, VHDL, Verilog}

import morphhdl.backend.verilog2001.{Verilog2001Capability => V2001Capability, Verilog2001Emitter}
import morphhdl.frontend.ParamRtlFrontend
import morphhdl.paramrtl.ModuleItem.{GenerateFor, GenerateIf, ModuleInstance}
import morphhdl.paramrtl.{
  BoolExpressionAnalysis,
  Design,
  DiagnosticSet,
  IntExpressionAnalysis,
  ParamRtlValidator,
  ValidatedDesign
}
import morphhdl.MorphVerilogStage._

object MorphVerilog {
  private final case class PreparedGeneration(
      design: Design,
      inheritedValidationPhaseIds: Vector[String],
      verilog: String
  )

  private final case class PortShape(
      name: String,
      direction: String,
      width: BigInt,
      signedness: String
  )

  private final case class ModuleShape(
      topName: Option[String],
      ports: Vector[PortShape],
      children: Vector[ChildShape]
  )

  private final case class ChildShape(shape: ModuleShape, count: BigInt)

  def apply[T <: Component](program: => MorphProgram[T]): MorphVerilogReport =
    apply(SpinalConfig())(program)

  def apply[T <: Component](
      config: SpinalConfig
  )(program: => MorphProgram[T]): MorphVerilogReport =
    tryGenerate(config)(program) match {
      case Right(report) => report
      case Left(failure) => throw new MorphVerilogException(failure)
    }

  def tryGenerate[T <: Component](
      config: SpinalConfig
  )(program: => MorphProgram[T]): Either[MorphVerilogFailure, MorphVerilogReport] = {
    val checkedConfig = validateConfig(config)
    checkedConfig match {
      case Left(failure) => Left(failure)
      case Right(_) =>
        evaluateProgram(program) match {
          case Left(failure) => Left(failure)
          case Right(value)   => run(config, value)
        }
    }
  }

  private def run[T <: Component](
      config: SpinalConfig,
      program: MorphProgram[T]
  ): Either[MorphVerilogFailure, MorphVerilogReport] =
    createWitnessDirectory() match {
      case Left(failure) => Left(failure)
      case Right(witnessDirectory) =>
        val prepared = prepare(config, program, witnessDirectory)
        cleanupWitnessDirectory(witnessDirectory) match {
          case Left(cleanupFailure) =>
            prepared match {
              case Left(original) => Left(appendCleanupFailure(original, cleanupFailure))
              case Right(_)       => Left(cleanupFailure)
            }
          case Right(_) =>
            prepared match {
              case Left(failure) => Left(failure)
              case Right(value) =>
                writeOutput(config, value.design.top, value.verilog) match {
                  case Left(failure) => Left(failure)
                  case Right(output) =>
                    Right(
                      MorphVerilogReport(
                        toplevelName = value.design.top,
                        generatedSourcesPaths = Vector(output.toString),
                        parameterizedDesign = value.design,
                        inheritedValidationPhaseIds = value.inheritedValidationPhaseIds
                      )
                    )
                }
            }
        }
    }

  private def prepare[T <: Component](
      config: SpinalConfig,
      program: MorphProgram[T],
      witnessDirectory: Path
  ): Either[MorphVerilogFailure, PreparedGeneration] =
    for {
      concreteReport <- runConcrete(config, program, witnessDirectory)
      phaseIds <- checkPhasePlan(concreteReport)
      design <- captureSymbolic(program)
      validated <- validateSymbolic(design)
      capable <- verifyCapability(validated)
      _ <- checkDefaultShape(concreteReport, capable)
      verilog <- renderVerilog(capable)
    } yield PreparedGeneration(design, phaseIds, verilog)

  private def evaluateProgram[T <: Component](
      program: => MorphProgram[T]
  ): Either[MorphVerilogFailure, MorphProgram[T]] =
    try {
      val value = program
      if (value == null) {
        Left(MorphVerilogFailure(ProgramConstruction, "program factory returned null"))
      } else Right(value)
    } catch {
      case NonFatal(error) =>
        Left(MorphVerilogFailure(ProgramConstruction, errorMessage(error), cause = Some(error)))
    }

  private def runConcrete[T <: Component](
      config: SpinalConfig,
      program: MorphProgram[T],
      witnessDirectory: Path
  ): Either[MorphVerilogFailure, SpinalReport[T]] =
    try {
      val witnessConfig = copyForWitness(config, witnessDirectory)
      val report = SpinalVerilog(witnessConfig) {
        // Spinal constructs the Component on its elaboration worker. Enter the
        // concrete frontend session on that worker so ThreadLocal capture stays
        // fail-closed across every other thread boundary.
        ParamRtlFrontend.concrete {
          program.concreteWitnessFactory()
        }
      }
      Right(report)
    } catch {
      case NonFatal(error) =>
        Left(MorphVerilogFailure(ConcreteWitness, errorMessage(error), cause = Some(error)))
    }

  private def checkPhasePlan[T <: Component](
      report: SpinalReport[T]
  ): Either[MorphVerilogFailure, Vector[String]] = {
    val expected = report.expectedInheritedValidationPhaseIds
    val actual = report.inheritedValidationPhaseIds
    if (expected.isEmpty) {
      Left(MorphVerilogFailure(PhasePlanParity, "the shared inherited phase plan exposed no validation IDs"))
    } else if (actual != expected) {
      Left(
        MorphVerilogFailure(
          PhasePlanParity,
          s"inherited phase plan drifted; expected [${expected.mkString(", ")}], " +
            s"observed [${actual.mkString(", ")}]"
        )
      )
    } else if (actual.distinct.size != actual.size) {
      Left(MorphVerilogFailure(PhasePlanParity, "the inherited phase plan contains duplicate validation IDs"))
    } else Right(actual)
  }

  private def captureSymbolic[T <: Component](
      program: MorphProgram[T]
  ): Either[MorphVerilogFailure, Design] =
    try {
      val design = program.parameterizedDesignFactory()
      if (design == null) Left(MorphVerilogFailure(SymbolicCapture, "symbolic factory returned null"))
      else Right(design)
    } catch {
      case NonFatal(error) =>
        Left(MorphVerilogFailure(SymbolicCapture, errorMessage(error), cause = Some(error)))
    }

  private def validateSymbolic(
      design: Design
  ): Either[MorphVerilogFailure, ValidatedDesign] =
    try {
      ParamRtlValidator.validate(design) match {
        case Right(validated) => Right(validated)
        case Left(diagnostics) => Left(diagnosticFailure(ParamRtlValidation, diagnostics))
      }
    } catch {
      case NonFatal(error) =>
        Left(MorphVerilogFailure(ParamRtlValidation, errorMessage(error), cause = Some(error)))
    }

  private def verifyCapability(
      design: ValidatedDesign
  ): Either[MorphVerilogFailure, ValidatedDesign] =
    try {
      V2001Capability.verify(design) match {
        case Right(capable) => Right(capable)
        case Left(diagnostics) => Left(diagnosticFailure(Verilog2001Capability, diagnostics))
      }
    } catch {
      case NonFatal(error) =>
        Left(MorphVerilogFailure(Verilog2001Capability, errorMessage(error), cause = Some(error)))
    }

  private def checkDefaultShape[T <: Component](
      concrete: SpinalReport[T],
      symbolic: ValidatedDesign
  ): Either[MorphVerilogFailure, Unit] =
    try {
      val symbolicDesign = symbolic.value
      val concreteTop = concrete.toplevelName
      if (concreteTop != symbolicDesign.top) {
        Left(
          MorphVerilogFailure(
            DefaultShapeAgreement,
            s"concrete top '$concreteTop' does not match symbolic top '${symbolicDesign.top}'"
          )
        )
      } else {
        symbolicDesign.modules.find(_.name == symbolicDesign.top) match {
          case None =>
            Left(
              MorphVerilogFailure(
                DefaultShapeAgreement,
                s"validated symbolic top '${symbolicDesign.top}' is unavailable"
              )
            )
          case Some(symbolicTop) =>
            val concreteModules = concreteModuleShape(concrete.toplevel)
            symbolicModuleShape(symbolicTop.name, symbolic) match {
              case Left(detail) =>
                Left(MorphVerilogFailure(DefaultShapeAgreement, detail))
              case Right(symbolicModules) if concreteModules != symbolicModules =>
                Left(
                  MorphVerilogFailure(
                    DefaultShapeAgreement,
                    s"default reachable module schemas differ; concrete ${renderModuleShapes(concreteModules)}, " +
                      s"symbolic ${renderModuleShapes(symbolicModules)}"
                  )
                )
              case Right(_) => Right(())
            }
        }
      }
    } catch {
      case NonFatal(error) =>
        Left(MorphVerilogFailure(DefaultShapeAgreement, errorMessage(error), cause = Some(error)))
    }

  private def validateConfig(config: SpinalConfig): Either[MorphVerilogFailure, Unit] = {
    try {
      if (config == null) {
        Left(MorphVerilogFailure(Configuration, "SpinalConfig must not be null"))
      } else {
        val errors = Vector.newBuilder[String]
        config.mode match {
          case null | Verilog =>
          case VHDL           => errors += "VHDL mode is not supported by MorphVerilog"
          case SystemVerilog  => errors += "SystemVerilog mode is not supported by MorphVerilog"
          case other          => errors += s"unsupported Spinal mode: $other"
        }
        if (config.targetDirectory == null) {
          errors += "targetDirectory must not be null"
        }
        Option(config.netlistFileName).foreach { filename =>
          validateNetlistFilename(filename).foreach(errors += _)
        }
        if (config.oneFilePerComponent) {
          errors += "oneFilePerComponent is incompatible with the single parameterized hierarchy"
        }
        if (config.svInterface) {
          errors += "svInterface is a SystemVerilog-only option"
        }
        if (config.verbose) {
          errors += "verbose is unsupported because inherited verbose.log is not isolated"
        }
        if (config.rtlHeader != null) {
          errors += "rtlHeader is not supported by the direct parameterized emitter"
        }
        if (!config.withTimescale) {
          errors += "withTimescale changes are not supported by the direct parameterized emitter"
        }
        if (config.headerWithDate) {
          errors += "headerWithDate is not supported by the deterministic parameterized emitter"
        }
        if (!config.headerWithRepoHash) {
          errors += "headerWithRepoHash changes are not supported by the direct parameterized emitter"
        }
        if (!config.printFilelist) {
          errors += "printFilelist changes are not supported by the direct parameterized emitter"
        }
        if (config.globalPrefix.nonEmpty) {
          errors += "globalPrefix is not supported by the direct parameterized emitter"
        }
        if (config.obfuscateNames || config.obfuscate != spinal.core.ObfuscateConfig()) {
          errors += "name obfuscation is not supported by the direct parameterized emitter"
        }
        if (config.genLineComments) {
          errors += "genLineComments is not supported by the direct parameterized emitter"
        }
        if (config.privateNamespace) {
          errors += "privateNamespace is not supported by the direct parameterized emitter"
        }
        if (!config.cutLongExpressions) {
          errors += "cutLongExpressions changes are not supported by the direct parameterized emitter"
        }
        if (!config.emitFullComponentBindings) {
          errors += "emitFullComponentBindings changes are not supported by the direct parameterized emitter"
        }
        if (config.keepAll) {
          errors += "keepAll is not supported by the direct parameterized emitter"
        }
        if (config.flags.nonEmpty) {
          errors += "generation flags are not supported by the direct parameterized emitter"
        }
        if (
          config.formalAsserts || config.noAssert || config.noAssertAtTimeZero ||
          config.reportIncludeSourceLocation
        ) {
          errors += "assertion-output changes are not supported by the direct parameterized emitter"
        }
        if (config.anonymSignalPrefix != null || config.anonymSignalUniqueness) {
          errors += "anonymous-signal naming changes are not supported by the direct parameterized emitter"
        }
        if (config.inlineRom || config.caseRom || config.romReuse) {
          errors += "ROM emission changes are not supported by the direct parameterized emitter"
        }
        if (config.inlineConditionalExpression) {
          errors += "inlineConditionalExpression is not supported by the direct parameterized emitter"
        }
        if (config.mergeAsyncProcess || !config.mergeSyncProcess || config.asyncResetCombSensitivity) {
          errors += "process-emission changes are not supported by the direct parameterized emitter"
        }
        if (!config.nameWhenByFile) {
          errors += "nameWhenByFile changes are not supported by the direct parameterized emitter"
        }
        if (config.noRandBoot || !config.randBootFixValue) {
          errors += "random-boot emission changes are not supported by the direct parameterized emitter"
        }
        if (!config.fixToWithWrap || config.removePruned || config.allowOutOfRangeLiterals) {
          errors += "legacy Verilog lowering changes are not supported by the direct parameterized emitter"
        }
        if (config.dontCareGenAsZero || config.normalizeComponentClockDomainName) {
          errors += "signal-lowering changes are not supported by the direct parameterized emitter"
        }
        if (!config.enumPrefixEnable || config.enumGlobalEnable) {
          errors += "enum naming changes are not supported by the direct parameterized emitter"
        }
        if (!config.singleTopLevel) {
          errors += "singleTopLevel changes are not supported by the direct parameterized emitter"
        }
        if (config.dumpWave != null) {
          errors += "dumpWave is not supported by the direct parameterized emitter"
        }
        val result = errors.result()
        if (result.isEmpty) Right(())
        else Left(MorphVerilogFailure(Configuration, result.mkString("; ")))
      }
    } catch {
      case NonFatal(error) =>
        Left(MorphVerilogFailure(Configuration, errorMessage(error), cause = Some(error)))
    }
  }

  private def writeOutput(
      config: SpinalConfig,
      top: String,
      verilog: String
  ): Either[MorphVerilogFailure, Path] =
    try {
      val directory = targetDirectory(config.targetDirectory)
      val filename = Option(config.netlistFileName).getOrElse(s"$top.v")
      Files.createDirectories(directory)
      val output = directory.resolve(filename)
      val temporary = Files.createTempFile(directory, s".${filename}.", ".tmp")
      var committed = false
      try {
        Files.write(
          temporary,
          verilog.getBytes(StandardCharsets.UTF_8),
          StandardOpenOption.TRUNCATE_EXISTING,
          StandardOpenOption.WRITE
        )
        // Do not degrade to a non-atomic replacement: on a filesystem without
        // atomic same-directory moves, the previous public artifact must stay
        // intact and generation fails closed.
        Files.move(
          temporary,
          output,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING
        )
        committed = true
        Right(output)
      } finally {
        if (!committed) deleteIfExistsBestEffort(temporary)
      }
    } catch {
      case NonFatal(error) =>
        Left(MorphVerilogFailure(OutputWrite, errorMessage(error), cause = Some(error)))
    }

  private def createWitnessDirectory(): Either[MorphVerilogFailure, Path] =
    try Right(Files.createTempDirectory("morphhdl-concrete-witness-"))
    catch {
      case NonFatal(error) =>
        Left(MorphVerilogFailure(TemporaryWorkspace, errorMessage(error), cause = Some(error)))
    }

  private def cleanupWitnessDirectory(root: Path): Either[MorphVerilogFailure, Unit] =
    try {
      deleteTree(root)
      Right(())
    } catch {
      case NonFatal(error) =>
        Left(MorphVerilogFailure(WitnessCleanup, errorMessage(error), cause = Some(error)))
    }

  private def appendCleanupFailure(
      original: MorphVerilogFailure,
      cleanup: MorphVerilogFailure
  ): MorphVerilogFailure = {
    for {
      originalCause <- original.cause
      cleanupCause <- cleanup.cause
    } originalCause.addSuppressed(cleanupCause)
    original.copy(detail = s"${original.detail}; witness cleanup also failed: ${cleanup.detail}")
  }

  private def copyForWitness(config: SpinalConfig, witnessDirectory: Path): SpinalConfig =
    config.copy(
      mode = Verilog,
      flags = config.flags.clone(),
      debugComponents = config.debugComponents.clone(),
      targetDirectory = witnessDirectory.toString,
      netlistFileName = null,
      phasesInserters = config.phasesInserters.clone(),
      transformationPhases = config.transformationPhases.clone(),
      memBlackBoxers = config.memBlackBoxers.clone(),
      scopeProperties = config.scopeProperties.clone()
    )

  private def validateNetlistFilename(filename: String): Option[String] = {
    val path = Paths.get(filename)
    val invalid =
      filename.trim.isEmpty || filename.contains('/') || filename.contains('\\') ||
        path.isAbsolute || path.getNameCount != 1 || path.getFileName.toString != filename ||
        !filename.endsWith(".v")
    if (invalid)
      Some(s"netlistFileName must be one relative .v filename, received '$filename'")
    else None
  }

  private def renderVerilog(design: ValidatedDesign): Either[MorphVerilogFailure, String] =
    try Right(Verilog2001Emitter.renderVerified(design))
    catch {
      case NonFatal(error) =>
        Left(MorphVerilogFailure(Verilog2001Rendering, errorMessage(error), cause = Some(error)))
    }

  private def concreteModuleShape(top: Component): ModuleShape = {
    def loop(component: Component, isTop: Boolean): ModuleShape = {
      val ports = component.getAllIo.toVector.map { port =>
        PortShape(
          name = port.getName(),
          direction =
            if (port.isInput) "input"
            else if (port.isOutput) "output"
            else if (port.isInOut) "inout"
            else "directionless",
          width = BigInt(port.getBitsWidth),
          signedness = port match {
            case _: spinal.core.SInt => "signed"
            case _                   => "unsigned"
          }
        )
      }.sortBy(_.name)
      val children = component.children.toVector
        .map(child => loop(child, isTop = false))
        .groupBy(identity)
        .map { case (shape, values) => ChildShape(shape, BigInt(values.size)) }
        .toVector
        .sortBy(child => renderModuleShape(child.shape))
      ModuleShape(if (isTop) Some(component.definitionName) else None, ports, children)
    }
    loop(top, isTop = true)
  }

  private def symbolicModuleShape(
      topName: String,
      design: ValidatedDesign
  ): Either[String, ModuleShape] = {
    val modulesByName = design.value.modules.map(module => module.name -> module).toMap

    def loop(
        name: String,
        parameters: Map[String, morphhdl.paramrtl.IntExprFacts],
        localParameters: Map[String, morphhdl.paramrtl.IntExprFacts],
        ancestors: Set[String],
        isTop: Boolean
    ): Either[String, ModuleShape] = {
      if (ancestors(name)) Left(s"symbolic default hierarchy contains a recursive module '$name'")
      else
        modulesByName.get(name) match {
          case None => Left(s"symbolic default hierarchy cannot resolve module '$name'")
          case Some(module) =>
            for {
              ports <- symbolicPortShapes(module, parameters, localParameters)
              childEntries <- symbolicChildren(module, parameters, localParameters)
              childShapes <- childEntries.foldLeft[Either[String, Vector[ChildShape]]](Right(Vector.empty)) {
                case (Left(detail), _) => Left(detail)
                case (Right(shapes), (instance, count)) =>
                  for {
                    childContext <- instantiatedContext(instance, parameters, localParameters, design)
                    child <- loop(
                      instance.moduleName,
                      childContext._1,
                      childContext._2,
                      ancestors + name,
                      isTop = false
                    )
                  } yield shapes :+ ChildShape(child, count)
              }
            } yield {
              val grouped = childShapes
                .groupBy(_.shape)
                .map { case (shape, values) => ChildShape(shape, values.map(_.count).sum) }
                .toVector
                .sortBy(child => renderModuleShape(child.shape))
              ModuleShape(if (isTop) Some(name) else None, ports, grouped)
            }
        }
    }

    val topFacts = design.moduleFacts(topName)
    loop(topName, topFacts.parameterFacts, topFacts.localParameterFacts, Set.empty, isTop = true)
  }

  private def symbolicPortShapes(
      module: morphhdl.paramrtl.ModuleDef,
      parameters: Map[String, morphhdl.paramrtl.IntExprFacts],
      localParameters: Map[String, morphhdl.paramrtl.IntExprFacts]
  ): Either[String, Vector[PortShape]] = {
    module.ports.foldLeft[Either[String, Vector[PortShape]]](Right(Vector.empty)) {
      case (Left(detail), _) => Left(detail)
      case (Right(shapes), port) =>
        IntExpressionAnalysis
          .analyze(port.dataType.width, parameters, localParameters) match {
          case Left(failure) =>
            Left(s"cannot evaluate default width for '${module.name}.${port.name}': $failure")
          case Right(widthFacts) =>
            val direction = port.direction match {
              case morphhdl.paramrtl.PortDirection.Input  => "input"
              case morphhdl.paramrtl.PortDirection.Output => "output"
            }
            val signedness = port.dataType.signedness match {
              case morphhdl.paramrtl.Signedness.Signed   => "signed"
              case morphhdl.paramrtl.Signedness.Unsigned => "unsigned"
            }
            Right(
              shapes :+ PortShape(
                port.name,
                direction,
                widthFacts.defaultValue,
                signedness
              )
            )
        }
    }.map(_.sortBy(_.name))
  }

  private def symbolicChildren(
      module: morphhdl.paramrtl.ModuleDef,
      parameters: Map[String, morphhdl.paramrtl.IntExprFacts],
      localParameters: Map[String, morphhdl.paramrtl.IntExprFacts]
  ): Either[String, Vector[(ModuleInstance, BigInt)]] = {
    val booleanParameters = module.booleanParameters.map(parameter => parameter.name -> parameter).toMap

    def collect(
        items: Vector[morphhdl.paramrtl.ModuleItem],
        multiplier: BigInt,
        entries: Vector[(ModuleInstance, BigInt)]
    ): Either[String, Vector[(ModuleInstance, BigInt)]] =
      items.foldLeft[Either[String, Vector[(ModuleInstance, BigInt)]]](Right(entries)) {
        case (Left(detail), _) => Left(detail)
        case (Right(current), instance: ModuleInstance) =>
          Right(current :+ (instance -> multiplier))
        case (Right(current), generate: GenerateFor) =>
          IntExpressionAnalysis
            .analyze(generate.count, parameters, localParameters) match {
            case Left(failure) =>
              Left(s"cannot evaluate default generate count '${module.name}.${generate.label}': $failure")
            case Right(countFacts) if countFacts.defaultValue < 0 =>
              Left(
                s"default generate count '${module.name}.${generate.label}' is negative: ${countFacts.defaultValue}"
              )
            case Right(countFacts) =>
              collect(generate.body, multiplier * countFacts.defaultValue, current)
          }
        case (Right(current), generate: GenerateIf) =>
          BoolExpressionAnalysis.evaluateDefault(generate.condition, booleanParameters) match {
            case Left(failure) =>
              Left(
                s"cannot evaluate default generate condition " +
                  s"'${module.name}.${generate.whenTrue.label}/${generate.whenFalse.label}': $failure"
              )
            case Right(true)  => collect(generate.whenTrue.body, multiplier, current)
            case Right(false) => collect(generate.whenFalse.body, multiplier, current)
          }
        case (Right(current), _) => Right(current)
      }

    collect(module.items, BigInt(1), Vector.empty).map(_.filter(_._2 > 0))
  }

  private def instantiatedContext(
      instance: ModuleInstance,
      parentParameters: Map[String, morphhdl.paramrtl.IntExprFacts],
      parentLocals: Map[String, morphhdl.paramrtl.IntExprFacts],
      design: ValidatedDesign
  ): Either[
    String,
    (Map[String, morphhdl.paramrtl.IntExprFacts], Map[String, morphhdl.paramrtl.IntExprFacts])
  ] = {
    val target = design.value.modules.find(_.name == instance.moduleName).get
    val bindings = instance.parameterBindings.map(binding => binding.parameterName -> binding.value).toMap
    val parameters = target.parameters.foldLeft[Either[String, Map[String, morphhdl.paramrtl.IntExprFacts]]](
      Right(Map.empty)
    ) {
      case (Left(detail), _) => Left(detail)
      case (Right(current), parameter) =>
        bindings.get(parameter.name) match {
          case None =>
            Right(
              current.updated(
                parameter.name,
                morphhdl.paramrtl.IntExprFacts(
                  parameter.default,
                  morphhdl.paramrtl.IntInterval.point(parameter.default)
                )
              )
            )
          case Some(expression) =>
            IntExpressionAnalysis.analyze(expression, parentParameters, parentLocals) match {
              case Left(failure) =>
                Left(
                  s"cannot evaluate default binding '${instance.name}.${parameter.name}': $failure"
                )
              case Right(value) => Right(current.updated(parameter.name, value))
            }
        }
    }
    parameters.flatMap { parameterFacts =>
      design.moduleFacts(target.name).orderedLocalParameters
        .foldLeft[Either[String, Map[String, morphhdl.paramrtl.IntExprFacts]]](Right(Map.empty)) {
          case (Left(detail), _) => Left(detail)
          case (Right(current), local) =>
            IntExpressionAnalysis.analyze(local.value, parameterFacts, current) match {
              case Left(failure) =>
                Left(s"cannot evaluate default local parameter '${target.name}.${local.name}': $failure")
              case Right(value) => Right(current.updated(local.name, value))
            }
        }
        .map(localFacts => parameterFacts -> localFacts)
    }
  }

  private def renderPortShapes(shapes: Vector[PortShape]): String =
    shapes
      .map(shape => s"${shape.name}:${shape.direction}:${shape.signedness}:${shape.width}")
      .mkString("[", ", ", "]")

  private def renderChildShapes(children: Vector[ChildShape]): String =
    children
      .map(child => s"${child.count}*${renderModuleShape(child.shape)}")
      .mkString("[", ", ", "]")

  private def renderModuleShape(shape: ModuleShape): String =
    s"${shape.topName.getOrElse("<instance>")}{ports=${renderPortShapes(shape.ports)},children=${renderChildShapes(shape.children)}}"

  private def renderModuleShapes(shape: ModuleShape): String = renderModuleShape(shape)

  private def targetDirectory(configured: String): Path = {
    val expanded =
      if (configured.startsWith("~")) System.getProperty("user.home") + configured.drop(1)
      else configured
    Paths.get(expanded)
  }

  private def diagnosticFailure(
      stage: MorphVerilogStage,
      diagnostics: DiagnosticSet
  ): MorphVerilogFailure =
    MorphVerilogFailure(
      stage,
      diagnostics.values.map(d => s"${d.code} at ${d.pathString}: ${d.message}").mkString("; "),
      diagnostics.values
    )

  private def errorMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getName)

  private def deleteIfExistsBestEffort(path: Path): Unit =
    try Files.deleteIfExists(path)
    catch { case NonFatal(_) => () }

  private def deleteTree(root: Path): Unit =
    if (Files.exists(root)) {
      val stream = Files.walk(root)
      try {
        stream.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach { path =>
          Files.deleteIfExists(path)
        }
      } finally stream.close()
    }
}
