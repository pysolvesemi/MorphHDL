package spinal.core

/** Test-only bridge for exercising package-internal preflight diagnostics
  * without reopening native formalization capability construction in
  * production code.
  */
object ExternalNativeIntFormalizationTestAccess {
  def withDefinitionExpressionBoundary[A](
      expression: ElaborationIntegerExpression,
      callSite: String,
      role: String
  )(body: => A): A = {
    val token = ExternalNativeIntFormalizationToken.nativeWidth(
      expression,
      callSite,
      valueOrigin = callSite,
      role
    )
    ExternalNativeIntShadowRegistry.withDefinitionExpressionBoundary(
      expression,
      token
    )(body)
  }

  def attachComponentGeometry[C <: Component](
      parent: Component,
      component: C,
      geometry: Iterable[Data],
      binding: ExternalFormalParameterBinding,
      callSite: String
  ): C = {
    val definition =
      ElabInt.directParameter(binding.formal, Some(callSite)).expression
    val token = ExternalNativeIntFormalizationToken.componentGeometry(
      parent,
      binding.actual,
      definition,
      callSite,
      valueOrigin = callSite,
      role = "formalization preflight test"
    )
    ExternalNativeIntFormalizationRegistry.attachComponent(
      parent,
      component,
      geometry,
      binding,
      token
    )
  }

  def attachRegion[T <: Data](
      owner: Component,
      data: T,
      expression: ElaborationIntegerExpression,
      formalBinding: Option[ExternalFormalParameterBinding],
      callSite: String
  ): T = {
    val token = ExternalNativeIntFormalizationToken.region(
      owner,
      expression,
      callSite,
      valueOrigin = callSite,
      role = "formalization preflight test"
    )
    ExternalNativeIntFormalizationRegistry.attachRegion(
      owner,
      data,
      expression,
      token,
      formalBinding
    )
  }
}
