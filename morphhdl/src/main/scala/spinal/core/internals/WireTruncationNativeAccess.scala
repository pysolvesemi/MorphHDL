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

  /** Return the exact fixed source declaration behind a captured symbolic low
    * projection when that native-resize owner is still publication-valid. The
    * owner may remain fresh (for example because a process use retains it) or
    * may already have completed the first WIRE consumption handoff. No new
    * mutation/proof authority is created here.
    */
  def lowBitTruncationSource(
      component: Component,
      target: BaseType
  ): Option[BaseType] =
    if (component == null || target == null) None
    else ExternalParameterizedNativeResize
      .lowBitTruncationSourceOf(component, target)
      .filter(value => value != null && (value.component eq component))

  /** Compare two symbolic declaration widths in their actual native owners.
    * Raw expression equality is intentionally insufficient here: two distinct
    * declarations can own the same HDL parameter expression while carrying
    * different native owner identities. Publication already has the canonical
    * cross-owner proof; expose only that read-only predicate to WIRE-TRUNC.
    */
  def equivalentSymbolicWidth(
      component: Component,
      left: BaseType,
      right: BaseType
  ): Boolean = {
    if (component == null || left == null || right == null ||
        (left.component ne component) || (right.component ne component)) false
    else {
      (for {
        leftWidth <- NativeWidthProvenance.optionalWidthOf(left)
        rightWidth <- NativeWidthProvenance.optionalWidthOf(right)
        if leftWidth.parameters.nonEmpty && rightWidth.parameters.nonEmpty
      } yield NativePublicationWidth.equivalentAtOwners(
        leftWidth, left, rightWidth, right, component)).contains(true)
    }
  }
}
