package morphhdl.frontend

import morphhdl.paramrtl.IntegerLocalParameter

/** Common identity used to validate and atomically claim both local-parameter kinds. */
private[frontend] trait ModuleLocalParameterToken {
  def parameterName: String
  def origin: SourceOrigin
  def allDependencies: Set[ModuleLocalParameterToken]

  private[frontend] def claimedBy: Option[LocalParameterOwner]
  private[frontend] def claim(value: LocalParameterOwner): Unit
}

/** Identity-bearing declaration provenance for one module-local parameter. */
private[frontend] final class LocalParameterToken(
    val declaration: IntegerLocalParameter,
    val parameters: Set[ParameterToken],
    val booleanParameters: Set[BooleanParameterToken],
    val dependencies: Set[LocalParameterToken],
    val booleanDependencies: Set[BooleanLocalParameterToken],
    val origin: SourceOrigin
) extends ModuleLocalParameterToken {
  private var owner: Option[LocalParameterOwner] = None

  override val parameterName: String = declaration.name
  override def allDependencies: Set[ModuleLocalParameterToken] =
    dependencies.map(identity[ModuleLocalParameterToken]) ++
      booleanDependencies.map(identity[ModuleLocalParameterToken])

  private[frontend] def claimedBy: Option[LocalParameterOwner] = owner

  private[frontend] def claim(value: LocalParameterOwner): Unit = owner = Some(value)
}

/** Opaque identity for the one module boundary which consumes local declarations. */
private[frontend] final class LocalParameterOwner(
    val moduleName: String,
    val origin: SourceOrigin
)

private[frontend] object ModuleLocalParameterToken {
  def requireUnclaimed(tokens: Vector[ModuleLocalParameterToken]): Unit = synchronized {
    firstClaimed(tokens).foreach { case (token, existing) =>
      failForeign(token, existing)
    }
  }

  /**
    * Claims a complete declaration set atomically. A token cannot silently be
    * recycled into another module, even when its name and expression match.
    */
  def claimAll(
      tokens: Vector[ModuleLocalParameterToken],
      owner: LocalParameterOwner
  ): Unit = synchronized {
    firstClaimed(tokens).foreach { case (token, existing) => failForeign(token, existing) }

    tokens.foreach(_.claim(owner))
  }

  private def firstClaimed(
      tokens: Vector[ModuleLocalParameterToken]
  ): Option[(ModuleLocalParameterToken, LocalParameterOwner)] =
    tokens
      .flatMap(token => token.claimedBy.map(existing => token -> existing))
      .sortBy { case (token, _) => (token.parameterName, token.origin.file, token.origin.line) }
      .headOption

  private def failForeign(
      token: ModuleLocalParameterToken,
      existing: LocalParameterOwner
  ): Nothing =
    FrontendException.failAt(
      "MORPH-FRONTEND-LOCAL-PARAMETER-FOREIGN",
      s"local parameter '${token.parameterName}' already belongs to module " +
        s"'${existing.moduleName}' declared at ${existing.origin.rendered}",
      token.origin
    )
}
