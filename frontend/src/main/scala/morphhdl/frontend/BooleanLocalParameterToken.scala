package morphhdl.frontend

import morphhdl.paramrtl.BooleanLocalParameter

/** Identity-bearing declaration provenance for one module-local Boolean parameter. */
private[frontend] final class BooleanLocalParameterToken(
    val declaration: BooleanLocalParameter,
    val parameters: Set[ParameterToken],
    val booleanParameters: Set[BooleanParameterToken],
    val integerDependencies: Set[LocalParameterToken],
    val dependencies: Set[BooleanLocalParameterToken],
    val origin: SourceOrigin
) extends ModuleLocalParameterToken {
  private var owner: Option[LocalParameterOwner] = None

  override val parameterName: String = declaration.name
  override def allDependencies: Set[ModuleLocalParameterToken] =
    integerDependencies.map(identity[ModuleLocalParameterToken]) ++
      dependencies.map(identity[ModuleLocalParameterToken])

  private[frontend] def claimedBy: Option[LocalParameterOwner] = owner

  private[frontend] def claim(value: LocalParameterOwner): Unit = owner = Some(value)
}
