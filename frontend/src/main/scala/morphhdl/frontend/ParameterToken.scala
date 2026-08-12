package morphhdl.frontend

import morphhdl.paramrtl.IntegerParameter

/** Identity-bearing declaration provenance for one frontend public parameter. */
private[frontend] final class ParameterToken(
    val declaration: IntegerParameter,
    val origin: SourceOrigin
)
