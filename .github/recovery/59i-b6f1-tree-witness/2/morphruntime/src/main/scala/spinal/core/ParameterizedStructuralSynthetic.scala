package spinal.core

import java.util.{Collections, IdentityHashMap, Set => JavaSet}

/** Synthetic structural blocks used by MorphHDL-owned frontend transforms.
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
  private val syntheticEmpty: JavaSet[ParameterizedStructuralBlock] =
    Collections.synchronizedSet(
      Collections.newSetFromMap(
        new IdentityHashMap[ParameterizedStructuralBlock, java.lang.Boolean]()
      )
    )

  def emptyBlock(sourceLocation: Option[String]): ParameterizedStructuralBlock = {
    val block = new ParameterizedStructuralBlock(
      statements = Vector.empty,
      declarations = Vector.empty,
      assignments = Vector.empty,
      memories = Vector.empty,
      children = Vector.empty,
      slices = Vector.empty,
      vecIndices = Vector.empty,
      memoryIndices = Vector.empty,
      scalarOperators = Vector.empty,
      regions = Vector.empty,
      sourceLocation = sourceLocation
    )
    syntheticEmpty.add(block)
    block
  }

  def isSyntheticEmpty(block: ParameterizedStructuralBlock): Boolean =
    block != null && syntheticEmpty.contains(block)
}
