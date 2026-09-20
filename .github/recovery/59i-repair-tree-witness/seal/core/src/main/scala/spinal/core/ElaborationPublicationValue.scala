package spinal.core

/** Consumer-specific publication authority; never grants a finite structural domain. */
private[spinal] object ElaborationPublicationValue {
  def projected(value: ElabInt, role: String, failureCode: String,
                requireProjectedExactExtrema: Boolean): ElaborationIntegerExpression = {
    if (ElaborationProductDomain.isRetained(value.expression)) {
      // Authenticate before projection, so a copied public summary cannot be laundered.
      ElaborationProductDomain.requireInteger(value.expression, role)
      val expression = value.projectedExpression(role)
      ElaborationProductDomain.requireInteger(expression, role)
      expression
    } else value.authoritativeProjectedExpression(role, failureCode, requireProjectedExactExtrema)
  }
}
