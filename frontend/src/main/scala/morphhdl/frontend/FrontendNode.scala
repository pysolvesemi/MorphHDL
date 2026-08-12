package morphhdl.frontend

/**
  * A ParamRTL value whose frontend provenance has not yet been discharged.
  *
  * The raw value is intentionally visible only to the frontend implementation;
  * the MorphHDL integration fixture can pass nodes between guarded helpers but
  * cannot forge or unwrap one.
  */
private[morphhdl] final class FrontendNode[A] private[frontend] (
    private[frontend] val raw: A,
    private[frontend] val parameters: Set[ParameterToken],
    private[frontend] val booleanParameters: Set[BooleanParameterToken],
    private[frontend] val localParameters: Set[LocalParameterToken],
    private[frontend] val scopes: Set[ScopeToken],
    private[frontend] val localDeclaration: Option[LocalParameterToken],
    private[frontend] val origin: SourceOrigin
) {
  private[frontend] def requireUsable(consumer: String): Unit =
    scopes.foreach(FrontendSession.requireActiveScope(_, consumer, origin))
}

private[frontend] object FrontendNode {
  def apply[A](
      raw: A,
      parameters: Set[ParameterToken] = Set.empty,
      booleanParameters: Set[BooleanParameterToken] = Set.empty,
      localParameters: Set[LocalParameterToken] = Set.empty,
      scopes: Set[ScopeToken] = Set.empty,
      localDeclaration: Option[LocalParameterToken] = None,
      origin: SourceOrigin
  ): FrontendNode[A] =
    new FrontendNode(
      raw,
      parameters,
      booleanParameters,
      localParameters,
      scopes,
      localDeclaration,
      origin
    )
}
