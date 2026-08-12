package morphhdl

import morphhdl.paramrtl.Design

final case class MorphVerilogReport(
    toplevelName: String,
    generatedSourcesPaths: Vector[String],
    parameterizedDesign: Design,
    inheritedValidationPhaseIds: Vector[String]
)
