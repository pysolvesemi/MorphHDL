package morphhdl.frontend

import morphhdl.paramrtl.BooleanLocalParameter

/** Identity-bearing declaration provenance for one module-local Boolean parameter. */
private[frontend] final class BooleanLocalParameterToken(
    val declaration: BooleanLocalParameter,
    val parameters: Set[ParameterToken],
    val booleanParameters: Set[BooleanParameterToken],
    var dependencies: Set[LocalParameterIdentity],
    val origin: SourceOrigin
) extends LocalParameterIdentity {
  private var owner: Option[LocalParameterOwner] = None

  override val name: String = declaration.name

  private[frontend] def claimedBy: Option[LocalParameterOwner] = owner
  private[frontend] def claim(value: LocalParameterOwner): Unit = owner = Some(value)
}
