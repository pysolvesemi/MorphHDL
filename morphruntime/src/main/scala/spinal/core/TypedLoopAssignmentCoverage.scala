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
    block.slices.foreach { slice =>
      if (slice.offset.default == 0 && slice.offset.minimum == 0 &&
          slice.offset.generateIndex.contains(loop.indexName) &&
          slice.width.parameters.isEmpty && slice.width.minimum == slice.width.maximum &&
          ParameterizedProcess.isContiguous(slice, loop.indexName)) {
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
