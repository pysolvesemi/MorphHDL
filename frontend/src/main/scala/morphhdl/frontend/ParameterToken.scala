package morphhdl.frontend

import morphhdl.paramrtl.IntegerParameter
import spinal.core.{ElaborationIntegerParameter, ElaborationIntegerParameterRoot}

/** Identity-bearing declaration provenance for one frontend public parameter. */
private[frontend] final class ParameterToken(
    val declaration: IntegerParameter,
    val origin: SourceOrigin,
    initialSchema: Option[ElaborationIntegerParameter] = None
) {
  if (initialSchema == null || initialSchema.exists(_ == null)) {
    FrontendException.failAt(
      "MORPH-FRONTEND-STRUCTURAL-PARAMETER-SCHEMA-NULL",
      s"parameter '${declaration.name}' requires a non-null canonical-schema option",
      origin
    )
  }

  private[this] var retainedSchema = initialSchema.orNull

  /** Reuse one schema JVM identity for every expression derived from this
    * declaration token. Exact-domain root binding must not depend on which
    * frontend conversion happened first.
    */
  def canonicalSchema(
      minimum: BigInt,
      maximum: BigInt
  ): ElaborationIntegerParameter = synchronized {
    if (retainedSchema eq null)
      retainedSchema = ElaborationIntegerParameter(
        declaration.name,
        declaration.default,
        minimum,
        maximum
      )
    else if (
      retainedSchema.name != declaration.name ||
      retainedSchema.default != declaration.default ||
      retainedSchema.minimum != minimum ||
      retainedSchema.maximum != maximum
    ) {
      FrontendException.failAt(
        "MORPH-FRONTEND-STRUCTURAL-PARAMETER-SCHEMA-CONFLICT",
        s"parameter '${declaration.name}' cannot replace its canonical schema",
        origin
      )
    }
    retainedSchema
  }

  lazy val elaborationRoot: ElaborationIntegerParameterRoot =
    ElaborationIntegerParameterRoot.fresh(
      declaration.name,
      Some(origin.rendered)
    )
}
