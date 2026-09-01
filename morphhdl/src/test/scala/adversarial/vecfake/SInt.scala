package adversarial.vecfake

/** Ordinary downstream Bits subtype whose class name deliberately collides
  * with SpinalHDL's signed leaf name. The inherited TypeBits object remains
  * the only authoritative leaf-kind evidence.
  */
final class SInt() extends spinal.core.Bits

object SInt {
  def apply(width: Int): SInt = new SInt().setWidth(width)
}
