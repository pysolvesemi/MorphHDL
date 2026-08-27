package spinal.core.internals

import scala.collection.mutable.ArrayBuffer

import spinal.core.ParameterizedStructure

/**
  * Install the narrow native-graph sizing boundary required by retained
 * structural alternatives. Widths are inspected only after PhaseInferWidth,
 * and an accepted fixed UInt source is sized immediately before ordinary
 * SpinalHDL input normalization validates the concrete witness graph. Any
 * temporary non-literal resize is removed only after native validation and
 * immediately before Verilog emission.
  */
object ExternalParameterizedStructuralWitnessSizing {
  private final class PreparePhase extends PhaseNetlist {
    override def impl(pc: PhaseContext): Unit = {
      pc.walkComponents(
        ParameterizedStructure.prepareWitnessSizedAssignments
      )
    }
  }

  private final class RestorePhase extends PhaseNetlist {
    override def impl(pc: PhaseContext): Unit = {
      pc.walkComponents(
        ParameterizedStructure.restoreWitnessSizedAssignments
      )
    }
  }

  def install(phases: ArrayBuffer[Phase]): Unit = {
    if (phases == null)
      throw new IllegalArgumentException("native phase plan must not be null")
    val normalizations = phases.collect {
      case phase: PhaseNormalizeNodeInputs => phase
    }
    val emissions = phases.collect { case phase: PhaseVerilog => phase }
    if (
      normalizations.size != 1 ||
      emissions.size != 1
    ) {
      throw new IllegalStateException(
        "native phase plan must contain exactly one input-normalization and Verilog-emission boundary"
      )
    }
    val normalization = normalizations.head
    val emission = emissions.head
    val normalizationIndex = phases.indexWhere(_ eq normalization)
    val emissionIndex = phases.indexWhere(_ eq emission)
    if (
      normalizationIndex <= 0 ||
      emissionIndex <= normalizationIndex ||
      !phases
        .take(normalizationIndex)
        .exists(_.isInstanceOf[PhaseInferWidth])
    ) {
      throw new IllegalStateException(
        "native phase plan has no post-inference input-normalization and later Verilog-emission boundary"
      )
    }

    phases.insert(normalizationIndex, new PreparePhase)
    phases.insert(phases.indexWhere(_ eq emission), new RestorePhase)
  }
}
