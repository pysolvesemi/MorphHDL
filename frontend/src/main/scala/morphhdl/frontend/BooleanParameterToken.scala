package morphhdl.frontend

import morphhdl.paramrtl.BooleanParameter
import spinal.core.ElaborationIntegerParameterRoot

/** Identity-bearing declaration provenance for one frontend Boolean parameter. */
private[frontend] final class BooleanParameterToken(
    val declaration: BooleanParameter,
    val origin: SourceOrigin
) {
  lazy val elaborationRoot: ElaborationIntegerParameterRoot =
    ElaborationIntegerParameterRoot.fresh(
      declaration.name,
      Some(origin.rendered)
    )
}
