package morphhdl.frontend

import morphhdl.paramrtl.BooleanParameter
import spinal.core.{ElaborationIntegerParameter, ElaborationIntegerParameterRoot}

/** Identity-bearing declaration provenance for one frontend Boolean parameter. */
private[frontend] final class BooleanParameterToken(
    val declaration: BooleanParameter,
    val origin: SourceOrigin
) {
  lazy val canonicalSchema: ElaborationIntegerParameter =
    ElaborationIntegerParameter(
      declaration.name,
      if (declaration.default) BigInt(1) else BigInt(0),
      minimum = BigInt(0),
      maximum = BigInt(1)
    )

  lazy val elaborationRoot: ElaborationIntegerParameterRoot =
    ElaborationIntegerParameterRoot.fresh(
      declaration.name,
      Some(origin.rendered)
    )
}
