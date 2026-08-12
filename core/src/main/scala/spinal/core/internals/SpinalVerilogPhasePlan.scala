package spinal.core.internals

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import spinal.core._

private[internals] final case class SpinalVerilogPhasePlan[T <: Component](
    phases: ArrayBuffer[Phase],
    report: SpinalReport[T],
    prunedSignals: mutable.Set[BaseType],
    unusedSignals: mutable.Set[BaseType],
    counterRegister: Ref[Int],
    blackboxesSourcesPaths: mutable.LinkedHashSet[String]
) {
  def finalizeValidation(pc: PhaseContext): Unit = pc.checkGlobalData()
}

/**
  * Single construction point for the inherited Verilog phase plan.
  *
  * SpinalVerilog executes this plan directly. MorphVerilog invokes
  * SpinalVerilog for its concrete witness, so configuration hooks and every
  * inherited validation phase remain shared by construction.
  */
private[internals] object SpinalVerilogPhasePlan {
  def build[T <: Component](
      config: SpinalConfig,
      pc: PhaseContext
  )(gen: => T): SpinalVerilogPhasePlan[T] = {
    val prunedSignals = mutable.Set.empty[BaseType]
    val unusedSignals = mutable.Set.empty[BaseType]
    val counterRegister = Ref[Int](0)
    val blackboxesSourcesPaths = mutable.LinkedHashSet.empty[String]
    val report = new SpinalReport[T]()
    report.globalData = pc.globalData

    val phases = ArrayBuffer.empty[Phase]
    phases += new PhaseCreateComponent(gen)(pc)
    phases += new PhaseDummy(SpinalProgress("Checks and transforms"))
    phases ++= config.transformationPhases
    phases ++= config.memBlackBoxers
    phases += new PhaseDeviceSpecifics(pc)
    phases += new PhaseApplyIoDefault(pc)

    phases += new PhaseNameNodesByReflection(pc)
    phases += new PhaseCollectAndNameEnum(pc)

    phases += new PhaseCheckIoBundle()
    phases += new PhaseCheckHierarchy()
    phases += new PhaseAnalog()
    phases += new PhaseNextifyReg()
    phases += new PhaseRemoveUselessStuff(false, false)
    phases += new PhaseRemoveIntermediateUnnameds(true)

    phases += new PhasePullClockDomains(pc)

    phases += new PhaseInferEnumEncodings(pc, e => if (e == `native`) binarySequential else e)
    phases += new PhaseInferWidth(pc)
    phases += new PhaseNormalizeNodeInputs(pc)
    phases += new PhaseRemoveIntermediateUnnameds(false)
    phases += new PhaseSimplifyNodes(pc)

    phases += new PhaseCompletSwitchCases()
    phases += new PhaseRemoveUselessStuff(true, true)
    phases += new PhaseRemoveIntermediateUnnameds(false)

    phases += new PhaseCheck_noLatchNoOverride(pc)
    phases += new PhaseCheck_noRegisterAsLatch()
    phases += new PhaseCheckCombinationalLoops()
    phases += new PhaseCheckCrossClock()

    phases += new PhasePropagateNames(pc)
    phases += new PhaseObfuscate()
    phases += new PhaseAllocateNames(pc)
    phases += new PhaseDevice(pc)

    if (config.mode == SystemVerilog && config.svInterface) {
      phases += new PhaseInterface(pc)
    }

    phases += new PhaseGetInfoRTL(
      prunedSignals,
      unusedSignals,
      counterRegister,
      blackboxesSourcesPaths
    )(pc)
    phases += new PhaseDummy(SpinalProgress(s"Generate Verilog to ${config.targetDirectory}"))
    phases += new PhaseVerilog(pc, report)

    // Preserve the historic hook surface. Every built-in PhaseCheck is
    // inventoried automatically unless it explicitly opts out (the
    // information-only PhaseGetInfoRTL), while PhaseInferWidth explicitly opts
    // in despite being a transformation. Configured custom phases are not part
    // of the inherited baseline.
    val configuredPhases =
      config.transformationPhases.toVector ++ config.memBlackBoxers.toVector
    val inheritedValidationPhases = phases.filterNot { phase =>
      configuredPhases.exists(_ eq phase)
    }.flatMap { phase =>
      phase.inheritedValidationPhaseId.map(phase -> _)
    }.toVector
    report._expectedInheritedValidationPhaseIds =
      inheritedValidationPhases.map(_._2) :+ GlobalDataValidationId
    config.phasesInserters.foreach(_(phases))

    // Track the registered built-in phase objects by identity. Inserters may
    // add their own PhaseCheck implementations without changing the inherited
    // manifest. A fresh instance with a baseline ID is still included, so
    // removal, replacement, duplication or reordering of a built-in remains
    // visible to MorphVerilog.
    val inheritedIds = inheritedValidationPhases.map(_._2).toSet
    report._inheritedValidationPhaseIds = phases.flatMap { phase =>
      inheritedValidationPhases.collectFirst {
        case (registered, id) if registered eq phase => id
      }.orElse {
        phase.inheritedValidationPhaseId.filter(inheritedIds)
      }
    }.toVector :+ GlobalDataValidationId

    SpinalVerilogPhasePlan(
      phases,
      report,
      prunedSignals,
      unusedSignals,
      counterRegister,
      blackboxesSourcesPaths
    )
  }

  private val GlobalDataValidationId = "PhaseContext.checkGlobalData"
}
