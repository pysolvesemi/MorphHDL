package spinal.core

/** Explicit typed elaboration-value adapters for ordinary hardware operators. */
object ElabValue {
  private object GeneratedValueOrdinalStorageKey

  private final class GeneratedValueOrdinals {
    var next = 0
  }

  private def retainedValueName(stableName: String): String = {
    if (stableName != null && stableName.nonEmpty) return stableName
    val component = Option(Component.current).getOrElse {
      throw new IllegalStateException(
        "typed UInt adapter requires an active Component"
      )
    }
    val ordinals = component.userCache
      .getOrElseUpdate(
        GeneratedValueOrdinalStorageKey,
        new GeneratedValueOrdinals
      )
      .asInstanceOf[GeneratedValueOrdinals]
    ordinals.next += 1
    s"morphhdl_typed_value_${ordinals.next}"
  }

  private def valueWidthInsufficient(
      expression: ElaborationIntegerExpression,
      prototypeWidth: Option[ElaborationIntegerExpression],
      detail: String
  ): Nothing =
    throw new ParameterizedVerilogException(
      "SPINAL-PARAMETERIZED-VERILOG-VALUE-WIDTH-INSUFFICIENT",
      detail,
      expression.sourceLocation.orElse(prototypeWidth.flatMap(_.sourceLocation))
    )

  /** Build an all-ones UInt whose width remains an exact typed expression.
    *
    * The ordinary native complement operator is authoritative.  Its operand is
    * one retained-width zero carrier, so widening a later Verilog
    * specialization widens the complement as well instead of reusing an
    * all-ones BigInt computed from the construction witness.
    */
  def uintAllOnes(width: ElabInt, stableName: String): UInt = {
    if (width == null)
      throw new IllegalArgumentException(
        "typed all-ones UInt requires a non-null width"
      )
    val zero = UInt(width bits)
    if (stableName != null && stableName.nonEmpty) zero.setName(stableName)
    zero := U(0)
    zero.setAsVital()
    zero.dontSimplifyIt()
    val ones = ~zero
    ParameterizedWidth.copyShape(zero, ones)
  }

  /** Materialize one UInt with the prototype's native shape while retaining the
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
    val expression = value.authoritativeProjectedExpression(
      role = "typed UInt value",
      failureCode = "SPINAL-PARAMETERIZED-VERILOG-VALUE-EXACT-DOMAIN-REQUIRED",
      requireProjectedExactExtrema = true
    )
    val witness = expression.default
    val width = prototype.getBitsWidth
    if (width < 1)
      throw new IllegalArgumentException(
        "typed UInt adapter prototype must have a positive native width"
      )
    val prototypeWidth =
      ParameterizedWidth.expressionOf(prototype).filter(_.parameters.nonEmpty)
    val symbolicPrototype = prototypeWidth.nonEmpty
    if (expression.parameters.isEmpty && witness < 0)
      valueWidthInsufficient(
        expression,
        prototypeWidth,
        s"typed UInt constant $witness cannot be represented by an unsigned carrier"
      )
    if (expression.parameters.nonEmpty || symbolicPrototype)
      ExternalParameterizedValueRegistry.validateCarrierDomain(
        prototype,
        expression,
        expression.sourceLocation
      )
    if (expression.parameters.isEmpty && !symbolicPrototype)
      U(witness, width bits)
    else {
      val result = prototypeWidth match {
        case Some(retained) => UInt(ElabInt.fromExpression(retained).bits)
        case None           => UInt(width bits)
      }
      // A concrete authored name is optional, but the retained carrier must
      // remain a named native declaration. Otherwise ordinary type-node
      // elimination can inline its witness literal into a symbolic consumer
      // before the exact registry rewrites that same carrier identity.
      result.setName(retainedValueName(stableName))
      // Match the native carrier's witness width explicitly.  Leaving the
      // literal unsized asks the assignment algorithm to insert a Resize,
      // which obscures the exact literal source identity retained below.
      result.assignFrom(
        spinal.core.internals.UIntLiteral(witness, null, width)
      )
      if (expression.parameters.nonEmpty || symbolicPrototype)
        ExternalParameterizedValueRegistry.attach(
          result,
          expression,
          witness,
          expression.sourceLocation
        )
      result
    }
  }
}
