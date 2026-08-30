package spinal.core

/** Explicit typed elaboration-value adapters for ordinary hardware operators. */
object ElabValue {
  /**
    * Materialize one UInt with the prototype's native shape while retaining the
    * exact typed integer expression on that exact carrier object.
    */
  def uintLike(
      value: ElabInt,
      prototype: UInt,
      stableName: String
  ): UInt = {
    if (value == null || prototype == null)
      throw new IllegalArgumentException(
        "typed UInt adapter requires a non-null ElabInt and prototype"
      )
    val expression = value.projectedExpression("typed UInt value")
    val witness = expression.default
    val width = prototype.getBitsWidth
    if (width < 1)
      throw new IllegalArgumentException(
        "typed UInt adapter prototype must have a positive native width"
      )
    if (expression.parameters.isEmpty) U(witness, width bits)
    else {
      val result = UInt(width bits)
      ParameterizedWidth.copyShape(prototype, result)
      if (stableName != null && stableName.nonEmpty) result.setName(stableName)
      result := U(witness)
      ExternalParameterizedValueRegistry.attach(
        result,
        expression,
        witness,
        expression.sourceLocation
      )
    }
  }
}
