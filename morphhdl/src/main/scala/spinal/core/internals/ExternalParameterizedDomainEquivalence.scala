package spinal.core.internals

import spinal.core.ElaborationIntegerParameter

/**
  * Generic bounded-domain proof for MorphHDL-retained integer expressions.
  *
  * The proof engine has no knowledge of Component classes, library source
  * paths, emitted HDL names or particular SpinalHDL APIs. Callers provide exact
  * graph/provenance-backed evaluators; this object only validates one common
  * formal schema and exhaustively checks the complete finite Cartesian domain.
  */
private[internals] object ExternalParameterizedDomainEquivalence {
  private val MaximumAssignments = BigInt(65536)

  def provePositiveEquality(
      parameters: Vector[ElaborationIntegerParameter],
      leftDefault: BigInt,
      rightDefault: BigInt
  )(
      left: Map[String, BigInt] => Option[BigInt],
      right: Map[String, BigInt] => Option[BigInt]
  ): Boolean = {
    if (parameters.isEmpty || leftDefault != rightDefault) return false

    val grouped = parameters.groupBy(_.name).toVector.sortBy(_._1)
    if (grouped.exists { case (_, declarations) => declarations.distinct.size != 1 })
      return false
    val formals = grouped.map(_._2.head)
    if (formals.exists { formal =>
          formal.minimum > formal.maximum ||
          formal.default < formal.minimum ||
          formal.default > formal.maximum
        }) return false

    val domainSize = formals.foldLeft(BigInt(1)) { (product, formal) =>
      val size = formal.maximum - formal.minimum + 1
      if (size < 1 || product > MaximumAssignments / size)
        MaximumAssignments + 1
      else product * size
    }
    if (domainSize < 1 || domainSize > MaximumAssignments) return false

    def verify(index: Int, values: Map[String, BigInt]): Boolean = {
      if (index == formals.size) {
        val leftValue = left(values)
        val rightValue = right(values)
        leftValue.nonEmpty && leftValue == rightValue && leftValue.exists(_ > 0)
      } else {
        val formal = formals(index)
        var value = formal.minimum
        var valid = true
        while (valid && value <= formal.maximum) {
          valid = verify(index + 1, values.updated(formal.name, value))
          value += 1
        }
        valid
      }
    }

    val defaults = formals.map(formal => formal.name -> formal.default).toMap
    val defaultLeft = left(defaults)
    val defaultRight = right(defaults)
    if (
      !defaultLeft.contains(leftDefault) ||
      !defaultRight.contains(rightDefault)
    ) false
    else verify(0, Map.empty)
  }
}
