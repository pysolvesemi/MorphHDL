package spinal.core

/**
  * Synthetic structural blocks used by MorphHDL-owned frontend transforms.
  *
  * User-authored structural bodies must continue to pass through
  * [[ParameterizedStructure.captureBlock]], which rejects bodies that produce
  * no native hardware. A compiler-generated continuation, however, may be
  * intentionally empty (for example the false side of one mutually-exclusive
  * sibling guard in a flattened `if / else if / else` chain). Such an empty
  * block carries no Scala side effects and exists only to complete the opaque
  * structural region shape expected by the external lowering pipeline.
  */
object ParameterizedStructuralSynthetic {
  def emptyBlock(sourceLocation: Option[String]): ParameterizedStructuralBlock =
    new ParameterizedStructuralBlock(
      statements = Vector.empty,
      declarations = Vector.empty,
      assignments = Vector.empty,
      children = Vector.empty,
      slices = Vector.empty,
      vecIndices = Vector.empty,
      sourceLocation = sourceLocation
    )
}
