package spinal.core

import spinal.core.internals._

/** An exact sized constant edge, authorized only by its enclosing finite loop. */
private[spinal] object TypedFiniteIndexValue {
  private object Key
  final case class Entry(index: ElabFiniteIndex, result: UInt,
      assignment: DataAssignmentStatement, literal: Expression, width: Int)
  def entries(component: Component): Vector[Entry] = component.userCache.get(Key)
    .map(_.asInstanceOf[scala.collection.mutable.ArrayBuffer[Entry]].toVector).getOrElse(Vector.empty)

  def apply(index: ElabFiniteIndex, width: Int): UInt = {
    if (width <= 0 || width > 32 || index.expression.maximum.bitLength > width)
      ParameterizedVerilogException.fail("SPINAL-ELAB-FINITE-INDEX-VALUE-WIDTH-INVALID",
        "generate index value width must represent every index and be in 1..32", index.expression.sourceLocation)
    if (index.expression.generateIndex.isEmpty) return U(index.expression.default, width bits)
    ParameterizedStructure.requireActiveFiniteIndex(index)
    val result = ParameterizedExpressionCarrier.retain(UInt(width bits))
    result.setWeakName("index_value")
    val constant = U(index.expression.default, width bits)
    result := constant
    val assignment = result.head.asInstanceOf[DataAssignmentStatement]
    assignment.source = constant.head.source
    val records = result.component.userCache.getOrElseUpdate(Key,
      scala.collection.mutable.ArrayBuffer.empty[Entry]).asInstanceOf[scala.collection.mutable.ArrayBuffer[Entry]]
    records += Entry(index, result, assignment, assignment.source, width)
    result
  }

  def source(component: Component, assignment: AssignmentStatement): Option[String] =
    entries(component).find(_.assignment eq assignment).map { entry =>
      def blocks(regions: Vector[ParameterizedStructure.StructuralRegion]): Vector[ParameterizedStructuralBlock] =
        regions.flatMap(region => region.blocks.flatMap(block => Vector(block) ++ blocks(block.regions)))
      val all = blocks(ParameterizedStructure.regionsOf(component))
      val owner = all.filter(_.assignments.exists(_ eq assignment))
      require(owner.size == 1 && (assignment.target eq entry.result) &&
        (assignment.source eq entry.literal) && entry.result.getBitsWidth == entry.width,
        "finite index value lost its exact constant edge, owner or width")
      require(entry.literal.isInstanceOf[UIntLiteral] && !entry.literal.asInstanceOf[UIntLiteral].hasPoison() &&
        entry.literal.asInstanceOf[UIntLiteral].getValue() == entry.index.expression.default &&
        entry.literal.asInstanceOf[UIntLiteral].getWidth == entry.width,
        "finite index value lost its exact unsigned witness literal")
      ParameterizedStructure.requireFiniteValueOwner(entry.index, component, owner.head)
      s"${entry.index.expression.verilog}[${entry.width - 1}:0]"
    }
}
