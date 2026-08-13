package morphhdl.frontend

import morphhdl.paramrtl.IntegerLocalParameter

/** Common identity used to order and atomically claim both local-parameter kinds. */
private[frontend] trait LocalParameterIdentity {
  def name: String
  def origin: SourceOrigin
  def dependencies: Set[LocalParameterIdentity]
  private[frontend] def claimedBy: Option[LocalParameterOwner]
  private[frontend] def claim(value: LocalParameterOwner): Unit
}

/** Identity-bearing declaration provenance for one module-local parameter. */
private[frontend] final class LocalParameterToken(
    val declaration: IntegerLocalParameter,
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

/** Opaque identity for the one module boundary which consumes local declarations. */
private[frontend] final class LocalParameterOwner(
    val moduleName: String,
    val origin: SourceOrigin
)

private[frontend] object LocalParameterToken {
  def requireUnclaimed(tokens: Vector[LocalParameterToken]): Unit =
    LocalParameterIdentity.requireUnclaimed(tokens)

  /**
    * Claims a complete declaration set atomically. A token cannot silently be
    * recycled into another module, even when its name and expression match.
    */
  def claimAll(
      tokens: Vector[LocalParameterToken],
      owner: LocalParameterOwner
  ): Unit = LocalParameterIdentity.claimAll(tokens, owner)
}

private[frontend] object LocalParameterIdentity {
  def requireUnclaimed(tokens: Vector[LocalParameterIdentity]): Unit = synchronized {
    firstClaimed(tokens).foreach { case (token, existing) => failForeign(token, existing) }
  }

  /** Claims the complete mixed-kind declaration graph as one atomic operation. */
  def claimAll(
      tokens: Vector[LocalParameterIdentity],
      owner: LocalParameterOwner
  ): Unit = synchronized {
    firstClaimed(tokens).foreach { case (token, existing) => failForeign(token, existing) }
    tokens.foreach(_.claim(owner))
  }

  private def firstClaimed(
      tokens: Vector[LocalParameterIdentity]
  ): Option[(LocalParameterIdentity, LocalParameterOwner)] =
    tokens
      .flatMap(token => token.claimedBy.map(existing => token -> existing))
      .sortBy { case (token, _) => (token.name, token.origin.file, token.origin.line) }
      .headOption

  private def failForeign(token: LocalParameterIdentity, existing: LocalParameterOwner): Nothing =
    FrontendException.failAt(
      "MORPH-FRONTEND-LOCAL-PARAMETER-FOREIGN",
      s"local parameter '${token.name}' already belongs to module " +
        s"'${existing.moduleName}' declared at ${existing.origin.rendered}",
      token.origin
    )
}
