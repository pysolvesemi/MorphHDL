package spinal.core.internals

/** Test-only inventory of the inherited Verilog validation phases, observed
  * exclusively through SpinalConfig's baseline phase-inserter API.
  */
private[internals] object ValidationPhaseInventory {
  private val phases = Vector(
    classOf[PhaseCheckIoBundle].getName -> "PhaseCheckIoBundle",
    classOf[PhaseCheckHierarchy].getName -> "PhaseCheckHierarchy",
    classOf[PhaseInferWidth].getName -> "PhaseInferWidth",
    classOf[PhaseCheck_noLatchNoOverride].getName -> "PhaseCheck_noLatchNoOverride",
    classOf[PhaseCheck_noRegisterAsLatch].getName -> "PhaseCheck_noRegisterAsLatch",
    classOf[PhaseCheckCombinationalLoops].getName -> "PhaseCheckCombinationalLoops",
    classOf[PhaseCheckCrossClock].getName -> "PhaseCheckCrossClock"
  )
  private val globalDataValidationId = "PhaseContext.checkGlobalData"
  private val byClassName = phases.toMap

  val expectedIds: Vector[String] = phases.map(_._2) :+ globalDataValidationId

  def idsOf(values: Iterable[Phase]): Vector[String] =
    values.iterator.flatMap(value => byClassName.get(value.getClass.getName)).toVector :+
      globalDataValidationId
}
