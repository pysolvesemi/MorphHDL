package spinal.core.internals

import spinal.core._

/** Read-only native metadata access used by WIRE-TRUNC-01 receiver forwarding.
  *
  * Keep package-protected native fields behind a narrow fail-closed surface.
  * This helper grants no mutation or proof authority: it only exposes the same
  * preservation and captured symbolic-width facts that the receiver phase must
  * re-check before replaying its canonical low-projection certificate.
  */
object WireTruncationNativeAccess {
  def protectedCarrier(value: BaseType): Boolean =
    value != null && value.dontSimplify &&
      value.getTags().forall(_ eq noBackendCombMerge)

  def symbolicResizeTargetWidth(
      component: Component,
      target: BaseType
  ): Option[ElaborationIntegerExpression] =
    if (component == null || target == null) None
    else ExternalParameterizedNativeResize
      .targetWidthOf(component, target)
      .filter(_.parameters.nonEmpty)
}
