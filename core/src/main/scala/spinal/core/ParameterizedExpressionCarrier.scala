package spinal.core

/** Compiler-owned packed geometry boundary retained through native lowering.
  *
  * Unlike `dontSimplifyIt`, this is not user preservation intent. An optional
  * expression optimizer may discharge it only after proving that substitution
  * retains the exact typed width and interpretation in every receiver. The
  * ordinary native simplifier must continue to retain the carrier. The marker
  * contains no native references and must never authorize dropping other tags
  * or an explicit `dontSimplifyIt` request on the same declaration.
  */
object ParameterizedExpressionCarrier {
  private object GeometryBoundary extends SpinalTag {
    override def canSymplifyHost: Boolean = false
    override def allowMultipleInstance: Boolean = false
  }

  private[spinal] def retain[T <: BaseType](value: T): T = {
    value.addTag(GeometryBoundary)
    value
  }

  /** Identity comparison keeps arbitrary user tags outside this contract. */
  def isGeometryBoundary(tag: SpinalTag): Boolean = tag eq GeometryBoundary
}
