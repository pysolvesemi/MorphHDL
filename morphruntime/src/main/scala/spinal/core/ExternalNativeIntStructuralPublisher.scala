package spinal.core

/** Sole public bridge from an active native-Int shadow boundary to the raw
  * package-internal structural generate-if sink.
  *
  * Predicate resolution and exact target binding happen together.  The
  * resulting opaque receipt can publish only that one condition/target tuple
  * and is consumed only after the underlying registration succeeds.
  */
object ExternalNativeIntStructuralPublisher {
  def definitionPredicateTracked(
      reference: String,
      witness: Boolean,
      pending: ParameterizedStructuralPending,
      whenTrueLabel: String,
      whenFalseLabel: String,
      whenTrue: ParameterizedStructuralBlock,
      whenFalse: ParameterizedStructuralBlock,
      sourceLocation: Option[String]
  ): ExternalNativeIntStructuralPredicateReceipt = {
    val source = requireSource(sourceLocation)
    val owner = Option(Component.current).getOrElse {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-STRUCTURAL-PREDICATE-OWNER-MISSING",
        "native Int structural predicate publication requires an active Component",
        source
      )
    }
    requireTarget(
      owner,
      pending,
      whenTrueLabel,
      whenFalseLabel,
      whenTrue,
      whenFalse,
      source
    )
    ExternalNativeIntShadowRegistry.definitionPredicateReceiptTracked(
      reference,
      witness,
      owner,
      pending,
      whenTrueLabel,
      whenFalseLabel,
      whenTrue,
      whenFalse,
      source
    )
  }

  def registerIf(
      receipt: ExternalNativeIntStructuralPredicateReceipt,
      condition: ElaborationBooleanExpression,
      pending: ParameterizedStructuralPending,
      whenTrueLabel: String,
      whenFalseLabel: String,
      whenTrue: ParameterizedStructuralBlock,
      whenFalse: ParameterizedStructuralBlock,
      sourceLocation: Option[String]
  ): Unit = {
    if (receipt eq null) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-STRUCTURAL-PREDICATE-RECEIPT-MISSING",
        "native Int structural predicate publication requires an issued receipt",
        Option(sourceLocation).flatten
      )
    }
    receipt.publish(
      condition,
      pending,
      whenTrueLabel,
      whenFalseLabel,
      whenTrue,
      whenFalse,
      sourceLocation
    ) {
      ParameterizedStructure.registerIf(
        pending,
        condition,
        whenTrueLabel,
        whenFalseLabel,
        whenTrue,
        whenFalse,
        sourceLocation,
        receipt.predicateDomain
      )
    }
  }

  private def requireTarget(
      owner: Component,
      pending: ParameterizedStructuralPending,
      whenTrueLabel: String,
      whenFalseLabel: String,
      whenTrue: ParameterizedStructuralBlock,
      whenFalse: ParameterizedStructuralBlock,
      sourceLocation: Option[String]
  ): Unit = {
    if (
      (pending eq null) || (pending.component ne owner) ||
      pending.kind != "generate-if" ||
      whenTrueLabel == null || whenTrueLabel.isEmpty ||
      whenFalseLabel == null || whenFalseLabel.isEmpty ||
      (whenTrue eq null) || (whenFalse eq null)
    ) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-STRUCTURAL-PREDICATE-TARGET-MISMATCH",
        "native Int structural predicate receipt requires one exact generate-if pending target, labels and captured blocks",
        sourceLocation
      )
    }
  }

  private def requireSource(
      sourceLocation: Option[String]
  ): Option[String] = {
    if (
      (sourceLocation eq null) || sourceLocation.isEmpty ||
      sourceLocation.exists(value => value == null || value.trim.isEmpty)
    ) {
      fail(
        "MORPH-FRONTEND-NATIVE-INT-STRUCTURAL-PREDICATE-SOURCE-MISSING",
        "native Int structural predicate receipt requires one non-empty source location",
        Option(sourceLocation).flatten
      )
    }
    sourceLocation
  }

  private def fail(
      code: String,
      detail: String,
      sourceLocation: Option[String]
  ): Nothing =
    ParameterizedVerilogException.fail(code, detail, sourceLocation)
}
