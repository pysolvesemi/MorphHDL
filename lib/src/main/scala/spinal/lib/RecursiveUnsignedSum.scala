package spinal.lib

import spinal.core._

/** Opt-in combinational unsigned modular sum, published as one recursive module.
  * Term zero occupies the least-significant TERM_WIDTH bits. Each term is zero
  * extended or truncated to SUM_WIDTH; addition is modulo 2^SUM_WIDTH. There
  * are no clocks/registers. This does not change Vec.reduceBalancedTree.
  */
class RecursiveUnsignedSum(countActual: ElabInt, termWidthActual: ElabInt, sumWidthActual: ElabInt,
    maximumCount: Int = 64, maximumTermWidth: Int = 64, maximumSumWidth: Int = 128) extends Component {
  require(ParameterizedStructure.captureEnabled, "RecursiveUnsignedSum requires MorphVerilog")
  require(maximumCount >= 2 && maximumTermWidth >= 1 && maximumSumWidth >= 1)
  setDefinitionName("RecursiveUnsignedSum")
  @dontName private val specifications = Vector(
    (countActual, "COUNT", maximumCount), (termWidthActual, "TERM_WIDTH", maximumTermWidth),
    (sumWidthActual, "SUM_WIDTH", maximumSumWidth))
  @dontName private val inventory = specifications.map { case (actual, name, maximum) =>
    require(actual != null && actual.minimum >= 1 && actual.maximum <= maximum,
      s"$name actual must fit the declared positive domain")
    val expression = actual.projectedExpression("recursive sum actual")
    (ElaborationIntegerParameter(name, expression.default, 1, maximum), expression, actual.sourceLocation)
  }
  @dontName private val values = inventory.map { case (formal, _, source) => ElabInt.directParameter(formal, source) }
  @dontName val count = values(0)
  @dontName val termWidth = values(1)
  @dontName val sumWidth = values(2)
  addPrePopTask(() => ExternalFormalParameterRegistry.retainTypedComponentParameters(this, inventory))
  val terms = in Bits((count * termWidth) bits)
  val sum = out UInt(sumWidth bits)

  private class Reference(nextCount: ElabInt) extends BlackBox {
    setBlackBoxName("RecursiveUnsignedSum")
    addGeneric("COUNT", nextCount)
    addGeneric("TERM_WIDTH", termWidth)
    addGeneric("SUM_WIDTH", sumWidth)
    val terms = in Bits((nextCount * termWidth) bits)
    val sum = out UInt(sumWidth bits)
  }

  ElabControl.selectSymbolic(count <= 1, "RecursiveUnsignedSum", 1) {
    sum := ElabPackedPartition.single(terms, termWidth).asUInt.resize(sumWidth)
  } {
    val halves = ElabPackedPartition(terms, count, termWidth)
    val left = new Reference(count / 2)
    val right = new Reference(count - count / 2)
    left.setWeakName("left")
    right.setWeakName("right")
    left.terms := halves._1
    right.terms := halves._2
    ElabPackedPartition.bind(halves._1, left.terms)
    ElabPackedPartition.bind(halves._2, right.terms)
    sum := left.sum + right.sum
  }
}
