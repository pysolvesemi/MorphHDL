package morphhdl

import morphhdl.paramrtl.Design

@deprecated(
  "Dual-factory reports are compatibility/mutation oracles; use MorphSingleSourceVerilogReport",
  "Increment 58"
)
final case class MorphVerilogReport(
    toplevelName: String,
    generatedSourcesPaths: Vector[String],
    parameterizedDesign: Design,
    inheritedValidationPhaseIds: Vector[String]
)
