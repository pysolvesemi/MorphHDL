package spinal.core
import spinal.core.internals._

/** Exact native shift operator owned by one finite generate index. */
private[spinal] object TypedLoopPowerShift {
  private object Key
  final case class Entry(index: ElabFiniteIndex, source: UInt, result: UInt,
      assignment: DataAssignmentStatement, operator: Operator.BitVector.ShiftRightByIntFixedWidth)
  def entries(component: Component): Vector[Entry] = component.userCache.get(Key)
    .map(_.asInstanceOf[scala.collection.mutable.ArrayBuffer[Entry]].toVector).getOrElse(Vector.empty)
  def apply(index: ElabFiniteIndex, source: UInt): UInt = {
    require(index.expression.minimum >= 0 && index.expression.maximum < 31,
      "finite power shift requires an unsigned shift exponent below 31")
    val shifted = source |>> (1 << index.expression.default.toInt)
    if (index.expression.generateIndex.isEmpty) return shifted
    val result = ParameterizedExpressionCarrier.retain(ParameterizedWidth.cloneOf(source))
    result.setWeakName("prefix_shift")
    result := shifted
    // Keep the direct native operator on its exact retained assignment.
    val operator = shifted.head.source.asInstanceOf[Operator.BitVector.ShiftRightByIntFixedWidth]
    val assignment = result.head.asInstanceOf[DataAssignmentStatement]
    assignment.source = operator
    val records = source.component.userCache.getOrElseUpdate(Key,
      scala.collection.mutable.ArrayBuffer.empty[Entry]).asInstanceOf[scala.collection.mutable.ArrayBuffer[Entry]]
    records += Entry(index, source, result, assignment, operator)
    result
  }
  def renderedIndex(component: Component, assignment: AssignmentStatement): Option[(UInt,String)] =
    entries(component).find(_.assignment eq assignment).map { entry =>
      def loops(regions: Vector[ParameterizedStructure.StructuralRegion]): Vector[ParameterizedStructure.StructuralFor] =
        regions.flatMap { region => region match {
          case loop: ParameterizedStructure.StructuralFor => Vector(loop) ++ loop.blocks.flatMap(b => loops(b.regions))
          case other => other.blocks.flatMap(b => loops(b.regions))
        }}
      val owners = loops(ParameterizedStructure.regionsOf(component)).filter(_.finiteIndexToken.exists(_ eq entry.index.token))
      require(owners.size == 1 && owners.head.body.assignments.exists(_ eq assignment) &&
        (assignment.source eq entry.operator) && (assignment.target eq entry.result) &&
        (entry.operator.source eq entry.source) && entry.operator.shift == (1 << entry.index.expression.default.toInt) &&
        entry.index.expression.generateIndex.contains(owners.head.indexName),
        "finite power shift lost its exact operator or enclosing range")
      entry.source -> entry.index.expression.verilog
    }
}
