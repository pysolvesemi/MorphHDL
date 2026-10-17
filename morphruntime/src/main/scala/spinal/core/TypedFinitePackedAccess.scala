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
      val strideExpression: ElaborationIntegerExpression, val sourceWidth: ElaborationIntegerExpression,
      val countWidthProof: ElabBool, val nativeWidth: Int, val baseExpression: ElaborationIntegerExpression,
      val owner: ParameterizedStructuralLexicalOwner) {
    private[TypedFinitePackedAccess] val assignment = ParameterizedStructure.sliceAssignment(
      source, result, index.expression.sourceLocation)
    private[TypedFinitePackedAccess] val nativeAccess = assignment.source
    private[TypedFinitePackedAccess] val nativeScope = assignment.parentScope
    def matches(actualSource: BitVector, actualResult: BitVector,
        actualOffset: ElaborationIntegerExpression, actualWidth: ElaborationIntegerExpression): Boolean =
      (actualSource eq source) && (actualResult eq result) &&
        (actualOffset eq offset) && (actualWidth eq width) &&
        source.getBitsWidth == nativeWidth &&
        ElabInt.equivalentExpression(widthOf(source), sourceWidth) &&
        offset.generateIndex == index.expression.generateIndex &&
        offset.default == index.expression.default * strideExpression.default + baseExpression.default &&
        offset.minimum == index.expression.minimum * strideExpression.minimum + baseExpression.minimum &&
        offset.maximum == index.expression.maximum * strideExpression.maximum + baseExpression.maximum

    def owns(component: Component, block: ParameterizedStructuralBlock): Boolean =
      (owner.component eq component) && ParameterizedStructure.blockOfLexicalOwner(owner).exists(_ eq block) &&
        ParameterizedStructure.withLexicalOwnerDomain(owner) {
          ElabBool.projectedTruth(countWidthProof) == ElabBool.AlwaysTrue
        }
  }
  /** Width inference may encounter the native witness range before structural
    * rewriting. Only the exact issued range and its registered owner authorize
    * the symbolic word width; an equal-looking fixed slice has no authority.
    */
  def widthOf(component: Component, expression: Expression): Option[ElaborationIntegerExpression] = {
    val issued = reads(component).filter(_.nativeAccess eq expression)
    if (issued.isEmpty) return None
    def fail(): Nothing = ParameterizedVerilogException.fail("SPINAL-ELAB-FINITE-PACKED-EVIDENCE-MISMATCH",
      "finite packed native range lost its exact assignment, slice or owner")
    if (issued.size != 1) fail()
    val read = issued.head
    val records = ParameterizedStructure.regionsOf(component).flatMap(ParameterizedStructure.allBlocks)
      .flatMap(block => block.slices.filter(_.finitePackedAccess.exists(_ eq read)).map(block -> _))
    if (records.size != 1) fail()
    val (block, slice) = records.head
    val geometry = expression match {
      case range: BitVectorRangedAccessFixed => (range.source eq read.source) &&
        range.lo == read.offset.default && BigInt(range.hi) + 1 == read.offset.default + read.width.default
      case _ => false
    }
    if (!geometry || (slice.assignment ne read.assignment) ||
        (read.assignment.source ne expression) || (read.assignment.target ne read.result) ||
        (read.assignment.parentScope ne read.nativeScope) ||
        !slice.finiteIndexToken.exists(_ eq read.index.token) ||
        !read.matches(slice.source, slice.result, slice.offset, slice.width) || !read.owns(component, block)) fail()
    ParameterizedStructure.requireFiniteValueOwner(read.index, component, block)
    Some(read.width)
  }

  final case class Write(index: ElabFiniteIndex, source: Bits, view: Bits, stride: Int, width: Int,
      sourceWidth: Int, assignment: DataAssignmentStatement, var target: Expression, complete: Boolean,
      strideExpression: ElaborationIntegerExpression, widthExpression: ElaborationIntegerExpression,
      baseExpression: ElaborationIntegerExpression)
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

  def apply(index: ElabFiniteIndex, source: Bits, stride: Int, width: BitCount): Bits =
    applyExpressions(index, source, ElabInt.literal(stride).expression, ElabInt.literal(width.value).expression)

  def apply(index: ElabFiniteIndex, source: Bits, stride: ElabInt, width: ParameterizedBitCount): Bits =
    applyExpressions(index, source, stride.expression,
      ParameterizedWidth.validatedWidthExpression(width).getOrElse(ElabInt.literal(width.value).expression))

  def bit(index: ElabFiniteIndex, source: Bits, stride: Int, base: Int): Bool = {
    val selected = range(index, source, ElabInt.literal(stride), ElabInt.literal(base), ElabInt.literal(1))
    val result = selected.asBool
    result.compositeAssign = new Assignable {
      protected def assignFromImpl(that: AnyRef, target: AnyRef, kind: AnyRef)(implicit location: Location): Unit = {
        require(kind == DataAssign && (target eq result), "finite bit assignment must be whole-leaf data")
        selected := that.asInstanceOf[Bool].asBits
      }
      def getRealSourceNoRec: BaseType = source
    }
    result
  }

  def range(index: ElabFiniteIndex, source: Bits, stride: ElabInt, base: ElabInt, width: ElabInt): Bits =
    applyExpressions(index,source,stride.expression,width.expression,base.expression)

  private def applyExpressions(index: ElabFiniteIndex, source: Bits,
      strideExpression: ElaborationIntegerExpression, wordWidth: ElaborationIntegerExpression,
      baseExpression: ElaborationIntegerExpression = ElabInt.literal(0).expression): Bits = {
    val code = "SPINAL-PARAMETERIZED-VERILOG-STRUCTURAL-SLICE-DOMAIN-UNSUPPORTED"
    val projectedStride = ElabInt.projectExpression(strideExpression, "finite packed stride")
    val base = ElabInt.projectExpression(baseExpression, "finite packed base")
    ElabFiniteRange.requireCompleteSymbolicDomain(base,"finite packed base",code)
    require(base.minimum >= 0,"finite packed base must remain nonnegative")
    val projectedWord = ElabInt.projectExpression(wordWidth, "finite packed word width")
    ElabFiniteRange.requireCompleteSymbolicDomain(projectedStride, "finite packed stride", code)
    ElabFiniteRange.requireCompleteSymbolicDomain(projectedWord, "finite packed word width", code)
    require(projectedStride.minimum > 0 && projectedWord.minimum > 0,
      "finite packed access requires positive stride and width throughout its domain")
    require(projectedStride.maximum.isValidInt && projectedWord.maximum.isValidInt && base.maximum.isValidInt,
      "finite packed geometry exceeds native integer bounds")
    val stride = strideExpression.default.toInt
    val width = BitCount(wordWidth.default.toInt)
    val offset = Math.addExact(Math.multiplyExact(index.expression.default.toInt, stride),base.default.toInt)
    val result = source(offset, width)
    if (index.expression.generateIndex.isEmpty) return result
    ParameterizedStructure.requireActiveFiniteIndex(index)
    val offsetText = s"(${index.expression.verilog} * ${strideExpression.verilog})"
    val expression = index.expression.copy(verilog = if(base.minimum==0 && base.maximum==0) offsetText else s"($offsetText + ${baseExpression.verilog})",
      default = index.expression.default * strideExpression.default + base.default,
      minimum = index.expression.minimum * projectedStride.minimum + base.minimum,
      maximum = index.expression.maximum * projectedStride.maximum + base.maximum,
      parameters = (index.expression.parameters ++ strideExpression.parameters ++ baseExpression.parameters).distinct,
      parameterRoots = (index.expression.parameterRoots ++ strideExpression.parameterRoots ++ baseExpression.parameterRoots).distinct)
    val sourceWidth = widthOf(source)
    val projectedWidth = ElabInt.projectExpression(sourceWidth, "finite packed source width")
    val projectedCount = ElabInt.projectExpression(index.count, "finite packed loop count")
    if (ElaborationProductDomain.isRetained(projectedWidth))
      ElaborationProductDomain.requireInteger(projectedWidth, "finite packed source width")
    else ElabFiniteRange.requireCompleteSymbolicDomain(projectedWidth, "finite packed source width", code)
    ElabFiniteRange.requireCompleteSymbolicDomain(projectedCount, "finite packed loop count", code)
    if (!((source.component eq Component.current) ||
        (source.isIo && source.component != null && (source.component.parent eq Component.current))))
      ParameterizedVerilogException.fail("SPINAL-ELAB-FINITE-PACKED-SOURCE-OWNER-MISMATCH",
        "finite packed source must belong to its loop component or be an exact direct-child port")
    val proof = (ElabInt.fromExpression(projectedCount) - 1) * ElabInt.fromExpression(projectedStride) + ElabInt.fromExpression(base) + ElabInt.fromExpression(projectedWord) <=
      ElabInt.fromExpression(projectedWidth)
    if (projectedCount.minimum < 1 || ElabBool.projectedTruth(proof) != ElabBool.AlwaysTrue)
      ParameterizedVerilogException.fail(code,
        "finite packed slice must fit its source at every value of the authenticated loop count")
    // Keep the selected word's authored width on its exact native result.
    // Otherwise downstream operators infer the witness slice as a fixed width
    // before structural publication restores the retained indexed selection.
    ParameterizedWidth.attach(result, ParameterizedBitCount(width.value, None,
      projectedWord.sourceLocation, Some(projectedWord)))
    val read = new Read(index, source, result, expression, projectedWord, projectedStride, sourceWidth, proof, source.getBitsWidth, base,
      ParameterizedStructure.currentLexicalOwner("finite packed geometry"))
    Component.current.userCache.getOrElseUpdate(ReadKey, scala.collection.mutable.ArrayBuffer.empty[Read])
      .asInstanceOf[scala.collection.mutable.ArrayBuffer[Read]] += read
    val contiguous = ElabBool.projectedTruth(ElabInt.fromExpression(projectedWord)
      .elabEq(ElabInt.fromExpression(projectedStride))) == ElabBool.AlwaysTrue
    val complete = base.minimum == 0 && base.maximum == 0 && contiguous && ElabBool.projectedTruth(
      (ElabInt.fromExpression(projectedCount) * ElabInt.fromExpression(projectedStride)).elabEq(ElabInt.fromExpression(projectedWidth))) == ElabBool.AlwaysTrue
    ParameterizedStructure.recordSlice(source, result, expression, projectedWord,
      index.expression.sourceLocation, Some(read))
    result.compositeAssign = new Assignable {
      protected def assignFromImpl(that: AnyRef, target: AnyRef, kind: AnyRef)(implicit location: Location): Unit = {
        ParameterizedStructure.requireActiveFiniteIndex(index)
        if (!(((source.component eq Component.current) && !source.isInput) ||
            (source.isInput && source.component != null && (source.component.parent eq Component.current))))
          ParameterizedVerilogException.fail("SPINAL-ELAB-FINITE-PACKED-TARGET-OWNER-MISMATCH",
            "finite packed target must be writable in its loop component or an exact direct-child input")
        if (ElabBool.projectedTruth(ElabInt.fromExpression(projectedWord) <=
            ElabInt.fromExpression(projectedStride)) != ElabBool.AlwaysTrue)
          ParameterizedVerilogException.fail(code, "finite packed writes must be disjoint throughout their domain")
        require(kind == DataAssign && (target eq result), "finite packed assignment must write the complete selected slice")
        val (_, assignments) = ParameterizedVec.captureAssignments(source) {
          source(offset, width) := that.asInstanceOf[Bits]
        }
        require(assignments.size == 1, "finite packed write must retain exactly one native assignment")
        val assignment = assignments.head
        val records = result.component.userCache.getOrElseUpdate(Key,
          scala.collection.mutable.ArrayBuffer.empty[Write]).asInstanceOf[scala.collection.mutable.ArrayBuffer[Write]]
        records += Write(index, source, result, stride, width.value, source.getBitsWidth, assignment, assignment.target, complete, projectedStride, projectedWord, base)
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
      write.width == slice.width.default && slice.offset.default == write.index.expression.default * write.stride + write.baseExpression.default &&
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
          range.lo == write.index.expression.default * write.stride + write.baseExpression.default && range.hi == range.lo + write.width - 1
        case _ => false
      }
      require(geometry && write.source.getBitsWidth == write.sourceWidth,
        "finite packed write lost its exact source width or witness slice")
      val owners = ParameterizedStructure.regionsOf(component).flatMap(ParameterizedStructure.allBlocks)
        .filter(_.assignments.exists(_ eq assignment))
      require(owners.size == 1, "finite packed write lost its unique structural owner")
      ParameterizedStructure.requireFiniteValueOwner(write.index, component, owners.head)
      val offset = s"(${write.index.expression.verilog} * ${write.strideExpression.verilog})"
      val withBase = if(write.baseExpression.minimum==0 && write.baseExpression.maximum==0) offset
        else s"($offset + ${write.baseExpression.verilog})"
      s"${reference(write.source)}[$withBase +: ${write.widthExpression.verilog}]"
    }
}
