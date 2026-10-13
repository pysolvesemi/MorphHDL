package spinal.core

import spinal.core.internals._
import spinal.idslplugin.Location

/** Sized packed accesses whose native write edges retain their exact index. */
private[spinal] object TypedFinitePackedAccess {
  private object Key
  private object ReadKey
  def reads(component: Component): Vector[Read] = component.userCache.get(ReadKey)
    .map(_.asInstanceOf[scala.collection.mutable.ArrayBuffer[Read]].toVector).getOrElse(Vector.empty)
  private def widthOf(source: Bits): ElaborationIntegerExpression =
    ParameterizedVec.packedWidthExpressionOf(source).orElse(ParameterizedWidth.expressionOf(source))
      .getOrElse(ElabInt.literal(source.getBitsWidth).expression)

  /** Issued only by an active finite index. The exact pointwise proof joins
    * COUNT and packed width before the index is replaced by a native witness.
    */
  final class Read private[TypedFinitePackedAccess] (
      val index: ElabFiniteIndex, val source: Bits, val result: Bits,
      val offset: ElaborationIntegerExpression, val width: ElaborationIntegerExpression,
      val stride: Int, val sourceWidth: ElaborationIntegerExpression,
      val countWidthProof: ElabBool, val nativeWidth: Int,
      val owner: ParameterizedStructuralLexicalOwner) {
    def matches(actualSource: BitVector, actualResult: BitVector,
        actualOffset: ElaborationIntegerExpression, actualWidth: ElaborationIntegerExpression): Boolean =
      (actualSource eq source) && (actualResult eq result) &&
        (actualOffset eq offset) && (actualWidth eq width) &&
        source.getBitsWidth == nativeWidth &&
        ElabInt.equivalentExpression(widthOf(source), sourceWidth) &&
        offset.generateIndex == index.expression.generateIndex &&
        offset.default == index.expression.default * stride &&
        offset.minimum == index.expression.minimum * stride &&
        offset.maximum == index.expression.maximum * stride

    def owns(component: Component, block: ParameterizedStructuralBlock): Boolean =
      (owner.component eq component) && ParameterizedStructure.blockOfLexicalOwner(owner).exists(_ eq block) &&
        ParameterizedStructure.withLexicalOwnerDomain(owner) {
          ElabBool.projectedTruth(countWidthProof) == ElabBool.AlwaysTrue
        }
  }
  final case class Write(index: ElabFiniteIndex, source: Bits, view: Bits, stride: Int, width: Int,
      sourceWidth: Int, assignment: DataAssignmentStatement, var target: Expression, complete: Boolean)
  def writes(component: Component): Vector[Write] = component.userCache.get(Key)
    .map(_.asInstanceOf[scala.collection.mutable.ArrayBuffer[Write]].toVector).getOrElse(Vector.empty)

  def completeDriver(component: Component, source: Bits, owner: ParameterizedStructuralBlock): Boolean = {
    if (source.component == null || !((source.component eq component) ||
      (source.isInput && (source.component.parent eq component)))) return false
    val candidates = writes(component).filter(w => w.source eq source)
    if (candidates.size != 1) return false
    val write = candidates.head
    val drivers = scala.collection.mutable.ArrayBuffer.empty[DataAssignmentStatement]
    write.source.foreachStatements { case a: DataAssignmentStatement => drivers += a; case _ => }
    val exact = write.source.isComb && write.width == write.stride && write.complete &&
      drivers.size == 1 && (drivers.head eq write.assignment) &&
      (write.assignment.target eq write.target) && (write.assignment.finalTarget eq write.source) &&
      (write.assignment.parentScope eq component.dslBody) && owner.assignments.exists(_ eq write.assignment)
    if (!exact) return false
    ParameterizedStructure.requireFiniteValueOwner(write.index, component, owner)
    // The write must be in the loop body itself, not a conditional descendant.
    def body(regions: Vector[ParameterizedStructure.StructuralRegion]): Boolean = regions.exists {
      case loop: ParameterizedStructure.StructuralFor if loop.finiteIndexToken.exists(_ eq write.index.token) => loop.body eq owner
      case region => region.blocks.exists(block => body(block.regions))
    }
    body(ParameterizedStructure.regionsOf(component))
  }

  def moduleDeclarations(component: Component, blocks: Vector[ParameterizedStructuralBlock]): Vector[BaseType] =
    writes(component).map(_.source).distinct.filter(source => !(source.isIo && (source.component eq component)) &&
      !blocks.exists(_.declarations.exists(_ eq source)) &&
      blocks.exists(block => completeDriver(component, source, block)))

  private[core] def retainCoverageTarget(component: Component, assignment: DataAssignmentStatement,
      target: Expression): Unit = writes(component).filter(_.assignment eq assignment).foreach { write =>
    require(write.target eq assignment.target, "finite packed write changed before exact coverage replacement")
    write.target = target
  }

  def apply(index: ElabFiniteIndex, source: Bits, stride: Int, width: BitCount): Bits = {
    require(stride > 0 && width.value > 0, "finite packed access requires positive stride and width")
    val offset = index.expression.default.toInt * stride
    val result = source(offset, width)
    if (index.expression.generateIndex.isEmpty) return result
    ParameterizedStructure.requireActiveFiniteIndex(index)
    val expression = index.expression.copy(verilog = s"(${index.expression.verilog} * $stride)",
      default = index.expression.default * stride, minimum = index.expression.minimum * stride,
      maximum = index.expression.maximum * stride)
    val sourceWidth = widthOf(source)
    val projectedWidth = ElabInt.projectExpression(sourceWidth, "finite packed source width")
    val projectedCount = ElabInt.projectExpression(index.count, "finite packed loop count")
    val code = "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SLICE-DOMAIN-UNSUPPORTED"
    ElabFiniteRange.requireCompleteSymbolicDomain(projectedWidth, "finite packed source width", code)
    ElabFiniteRange.requireCompleteSymbolicDomain(projectedCount, "finite packed loop count", code)
    if (!((source.component eq Component.current) ||
        (source.isIo && source.component != null && (source.component.parent eq Component.current))))
      ParameterizedVerilogException.fail("SPINAL-ELAB-FINITE-PACKED-SOURCE-OWNER-MISMATCH",
        "finite packed source must belong to its loop component or be an exact direct-child port")
    val proof = (ElabInt.fromExpression(projectedCount) - 1) * stride + width.value <=
      ElabInt.fromExpression(projectedWidth)
    if (projectedCount.minimum < 1 || ElabBool.projectedTruth(proof) != ElabBool.AlwaysTrue)
      ParameterizedVerilogException.fail(code,
        "finite packed slice must fit its source at every value of the authenticated loop count")
    val wordWidth = ElabInt.literal(width.value).expression
    val read = new Read(index, source, result, expression, wordWidth, stride, sourceWidth, proof, source.getBitsWidth,
      ParameterizedStructure.currentLexicalOwner("finite packed geometry"))
    Component.current.userCache.getOrElseUpdate(ReadKey, scala.collection.mutable.ArrayBuffer.empty[Read])
      .asInstanceOf[scala.collection.mutable.ArrayBuffer[Read]] += read
    val complete = width.value == stride && ElabBool.projectedTruth(
      (ElabInt.fromExpression(projectedCount) * stride).elabEq(ElabInt.fromExpression(projectedWidth))) == ElabBool.AlwaysTrue
    ParameterizedStructure.recordSlice(source, result, expression, wordWidth,
      index.expression.sourceLocation, Some(read))
    result.compositeAssign = new Assignable {
      protected def assignFromImpl(that: AnyRef, target: AnyRef, kind: AnyRef)(implicit location: Location): Unit = {
        ParameterizedStructure.requireActiveFiniteIndex(index)
        if (!(((source.component eq Component.current) && !source.isInput) ||
            (source.isInput && source.component != null && (source.component.parent eq Component.current))))
          ParameterizedVerilogException.fail("SPINAL-ELAB-FINITE-PACKED-TARGET-OWNER-MISMATCH",
            "finite packed target must be writable in its loop component or an exact direct-child input")
        require(kind == DataAssign && (target eq result), "finite packed assignment must write the complete selected slice")
        val (_, assignments) = ParameterizedVec.captureAssignments(source) {
          source(offset, width) := that.asInstanceOf[Bits]
        }
        require(assignments.size == 1, "finite packed write must retain exactly one native assignment")
        val assignment = assignments.head
        val records = result.component.userCache.getOrElseUpdate(Key,
          scala.collection.mutable.ArrayBuffer.empty[Write]).asInstanceOf[scala.collection.mutable.ArrayBuffer[Write]]
        records += Write(index, source, result, stride, width.value, source.getBitsWidth, assignment, assignment.target, complete)
      }
      def getRealSourceNoRec: BaseType = source
    }
    result
  }

  private[spinal] def prunedWriteOnly(component: Component, slice: ParameterizedStructure.StructuralSlice,
      live: java.util.IdentityHashMap[Statement, java.lang.Boolean]): Boolean = {
    if (live.containsKey(slice.assignment)) return false
    val matches = writes(component).filter(_.view eq slice.result)
    if (matches.isEmpty) return false
    require(matches.forall(write => (write.source eq slice.source) &&
      write.width == slice.width.default && slice.offset.default == write.index.expression.default * write.stride &&
      live.containsKey(write.assignment) && (write.assignment.target eq write.target)),
      "write-only finite slice lost its exact write edge")
    var read = false
    def visit(expression: Expression): Unit = {
      if (expression eq slice.result) read = true
      else expression.foreachExpression(visit)
    }
    component.dslBody.walkStatements {
      case assignment: DataAssignmentStatement => visit(assignment.source)
      case _ =>
    }
    require(!read, "pruned finite slice still has a live read")
    true
  }

  def target(component: Component, assignment: AssignmentStatement, reference: BaseType => String): Option[String] =
    writes(component).find(_.assignment eq assignment).map { write =>
      require(assignment.finalTarget.eq(write.source) && assignment.target.eq(write.target),
        "finite packed write lost its exact target")
      val geometry = assignment.target match {
        case range: RangedAssignmentFixed => (range.out eq write.source) &&
          range.lo == write.index.expression.default * write.stride && range.hi == range.lo + write.width - 1
        case _ => false
      }
      require(geometry && write.source.getBitsWidth == write.sourceWidth,
        "finite packed write lost its exact source width or witness slice")
      val owners = ParameterizedStructure.regionsOf(component).flatMap(ParameterizedStructure.allBlocks)
        .filter(_.assignments.exists(_ eq assignment))
      require(owners.size == 1, "finite packed write lost its unique structural owner")
      ParameterizedStructure.requireFiniteValueOwner(write.index, component, owners.head)
      s"${reference(write.source)}[(${write.index.expression.verilog} * ${write.stride}) +: ${write.width}]"
    }
}
