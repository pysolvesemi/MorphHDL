package spinal.core

/** Explicit reference to a real sibling definition in the same native hierarchy.
  * This does not authorize recursive/non-local edges or supply an implementation.
  * Port and typed generic substitution are validated during publication.
  */
object ElabModuleReference {
  private object Key
  private case class Binding(reference: BlackBox, definition: Component, owner: Component)

  def bind[T <: BlackBox](reference: T, definition: Component): T = {
    require(reference != null && definition != null && !definition.isInstanceOf[BlackBox],
      "a shared reference requires a real definition")
    require(reference.parent != null && (reference.parent eq definition.parent),
      "a shared reference and its definition must be direct siblings")
    if (reference.impl != null || reference.listRTLPath.nonEmpty || reference.children.nonEmpty)
      ParameterizedVerilogException.fail("SPINAL-PARAMETERIZED-VERILOG-SHARED-REFERENCE-MISMATCH",
        "a shared reference cannot supply a separate implementation", None)
    require(!reference.userCache.contains(Key), "shared reference identity is already bound")
    reference.userCache(Key) = Binding(reference, definition, reference.parent)
    reference
  }

  private[spinal] def definitionOf(reference: BlackBox): Option[Component] =
    reference.userCache.get(Key).map { value =>
      val binding = value.asInstanceOf[Binding]
      require((binding.reference eq reference) && (reference.parent eq binding.owner) &&
        (binding.definition.parent eq binding.owner) &&
        binding.owner.children.exists(_ eq reference) &&
        binding.owner.children.exists(_ eq binding.definition),
        "shared reference lost its exact sibling ownership")
      binding.definition
    }
}
