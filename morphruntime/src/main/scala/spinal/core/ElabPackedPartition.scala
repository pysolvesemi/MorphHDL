package spinal.core

import spinal.core.internals._

/** Native packed partition used by explicitly recursive unsigned reduction.
  * Its two disjoint intervals are constructed together; callers cannot supply
  * independent offsets or omit/overlap a term. No hardware index is introduced.
  */
private[spinal] object ElabPackedPartition {
  private object Key
  private final class Carrier extends ResizeBits {
    override def simplifyNode: Expression = this
  }
  final case class Read(source: Bits, result: Bits, offset: ElaborationIntegerExpression,
      width: ElaborationIntegerExpression, sourceWidth: ElaborationIntegerExpression,
      assignment: DataAssignmentStatement, nativeSource: Expression,
      sourceDeclaration: ElaborationIntegerExpression, resultDeclaration: ElaborationIntegerExpression,
      requiresConsumer: Boolean) {
    private[ElabPackedPartition] var consumer: Option[(Bits, DataAssignmentStatement)] = None
  }
  def entries(component: Component): Vector[Read] = component.userCache.get(Key)
    .map(_.asInstanceOf[scala.collection.mutable.ArrayBuffer[Read]].toVector).getOrElse(Vector.empty)

  def apply(source: Bits, count: ElabInt, termWidth: ElabInt): (Bits, Bits) = {
    require(ParameterizedStructure.captureEnabled, "recursive packed partition requires MorphHDL capture")
    require(source.component eq Component.current, "partition source must belong to its declaring component")
    val left = count / 2
    val right = count - left
    require(count.minimum > 1 && termWidth.minimum > 0 && left.minimum > 0 && right.minimum > 0,
      "recursive partition requires at least two positive-width terms")
    val total = ElabInt.widthOf(source)
    require(ElaborationProductDomain.provesRelation(total.expression, (count * termWidth).expression)(_ == _),
      "recursive partition source does not match its term geometry")
    (read(source, ElabInt.literal(0), left * termWidth, true), read(source, left * termWidth, right * termWidth, true))
  }

  def single(source: Bits, termWidth: ElabInt): Bits = {
    require(ElaborationProductDomain.provesRelation(
      ElabInt.widthOf(source).expression, termWidth.expression)(_ == _),
      "recursive base must contain exactly one term")
    read(source, ElabInt.literal(0), termWidth, false)
  }

  private def read(source: Bits, offset: ElabInt, width: ElabInt, requiresConsumer: Boolean): Bits = {
    require(ParameterizedStructure.captureEnabled && (source.component eq Component.current),
      "recursive read requires its captured native source owner")
    val result = Bits(width bits).dontSimplifyIt()
    val resized = source.resize(width.witness)
    result := resized
    val assignment = result.head.asInstanceOf[DataAssignmentStatement]
    val native = new Carrier
    native.input = source
    native.size = width.witness
    assignment.source = native
    val records = source.component.userCache.getOrElseUpdate(Key,
      scala.collection.mutable.ArrayBuffer.empty[Read]).asInstanceOf[scala.collection.mutable.ArrayBuffer[Read]]
    records += Read(source, result, offset.projectedExpression("partition offset"),
      width.projectedExpression("partition width"), ElabInt.widthOf(source).projectedExpression("partition source width"),
      assignment, assignment.source, ParameterizedWidth.expressionOf(source).get,
      ParameterizedWidth.expressionOf(result).get, requiresConsumer)
    result
  }

  def bind(result: Bits, input: Bits): Unit = {
    val entry = entries(result.component).find(_.result eq result).getOrElse(
      throw new IllegalArgumentException("recursive partition consumer requires an exact retained result"))
    require(entry.requiresConsumer && entry.consumer.isEmpty && input.isInput &&
      (input.component.parent eq result.component) && input.hasOnlyOneStatement,
      "recursive partition requires one exact direct child input")
    val assignment = input.head.asInstanceOf[DataAssignmentStatement]
    require((assignment.target eq input) && (assignment.source eq result),
      "recursive partition child must consume its complete exact slice")
    entry.consumer = Some(input -> assignment)
  }

  def of(component: Component, assignment: AssignmentStatement): Option[Read] =
    entries(component).find(_.assignment eq assignment).map { entry =>
      require(!entry.requiresConsumer || entry.consumer.exists { case (input, edge) =>
        input.isInput && (input.component.parent eq component) && input.hasOnlyOneStatement &&
          (input.head eq edge) && (edge.target eq input) && (edge.source eq entry.result)
      }, "recursive partition lost or replaced its exact child connection")
      val native = entry.nativeSource.asInstanceOf[Carrier]
      require(entry.source.getBitsWidth == entry.sourceDeclaration.default &&
        entry.result.getBitsWidth == entry.width.default &&
        ParameterizedWidth.expressionOf(entry.source).exists(_ eq entry.sourceDeclaration) &&
        ParameterizedWidth.expressionOf(entry.result).exists(_ eq entry.resultDeclaration) &&
        (native.input eq entry.source) && native.size == entry.width.default &&
        (entry.source.component eq component) && (entry.result.component eq component) &&
        (assignment.target eq entry.result) && (assignment.source eq entry.nativeSource) &&
        entry.result.hasOnlyOneStatement && (entry.result.head eq assignment), "recursive partition lost its exact native assignment")
      def owned(value: ElaborationIntegerExpression) = ElaborationProductDomain.owner(value,
        "recursive packed partition", value.sourceLocation) { (root, universe) =>
        ParameterizedStructure.exactAssignmentDomainOf(component, entry.assignment, root, universe,
          "recursive packed partition", value.sourceLocation).values
      }.get
      val offset = owned(entry.offset); val width = owned(entry.width); val total = owned(entry.sourceWidth)
      require(offset.minimum >= 0 && width.minimum > 0 &&
        total.nonNegativeDifference(offset.combine("+", width)), "recursive partition exceeds its exact source domain")
      entry
    }
}
