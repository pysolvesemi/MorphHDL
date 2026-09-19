package morphhdl

import spinal.core.Component

import morphhdl.paramrtl.Design

/**
  * Re-entrant factories for the two independent MorphHDL validation legs.
  *
  * Increment 7 keeps these factories explicit because the bounded frontend
  * does not yet lower an arbitrary Spinal Component into ParamRTL. Both
  * arguments are by-name so no Component is constructed outside Spinal's
  * elaboration context. MorphVerilog requires every reachable module instance
  * in their binding-aware default flat interface and hierarchy to agree before
  * publishing the symbolic artifact.
  */
@deprecated(
  "Dual-factory MorphProgram is a compatibility/mutation oracle; use typed single-source MorphVerilog",
  "Increment 58"
)
final class MorphProgram[T <: Component] private[morphhdl] (
    private[morphhdl] val concreteWitnessFactory: () => T,
    private[morphhdl] val parameterizedDesignFactory: () => Design
)

@deprecated(
  "Dual-factory MorphProgram is a compatibility/mutation oracle; use typed single-source MorphVerilog",
  "Increment 58"
)
object MorphProgram {
  def apply[T <: Component](
      concreteWitness: => T,
      parameterizedDesign: => Design
  ): MorphProgram[T] =
    new MorphProgram[T](() => concreteWitness, () => parameterizedDesign)
}
