package spinal.core

/** Immutable range evidence attached to one registered loop selection. */
private[core] final class TypedLoopVecRange private[core] (
    val selection: ParameterizedStructure.StructuralVecIndex,
    val loop: ParameterizedStructure.StructuralFor,
    val count: ElabInt,
    val depth: ElaborationIntegerExpression,
    val offset: Int) {
  private val selector = selection.index
  private val vector = selection.vector
  private val token = selection.finiteIndexToken
  def matches: Boolean =
    (selection.index eq selector) && (selection.vector eq vector) &&
      selection.finiteIndexToken == token && token.nonEmpty &&
      loop.finiteIndexToken == token && loop.body.vecIndices.exists(_ eq selection) &&
      ParameterizedVec.shapeOf(vector).exists(_.depth eq depth) &&
      selector.generateIndex.contains(loop.indexName) &&
      TypedLoopVecRange.unitOffset(selector, loop.indexName).contains(offset) &&
      ElabBool.projectedTruth((count + offset) <= ElabInt.fromExpression(depth)) == ElabBool.AlwaysTrue
  def coversTail: Boolean = matches &&
    ElabBool.projectedTruth((count + offset).elabEq(ElabInt.fromExpression(depth))) == ElabBool.AlwaysTrue
}

private[core] object TypedLoopVecRange {
  private[core] def unitOffset(index: ElaborationIntegerExpression, name: String): Option[Int] = {
    val text = index.verilog.replaceAll("[()\\s]", "")
    if (index.parameters.nonEmpty || !index.minimum.isValidInt || index.minimum < 0) None
    else if (text == name && index.minimum == 0) Some(0)
    else if (text == s"$name+${index.minimum}" && index.default == index.minimum) Some(index.minimum.toInt)
    else None
  }
  def retain(loop: ParameterizedStructure.StructuralFor, proof: Option[ElabInt]): Unit = {
    val normalized = proof.map { count =>
      val value = count.expression
      if (value.parameters.isEmpty && value.generateIndex.isEmpty && value.minimum == value.maximum && value.default == value.minimum)
        ElabInt.fromBigInt(value.default)
      else count
    }
    for (count <- normalized; selection <- loop.body.vecIndices if selection.affineRead.isEmpty;
         shape <- ParameterizedVec.shapeOf(selection.vector);
         offset <- unitOffset(selection.index, loop.indexName)) {
      val evidence = new TypedLoopVecRange(selection, loop, count, shape.depth, offset)
      if (evidence.matches) selection.unitRange = Some(evidence)
    }
  }
}
