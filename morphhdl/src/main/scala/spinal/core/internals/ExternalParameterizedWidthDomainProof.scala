package spinal.core.internals

/**
  * Generic bounded proof kernel for parameter-derived packed widths.
  *
  * It has no knowledge of any SpinalHDL component, signal or library name.
  * Callers must separately prove that both evaluators belong to one exact
  * compiler-retained parameter root. Undefined, non-positive or unequal values
  * fail closed.
  */
private[internals] object ExternalParameterizedWidthDomainProof {
  private val MaximumValues = BigInt(65536)

  def equivalent(
      minimum: BigInt,
      maximum: BigInt
  )(
      left: BigInt => Option[BigInt],
      right: BigInt => Option[BigInt]
  ): Boolean = {
    val count = maximum - minimum + 1
    if (minimum > maximum || count < 1 || count > MaximumValues) return false

    var value = minimum
    while (value <= maximum) {
      val l = left(value)
      val r = right(value)
      if (l.isEmpty || r.isEmpty || l != r || l.exists(_ < 1)) return false
      value += 1
    }
    true
  }
}
