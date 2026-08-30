package morphhdl.frontend

import morphhdl.paramrtl.IntegerParameter
import spinal.core.ElaborationIntegerParameterRoot

/** Identity-bearing declaration provenance for one frontend public parameter. */
private[frontend] final class ParameterToken(
    val declaration: IntegerParameter,
    val origin: SourceOrigin
) {
  lazy val elaborationRoot: ElaborationIntegerParameterRoot =
    ElaborationIntegerParameterRoot.fresh(
      declaration.name,
      Some(origin.rendered)
    )
}
