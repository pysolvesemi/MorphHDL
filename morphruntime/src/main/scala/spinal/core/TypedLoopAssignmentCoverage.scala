package spinal.core

import spinal.core.internals._

/** Definite assignment for a retained loop, without changing its witness RTL
  * target or exempting a declaration from native latch/overlap checks.
  */
private[core] object TypedLoopAssignmentCoverage {
  def retain(component: Component, loop: ParameterizedStructure.StructuralFor,
      countProof: Option[ElabInt]): Unit = {
    val count = loop.count
    if (count.minimum < 0) return
    val root = loop.finiteIndexToken.flatMap(ParameterizedStructure.finitePredicateRoot(component, _))
    def visit(block: ParameterizedStructuralBlock, allowed: Option[Set[BigInt]]): Unit = {
    def wholeWordPartition(write: TypedFinitePackedAccess.Write): Boolean = {
      val parts = TypedFinitePackedAccess.writes(component).filter(w =>
        (w.source eq write.source) && (w.index.token eq write.index.token) &&
          (w.assignment.parentScope eq write.assignment.parentScope) &&
          (w.assignment.target eq w.target) && block.assignments.exists(_ eq w.assignment)).sortBy(_.baseExpression.default)
      def equal(a: ElaborationIntegerExpression, b: ElaborationIntegerExpression): Boolean =
        ElabBool.projectedTruth(ElabInt.fromExpression(a).elabEq(ElabInt.fromExpression(b))) == ElabBool.AlwaysTrue
      parts.nonEmpty && parts.forall(w => equal(w.strideExpression, write.strideExpression)) &&
        equal(parts.head.baseExpression, ElabInt.literal(0).expression) &&
        parts.sliding(2).forall(pair => pair.size < 2 || equal(
          (ElabInt.fromExpression(pair.head.baseExpression) + ElabInt.fromExpression(pair.head.widthExpression)).expression,
          pair.last.baseExpression)) &&
        equal((ElabInt.fromExpression(parts.last.baseExpression) + ElabInt.fromExpression(parts.last.widthExpression)).expression,
          write.strideExpression) &&
        equal((countProof.getOrElse(ElabInt.fromExpression(count)) * ElabInt.fromExpression(write.strideExpression)).expression,
          ParameterizedWidth.expressionOf(write.source).getOrElse(ElabInt.literal(write.source.getBitsWidth).expression))
    }
    // A subword write covers a set of disjoint intervals, never its bounding
    // interval. Keep native overlap/latch checks exact for fixed word geometry.
    TypedFinitePackedAccess.writes(component).filter { write =>
      !write.complete && loop.finiteIndexToken.exists(_ eq write.index.token) &&
        block.assignments.exists(_ eq write.assignment) &&
        (Seq(write.strideExpression, write.widthExpression, write.baseExpression)
          .forall(e => e.minimum == e.maximum) || wholeWordPartition(write))
    }.foreach { write =>
      val assignment = write.assignment
      assignment.target match {
        case native: RangedAssignmentFixed =>
          require((native eq write.target) && (native.out eq write.source) &&
            BigInt(native.lo) == write.index.expression.default * write.stride + write.baseExpression.default &&
            BigInt(native.hi) + 1 == BigInt(native.lo) + write.width,
            "subword coverage requires the exact authenticated native slice")
          val targetWidth = ParameterizedWidth.expressionOf(write.source)
            .getOrElse(ElabInt.literal(write.source.getBitsWidth).expression)
          val fullWords = ElabBool.projectedTruth(
            (countProof.getOrElse(ElabInt.fromExpression(count)) *
              ElabInt.fromExpression(write.strideExpression)).elabEq(ElabInt.fromExpression(targetWidth))) == ElabBool.AlwaysTrue
          val least = if (fullWords) count.default else count.minimum
          val most = if (fullWords) count.default else count.maximum
          require(least.isValidInt && most.isValidInt, "finite subword coverage exceeds native range")
          val scope = assignment.parentScope
          val source = native.out; val low = native.lo; val high = native.hi
          val covered = new RangedAssignmentFixed {
            out = source; lo = low; hi = high
            private def ranges(n: Int): Seq[AssignedRange] = {
              def registered(regions: Vector[ParameterizedStructure.StructuralRegion]): Boolean =
                regions.exists(r => (r eq loop) || r.blocks.exists(b => registered(b.regions)))
              if ((assignment.target ne this) || (out ne source) || lo != low || hi != high ||
                  (assignment.parentScope ne scope) || !block.assignments.exists(_ eq assignment) ||
                  !registered(ParameterizedStructure.regionsOf(component)) ||
                  (!Seq(write.strideExpression, write.widthExpression, write.baseExpression).forall(e => e.minimum == e.maximum) &&
                    !wholeWordPartition(write)))
                ParameterizedVerilogException.fail("SPINAL-TYPED-LOOP-COVERAGE-IDENTITY-MISMATCH",
                  "subword coverage lost its exact loop assignment or native target")
              (0 until n).filter(i => allowed.forall(_.contains(BigInt(i)))).map { i =>
                val start = Math.addExact(Math.multiplyExact(i, write.stride), write.baseExpression.default.toInt)
                val end = Math.addExact(start, write.width - 1)
                require(start >= 0 && end < source.getBitsWidth, "subword coverage escaped native target")
                AssignedRange(end, start)
              }
            }
            override def getMinAssignedRanges: Seq[AssignedRange] = ranges(least.toInt)
            override def getMaxAssignedRanges: Seq[AssignedRange] = ranges(most.toInt)
          }
          TypedFinitePackedAccess.retainCoverageTarget(component, assignment, covered)
          assignment.target = covered
        case _ =>
      }
    }
    block.slices.foreach { slice =>
      if (slice.offset.default == 0 && slice.offset.minimum == 0 &&
          slice.offset.generateIndex.contains(loop.indexName) &&
          ((slice.width.parameters.isEmpty && slice.width.minimum == slice.width.maximum &&
            ParameterizedProcess.isContiguous(slice, loop.indexName)) ||
            slice.finitePackedAccess.exists(read =>
              read.matches(slice.source, slice.result, slice.offset, slice.width) &&
              loop.finiteIndexToken.exists(_ eq read.index.token) &&
              TypedFinitePackedAccess.writes(component).exists(write =>
                (write.view eq slice.result) && write.complete &&
                block.assignments.exists(_ eq write.assignment))))) {
        val writes = block.assignments.filter(ParameterizedProcess.matchesTargetSlice(_, slice))
        writes.foreach { assignment => assignment.target match {
          case native: RangedAssignmentFixed =>
            val total = countProof.getOrElse(ElabInt.fromExpression(count)) * ElabInt.fromExpression(slice.width)
            val targetWidth = ParameterizedWidth.expressionOf(slice.source)
              .getOrElse(ElabInt.literal(slice.source.getBitsWidth).expression)
            // A full correlated range covers the native target width at its
            // witness. Otherwise only the prefix present at EVERY admitted
            // count is definite: a full witness must not hide a partial override.
            val full = ElabBool.projectedTruth(total.elabEq(ElabInt.fromExpression(targetWidth))) == ElabBool.AlwaysTrue
            val extent = if (full) BigInt(slice.source.getBitsWidth) else total.expression.minimum
            val possibleExtent = if (full) BigInt(slice.source.getBitsWidth)
              else total.expression.maximum.min(BigInt(slice.source.getBitsWidth))
            if (extent >= 0 && extent <= slice.source.getBitsWidth && extent.isValidInt) {
              val scope = assignment.parentScope
              val source = native.out
              val low = native.lo
              val high = native.hi
              val covered = new RangedAssignmentFixed {
                out = source
                lo = low
                hi = high
                private def coverage(size: BigInt): AssignedRange = {
                  def registered(regions: Vector[ParameterizedStructure.StructuralRegion]): Boolean =
                    regions.exists(region => (region eq loop) || region.blocks.exists(block => registered(block.regions)))
                  if ((assignment.target ne this) || (out ne source) || lo != low || hi != high ||
                      (assignment.parentScope ne scope) || !block.assignments.exists(_ eq assignment) ||
                      !block.slices.exists(_ eq slice) || !registered(ParameterizedStructure.regionsOf(component)))
                    ParameterizedVerilogException.fail("SPINAL-TYPED-LOOP-COVERAGE-IDENTITY-MISMATCH",
                      "typed loop coverage lost its exact registered assignment, scope or slice", slice.sourceLocation)
                  if (size == 0) AssignedRange()
                  else allowed match {
                    case None => AssignedRange(size.toInt - 1, 0)
                    case Some(indices) =>
                      val covered = indices.toVector.sorted.filter(i => i * slice.width.default < size)
                      // Native AssignedRange is contiguous. Never fill a gap in
                      // a truth set; retain only its first complete run.
                      if (covered.isEmpty) AssignedRange()
                      else {
                        val run = covered.zipWithIndex.takeWhile { case (i, n) => i == covered.head + n }.map(_._1)
                        AssignedRange(((run.last + 1) * slice.width.default - 1).toInt,
                          (run.head * slice.width.default).toInt)
                      }
                  }
                }
                override def getMinAssignedBits: AssignedRange = coverage(extent)
                override def getMaxAssignedBits: AssignedRange = coverage(possibleExtent)
              }
              TypedFinitePackedAccess.retainCoverageTarget(component, assignment, covered)
              assignment.target = covered
            }
          case _ =>
        }}
      }
    }
    block.regions.foreach {
      case branch: ParameterizedStructure.StructuralIf if branch.predicateDomain.exists(d => root.exists(_ eq d.root)) =>
        val domain = branch.predicateDomain.get
        visit(branch.whenTrue, Some(allowed.getOrElse(domain.universe) intersect domain.whenTrue))
        visit(branch.whenFalse, Some(allowed.getOrElse(domain.universe) intersect (domain.universe -- domain.whenTrue)))
      case _ =>
    }
    }
    visit(loop.body, None)
  }
}
