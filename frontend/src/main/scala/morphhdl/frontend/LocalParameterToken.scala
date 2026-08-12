package morphhdl.frontend

import morphhdl.paramrtl.IntegerLocalParameter

/** Identity-bearing declaration provenance for one module-local parameter. */
private[frontend] final class LocalParameterToken(
    val declaration: IntegerLocalParameter,
    val parameters: Set[ParameterToken],
    val dependencies: Set[LocalParameterToken],
    val origin: SourceOrigin
) {
  private var owner: Option[LocalParameterOwner] = None

  private[frontend] def claimedBy: Option[LocalParameterOwner] = owner

  private[frontend] def claim(value: LocalParameterOwner): Unit = owner = Some(value)
}

/** Opaque identity for the one module boundary which consumes local declarations. */
private[frontend] final class LocalParameterOwner(
    val moduleName: String,
    val origin: SourceOrigin
)

private[frontend] object LocalParameterToken {
  def requireUnclaimed(tokens: Vector[LocalParameterToken]): Unit = synchronized {
    firstClaimed(tokens).foreach { case (token, existing) =>
      failForeign(token, existing)
    }
  }

  /**
    * Claims a complete declaration set atomically. A token cannot silently be
    * recycled into another module, even when its name and expression match.
    */
  def claimAll(
      tokens: Vector[LocalParameterToken],
      owner: LocalParameterOwner
  ): Unit = synchronized {
    firstClaimed(tokens).foreach { case (token, existing) => failForeign(token, existing) }

    tokens.foreach(_.claim(owner))
  }

  private def firstClaimed(
      tokens: Vector[LocalParameterToken]
  ): Option[(LocalParameterToken, LocalParameterOwner)] =
    tokens
      .flatMap(token => token.claimedBy.map(existing => token -> existing))
      .sortBy { case (token, _) => (token.declaration.name, token.origin.file, token.origin.line) }
      .headOption

  private def failForeign(token: LocalParameterToken, existing: LocalParameterOwner): Nothing =
    FrontendException.failAt(
      "MORPH-FRONTEND-LOCAL-PARAMETER-FOREIGN",
      s"local parameter '${token.declaration.name}' already belongs to module " +
        s"'${existing.moduleName}' declared at ${existing.origin.rendered}",
      token.origin
    )
}
