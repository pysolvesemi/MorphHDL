package morphhdl

import morphhdl.paramrtl.IntegerParameter

/**
  * Result of parameterized Verilog generation from one ordinary SpinalHDL
  * Component factory.
  *
  * Unlike [[MorphVerilogReport]], this report deliberately has no ParamRTL
  * design: the native SpinalHDL graph is the single source that was validated
  * and emitted.
  */
final case class MorphSingleSourceVerilogReport(
    toplevelName: String,
    generatedSourcesPaths: Vector[String],
    parameters: Vector[IntegerParameter],
    inheritedValidationPhaseIds: Vector[String]
)
