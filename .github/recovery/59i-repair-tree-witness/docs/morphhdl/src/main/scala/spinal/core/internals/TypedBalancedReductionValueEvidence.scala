package spinal.core.internals

import spinal.core._

/** Width authority for one exact native value before normalization.
  * A native intermediate's witness width is not a symbolic width transfer.
  * Derived evidence can only originate in an actual operator or bridge proof.
  */
private[spinal] object TypedBalancedReductionValueEvidence {
  private def fail(detail: String): Nothing =
    throw new IllegalArgumentException("MORPH-REDUCE-BALANCED-VALUE-EVIDENCE: " + detail)

  private val scalarClasses: Set[Class[_]] =
    Set(classOf[Bool], classOf[Bits], classOf[UInt], classOf[SInt])

  /** HardType fixes a driven source before cloning it. Materializing a
    * proved constant width preserves its meaning, but freezing a symbolic
    * width to its default does not. No other width-policy edit is admitted.
    */
  def preservesFixedWidth(before: Int, after: Int,
      width: ElaborationIntegerExpression): Boolean =
    before == after || (before == -1 && width.parameters.isEmpty &&
      width.generateIndex.isEmpty && BigInt(after) == width.default)

  /** Native HardType may materialize the witness of an already retained width.
    * The live, exact symbolic registry entry remains authoritative; a bare
    * inferred value or a replaced/frozen entry still cannot make this transfer. */
  def preservesValueWidth(value: BaseType, before: Int, after: Int,
      width: ElaborationIntegerExpression): Boolean =
    preservesFixedWidth(before, after, width) ||
      (before == -1 && BigInt(after) == width.default &&
        ParameterizedWidth.expressionOf(value).exists(ElaborationWidthAuthority.equivalent(_, width)))

  private def sameWidthIdentity(a: Option[ElaborationIntegerExpression],
      b: Option[ElaborationIntegerExpression]): Boolean = (a, b) match {
    case (None, None) => true
    case (Some(left), Some(right)) => left eq right
    case _ => false
  }

  final class Evidence private[TypedBalancedReductionValueEvidence] (
      val value: BaseType,
      val width: ElaborationIntegerExpression,
      private val prerequisite: () => Unit
  ) {
    val owner: Component = value.component
    val kind: AnyRef = value.getTypeObject.asInstanceOf[AnyRef]
    private val retained = ParameterizedWidth.expressionOf(value)
    private val scope = value.parentScope
    private val register = value.isReg
    private val clock = value.clockDomain
    private val fixed = value match { case bits: BitVector => bits.fixedWidth; case _ => -1 }

    def requireFreshness(): Unit = {
      prerequisite()
      val currentFixed = value match { case bits: BitVector => bits.fixedWidth; case _ => -1 }
      if ((value.component ne owner) || (value.parentScope ne scope) ||
          (value.getTypeObject.asInstanceOf[AnyRef] ne kind) ||
          (value.clockDomain ne clock) || value.isReg != register || value.isAnalog ||
          value.hasTag(tagAutoResize) || !preservesValueWidth(value, fixed, currentFixed, width) ||
          BigInt(value.getBitsWidth) != width.default ||
          !sameWidthIdentity(ParameterizedWidth.expressionOf(value), retained))
        fail("the certified native value changed its owner, type or width")
    }

    def requireValue(candidate: BaseType): Unit = {
      if (candidate ne value) fail("evidence belongs to a different native value")
      requireFreshness()
    }

    /** Replacement values need their own direct typed width, not the old
      * intermediate's evidence. Native replay attaches that proved width.
      */
    def requireReplacement(candidate: BaseType): Unit = {
      requireFreshness()
      if (candidate == null || !scalarClasses.contains(candidate.getClass) ||
          (candidate.component ne owner) ||
          (candidate.getTypeObject.asInstanceOf[AnyRef] ne kind) ||
          candidate.isAnalog || candidate.hasTag(tagAutoResize) ||
          BigInt(candidate.getBitsWidth) != width.default)
        fail("replacement value has a different native owner, type or width")
      val actual = ParameterizedWidth.expressionOf(candidate)
        .getOrElse(ElabInt.literal(candidate.getBitsWidth).expression)
      if (!ElaborationWidthAuthority.equivalent(actual, width))
        fail("replacement value lost the exact symbolic width authority")
    }
  }

  private def create(value: BaseType, width: ElaborationIntegerExpression,
      prerequisite: () => Unit): Evidence = {
    if (value == null || width == null || prerequisite == null ||
        !scalarClasses.contains(value.getClass) || value.component == null || value.isAnalog)
      fail("one supported live scalar value and authoritative width are required")
    ElaborationWidthAuthority.requireAuthoritative(width, "balanced value width",
      "MORPH-REDUCE-BALANCED-VALUE-WIDTH-AUTHORITY")
    if (width.minimum < 1) fail("value width must remain positive")
    val result = new Evidence(value, width, prerequisite)
    result.requireFreshness()
    result
  }

  def input(value: BaseType): Evidence = {
    if (value == null) fail("input value must be present")
    val width = ParameterizedWidth.expressionOf(value)
      .getOrElse(ElabInt.literal(value.getBitsWidth).expression)
    create(value, width, () => ())
  }

  def fromOperator(proof: TypedBalancedReductionOperatorCertificate): Evidence = {
    if (proof == null) fail("operator proof must be present")
    create(proof.nativeResult, proof.resultWidth, () => proof.validateFreshness())
  }

  def fromBridge(proof: TypedBalancedReductionBridgeReplay.Proof): Evidence = {
    if (proof == null) fail("bridge proof must be present")
    create(proof.nativeResult, proof.resultWidth, () => proof.validateFreshness())
  }

  def fromComposite(proof: TypedBalancedReductionCompositeReplay.OperatorProof,
      index: Int): Evidence = {
    if (proof == null) fail("composite operator proof must be present")
    create(proof.nativeResult.flatten(index), proof.resultWidths(index),
      () => proof.validateFreshness())
  }
}
