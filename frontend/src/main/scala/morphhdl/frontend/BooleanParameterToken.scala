package morphhdl.frontend

import morphhdl.paramrtl.BooleanParameter

/** Identity-bearing declaration provenance for one frontend Boolean parameter. */
private[frontend] final class BooleanParameterToken(
    val declaration: BooleanParameter,
    val origin: SourceOrigin
)
