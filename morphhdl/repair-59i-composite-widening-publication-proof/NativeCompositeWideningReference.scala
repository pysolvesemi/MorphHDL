package nativeapplication

import spinal.core._
import spinal.lib._

/** Concrete, independently constructed reference for one specialization. */
final case class NativeCompositeWideningValue(
    unsignedSumWidth: Int,
    unsignedProductWidth: Int,
    signedSumWidth: Int,
    signedProductWidth: Int) extends Bundle {
  val unsignedSum = UInt(unsignedSumWidth bits)
  val unsignedProduct = UInt(unsignedProductWidth bits)
  val signedSum = SInt(signedSumWidth bits)
  val signedProduct = SInt(signedProductWidth bits)
}

final class NativeCompositeWideningReference(
    unsignedSumWidth: Int,
    unsignedProductWidth: Int,
    signedSumWidth: Int,
    signedProductWidth: Int,
    count: Int,
    moduleName: String) extends Component {
  setDefinitionName(moduleName)

  private val laneWidth = unsignedSumWidth + unsignedProductWidth + signedSumWidth + signedProductWidth
  val values = in(Bits((laneWidth * count) bits)).setName("values")

  private def typedWidth(value: BaseType): Int = value.getBitsWidth
  private def combine(a: NativeCompositeWideningValue,
      b: NativeCompositeWideningValue): NativeCompositeWideningValue = {
    val unsignedSum = a.unsignedSum +^ b.unsignedSum
    val unsignedProduct = a.unsignedProduct * b.unsignedProduct
    val signedSum = a.signedSum +^ b.signedSum
    val signedProduct = a.signedProduct * b.signedProduct
    val result = NativeCompositeWideningValue(typedWidth(unsignedSum), typedWidth(unsignedProduct),
      typedWidth(signedSum), typedWidth(signedProduct))
    result.unsignedSum := unsignedSum
    result.unsignedProduct := unsignedProduct
    result.signedSum := signedSum
    result.signedProduct := signedProduct
    result
  }

  private val lanes = Vector.tabulate(count) { index =>
    val lane = NativeCompositeWideningValue(unsignedSumWidth, unsignedProductWidth,
      signedSumWidth, signedProductWidth)
    lane.assignFromBits(values(index * laneWidth, laneWidth bits))
    lane
  }

  val reduced = lanes.reduceBalancedTree(
    (a: NativeCompositeWideningValue, b: NativeCompositeWideningValue) => combine(a, b))
  val result = out(cloneOf(reduced)).setName("result")
  result := reduced
}
