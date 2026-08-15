package morphhdl

import morphhdl.paramrtl.Diagnostic

sealed trait MorphVerilogStage extends Product with Serializable {
  def id: String
}

object MorphVerilogStage {
  case object ProgramConstruction extends MorphVerilogStage {
    override val id = "program-construction"
  }
  case object Configuration extends MorphVerilogStage {
    override val id = "configuration"
  }
  case object TemporaryWorkspace extends MorphVerilogStage {
    override val id = "temporary-workspace"
  }
  case object ConcreteWitness extends MorphVerilogStage {
    override val id = "concrete-witness"
  }
  case object SingleSourceGeneration extends MorphVerilogStage {
    override val id = "single-source-generation"
  }
  case object PhasePlanParity extends MorphVerilogStage {
    override val id = "phase-plan-parity"
  }
  case object SymbolicCapture extends MorphVerilogStage {
    override val id = "symbolic-capture"
  }
  case object ParamRtlValidation extends MorphVerilogStage {
    override val id = "paramrtl-validation"
  }
  case object Verilog2001Capability extends MorphVerilogStage {
    override val id = "verilog-2001-capability"
  }
  case object DefaultShapeAgreement extends MorphVerilogStage {
    override val id = "default-shape-agreement"
  }
  case object Verilog2001Rendering extends MorphVerilogStage {
    override val id = "verilog-2001-rendering"
  }
  case object WitnessCleanup extends MorphVerilogStage {
    override val id = "witness-cleanup"
  }
  case object SingleSourceCleanup extends MorphVerilogStage {
    override val id = "single-source-cleanup"
  }
  case object OutputWrite extends MorphVerilogStage {
    override val id = "output-write"
  }
}

final case class MorphVerilogFailure(
    stage: MorphVerilogStage,
    detail: String,
    diagnostics: Vector[Diagnostic] = Vector.empty,
    cause: Option[Throwable] = None
) {
  def message: String = s"MorphVerilog ${stage.id} failed: $detail"
}

final class MorphVerilogException(val failure: MorphVerilogFailure)
    extends RuntimeException(failure.message, failure.cause.orNull)
